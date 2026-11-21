package app.calmlauncher

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.work.WorkManager
import app.calmlauncher.data.SettingsStore
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.enforce.Enforcer
import app.calmlauncher.enforce.OverlayController
import app.calmlauncher.onboarding.HealthLine
import app.calmlauncher.onboarding.OnboardingActivity
import app.calmlauncher.onboarding.PermissionStatus
import app.calmlauncher.util.Apps
import app.calmlauncher.util.failOpen
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.helper.isOlauncherDefault

/** Hooks called from upstream `MainActivity` and `HomeFragment` (contracts/engine-api.md). */
object CalmHooks {

    /** True while the launcher's home screen is in front. Used by the "left early" grace check. */
    @Volatile
    var launcherInFront = false
        private set

    private var wallpaperWorkerCancelled = false

    /**
     * Called from upstream MainActivity.onResume(). Removes a stray time's-up card (Home was
     * pressed while it showed), reconciles the session with the clock, starts the "left early"
     * grace check, counts unplanned opens, and shows onboarding once.
     */
    fun onLauncherResumed(activity: Activity) = failOpen("CalmHooks.onLauncherResumed") {
        CalmGraph.init(activity)
        launcherInFront = true
        stopWallpaperWorkerOnce(activity)
        // Home was pressed while the card was up: the launcher is in front, so the card goes (T047).
        OverlayController.hide()
        val repo = CalmGraph.repository
        repo.whenLoaded {
            if (!launcherInFront) return@whenLoaded
            repo.dispatch(SessionEvent.Reconcile(Apps.bootedAt()))
            repo.dispatch(SessionEvent.LauncherResumed)
            repo.refreshDefaultExemptions()
            repo.countUnplannedOpens()
            maybeStartOnboarding(activity)
        }
    }

    /** Called from upstream MainActivity.onPause(): cancels the "left early" grace check. */
    fun onLauncherPaused(activity: Activity) = failOpen("CalmHooks.onLauncherPaused") {
        CalmGraph.init(activity)
        launcherInFront = false
        val repo = CalmGraph.repository
        if (repo.isLoaded) repo.dispatch(SessionEvent.LauncherPaused)
    }

    /** Text for the home health line, or null when everything is fine (spec FR-023). */
    fun healthLine(context: Context): String? = failOpen("CalmHooks.healthLine", null) { currentHealthItem(context)?.text }

    private fun currentHealthItem(context: Context): HealthLine.Item? {
        CalmGraph.init(context)
        val repo = CalmGraph.repository
        if (!repo.isLoaded) return null
        val now = System.currentTimeMillis()
        return HealthLine.pick(
            status = PermissionStatus.read(context),
            notice = repo.settings.healthNotice,
            loopGuardUntil = Enforcer.loopGuard.pausedUntil(now),
            trackingInUse = repo.tracked.isNotEmpty(),
            now = now,
        )
    }

    /**
     * Binds the health line view on the home screen (one call from upstream HomeFragment.onResume).
     * Tapping the line opens the onboarding page that fixes it.
     */
    fun bindHealthLine(view: TextView): Unit = failOpen("CalmHooks.bindHealthLine") {
        val context = view.context
        CalmGraph.init(context)
        val repo = CalmGraph.repository
        if (!repo.isLoaded) {
            view.visibility = View.GONE
            repo.whenLoaded { bindHealthLine(view) }
            return@failOpen
        }
        val item = currentHealthItem(context)
        if (item == null) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            return@failOpen
        }
        view.text = item.text
        view.visibility = View.VISIBLE
        // The line sits under the clock; keep it visible even if the clock and date are turned off.
        (view.parent as? View)?.visibility = View.VISIBLE
        view.setOnClickListener {
            failOpen("CalmHooks.healthLineClick") {
                if (item.clearsNotice) repo.setHealthNotice(null)
                OnboardingActivity.open(context, item.page)
            }
        }
    }

    /** Onboarding opens once, after Calm Launcher has been made the Home app (avoids the chooser). */
    private fun maybeStartOnboarding(activity: Activity) {
        val repo = CalmGraph.repository
        if (repo.settings.onboardingVersion >= SettingsStore.ONBOARDING_CURRENT) return
        if (!isOlauncherDefault(activity)) return
        repo.markOnboardingShown()
        OnboardingActivity.open(activity, null)
    }

    /** No network wallpaper (Constitution I): cancel upstream's worker once per process. */
    private fun stopWallpaperWorkerOnce(context: Context) {
        if (wallpaperWorkerCancelled) return
        wallpaperWorkerCancelled = true
        WorkManager.getInstance(context).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
        Prefs(context).dailyWallpaper = false
    }
}
