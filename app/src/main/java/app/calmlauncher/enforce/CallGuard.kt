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
import app.calmlauncher.access.AccessState
import app.calmlauncher.engine.CallRule
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
 * A running call blocks only while the session's app is not known to be in front: the call screen
 * is never covered, but a tracked app used during a call (the call in the background or in a small
 * floating window) is limited like at any other time. Only the app-limits accessibility service
 * can say which app is in front right now, so with its toggle off every call blocks. The rule
 * itself is the engine's [CallRule].
 *
 * It only watches while it matters: while the time's-up card is up (to hide it when a call
 * arrives) or while an expiry is deferred (to show the card when the call ends). On Android 12+
 * an audio-mode listener tells us about changes. ⚠️ Below Android 12 there is no such callback,
 * so we re-check every 5 s, only during those moments (plan.md Complexity Tracking). Screen on,
 * screen off and unlock come as broadcasts to SessionService, which calls [check]; a change of the
 * app in front comes from the accessibility service ([onForegroundChanged]).
 */
object CallGuard {

    private const val RECHECK_MS = 5_000L
    private const val SETTLE_MS = 2_000L
    private val RECHECK_TOKEN = Any()
    private val SETTLE_TOKEN = Any()

    private val handler = Handler(Looper.getMainLooper())
    private var watching = false
    private var modeListener: Any? = null

    /** Whether a phone or internet call rings or runs right now. */
    fun callState(context: Context): CallRule.Call =
        when (context.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL) {
            AudioManager.MODE_RINGTONE -> CallRule.Call.RINGING
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> CallRule.Call.ACTIVE
            else -> CallRule.Call.NONE
        }

    /**
     * True while our UI for the session of [pkg] must not be drawn. With [pkg] == null every call
     * blocks (used for the warning, which makes a sound).
     */
    fun isBlocked(context: Context, pkg: String? = null): Boolean {
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val screenOff = context.getSystemService(PowerManager::class.java)?.isInteractive == false
        // AccessState.inFront is null while the app-limits toggle is off: then every call blocks.
        val trackedInFront = pkg != null && AccessState.inFront == pkg
        return CallRule.blocks(callState(context), locked, screenOff, trackedInFront)
    }

    fun guardState(context: Context, pkg: String? = null): GuardState = GuardState { isBlocked(context, pkg) }

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
        val blocked = isBlocked(context, state.rec.key.pkg)
        when {
            state.deferred && !blocked -> settleThenClear(context)
            state.cardShown && blocked -> CalmGraph.repository.post(SessionEvent.InCallOrLocked)
        }
        if (watching && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) scheduleRecheck(context)
    }

    /**
     * The app-limits service saw the app in front change. During a call that decides whether a
     * waiting card may show (the tracked app came back) or a shown card must go (the call screen
     * came to the front).
     */
    fun onForegroundChanged(context: Context): Unit = check(context.applicationContext)

    /**
     * Right after a call ends, the call screen is often still the last app in usage history. Wait
     * a moment so the probe sees the app the user actually returns to, then check once more.
     */
    private fun settleThenClear(context: Context) {
        handler.removeCallbacksAndMessages(SETTLE_TOKEN)
        handler.postAtTime({
            failOpen("CallGuard.settle") {
                val state = CalmGraph.repository.sessionState as? SessionState.Expired
                if (state != null && state.deferred && !isBlocked(context, state.rec.key.pkg)) {
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
