package app.calmlauncher.enforce

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.Config
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.ui.CalmActivity
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.olauncher.R
import app.olauncher.databinding.CalmCardBinding

/**
 * The full-screen time's-up card (spec US3, contracts/ui-surfaces.md).
 *
 * An overlay window is a view our app adds straight to the screen with WindowManager, on top of
 * other apps, without starting an Activity. It needs the "Display over other apps" permission
 * (SYSTEM_ALERT_WINDOW). We use it because Android 10+ blocks starting an Activity from the
 * background, and a visible overlay also lets us send the user home (research R5).
 * ⚠️ Android 12+ blocks touches that pass through other apps' translucent overlays; our card is
 * opaque and handles every touch itself, so it is not affected.
 *
 * Safety (Constitution II, spec FR-020): Back and "Go home" always work, Home removes the card
 * (launcher resume hook), and the card removes itself after [Config.cardTimeoutSec] untouched.
 */
object OverlayController {

    /** Small margin so the UI never asks before the engine's own wait check has passed. */
    private const val WAIT_MARGIN_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private var root: CardRoot? = null
    private var windowManager: WindowManager? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = root != null

    fun show(context: Context, rec: SessionRecord, canExtend: Boolean, cfg: Config): Boolean =
        failOpen("OverlayController.show", false) {
            if (!canDraw(context)) return@failOpen false
            hide()
            val app = context.applicationContext
            val themed = CalmActivity.themedContext(app)
            val view = CardRoot(themed)
            val binding = CalmCardBinding.inflate(LayoutInflater.from(themed), view, true)
            Card(app, binding, view, rec, canExtend, cfg).bind()

            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            // Focusable (no FLAG_NOT_FOCUSABLE) so the card receives the Back key.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "Calm Launcher time's up"
            }
            val wm = app.getSystemService(WindowManager::class.java)
            wm.addView(view, params)
            windowManager = wm
            root = view
            view.requestFocus()
            restartTimeout(cfg)
            true
        }

    /** Removes the card if it is showing. Safe to call at any time. */
    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val view = root ?: return
        root = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            CalmLog.w("removing the card failed", e)
        }
    }

    private fun restartTimeout(cfg: Config) {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, cfg.cardTimeoutSec * 1000L)
    }

    private val timeout = Runnable {
        failOpen("OverlayController.timeout") {
            hide()
            CalmGraph.repository.dispatch(SessionEvent.CardTimedOut)
        }
    }

    /** Binds one card's buttons and its two waits (extension, override). */
    private class Card(
        private val context: Context,
        private val b: CalmCardBinding,
        private val root: CardRoot,
        private val rec: SessionRecord,
        private val canExtend: Boolean,
        private val cfg: Config,
    ) {
        private val repo get() = CalmGraph.repository
        private var overrideRunning = false

        fun bind() {
            val label = Apps.label(context, rec.key)
            val minutes = ((rec.deadlineAt - rec.startedAt) / 60_000L).toInt().coerceAtLeast(1)
            b.calmCardSubtitle.text = context.getString(R.string.calm_card_subtitle, minutes, label)

            b.calmCardHome.setOnClickListener { act { repo.dispatch(SessionEvent.WentHome) } }
            root.onBack = { act { repo.dispatch(SessionEvent.WentHome) } }
            root.onHidden = { if (overrideRunning) resetOverride() }

            b.calmCardExtend.isVisible = canExtend
            b.calmCardExtend.setOnClickListener { act { startExtensionWait() } }
            b.calmCardOverride.setOnClickListener { act { if (!overrideRunning) startOverride() } }
        }

        /** Every tap counts as interaction and restarts the self-removal timer. */
        private fun act(block: () -> Unit) = failOpen("OverlayController.card") {
            restartTimeout(cfg)
            block()
        }

        private fun startExtensionWait() {
            b.calmCardExtend.isVisible = false
            b.calmCardExtendRow.isVisible = true
            repo.dispatch(SessionEvent.ExtendStarted)
            val options = cfg.extensionOptionsMin.map { minutes ->
                TextView(b.root.context, null, 0, R.style.CalmButton).apply {
                    text = context.getString(R.string.calm_card_extend_option, minutes)
                    isEnabled = false
                    alpha = DISABLED_ALPHA
                    setOnClickListener { act { repo.dispatch(SessionEvent.Extended(minutes)) } }
                }.also { b.calmCardExtendOptions.addView(it) }
            }
            countdown(EXTEND_TOKEN, cfg.extensionWaitSec, onTick = { left ->
                b.calmCardExtendWait.text = context.getString(R.string.calm_card_extend_wait, left)
            }) {
                b.calmCardExtendWait.isVisible = false
                options.forEach { it.isEnabled = true; it.alpha = 1f }
            }
        }

        private fun startOverride() {
            overrideRunning = true
            repo.dispatch(SessionEvent.OverrideStarted)
            b.calmCardOverrideWait.isVisible = true
            countdown(OVERRIDE_TOKEN, cfg.overrideWaitSec, onTick = { left ->
                b.calmCardOverrideWait.text = context.getString(R.string.calm_override_wait, left)
            }) {
                overrideRunning = false
                repo.dispatch(SessionEvent.OverrideCompleted)
            }
        }

        /** The override wait starts over when the card is no longer visible (Constitution III). */
        private fun resetOverride() {
            overrideRunning = false
            handler.removeCallbacksAndMessages(OVERRIDE_TOKEN)
            b.calmCardOverrideWait.isVisible = false
            // Posted, not dispatched: this can run while the card is being removed inside dispatch().
            repo.post(SessionEvent.OverrideReset)
        }

        /**
         * Shows a visible countdown of [seconds], then runs [onDone]. Callbacks carry [token] so one
         * wait can be cancelled on its own; [hide] cancels them all.
         */
        private fun countdown(token: Any, seconds: Int, onTick: (Int) -> Unit, onDone: () -> Unit) {
            val end = SystemClock.uptimeMillis() + seconds * 1000L + WAIT_MARGIN_MS
            fun step() {
                val now = SystemClock.uptimeMillis()
                if (now >= end) {
                    failOpen("OverlayController.countdown") { onDone() }
                } else {
                    val left = ((end - WAIT_MARGIN_MS - now + 999) / 1000).toInt().coerceAtLeast(0)
                    onTick(left)
                    handler.postAtTime({ step() }, token, minOf(end, now + 1000L))
                }
            }
            step()
        }
    }

    /** The card's root view: turns Back into "Go home" and reports when the card stops being visible. */
    @SuppressLint("ViewConstructor")
    private class CardRoot(context: Context) : FrameLayout(context) {
        var onBack: () -> Unit = {}
        var onHidden: () -> Unit = {}

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE) onHidden()
        }
    }

    private const val DISABLED_ALPHA = 0.4f
    private val OVERRIDE_TOKEN = Any()
    private val EXTEND_TOKEN = Any()
}
