package app.calmlauncher.engine

/**
 * Where the single session (at most one app at a time) currently is. See data-model.md
 * "State transitions" for the full table.
 */
sealed interface SessionState {
    /** No session runs and no prompt is open. */
    data object Idle : SessionState

    /**
     * Our prompt for [key] is on screen.
     *
     * - DURATION mode ([blockedUntil] == null): "How long?".
     * - BLOCKED mode ([blockedUntil] != null): the cooldown screen; the override wait started at
     *   [overrideStartedAt] if the user tapped "I really need this".
     *
     * [previous] is the state to return to when the prompt is cancelled. It may hold a running
     * session for another app, which only ends if the user confirms a new session (invariant 4).
     */
    data class Prompting(
        val key: AppKey,
        val cooldownMin: Int,
        val previous: SessionState,
        val blockedUntil: Long? = null,
        val overrideStartedAt: Long? = null,
        val source: LaunchSource = LaunchSource.LAUNCHER,
    ) : SessionState {
        val isBlocked: Boolean get() = blockedUntil != null
    }

    /**
     * A session is counting down. [graceCheckAt] is set while the user is back on the launcher
     * (US6): if they are still there at that time, the session ends as LEFT_EARLY.
     */
    data class Running(
        val rec: SessionRecord,
        val cooldownMin: Int,
        val graceCheckAt: Long? = null,
    ) : SessionState

    /** Like [Running], but the one warning of this session has already been shown. */
    data class Warning(
        val rec: SessionRecord,
        val cooldownMin: Int,
        val graceCheckAt: Long? = null,
    ) : SessionState

    /**
     * The deadline has passed. The time's-up card is shown only if the tracked app is confirmed
     * to be in front ([cardShown]); while a call or the lock screen is active the card waits
     * ([deferred]). The extension and override waits start at [extendStartedAt] / [overrideStartedAt].
     */
    data class Expired(
        val rec: SessionRecord,
        val cooldownMin: Int,
        val deferred: Boolean = false,
        val cardShown: Boolean = false,
        val extendStartedAt: Long? = null,
        val overrideStartedAt: Long? = null,
    ) : SessionState

    /** [key] is cooling down after an expired session until [until]. */
    data class Cooldown(val key: AppKey, val until: Long) : SessionState
}

/** The session state behind any open prompt. */
fun SessionState.session(): SessionState = if (this is SessionState.Prompting) previous.session() else this

/** The session that is counting down or has just expired, if any. */
val SessionState.activeRecord: SessionRecord?
    get() = when (val s = session()) {
        is SessionState.Running -> s.rec
        is SessionState.Warning -> s.rec
        is SessionState.Expired -> s.rec
        else -> null
    }

/**
 * How a tracked app was opened (data-model.md `LaunchSource`, research R15).
 * - [LAUNCHER]: from our launcher, which asks before the app opens.
 * - [OUTSIDE]: the app came to the front another way (recent-apps list, link, notification) and the
 *   accessibility service saw it. It is already on screen, so the engine never relaunches it.
 */
enum class LaunchSource { LAUNCHER, OUTSIDE }

/** Things that happen. Time is not part of an event: it is passed to [SessionMachine.reduce]. */
sealed interface SessionEvent {
    /** The user opened [key] from the launcher, and the rules engine said [decision]. */
    data class LaunchRequested(
        val key: AppKey,
        val decision: Decision,
        val cooldownMin: Int = TrackedApp.DEFAULT_COOLDOWN_MIN,
        val source: LaunchSource = LaunchSource.LAUNCHER,
    ) : SessionEvent

    /** The prompt was closed with Cancel, Back or "Go home". */
    data object Cancelled : SessionEvent

    /** The user picked a duration on the prompt. */
    data class DurationChosen(val minutes: Int) : SessionEvent

    /** A timer callback fired (warning, deadline, alarm, screen on). */
    data object Tick : SessionEvent

    /** "End now" from the countdown notification. */
    data object EndedByUser : SessionEvent

    /** Result of the foreground check at expiry. */
    data object ForegroundIsTracked : SessionEvent
    data object ForegroundIsOther : SessionEvent
    data object ForegroundUnknown : SessionEvent

    /** A call is active or the lock screen is showing, so no card may be drawn. */
    data object InCallOrLocked : SessionEvent

    /** The call ended and the screen is unlocked again. */
    data object GuardCleared : SessionEvent

    /** The card could not be drawn (overlay permission missing); the fallback notification was posted. */
    data object CardUnavailable : SessionEvent

    /** "Go home" or Back on the time's-up card. */
    data object WentHome : SessionEvent

    /** The card was left untouched for [Config.cardTimeoutSec] and removed itself. */
    data object CardTimedOut : SessionEvent

    /** "Extend" tapped: the extension wait starts. */
    data object ExtendStarted : SessionEvent

    /** An extension of [minutes] was chosen after the wait. */
    data class Extended(val minutes: Int) : SessionEvent

    /** "I really need this" tapped: the override wait starts. */
    data object OverrideStarted : SessionEvent

    /** The screen showing the override wait lost visibility, so the wait starts over. */
    data object OverrideReset : SessionEvent

    /** The override wait finished. */
    data object OverrideCompleted : SessionEvent

    /** The launcher came to the front / left the front. */
    data object LauncherResumed : SessionEvent
    data object LauncherPaused : SessionEvent

    /** The grace period after going home ended; [stillOnLauncher] says whether the user stayed there. */
    data class GraceElapsed(val stillOnLauncher: Boolean) : SessionEvent

    /**
     * Re-check the persisted session against the clock: after boot, process restart, launcher
     * resume or a clock change. [bootedAt] is the wall-clock time the device last booted.
     */
    data class Reconcile(val bootedAt: Long = Long.MIN_VALUE) : SessionEvent

    /** [key] was uninstalled. */
    data class AppUninstalled(val key: AppKey) : SessionEvent
}

/** Why a timer callback is scheduled. */
enum class Wake { WARNING, DEADLINE, GRACE }

/** Things the Android side must do. The engine only describes them. */
sealed interface Effect {
    data class ShowPrompt(val key: AppKey, val presetsMin: List<Int>, val customMaxMin: Int) : Effect
    data class ShowBlocked(val key: AppKey, val until: Long) : Effect
    data class LaunchApp(val key: AppKey) : Effect

    /** Insert a new session row, or update the active one (deadline, extensions, overrides, left time). */
    data class PersistSession(val rec: SessionRecord) : Effect

    /** Close the session row with [rec]'s end time and reason. */
    data class EndSession(val rec: SessionRecord) : Effect {
        val reason: EndReason? get() = rec.endReason
        val at: Long? get() = rec.endedAt
    }

    /** Show or update the countdown notification (and keep the session service running). */
    data class StartCountdown(val rec: SessionRecord) : Effect
    data object StopCountdown : Effect

    data class ScheduleAt(val at: Long, val why: Wake) : Effect
    data class CancelSchedule(val why: Wake) : Effect

    data class ShowWarning(val rec: SessionRecord) : Effect

    /** Check which app is in front (and whether a call or the lock screen is active), then report back. */
    data class ProbeForegroundThenShowCard(val rec: SessionRecord) : Effect

    data class ShowCard(val rec: SessionRecord, val canExtend: Boolean) : Effect
    data object HideCard : Effect

    /** Wait for the call to end / the screen to unlock, then send [SessionEvent.GuardCleared]. */
    data class DeferUntilClear(val rec: SessionRecord) : Effect

    data object GoHome : Effect

    data class Log(val type: EventType, val key: AppKey?, val detail: String? = null) : Effect
}

data class Transition(val state: SessionState, val effects: List<Effect> = emptyList())
