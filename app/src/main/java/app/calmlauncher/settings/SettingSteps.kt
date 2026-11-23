package app.calmlauncher.settings

import app.calmlauncher.engine.MAX_SESSION_MIN
import app.calmlauncher.engine.MIN_SESSION_MIN

/**
 * The values the minus and plus buttons in Screen time limits step through, and the rules for
 * adding and removing session lengths. Pure Kotlin so it is unit-tested.
 */
object SettingSteps {

    /** Cooldown after time's up, minutes (0 = off). Upper end is TrackedApp.MAX_COOLDOWN_MIN. */
    val COOLDOWN_MIN = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60)

    /** Warning before the end, seconds (0 = off). Upper end is Config's 300 s limit. */
    val WARNING_SEC = listOf(0, 15, 30, 45, 60, 90, 120, 180, 240, 300)

    /** Wait before "more time" can be chosen, seconds. Upper end is Config's 300 s limit. */
    val EXTENSION_WAIT_SEC = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300)

    /** At most this many session lengths, so "How long?" stays on one line on most phones. */
    const val MAX_PRESETS = 6

    /**
     * The next value up ([up] = true) or down from [current]. A value saved earlier that sits between
     * two steps moves to the nearest step in that direction. At either end the value stays put.
     */
    fun step(options: List<Int>, current: Int, up: Boolean): Int =
        if (up) options.firstOrNull { it > current } ?: current
        else options.lastOrNull { it < current } ?: current

    /** True when [current] is already at the end it would move towards. */
    fun atEnd(options: List<Int>, current: Int, up: Boolean): Boolean = step(options, current, up) == current

    /** [presets] with [minutes] added, sorted; null if it is out of range, already there, or the list is full. */
    fun addPreset(presets: List<Int>, minutes: Int): List<Int>? = when {
        minutes !in MIN_SESSION_MIN..MAX_SESSION_MIN -> null
        minutes in presets -> null
        presets.size >= MAX_PRESETS -> null
        else -> (presets + minutes).sorted()
    }

    /** [presets] without [minutes]; null if that would leave none (the prompt needs at least one). */
    fun removePreset(presets: List<Int>, minutes: Int): List<Int>? = when {
        minutes !in presets -> null
        presets.size <= 1 -> null
        else -> presets - minutes
    }
}
