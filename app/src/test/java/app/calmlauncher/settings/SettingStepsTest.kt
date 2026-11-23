package app.calmlauncher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T078: minus / plus steps and session-length chips in Screen time limits. */
class SettingStepsTest {

    private val cooldown = SettingSteps.COOLDOWN_MIN

    @Test
    fun stepsMoveToTheNeighbouringValue() {
        assertEquals(15, SettingSteps.step(cooldown, 10, up = true))
        assertEquals(5, SettingSteps.step(cooldown, 10, up = false))
        assertEquals(1, SettingSteps.step(cooldown, 0, up = true))
    }

    @Test
    fun aValueBetweenStepsMovesToTheNearestStepInThatDirection() {
        // 12 was typed in the old text field
        assertEquals(15, SettingSteps.step(cooldown, 12, up = true))
        assertEquals(10, SettingSteps.step(cooldown, 12, up = false))
    }

    @Test
    fun theEndsStayPut() {
        assertEquals(0, SettingSteps.step(cooldown, 0, up = false))
        assertEquals(60, SettingSteps.step(cooldown, 60, up = true))
        assertTrue(SettingSteps.atEnd(cooldown, 0, up = false))
        assertTrue(SettingSteps.atEnd(cooldown, 60, up = true))
        assertFalse(SettingSteps.atEnd(cooldown, 30, up = true))
    }

    @Test
    fun everyStepListStaysInsideTheEngineLimits() {
        assertTrue(SettingSteps.COOLDOWN_MIN.all { it in 0..60 })
        assertTrue(SettingSteps.WARNING_SEC.all { it in 0..300 })
        assertTrue(SettingSteps.EXTENSION_WAIT_SEC.all { it in 0..300 })
        listOf(SettingSteps.COOLDOWN_MIN, SettingSteps.WARNING_SEC, SettingSteps.EXTENSION_WAIT_SEC).forEach {
            assertEquals(it.sorted().distinct(), it)
        }
    }

    @Test
    fun addingAPresetKeepsTheListSortedAndValid() {
        assertEquals(listOf(2, 5, 7, 10), SettingSteps.addPreset(listOf(2, 5, 10), 7))
        assertNull(SettingSteps.addPreset(listOf(2, 5, 10), 5))   // already there
        assertNull(SettingSteps.addPreset(listOf(2, 5, 10), 0))   // out of range
        assertNull(SettingSteps.addPreset(listOf(2, 5, 10), 61))
        assertNull(SettingSteps.addPreset(listOf(1, 2, 3, 4, 5, 6), 7)) // full
    }

    @Test
    fun theLastPresetCannotBeRemoved() {
        assertEquals(listOf(2, 10), SettingSteps.removePreset(listOf(2, 5, 10), 5))
        assertNull(SettingSteps.removePreset(listOf(5), 5))
        assertNull(SettingSteps.removePreset(listOf(2, 5), 3))
    }
}
