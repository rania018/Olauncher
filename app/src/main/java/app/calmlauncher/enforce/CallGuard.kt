package app.calmlauncher.enforce

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.engine.SessionState
import app.calmlauncher.util.failOpen

/**
 * Keeps our UI away from calls and the lock screen (spec FR-018, research R7).
 *
 * - Calls: AudioManager's mode is RINGTONE, IN_CALL or IN_COMMUNICATION while a phone or internet
 *   (VoIP, e.g. WhatsApp) call rings or runs. Reading it needs no permission.
 * - Lock screen: KeyguardManager.isKeyguardLocked. Screen off: PowerManager.isInteractive.
 *
 * It only watches while it matters: while the time's-up card is up (to hide it when a call
 * arrives) or while an expiry is deferred (to show the card when the call ends). On Android 12+
 * an audio-mode listener tells us about changes. ⚠️ Below Android 12 there is no such callback,
 * so we re-check every 5 s, only during those moments (plan.md Complexity Tracking). Screen on,
 * screen off and unlock come as broadcasts to SessionService, which calls [check].
 */
object CallGuard {

    private const val RECHECK_MS = 5_000L
    private const val SETTLE_MS = 2_000L
    private val RECHECK_TOKEN = Any()
    private val SETTLE_TOKEN = Any()

    private val handler = Handler(Looper.getMainLooper())
    private var watching = false
    private var modeListener: Any? = null

    fun isBlocked(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        val inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val screenOff = context.getSystemService(PowerManager::class.java)?.isInteractive == false
        return inCall || locked || screenOff
    }

    fun guardState(context: Context): GuardState = GuardState { isBlocked(context) }

    /** Watches only while the card is up or an expiry is deferred. Called after every state change. */
    fun sync(context: Context, state: SessionState = CalmGraph.repository.sessionState) {
        val needed = state is SessionState.Expired && (state.deferred || state.cardShown)
        if (needed) start(context.applicationContext) else stop(context.applicationContext)
    }

    /** Re-evaluates now, and tells the session if something changed. */
    fun check(context: Context) = failOpen("CallGuard.check") {
        val state = CalmGraph.repository.sessionState as? SessionState.Expired
        if (state == null) {
            stop(context)
            return@failOpen
        }
        val blocked = isBlocked(context)
        when {
            state.deferred && !blocked -> settleThenClear(context)
            state.cardShown && blocked -> CalmGraph.repository.post(SessionEvent.InCallOrLocked)
        }
        if (watching && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) scheduleRecheck(context)
    }

    /**
     * Right after a call ends, the call screen is often still the last app in usage history. Wait
     * a moment so the probe sees the app the user actually returns to, then check once more.
     */
    private fun settleThenClear(context: Context) {
        handler.removeCallbacksAndMessages(SETTLE_TOKEN)
        handler.postAtTime({
            failOpen("CallGuard.settle") {
                val state = CalmGraph.repository.sessionState as? SessionState.Expired
                if (state != null && state.deferred && !isBlocked(context)) {
                    CalmGraph.repository.post(SessionEvent.GuardCleared)
                }
            }
        }, SETTLE_TOKEN, SystemClock.uptimeMillis() + SETTLE_MS)
    }

    private fun start(context: Context) {
        if (watching) return
        watching = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addModeListener(context) else scheduleRecheck(context)
    }

    fun stop(context: Context) {
        if (!watching) return
        watching = false
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) removeModeListener(context)
    }

    private fun scheduleRecheck(context: Context) {
        handler.removeCallbacksAndMessages(RECHECK_TOKEN)
        handler.postAtTime({ check(context) }, RECHECK_TOKEN, SystemClock.uptimeMillis() + RECHECK_MS)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun addModeListener(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val listener = AudioManager.OnModeChangedListener { check(context) }
        audio.addOnModeChangedListener(context.mainExecutor, listener)
        modeListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun removeModeListener(context: Context) {
        val listener = modeListener as? AudioManager.OnModeChangedListener ?: return
        context.getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(listener)
        modeListener = null
    }
}
