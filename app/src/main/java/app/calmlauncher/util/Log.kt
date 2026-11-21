package app.calmlauncher.util

import app.calmlauncher.CalmGraph
import app.calmlauncher.data.EventLogEntity
import app.calmlauncher.engine.EventType

/** Logcat helpers. Filter with `adb logcat -s CalmLauncher:*`. */
object CalmLog {
    const val TAG = "CalmLauncher"

    fun i(message: String) {
        android.util.Log.i(TAG, message)
    }

    fun w(message: String, t: Throwable? = null) {
        android.util.Log.w(TAG, message, t)
    }

    fun e(message: String, t: Throwable? = null) {
        android.util.Log.e(TAG, message, t)
    }
}

/**
 * What may go into `event_log.detail` (data-model.md, Constitution I). Only our own short text
 * is accepted: letters, digits and a few separators, at most 120 characters. Exceptions are
 * recorded by class name only, because an exception message could carry text from another app.
 */
object EventDetail {
    private const val MAX_LENGTH = 120
    private const val ALLOWED_PUNCTUATION = " .:_+-/#"

    fun sanitize(text: String): String =
        text.filter { it.isLetterOrDigit() || it in ALLOWED_PUNCTUATION }.take(MAX_LENGTH)

    fun forError(where: String, t: Throwable): String = sanitize("$where: ${t.javaClass.name}")
}

/**
 * The single function that writes `event_log`. The write happens on the database thread, in
 * order. Failures here are only printed to Logcat, never thrown.
 */
fun logEvent(type: EventType, pkg: String? = null, detail: String? = null, at: Long = System.currentTimeMillis()) {
    val clean = detail?.let { EventDetail.sanitize(it) }
    CalmLog.i("event $type ${pkg.orEmpty()} ${clean.orEmpty()}")
    CalmGraph.onDatabaseThread { db -> db.events().insert(EventLogEntity(at = at, pkg = pkg, type = type, detail = clean)) }
}

/** Records a caught error (fail open): Logcat with the stack trace, `event_log` with the class name only. */
fun logError(where: String, t: Throwable) {
    CalmLog.e("fail open in $where", t)
    logEvent(EventType.ERROR, detail = EventDetail.forError(where, t))
}
