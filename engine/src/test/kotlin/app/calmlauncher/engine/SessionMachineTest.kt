package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.Allow
import app.calmlauncher.engine.Decision.AskDuration
import app.calmlauncher.engine.Decision.Blocked
import app.calmlauncher.engine.Effect.CancelSchedule
import app.calmlauncher.engine.Effect.DeferUntilClear
import app.calmlauncher.engine.Effect.EndSession
import app.calmlauncher.engine.Effect.GoHome
import app.calmlauncher.engine.Effect.HideCard
import app.calmlauncher.engine.Effect.LaunchApp
import app.calmlauncher.engine.Effect.Log
import app.calmlauncher.engine.Effect.PersistSession
import app.calmlauncher.engine.Effect.ProbeForegroundThenShowCard
import app.calmlauncher.engine.Effect.ScheduleAt
import app.calmlauncher.engine.Effect.ShowBlocked
import app.calmlauncher.engine.Effect.ShowCard
import app.calmlauncher.engine.Effect.ShowPrompt
import app.calmlauncher.engine.Effect.ShowWarning
import app.calmlauncher.engine.Effect.StartCountdown
import app.calmlauncher.engine.Effect.StopCountdown
import app.calmlauncher.engine.Fx.CFG
import app.calmlauncher.engine.Fx.INSTAGRAM
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.YOUTUBE
import app.calmlauncher.engine.Fx.min
import app.calmlauncher.engine.Fx.sec
import app.calmlauncher.engine.SessionEvent.AppUninstalled
import app.calmlauncher.engine.SessionEvent.CardTimedOut
import app.calmlauncher.engine.SessionEvent.CardUnavailable
import app.calmlauncher.engine.SessionEvent.Cancelled
import app.calmlauncher.engine.SessionEvent.DurationChosen
import app.calmlauncher.engine.SessionEvent.EndedByUser
import app.calmlauncher.engine.SessionEvent.ExtendStarted
import app.calmlauncher.engine.SessionEvent.Extended
import app.calmlauncher.engine.SessionEvent.ForegroundIsOther
import app.calmlauncher.engine.SessionEvent.ForegroundIsTracked
import app.calmlauncher.engine.SessionEvent.ForegroundUnknown
import app.calmlauncher.engine.SessionEvent.GraceElapsed
import app.calmlauncher.engine.SessionEvent.GuardCleared
import app.calmlauncher.engine.SessionEvent.InCallOrLocked
import app.calmlauncher.engine.SessionEvent.LaunchRequested
import app.calmlauncher.engine.SessionEvent.LauncherPaused
import app.calmlauncher.engine.SessionEvent.LauncherResumed
import app.calmlauncher.engine.SessionEvent.OverrideCompleted
import app.calmlauncher.engine.SessionEvent.OverrideReset
import app.calmlauncher.engine.SessionEvent.OverrideStarted
import app.calmlauncher.engine.SessionEvent.Reconcile
import app.calmlauncher.engine.SessionEvent.Tick
import app.calmlauncher.engine.SessionEvent.WentHome
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

/** data-model.md "State transitions", one group per user story. */
class SessionMachineTest {

    private fun reduce(state: SessionState, event: SessionEvent, now: Long = T0, cfg: Config = CFG) =
        SessionMachine.reduce(state, event, now, cfg)

    private val ask = AskDuration(TrackedApp.DEFAULT_PRESETS, 60)

    /** A session for [key] started at T0 and lasting [minutes]. */
    private fun rec(key: AppKey = INSTAGRAM, minutes: Int = 5, start: Long = T0) = Fx.active(key, start, minutes)

    private fun running(minutes: Int = 5, cooldownMin: Int = 10) = Running(rec(minutes = minutes), cooldownMin)

    private fun assertUnchanged(state: SessionState, t: Transition) {
        assertEquals(state, t.state)
        assertEquals(emptyList<Effect>(), t.effects)
    }

    /** UI effects that must never come out of a reconcile (invariant 6). */
    private fun isUi(e: Effect) = e is ShowCard || e is ShowWarning || e is ProbeForegroundThenShowCard ||
        e is GoHome || e is ShowPrompt || e is ShowBlocked || e is LaunchApp

    // ---------------------------------------------------------------- US1: prompt (T029)

    @Test
    fun launchOfTrackedAppOpensThePrompt() {
        val t = reduce(Idle, LaunchRequested(INSTAGRAM, ask, cooldownMin = 10))
        assertEquals(Prompting(INSTAGRAM, 10, previous = Idle), t.state)
        assertEquals(listOf(ShowPrompt(INSTAGRAM, TrackedApp.DEFAULT_PRESETS, 60)), t.effects)
    }

    @Test
    fun allowedLaunchJustLaunches() {
        val t = reduce(Idle, LaunchRequested(Fx.NOTES, Allow(AllowReason.NOT_TRACKED)))
        assertEquals(Idle, t.state)
        assertEquals(listOf(LaunchApp(Fx.NOTES)), t.effects)
    }

    @Test
    fun cancelGoesBackToIdleAndIsLogged() {
        val t = reduce(Prompting(INSTAGRAM, 10, Idle), Cancelled)
        assertEquals(Idle, t.state)
        assertEquals(listOf(Log(EventType.PROMPT_CANCELLED, INSTAGRAM)), t.effects)
    }

    @Test
    fun durationChosenStartsTheSession() {
        val t = reduce(Prompting(INSTAGRAM, 10, Idle), DurationChosen(5), now = T0)
        val expected = SessionRecord(key = INSTAGRAM, startedAt = T0, declaredMs = min(5), deadlineAt = T0 + min(5))
        assertEquals(Running(expected, 10), t.state)
        assertEquals(
            listOf(
                PersistSession(expected),
                LaunchApp(INSTAGRAM),
                StartCountdown(expected),
                ScheduleAt(T0 + min(4), Wake.WARNING),
                ScheduleAt(T0 + min(5), Wake.DEADLINE),
            ),
            t.effects,
        )
    }

    @Test
    fun boundaryDurationsAreAccepted() {
        assertTrue(reduce(Prompting(INSTAGRAM, 10, Idle), DurationChosen(1)).state is Running)
        assertTrue(reduce(Prompting(INSTAGRAM, 10, Idle), DurationChosen(60)).state is Running)
    }

    @Test
    fun outOfRangeMinutesAreRejected() {
        val prompting = Prompting(INSTAGRAM, 10, Idle)
        for (m in listOf(0, -1, 61, 1000, Int.MIN_VALUE)) assertUnchanged(prompting, reduce(prompting, DurationChosen(m)))
        assertUnchanged(prompting, reduce(prompting, DurationChosen(45), cfg = Config(customMaxMin = 30)))
    }

    @Test
    fun durationOutsideAPromptIsIgnored() {
        assertUnchanged(Idle, reduce(Idle, DurationChosen(5)))
        val r = running()
        assertUnchanged(r, reduce(r, DurationChosen(5)))
    }

    @Test
    fun secondAppEndsTheFirstAsLeftEarly() {
        val youtube = Running(rec(YOUTUBE, minutes = 10), 10)
        val prompting = reduce(youtube, LaunchRequested(INSTAGRAM, ask, 10), now = T0 + min(3)).state
        assertEquals(Prompting(INSTAGRAM, 10, previous = youtube), prompting)

        val t = reduce(prompting, DurationChosen(5), now = T0 + min(4))
        val ended = t.effects.first() as EndSession
        assertEquals(YOUTUBE, ended.rec.key)
        assertEquals(EndReason.LEFT_EARLY, ended.reason)
        assertEquals(T0 + min(4), ended.at)
        assertEquals(INSTAGRAM, (t.state as Running).rec.key)
        assertEquals(1, t.effects.count { it is EndSession })
    }

    @Test
    fun cancelledPromptLeavesTheRunningSessionAlone() {
        val youtube = Running(rec(YOUTUBE, minutes = 10), 10)
        val t = reduce(Prompting(INSTAGRAM, 10, youtube), Cancelled)
        assertEquals(youtube, t.state)
        assertFalse(t.effects.any { it is EndSession })
    }

    @Test
    fun aNewLaunchReplacesAnOpenPromptButKeepsWhatWasRunning() {
        val youtube = Running(rec(YOUTUBE, minutes = 10), 10)
        val t = reduce(Prompting(INSTAGRAM, 10, youtube), LaunchRequested(Fx.NOTES, ask, 0))
        assertEquals(Prompting(Fx.NOTES, 0, youtube), t.state)
    }

    @Test
    fun atMostOneActiveSessionAcrossManySwitches() {
        var state: SessionState = Idle
        var active = 0
        var now = T0
        repeat(20) { i ->
            val key = if (i % 2 == 0) INSTAGRAM else YOUTUBE
            state = reduce(state, LaunchRequested(key, ask, 10), now).state
            val t = reduce(state, DurationChosen(5), now)
            active += t.effects.count { it is PersistSession } - t.effects.count { it is EndSession }
            state = t.state
            assertEquals(1, active)
            now += min(1)
        }
    }

    // ---------------------------------------------------------------- US2: countdown and warning (T035)

    @Test
    fun tickBeforeTheWarningDoesNothing() {
        val r = running(5)
        assertUnchanged(r, reduce(r, Tick, now = T0 + min(3)))
    }

    @Test
    fun tickAtTheWarningShowsItOnce() {
        val r = running(5)
        val t = reduce(r, Tick, now = T0 + min(4))
        assertEquals(Warning(r.rec, 10), t.state)
        assertEquals(listOf(ShowWarning(r.rec)), t.effects)
        assertUnchanged(t.state, reduce(t.state, Tick, now = T0 + min(4) + sec(30)))
    }

    @Test
    fun exactlyOneWarningPerSession() {
        var state: SessionState = running(5)
        var warnings = 0
        var now = T0
        while (now < T0 + min(5)) {
            val t = reduce(state, Tick, now)
            warnings += t.effects.count { it is ShowWarning }
            state = t.state
            now += sec(5)
        }
        assertEquals(1, warnings)
    }

    @Test
    fun noWarningWhenTheSessionIsNotLongerThanTheLead() {
        val r = running(1)
        assertUnchanged(r, reduce(r, Tick, now = T0 + sec(1)))
        val t = reduce(Prompting(INSTAGRAM, 10, Idle), DurationChosen(1), now = T0)
        assertFalse(t.effects.any { it is ScheduleAt && it.why == Wake.WARNING })
    }

    @Test
    fun warningLeadComesFromConfig() {
        val r = running(5)
        val cfg = Config(warningLeadSec = 120)
        assertTrue(reduce(r, Tick, now = T0 + min(3), cfg = cfg).state is Warning)
        assertUnchanged(r, reduce(r, Tick, now = T0 + min(3) - 1, cfg = cfg))
    }

    @Test
    fun endNowEndsTheSessionAndGoesHome() {
        for (state in listOf(running(5), Warning(rec(), 10))) {
            val t = reduce(state, EndedByUser, now = T0 + min(2))
            assertEquals(Idle, t.state)
            val end = t.effects.filterIsInstance<EndSession>().single()
            assertEquals(EndReason.ENDED_BY_USER, end.reason)
            assertEquals(T0 + min(2), end.at)
            assertTrue(StopCountdown in t.effects)
            assertTrue(GoHome in t.effects)
        }
    }

    // ---------------------------------------------------------------- US3: expiry, extension, override (T040)

    @Test
    fun deadlineExpiresTheSessionAndProbesTheForeground() {
        for (state in listOf(running(5), Warning(rec(), 10))) {
            val t = reduce(state, Tick, now = T0 + min(5))
            assertEquals(Expired(rec(), 10), t.state)
            assertEquals(listOf(ProbeForegroundThenShowCard(rec())), t.effects)
        }
    }

    @Test
    fun otherOrUnknownForegroundEndsQuietlyIntoCooldown() {
        for (event in listOf(ForegroundIsOther, ForegroundUnknown, CardUnavailable)) {
            val t = reduce(Expired(rec(), 10), event, now = T0 + min(5) + sec(3))
            assertEquals(Cooldown(INSTAGRAM, T0 + min(5) + min(10)), t.state)
            val end = t.effects.filterIsInstance<EndSession>().single()
            assertEquals(EndReason.EXPIRED, end.reason)
            assertEquals(T0 + min(5), end.at)
            assertTrue(StopCountdown in t.effects)
            assertFalse(t.effects.any(::isUi))
        }
    }

    @Test
    fun quietEndWithoutCooldownGoesIdle() {
        assertEquals(Idle, reduce(Expired(rec(), 0), ForegroundIsOther, now = T0 + min(5)).state)
    }

    @Test
    fun trackedAppInFrontShowsTheCard() {
        val t = reduce(Expired(rec(), 10), ForegroundIsTracked, now = T0 + min(5))
        assertEquals(Expired(rec(), 10, cardShown = true), t.state)
        assertEquals(listOf(ShowCard(rec(), canExtend = true)), t.effects)
    }

    @Test
    fun cardHidesExtendAfterTwoExtensions() {
        val used = rec().copy(extensions = 2)
        val t = reduce(Expired(used, 10), ForegroundIsTracked, now = T0 + min(5))
        assertEquals(listOf(ShowCard(used, canExtend = false)), t.effects)
    }

    @Test
    fun goHomeEndsAsExpiredWithCooldown() {
        val now = T0 + min(5) + sec(20)
        val t = reduce(Expired(rec(), 10, cardShown = true), WentHome, now = now)
        assertEquals(Cooldown(INSTAGRAM, now + min(10)), t.state)
        val end = t.effects.filterIsInstance<EndSession>().single()
        assertEquals(EndReason.EXPIRED, end.reason)
        assertEquals(now, end.at)
        assertTrue(GoHome in t.effects && HideCard in t.effects && StopCountdown in t.effects)
    }

    @Test
    fun goHomeComesBeforeHidingTheCard() {
        // research R5: the HOME start relies on our overlay still being visible.
        val effects = reduce(Expired(rec(), 10, cardShown = true), WentHome, now = T0 + min(6)).effects
        assertTrue(effects.indexOf(GoHome) < effects.indexOf(HideCard))
    }

    @Test
    fun cardThatCannotBeDrawnEndsQuietlyEvenAfterItWasApproved() {
        val t = reduce(Expired(rec(), 10, cardShown = true), CardUnavailable, now = T0 + min(5) + sec(2))
        assertEquals(Cooldown(INSTAGRAM, T0 + min(15)), t.state)
        assertEquals(T0 + min(5), t.effects.filterIsInstance<EndSession>().single().at)
        assertTrue(HideCard in t.effects)
        assertFalse(GoHome in t.effects)
        // but a late probe result never ends a session whose card is up
        val shown = Expired(rec(), 10, cardShown = true)
        assertUnchanged(shown, reduce(shown, ForegroundIsOther, now = T0 + min(6)))
    }

    @Test
    fun cardTimeoutEndsWithoutGoingHome() {
        val now = T0 + min(7)
        val t = reduce(Expired(rec(), 0, cardShown = true), CardTimedOut, now = now)
        assertEquals(Idle, t.state)
        assertTrue(t.effects.contains(Log(EventType.OVERLAY_TIMEOUT, INSTAGRAM)))
        assertFalse(GoHome in t.effects)
        assertEquals(EndReason.EXPIRED, t.effects.filterIsInstance<EndSession>().single().reason)
    }

    private fun shownCard(r: SessionRecord = rec()) = Expired(r, 10, cardShown = true)

    @Test
    fun extensionNeedsTheWait() {
        val started = reduce(shownCard(), ExtendStarted, now = T0 + min(5)).state
        assertEquals(T0 + min(5), (started as Expired).extendStartedAt)
        assertUnchanged(started, reduce(started, Extended(2), now = T0 + min(5) + sec(14)))

        val now = T0 + min(5) + sec(15)
        val t = reduce(started, Extended(2), now = now)
        val extended = rec().copy(deadlineAt = now + min(2), extensions = 1)
        assertEquals(Warning(extended, 10), t.state)
        assertEquals(
            listOf(
                PersistSession(extended),
                Log(EventType.EXTENDED, INSTAGRAM, "+2"),
                HideCard,
                StartCountdown(extended),
                ScheduleAt(now + min(2), Wake.DEADLINE),
            ),
            t.effects,
        )
    }

    @Test
    fun extensionWithoutStartingTheWaitIsRejected() {
        assertUnchanged(shownCard(), reduce(shownCard(), Extended(2), now = T0 + min(9)))
    }

    @Test
    fun onlyOfferedExtensionSizesAreAccepted() {
        val started = reduce(shownCard(), ExtendStarted, now = T0 + min(5)).state
        assertUnchanged(started, reduce(started, Extended(3), now = T0 + min(6)))
        assertTrue(reduce(started, Extended(5), now = T0 + min(6)).state is Warning)
    }

    @Test
    fun neverMoreThanTwoExtensions() {
        val used = shownCard(rec().copy(extensions = 2))
        val started = reduce(used, ExtendStarted, now = T0 + min(5)).state
        assertUnchanged(started, reduce(started, Extended(2), now = T0 + min(6)))
        // A config asking for more is clamped to 2.
        assertUnchanged(started, reduce(started, Extended(2), now = T0 + min(6), cfg = Config(maxExtensions = 9)))
        // A config asking for fewer is honoured.
        val once = shownCard(rec().copy(extensions = 1))
        val s1 = reduce(once, ExtendStarted, now = T0 + min(5), cfg = Config(maxExtensions = 1)).state
        assertUnchanged(s1, reduce(s1, Extended(2), now = T0 + min(6), cfg = Config(maxExtensions = 1)))
    }

    @Test
    fun extendStartedIsIgnoredWhenNoExtensionIsLeft() {
        val used = shownCard(rec().copy(extensions = 2))
        assertUnchanged(used, reduce(used, ExtendStarted, now = T0 + min(5)))
    }

    @Test
    fun overrideFromTheCardGivesFiveMinutes() {
        val started = reduce(shownCard(), OverrideStarted, now = T0 + min(5)).state
        assertUnchanged(started, reduce(started, OverrideCompleted, now = T0 + min(5) + sec(29)))

        val now = T0 + min(5) + sec(30)
        val t = reduce(started, OverrideCompleted, now = now)
        val granted = rec().copy(deadlineAt = now + min(5), overrides = 1)
        assertEquals(Warning(granted, 10), t.state)
        assertTrue(t.effects.contains(Log(EventType.OVERRIDE, INSTAGRAM)))
        assertTrue(t.effects.contains(PersistSession(granted)))
        assertTrue(HideCard in t.effects)
        assertTrue(t.effects.contains(StartCountdown(granted)))
    }

    @Test
    fun overrideIsAlwaysAvailableEvenAfterTwoExtensions() {
        var state: SessionState = shownCard(rec().copy(extensions = 2))
        repeat(3) { i ->
            val start = T0 + min(10 * (i + 1))
            state = reduce(state, OverrideStarted, now = start).state
            val t = reduce(state, OverrideCompleted, now = start + sec(30))
            assertEquals(1, t.effects.count { it == Log(EventType.OVERRIDE, INSTAGRAM) })
            val rec = (t.state as Warning).rec
            assertEquals(i + 1, rec.overrides)
            state = shownCard(rec)
        }
    }

    @Test
    fun leavingTheCardResetsTheOverrideWait() {
        val started = reduce(shownCard(), OverrideStarted, now = T0 + min(5)).state
        val reset = reduce(started, OverrideReset, now = T0 + min(5) + sec(20)).state
        assertEquals(null, (reset as Expired).overrideStartedAt)
        assertUnchanged(reset, reduce(reset, OverrideCompleted, now = T0 + min(9)))
    }

    @Test
    fun overrideWaitNeverDropsBelowTenSeconds() {
        val cfg = Config(overrideWaitSec = 3)
        val started = reduce(shownCard(), OverrideStarted, now = T0, cfg = cfg).state
        assertUnchanged(started, reduce(started, OverrideCompleted, now = T0 + sec(9), cfg = cfg))
        assertTrue(reduce(started, OverrideCompleted, now = T0 + sec(10), cfg = cfg).state is Warning)
    }

    // ---------------------------------------------------------------- US4: calls and lock screen (T049)

    @Test
    fun callOrLockDefersTheCard() {
        val t = reduce(Expired(rec(), 10), InCallOrLocked, now = T0 + min(5))
        assertEquals(Expired(rec(), 10, deferred = true), t.state)
        assertEquals(listOf(DeferUntilClear(rec())), t.effects)
    }

    @Test
    fun callArrivingWhileTheCardIsShownHidesIt() {
        val t = reduce(shownCard(), InCallOrLocked, now = T0 + min(6))
        assertEquals(Expired(rec(), 10, deferred = true), t.state)
        assertEquals(listOf(HideCard, DeferUntilClear(rec())), t.effects)
    }

    @Test
    fun nothingHappensWhileDeferred() {
        val deferred = Expired(rec(), 10, deferred = true)
        assertUnchanged(deferred, reduce(deferred, Tick, now = T0 + min(8)))
        assertUnchanged(deferred, reduce(deferred, ForegroundIsTracked, now = T0 + min(8)))
    }

    @Test
    fun afterTheCallTheCardShowsOnlyIfTheAppIsStillInFront() {
        val deferred = Expired(rec(), 10, deferred = true)
        val cleared = reduce(deferred, GuardCleared, now = T0 + min(8))
        assertEquals(Expired(rec(), 10), cleared.state)
        assertEquals(listOf(ProbeForegroundThenShowCard(rec())), cleared.effects)

        assertTrue(reduce(cleared.state, ForegroundIsTracked, now = T0 + min(8)).effects.single() is ShowCard)
        val other = reduce(cleared.state, ForegroundIsOther, now = T0 + min(8))
        assertFalse(other.effects.any(::isUi))
        assertEquals(EndReason.EXPIRED, other.effects.filterIsInstance<EndSession>().single().reason)
    }

    @Test
    fun guardClearedWithoutDeferralDoesNothing() {
        val r = running()
        assertUnchanged(r, reduce(r, GuardCleared, now = T0 + min(1)))
    }

    // ---------------------------------------------------------------- US5: reboot and process death (T055)

    @Test
    fun reconcileWithAFutureDeadlineContinuesAndReschedules() {
        val r = running(10)
        val t = reduce(r, Reconcile(bootedAt = T0 - min(60)), now = T0 + min(3))
        assertEquals(r, t.state)
        assertEquals(
            listOf(StartCountdown(r.rec), ScheduleAt(T0 + min(9), Wake.WARNING), ScheduleAt(T0 + min(10), Wake.DEADLINE)),
            t.effects,
        )
    }

    @Test
    fun reconcileWithAPastDeadlineClosesSilentlyAtTheDeadline() {
        for (state in listOf<SessionState>(running(2), Warning(rec(minutes = 2), 10))) {
            val t = reduce(state, Reconcile(bootedAt = T0 + min(4)), now = T0 + min(5))
            val end = t.effects.filterIsInstance<EndSession>().single()
            assertEquals(EndReason.EXPIRED, end.reason)
            assertEquals(T0 + min(2), end.at)
            assertFalse(t.effects.any(::isUi))
            assertTrue(StopCountdown in t.effects)
            // the cooldown starts from the deadline
            assertEquals(Cooldown(INSTAGRAM, T0 + min(2) + min(10)), t.state)
        }
    }

    @Test
    fun reconcileAfterExpiryWithoutCooldownGoesIdle() {
        assertEquals(Idle, reduce(running(2, cooldownMin = 0), Reconcile(), now = T0 + min(5)).state)
    }

    @Test
    fun timerThatDidNotRunIsReported() {
        // Device was up the whole time and the deadline passed more than 60 s ago.
        val t = reduce(running(2), Reconcile(bootedAt = T0 - min(60)), now = T0 + min(2) + sec(61))
        assertTrue(t.effects.contains(Log(EventType.SERVICE_INTERRUPTED, INSTAGRAM)))
        // A deadline that passed while the phone was off is not an interruption.
        val reboot = reduce(running(2), Reconcile(bootedAt = T0 + min(4)), now = T0 + min(5))
        assertFalse(reboot.effects.any { it is Log && it.type == EventType.SERVICE_INTERRUPTED })
        // Within 60 s is normal timer lag.
        val lag = reduce(running(2), Reconcile(bootedAt = T0 - min(60)), now = T0 + min(2) + sec(30))
        assertFalse(lag.effects.any { it is Log && it.type == EventType.SERVICE_INTERRUPTED })
    }

    @Test
    fun reconcileWhenNothingRunsDoesNothing() {
        assertUnchanged(Idle, reduce(Idle, Reconcile(), now = T0))
        val cd = Cooldown(INSTAGRAM, T0 + min(5))
        assertUnchanged(cd, reduce(cd, Reconcile(), now = T0))
        assertEquals(Idle, reduce(cd, Reconcile(), now = T0 + min(5)).state)
    }

    @Test
    fun reconcileReachesTheSessionBehindAPrompt() {
        val prompting = Prompting(YOUTUBE, 10, running(2))
        val t = reduce(prompting, Reconcile(), now = T0 + min(3))
        assertEquals(Prompting(YOUTUBE, 10, Cooldown(INSTAGRAM, T0 + min(12))), t.state)
        assertEquals(EndReason.EXPIRED, t.effects.filterIsInstance<EndSession>().single().reason)
    }

    @Test
    fun uninstallingTheAppEndsItsSessionAsInterrupted() {
        val states = listOf<SessionState>(running(), Warning(rec(), 10), Expired(rec(), 10, cardShown = true))
        for (state in states) {
            val t = reduce(state, AppUninstalled(INSTAGRAM), now = T0 + min(1))
            assertEquals(Idle, t.state)
            val end = t.effects.filterIsInstance<EndSession>().single()
            assertEquals(EndReason.INTERRUPTED, end.reason)
            assertEquals(T0 + min(1), end.at)
            assertTrue(StopCountdown in t.effects)
        }
        assertTrue(HideCard in reduce(Expired(rec(), 10, cardShown = true), AppUninstalled(INSTAGRAM)).effects)
    }

    @Test
    fun uninstallingAnotherAppChangesNothing() {
        val r = running()
        assertUnchanged(r, reduce(r, AppUninstalled(YOUTUBE)))
        assertEquals(Idle, reduce(Cooldown(INSTAGRAM, T0 + min(5)), AppUninstalled(INSTAGRAM)).state)
        // A prompt for the uninstalled app closes; the session behind it continues.
        val youtube = Running(rec(YOUTUBE), 10)
        assertEquals(youtube, reduce(Prompting(INSTAGRAM, 10, youtube), AppUninstalled(INSTAGRAM)).state)
    }

    // ---------------------------------------------------------------- US6: leaving early (T061)

    @Test
    fun goingHomeStartsTheGracePeriod() {
        val t = reduce(running(10), LauncherResumed, now = T0 + min(1))
        val expected = Running(rec(minutes = 10).copy(leftAt = T0 + min(1)), 10, graceCheckAt = T0 + min(3))
        assertEquals(expected, t.state)
        assertEquals(listOf(ScheduleAt(T0 + min(3), Wake.GRACE)), t.effects)
    }

    @Test
    fun stayingHomeThroughTheGraceEndsAsLeftEarly() {
        val home = reduce(running(10), LauncherResumed, now = T0 + min(1)).state
        val t = reduce(home, GraceElapsed(stillOnLauncher = true), now = T0 + min(3))
        assertEquals(Idle, t.state)
        val end = t.effects.filterIsInstance<EndSession>().single()
        assertEquals(EndReason.LEFT_EARLY, end.reason)
        assertEquals(T0 + min(3), end.at)
        assertEquals(T0 + min(1), end.rec.leftAt)
        assertTrue(StopCountdown in t.effects)
        assertFalse(GoHome in t.effects)
    }

    @Test
    fun leavingTheLauncherCancelsTheGraceCheck() {
        val home = reduce(running(10), LauncherResumed, now = T0 + min(1)).state
        val t = reduce(home, LauncherPaused, now = T0 + min(2))
        assertEquals(running(10), t.state)
        assertEquals(listOf(CancelSchedule(Wake.GRACE)), t.effects)
        assertUnchanged(t.state, reduce(t.state, GraceElapsed(true), now = T0 + min(3)))
    }

    @Test
    fun reopeningTheSameAppDuringGraceContinuesTheSession() {
        val home = reduce(running(10), LauncherResumed, now = T0 + min(1)).state
        val t = reduce(home, LaunchRequested(INSTAGRAM, Allow(AllowReason.RUNNING)), now = T0 + min(2))
        assertEquals(running(10), t.state)
        assertEquals(listOf(CancelSchedule(Wake.GRACE), LaunchApp(INSTAGRAM)), t.effects)
    }

    @Test
    fun earlyOrUnexpectedGraceCallbacksAreIgnored() {
        val home = reduce(running(10), LauncherResumed, now = T0 + min(1)).state
        assertUnchanged(home, reduce(home, GraceElapsed(true), now = T0 + min(2)))
        val r = running(10)
        assertUnchanged(r, reduce(r, GraceElapsed(true), now = T0 + min(5)))
    }

    @Test
    fun graceCallbackWhenNotOnTheLauncherClearsTheCheck() {
        val home = reduce(running(10), LauncherResumed, now = T0 + min(1)).state
        assertEquals(running(10), reduce(home, GraceElapsed(stillOnLauncher = false), now = T0 + min(3)).state)
    }

    @Test
    fun graceWorksAfterTheWarningToo() {
        val warned = Warning(rec(minutes = 10), 10)
        val home = reduce(warned, LauncherResumed, now = T0 + min(9)).state
        assertEquals(T0 + min(11), (home as Warning).graceCheckAt)
    }

    @Test
    fun homePressWhileTheCardShowsEndsTheSession() {
        val now = T0 + min(6)
        val t = reduce(shownCard(), LauncherResumed, now = now)
        assertEquals(Cooldown(INSTAGRAM, now + min(10)), t.state)
        assertEquals(EndReason.EXPIRED, t.effects.filterIsInstance<EndSession>().single().reason)
        assertTrue(HideCard in t.effects)
        assertFalse(GoHome in t.effects)
    }

    @Test
    fun launcherResumingUnderAnOpenPromptCountsAsCancel() {
        val youtube = Running(rec(YOUTUBE, minutes = 10), 10)
        val t = reduce(Prompting(INSTAGRAM, 10, youtube), LauncherResumed, now = T0 + min(1))
        assertEquals(T0 + min(3), (t.state as Running).graceCheckAt)
        assertTrue(t.effects.contains(Log(EventType.PROMPT_CANCELLED, INSTAGRAM)))
    }
}
