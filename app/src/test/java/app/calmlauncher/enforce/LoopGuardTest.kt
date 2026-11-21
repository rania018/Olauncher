package app.calmlauncher.enforce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T053: more than 3 enforcement actions on one app in 30 s pause it for 10 min. */
class LoopGuardTest {

    private val t0 = 1_700_000_000_000L
    private val ig = "com.instagram.android"

    @Test
    fun threeActionsAreFine() {
        val g = LoopGuard()
        repeat(3) { assertFalse(g.record(ig, t0 + it * 1_000L)) }
        assertFalse(g.isPaused(ig, t0 + 3_000L))
    }

    @Test
    fun theFourthWithinThirtySecondsTripsAndPausesForTenMinutes() {
        val g = LoopGuard()
        repeat(3) { g.record(ig, t0 + it * 1_000L) }
        assertTrue(g.record(ig, t0 + 20_000L))
        assertTrue(g.isPaused(ig, t0 + 20_000L + 9 * 60_000L))
        assertFalse(g.isPaused(ig, t0 + 20_000L + 10 * 60_000L))
        assertEquals(t0 + 20_000L + 10 * 60_000L, g.pausedUntil(t0 + 30_000L))
    }

    @Test
    fun spreadOutActionsNeverTrip() {
        val g = LoopGuard()
        repeat(20) { assertFalse(g.record(ig, t0 + it * 11_000L)) }
    }

    @Test
    fun appsAreCountedSeparately() {
        val g = LoopGuard()
        repeat(3) { g.record(ig, t0) }
        assertFalse(g.record("com.google.android.youtube", t0))
        assertFalse(g.isPaused("com.google.android.youtube", t0))
        assertEquals(null, g.pausedUntil(t0))
    }
}
