package app.calmlauncher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the :engine test task runs on the plain JVM (no Android, no device). */
class SmokeTest {
    @Test
    fun engineTestsRunOnTheJvm() {
        assertEquals(4, 2 + 2)
    }
}
