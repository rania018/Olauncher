package app.calmlauncher.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.calmlauncher.CalmGraph
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen

/**
 * Backup for the session deadline (research R4, T058).
 *
 * AlarmManager wakes our app at a wall-clock time even if the process was killed in between,
 * by sending this receiver a broadcast. We set one "exact" alarm, one second after the deadline,
 * in case SessionService was stopped by the system.
 *
 * ⚠️ Android 12+ needs the "Alarms & reminders" permission (SCHEDULE_EXACT_ALARM) for exact
 * alarms, and Android 14+ denies it by default for new installs, so this backup is optional:
 * without it, the next launcher resume closes the session. We do not use USE_EXACT_ALARM
 * (🅟 reserved for alarm-clock apps). An exact alarm may start a foreground service from the
 * background, which is how this receiver restarts the timer.
 */
class SessionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) = failOpen("SessionAlarmReceiver.onReceive") {
        CalmGraph.init(context)
        SessionService.ensureRunning(context, SessionService.ACTION_TICK)
    }

    companion object {
        private const val REQUEST_CODE = 4301
        private const val AFTER_DEADLINE_MS = 1_000L

        fun schedule(context: Context, deadlineAt: Long) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) return
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineAt + AFTER_DEADLINE_MS, pendingIntent(context))
            } catch (e: SecurityException) {
                CalmLog.w("exact alarm not allowed", e)
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, REQUEST_CODE, Intent(context, SessionAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
