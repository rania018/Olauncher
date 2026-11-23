package app.calmlauncher.session

import app.calmlauncher.engine.AppKey
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.session.TimeLeft.Display
import org.junit.Assert.assertEquals
import org.junit.Test

/** T071: the on-screen time-left reminder (FR-029). */
class TimeLeftTest {

    private val t0 = 1_700_000_000_000L
    private fun sec(s: Int) = s * 1_000L
    private fun min(m: Int) = m * 60_000L
    private fun rec(minutes: Int = 5) = SessionRecord(
        key = AppKey("com.instagram.android", 0), startedAt = t0, declaredMs = min(minutes), deadlineAt = t0 + min(minutes),
    )

    @Test
    fun labelShowsMinutesThenSeconds() {
        assertEquals("5 min", TimeLeft.label(min(5)))
        assertEquals("5 min", TimeLeft.label(min(4) + sec(1)))
        assertEquals("4 min", TimeLeft.label(min(4)))
        assertEquals("2 min", TimeLeft.label(min(1) + 1))
        assertEquals("1:00", TimeLeft.label(min(1)))
        assertEquals("0:42", TimeLeft.label(sec(41) + 1))
        assertEquals("0:01", TimeLeft.label(1))
        assertEquals("0:00", TimeLeft.label(0))
        assertEquals("0:00", TimeLeft.label(-5_000))
    }

    @Test
    fun barShrinksFromFullToEmpty() {
        val r = rec(10)
        assertEquals(1f, TimeLeft.fraction(r, t0), 0.0001f)
        assertEquals(0.5f, TimeLeft.fraction(r, t0 + min(5)), 0.0001f)
        assertEquals(0f, TimeLeft.fraction(r, t0 + min(10)), 0.0001f)
        assertEquals(0f, TimeLeft.fraction(r, t0 + min(20)), 0.0001f)
        assertEquals(1f, TimeLeft.fraction(r, t0 - min(1)), 0.0001f)
    }

    @Test
    fun extensionKeepsTheBarProportional() {
        val extended = rec(5).copy(deadlineAt = t0 + min(7))
        assertEquals(2f / 7f, TimeLeft.fraction(extended, t0 + min(5)), 0.0001f)
    }

    @Test
    fun pillTextRefreshesOncePerMinuteThenEverySecond() {
        // A 60-minute session: its ring moves every 36 s, so the text sets the pace here.
        val r = rec(60)
        // 4:10 left: next text change at 4:00 left
        assertEquals(sec(10), TimeLeft.nextRefreshMs(Display.PILL, r, r.deadlineAt - min(4) - sec(10)))
        // last minute: every second
        assertEquals(sec(1), TimeLeft.nextRefreshMs(Display.PILL, r, r.deadlineAt - sec(42)))
        assertEquals(500L, TimeLeft.nextRefreshMs(Display.PILL, r, r.deadlineAt - sec(41) - 500))
    }

    @Test
    fun pillRingMovesInStepsOfOnePercent() {
        val r = rec(5)
        // 4:30 left: the text changes in 30 s, but the ring moves every 3 s (5 min / 100)
        assertEquals(sec(3), TimeLeft.nextRefreshMs(Display.PILL, r, r.deadlineAt - min(4) - sec(30)))
        // a 60-minute session at exactly 4:00 left: the ring (36 s) comes before the text (1 min)
        val long = rec(60)
        assertEquals(sec(36), TimeLeft.nextRefreshMs(Display.PILL, long, long.deadlineAt - min(4)))
    }

    @Test
    fun barRefreshesInSmallStepsButNeverFasterThanOncePerSecond() {
        assertEquals(sec(3), TimeLeft.nextRefreshMs(Display.BAR, rec(5), t0))   // 5 min / 100
        assertEquals(sec(36), TimeLeft.nextRefreshMs(Display.BAR, rec(60), t0)) // 60 min / 100
        assertEquals(sec(1), TimeLeft.nextRefreshMs(Display.BAR, rec(1), t0))   // 0.6 s -> 1 s
    }

    @Test
    fun nothingToRefreshWhenOffOrOver() {
        assertEquals(null, TimeLeft.nextRefreshMs(Display.OFF, rec(), t0))
        assertEquals(null, TimeLeft.nextRefreshMs(Display.PILL, rec(), t0 + min(5)))
    }

    @Test
    fun unknownSettingFallsBackToThePill() {
        assertEquals(Display.PILL, Display.parse(null))
        assertEquals(Display.PILL, Display.parse("???"))
        assertEquals(Display.BAR, Display.parse("BAR"))
        assertEquals(Display.OFF, Display.parse("OFF"))
    }
}
