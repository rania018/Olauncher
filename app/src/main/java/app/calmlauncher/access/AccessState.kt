package app.calmlauncher.access

import android.content.Context
import android.view.inputmethod.InputMethodManager
import app.calmlauncher.engine.ForegroundFilter

/**
 * What the app-limits accessibility service knows (research R13–R16): whether it is connected, and
 * which app it last saw in front. Written by [CalmAccessibilityService] on the main thread; read by
 * the repository, the session service and the time-left reminder.
 */
object AccessState {

    /** The notification shade, volume panel and other system windows (research R14, T084). */
    private const val SYSTEM_UI = "com.android.systemui"

    /** The recent-apps screen is an activity on Samsung phones, but passing through it is not leaving (T084). */
    private val IGNORED_CLASSES = setOf("com.android.quickstep.RecentsActivity")

    @Volatile
    var connected: Boolean = false
        private set

    private var filter: ForegroundFilter? = null

    /** The package the service last saw in front (our own package for our screens), or null if unknown. */
    val inFront: String? get() = if (connected) filter?.inFront else null

    fun filter(): ForegroundFilter? = filter

    fun onConnected(context: Context) {
        filter = ForegroundFilter(context.packageName, ignoredPackages(context), IGNORED_CLASSES)
        connected = true
    }

    fun onDisconnected() {
        connected = false
        filter = null
    }

    /** An outside prompt for [pkg] was cancelled: ignore that app for a moment (research R15). */
    fun noteCancelled(pkg: String, now: Long) {
        filter?.noteCancelled(pkg, now)
    }

    /** System UI plus every enabled keyboard: their windows sit on top of apps without replacing them. */
    private fun ignoredPackages(context: Context): Set<String> {
        val keyboards = runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }
        }.getOrNull().orEmpty()
        return setOf(SYSTEM_UI) + keyboards
    }
}
