package app.calmlauncher.session

import app.calmlauncher.engine.SessionRecord

/**
 * What the on-screen time-left reminder shows, and when it needs to change (spec FR-029). Pure
 * Kotlin so it is unit-tested; [TimeLeftOverlay] draws it.
 */
object TimeLeft {

    /** The user's choice in Screen time limits. */
    enum class Display {
        PILL, BAR, OFF;

        companion object {
            fun parse(value: String?): Display = entries.firstOrNull { it.name == value } ?: PILL
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val SECOND_MS = 1_000L

    /** "4 min" while more than a minute is left (rounded up), then "0:42". */
    fun label(remainingMs: Long): String {
        val r = remainingMs.coerceAtLeast(0)
        if (r > MINUTE_MS) return "${(r + MINUTE_MS - 1) / MINUTE_MS} min"
        val seconds = (r + SECOND_MS - 1) / SECOND_MS
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Share of the session still left: 1 at the start, 0 at the deadline. */
    fun fraction(rec: SessionRecord, now: Long): Float {
        val total = (rec.deadlineAt - rec.startedAt).coerceAtLeast(1)
        return ((rec.deadlineAt - now).toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * Milliseconds until the reminder must be redrawn, or null when nothing will change. The bar,
     * and the ring inside the pill, move in steps of 1% of the session, at most once per second.
     * The pill's text also changes once a minute, and every second in the last minute; it is
     * redrawn at whichever comes first (plan.md Complexity Tracking).
     */
    fun nextRefreshMs(display: Display, rec: SessionRecord, now: Long): Long? {
        val remaining = rec.deadlineAt - now
        if (remaining <= 0) return null
        val progressStep = ((rec.deadlineAt - rec.startedAt) / 100).coerceAtLeast(SECOND_MS)
        return when (display) {
            Display.OFF -> null
            Display.PILL -> {
                val step = if (remaining > MINUTE_MS) MINUTE_MS else SECOND_MS
                val textChange = (remaining % step).takeIf { it != 0L } ?: step
                minOf(textChange, progressStep)
            }
            Display.BAR -> progressStep
        }
    }
}
