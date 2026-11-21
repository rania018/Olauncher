package app.calmlauncher.util

/**
 * Android-side Fail Open wrapper (Constitution II). Every entry point that Android calls into
 * (activities, the service, receivers, the overlay, upstream hooks) runs its body inside this.
 * Any [Throwable] is logged (Logcat + `event_log` type ERROR) and [fallback] is returned, which
 * must always mean "allow / do nothing".
 *
 * [where] names the entry point in the log, e.g. "LaunchGate.intercept".
 */
inline fun <T> failOpen(where: String, fallback: T, block: () -> T): T =
    app.calmlauncher.engine.failOpen(fallback, onError = { logError(where, it) }, block = block)

/** Same as [failOpen] for blocks that return nothing. */
inline fun failOpen(where: String, block: () -> Unit) = failOpen(where, Unit, block)
