package app.calmlauncher.enforce

/**
 * Safety net against a bug that would show the card again and again (TECHNICAL_DESIGN §6 item 6):
 * more than [maxActions] enforcement actions on the same app within [windowMs] pause enforcement
 * for that app for [pauseMs]. Plain Kotlin, unit-tested.
 */
class LoopGuard(
    private val maxActions: Int = 3,
    private val windowMs: Long = 30_000L,
    private val pauseMs: Long = 10 * 60_000L,
) {
    private val actions = HashMap<String, ArrayDeque<Long>>()
    private val pausedUntil = HashMap<String, Long>()

    fun isPaused(pkg: String, now: Long): Boolean = (pausedUntil[pkg] ?: Long.MIN_VALUE) > now

    /** The latest end of any pause still running, or null. Used by the home health line. */
    fun pausedUntil(now: Long): Long? = pausedUntil.values.filter { it > now }.maxOrNull()

    /**
     * Records one enforcement action on [pkg]. Returns true if this action is one too many: the
     * action must then not happen, and enforcement for [pkg] is paused.
     */
    fun record(pkg: String, now: Long): Boolean {
        val recent = actions.getOrPut(pkg) { ArrayDeque() }
        recent.addLast(now)
        while (recent.isNotEmpty() && recent.first() <= now - windowMs) recent.removeFirst()
        if (recent.size > maxActions) {
            pausedUntil[pkg] = now + pauseMs
            recent.clear()
            return true
        }
        return false
    }
}
