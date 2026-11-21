package app.calmlauncher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T015: event_log.detail only ever stores our own short text or an exception class name. */
class EventDetailTest {

    @Test
    fun ourOwnShortTextIsKept() {
        assertEquals("+2", EventDetail.sanitize("+2"))
        assertEquals(
            "LaunchGate.intercept: java.lang.IllegalStateException",
            EventDetail.sanitize("LaunchGate.intercept: java.lang.IllegalStateException"),
        )
    }

    @Test
    fun errorsAreRecordedByClassNameOnly() {
        val error = IllegalStateException("text that might have come from another app")
        val detail = EventDetail.forError("Overlay", error)
        assertEquals("Overlay: java.lang.IllegalStateException", detail)
        assertTrue(!detail.contains("another app"))
    }

    @Test
    fun lengthAndCharactersAreLimited() {
        assertEquals(120, EventDetail.sanitize("x".repeat(500)).length)
        assertEquals("ab", EventDetail.sanitize("a\nb\"<>{}"))
    }
}
