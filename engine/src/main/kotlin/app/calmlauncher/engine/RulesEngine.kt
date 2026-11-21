package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.Allow
import app.calmlauncher.engine.Decision.AskDuration
import app.calmlauncher.engine.Decision.Blocked

/**
 * Decides what happens when the user opens an app from the launcher.
 *
 * The checks run in the fixed order of TECHNICAL_DESIGN §4.2; the first match wins. Checks that
 * belong to Phase 2 (pause, schedules, caps, "one more?", delay, intent) are not part of the MVP.
 */
object RulesEngine {

    /** Pure. Never throws: any internal error returns Allow(FAIL_OPEN). */
    fun decide(
        app: AppKey,
        now: Long,
        tracked: Map<AppKey, TrackedApp>,
        exemptions: Exemptions,
        history: List<SessionRecord>,
        cfg: Config,
    ): Decision = failOpen(Allow(AllowReason.FAIL_OPEN)) {
        decideOrThrow(app, now, tracked, exemptions, history, cfg)
    }

    private fun decideOrThrow(
        app: AppKey,
        now: Long,
        tracked: Map<AppKey, TrackedApp>,
        exemptions: Exemptions,
        history: List<SessionRecord>,
        cfg: Config,
    ): Decision {
        // 1. Exempt apps are never delayed, prompted, timed or blocked. Checked before anything
        //    else touches the (possibly corrupt) tracked map, history or config.
        if (exemptions.isExempt(app.pkg)) return Allow(AllowReason.EXEMPT)

        // 2. Enforcement paused: Phase 2.

        // 3. Not tracked: open as usual.
        val rule = tracked[app] ?: return Allow(AllowReason.NOT_TRACKED)

        // 4. A session for this app is already running: open it and keep the session.
        if (history.any { it.key == app && it.isActive && now < it.deadlineAt }) {
            return Allow(AllowReason.RUNNING)
        }

        // 5-6. Schedules and caps: Phase 2.

        // 7. Cooldown after an expired session.
        val until = cooldownUntil(app, history, rule.cooldownMin, now)
        if (until != null && now < until) return Blocked(BlockKind.COOLDOWN, until)

        // 8-10. "One more?", launch delay, intent prompt: Phase 2.

        // 11. Ask how long.
        return AskDuration(rule.presetsMin, cfg.sanitized().customMaxMin)
    }

    /**
     * End of the cooldown for [app], or null when there is none (research R10).
     *
     * Only sessions that ended as EXPIRED start a cooldown. An active session whose deadline has
     * passed but which has not been closed yet counts as expired at its deadline. If the clock
     * was moved backwards, the expiry is capped at [now] so a clock change cannot cause a long block.
     */
    fun cooldownUntil(app: AppKey, history: List<SessionRecord>, cooldownMin: Int, now: Long): Long? {
        if (cooldownMin <= 0) return null
        val lastExpiry = history
            .filter { it.key == app }
            .mapNotNull { rec ->
                when {
                    rec.endReason == EndReason.EXPIRED && rec.endedAt != null -> rec.endedAt
                    rec.isActive && now >= rec.deadlineAt -> rec.deadlineAt
                    else -> null
                }
            }
            .maxOrNull() ?: return null
        return minOf(lastExpiry, now) + cooldownMin * MINUTE_MS
    }
}
