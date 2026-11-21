package app.calmlauncher.enforce

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.calmlauncher.util.CalmLog

/**
 * Sends the user to the home screen (research R5). The HOME intent resolves to the default
 * launcher, which is Calm Launcher itself once it is set as the Home app.
 *
 * ⚠️ Android 10+ blocks starting activities from the background. This is called either from our
 * own foreground screens, or from the time's-up overlay while it is still visible, which Android
 * allows for apps holding "Display over other apps". The overlay is removed only afterwards.
 */
object GoHome {

    private const val REQUEST_CODE = 4201

    fun intent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun go(context: Context) {
        try {
            context.startActivity(intent())
        } catch (e: Exception) {
            CalmLog.w("could not start HOME", e)
        }
    }

    /** For notifications: tapping goes home. */
    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, REQUEST_CODE, intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
