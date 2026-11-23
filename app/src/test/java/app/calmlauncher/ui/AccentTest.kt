package app.calmlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** T077: the accent colour choice (FR-030). */
class AccentTest {

    @Test
    fun missingOrUnknownChoiceFollowsThePhone() {
        assertEquals(Accent.PHONE, Accent.parse(null))
        assertEquals(Accent.PHONE, Accent.parse(""))
        assertEquals(Accent.PHONE, Accent.parse("RAINBOW"))
    }

    @Test
    fun savedChoicesReadBack() {
        Accent.entries.forEach { assertEquals(it, Accent.parse(it.name)) }
    }

    @Test
    fun phoneIsTheDefault() {
        assertEquals(Accent.PHONE, Accent.DEFAULT)
    }
}
