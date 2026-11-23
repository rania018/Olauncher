package app.calmlauncher.exempt

import app.calmlauncher.data.ExemptAppEntity
import app.calmlauncher.data.ExemptCategory
import app.calmlauncher.data.ExemptSource
import app.calmlauncher.engine.AppKey

/**
 * An app is either tracked or exempt, never both (spec FR-004). Pure rules, unit-tested; the
 * settings screen and SessionRepository apply them. The rules engine still checks exemptions
 * first, so even a stray overlap could never limit an exempt app.
 */
object ExemptRules {

    /** What happens when the user ticks "Track" on an app with this exemption row (null = not exempt). */
    enum class TrackRequest {
        /** Not exempt: track it. */
        TRACK,

        /** Phone, SMS or Settings: always exempt, cannot be tracked (Constitution II). */
        REFUSE_ALWAYS_EXEMPT,

        /** A default exemption: removing it needs explicit confirmation first (FR-021). */
        CONFIRM_REMOVE_DEFAULT,

        /** The user's own exemption: remove it and track. */
        REMOVE_EXEMPTION_THEN_TRACK,
    }

    fun whenTracking(exemption: ExemptAppEntity?): TrackRequest = when {
        exemption == null -> TrackRequest.TRACK
        exemption.category == ExemptCategory.SYSTEM -> TrackRequest.REFUSE_ALWAYS_EXEMPT
        exemption.source == ExemptSource.DEFAULT -> TrackRequest.CONFIRM_REMOVE_DEFAULT
        else -> TrackRequest.REMOVE_EXEMPTION_THEN_TRACK
    }

    /**
     * Tracked apps whose package is exempt. Overlaps are resolved towards exempt, the safe
     * direction, and an exemption covers every user profile of the package.
     */
    fun trackedToDrop(tracked: Collection<AppKey>, exemptPkgs: Set<String>): List<AppKey> =
        tracked.filter { it.pkg in exemptPkgs }
}
