package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.Allow
import app.calmlauncher.engine.Decision.AskDuration
import app.calmlauncher.engine.Decision.Blocked
import app.calmlauncher.engine.Effect.CancelSchedule
import app.calmlauncher.engine.Effect.DeferUntilClear
import app.calmlauncher.engine.Effect.EndSession
import app.calmlauncher.engine.Effect.GoHome
import app.calmlauncher.engine.Effect.HideCard
import app.calmlauncher.engine.Effect.LaunchApp
import app.calmlauncher.engine.Effect.Log
import app.calmlauncher.engine.Effect.PersistSession
import app.calmlauncher.engine.Effect.ProbeForegroundThenShowCard
import app.calmlauncher.engine.Effect.ScheduleAt
import app.calmlauncher.engine.Effect.ShowBlocked
import app.calmlauncher.engine.Effect.ShowCard
import app.calmlauncher.engine.Effect.ShowPrompt
import app.calmlauncher.engine.Effect.ShowWarning
import app.calmlauncher.engine.Effect.StartCountdown
import app.calmlauncher.engine.Effect.StopCountdown
import app.calmlauncher.engine.SessionEvent.AppUninstalled
import app.calmlauncher.engine.SessionEvent.CardTimedOut
import app.calmlauncher.engine.SessionEvent.CardUnavailable
import app.calmlauncher.engine.SessionEvent.Cancelled
import app.calmlauncher.engine.SessionEvent.DurationChosen
import app.calmlauncher.engine.SessionEvent.EndedByUser
import app.calmlauncher.engine.SessionEvent.ExtendStarted
import app.calmlauncher.engine.SessionEvent.Extended
import app.calmlauncher.engine.SessionEvent.ForegroundIsOther
import app.calmlauncher.engine.SessionEvent.ForegroundIsTracked
import app.calmlauncher.engine.SessionEvent.ForegroundUnknown
import app.calmlauncher.engine.SessionEvent.GraceElapsed
import app.calmlauncher.engine.SessionEvent.GuardCleared
import app.calmlauncher.engine.SessionEvent.InCallOrLocked
import app.calmlauncher.engine.SessionEvent.LaunchRequested
import app.calmlauncher.engine.SessionEvent.LauncherPaused
import app.calmlauncher.engine.SessionEvent.LauncherResumed
import app.calmlauncher.engine.SessionEvent.OverrideCompleted
import app.calmlauncher.engine.SessionEvent.OverrideReset
import app.calmlauncher.engine.SessionEvent.OverrideStarted
import app.calmlauncher.engine.SessionEvent.Reconcile
import app.calmlauncher.engine.SessionEvent.Tick
import app.calmlauncher.engine.SessionEvent.WentHome
import app.calmlauncher.engine.SessionState.Cooldown
import app.calmlauncher.engine.SessionState.Expired
import app.calmlauncher.engine.SessionState.Idle
import app.calmlauncher.engine.SessionState.Prompting
import app.calmlauncher.engine.SessionState.Running
import app.calmlauncher.engine.SessionState.Warning

/**
 * The session state machine (data-model.md "State transitions"). It is a pure function: the
 * same state, event, time and config always give the same transition, so every row of the table
 * is a plain JVM unit test. Events that make no sense in the current state are ignored (the state
 * stays the same and nothing happens), which is the fail-open outcome.
 */
object SessionMachine {

    /** A deadline missed by more than this means the timer did not run (T059). */
    const val INTERRUPTED_AFTER_MS = 60_000L

    /** Pure. Never throws: on error returns Transition(state, listOf(Effect.Log(ERROR))). */
    fun reduce(state: SessionState, event: SessionEvent, now: Long, cfg: Config): Transition {
        var error: Throwable? = null
        return failOpen<Transition?>(null, onError = { error = it }) {
            step(state, event, now, cfg.sanitized())
        } ?: Transition(state, listOf(Log(EventType.ERROR, null, error?.javaClass?.simpleName)))
    }

    private fun step(state: SessionState, event: SessionEvent, now: Long, cfg: Config): Transition {
        // While a prompt is open, the session behind it (if any) keeps living: every event that
        // is not about the prompt itself goes to the previous state.
        if (state is Prompting && !isPromptEvent(event)) {
            val inner = step(state.previous, event, now, cfg)
            return Transition(state.copy(previous = inner.state), inner.effects)
        }
        return when (event) {
            is LaunchRequested -> launchRequested(state, event, now)
            Cancelled -> cancelled(state)
            is DurationChosen -> durationChosen(state, event.minutes, now, cfg)
            Tick -> tick(state, now, cfg)
            EndedByUser -> endedByUser(state, now)
            ForegroundIsTracked -> foregroundIsTracked(state, cfg)
            ForegroundIsOther, ForegroundUnknown -> expiredQuietly(state)
            CardUnavailable -> cardUnavailable(state)
            InCallOrLocked -> inCallOrLocked(state)
            GuardCleared -> guardCleared(state)
            WentHome -> leaveCard(state, now, goHome = true, timedOut = false)
            CardTimedOut -> leaveCard(state, now, goHome = false, timedOut = true)
            ExtendStarted -> extendStarted(state, now, cfg)
            is Extended -> extended(state, event.minutes, now, cfg)
            OverrideStarted -> overrideStarted(state, now)
            OverrideReset -> overrideReset(state)
            OverrideCompleted -> overrideCompleted(state, now, cfg)
            LauncherResumed -> launcherResumed(state, now, cfg)
            LauncherPaused -> launcherPaused(state)
            is GraceElapsed -> graceElapsed(state, event.stillOnLauncher, now)
            is Reconcile -> reconcile(state, event.bootedAt, now, cfg)
            is AppUninstalled -> appUninstalled(state, event.key, now)
        }
    }

    /** Events that belong to an open prompt rather than to the session behind it. */
    private fun isPromptEvent(event: SessionEvent): Boolean = when (event) {
        is LaunchRequested, Cancelled, is DurationChosen, OverrideStarted, OverrideReset, OverrideCompleted,
        LauncherResumed, is AppUninstalled -> true
        else -> false
    }

    // ------------------------------------------------------------------------------------ launch

    private fun launchRequested(state: SessionState, e: LaunchRequested, now: Long): Transition {
        val outside = e.source == LaunchSource.OUTSIDE
        // More window events of the app we are already asking about must not restart the prompt
        // or its override wait (invariant 10).
        if (outside && state is Prompting && state.key == e.key) return Transition(state)
        // A new launch replaces an open prompt; what was running behind it stays the same.
        val base = if (state is Prompting) state.previous else state
        return when (val d = e.decision) {
            is Allow -> {
                val t = when (base) {
                    is Running -> if (base.rec.key == e.key) clearGrace(base) else Transition(base)
                    is Warning -> if (base.rec.key == e.key) clearGrace(base) else Transition(base)
                    else -> Transition(base)
                }
                // An app opened from outside is already on screen: never relaunch it (invariant 7).
                Transition(t.state, if (outside) t.effects else t.effects + LaunchApp(e.key))
            }

            is AskDuration -> Transition(
                Prompting(e.key, e.cooldownMin, base, source = e.source),
                listOf(ShowPrompt(e.key, d.presetsMin, d.customMaxMin)),
            )

            is Blocked -> Transition(
                Prompting(e.key, e.cooldownMin, base, blockedUntil = d.until, source = e.source),
                listOf(ShowBlocked(e.key, d.until), Log(EventType.COOLDOWN_SHOWN, e.key, sourceDetail(e.source))),
            )
        }
    }

    /** `event_log.detail` for prompts of an app opened from outside (data-model.md); null otherwise. */
    private fun sourceDetail(source: LaunchSource): String? =
        if (source == LaunchSource.OUTSIDE) "src=outside" else null

    private fun cancelled(state: SessionState): Transition {
        if (state !is Prompting) return Transition(state)
        val log = if (state.isBlocked) emptyList() else listOf(Log(EventType.PROMPT_CANCELLED, state.key, sourceDetail(state.source)))
        return Transition(state.previous, log)
    }

    private fun durationChosen(state: SessionState, minutes: Int, now: Long, cfg: Config): Transition {
        if (state !is Prompting || state.isBlocked) return Transition(state)
        if (minutes !in MIN_SESSION_MIN..cfg.customMaxMin) return Transition(state)
        val rec = SessionRecord(
            key = state.key,
            startedAt = now,
            declaredMs = minutes * MINUTE_MS,
            deadlineAt = now + minutes * MINUTE_MS,
        )
        return startSession(state, rec, cfg, extra = emptyList())
    }

    /**
     * Ends whatever ran behind the prompt, then starts [rec] and opens the app. An app opened from
     * outside the launcher is already underneath the prompt, so it is not opened again (invariant 7).
     */
    private fun startSession(prompt: Prompting, rec: SessionRecord, cfg: Config, extra: List<Effect>): Transition {
        val launch = if (prompt.source == LaunchSource.OUTSIDE) emptyList() else listOf(LaunchApp(rec.key))
        val effects = endPrevious(prompt.previous, rec.startedAt) +
            listOf(PersistSession(rec)) + launch + listOf(StartCountdown(rec)) +
            schedules(rec, cfg, includeWarning = true) +
            extra
        return Transition(Running(rec, prompt.cooldownMin), effects)
    }

    /** One foreground app at a time (invariant 4): the old session ends when a new one starts. */
    private fun endPrevious(previous: SessionState, now: Long): List<Effect> = when (previous) {
        is Running -> listOf(EndSession(endedEarlyOrExpired(previous.rec, now)))
        is Warning -> listOf(EndSession(endedEarlyOrExpired(previous.rec, now)))
        is Expired -> listOf(EndSession(previous.rec.ended(EndReason.EXPIRED, previous.rec.deadlineAt)), HideCard)
        else -> emptyList()
    }

    private fun endedEarlyOrExpired(rec: SessionRecord, now: Long): SessionRecord =
        if (now >= rec.deadlineAt) rec.ended(EndReason.EXPIRED, rec.deadlineAt)
        else rec.ended(EndReason.LEFT_EARLY, now, leftAt = rec.leftAt ?: now)

    // ------------------------------------------------------------------------- countdown, warning

    private fun tick(state: SessionState, now: Long, cfg: Config): Transition = when (state) {
        is Running -> when {
            now >= state.rec.deadlineAt -> expire(state.rec, state.cooldownMin)
            warns(state.rec, cfg) && now >= warningAt(state.rec, cfg) ->
                Transition(Warning(state.rec, state.cooldownMin, state.graceCheckAt), listOf(ShowWarning(state.rec)))

            else -> Transition(state)
        }

        is Warning -> if (now >= state.rec.deadlineAt) expire(state.rec, state.cooldownMin) else Transition(state)
        is Cooldown -> if (now >= state.until) Transition(Idle) else Transition(state)
        else -> Transition(state)
    }

    /** A session gets a warning only if it is longer than the warning lead (spec US2 sc. 3). */
    private fun warns(rec: SessionRecord, cfg: Config): Boolean =
        cfg.warningLeadSec > 0 && rec.declaredMs > cfg.warningLeadSec * SECOND_MS

    private fun warningAt(rec: SessionRecord, cfg: Config): Long = rec.deadlineAt - cfg.warningLeadSec * SECOND_MS

    private fun schedules(rec: SessionRecord, cfg: Config, includeWarning: Boolean): List<Effect> =
        (if (includeWarning && warns(rec, cfg)) listOf(ScheduleAt(warningAt(rec, cfg), Wake.WARNING)) else emptyList()) +
            ScheduleAt(rec.deadlineAt, Wake.DEADLINE)

    private fun endedByUser(state: SessionState, now: Long): Transition = when (state) {
        is Running -> Transition(Idle, listOf(EndSession(state.rec.ended(EndReason.ENDED_BY_USER, now)), StopCountdown, GoHome))
        is Warning -> Transition(Idle, listOf(EndSession(state.rec.ended(EndReason.ENDED_BY_USER, now)), StopCountdown, GoHome))
        is Expired -> leaveCard(state, now, goHome = true, timedOut = false)
        else -> Transition(state)
    }

    // ------------------------------------------------------------------------------------ expiry

    private fun expire(rec: SessionRecord, cooldownMin: Int): Transition =
        Transition(Expired(rec, cooldownMin), listOf(ProbeForegroundThenShowCard(rec)))

    private fun foregroundIsTracked(state: SessionState, cfg: Config): Transition {
        if (state !is Expired || state.deferred || state.cardShown) return Transition(state)
        val canExtend = state.rec.extensions < cfg.maxExtensions
        return Transition(state.copy(cardShown = true), listOf(ShowCard(state.rec, canExtend)))
    }

    /** Another app is in front, or we cannot tell: no card, the session ends at its deadline (fail open). */
    private fun expiredQuietly(state: SessionState): Transition {
        if (state !is Expired || state.deferred || state.cardShown) return Transition(state)
        return endExpired(state.rec, state.cooldownMin, at = state.rec.deadlineAt)
    }

    /** The card could not be drawn (no overlay permission, or drawing failed): end at the deadline. */
    private fun cardUnavailable(state: SessionState): Transition {
        if (state !is Expired || state.deferred) return Transition(state)
        val hide = if (state.cardShown) listOf(HideCard) else emptyList()
        return endExpired(state.rec, state.cooldownMin, at = state.rec.deadlineAt, extra = hide)
    }

    /** Closes an expired session at [at]; the cooldown (if any) runs from that moment. */
    private fun endExpired(rec: SessionRecord, cooldownMin: Int, at: Long, extra: List<Effect> = emptyList()): Transition {
        val next = if (cooldownMin > 0) Cooldown(rec.key, at + cooldownMin * MINUTE_MS) else Idle
        return Transition(next, listOf(EndSession(rec.ended(EndReason.EXPIRED, at)), StopCountdown) + extra)
    }

    private fun inCallOrLocked(state: SessionState): Transition {
        if (state !is Expired || state.deferred) return Transition(state)
        val deferred = Expired(state.rec, state.cooldownMin, deferred = true)
        val hide = if (state.cardShown) listOf(HideCard) else emptyList()
        return Transition(deferred, hide + DeferUntilClear(state.rec))
    }

    private fun guardCleared(state: SessionState): Transition {
        if (state !is Expired || !state.deferred) return Transition(state)
        return Transition(state.copy(deferred = false), listOf(ProbeForegroundThenShowCard(state.rec)))
    }

    private fun leaveCard(state: SessionState, now: Long, goHome: Boolean, timedOut: Boolean): Transition {
        if (state !is Expired) return Transition(state)
        if (timedOut && !state.cardShown) return Transition(state)
        // GoHome comes before HideCard: starting HOME relies on our overlay still being visible (research R5).
        val extra = buildList {
            if (goHome) add(GoHome)
            add(HideCard)
            if (timedOut) add(Log(EventType.OVERLAY_TIMEOUT, state.rec.key))
        }
        return endExpired(state.rec, state.cooldownMin, at = now, extra = extra)
    }

    private fun extendStarted(state: SessionState, now: Long, cfg: Config): Transition {
        if (state !is Expired || !state.cardShown || state.rec.extensions >= cfg.maxExtensions) return Transition(state)
        if (state.extendStartedAt != null) return Transition(state)
        return Transition(state.copy(extendStartedAt = now))
    }

    private fun extended(state: SessionState, minutes: Int, now: Long, cfg: Config): Transition {
        if (state !is Expired || !state.cardShown) return Transition(state)
        val started = state.extendStartedAt ?: return Transition(state)
        if (now < started + cfg.extensionWaitSec * SECOND_MS) return Transition(state)
        if (state.rec.extensions >= cfg.maxExtensions) return Transition(state)
        if (minutes !in cfg.extensionOptionsMin) return Transition(state)
        val rec = state.rec.copy(deadlineAt = now + minutes * MINUTE_MS, extensions = state.rec.extensions + 1)
        return Transition(
            // The one warning of this session has already been "spent" by the card.
            Warning(rec, state.cooldownMin),
            listOf(
                PersistSession(rec),
                Log(EventType.EXTENDED, rec.key, "+$minutes"),
                HideCard,
                StartCountdown(rec),
                ScheduleAt(rec.deadlineAt, Wake.DEADLINE),
            ),
        )
    }

    // ---------------------------------------------------------------------------------- override

    private fun overrideStarted(state: SessionState, now: Long): Transition = when {
        state is Expired && state.cardShown && state.overrideStartedAt == null ->
            Transition(state.copy(overrideStartedAt = now))

        state is Prompting && state.isBlocked && state.overrideStartedAt == null ->
            Transition(state.copy(overrideStartedAt = now))

        else -> Transition(state)
    }

    private fun overrideReset(state: SessionState): Transition = when (state) {
        is Expired -> Transition(state.copy(overrideStartedAt = null))
        is Prompting -> Transition(state.copy(overrideStartedAt = null))
        else -> Transition(state)
    }

    /** The override always succeeds once its wait is over (Constitution III); each one is logged. */
    private fun overrideCompleted(state: SessionState, now: Long, cfg: Config): Transition {
        val grantMs = cfg.overrideGrantMin * MINUTE_MS
        val waitMs = cfg.overrideWaitSec * SECOND_MS
        return when {
            state is Expired && state.cardShown -> {
                val started = state.overrideStartedAt ?: return Transition(state)
                if (now < started + waitMs) return Transition(state)
                val rec = state.rec.copy(deadlineAt = now + grantMs, overrides = state.rec.overrides + 1)
                Transition(
                    Warning(rec, state.cooldownMin),
                    listOf(
                        PersistSession(rec),
                        Log(EventType.OVERRIDE, rec.key),
                        HideCard,
                        StartCountdown(rec),
                        ScheduleAt(rec.deadlineAt, Wake.DEADLINE),
                    ),
                )
            }

            state is Prompting && state.isBlocked -> {
                val started = state.overrideStartedAt ?: return Transition(state)
                if (now < started + waitMs) return Transition(state)
                val rec = SessionRecord(key = state.key, startedAt = now, declaredMs = grantMs, deadlineAt = now + grantMs, overrides = 1)
                startSession(state, rec, cfg, extra = listOf(Log(EventType.OVERRIDE, rec.key)))
            }

            else -> Transition(state)
        }
    }

    // ------------------------------------------------------------------------- leaving early (US6)

    private fun launcherResumed(state: SessionState, now: Long, cfg: Config): Transition = when (state) {
        is Running -> if (state.graceCheckAt != null) Transition(state) else {
            val at = now + cfg.graceSec * SECOND_MS
            Transition(state.copy(rec = state.rec.copy(leftAt = now), graceCheckAt = at), listOf(ScheduleAt(at, Wake.GRACE)))
        }

        is Warning -> if (state.graceCheckAt != null) Transition(state) else {
            val at = now + cfg.graceSec * SECOND_MS
            Transition(state.copy(rec = state.rec.copy(leftAt = now), graceCheckAt = at), listOf(ScheduleAt(at, Wake.GRACE)))
        }

        // Home was pressed while the card was up (or before it could be shown).
        is Expired -> endExpired(
            state.rec, state.cooldownMin,
            at = if (state.cardShown) now else state.rec.deadlineAt,
            extra = listOf(HideCard),
        )

        // The launcher is in front again, so the prompt is gone: treat it as cancelled.
        is Prompting -> {
            val cancel = cancelled(state)
            val inner = launcherResumed(cancel.state, now, cfg)
            Transition(inner.state, cancel.effects + inner.effects)
        }

        else -> Transition(state)
    }

    private fun launcherPaused(state: SessionState): Transition = when (state) {
        is Running -> if (state.graceCheckAt == null) Transition(state) else clearGrace(state)
        is Warning -> if (state.graceCheckAt == null) Transition(state) else clearGrace(state)
        else -> Transition(state)
    }

    private fun clearGrace(state: Running): Transition =
        if (state.graceCheckAt == null) Transition(state)
        else Transition(state.copy(rec = state.rec.copy(leftAt = null), graceCheckAt = null), listOf(CancelSchedule(Wake.GRACE)))

    private fun clearGrace(state: Warning): Transition =
        if (state.graceCheckAt == null) Transition(state)
        else Transition(state.copy(rec = state.rec.copy(leftAt = null), graceCheckAt = null), listOf(CancelSchedule(Wake.GRACE)))

    private fun graceElapsed(state: SessionState, stillOnLauncher: Boolean, now: Long): Transition {
        val (rec, checkAt) = when (state) {
            is Running -> state.rec to state.graceCheckAt
            is Warning -> state.rec to state.graceCheckAt
            else -> return Transition(state)
        }
        if (checkAt == null || now < checkAt) return Transition(state)
        if (!stillOnLauncher) {
            return when (state) {
                is Running -> Transition(state.copy(rec = rec.copy(leftAt = null), graceCheckAt = null))
                is Warning -> Transition(state.copy(rec = rec.copy(leftAt = null), graceCheckAt = null))
                else -> Transition(state)
            }
        }
        return Transition(Idle, listOf(EndSession(rec.ended(EndReason.LEFT_EARLY, now, leftAt = rec.leftAt)), StopCountdown))
    }

    // ------------------------------------------------------------------- reboot, process death (US5)

    private fun reconcile(state: SessionState, bootedAt: Long, now: Long, cfg: Config): Transition = when (state) {
        is Running -> reconcileActive(state.rec, state.cooldownMin, state.graceCheckAt, includeWarning = true, state, bootedAt, now, cfg)
        is Warning -> reconcileActive(state.rec, state.cooldownMin, state.graceCheckAt, includeWarning = false, state, bootedAt, now, cfg)
        is Cooldown -> if (now >= state.until) Transition(Idle) else Transition(state)
        else -> Transition(state)
    }

    /**
     * A future deadline: keep going and re-arm the timers. A past deadline: close the session as
     * EXPIRED at its deadline, with no card or other UI (invariant 6). If the device was running
     * the whole time and the deadline is more than a minute old, our timer evidently did not run.
     */
    private fun reconcileActive(
        rec: SessionRecord, cooldownMin: Int, graceCheckAt: Long?, includeWarning: Boolean,
        state: SessionState, bootedAt: Long, now: Long, cfg: Config,
    ): Transition {
        if (now < rec.deadlineAt) {
            val grace = if (graceCheckAt != null) listOf(ScheduleAt(graceCheckAt, Wake.GRACE)) else emptyList()
            return Transition(state, listOf(StartCountdown(rec)) + schedules(rec, cfg, includeWarning) + grace)
        }
        val interrupted = rec.deadlineAt > bootedAt && now - rec.deadlineAt > INTERRUPTED_AFTER_MS
        val log = if (interrupted) listOf(Log(EventType.SERVICE_INTERRUPTED, rec.key)) else emptyList()
        return endExpired(rec, cooldownMin, at = rec.deadlineAt, extra = log)
    }

    private fun appUninstalled(state: SessionState, key: AppKey, now: Long): Transition = when (state) {
        is Running -> if (state.rec.key == key) interrupted(state.rec, now, hideCard = false) else Transition(state)
        is Warning -> if (state.rec.key == key) interrupted(state.rec, now, hideCard = false) else Transition(state)
        is Expired -> if (state.rec.key == key) interrupted(state.rec, now, hideCard = true) else Transition(state)
        is Cooldown -> if (state.key == key) Transition(Idle) else Transition(state)
        is Prompting -> {
            val inner = appUninstalled(state.previous, key, now)
            if (state.key == key) inner else Transition(state.copy(previous = inner.state), inner.effects)
        }

        Idle -> Transition(state)
    }

    private fun interrupted(rec: SessionRecord, now: Long, hideCard: Boolean): Transition =
        Transition(
            Idle,
            listOf(EndSession(rec.ended(EndReason.INTERRUPTED, now)), StopCountdown) + if (hideCard) listOf(HideCard) else emptyList(),
        )
}
