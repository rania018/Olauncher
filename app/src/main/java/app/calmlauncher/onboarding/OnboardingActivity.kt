package app.calmlauncher.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import app.calmlauncher.CalmGraph
import app.calmlauncher.data.ExemptCategory
import app.calmlauncher.engine.AppKey
import app.calmlauncher.exempt.KnownApps
import app.calmlauncher.ui.CalmActivity
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.olauncher.R
import app.olauncher.databinding.CalmOnboardingBinding

/**
 * Explains each permission on its own page: why it is needed, what is reduced without it, a
 * button to the exact setting, and a live ✓/✗ refreshed when the user comes back (spec FR-022).
 * Every page can be skipped; nothing here blocks the launcher. The last page suggests apps that
 * should never be limited (spec FR-017). Re-openable from Screen time limits and the health line.
 */
class OnboardingActivity : CalmActivity() {

    private lateinit var b: CalmOnboardingBinding
    private val pages = OnboardingPage.entries
    private var index = 0
    private var suggestions: List<Pair<String, ExemptCategory>> = emptyList()
    private val selected = LinkedHashSet<String>()

    /** Android 13+ asks for the notification permission with a system dialog. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openNotificationSettings()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = CalmOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)
        val start = intent.getStringExtra(EXTRA_PAGE)?.let { name -> pages.firstOrNull { it.name == name } }
        index = start?.let { pages.indexOf(it) } ?: 0
        b.onbSkip.setOnClickListener { failOpen("Onboarding.skip") { next(save = false) } }
        b.onbNext.setOnClickListener { failOpen("Onboarding.next") { next(save = true) } }
        b.onbOpen.setOnClickListener { failOpen("Onboarding.open") { openSetting(pages[index]) } }
        CalmGraph.repository.markOnboardingShown()
    }

    override fun onResume() {
        super.onResume()
        failOpen("Onboarding.onResume") { render() }
    }

    private fun next(save: Boolean) {
        if (pages[index] == OnboardingPage.EXEMPT_APPS && save) {
            val repo = CalmGraph.repository
            suggestions.filter { it.first in selected }.forEach { (pkg, category) -> repo.addExempt(pkg, category) }
        }
        if (index >= pages.lastIndex) finish() else {
            index++
            render()
        }
    }

    private fun render() {
        val page = pages[index]
        val status = PermissionStatus.read(this)
        b.onbStep.text = getString(R.string.calm_onb_step, index + 1, pages.size)
        val (title, why, reduced) = texts(page)
        b.onbTitle.text = getString(title)
        b.onbWhy.text = getString(why)
        b.onbReduced.text = getString(reduced)
        b.onbNext.text = getString(if (index == pages.lastIndex) R.string.calm_onb_done else R.string.calm_onb_next)

        val granted = status.isGranted(page)
        val notNeeded = page == OnboardingPage.EXACT_ALARMS && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        b.onbStatus.isVisible = granted != null
        b.onbStatus.text = getString(
            when {
                notNeeded -> R.string.calm_onb_not_needed
                granted == true -> R.string.calm_onb_allowed
                else -> R.string.calm_onb_not_allowed
            },
        )
        b.onbOpen.isVisible = granted == false && !notNeeded
        b.onbList.isVisible = page == OnboardingPage.EXEMPT_APPS
        if (page == OnboardingPage.EXEMPT_APPS) renderSuggestions()
    }

    private fun texts(page: OnboardingPage): Triple<Int, Int, Int> = when (page) {
        OnboardingPage.NOTIFICATIONS -> Triple(R.string.calm_onb_notifications_title, R.string.calm_onb_notifications_why, R.string.calm_onb_notifications_reduced)
        OnboardingPage.OVERLAY -> Triple(R.string.calm_onb_overlay_title, R.string.calm_onb_overlay_why, R.string.calm_onb_overlay_reduced)
        OnboardingPage.USAGE_ACCESS -> Triple(R.string.calm_onb_usage_title, R.string.calm_onb_usage_why, R.string.calm_onb_usage_reduced)
        OnboardingPage.EXACT_ALARMS -> Triple(R.string.calm_onb_alarms_title, R.string.calm_onb_alarms_why, R.string.calm_onb_alarms_reduced)
        OnboardingPage.BATTERY -> Triple(R.string.calm_onb_battery_title, R.string.calm_onb_battery_why, R.string.calm_onb_battery_reduced)
        OnboardingPage.EXEMPT_APPS -> Triple(R.string.calm_onb_exempt_title, R.string.calm_onb_exempt_why, R.string.calm_onb_exempt_reduced)
    }

    /** Installed messaging, banking, authenticator and transport apps, all ticked by default. */
    private fun renderSuggestions() {
        if (suggestions.isEmpty()) {
            val installed = runCatching {
                getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
                    .map { it.applicationInfo.packageName }.toSet()
            }.getOrDefault(emptySet())
            val mySerial = Apps.mySerial(this)
            suggestions = KnownApps.installed(installed).sortedBy { Apps.label(this, AppKey(it.first, mySerial)) }
            // Ticked by default: making an app exempt is the safe direction (Constitution II).
            selected.clear()
            selected += suggestions.map { it.first }
        }
        b.onbList.removeAllViews()
        if (suggestions.isEmpty()) {
            b.onbList.addView(TextView(this, null, 0, R.style.TextSmall).apply { text = getString(R.string.calm_onb_exempt_none) })
            return
        }
        val mySerial = Apps.mySerial(this)
        suggestions.forEach { (pkg, category) ->
            val row = TextView(this, null, 0, R.style.CalmButton)
            fun refresh() {
                val name = getString(R.string.calm_onb_exempt_row, Apps.label(this, AppKey(pkg, mySerial)), getString(categoryLabel(category)))
                row.text = (if (pkg in selected) "✓  " else "     ") + name
                row.alpha = if (pkg in selected) 1f else 0.6f
            }
            refresh()
            row.setOnClickListener {
                if (!selected.remove(pkg)) selected += pkg
                refresh()
            }
            b.onbList.addView(row)
        }
    }

    private fun openSetting(page: OnboardingPage) {
        val pkgUri = Uri.parse("package:$packageName")
        when (page) {
            OnboardingPage.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }

            OnboardingPage.OVERLAY -> open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
            OnboardingPage.USAGE_ACCESS -> open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            OnboardingPage.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
            }

            OnboardingPage.BATTERY -> openBatterySetting(pkgUri)
            OnboardingPage.EXEMPT_APPS -> Unit
        }
    }

    /** 🅟 The direct "allow unrestricted battery" dialog needs a Play-restricted permission; fine when sideloaded. */
    @SuppressLint("BatteryLife")
    private fun openBatterySetting(pkgUri: Uri) {
        open(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
        )
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        open(intent)
    }

    /** Tries each intent in turn; settings screens differ between Android versions and vendors. */
    private fun open(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                CalmLog.w("settings screen not available: ${intent.action}", e)
            }
        }
    }

    private fun categoryLabel(category: ExemptCategory): Int = when (category) {
        ExemptCategory.MESSAGING -> R.string.calm_category_messaging
        ExemptCategory.BANKING -> R.string.calm_category_banking
        ExemptCategory.AUTH -> R.string.calm_category_auth
        ExemptCategory.TRANSPORT -> R.string.calm_category_transport
        ExemptCategory.SYSTEM -> R.string.calm_category_system
    }

    companion object {
        private const val EXTRA_PAGE = "calm_page"

        /** Opens onboarding at [page] (or at the start). */
        fun open(context: Context, page: OnboardingPage?) {
            val intent = Intent(context, OnboardingActivity::class.java)
                .putExtra(EXTRA_PAGE, page?.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                CalmLog.w("could not open onboarding", e)
            }
        }
    }
}
