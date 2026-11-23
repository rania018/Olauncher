package app.calmlauncher.util

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import app.calmlauncher.engine.AppKey
import app.olauncher.data.Prefs

/** Small helpers to turn Android's app and user identities into engine [AppKey]s and back. */
object Apps {

    /**
     * Android identifies each user profile (main, work, Private Space) by a [UserHandle]. Its
     * serial number is stable across reboots, so we store that.
     */
    fun serialOf(context: Context, user: UserHandle): Long =
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(user)

    fun userOf(context: Context, serial: Long): UserHandle? =
        context.getSystemService(UserManager::class.java).getUserForSerialNumber(serial)

    fun keyOf(context: Context, pkg: String, user: UserHandle): AppKey = AppKey(pkg, serialOf(context, user))

    fun mySerial(context: Context): Long = serialOf(context, Process.myUserHandle())

    /** The label the launcher shows: the user's rename in upstream settings, else the app's own label. */
    fun label(context: Context, key: AppKey): String {
        Prefs(context).getAppRenameLabel(key.pkg).takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val user = userOf(context, key.userSerial) ?: Process.myUserHandle()
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(key.pkg, user).firstOrNull()?.label?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(key.pkg, 0)).toString()
            }.getOrNull()
            ?: key.pkg
    }

    /** The app's icon (with the work badge for work apps), or null if it cannot be loaded. */
    fun icon(context: Context, key: AppKey): Drawable? = runCatching {
        val user = userOf(context, key.userSerial) ?: Process.myUserHandle()
        context.getSystemService(LauncherApps::class.java)
            .getActivityList(key.pkg, user).firstOrNull()?.getBadgedIcon(0)
    }.getOrNull()

    /** Wall-clock time of the last boot. */
    fun bootedAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
}
