package app.calmlauncher.engine

import app.calmlauncher.engine.Fx.INSTAGRAM
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.min
import app.calmlauncher.engine.Fx.sec
import app.calmlauncher.engine.UnplannedOpens.Resume
import org.junit.Assert.assertEquals
import org.junit.Test

class UnplannedOpensTest {

    private val ig = INSTAGRAM.pkg
    private val launcher = "app.calmlauncher"
    private val tracked = setOf(ig)

    @Test
    fun openWithoutASessionIsUnplanned() {
        val resumes = listOf(Resume(ig, T0))
        assertEquals(resumes, UnplannedOpens.find(resumes, tracked, emptyList(), T0 + min(1)))
    }

    @Test
    fun opensDuringASessionArePlanned() {
        val session = Fx.active(INSTAGRAM, start = T0, minutes = 5)
        val resumes = listOf(Resume(ig, T0 + sec(1)), Resume(launcher, T0 + min(1)), Resume(ig, T0 + min(2)))
        assertEquals(emptyList<Resume>(), UnplannedOpens.find(resumes, tracked, listOf(session), T0 + min(3)))
    }

    @Test
    fun openAfterTheSessionEndedIsUnplanned() {
        val session = Fx.ended(INSTAGRAM, endedAt = T0 + min(5), reason = EndReason.EXPIRED)
        val late = Resume(ig, T0 + min(20))
        assertEquals(listOf(late), UnplannedOpens.find(listOf(late), tracked, listOf(session), T0 + min(30)))
    }

    @Test
    fun severalScreensOfOneAppCountOnce() {
        val resumes = listOf(Resume(ig, T0), Resume(ig, T0 + sec(5)), Resume(ig, T0 + sec(9)))
        assertEquals(listOf(Resume(ig, T0)), UnplannedOpens.find(resumes, tracked, emptyList(), T0 + min(1)))
    }

    @Test
    fun untrackedAppsAreIgnored() {
        val resumes = listOf(Resume("com.example.notes", T0), Resume(launcher, T0 + sec(5)))
        assertEquals(emptyList<Resume>(), UnplannedOpens.find(resumes, tracked, emptyList(), T0 + min(1)))
    }

    @Test
    fun returningToTheAppAfterAnotherAppIsANewOpen() {
        val resumes = listOf(Resume(ig, T0), Resume("com.whatsapp", T0 + min(1)), Resume(ig, T0 + min(2)))
        assertEquals(2, UnplannedOpens.find(resumes, tracked, emptyList(), T0 + min(3)).size)
    }
}
