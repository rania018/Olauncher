package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.Allow
import app.calmlauncher.engine.Decision.AskDuration
import app.calmlauncher.engine.Fx.CFG
import app.calmlauncher.engine.Fx.DIALER
import app.calmlauncher.engine.Fx.DIALER_EXEMPT
import app.calmlauncher.engine.Fx.INSTAGRAM
import app.calmlauncher.engine.Fx.INSTAGRAM_WORK
import app.calmlauncher.engine.Fx.NOTES
import app.calmlauncher.engine.Fx.NO_EXEMPTIONS
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.min
import app.calmlauncher.engine.Fx.tracked
import org.junit.Assert.assertEquals
import org.junit.Test

/** One test per MVP row of TECHNICAL_DESIGN §4.2, in check order. The first match wins. */
class RulesEngineTest {

    private fun decide(
        app: AppKey,
        now: Long = T0,
        tracked: Map<AppKey, TrackedApp> = tracked(INSTAGRAM),
        exemptions: Exemptions = NO_EXEMPTIONS,
        history: List<SessionRecord> = emptyList(),
        cfg: Config = CFG,
    ) = RulesEngine.decide(app, now, tracked, exemptions, history, cfg)

    // Check 1: exempt
    @Test
    fun exemptAppIsAllowed() {
        assertEquals(Allow(AllowReason.EXEMPT), decide(DIALER, exemptions = DIALER_EXEMPT))
    }

    @Test
    fun exemptWinsOverTracked() {
        assertEquals(
            Allow(AllowReason.EXEMPT),
            decide(DIALER, tracked = tracked(DIALER), exemptions = DIALER_EXEMPT),
        )
    }

    @Test
    fun userExemptionCountsToo() {
        val exemptions = Exemptions(user = setOf(INSTAGRAM.pkg))
        assertEquals(Allow(AllowReason.EXEMPT), decide(INSTAGRAM, exemptions = exemptions))
    }

    @Test
    fun exemptionCoversEveryProfileOfThePackage() {
        val exemptions = Exemptions(user = setOf(INSTAGRAM.pkg))
        assertEquals(
            Allow(AllowReason.EXEMPT),
            decide(INSTAGRAM_WORK, tracked = tracked(INSTAGRAM_WORK), exemptions = exemptions),
        )
    }

    // Check 3: not tracked
    @Test
    fun untrackedAppIsAllowed() {
        assertEquals(Allow(AllowReason.NOT_TRACKED), decide(NOTES))
    }

    @Test
    fun trackingIsPerProfile() {
        // Only the main-profile Instagram is tracked; the work-profile copy is a different app.
        assertEquals(Allow(AllowReason.NOT_TRACKED), decide(INSTAGRAM_WORK))
    }

    // Check 4: running
    @Test
    fun appWithRunningSessionIsAllowed() {
        val history = listOf(Fx.active(INSTAGRAM, start = T0, minutes = 5))
        assertEquals(Allow(AllowReason.RUNNING), decide(INSTAGRAM, now = T0 + min(2), history = history))
    }

    @Test
    fun runningSessionOfAnotherAppDoesNotCount() {
        val history = listOf(Fx.active(Fx.YOUTUBE, start = T0, minutes = 5))
        val decision = decide(INSTAGRAM, now = T0 + min(2), tracked = tracked(INSTAGRAM, Fx.YOUTUBE), history = history)
        assertEquals(AskDuration(TrackedApp.DEFAULT_PRESETS, 60), decision)
    }

    @Test
    fun activeSessionPastItsDeadlineIsNotRunning() {
        val history = listOf(Fx.active(INSTAGRAM, start = T0, minutes = 5))
        val decision = decide(INSTAGRAM, now = T0 + min(6), tracked = tracked(INSTAGRAM, cooldownMin = 0), history = history)
        assertEquals(AskDuration(TrackedApp.DEFAULT_PRESETS, 60), decision)
    }

    // Check 11: otherwise
    @Test
    fun trackedAppAsksForDurationWithItsPresets() {
        val tracked = mapOf(INSTAGRAM to TrackedApp(INSTAGRAM, listOf(1, 3, 20), 10))
        assertEquals(AskDuration(listOf(1, 3, 20), 60), decide(INSTAGRAM, tracked = tracked))
    }

    @Test
    fun customMaximumComesFromConfig() {
        assertEquals(
            AskDuration(TrackedApp.DEFAULT_PRESETS, 30),
            decide(INSTAGRAM, cfg = Config(customMaxMin = 30)),
        )
    }

    @Test
    fun finishedSessionsDoNotSkipThePrompt() {
        val history = listOf(Fx.ended(INSTAGRAM, endedAt = T0 - min(1), reason = EndReason.ENDED_BY_USER))
        assertEquals(AskDuration(TrackedApp.DEFAULT_PRESETS, 60), decide(INSTAGRAM, history = history))
    }
}
