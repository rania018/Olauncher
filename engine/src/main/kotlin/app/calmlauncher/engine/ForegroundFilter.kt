package app.calmlauncher.engine

/**
 * Turns accessibility "window state changed" events into "this app just came to the front", or
 * nothing (research R14, R15). It is pure: the accessibility service passes in only the package,
 * the class name, whether that class is an activity of the package, and the time.
 *
 * Rules, from the Samsung device check (T084):
 * - Windows that are not activities (dialogs, pop-ups, splash windows, keyboards) are ignored.
 * - [ignoredPkgs] (the notification shade, keyboards) and [ignoredClasses] (the recent-apps screen)
 *   are ignored, so passing through them is not "leaving" the app.
 * - Our own package is reported as [Result.Own] and remembered, so going from an app to our prompt
 *   or launcher and back to the same app is seen as a change.
 * - More events for the app already in front (duplicates, in-app navigation) are ignored.
 * - After a prompt for an app opened from outside is cancelled, that app is ignored for
 *   [CANCEL_QUIET_MS], so its last windows on the way home do not prompt again.
 */
class ForegroundFilter(
    private val ownPkg: String,
    private val ignoredPkgs: Set<String>,
    private val ignoredClasses: Set<String>,
) {
    sealed interface Result {
        /** [pkg] (not ours) has just come to the front. */
        data class InFront(val pkg: String) : Result

        /** One of our own screens (launcher, prompt, settings) has come to the front. */
        data object Own : Result

        /** Nothing changed. */
        data object Ignore : Result
    }

    private var current: String? = null
    private val quietUntil = HashMap<String, Long>()

    /** The package last seen in front ([ownPkg] for our screens), or null if none yet. */
    val inFront: String? get() = current

    fun accept(pkg: String?, className: String?, isActivity: Boolean, now: Long): Result {
        if (pkg.isNullOrEmpty() || !isActivity) return Result.Ignore
        if (pkg in ignoredPkgs || className in ignoredClasses) return Result.Ignore
        if (pkg == current) return Result.Ignore
        if (pkg == ownPkg) {
            current = pkg
            return Result.Own
        }
        val quiet = quietUntil[pkg]
        if (quiet != null) {
            // A quiet event is not remembered as "in front": the next event of the app counts.
            if (now < quiet) return Result.Ignore
            quietUntil.remove(pkg)
        }
        current = pkg
        return Result.InFront(pkg)
    }

    /** An outside prompt for [pkg] was cancelled at [now]: ignore that app for a moment. */
    fun noteCancelled(pkg: String, now: Long) {
        quietUntil[pkg] = now + CANCEL_QUIET_MS
    }

    companion object {
        const val CANCEL_QUIET_MS = 3_000L
    }
}
