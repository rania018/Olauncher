package app.calmlauncher.session

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.calmlauncher.CalmGraph
import app.calmlauncher.CalmHooks
import app.calmlauncher.access.AccessState
import app.calmlauncher.engine.GraceCheck
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.engine.Wake
import app.calmlauncher.engine.activeRecord
import app.calmlauncher.enforce.CallGuard
import app.calmlauncher.enforce.ForegroundProbe
import app.calmlauncher.onboarding.PermissionStatus
import app.calmlauncher.util.Apps
import app.calmlauncher.util.failOpen
import app.calmlauncher.util.logError

/**
 * Keeps the session timer alive (research R4, TECHNICAL_DESIGN §5).
 *
 * A Service is an app component without a screen. A *foreground* service must show an ongoing
 * notification, and in return Android keeps the process alive while it runs. Ours exists only
 * while a session is active (Constitution V) and stops itself as soon as none is.
 *
 * It does not poll: the notification's clock is animated by the system, and the service only
 * posts callbacks for the moments the engine asked for (warning, deadline, grace check).
 *
 * ⚠️ Android 12+ only lets a foreground service start while the app is in the foreground (our
 * prompt, the launcher, the visible overlay) or from a few exempt events (boot, exact alarm).
 * ⚠️ Android 14+ requires a declared service type; ours is `specialUse`, with the explanation in
 * the manifest property PROPERTY_SPECIAL_USE_FGS_SUBTYPE (🅟 reviewed on Google Play; we sideload).
 * 🅢 One UI may still kill it under battery restrictions; reconciliation covers that.
 */
class SessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val repo get() = CalmGraph.repository
    private val onStateChange: () -> Unit = { reschedule() }
    private var receiver: BroadcastReceiver? = null
    private var appsCallback: LauncherApps.Callback? = null
    private var shownRecord: SessionRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        failOpen("SessionService.onCreate") {
            CalmGraph.init(this)
            repo.addListener(onStateChange)
            registerReceivers()
            registerPackageCallback()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        failOpen("SessionService.onStartCommand") {
            // Must be called promptly after startForegroundService(), even if we stop right away.
            goForeground(if (repo.isLoaded) repo.state.activeRecord else null)
            val restartedBySystem = intent == null
            val tick = intent?.action == ACTION_TICK
            repo.whenLoaded {
                if (restartedBySystem) repo.dispatch(SessionEvent.Reconcile(Apps.bootedAt()))
                if (tick) repo.dispatch(SessionEvent.Tick)
                reschedule()
            }
        }
        // If Android kills the process, restart the service; it then reconciles from the database.
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        failOpen("SessionService.onDestroy") {
            repo.removeListener(onStateChange)
            receiver?.let { unregisterReceiver(it) }
            appsCallback?.let { getSystemService(LauncherApps::class.java).unregisterCallback(it) }
            CallGuard.stop(this)
            TimeLeftOverlay.hide()
        }
        super.onDestroy()
    }

    private fun goForeground(rec: SessionRecord?) {
        val notification = Notifications.session(this, rec)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_SESSION, notification, type)
        shownRecord = rec
    }

    /** Re-arms the timer callbacks from the current state. Runs after every state change. */
    private fun reschedule(): Unit = failOpen("SessionService.reschedule") {
        handler.removeCallbacksAndMessages(null)
        val state = repo.sessionState
        CallGuard.sync(this, state)
        TimeLeftOverlay.sync(this, state, repo.timeLeftDisplay)
        val rec = state.activeRecord
        if (rec == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return@failOpen
        }
        if (rec.key != shownRecord?.key || rec.deadlineAt != shownRecord?.deadlineAt) goForeground(rec)

        val now = System.currentTimeMillis()
        for ((why, at) in repo.wakeups()) {
            handler.postDelayed({ fire(why, at) }, (at - now).coerceAtLeast(0))
        }
    }

    private fun fire(why: Wake, at: Long): Unit = failOpen("SessionService.fire") {
        if (System.currentTimeMillis() < at - EARLY_TOLERANCE_MS) {
            reschedule() // the wall clock moved; try again at the right time
            return@failOpen
        }
        repo.consumeWakeup(why)
        when (why) {
            Wake.WARNING, Wake.DEADLINE -> repo.dispatch(SessionEvent.Tick)
            Wake.GRACE -> graceCheck()
        }
    }

    /**
     * End of the "left early" grace period (research R16): is the session's app in front again?
     * Three sources answer: the app-limits service's last app in front (null while its toggle is
     * off), whether our home screen is still in front, and, with usage access, the last app in the
     * usage history. The engine's [GraceCheck] combines them; only a clear "no" ends the session.
     */
    private fun graceCheck() {
        val rec = repo.sessionState.activeRecord ?: return
        val pkg = rec.key.pkg
        if (!PermissionStatus.usageAccess(this)) {
            val inFront = GraceCheck.trackedInFront(pkg, AccessState.inFront, CalmHooks.launcherInFront, usageFront = null)
            repo.dispatch(SessionEvent.GraceElapsed(inFront))
            return
        }
        val app = applicationContext
        CalmGraph.loadThen({ _ ->
            val now = System.currentTimeMillis()
            ForegroundProbe.lastResumed(app, ForegroundProbe.windowStart(rec, now), now)
        }) { last ->
            // Read the live answers only now: the user may have come back while the query ran.
            val inFront = GraceCheck.trackedInFront(pkg, AccessState.inFront, CalmHooks.launcherInFront, last?.pkg)
            repo.dispatch(SessionEvent.GraceElapsed(inFront))
        }
    }

    /**
     * Broadcasts we only need while a session runs, registered here rather than in the manifest:
     * - screen on / off and unlock: catch up on timers that slept, and let CallGuard show or hide the card;
     * - clock or time-zone change: re-check the deadline (spec edge case "system clock changed").
     */
    private fun registerReceivers() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = failOpen("SessionService.receiver") {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> CallGuard.check(context)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        repo.dispatch(SessionEvent.Tick)
                        CallGuard.check(context)
                    }
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        repo.dispatch(SessionEvent.Reconcile(Apps.bootedAt()))
                        reschedule()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    /**
     * Uninstalling the session's app ends the session as INTERRUPTED. LauncherApps reports removals
     * for every user profile (a plain PACKAGE_REMOVED broadcast covers only the main profile) and
     * not for app updates.
     */
    private fun registerPackageCallback() {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = failOpen("SessionService.onPackageRemoved") {
                repo.dispatch(SessionEvent.AppUninstalled(Apps.keyOf(this@SessionService, packageName, user)))
            }

            override fun onPackageAdded(packageName: String, user: UserHandle) = Unit
            override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
        }
        getSystemService(LauncherApps::class.java).registerCallback(callback, handler)
        appsCallback = callback
    }

    companion object {
        const val ACTION_TICK = "app.calmlauncher.action.TICK"
        private const val EARLY_TOLERANCE_MS = 50L

        @Volatile
        var isRunning = false
            private set

        /**
         * Starts the service if it is not running yet ([action] forces a start command). It is
         * never stopped from outside: stopping a service before it has called startForeground()
         * crashes the app on Android 8+, so it always stops itself.
         */
        fun ensureRunning(context: Context, action: String? = null) {
            if (isRunning && action == null) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java).setAction(action))
            } catch (e: Exception) {
                // ⚠️ Android 12+: ForegroundServiceStartNotAllowedException from the background.
                // Fail open: the backup alarm and the next launcher resume still close the session.
                logError("SessionService.start", e)
            }
        }
    }
}
