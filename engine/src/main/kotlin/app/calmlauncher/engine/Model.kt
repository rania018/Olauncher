package app.calmlauncher.engine

/** Shortest and longest session a user can declare, in minutes (spec FR-006). */
const val MIN_SESSION_MIN = 1
const val MAX_SESSION_MIN = 60

const val MINUTE_MS = 60_000L
const val SECOND_MS = 1_000L

/**
 * Identity of an app in one user profile. A work-profile or Private Space copy of an app is a
 * different [AppKey] from the main-profile copy, because it has a different [userSerial].
 */
data class AppKey(val pkg: String, val userSerial: Long)

/** Why a session ended. Stored as text in `session.end_reason`. */
enum class EndReason { EXPIRED, ENDED_BY_USER, LEFT_EARLY, INTERRUPTED }

/** Kinds of `event_log` rows (data-model.md). */
enum class EventType {
    PROMPT_CANCELLED,
    EXTENDED,
    OVERRIDE,
    OVERLAY_TIMEOUT,
    UNPLANNED_OPEN,
    SERVICE_INTERRUPTED,
    ERROR,
    COOLDOWN_SHOWN,
    LOOP_GUARD,
}

/**
 * An app the user opted into tracking.
 *
 * Invariants (data-model.md `TrackedApp`): [presetsMin] is non-empty, sorted, distinct and every
 * value is in 1..60; [cooldownMin] is in 0..60, where 0 turns the cooldown off. The constructor
 * rejects anything else; use [of] to build one from untrusted input such as the database. [of]
 * also keeps at most [MAX_PRESETS] session lengths (spec FR-024).
 */
data class TrackedApp(val key: AppKey, val presetsMin: List<Int>, val cooldownMin: Int) {
    init {
        require(presetsMin.isNotEmpty()) { "presets must not be empty" }
        require(presetsMin.all { it in MIN_SESSION_MIN..MAX_SESSION_MIN }) { "presets must be 1..60" }
        require(presetsMin == presetsMin.distinct().sorted()) { "presets must be sorted and distinct" }
        require(cooldownMin in 0..MAX_COOLDOWN_MIN) { "cooldown must be 0..60" }
    }

    companion object {
        val DEFAULT_PRESETS = listOf(2, 5, 10, 15)
        const val DEFAULT_COOLDOWN_MIN = 10
        const val MAX_COOLDOWN_MIN = 60

        /** The prompt offers at most this many session lengths (spec FR-024). */
        const val MAX_PRESETS = 6

        /**
         * Builds a valid [TrackedApp] from any input: drops bad presets, keeps the [MAX_PRESETS]
         * shortest, clamps the cooldown.
         */
        fun of(key: AppKey, presetsMin: List<Int>, cooldownMin: Int): TrackedApp {
            val presets = presetsMin.filter { it in MIN_SESSION_MIN..MAX_SESSION_MIN }
                .distinct()
                .sorted()
                .take(MAX_PRESETS)
                .ifEmpty { DEFAULT_PRESETS }
            return TrackedApp(key, presets, cooldownMin.coerceIn(0, MAX_COOLDOWN_MIN))
        }
    }
}

/**
 * Packages that are never prompted, timed or blocked (Constitution II). An exemption applies to
 * every user profile of the package.
 */
data class Exemptions(val defaults: Set<String> = emptySet(), val user: Set<String> = emptySet()) {
    fun isExempt(pkg: String): Boolean = pkg in defaults || pkg in user
}

/**
 * Tunable numbers. Values come from settings, so they may be out of range; the engine always works
 * on [sanitized] values.
 */
data class Config(
    val warningLeadSec: Int = 60,
    val extensionWaitSec: Int = 15,
    val extensionOptionsMin: List<Int> = DEFAULT_EXTENSION_OPTIONS,
    val maxExtensions: Int = 2,
    val overrideWaitSec: Int = 30,
    val overrideGrantMin: Int = 5,
    val graceSec: Int = 120,
    val customMaxMin: Int = MAX_SESSION_MIN,
    val cardTimeoutSec: Int = 120,
) {
    /** Clamps every value into its allowed range. The override wait never drops below 10 s (Constitution III). */
    fun sanitized(): Config = Config(
        warningLeadSec = warningLeadSec.coerceIn(0, 300),
        extensionWaitSec = extensionWaitSec.coerceIn(0, 300),
        extensionOptionsMin = extensionOptionsMin
            .filter { it in MIN_SESSION_MIN..MAX_SESSION_MIN }
            .distinct()
            .sorted()
            .ifEmpty { DEFAULT_EXTENSION_OPTIONS },
        maxExtensions = maxExtensions.coerceIn(0, MAX_EXTENSIONS),
        overrideWaitSec = overrideWaitSec.coerceIn(MIN_OVERRIDE_WAIT_SEC, 300),
        overrideGrantMin = overrideGrantMin.coerceIn(MIN_SESSION_MIN, MAX_SESSION_MIN),
        graceSec = graceSec.coerceIn(30, 600),
        customMaxMin = customMaxMin.coerceIn(MIN_SESSION_MIN, MAX_SESSION_MIN),
        cardTimeoutSec = cardTimeoutSec.coerceIn(10, MAX_CARD_TIMEOUT_SEC),
    )

    companion object {
        val DEFAULT_EXTENSION_OPTIONS = listOf(2, 5)
        const val MAX_EXTENSIONS = 2
        const val MIN_OVERRIDE_WAIT_SEC = 10
        const val MAX_CARD_TIMEOUT_SEC = 120
    }
}

/**
 * One declared use of a tracked app (table `session`).
 *
 * [deadlineAt] = [startedAt] + [declaredMs] + any time added by extensions or overrides.
 * [endedAt] == null means the session is still active; at most one session is active at a time.
 * [leftAt] is when the user went back to the launcher during the session (US6), if they did.
 */
data class SessionRecord(
    val id: Long = 0,
    val key: AppKey,
    val startedAt: Long,
    val declaredMs: Long,
    val deadlineAt: Long,
    val endedAt: Long? = null,
    val endReason: EndReason? = null,
    val extensions: Int = 0,
    val overrides: Int = 0,
    val leftAt: Long? = null,
) {
    val isActive: Boolean get() = endedAt == null

    fun ended(reason: EndReason, at: Long, leftAt: Long? = this.leftAt): SessionRecord =
        copy(endedAt = at, endReason = reason, leftAt = leftAt)
}
