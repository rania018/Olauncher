package app.calmlauncher.gate

import android.content.Context
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.AllowReason
import app.calmlauncher.engine.Decision
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.olauncher.BuildConfig
import app.olauncher.data.AppModel

/**
 * Entry point called by upstream `MainViewModel.selectedApp()` before it launches an app
 * (contracts/engine-api.md). Every launch made through the launcher passes here: home labels,
 * swipes, the drawer, search auto-launch, Enter, and the clock/calendar/screen-time shortcuts
 * when they are set to an app (research R1).
 */
object LaunchGate {

    /** At process start the database may still be loading; wait at most this long, then fail open. */
    private const val LOAD_WAIT_MS = 400L

    /** true = we handled it (prompt shown), false = upstream launches as usual. */
    fun intercept(context: Context, app: AppModel): Boolean {
        return failOpen("LaunchGate.intercept", false) {
            if (app is AppModel.PrivateSpaceHeader) return@failOpen false
            CalmGraph.init(context)
            val repo = CalmGraph.repository
            if (!repo.ensureLoaded(LOAD_WAIT_MS)) return@failOpen false

            val key = Apps.keyOf(context, app.appPackage, app.user)
            val decision = repo.decide(key)
            if (decision is Decision.Allow) {
                // Debug builds say in Logcat why a tracked app opened without a prompt, e.g. RUNNING
                // (its session is still counting down, spec FR-008).
                if (BuildConfig.DEBUG && decision.reason != AllowReason.NOT_TRACKED) {
                    CalmLog.i("no prompt for ${key.pkg}: ${decision.reason}")
                }
                // Reopening the session's app is coming back to it: a pending grace ends (research R16).
                if (decision.reason == AllowReason.RUNNING) repo.onSessionAppReopened(key)
                return@failOpen false
            }

            AppLauncher.remember(
                AppLauncher.Target(
                    key = key,
                    label = app.appLabel,
                    activityClassName = (app as? AppModel.App)?.activityClassName,
                    shortcutId = (app as? AppModel.PinnedShortcut)?.shortcutId,
                ),
            )
            repo.requestLaunch(key, decision)
        }
    }
}
