package app.calmlauncher.engine

/**
 * Runs [block]; if it throws anything, reports the error to [onError] and returns [fallback].
 *
 * This is how every engine entry point keeps the Fail Open rule (Constitution II): an error can
 * only ever lead to "allow" or "do nothing", never to a block. [onError] itself must not break
 * that rule, so its own exceptions are swallowed.
 */
inline fun <T> failOpen(fallback: T, onError: (Throwable) -> Unit = {}, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        try {
            onError(t)
        } catch (_: Throwable) {
        }
        fallback
    }
