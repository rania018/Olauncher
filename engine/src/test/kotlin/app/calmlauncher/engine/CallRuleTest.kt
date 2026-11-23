package app.calmlauncher.engine

import app.calmlauncher.engine.CallRule.Call
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T105: when a call, the lock screen or a dark screen keeps our UI away (spec FR-018, research R7).
 * Found on the phone: a tracked app used during a WhatsApp call showed no pill and no time's-up card.
 */
class CallRuleTest {

    private fun blocks(
        call: Call = Call.NONE,
        locked: Boolean = false,
        screenOff: Boolean = false,
        trackedInFront: Boolean = false,
    ) = CallRule.blocks(call, locked, screenOff, trackedInFront)

    @Test
    fun nothingBlocksWithoutACallOrLock() {
        assertFalse(blocks())
        assertFalse(blocks(trackedInFront = true))
    }

    @Test
    fun aRunningCallBlocksWhileTheTrackedAppIsNotKnownToBeInFront() {
        // The call screen, the launcher or any other app is in front, or the toggle is off.
        assertTrue(blocks(call = Call.ACTIVE))
    }

    @Test
    fun aRunningCallDoesNotBlockTheTrackedAppInFront() {
        assertFalse(blocks(call = Call.ACTIVE, trackedInFront = true))
    }

    @Test
    fun aRingingCallAlwaysBlocks() {
        assertTrue(blocks(call = Call.RINGING))
        assertTrue(blocks(call = Call.RINGING, trackedInFront = true))
    }

    @Test
    fun theLockScreenAlwaysBlocks() {
        assertTrue(blocks(locked = true))
        assertTrue(blocks(locked = true, trackedInFront = true))
        assertTrue(blocks(call = Call.ACTIVE, locked = true, trackedInFront = true))
    }

    @Test
    fun aDarkScreenAlwaysBlocks() {
        assertTrue(blocks(screenOff = true))
        assertTrue(blocks(screenOff = true, trackedInFront = true))
        assertTrue(blocks(call = Call.ACTIVE, screenOff = true, trackedInFront = true))
    }
}
