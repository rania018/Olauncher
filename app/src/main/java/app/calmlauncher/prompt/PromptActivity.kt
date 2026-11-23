package app.calmlauncher.prompt

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.AppKey
import app.calmlauncher.engine.MIN_SESSION_MIN
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.engine.SessionState
import app.calmlauncher.engine.activeRecord
import app.calmlauncher.enforce.GoHome
import app.calmlauncher.gate.AppLauncher
import app.calmlauncher.ui.CalmActivity
import app.calmlauncher.util.Apps
import app.calmlauncher.util.CalmLog
import app.calmlauncher.util.failOpen
import app.olauncher.R
import app.olauncher.databinding.CalmPromptBinding
import java.util.Date

/**
 * "How long?" before a tracked app opens, the cooldown screen, and the small session sheet
 * behind the countdown notification (contracts/ui-surfaces.md).
 *
 * An Activity is one screen of an app. The prompt is its own Activity rather than a dialog inside
 * the launcher, because the app drawer closes itself right after a tap, which would destroy a
 * dialog (research R1). It is started from the launcher's application context, so it needs
 * FLAG_ACTIVITY_NEW_TASK; together with `taskAffinity=""`, `excludeFromRecents` and
 * `launchMode="singleInstance"` in the manifest, it lives in its own hidden task, so only one
 * prompt exists and it never shows up in Recents.
 *
 * Every way out of a prompt either opens the app with a session or cancels: Cancel, Back and
 * Home all cancel and nothing opens (spec US1 sc. 3).
 */
class PromptActivity : CalmActivity() {

    private lateinit var b: CalmPromptBinding
    private val repo get() = CalmGraph.repository
    private val handler = Handler(Looper.getMainLooper())

    private var mode = MODE_DURATION
    private var key: AppKey? = null

    /** True once the user has made a choice, so leaving the screen does not cancel it again. */
    private var decided = false
    private var overrideRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = CalmPromptBinding.inflate(layoutInflater)
        setContentView(b.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = failOpen("PromptActivity.back") { leave() }
        })
        failOpen("PromptActivity.onCreate") { bind(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        failOpen("PromptActivity.onNewIntent") { bind(intent) }
    }

    override fun onPause() {
        super.onPause()
        // The override wait starts over whenever this screen loses visibility (Constitution III).
        if (overrideRunning) failOpen("PromptActivity.onPause") { resetOverride() }
    }

    /** Home or Recents pressed while the prompt is up: that is a cancel. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!decided && (mode == MODE_DURATION || mode == MODE_BLOCKED)) {
            failOpen("PromptActivity.onUserLeaveHint") {
                decided = true
                if (isOurPrompt()) repo.dispatch(SessionEvent.Cancelled)
                finishQuietly()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bind(intent: Intent) {
        handler.removeCallbacksAndMessages(null)
        decided = false
        overrideRunning = false
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DURATION
        key = intent.getStringExtra(EXTRA_PKG)?.let { AppKey(it, intent.getLongExtra(EXTRA_USER, 0)) }

        b.promptDuration.isVisible = false
        b.promptBlocked.isVisible = false
        b.promptEndNow.isVisible = false
        b.promptCustomError.isVisible = false
        b.promptOverrideWait.isVisible = false
        b.promptOverride.isVisible = true
        showIcon(key)

        when (mode) {
            MODE_DURATION -> bindDuration(intent)
            MODE_BLOCKED -> bindBlocked(intent)
            MODE_SESSION -> repo.whenLoaded { bindSession() }
            MODE_END_NOW -> repo.whenLoaded { endNow() }
            else -> finishQuietly()
        }
    }

    // ------------------------------------------------------------------------------- "How long?"

    private fun bindDuration(intent: Intent) {
        val key = key ?: return finishQuietly()
        val presets = intent.getIntArrayExtra(EXTRA_PRESETS)?.toList().orEmpty()
        val customMax = intent.getIntExtra(EXTRA_CUSTOM_MAX, 60)
        b.promptTitle.text = getString(R.string.calm_prompt_how_long)
        b.promptSubtitle.text = label(key)
        b.promptDuration.isVisible = true

        // Session lengths as chips on one line; the row wraps only if they do not fit.
        b.promptPresets.removeAllViews()
        presets.forEach { minutes ->
            val chip = TextView(this, null, 0, R.style.CalmChip).apply {
                text = getString(R.string.calm_minutes_short, minutes)
                setOnClickListener { failOpen("PromptActivity.preset") { choose(minutes) } }
            }
            b.promptPresets.addView(chip)
        }

        b.promptCustom.setText("")
        val submit = {
            failOpen("PromptActivity.custom") {
                val minutes = b.promptCustom.text.toString().trim().toIntOrNull()
                if (minutes == null || minutes !in MIN_SESSION_MIN..customMax) {
                    b.promptCustomError.text = getString(R.string.calm_prompt_custom_error, customMax)
                    b.promptCustomError.isVisible = true
                } else {
                    choose(minutes)
                }
            }
        }
        b.promptCustomOpen.setOnClickListener { submit() }
        b.promptCustom.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        b.promptPrimary.text = getString(R.string.calm_cancel)
        b.promptPrimary.setOnClickListener { failOpen("PromptActivity.cancel") { leave() } }
    }

    private fun choose(minutes: Int) {
        if (decided) return
        decided = true
        val key = key ?: return finishQuietly()
        if (isOurPrompt()) {
            // The engine starts the session and opens the app (LaunchApp effect).
            repo.dispatch(SessionEvent.DurationChosen(minutes))
        } else {
            // The prompt no longer matches the session state (e.g. the process restarted while it
            // was open). Fail open: just open the app.
            CalmLog.w("prompt out of date; opening ${key.pkg} without a session")
            AppLauncher.launch(this, key)
        }
        finishQuietly()
    }

    // --------------------------------------------------------------------------- cooldown screen

    private fun bindBlocked(intent: Intent) {
        val key = key ?: return finishQuietly()
        val until = intent.getLongExtra(EXTRA_UNTIL, 0)
        val label = label(key)
        b.promptTitle.text = getString(R.string.calm_blocked_title, label, time(until))
        b.promptSubtitle.text = getString(R.string.calm_override_hint, label, repo.config.overrideGrantMin)
        b.promptBlocked.isVisible = true
        b.promptOverride.setOnClickListener { failOpen("PromptActivity.override") { startOverride() } }
        b.promptPrimary.text = getString(R.string.calm_go_home)
        b.promptPrimary.setOnClickListener { failOpen("PromptActivity.goHome") { leave() } }
    }

    private fun startOverride() {
        if (overrideRunning || decided) return
        overrideRunning = true
        repo.dispatch(SessionEvent.OverrideStarted)
        b.promptOverride.isVisible = false
        b.promptOverrideWait.isVisible = true
        val waitSec = repo.config.overrideWaitSec
        val end = SystemClock.uptimeMillis() + waitSec * 1000L + WAIT_MARGIN_MS
        fun step() {
            val now = SystemClock.uptimeMillis()
            if (now >= end) {
                failOpen("PromptActivity.overrideDone") { completeOverride() }
            } else {
                val left = ((end - WAIT_MARGIN_MS - now + 999) / 1000).toInt().coerceAtLeast(0)
                b.promptOverrideWait.text = getString(R.string.calm_override_wait, left)
                handler.postAtTime({ step() }, minOf(end, now + 1000L))
            }
        }
        step()
    }

    private fun completeOverride() {
        overrideRunning = false
        decided = true
        // Always succeeds (Constitution III): the engine starts a 5-minute session and opens the app.
        repo.dispatch(SessionEvent.OverrideCompleted)
        val state = repo.state
        if (state is SessionState.Running && state.rec.key == key) {
            finishQuietly()
        } else {
            // Not accepted (should not happen): open the app anyway, fail open.
            key?.let { AppLauncher.launch(this, it) }
            finishQuietly()
        }
    }

    private fun resetOverride() {
        overrideRunning = false
        handler.removeCallbacksAndMessages(null)
        b.promptOverrideWait.isVisible = false
        b.promptOverride.isVisible = true
        repo.dispatch(SessionEvent.OverrideReset)
    }

    // ----------------------------------------------------------------------------- session sheet

    private fun bindSession() {
        val rec = repo.state.activeRecord
        b.promptPrimary.text = getString(R.string.calm_close)
        b.promptPrimary.setOnClickListener { finishQuietly() }
        if (rec == null) {
            b.promptTitle.text = getString(R.string.calm_session_over)
            b.promptSubtitle.text = ""
            return
        }
        showIcon(rec.key)
        b.promptTitle.text = label(rec.key)
        b.promptSubtitle.text = getString(R.string.calm_session_ends_at, time(rec.deadlineAt))
        b.promptEndNow.isVisible = true
        b.promptEndNow.setOnClickListener { failOpen("PromptActivity.endNow") { endNow() } }
    }

    /** "End now": the engine ends the session and sends the user home. */
    private fun endNow() {
        decided = true
        repo.dispatch(SessionEvent.EndedByUser)
        finishQuietly()
    }

    // ------------------------------------------------------------------------------------ common

    /** Cancel, Back or "Go home": nothing opens, and the user lands on the home screen. */
    private fun leave() {
        if (mode == MODE_DURATION || mode == MODE_BLOCKED) {
            if (!decided) {
                decided = true
                if (isOurPrompt()) repo.dispatch(SessionEvent.Cancelled)
            }
            GoHome.go(this)
        }
        finishQuietly()
    }

    private fun isOurPrompt(): Boolean {
        val s = repo.state
        return s is SessionState.Prompting && s.key == key
    }

    /** The app's own icon above the title, so it is clear at a glance which app is asking. */
    private fun showIcon(key: AppKey?) {
        val icon = key?.let { Apps.icon(this, it) }
        b.promptIcon.setImageDrawable(icon)
        b.promptIcon.isVisible = icon != null
    }

    private fun label(key: AppKey): String = AppLauncher.target(key)?.label ?: Apps.label(this, key)

    private fun time(at: Long): String = DateFormat.getTimeFormat(this).format(Date(at))

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        handler.removeCallbacksAndMessages(null)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_MODE = "calm_mode"
        private const val EXTRA_PKG = "calm_pkg"
        private const val EXTRA_USER = "calm_user"
        private const val EXTRA_PRESETS = "calm_presets"
        private const val EXTRA_CUSTOM_MAX = "calm_custom_max"
        private const val EXTRA_UNTIL = "calm_until"

        private const val MODE_DURATION = "DURATION"
        private const val MODE_BLOCKED = "BLOCKED"
        private const val MODE_SESSION = "SESSION"
        private const val MODE_END_NOW = "END_NOW"

        private const val WAIT_MARGIN_MS = 500L
        private const val RC_SESSION = 4401
        private const val RC_END_NOW = 4402

        private fun base(context: Context, mode: String) =
            Intent(context, PromptActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** Shows "How long?". Returns false if the screen could not be started. */
        fun showDuration(context: Context, key: AppKey, presets: List<Int>, customMax: Int): Boolean =
            start(
                context,
                base(context, MODE_DURATION)
                    .putExtra(EXTRA_PKG, key.pkg)
                    .putExtra(EXTRA_USER, key.userSerial)
                    .putExtra(EXTRA_PRESETS, presets.toIntArray())
                    .putExtra(EXTRA_CUSTOM_MAX, customMax),
            )

        /** Shows the cooldown screen. Returns false if the screen could not be started. */
        fun showBlocked(context: Context, key: AppKey, until: Long): Boolean =
            start(
                context,
                base(context, MODE_BLOCKED)
                    .putExtra(EXTRA_PKG, key.pkg)
                    .putExtra(EXTRA_USER, key.userSerial)
                    .putExtra(EXTRA_UNTIL, until),
            )

        fun sessionSheetPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, RC_SESSION, base(context, MODE_SESSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun endNowPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, RC_END_NOW, base(context, MODE_END_NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun start(context: Context, intent: Intent): Boolean = try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            CalmLog.e("could not show the prompt", e)
            false
        }
    }
}
