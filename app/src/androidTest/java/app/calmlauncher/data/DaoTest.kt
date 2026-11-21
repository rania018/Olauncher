package app.calmlauncher.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.calmlauncher.engine.EndReason
import app.calmlauncher.engine.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room DAO checks that need real SQLite (T066). Run with the phone attached:
 * `./gradlew connectedDebugAndroidTest`. Uses an in-memory database, so the app's data is untouched.
 */
@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var db: CalmDatabase
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CalmDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(pkg: String, start: Long) = SessionEntity(
        pkg = pkg, userSerial = 0, startedAt = start, declaredMs = 300_000, deadlineAt = start + 300_000,
    )

    @Test
    fun startingASessionLeavesAtMostOneActive() {
        val dao = db.sessions()
        dao.startSession(session("com.instagram.android", t0))
        dao.startSession(session("com.google.android.youtube", t0 + 60_000))
        val active = dao.active()
        assertEquals(1, active.size)
        assertEquals("com.google.android.youtube", active.single().pkg)
        val closed = dao.all().first { it.pkg == "com.instagram.android" }
        assertEquals(EndReason.INTERRUPTED, closed.endReason)
        assertEquals(t0 + 60_000, closed.endedAt)
    }

    @Test
    fun saveActiveUpdatesTheRunningRow() {
        val dao = db.sessions()
        val s = session("com.instagram.android", t0)
        dao.saveActive(s) // not there yet: starts it
        dao.saveActive(s.copy(deadlineAt = s.deadlineAt + 120_000, extensions = 1))
        val row = dao.active().single()
        assertEquals(1, row.extensions)
        assertEquals(s.deadlineAt + 120_000, row.deadlineAt)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun endClosesTheRowWithItsReasonAndLeftTime() {
        val dao = db.sessions()
        val s = session("com.instagram.android", t0)
        dao.startSession(s)
        dao.end(s.copy(endedAt = t0 + 200_000, endReason = EndReason.LEFT_EARLY, leftAt = t0 + 80_000))
        assertEquals(0, dao.active().size)
        val row = dao.all().single()
        assertEquals(EndReason.LEFT_EARLY, row.endReason)
        assertEquals(t0 + 80_000, row.leftAt)
    }

    @Test
    fun endingASessionThatWasNeverStoredStillRecordsIt() {
        val dao = db.sessions()
        dao.end(session("com.instagram.android", t0).copy(endedAt = t0 + 300_000, endReason = EndReason.EXPIRED))
        assertEquals(EndReason.EXPIRED, dao.all().single().endReason)
    }

    @Test
    fun recentReturnsActiveAndRecentlyEnded() {
        val dao = db.sessions()
        dao.end(session("old", t0).copy(endedAt = t0 + 1, endReason = EndReason.EXPIRED))
        dao.end(session("recent", t0).copy(endedAt = t0 + 10_000_000, endReason = EndReason.EXPIRED))
        dao.startSession(session("active", t0 + 20_000_000))
        assertEquals(setOf("recent", "active"), dao.recent(t0 + 5_000_000).map { it.pkg }.toSet())
    }

    @Test
    fun enumsRoundTrip() {
        EventType.entries.forEachIndexed { i, type ->
            db.events().insert(EventLogEntity(at = t0 + i, pkg = null, type = type, detail = null))
        }
        assertEquals(EventType.entries.toList(), db.events().since(0).map { it.type })

        val exempt = db.exemptApps()
        ExemptCategory.entries.forEachIndexed { i, category ->
            exempt.upsert(ExemptAppEntity("pkg$i", ExemptSource.entries[i % 2], category, t0))
        }
        exempt.upsert(ExemptAppEntity("none", ExemptSource.USER, null, t0))
        val rows = exempt.all().associateBy { it.pkg }
        ExemptCategory.entries.forEachIndexed { i, category ->
            assertEquals(category, rows["pkg$i"]!!.category)
            assertEquals(ExemptSource.entries[i % 2], rows["pkg$i"]!!.source)
        }
        assertNull(rows["none"]!!.category)

        EndReason.entries.forEachIndexed { i, reason ->
            db.sessions().end(session("p$i", t0 + i).copy(endedAt = t0 + 1_000, endReason = reason))
        }
        assertEquals(EndReason.entries.toSet(), db.sessions().all().mapNotNull { it.endReason }.toSet())
    }

    @Test
    fun defaultExemptionsNeverOverwriteAUserChoice() {
        val exempt = db.exemptApps()
        exempt.upsert(ExemptAppEntity("com.whatsapp", ExemptSource.USER, ExemptCategory.MESSAGING, t0))
        exempt.insertIfAbsent(listOf(ExemptAppEntity("com.whatsapp", ExemptSource.DEFAULT, null, t0 + 1)))
        assertEquals(ExemptSource.USER, exempt.all().single().source)
    }

    @Test
    fun trackedAppsArePerProfile() {
        val tracked = db.trackedApps()
        tracked.upsert(TrackedAppEntity("com.instagram.android", 0, "2,5", 10, t0))
        tracked.upsert(TrackedAppEntity("com.instagram.android", 10, "5", 0, t0))
        assertEquals(2, tracked.all().size)
        tracked.delete("com.instagram.android", 10)
        assertEquals(0L, tracked.all().single().userSerial)
    }
}
