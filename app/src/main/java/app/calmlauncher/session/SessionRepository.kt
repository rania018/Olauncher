package app.calmlauncher.session

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.MutablePreferences
import app.calmlauncher.CalmGraph
import app.calmlauncher.data.CalmDatabase
import app.calmlauncher.data.ExemptAppEntity
import app.calmlauncher.data.ExemptCategory
import app.calmlauncher.data.ExemptSource
import app.calmlauncher.data.Mappers
import app.calmlauncher.data.SessionEntity
import app.calmlauncher.data.SettingsStore
import app.calmlauncher.data.TrackedAppEntity
import app.calmlauncher.engine.AppKey
import app.calmlauncher.engine.Config
import app.calmlauncher.engine.Decision
import app.calmlauncher.engine.Effect
import app.calmlauncher.engine.EndReason
import app.calmlauncher.engine.EventType
import app.calmlauncher.engine.Exemptions
import app.calmlauncher.engine.RulesEngine
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.engine.SessionMachine
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.engine.SessionState
import app.calmlauncher.engine.TrackedApp
import app.calmlauncher.engine.UnplannedOpens
import app.calmlauncher.engine.Wake
import app.calmlauncher.engine.session
import app.calmlauncher.enforce.ForegroundProbe
import app.calmlauncher.exempt.DefaultExemptions
import app.calmlauncher.onboarding.HealthLine
import app.calmlauncher.onboarding.PermissionStatus
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.calmlauncher.util.logEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps the session state and everything the rules engine needs in memory, and writes changes
 * to the database.
 *
 * Threading: the state, the caches and [dispatch] are only touched on the main thread, one
 * event at a time, so no locks are needed. Database reads and writes run on CalmGraph's single
 * database thread, in order. The first load happens in the background when this object is
 * created; until it finishes, launches fail open (they are not intercepted).
 *
 * The tracked and exempt lists are cached here so `RulesEngine.decide` costs well under 5 ms
 * and never touches the disk while the user taps an app (plan.md performance goals).
 */
class SessionRepository(context: Context, private val db: CalmDatabase, private val settingsStore: SettingsStore) {

    private val app = context.applicationContext

    private class Loaded(
        val tracked: List<TrackedAppEntity>,
        val exempt: List<ExemptAppEntity>,
        val sessions: List<SessionEntity>,
        val settings: SettingsStore.Snapshot,
    )

    @Volatile
    private var loaded: Loaded? = null
    private val loadLatch = CountDownLatch(1)
    private var applied = false
    private val onLoaded = ArrayList<() -> Unit>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var tracked: Map<AppKey, TrackedApp> = emptyMap()
        private set
    var exemptRows: Map<String, ExemptAppEntity> = emptyMap()
        private set
    var exemptions: Exemptions = Exemptions()
        private set
    var settings: SettingsStore.Snapshot = SettingsStore.Snapshot(Config(), TrackedApp.DEFAULT_PRESETS, TrackedApp.DEFAULT_COOLDOWN_MIN, 0, null, emptySet())
        private set
    val config: Config get() = settings.config

    /** The current session state. Changed only by [dispatch]. */
    var state: SessionState = SessionState.Idle
        private set

    /** Active and recently ended sessions: the rules engine's history (cooldowns, running check). */
    private val history = ArrayList<SessionRecord>()

    private var lastDefaultsRefresh = 0L

    /**
     * Timer callbacks the engine asked for (warning, deadline, grace check). SessionService arms
     * them; the engine stays the only place that decides *when*.
     */
    private val wakeups = HashMap<Wake, Long>()

    fun wakeups(): Map<Wake, Long> = HashMap(wakeups)

    /** A wakeup fired: forget it so it is not armed again. */
    fun consumeWakeup(why: Wake) {
        wakeups.remove(why)
    }

    init {
        load()
    }

    // ------------------------------------------------------------------------------------ loading

    private fun load() {
        CalmGraph.onDatabaseThread { db ->
            loaded = try {
                Loaded(
                    db.trackedApps().all(),
                    db.exemptApps().all(),
                    db.sessions().recent(System.currentTimeMillis() - HISTORY_MS),
                    settingsStore.readBlocking(),
                )
            } catch (t: Throwable) {
                // Fail open: with nothing loaded, nothing is tracked.
                CalmLog.e("first load failed", t)
                Loaded(emptyList(), emptyList(), emptyList(), settings)
            } finally {
                loadLatch.countDown()
            }
            CalmGraph.main.post { applyLoaded() }
        }
    }

    /**
     * Main thread. Makes sure the first load has been applied, waiting at most [timeoutMs].
     * Returns false if it is still not ready; callers must then fail open.
     */
    fun ensureLoaded(timeoutMs: Long): Boolean {
        if (applied) return true
        if (!loadLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        applyLoaded()
        return applied
    }

    val isLoaded: Boolean get() = applied

    /** Main thread. Runs [action] now if loaded, otherwise right after the first load. */
    fun whenLoaded(action: () -> Unit) {
        if (applied) action() else onLoaded += action
    }

    private fun applyLoaded() {
        if (applied) return
        val l = loaded ?: return
        applied = true
        settings = l.settings
        setTrackedCache(l.tracked.map(Mappers::toTrackedApp))
        setExemptCache(l.exempt)
        history.clear()
        history.addAll(l.sessions.map(Mappers::toRecord))
        state = rebuildState(System.currentTimeMillis())
        CalmLog.i("loaded: ${tracked.size} tracked, ${exemptRows.size} exempt, state ${state::class.simpleName}")
        dispatch(SessionEvent.Reconcile(Apps.bootedAt()))
        refreshDefaultExemptions(force = true)
        val pending = onLoaded.toList()
        onLoaded.clear()
        pending.forEach { action -> failOpen("SessionRepository.whenLoaded") { action() } }
    }

    /** After a process restart the state comes back from the database. */
    private fun rebuildState(now: Long): SessionState {
        val active = history.filter { it.isActive }.maxByOrNull { it.startedAt }
        if (active != null) {
            val cooldown = cooldownMinFor(active.key)
            val lead = config.warningLeadSec * 1000L
            val warned = active.declaredMs > lead && now >= active.deadlineAt - lead
            return if (warned) SessionState.Warning(active, cooldown) else SessionState.Running(active, cooldown)
        }
        val lastExpired = history.filter { it.endReason == EndReason.EXPIRED }.maxByOrNull { it.endedAt ?: 0 }
        if (lastExpired != null) {
            val until = RulesEngine.cooldownUntil(lastExpired.key, history, cooldownMinFor(lastExpired.key), now)
            if (until != null && now < until) return SessionState.Cooldown(lastExpired.key, until)
        }
        return SessionState.Idle
    }

    // ----------------------------------------------------------------------------------- deciding

    fun cooldownMinFor(key: AppKey): Int = tracked[key]?.cooldownMin ?: settings.defaultCooldownMin

    /** What happens when [key] is opened from the launcher. Pure and in memory. */
    fun decide(key: AppKey, now: Long = System.currentTimeMillis()): Decision =
        RulesEngine.decide(key, now, tracked, exemptions, history, config)

    fun isExempt(pkg: String): Boolean = exemptions.isExempt(pkg)

    /**
     * The launcher wants to open [key] and the rules said [decision] (not Allow). Returns true if
     * our prompt is now on screen; false means the caller must let upstream open the app.
     */
    fun requestLaunch(key: AppKey, decision: Decision): Boolean {
        dispatch(SessionEvent.LaunchRequested(key, decision, cooldownMinFor(key)))
        val s = state
        return s is SessionState.Prompting && s.key == key
    }

    /** Called when our prompt could not be started: go back to where we were (fail open). */
    fun promptFailed() {
        val s = state
        if (s is SessionState.Prompting) state = s.previous
    }

    // ---------------------------------------------------------------------------------- the machine

    /** Main thread only. Feeds [event] to the state machine and carries out the effects. */
    fun dispatch(event: SessionEvent) {
        if (!applied) {
            onLoaded += { dispatch(event) }
            return
        }
        val now = System.currentTimeMillis()
        val before = state
        val t = SessionMachine.reduce(state, event, now, config)
        state = t.state
        if (before != state || t.effects.isNotEmpty()) {
            CalmLog.i("$event: ${before::class.simpleName} -> ${state::class.simpleName} ${t.effects.map { it::class.simpleName }}")
        }
        for (effect in t.effects) {
            failOpen("effect ${effect::class.simpleName}") { apply(effect) }
        }
        listeners.forEach { listener -> failOpen("SessionRepository.listener") { listener() } }
    }

    /** Posts [event] to the main thread, so effects never re-enter [dispatch] while it runs. */
    fun post(event: SessionEvent) {
        CalmGraph.main.post { failOpen("SessionRepository.post") { dispatch(event) } }
    }

    private fun apply(effect: Effect) {
        when (effect) {
            is Effect.PersistSession -> {
                remember(effect.rec)
                val entity = Mappers.toEntity(effect.rec)
                CalmGraph.onDatabaseThread { it.sessions().saveActive(entity) }
            }

            is Effect.EndSession -> {
                remember(effect.rec)
                val entity = Mappers.toEntity(effect.rec)
                CalmGraph.onDatabaseThread { it.sessions().end(entity) }
            }

            is Effect.Log -> {
                logEvent(effect.type, effect.key?.pkg, effect.detail)
                if (effect.type == EventType.SERVICE_INTERRUPTED) setHealthNotice(HealthLine.NOTICE_INTERRUPTED)
            }

            is Effect.ScheduleAt -> wakeups[effect.why] = effect.at
            is Effect.CancelSchedule -> wakeups.remove(effect.why)

            Effect.StopCountdown -> {
                wakeups.clear()
                EffectRunner.run(app, effect)
            }

            else -> EffectRunner.run(app, effect)
        }
    }

    private fun remember(rec: SessionRecord) {
        val i = history.indexOfFirst { it.key == rec.key && it.startedAt == rec.startedAt }
        if (i >= 0) history[i] = rec else history += rec
        val cutoff = System.currentTimeMillis() - HISTORY_MS
        history.removeAll { it.endedAt != null && it.endedAt!! < cutoff }
    }

    /** [listener] runs on the main thread after every state change (the session service uses it). */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    // ------------------------------------------------------------------------- tracked and exempt

    private fun setTrackedCache(apps: List<TrackedApp>) {
        tracked = apps.associateBy { it.key }
    }

    private fun setExemptCache(rows: Collection<ExemptAppEntity>) {
        exemptRows = rows.associateBy { it.pkg }
        exemptions = Exemptions(
            defaults = rows.filter { it.source == ExemptSource.DEFAULT }.map { it.pkg }.toSet(),
            user = rows.filter { it.source == ExemptSource.USER }.map { it.pkg }.toSet(),
        )
    }

    fun setTracked(key: AppKey, on: Boolean) {
        if (on) {
            saveTracked(TrackedApp.of(key, settings.defaultPresets, settings.defaultCooldownMin))
        } else {
            setTrackedCache((tracked - key).values.toList())
            CalmGraph.onDatabaseThread { it.trackedApps().delete(key.pkg, key.userSerial) }
        }
    }

    fun saveTracked(app: TrackedApp) {
        setTrackedCache((tracked + (app.key to app)).values.toList())
        val entity = Mappers.toEntity(app, System.currentTimeMillis())
        CalmGraph.onDatabaseThread { it.trackedApps().upsert(entity) }
    }

    /** Makes [pkg] exempt (source USER), unless it already is. */
    fun addExempt(pkg: String, category: ExemptCategory? = null) {
        if (exemptRows.containsKey(pkg)) return
        val row = ExemptAppEntity(pkg, ExemptSource.USER, category, System.currentTimeMillis())
        setExemptCache(exemptRows.values + row)
        CalmGraph.onDatabaseThread { it.exemptApps().upsert(row) }
    }

    /**
     * Removes [pkg] from the exempt list. SYSTEM defaults (dialer, SMS, Settings) can never be
     * removed, so the default list never becomes empty. A removed default is remembered so it is
     * not added back by the next resolution. Returns false if the app must stay exempt.
     */
    fun removeExempt(pkg: String): Boolean {
        val row = exemptRows[pkg] ?: return true
        if (row.category == ExemptCategory.SYSTEM) return false
        setExemptCache(exemptRows.values.filter { it.pkg != pkg })
        val wasDefault = row.source == ExemptSource.DEFAULT
        if (wasDefault) settings = settings.copy(removedDefaults = settings.removedDefaults + pkg)
        val removed = settings.removedDefaults
        CalmGraph.onDatabaseThread {
            it.exemptApps().delete(pkg)
            if (wasDefault) settingsStore.editBlocking { p -> p[SettingsStore.Keys.REMOVED_DEFAULT_EXEMPT] = removed.joinToString(",") }
        }
        return true
    }

    /** Re-resolves the default exempt apps (dialer, SMS, maps...) in the background. */
    fun refreshDefaultExemptions(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDefaultsRefresh < DEFAULTS_REFRESH_MS) return
        lastDefaultsRefresh = now
        val removed = settings.removedDefaults
        CalmGraph.loadThen({ db ->
            val rows = DefaultExemptions.rows(DefaultExemptions.resolve(app), removed, now)
            db.exemptApps().insertIfAbsent(rows)
            db.exemptApps().all()
        }) { rows -> setExemptCache(rows) }
    }

    // ------------------------------------------------------------------------------------ settings

    /** Saves settings in the background and applies them here once written. */
    fun editSettings(block: (MutablePreferences) -> Unit, then: () -> Unit = {}) {
        CalmGraph.loadThen({ _ ->
            settingsStore.editBlocking(block)
            settingsStore.readBlocking()
        }) { snapshot ->
            settings = snapshot
            then()
        }
    }

    fun setHealthNotice(code: String?) {
        val notice = code?.let { SettingsStore.HealthNotice(it, System.currentTimeMillis()) }
        settings = settings.copy(healthNotice = notice)
        CalmGraph.onDatabaseThread {
            settingsStore.editBlocking { p ->
                if (notice == null) p.remove(SettingsStore.Keys.LAST_HEALTH_NOTICE)
                else p[SettingsStore.Keys.LAST_HEALTH_NOTICE] = SettingsStore.encodeNotice(notice)
            }
        }
    }

    fun markOnboardingShown() {
        if (settings.onboardingVersion >= SettingsStore.ONBOARDING_CURRENT) return
        settings = settings.copy(onboardingVersion = SettingsStore.ONBOARDING_CURRENT)
        CalmGraph.onDatabaseThread {
            settingsStore.editBlocking { p -> p[SettingsStore.Keys.ONBOARDING_VERSION] = SettingsStore.ONBOARDING_CURRENT }
        }
    }

    // ------------------------------------------------------------------------------ unplanned opens

    /**
     * Counts tracked apps that came to the front without a session since the launcher was last in
     * front (T065): one usage-history query per launcher resume, no polling. Main-profile apps only,
     * because usage history covers the current profile.
     */
    fun countUnplannedOpens() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !PermissionStatus.usageAccess(app)) return
        val now = System.currentTimeMillis()
        val mySerial = Apps.mySerial(app)
        val trackedPkgs = tracked.keys.filter { it.userSerial == mySerial }.map { it.pkg }
            .filterNot { exemptions.isExempt(it) }.toSet()
        CalmGraph.onDatabaseThread { db ->
            val last = settingsStore.lastLauncherResumeBlocking()
            settingsStore.editBlocking { p -> p[SettingsStore.Keys.LAST_LAUNCHER_RESUME] = now }
            if (last == null || trackedPkgs.isEmpty()) return@onDatabaseThread
            val since = maxOf(last, now - UNPLANNED_WINDOW_MS)
            val sessions = db.sessions().recent(since).map(Mappers::toRecord)
            val resumes = ForegroundProbe.resumedEvents(app, since, now)
            UnplannedOpens.find(resumes, trackedPkgs, sessions, now).forEach {
                logEvent(EventType.UNPLANNED_OPEN, it.pkg, at = it.at)
            }
        }
    }

    /** The session state behind any open prompt. */
    val sessionState: SessionState get() = state.session()

    companion object {
        /** How much session history is kept in memory: more than the longest cooldown. */
        const val HISTORY_MS = 2 * 60 * 60 * 1000L
        const val DEFAULTS_REFRESH_MS = 10 * 60 * 1000L
        const val UNPLANNED_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
