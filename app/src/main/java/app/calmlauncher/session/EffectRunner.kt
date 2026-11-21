package app.calmlauncher.session

import android.content.Context
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.Effect
import app.calmlauncher.enforce.CallGuard
import app.calmlauncher.enforce.Enforcer
import app.calmlauncher.enforce.GoHome
import app.calmlauncher.enforce.OverlayController
import app.calmlauncher.gate.AppLauncher
import app.calmlauncher.prompt.PromptActivity

/**
 * Turns the engine's Android-facing effects into Android calls. Database and log effects are
 * handled by [SessionRepository]; timer effects (ScheduleAt) are read by [SessionService].
 * The repository wraps each call in failOpen, so one failing effect never stops the others.
 */
object EffectRunner {

    fun run(context: Context, effect: Effect) {
        when (effect) {
            is Effect.ShowPrompt ->
                if (!PromptActivity.showDuration(context, effect.key, effect.presetsMin, effect.customMaxMin)) {
                    CalmGraph.repository.promptFailed()
                }

            is Effect.ShowBlocked ->
                if (!PromptActivity.showBlocked(context, effect.key, effect.until)) CalmGraph.repository.promptFailed()

            is Effect.LaunchApp -> AppLauncher.launch(context, effect.key)

            is Effect.StartCountdown -> {
                SessionService.ensureRunning(context)
                if (SessionService.isRunning) Notifications.updateSession(context, effect.rec)
                SessionAlarmReceiver.schedule(context, effect.rec.deadlineAt)
            }

            // The service notices the state change and stops itself (never stopped from outside,
            // see SessionService). Only the backup alarm and a pending warning are cleared here.
            Effect.StopCountdown -> {
                SessionAlarmReceiver.cancel(context)
                Notifications.cancelWarning(context)
            }

            is Effect.ShowWarning -> Enforcer.warn(context, effect.rec)
            is Effect.ProbeForegroundThenShowCard -> Enforcer.probe(context, effect.rec)
            is Effect.ShowCard -> Enforcer.showCard(context, effect.rec, effect.canExtend)
            Effect.HideCard -> OverlayController.hide()
            is Effect.DeferUntilClear -> CallGuard.sync(context)
            Effect.GoHome -> GoHome.go(context)

            is Effect.ScheduleAt, is Effect.CancelSchedule,
            is Effect.PersistSession, is Effect.EndSession, is Effect.Log -> Unit
        }
    }
}
