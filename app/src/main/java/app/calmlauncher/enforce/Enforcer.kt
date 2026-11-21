package app.calmlauncher.enforce

import android.content.Context
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.EventType
import app.calmlauncher.engine.SessionEvent
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.session.Notifications
import app.calmlauncher.util.logEvent

/**
 * Carries out the engine's warning and expiry effects, always through [ShowGate] (T052): never
 * over an exempt app, never during a call or over the lock screen, never in a loop. Every
 * outcome is reported back to the session as an event, posted so it never re-enters dispatch.
 */
object Enforcer {

    /** One loop guard for the whole process (T053). */
    val loopGuard = LoopGuard()

    private val repo get() = CalmGraph.repository

    private fun gate(context: Context, pkg: String, isEnforcement: Boolean): ShowGate.Verdict {
        val now = System.currentTimeMillis()
        val wasPaused = loopGuard.isPaused(pkg, now)
        val verdict = ShowGate.check(pkg, CallGuard.guardState(context), repo::isExempt, loopGuard, now, isEnforcement)
        if (!wasPaused && loopGuard.isPaused(pkg, now)) logEvent(EventType.LOOP_GUARD, pkg)
        return verdict
    }

    /** The heads-up warning. Skipped, not delayed, during a call or on the lock screen. */
    fun warn(context: Context, rec: SessionRecord) {
        if (gate(context, rec.key.pkg, isEnforcement = false) != ShowGate.Verdict.SHOW) return
        Notifications.showWarning(context, rec, repo.config.warningLeadSec)
    }

    /** At the deadline: may we draw, and is the tracked app really in front? */
    fun probe(context: Context, rec: SessionRecord) {
        when (gate(context, rec.key.pkg, isEnforcement = false)) {
            ShowGate.Verdict.SKIP -> repo.post(SessionEvent.ForegroundUnknown)
            ShowGate.Verdict.DEFER -> repo.post(SessionEvent.InCallOrLocked)
            ShowGate.Verdict.SHOW -> ForegroundProbe.probe(context, rec) { result ->
                when (result) {
                    ForegroundProbe.Result.TRACKED -> approveCard(context, rec)
                    ForegroundProbe.Result.OTHER -> repo.post(SessionEvent.ForegroundIsOther)
                    ForegroundProbe.Result.UNKNOWN -> {
                        // We cannot tell what is in front: no card (fail open), only a notification.
                        Notifications.showExpiryFallback(context, rec)
                        repo.post(SessionEvent.ForegroundUnknown)
                    }
                }
            }
        }
    }

    /** Checks once more right before drawing: a call may have started during the probe. */
    private fun approveCard(context: Context, rec: SessionRecord) {
        when (gate(context, rec.key.pkg, isEnforcement = true)) {
            ShowGate.Verdict.SKIP -> repo.post(SessionEvent.ForegroundUnknown)
            ShowGate.Verdict.DEFER -> repo.post(SessionEvent.InCallOrLocked)
            ShowGate.Verdict.SHOW -> if (OverlayController.canDraw(context)) {
                repo.post(SessionEvent.ForegroundIsTracked)
            } else {
                Notifications.showExpiryFallback(context, rec)
                repo.post(SessionEvent.CardUnavailable)
            }
        }
    }

    /** Draws the card the engine asked for, or falls back to the notification. */
    fun showCard(context: Context, rec: SessionRecord, canExtend: Boolean) {
        if (!OverlayController.show(context, rec, canExtend, repo.config)) {
            Notifications.showExpiryFallback(context, rec)
            repo.post(SessionEvent.CardUnavailable)
        }
    }
}
