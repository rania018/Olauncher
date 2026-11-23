package app.calmlauncher.session

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.enforce.GoHome
import app.calmlauncher.prompt.PromptActivity
import app.calmlauncher.ui.CalmLook
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.olauncher.R
import java.util.Date

/**
 * Our three notifications (contracts/ui-surfaces.md).
 *
 * Since Android 8, every notification belongs to a "channel". A channel fixes how loud its
 * notifications are, and the user can mute or change each channel in system settings. We use:
 * - `calm_session`: low importance, silent, ongoing countdown while a session runs;
 * - `calm_warning`: high importance, one heads-up before the end. Its sound follows the phone's
 *   ringer mode, so it is silent when the phone is on silent;
 * - `calm_expiry`: high importance, the time's-up fallback when the card cannot be drawn.
 */
object Notifications {

    const val CHANNEL_SESSION = "calm_session"
    const val CHANNEL_WARNING = "calm_warning"
    const val CHANNEL_EXPIRY = "calm_expiry"

    const val ID_SESSION = 4101
    const val ID_WARNING = 4102
    const val ID_EXPIRY = 4103

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SESSION, context.getString(R.string.calm_channel_session), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.calm_channel_session_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_WARNING, context.getString(R.string.calm_channel_warning), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.calm_channel_warning_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_EXPIRY, context.getString(R.string.calm_channel_expiry), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.calm_channel_expiry_desc)
                    setShowBadge(false)
                },
            ),
        )
    }

    /**
     * The ongoing countdown. The system draws the clock itself (`setUsesChronometer` counting down
     * to `setWhen(deadline)`), so the app never needs to update it every second.
     */
    fun session(context: Context, rec: SessionRecord?): Notification {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.calm_ic_timer)
            .setColor(CalmLook.accentColor(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (rec == null) return builder.setContentTitle(context.getString(R.string.calm_notif_timer)).build()
        return builder
            .setContentTitle(Apps.label(context, rec.key))
            .setContentText(context.getString(R.string.calm_session_ends_at, time(context, rec.deadlineAt)))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(rec.deadlineAt)
            .setContentIntent(PromptActivity.sessionSheetPendingIntent(context))
            .addAction(0, context.getString(R.string.calm_end_now), PromptActivity.endNowPendingIntent(context))
            .build()
    }

    fun updateSession(context: Context, rec: SessionRecord) = notify(context, ID_SESSION, session(context, rec))

    /** The one warning of a session, e.g. "1 minute left in Instagram". */
    fun showWarning(context: Context, rec: SessionRecord, leadSec: Int) {
        ensureChannels(context)
        val label = Apps.label(context, rec.key)
        val text = when {
            leadSec == 60 -> context.getString(R.string.calm_warning_one_minute, label)
            leadSec % 60 == 0 -> context.getString(R.string.calm_warning_minutes, leadSec / 60, label)
            else -> context.getString(R.string.calm_warning_seconds, leadSec, label)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(R.drawable.calm_ic_timer)
            .setColor(CalmLook.accentColor(context))
            .setContentTitle(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(leadSec * 1000L)
            .build()
        notify(context, ID_WARNING, n)
    }

    /** Replaces the card when it cannot be drawn. Tapping it goes home; nothing is blocked. */
    fun showExpiryFallback(context: Context, rec: SessionRecord) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_EXPIRY)
            .setSmallIcon(R.drawable.calm_ic_timer)
            .setColor(CalmLook.accentColor(context))
            .setContentTitle(context.getString(R.string.calm_expiry_title, Apps.label(context, rec.key)))
            .setContentText(context.getString(R.string.calm_expiry_text))
            .setContentIntent(GoHome.pendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        notify(context, ID_EXPIRY, n)
    }

    fun cancelWarning(context: Context) = NotificationManagerCompat.from(context).cancel(ID_WARNING)

    private fun time(context: Context, at: Long): String = DateFormat.getTimeFormat(context).format(Date(at))

    /** Posting is skipped when notifications are off (Android 13+ permission); nothing else depends on it. */
    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, notification: Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, notification)
        } catch (e: SecurityException) {
            CalmLog.w("notification not allowed", e)
        }
    }
}
