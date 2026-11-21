package app.calmlauncher.data

import app.calmlauncher.engine.AppKey
import app.calmlauncher.engine.EndReason
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.engine.TrackedApp
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun presetsRoundTrip() {
        val app = TrackedApp(AppKey("com.instagram.android", 0), listOf(2, 5, 10, 15), 10)
        val entity = Mappers.toEntity(app, createdAt = 1L)
        assertEquals("2,5,10,15", entity.presetsCsv)
        assertEquals(app, Mappers.toTrackedApp(entity))
    }

    @Test
    fun messyPresetsAreCleanedUp() {
        val entity = TrackedAppEntity("p", 0, " 15, 2,x,,2, 90, 0, 5 ", cooldownMin = 10, createdAt = 0)
        assertEquals(listOf(2, 5, 15), Mappers.toTrackedApp(entity).presetsMin)
    }

    @Test
    fun emptyPresetsFallBackToDefaults() {
        val entity = TrackedAppEntity("p", 0, "", cooldownMin = 10, createdAt = 0)
        assertEquals(TrackedApp.DEFAULT_PRESETS, Mappers.toTrackedApp(entity).presetsMin)
    }

    @Test
    fun cooldownIsClamped() {
        assertEquals(60, Mappers.toTrackedApp(TrackedAppEntity("p", 0, "5", 999, 0)).cooldownMin)
        assertEquals(0, Mappers.toTrackedApp(TrackedAppEntity("p", 0, "5", -3, 0)).cooldownMin)
    }

    @Test
    fun userSerialIsKeptPerProfile() {
        val work = TrackedApp(AppKey("com.instagram.android", 10), listOf(5), 0)
        val entity = Mappers.toEntity(work, createdAt = 0)
        assertEquals(10L, entity.userSerial)
        assertEquals(AppKey("com.instagram.android", 10), Mappers.toTrackedApp(entity).key)
    }

    @Test
    fun sessionRoundTrip() {
        val rec = SessionRecord(
            id = 7, key = AppKey("com.instagram.android", 10), startedAt = 1_000, declaredMs = 120_000,
            deadlineAt = 241_000, endedAt = 200_000, endReason = EndReason.LEFT_EARLY,
            extensions = 1, overrides = 2, leftAt = 150_000,
        )
        assertEquals(rec, Mappers.toRecord(Mappers.toEntity(rec)))
    }

    @Test
    fun enumConvertersSurviveUnknownText() {
        val c = Converters()
        assertEquals(EndReason.INTERRUPTED, c.textToEndReason("SOMETHING_NEW"))
        assertEquals(null, c.textToEndReason(null))
        assertEquals(ExemptSource.USER, c.textToSource("???"))
        assertEquals(null, c.textToCategory("???"))
        EndReason.entries.forEach { assertEquals(it, c.textToEndReason(c.endReasonToText(it))) }
    }

    @Test
    fun healthNoticeRoundTrip() {
        val notice = SettingsStore.HealthNotice("INTERRUPTED", 123L)
        assertEquals(notice, SettingsStore.decodeNotice(SettingsStore.encodeNotice(notice)))
        assertEquals(null, SettingsStore.decodeNotice("garbage"))
    }
}
