package app.calmlauncher.exempt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import app.calmlauncher.data.ExemptAppEntity
import app.calmlauncher.data.ExemptCategory
import app.calmlauncher.data.ExemptSource
import app.calmlauncher.util.failOpen

/**
 * Finds the apps that are exempt by default on this phone (research R8, spec FR-017): the current
 * phone, SMS, maps, camera, clock and system Settings apps. They are resolved on the device, so
 * they follow whatever apps the user chose as defaults; nothing vendor-specific is hard-coded.
 *
 * Dialer and SMS: Android's RoleManager can only tell an app whether it holds a role itself, so we
 * use the public TelecomManager / Telephony.Sms lookups, with intent resolution as a fallback.
 * When an intent has no default handler yet (Android would show a chooser), every candidate is
 * exempted: being too generous here is the safe direction (Constitution II).
 *
 * Dialer, SMS and Settings are marked SYSTEM and can never be removed, so the default list can
 * never be edited into an empty state. Maps, camera and clock can be removed after confirmation
 * (spec FR-021).
 */
object DefaultExemptions {

    data class Resolved(val pkg: String, val category: ExemptCategory?)

    fun resolve(context: Context): List<Resolved> = failOpen("DefaultExemptions.resolve", emptyList()) {
        val pm = context.packageManager
        val found = linkedMapOf<String, ExemptCategory?>()
        fun add(pkgs: Collection<String>, category: ExemptCategory?) {
            pkgs.filter { it.isNotBlank() && it != "android" && it != context.packageName }.forEach { pkg ->
                // SYSTEM wins if the same package qualifies twice (e.g. Google Messages).
                if (found[pkg] != ExemptCategory.SYSTEM) found[pkg] = category
            }
        }

        add(setOfNotNull(defaultDialer(context)) + handlers(pm, Intent(Intent.ACTION_DIAL)), ExemptCategory.SYSTEM)
        add(
            setOfNotNull(defaultSms(context)) + handlers(pm, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            ExemptCategory.SYSTEM,
        )
        add(handlers(pm, Intent(Settings.ACTION_SETTINGS)), ExemptCategory.SYSTEM)
        add(handlers(pm, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))), null)
        add(handlers(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE)), null)
        add(handlers(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS)), null)

        found.map { (pkg, category) -> Resolved(pkg, category) }
    }

    /** Rows to insert for the resolved defaults, skipping packages the user removed on purpose. */
    fun rows(resolved: List<Resolved>, removedByUser: Set<String>, now: Long): List<ExemptAppEntity> =
        resolved
            .filter { it.category == ExemptCategory.SYSTEM || it.pkg !in removedByUser }
            .map { ExemptAppEntity(it.pkg, ExemptSource.DEFAULT, it.category, now) }

    private fun defaultDialer(context: Context): String? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()

    private fun defaultSms(context: Context): String? = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()

    /** The default handler of [intent], or all handlers when no default is set. */
    private fun handlers(pm: PackageManager, intent: Intent): Set<String> = runCatching {
        val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (default != null && default != "android") {
            setOf(default)
        } else {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        }
    }.getOrDefault(emptySet())
}
