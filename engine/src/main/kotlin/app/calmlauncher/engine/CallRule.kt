package app.calmlauncher.engine

/**
 * When a call, the lock screen or a dark screen keeps Calm Launcher's own UI away (spec FR-018,
 * research R7). Pure, so every case is a JVM unit test; the Android side (`CallGuard`) reads the
 * phone's state and asks this rule.
 *
 * A call protects the call, not the tracked app. While the phone rings, or while a call runs and
 * the tracked app is not known to be in front (the call screen may be), nothing is drawn. Once the
 * app-limits service has seen the tracked app come to the front, the user is using that app while
 * the call carries on in the background, so the time-left reminder and the time's-up card apply as
 * usual. When in doubt the answer is "blocked": nothing is drawn (fail open).
 */
object CallRule {

    enum class Call {
        /** No call. */
        NONE,

        /** A phone or internet call is ringing. */
        RINGING,

        /** A phone or internet call is running. */
        ACTIVE,
    }

    /**
     * True while nothing may be drawn. [trackedInFront] is true only when the app-limits service
     * last saw the session's app in front; false when it saw another app or cannot tell (its
     * toggle is off).
     */
    fun blocks(call: Call, locked: Boolean, screenOff: Boolean, trackedInFront: Boolean): Boolean = when {
        locked || screenOff -> true
        call == Call.RINGING -> true
        call == Call.ACTIVE -> !trackedInFront
        else -> false
    }
}
