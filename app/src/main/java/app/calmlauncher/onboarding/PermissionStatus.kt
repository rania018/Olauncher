package app.calmlauncher.onboarding

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.calmlauncher.access.CalmAccessibilityService
import app.calmlauncher.data.SettingsStore
import app.olauncher.helper.appUsagePermissionGranted

/**
 * The onboarding pages, one per permission, then the "App limits" accessibility toggle and the
 * exempt-app picker (spec FR-022).
 */
enum class OnboardingPage { NOTIFICATIONS, OVERLAY, USAGE_ACCESS, EXACT_ALARMS, BATTERY, ACCESSIBILITY, EXEMPT_APPS }

/**
 * Which of the permissions Calm Launcher asks for are granted right now. [accessibility] is the
 * "App limits" toggle: our own accessibility service, switched on in Android's Accessibility settings.
 */
data class PermissionStatus(
    val notifications: Boolean,
    val overlay: Boolean,
    val usageAccess: Boolean,
    val exactAlarms: Boolean,
    val battery: Boolean,
    val accessibility: Boolean,
) {
    /** Status for an onboarding page, or null for the page that is not about a permission. */
    fun isGranted(page: OnboardingPage): Boolean? = when (page) {
        OnboardingPage.NOTIFICATIONS -> notifications
        OnboardingPage.OVERLAY -> overlay
        OnboardingPage.USAGE_ACCESS -> usageAccess
        OnboardingPage.EXACT_ALARMS -> exactAlarms
        OnboardingPage.BATTERY -> battery
        OnboardingPage.ACCESSIBILITY -> accessibility
        OnboardingPage.EXEMPT_APPS -> null
    }

    companion object {
        fun read(context: Context) = PermissionStatus(
            notifications = notifications(context),
            overlay = Settings.canDrawOverlays(context),
            usageAccess = usageAccess(context),
            exactAlarms = exactAlarms(context),
            battery = battery(context),
            accessibility = accessibility(context),
        )

        /**
         * Whether the user switched on our app-limits accessibility service (research R13). Android
         * keeps the enabled services as a colon-separated list of component names in a system
         * setting, so this also works while the service itself is not running.
         * 🅢 One UI may switch the service off after a force stop or a crash; the health line shows it.
         */
        fun accessibility(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val ours = ComponentName(context, CalmAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
        }

        /** ⚠️ Android 13+ asks for notifications at runtime; older versions allow them by default. */
        fun notifications(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** "Usage access" is a special app-op, granted on its own settings screen. Reuses upstream's check. */
        fun usageAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.appUsagePermissionGranted()
            } else {
                val appOps = context.getSystemService(AppOpsManager::class.java)
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                    AppOpsManager.MODE_ALLOWED
            }

        /** ⚠️ Android 12+: "Alarms & reminders"; denied by default for new installs on Android 14+. */
        fun exactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

        /** 🅢 On Samsung, "Unrestricted" battery use is also set per app in One UI settings. */
        fun battery(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}

/**
 * Chooses the single home-screen health line (spec FR-023, contracts/ui-surfaces.md): at most one
 * plain line, calm wording, naming what is reduced. Pure Kotlin so the choice is unit-tested;
 * the texts live here (not in resources) for that reason.
 */
object HealthLine {

    enum class Item(val text: String, val page: OnboardingPage?, val clearsNotice: Boolean = false) {
        INTERRUPTED("Timer was interrupted, check battery settings", OnboardingPage.BATTERY, clearsNotice = true),
        LOOP_GUARD("Time's-up card paused for 10 min after it kept reappearing", null),
        NOTIFICATIONS("Countdown hidden: notifications are off", OnboardingPage.NOTIFICATIONS),
        OVERLAY("Time's-up card off: display over other apps is not allowed", OnboardingPage.OVERLAY),
        ACCESSIBILITY("Limits only from this launcher: app limits are off in Accessibility", OnboardingPage.ACCESSIBILITY),
        USAGE_ACCESS("Time's-up card off: usage access is not allowed", OnboardingPage.USAGE_ACCESS),
        BATTERY("Timer may be stopped: battery use is restricted", OnboardingPage.BATTERY),
    }

    /** A "timer was interrupted" notice is shown until tapped, or for this long. */
    const val NOTICE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /**
     * Most important first. Permission lines only matter once at least one app is tracked;
     * alarms are optional and never shown here.
     */
    fun pick(
        status: PermissionStatus,
        notice: SettingsStore.HealthNotice?,
        loopGuardUntil: Long?,
        trackingInUse: Boolean,
        now: Long,
    ): Item? {
        if (notice?.code == NOTICE_INTERRUPTED && now - notice.at in 0 until NOTICE_MAX_AGE_MS) return Item.INTERRUPTED
        if (loopGuardUntil != null && loopGuardUntil > now) return Item.LOOP_GUARD
        if (!trackingInUse) return null
        return when {
            !status.notifications -> Item.NOTIFICATIONS
            !status.overlay -> Item.OVERLAY
            !status.accessibility -> Item.ACCESSIBILITY
            !status.usageAccess -> Item.USAGE_ACCESS
            !status.battery -> Item.BATTERY
            else -> null
        }
    }

    const val NOTICE_INTERRUPTED = "INTERRUPTED"
}
