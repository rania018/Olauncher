package app.calmlauncher.engine

/**
 * How an app is exempt (data-model.md `exempt_app.category` and `exempt_app.source`). The Android
 * side maps a database row to one of these; null means "not exempt".
 */
enum class ExemptionKind {
    /** Phone, SMS or Settings (category SYSTEM): always exempt, never tracked or removed (Constitution II). */
    ALWAYS,

    /** Exempt by default (source DEFAULT: maps, camera, clock): removable after an explicit confirmation (FR-021). */
    DEFAULT,

    /** Made exempt by the user (source USER). */
    USER,
}

/**
 * An app is either tracked or exempt, never both (spec FR-004). Pure rules, unit-tested; the
 * settings screen and the session repository apply them. The rules engine still checks exemptions
 * first, so even a stray overlap could never limit an exempt app.
 */
object ExemptRules {

    /** What happens when the user turns on "Track" for an app. */
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

    /** [kind] is how the app is exempt right now, or null when it is not. */
    fun whenTracking(kind: ExemptionKind?): TrackRequest = when (kind) {
        null -> TrackRequest.TRACK
        ExemptionKind.ALWAYS -> TrackRequest.REFUSE_ALWAYS_EXEMPT
        ExemptionKind.DEFAULT -> TrackRequest.CONFIRM_REMOVE_DEFAULT
        ExemptionKind.USER -> TrackRequest.REMOVE_EXEMPTION_THEN_TRACK
    }

    /**
     * Tracked apps whose package is exempt. Overlaps are resolved towards exempt, the safe
     * direction, and an exemption covers every user profile of the package.
     */
    fun trackedToDrop(tracked: Collection<AppKey>, exemptPkgs: Set<String>): List<AppKey> =
        tracked.filter { it.pkg in exemptPkgs }
}
