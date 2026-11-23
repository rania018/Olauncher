package app.calmlauncher.exempt

import app.calmlauncher.data.ExemptAppEntity
import app.calmlauncher.data.ExemptCategory
import app.calmlauncher.data.ExemptSource
import app.calmlauncher.engine.AppKey
import app.calmlauncher.exempt.ExemptRules.TrackRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** T072: an app is either tracked or exempt, never both (FR-004). */
class ExemptRulesTest {

    private fun row(pkg: String, source: ExemptSource, category: ExemptCategory?) = ExemptAppEntity(pkg, source, category, 0)

    @Test
    fun appThatIsNotExemptCanBeTracked() {
        assertEquals(TrackRequest.TRACK, ExemptRules.whenTracking(null))
    }

    @Test
    fun phoneSmsAndSettingsCanNeverBeTracked() {
        assertEquals(TrackRequest.REFUSE_ALWAYS_EXEMPT, ExemptRules.whenTracking(row("dialer", ExemptSource.DEFAULT, ExemptCategory.SYSTEM)))
    }

    @Test
    fun otherDefaultExemptionsNeedConfirmationBeforeTracking() {
        assertEquals(TrackRequest.CONFIRM_REMOVE_DEFAULT, ExemptRules.whenTracking(row("maps", ExemptSource.DEFAULT, null)))
    }

    @Test
    fun userExemptionsAreSimplySwapped() {
        assertEquals(TrackRequest.REMOVE_EXEMPTION_THEN_TRACK, ExemptRules.whenTracking(row("com.facebook.orca", ExemptSource.USER, ExemptCategory.MESSAGING)))
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
}
