package app.calmlauncher.enforce

import app.calmlauncher.enforce.ShowGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** T052: every card and warning goes through the call/lock guard and the exemption check. */
class ShowGateTest {

    private val now = 1_700_000_000_000L
    private val ig = "com.instagram.android"

    /** A fake CallGuard: no Android needed. */
    private class FakeGuard(var blocked: Boolean = false) : GuardState {
        override fun isBlocked() = blocked
    }

    private fun check(
        guard: GuardState = FakeGuard(),
        exempt: Set<String> = emptySet(),
        loop: LoopGuard = LoopGuard(),
        isEnforcement: Boolean = true,
    ) = ShowGate.check(ig, guard, { it in exempt }, loop, now, isEnforcement)

    @Test
    fun showsWhenNothingStandsInTheWay() {
        assertEquals(Verdict.SHOW, check())
    }

    @Test
    fun callOrLockScreenDefers() {
        assertEquals(Verdict.DEFER, check(guard = FakeGuard(blocked = true)))
    }

    @Test
    fun exemptAppIsNeverCoveredEvenDuringACall() {
        assertEquals(Verdict.SKIP, check(exempt = setOf(ig)))
        assertEquals(Verdict.SKIP, check(guard = FakeGuard(blocked = true), exempt = setOf(ig)))
    }

    @Test
    fun exemptionIsReadAtShowTime() {
        val exempt = mutableSetOf<String>()
        val gate = { ShowGate.check(ig, FakeGuard(), { it in exempt }, LoopGuard(), now, true) }
        assertEquals(Verdict.SHOW, gate())
        exempt += ig // became exempt during the session
        assertEquals(Verdict.SKIP, gate())
    }

    @Test
    fun guardIsAskedEveryTime() {
        val guard = FakeGuard()
        assertEquals(Verdict.SHOW, check(guard = guard))
        guard.blocked = true // a call started
        assertEquals(Verdict.DEFER, check(guard = guard))
    }

    @Test
    fun pausedByTheLoopGuardIsSkipped() {
        val loop = LoopGuard()
        repeat(4) { loop.record(ig, now) }
        assertEquals(Verdict.SKIP, check(loop = loop))
        assertEquals(Verdict.SKIP, check(guard = FakeGuard(blocked = true), loop = loop))
    }

    @Test
    fun fourthCardWithinThirtySecondsIsSkipped() {
        val loop = LoopGuard()
        repeat(3) { assertEquals(Verdict.SHOW, check(loop = loop)) }
        assertEquals(Verdict.SKIP, check(loop = loop))
    }

    @Test
    fun warningsDoNotCountAsEnforcement() {
        val loop = LoopGuard()
        repeat(10) { assertEquals(Verdict.SHOW, check(loop = loop, isEnforcement = false)) }
        assertEquals(Verdict.SHOW, check(loop = loop))
    }
}
