package app.calmlauncher.engine

import app.calmlauncher.engine.ExemptRules.TrackRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T072, moved into the engine by T103 (Constitution VI): an app is either tracked or exempt, never
 * both (spec FR-004).
 */
class ExemptRulesTest {

    @Test
    fun appThatIsNotExemptCanBeTracked() {
        assertEquals(TrackRequest.TRACK, ExemptRules.whenTracking(null))
    }

    @Test
    fun phoneSmsAndSettingsCanNeverBeTracked() {
        assertEquals(TrackRequest.REFUSE_ALWAYS_EXEMPT, ExemptRules.whenTracking(ExemptionKind.ALWAYS))
    }

    @Test
    fun otherDefaultExemptionsNeedConfirmationBeforeTracking() {
        assertEquals(TrackRequest.CONFIRM_REMOVE_DEFAULT, ExemptRules.whenTracking(ExemptionKind.DEFAULT))
    }

    @Test
    fun userExemptionsAreSimplySwapped() {
        assertEquals(TrackRequest.REMOVE_EXEMPTION_THEN_TRACK, ExemptRules.whenTracking(ExemptionKind.USER))
    }

    @Test
    fun existingOverlapsAreResolvedTowardsExempt() {
        val tracked = listOf(
            AppKey("com.instagram.android", 0),
            AppKey("com.facebook.orca", 0),
            AppKey("com.facebook.orca", 10), // work-profile copy: exemption covers every profile
        )
        assertEquals(
            listOf(AppKey("com.facebook.orca", 0), AppKey("com.facebook.orca", 10)),
            ExemptRules.trackedToDrop(tracked, exemptPkgs = setOf("com.facebook.orca", "com.whatsapp")),
        )
        assertEquals(emptyList<AppKey>(), ExemptRules.trackedToDrop(tracked, emptySet()))
    }

    // ------------------------------------------------- TrackedApp.of (data-model.md, analysis C3)

    @Test
    fun aTrackedAppKeepsAtMostSixSessionLengths() {
        val app = TrackedApp.of(Fx.INSTAGRAM, listOf(30, 1, 2, 5, 10, 15, 20, 45), cooldownMin = 10)
        assertEquals(TrackedApp.MAX_PRESETS, app.presetsMin.size)
        // The six shortest are kept, whatever order they came in.
        assertEquals(listOf(1, 2, 5, 10, 15, 20), app.presetsMin)
    }

    @Test
    fun sixOrFewerSessionLengthsAreAllKept() {
        assertEquals(listOf(1, 2, 5, 10, 15, 20), TrackedApp.of(Fx.INSTAGRAM, listOf(20, 15, 10, 5, 2, 1), 10).presetsMin)
        assertEquals(listOf(5), TrackedApp.of(Fx.INSTAGRAM, listOf(5, 5, 0, 90), 10).presetsMin)
        assertEquals(TrackedApp.DEFAULT_PRESETS, TrackedApp.of(Fx.INSTAGRAM, emptyList(), 10).presetsMin)
    }
}
