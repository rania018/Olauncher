package app.calmlauncher.engine

/** Why a launch is allowed straight through. */
enum class AllowReason { EXEMPT, NOT_TRACKED, RUNNING, FAIL_OPEN }

/** Why a launch is blocked. MVP: only the post-expiry cooldown. */
enum class BlockKind { COOLDOWN }

/** What the launcher should do when the user opens an app (contracts/engine-api.md). */
sealed interface Decision {
    /** Open the app now, as upstream would. */
    data class Allow(val reason: AllowReason) : Decision

    /** Show "How long?" first. */
    data class AskDuration(val presetsMin: List<Int>, val customMaxMin: Int) : Decision

    /**
     * Show the blocked screen until [until] (epoch ms).
     *
     * [overridable] is not a constructor parameter: every block can be overridden, by type
     * (Constitution III, contracts invariant 3).
     */
    data class Blocked(val kind: BlockKind, val until: Long) : Decision {
        val overridable: Boolean get() = true
    }
}
