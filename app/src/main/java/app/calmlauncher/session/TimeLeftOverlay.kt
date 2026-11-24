package app.calmlauncher.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.calmlauncher.access.AccessState
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.engine.SessionState
import app.calmlauncher.engine.session
import app.calmlauncher.enforce.CallGuard
import app.calmlauncher.prompt.PromptActivity
import app.calmlauncher.ui.CalmActivity
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.olauncher.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The on-screen reminder of the time left in a session (spec FR-029), as the user chose:
 * - **pill**: a solid rounded label in the accent colour ("4 min left") with a ring that empties
 *   as time runs out, in a top corner. It can be dragged aside, and a tap opens the session sheet
 *   with "End now". Only its own small area takes touches.
 * - **bar**: a 6 dp line under the status bar, in the accent colour on a dark track, that shrinks
 *   as time runs out. It never takes touches.
 * Both turn amber from the warning on (the last minute by default) and stand out over white and
 * black apps alike (owner feedback: the first, monochrome version was nearly invisible).
 *
 * Both are overlay windows (see OverlayController for what that means). ⚠️ Since Android 12 the
 * system drops touches that would pass *through* another app's overlay unless the overlay is at
 * most 80% opaque, so the touch-through bar window uses alpha 0.8. The pill takes its own touches,
 * so it can be fully opaque.
 *
 * It is shown only while a session counts down: not with the time's-up card, not on the lock
 * screen, and not over a call. With the app-limits toggle on it also hides while the user is away
 * from the tracked app, on the home screen or in another app (TimeLeft.shouldShow, research R17). A ringing call always hides it; a running call hides it unless the
 * tracked app itself is in front (CallGuard; an audio-mode listener on Android 12+ and the
 * app-limits service tell us at once when that changes). It is redrawn only when its text or
 * length changes (TimeLeft.nextRefreshMs), never every frame.
 */
object TimeLeftOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var pill: TextView? = null
    private var ring: RingView? = null
    private var fill: View? = null
    private var shownAs: TimeLeft.Display? = null
    private var rec: SessionRecord? = null
    private var warning = false
    private var colors: Colors? = null
    private var modeListener: Any? = null

    /** Where the user dragged the pill to (from the top-right corner), kept while the app runs. */
    private var pillOffsetX = -1
    private var pillOffsetY = -1

    private data class Colors(val accent: Int, val onAccent: Int, val warn: Int, val onWarn: Int)

    /** Shows, updates or hides the reminder for [state]. Called after every session change. */
    fun sync(context: Context, state: SessionState, display: TimeLeft.Display): Unit = failOpen("TimeLeftOverlay.sync") {
        val app = context.applicationContext
        appContext = app
        val session = state.session()
        val counting: SessionRecord?
        val graceCheckAt: Long?
        when (session) {
            is SessionState.Running -> {
                counting = session.rec
                graceCheckAt = session.graceCheckAt
            }

            is SessionState.Warning -> {
                counting = session.rec
                graceCheckAt = session.graceCheckAt
            }

            else -> { // idle, prompt, cooldown, or the time's-up card is up
                counting = null
                graceCheckAt = null
            }
        }
        // With the app-limits toggle on, the reminder shows only in the tracked app (research R17).
        val show = TimeLeft.shouldShow(counting != null, graceCheckAt, AccessState.connected)
        val wanted = display.takeIf { show && it != TimeLeft.Display.OFF && Settings.canDrawOverlays(app) }
        if (wanted == null) {
            hide()
            return@failOpen
        }
        if (root == null || shownAs != wanted) {
            hide()
            show(app, wanted)
        }
        // The switch to amber is the only cue at the warning: no custom animation (Constitution IV).
        warning = session is SessionState.Warning
        rec = counting
        watchCalls(app)
        refresh()
    }

    /**
     * The app-limits service saw the app in front change. During a call that decides whether the
     * reminder may show: over the tracked app yes, over the call screen or anything else no.
     */
    fun onForegroundChanged(): Unit = failOpen("TimeLeftOverlay.foreground") {
        if (root != null) refresh()
    }

    /** Draws the reminder again from scratch, e.g. after the accent colour changed. */
    fun restyle(context: Context, state: SessionState, display: TimeLeft.Display) {
        hide()
        sync(context, state, display)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        appContext?.let { unwatchCalls(it) }
        val view = root ?: return
        root = null
        pill = null
        ring = null
        fill = null
        shownAs = null
        rec = null
        warning = false
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            CalmLog.w("removing the time-left reminder failed", e)
        }
    }

    private fun show(app: Context, display: TimeLeft.Display) {
        val themed = CalmActivity.themedContext(app)
        colors = resolveColors(themed)
        val wm = app.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val statusBar = statusBarHeight(app)
        val params: WindowManager.LayoutParams
        val view: View
        if (display == TimeLeft.Display.PILL) {
            // Room around the pill so its shadow is not cut off by the window edge.
            val shadowRoom = dp(app, 8)
            val frame = FrameLayout(themed).apply { setPadding(shadowRoom, shadowRoom, shadowRoom, shadowRoom) }
            val chip = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(app, 12), dp(app, 8), dp(app, 16), dp(app, 8))
                background = GradientDrawable().apply { cornerRadius = dp(app, 999).toFloat() }
                elevation = dp(app, 6).toFloat()
            }
            val progress = RingView(themed).apply { stroke = dp(app, 3).toFloat() }
            chip.addView(progress, LinearLayout.LayoutParams(dp(app, 20), dp(app, 20)).apply { marginEnd = dp(app, 8) })
            val label = TextView(themed).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            }
            chip.addView(label)
            frame.addView(chip)
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // Not focusable and not modal: everything outside the pill goes to the app below.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = if (pillOffsetX >= 0) pillOffsetX else dp(app, 4)
                y = if (pillOffsetY >= 0) pillOffsetY else statusBar
                title = "Calm Launcher time left"
            }
            makeDraggable(app, touchArea = chip, window = frame, params = params, wm = wm)
            pill = label
            ring = progress
            view = frame
        } else {
            val height = dp(app, BAR_HEIGHT_DP)
            val track = FrameLayout(themed).apply {
                setBackgroundColor(ContextCompat.getColor(themed, R.color.calm_bar_track))
            }
            val line = View(themed).apply {
                background = GradientDrawable().apply {
                    val r = height / 2f
                    // Square at the start, rounded at the moving end.
                    cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }
            }
            track.addView(line, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = statusBar
                alpha = 0.8f // ⚠️ Android 12+: touches pass through only overlays at most 80% opaque
                title = "Calm Launcher time left"
            }
            fill = line
            view = track
        }
        wm.addView(view, params)
        windowManager = wm
        root = view
        shownAs = display
    }

    private fun resolveColors(themed: Context): Colors {
        val a = themed.obtainStyledAttributes(intArrayOf(R.attr.calmAccent, R.attr.calmOnAccent))
        try {
            return Colors(
                accent = a.getColor(0, ContextCompat.getColor(themed, R.color.calm_accent_teal)),
                onAccent = a.getColor(1, ContextCompat.getColor(themed, R.color.calm_on_accent)),
                warn = ContextCompat.getColor(themed, R.color.calm_warn),
                onWarn = ContextCompat.getColor(themed, R.color.calm_on_warn),
            )
        } finally {
            a.recycle()
        }
    }

    /** Redraws the text, ring or length and the colours, then waits until the next visible change. */
    private fun refresh() {
        handler.removeCallbacksAndMessages(null)
        val rec = rec ?: return
        val app = appContext ?: return
        val display = shownAs ?: return
        val c = colors ?: return
        val now = System.currentTimeMillis()
        // A running call hides the reminder unless the session's app is the one in front.
        val blocked = CallGuard.isBlocked(app, rec.key.pkg)
        root?.visibility = if (blocked) View.GONE else View.VISIBLE
        val fillColor = if (warning) c.warn else c.accent
        val textColor = if (warning) c.onWarn else c.onAccent
        val fraction = TimeLeft.fraction(rec, now)
        pill?.let { label ->
            label.text = app.getString(R.string.calm_time_left_pill_text, TimeLeft.label(rec.deadlineAt - now))
            label.setTextColor(textColor)
            ((label.parent as? View)?.background as? GradientDrawable)?.setColor(fillColor)
        }
        ring?.let {
            it.color = textColor
            it.fraction = fraction
        }
        fill?.let { line ->
            (line.background as? GradientDrawable)?.setColor(fillColor)
            val width = root?.width?.takeIf { it > 0 } ?: app.resources.displayMetrics.widthPixels
            val lp = line.layoutParams
            lp.width = (width * fraction).roundToInt().coerceAtLeast(dp(app, BAR_HEIGHT_DP))
            line.layoutParams = lp
        }
        var next = TimeLeft.nextRefreshMs(display, rec, now) ?: return
        // Below Android 12 there is no call callback: while hidden for a call, look again every 5 s.
        if (blocked && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) next = minOf(next, 5_000L)
        handler.postDelayed({ failOpen("TimeLeftOverlay.refresh") { refresh() } }, next)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(app: Context, touchArea: View, window: View, params: WindowManager.LayoutParams, wm: WindowManager) {
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        touchArea.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // Gravity is top-right, so x grows towards the left.
                        params.x = (startX - dx).roundToInt().coerceAtLeast(0)
                        params.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        pillOffsetX = params.x
                        pillOffsetY = params.y
                        failOpen("TimeLeftOverlay.drag") { wm.updateViewLayout(window, params) }
                    }
                }

                MotionEvent.ACTION_UP -> if (!dragging) {
                    failOpen("TimeLeftOverlay.tap") { PromptActivity.sessionSheetPendingIntent(app).send() }
                }
            }
            true
        }
    }

    /** On Android 12+, hide at once when a call starts and come back when it ends. */
    private fun watchCalls(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || modeListener != null) return
        addModeListener(app)
    }

    private fun unwatchCalls(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        removeModeListener(app)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun addModeListener(app: Context) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val listener = AudioManager.OnModeChangedListener { failOpen("TimeLeftOverlay.mode") { refresh() } }
        audio.addOnModeChangedListener(app.mainExecutor, listener)
        modeListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun removeModeListener(app: Context) {
        val listener = modeListener as? AudioManager.OnModeChangedListener ?: return
        app.getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(listener)
        modeListener = null
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 24)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private const val BAR_HEIGHT_DP = 6

    /** A small ring that empties clockwise as the session runs out: a faint track and the part left. */
    private class RingView(context: Context) : View(context) {
        var stroke = 6f
        var color = Color.WHITE
            set(value) {
                field = value
                invalidate()
            }
        var fraction = 1f
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = stroke / 2f
            box.set(inset, inset, width - inset, height - inset)
            paint.strokeWidth = stroke
            paint.color = color
            paint.alpha = 90
            canvas.drawOval(box, paint)
            paint.alpha = 255
            canvas.drawArc(box, -90f, 360f * fraction, false, paint)
        }
    }
}
