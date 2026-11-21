package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.AskDuration
import app.calmlauncher.engine.Decision.Blocked
import app.calmlauncher.engine.Effect.EndSession
import app.calmlauncher.engine.Effect.LaunchApp
import app.calmlauncher.engine.Effect.Log
import app.calmlauncher.engine.Effect.PersistSession
import app.calmlauncher.engine.Effect.ShowBlocked
import app.calmlauncher.engine.Effect.StartCountdown
import app.calmlauncher.engine.Fx.CFG
import app.calmlauncher.engine.Fx.INSTAGRAM
import app.calmlauncher.engine.Fx.INSTAGRAM_WORK
import app.calmlauncher.engine.Fx.NO_EXEMPTIONS
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.YOUTUBE
import app.calmlauncher.engine.Fx.min
import app.calmlauncher.engine.Fx.sec
import app.calmlauncher.engine.SessionEvent.Cancelled
import app.calmlauncher.engine.SessionEvent.DurationChosen
import app.calmlauncher.engine.SessionEvent.LaunchRequested
import app.calmlauncher.engine.SessionEvent.OverrideCompleted
import app.calmlauncher.engine.SessionEvent.OverrideReset
import app.calmlauncher.engine.SessionEvent.OverrideStarted
import app.calmlauncher.engine.SessionEvent.Tick
import app.calmlauncher.engine.SessionState.Cooldown
import app.calmlauncher.engine.SessionState.Idle
import app.calmlauncher.engine.SessionState.Prompting
import app.calmlauncher.engine.SessionState.Running
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Post-expiry cooldown (spec FR-028, research R10): rules engine step 7 and the cooldown screen. */
class CooldownTest {

    private val ask = AskDuration(TrackedApp.DEFAULT_PRESETS, 60)

    private fun decide(
        history: List<SessionRecord>,
        now: Long,
        app: AppKey = INSTAGRAM,
        tracked: Map<AppKey, TrackedApp> = Fx.tracked(INSTAGRAM, YOUTUBE, INSTAGRAM_WORK, cooldownMin = 10),
    ) = RulesEngine.decide(app, now, tracked, NO_EXEMPTIONS, history, CFG)

    private val expiredAtT0 = listOf(Fx.ended(INSTAGRAM, endedAt = T0, reason = EndReason.EXPIRED))

    @Test
    fun blockedInsideTheCooldownWindow() {
        assertEquals(Blocked(BlockKind.COOLDOWN, T0 + min(10)), decide(expiredAtT0, now = T0 + min(5)))
        assertEquals(Blocked(BlockKind.COOLDOWN, T0 + min(10)), decide(expiredAtT0, now = T0))
    }

    @Test
    fun askAgainOnceTheCooldownHasPassed() {
        assertEquals(ask, decide(expiredAtT0, now = T0 + min(10)))
        assertEquals(ask, decide(expiredAtT0, now = T0 + min(60)))
    }

    @Test
    fun cooldownOfZeroTurnsItOff() {
        val tracked = Fx.tracked(INSTAGRAM, cooldownMin = 0)
        assertEquals(ask, decide(expiredAtT0, now = T0 + sec(1), tracked = tracked))
    }

    @Test
    fun onlyExpiredSessionsStartACooldown() {
        for (reason in listOf(EndReason.ENDED_BY_USER, EndReason.LEFT_EARLY, EndReason.INTERRUPTED)) {
            val history = listOf(Fx.ended(INSTAGRAM, endedAt = T0, reason = reason))
            assertEquals("reason=$reason", ask, decide(history, now = T0 + min(1)))
        }
    }

    @Test
    fun aLaterNonExpiredSessionDoesNotLiftTheCooldown() {
        val history = expiredAtT0 + Fx.ended(INSTAGRAM, endedAt = T0 + min(3), reason = EndReason.LEFT_EARLY, minutes = 2)
        assertEquals(Blocked(BlockKind.COOLDOWN, T0 + min(10)), decide(history, now = T0 + min(5)))
    }

    @Test
    fun cooldownIsPerAppAndPerProfile() {
        assertEquals(ask, decide(expiredAtT0, now = T0 + min(5), app = YOUTUBE))
        assertEquals(ask, decide(expiredAtT0, now = T0 + min(5), app = INSTAGRAM_WORK))
    }

    @Test
    fun unclosedSessionPastItsDeadlineCountsAsExpired() {
        val history = listOf(Fx.active(INSTAGRAM, start = T0, minutes = 5))
        assertEquals(Blocked(BlockKind.COOLDOWN, T0 + min(15)), decide(history, now = T0 + min(6)))
    }

    @Test
    fun clockMovedBackwardsCannotCauseALongBlock() {
        val future = listOf(Fx.ended(INSTAGRAM, endedAt = T0 + min(600), reason = EndReason.EXPIRED))
        assertEquals(Blocked(BlockKind.COOLDOWN, T0 + min(10)), decide(future, now = T0))
    }

    // ------------------------------------------------------------- cooldown screen in the state machine

    private val blocked = Blocked(BlockKind.COOLDOWN, T0 + min(10))

    @Test
    fun blockedLaunchShowsTheCooldownScreen() {
        val t = SessionMachine.reduce(Idle, LaunchRequested(INSTAGRAM, blocked, 10), T0 + min(5), CFG)
        assertEquals(Prompting(INSTAGRAM, 10, Idle, blockedUntil = T0 + min(10)), t.state)
        assertEquals(
            listOf(ShowBlocked(INSTAGRAM, T0 + min(10)), Log(EventType.COOLDOWN_SHOWN, INSTAGRAM)),
            t.effects,
        )
    }

    @Test
    fun goHomeFromTheCooldownScreenIsNotACancelledPrompt() {
        val screen = Prompting(INSTAGRAM, 10, Idle, blockedUntil = T0 + min(10))
        val t = SessionMachine.reduce(screen, Cancelled, T0 + min(5), CFG)
        assertEquals(Idle, t.state)
        assertFalse(t.effects.any { it is Log && it.type == EventType.PROMPT_CANCELLED })
    }

    @Test
    fun durationCannotBeChosenOnTheCooldownScreen() {
        val screen = Prompting(INSTAGRAM, 10, Idle, blockedUntil = T0 + min(10))
        val t = SessionMachine.reduce(screen, DurationChosen(5), T0 + min(5), CFG)
        assertEquals(screen, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun overrideOnTheCooldownScreenStartsAFiveMinuteSession() {
        val screen = Prompting(INSTAGRAM, 10, Idle, blockedUntil = T0 + min(10))
        val waiting = SessionMachine.reduce(screen, OverrideStarted, T0 + min(5), CFG).state
        assertEquals(waiting, SessionMachine.reduce(waiting, OverrideCompleted, T0 + min(5) + sec(29), CFG).state)

        val now = T0 + min(5) + sec(30)
        val t = SessionMachine.reduce(waiting, OverrideCompleted, now, CFG)
        val granted = SessionRecord(key = INSTAGRAM, startedAt = now, declaredMs = min(5), deadlineAt = now + min(5), overrides = 1)
        assertEquals(Running(granted, 10), t.state)
        assertTrue(t.effects.contains(PersistSession(granted)))
        assertTrue(t.effects.contains(LaunchApp(INSTAGRAM)))
        assertTrue(t.effects.contains(StartCountdown(granted)))
        assertTrue(t.effects.contains(Log(EventType.OVERRIDE, INSTAGRAM)))
    }

    @Test
    fun overrideWaitOnTheCooldownScreenResetsWhenLeft() {
        val screen = Prompting(INSTAGRAM, 10, Idle, blockedUntil = T0 + min(10))
        val waiting = SessionMachine.reduce(screen, OverrideStarted, T0, CFG).state
        val reset = SessionMachine.reduce(waiting, OverrideReset, T0 + sec(20), CFG).state
        assertEquals(screen, reset)
        assertEquals(reset, SessionMachine.reduce(reset, OverrideCompleted, T0 + min(1), CFG).state)
    }

    @Test
    fun overrideOnTheCooldownScreenEndsAnotherRunningSession() {
        val youtube = Running(Fx.active(YOUTUBE, T0, 10), 10)
        val screen = Prompting(INSTAGRAM, 10, youtube, blockedUntil = T0 + min(10), overrideStartedAt = T0)
        val t = SessionMachine.reduce(screen, OverrideCompleted, T0 + sec(30), CFG)
        assertEquals(EndReason.LEFT_EARLY, t.effects.filterIsInstance<EndSession>().single().reason)
        assertEquals(INSTAGRAM, (t.state as Running).rec.key)
    }

    @Test
    fun cooldownStateEndsOnItsOwn() {
        val cd = Cooldown(INSTAGRAM, T0 + min(10))
        assertEquals(cd, SessionMachine.reduce(cd, Tick, T0 + min(9), CFG).state)
        assertEquals(Idle, SessionMachine.reduce(cd, Tick, T0 + min(10), CFG).state)
    }
}
