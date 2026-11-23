package app.calmlauncher.access

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.ForegroundFilter
import app.calmlauncher.enforce.CallGuard
import app.calmlauncher.session.TimeLeftOverlay
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.olauncher.BuildConfig

/**
 * Tells Calm Launcher which app comes to the front (research R13, spec FR-031).
 *
 * An AccessibilityService is a component the user switches on in Android Settings → Accessibility.
 * The system then sends it events about what happens on screen. Ours asks only for
 * "window state changed" events (an app or screen came to the front), and its config sets
 * `canRetrieveWindowContent="false"`, so Android never hands it what is on screen. We read only the
 * event's package and class name, never its text (Constitution I).
 *
 * It is separate from upstream's `MyAccessibilityService` (double-tap to lock), which runs in another
 * process and is limited to our own package. Ours runs in the main process, next to the engine.
 *
 * Each event goes through [ForegroundFilter] (pure, in the engine). When a different app comes to
 * the front, the repository decides whether to ask "How long?" (research R15).
 */
class CalmAccessibilityService : AccessibilityService() {

    /** "Is this class an activity of that package?", asked once per class (research R14). */
    private val activityCache = LruCache<String, Boolean>(256)

    override fun onServiceConnected() {
        super.onServiceConnected()
        failOpen("CalmAccessibilityService.connected") {
            CalmGraph.init(this)
            AccessState.onConnected(this)
            CalmLog.i("app-limits service connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?): Unit = failOpen("CalmAccessibilityService.event") {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return@failOpen
        // Package and class only: never event.text, never event.source (no screen content).
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        val filter = AccessState.filter() ?: return@failOpen
        val result = filter.accept(pkg, cls, isActivity(pkg, cls), System.currentTimeMillis())
        if (result is ForegroundFilter.Result.InFront) {
            if (BuildConfig.DEBUG) CalmLog.i("in front: ${result.pkg}")
            CalmGraph.repository.onAppInFront(result.pkg)
        }
        // The app in front changed (another app, or one of our own screens). During a call that
        // decides whether the time-left reminder and the time's-up card may show (spec FR-018).
        if (result != ForegroundFilter.Result.Ignore) {
            TimeLeftOverlay.onForegroundChanged()
            CallGuard.onForegroundChanged(this)
        }
    }

    override fun onInterrupt() {
        // Required by AccessibilityService; we give no spoken or haptic feedback, so nothing to stop.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        failOpen("CalmAccessibilityService.unbind") {
            AccessState.onDisconnected()
            CalmLog.i("app-limits service disconnected")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        failOpen("CalmAccessibilityService.destroy") { AccessState.onDisconnected() }
        super.onDestroy()
    }

    /**
     * Dialogs, pop-ups, splash windows and keyboards are not activities, so they never count as
     * an app switch. A failed lookup counts as "not an activity" (ignored: nothing happens).
     */
    @Suppress("DEPRECATION")
    private fun isActivity(pkg: String?, cls: String?): Boolean {
        if (pkg.isNullOrEmpty() || cls.isNullOrEmpty()) return false
        val id = "$pkg/$cls"
        activityCache.get(id)?.let { return it }
        val found = try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        activityCache.put(id, found)
        return found
    }
}
