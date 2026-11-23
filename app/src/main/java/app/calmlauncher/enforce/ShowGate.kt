package app.calmlauncher.enforce

/** Whether drawing over the screen is allowed right now. [CallGuard] is the real implementation. */
fun interface GuardState {
    /**
     * True while a call rings, a call runs and the tracked app is not the app in front, the lock
     * screen is up, or the screen is off (the engine's CallRule).
     */
    fun isBlocked(): Boolean
}

/**
 * The single check every time's-up card and every warning goes through (T052, spec FR-007 and
 * FR-018). Pure logic with no Android types, so it is unit-tested with fakes.
 *
 * Order matters: an exempt app is never covered (checked at show time, because the exempt list
 * can change during a session); an app whose enforcement is paused by the loop guard is left
 * alone; a call (ringing, or running while another screen is in front) or the lock screen only
 * delays the card.
 */
object ShowGate {
    enum class Verdict { SHOW, DEFER, SKIP }

    fun check(
        pkg: String,
        guard: GuardState,
        isExempt: (String) -> Boolean,
        loopGuard: LoopGuard,
        now: Long,
        isEnforcement: Boolean,
    ): Verdict {
        if (isExempt(pkg)) return Verdict.SKIP
        if (loopGuard.isPaused(pkg, now)) return Verdict.SKIP
        if (guard.isBlocked()) return Verdict.DEFER
        if (isEnforcement && loopGuard.record(pkg, now)) return Verdict.SKIP
        return Verdict.SHOW
    }
}
