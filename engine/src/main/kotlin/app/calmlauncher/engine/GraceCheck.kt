package app.calmlauncher.engine

/**
 * At the end of the grace period: is the session's app in front again? (research R16). Pure; the
 * session service passes in what the phone knows, and the answer goes into
 * [SessionEvent.GraceElapsed].
 */
object GraceCheck {

    /**
     * - [accessibilityFront]: the package the app-limits service last saw in front (our own package
     *   for our screens), or null while its toggle is off.
     * - [launcherResumed]: our home screen is in front right now.
     * - [usageFront]: the last app that came to the front according to usage history, or null
     *   without usage access.
     *
     * true = back in the app (either source names it, so one missed event cannot end a session the
     * user is still in); false = on the home screen or in another app; null = neither source knows.
     */
    fun trackedInFront(
        sessionPkg: String,
        accessibilityFront: String?,
        launcherResumed: Boolean,
        usageFront: String?,
    ): Boolean? = when {
        accessibilityFront == sessionPkg || usageFront == sessionPkg -> true
        launcherResumed -> false
        accessibilityFront != null || usageFront != null -> false
        else -> null
    }
}
