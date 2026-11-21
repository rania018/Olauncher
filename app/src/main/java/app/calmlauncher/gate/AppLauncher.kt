package app.calmlauncher.gate

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import app.calmlauncher.engine.AppKey
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.olauncher.R
import app.olauncher.helper.showToast

/**
 * Opens an app the same way upstream does (`MainViewModel.launchApp` / `launchShortcut`):
 * through LauncherApps, which also works for work-profile and Private Space apps.
 */
object AppLauncher {

    /** What the user tapped in the launcher: an app's main screen or one of its pinned shortcuts. */
    data class Target(val key: AppKey, val label: String, val activityClassName: String?, val shortcutId: String?)

    private val targets = HashMap<AppKey, Target>()

    /** Remembers what was tapped, so the app opens exactly as upstream would after the prompt. */
    fun remember(target: Target) {
        targets[target.key] = target
    }

    fun target(key: AppKey): Target? = targets[key]

    fun launch(context: Context, key: AppKey): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val user = Apps.userOf(context, key.userSerial) ?: Process.myUserHandle()
        val target = targets[key]
        return try {
            if (target?.shortcutId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                val query = LauncherApps.ShortcutQuery()
                    .setPackage(key.pkg)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                val shortcut = launcherApps.getShortcuts(query, user)?.find { it.id == target.shortcutId }
                if (shortcut != null) {
                    launcherApps.startShortcut(shortcut, null, null)
                    return true
                }
            }
            val activities = launcherApps.getActivityList(key.pkg, user)
            val component = activities.firstOrNull { it.componentName.className == target?.activityClassName }?.componentName
                ?: activities.lastOrNull()?.componentName
            if (component == null) {
                context.showToast(R.string.app_not_found)
                return false
            }
            launcherApps.startMainActivity(component, user, null, null)
            true
        } catch (e: Exception) {
            CalmLog.w("launch failed for ${key.pkg}", e)
            context.showToast(R.string.unable_to_open_app)
            false
        }
    }
}
