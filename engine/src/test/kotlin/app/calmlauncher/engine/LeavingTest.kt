package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.Allow
import app.calmlauncher.engine.Decision.AskDuration
import app.calmlauncher.engine.Effect.CancelSchedule
import app.calmlauncher.engine.Effect.EndSession
import app.calmlauncher.engine.Effect.GoHome
import app.calmlauncher.engine.Effect.LaunchApp
import app.calmlauncher.engine.Effect.ScheduleAt
import app.calmlauncher.engine.Effect.StopCountdown
import app.calmlauncher.engine.Fx.CFG
import app.calmlauncher.engine.Fx.INSTAGRAM
import app.calmlauncher.engine.Fx.INSTAGRAM_WORK
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.YOUTUBE
import app.calmlauncher.engine.Fx.min
import app.calmlauncher.engine.Fx.sec
import app.calmlauncher.engine.SessionEvent.DurationChosen
import app.calmlauncher.engine.SessionEvent.ForegroundChanged
import app.calmlauncher.engine.SessionEvent.GraceElapsed
import app.calmlauncher.engine.SessionEvent.LaunchRequested
import app.calmlauncher.engine.SessionEvent.LauncherPaused
import app.calmlauncher.engine.SessionEvent.LauncherResumed
import app.calmlauncher.engine.SessionState.Cooldown
import app.calmlauncher.engine.SessionState.Expired
import app.calmlauncher.engine.SessionState.Idle
import app.calmlauncher.engine.SessionState.Prompting
import app.calmlauncher.engine.SessionState.Running
import app.calmlauncher.engine.SessionState.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T092: leaving the session's app for the home screen or for any other app (spec FR-015,
 * research R16, contracts/engine-api invariants 8 and 9). Being away for the whole grace period
 * ends the session as "left early"; coming back within it continues the same session.
 */
class LeavingTest {

    private fun reduce(state: SessionState, event: SessionEvent, now: Long = T0) =
        SessionMachine.reduce(state, event, now, CFG)

    private val whatsapp = "com.whatsapp"
    private val chrome = "com.android.chrome"

    /** A 10-minute Instagram session that started at T0. */
    private val rec = Fx.active(minutes = 10)
    private val running = Running(rec, cooldownMin = 10)

    /** The same session after the user switched to another app one minute in. */
    private val away = Running(rec.copy(leftAt = T0 + min(1)), cooldownMin = 10, graceCheckAt = T0 + min(3))

    private fun assertUnchanged(state: SessionState, t: Transition) {
        assertEquals(state, t.state)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    // ----------------------------------------------------------------------- the grace starts

    @Test
    fun switchingToAnotherAppStartsTheGrace() {
        val t = reduce(running, ForegroundChanged(whatsapp), now = T0 + min(1))
        assertEquals(away, t.state)
        assertEquals(listOf(ScheduleAt(T0 + min(3), Wake.GRACE)), t.effects)
    }

    @Test
    fun switchingToAnotherAppAfterTheWarningStartsTheGraceToo() {
        val t = reduce(Warning(rec, 10), ForegroundChanged(whatsapp), now = T0 + min(9) + sec(30))
        val expected = Warning(rec.copy(leftAt = T0 + min(9) + sec(30)), 10, graceCheckAt = T0 + min(11) + sec(30))
        assertEquals(expected, t.state)
        assertEquals(listOf(ScheduleAt(T0 + min(11) + sec(30), Wake.GRACE)), t.effects)
    }

    @Test
    fun aSecondAppOrTheHomeScreenWhileAwayDoesNotRestartTheGrace() {
        assertUnchanged(away, reduce(away, ForegroundChanged(chrome), now = T0 + min(2)))
        assertUnchanged(away, reduce(away, LauncherResumed, now = T0 + min(2)))
    }

    @Test
    fun anotherAppAfterGoingHomeDoesNotRestartTheGrace() {
        val home = reduce(running, LauncherResumed, now = T0 + min(1)).state
        assertEquals(away, home)
        assertUnchanged(home, reduce(home, ForegroundChanged(whatsapp), now = T0 + min(2)))
    }

    @Test
    fun moreWindowsOfTheSessionAppWhileInItChangeNothing() {
        assertUnchanged(running, reduce(running, ForegroundChanged(INSTAGRAM.pkg), now = T0 + min(1)))
    }

    @Test
    fun foregroundChangesWithoutACountingSessionChangeNothing() {
        assertUnchanged(Idle, reduce(Idle, ForegroundChanged(whatsapp)))
        val cooldown = Cooldown(INSTAGRAM, T0 + min(5))
        assertUnchanged(cooldown, reduce(cooldown, ForegroundChanged(whatsapp)))
        val expired = Expired(rec, 10, cardShown = true)
        assertUnchanged(expired, reduce(expired, ForegroundChanged(whatsapp), now = T0 + min(11)))
    }

    // ------------------------------------------------------------------------ the grace ends

    @Test
    fun comingBackToTheSessionAppClearsTheGrace() {
        val t = reduce(away, ForegroundChanged(INSTAGRAM.pkg), now = T0 + min(2))
        assertEquals(running, t.state)
        assertEquals(listOf(CancelSchedule(Wake.GRACE)), t.effects)
    }

    @Test
    fun windowEventsCarryNoProfileSoThePackageAloneCounts() {
        val work = Fx.active(INSTAGRAM_WORK, minutes = 10)
        val awayFromWork = Running(work.copy(leftAt = T0 + min(1)), 10, graceCheckAt = T0 + min(3))
        val t = reduce(awayFromWork, ForegroundChanged(INSTAGRAM.pkg), now = T0 + min(2))
        assertEquals(Running(work, 10), t.state)
    }

    @Test
    fun reopeningTheSessionAppFromTheLauncherClearsTheGraceAndOpensIt() {
        val t = reduce(away, LaunchRequested(INSTAGRAM, Allow(AllowReason.RUNNING)), now = T0 + min(2))
        assertEquals(running, t.state)
        assertEquals(listOf(CancelSchedule(Wake.GRACE), LaunchApp(INSTAGRAM)), t.effects)
    }

    @Test
    fun reopeningTheSessionAppFromOutsideClearsTheGraceWithoutOpeningIt() {
        val event = LaunchRequested(INSTAGRAM, Allow(AllowReason.RUNNING), source = LaunchSource.OUTSIDE)
        val t = reduce(away, event, now = T0 + min(2))
        assertEquals(running, t.state)
        assertEquals(listOf(CancelSchedule(Wake.GRACE)), t.effects)
    }

    // ---------------------------------------------------------------------------- invariant 8

    @Test
    fun leavingTheLauncherNeverClearsTheGrace() {
        // Opening another app from the launcher is not "coming back" (the bug found on the phone).
        assertUnchanged(away, reduce(away, LauncherPaused, now = T0 + min(2)))
        assertUnchanged(running, reduce(running, LauncherPaused, now = T0 + min(2)))
        val warnedAway = Warning(rec.copy(leftAt = T0 + min(9)), 10, graceCheckAt = T0 + min(11))
        assertUnchanged(warnedAway, reduce(warnedAway, LauncherPaused, now = T0 + min(10)))
    }

    // ---------------------------------------------------------------------------- invariant 9

    @Test
    fun stillAwayAtTheEndOfTheGraceEndsTheSessionAsLeftEarly() {
        val t = reduce(away, GraceElapsed(trackedInFront = false), now = T0 + min(3))
        assertEquals(Idle, t.state) // "left early" never starts a cooldown
        val end = t.effects.filterIsInstance<EndSession>().single()
        assertEquals(EndReason.LEFT_EARLY, end.reason)
        assertEquals(T0 + min(3), end.at)
        assertEquals(T0 + min(1), end.rec.leftAt)
        assertTrue(StopCountdown in t.effects)
        assertFalse(GoHome in t.effects)
    }

    @Test
    fun backInTheAppAtTheEndOfTheGraceContinuesTheSession() {
        val t = reduce(away, GraceElapsed(trackedInFront = true), now = T0 + min(3))
        assertEquals(running, t.state)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    @Test
    fun notKnowingWhichAppIsInFrontContinuesTheSession() {
        val t = reduce(away, GraceElapsed(trackedInFront = null), now = T0 + min(3))
        assertEquals(running, t.state)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    @Test
    fun theGraceWorksTheSameAfterTheWarning() {
        // Left at 7:30; the warning came at 9:00 while away; the grace ends at 9:30.
        val check = T0 + min(9) + sec(30)
        val warnedAway = Warning(rec.copy(leftAt = T0 + min(7) + sec(30)), 10, graceCheckAt = check)
        assertEquals(Idle, reduce(warnedAway, GraceElapsed(false), now = check).state)
        assertEquals(Warning(rec, 10), reduce(warnedAway, GraceElapsed(true), now = check).state)
        assertEquals(Warning(rec, 10), reduce(warnedAway, GraceElapsed(null), now = check).state)
    }

    @Test
    fun aGraceCallbackThatIsEarlyOrNotExpectedIsIgnored() {
        assertUnchanged(away, reduce(away, GraceElapsed(false), now = T0 + min(2)))
        assertUnchanged(running, reduce(running, GraceElapsed(false), now = T0 + min(5)))
        assertUnchanged(Idle, reduce(Idle, GraceElapsed(false)))
    }

    // ------------------------------------------------------------------- with a prompt in front

    @Test
    fun aForegroundChangeReachesTheSessionBehindAnOpenPrompt() {
        // YouTube came to the front and is being asked "How long?" while Instagram's session runs.
        val prompt = Prompting(YOUTUBE, 10, running, source = LaunchSource.OUTSIDE)
        val t = reduce(prompt, ForegroundChanged(YOUTUBE.pkg), now = T0 + min(1))
        assertEquals(prompt.copy(previous = away), t.state)
        assertEquals(listOf(ScheduleAt(T0 + min(3), Wake.GRACE)), t.effects)
    }

    @Test
    fun confirmingAnotherAppWhileAwayEndsTheFirstSessionFromWhenItWasLeft() {
        val ask = AskDuration(TrackedApp.DEFAULT_PRESETS, 60)
        val left = reduce(running, ForegroundChanged(YOUTUBE.pkg), now = T0 + min(1)).state
        val prompt = reduce(left, LaunchRequested(YOUTUBE, ask, 10, LaunchSource.OUTSIDE), now = T0 + min(1)).state
        val t = reduce(prompt, DurationChosen(5), now = T0 + min(1) + sec(10))
        assertEquals(YOUTUBE, (t.state as Running).rec.key)
        val end = t.effects.filterIsInstance<EndSession>().single()
        assertEquals(INSTAGRAM, end.rec.key)
        assertEquals(EndReason.LEFT_EARLY, end.reason)
        assertEquals(T0 + min(1), end.rec.leftAt)
    }

    // ------------------------------------------------- what the service tells the engine (T094)

    @Test
    fun eitherSourceNamingTheSessionAppMeansBackInTheApp() {
        val pkg = INSTAGRAM.pkg
        assertEquals(true, GraceCheck.trackedInFront(pkg, accessibilityFront = pkg, launcherResumed = false, usageFront = whatsapp))
        assertEquals(true, GraceCheck.trackedInFront(pkg, accessibilityFront = whatsapp, launcherResumed = false, usageFront = pkg))
        assertEquals(true, GraceCheck.trackedInFront(pkg, accessibilityFront = null, launcherResumed = false, usageFront = pkg))
        // A missed "launcher paused" must not end a session the user is back in.
        assertEquals(true, GraceCheck.trackedInFront(pkg, accessibilityFront = null, launcherResumed = true, usageFront = pkg))
    }

    @Test
    fun theLauncherStillInFrontMeansAway() {
        val pkg = INSTAGRAM.pkg
        assertEquals(false, GraceCheck.trackedInFront(pkg, accessibilityFront = null, launcherResumed = true, usageFront = null))
        assertEquals(false, GraceCheck.trackedInFront(pkg, accessibilityFront = "app.calmlauncher", launcherResumed = true, usageFront = "app.calmlauncher"))
    }

    @Test
    fun aSourceNamingAnotherAppMeansAway() {
        val pkg = INSTAGRAM.pkg
        assertEquals(false, GraceCheck.trackedInFront(pkg, accessibilityFront = whatsapp, launcherResumed = false, usageFront = null))
        assertEquals(false, GraceCheck.trackedInFront(pkg, accessibilityFront = null, launcherResumed = false, usageFront = whatsapp))
        assertEquals(false, GraceCheck.trackedInFront(pkg, accessibilityFront = whatsapp, launcherResumed = false, usageFront = chrome))
    }

    @Test
    fun noSourceAtAllMeansUnknown() {
        assertEquals(null, GraceCheck.trackedInFront(INSTAGRAM.pkg, accessibilityFront = null, launcherResumed = false, usageFront = null))
    }
}
