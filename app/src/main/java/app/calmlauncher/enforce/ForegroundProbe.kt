package app.calmlauncher.enforce

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import app.calmlauncher.CalmGraph
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.engine.UnplannedOpens.Resume
import app.calmlauncher.onboarding.PermissionStatus
import app.calmlauncher.util.CalmLog

/**
 * "Which app is in front?" without an accessibility service (research R6).
 *
 * UsageStatsManager keeps a history of usage events, such as "this app's screen came to the
 * front at this time". There is no callback when the foreground app changes, so we never poll:
 * we ask once, at the deadline, which app was resumed last, with one retry after 2 s because
 * events can arrive a few seconds late. Needs "Usage access", which upstream already uses for
 * its screen-time display. Only package names and times are read.
 */
object ForegroundProbe {

    enum class Result { TRACKED, OTHER, UNKNOWN }

    private const val LOOKBACK_MS = 10 * 60 * 1000L
    private const val RETRY_MS = 2_000L

    /** Reports on the main thread whether [rec]'s app is in front. */
    fun probe(context: Context, rec: SessionRecord, onResult: (Result) -> Unit) {
        if (!PermissionStatus.usageAccess(context)) {
            onResult(Result.UNKNOWN)
            return
        }
        attempt(context.applicationContext, rec, retry = true, onResult)
    }

    private fun attempt(context: Context, rec: SessionRecord, retry: Boolean, onResult: (Result) -> Unit) {
        CalmGraph.loadThen({ _ ->
            val now = System.currentTimeMillis()
            lastResumed(context, now - LOOKBACK_MS, now)
        }) { last ->
            val result = when {
                last == null -> Result.UNKNOWN
                last.pkg == rec.key.pkg -> Result.TRACKED
                else -> Result.OTHER
            }
            // The newest event is older than the session itself: events are lagging, so ask again once.
            val stale = last == null || last.at < rec.startedAt
            if (retry && result != Result.TRACKED && stale) {
                CalmGraph.main.postDelayed({ attempt(context, rec, retry = false, onResult) }, RETRY_MS)
            } else {
                onResult(result)
            }
        }
    }

    /** Blocking (background thread): the last app that came to the front between [from] and [to]. */
    fun lastResumed(context: Context, from: Long, to: Long): Resume? = resumedEvents(context, from, to).lastOrNull()

    /** Blocking (background thread): every "came to the front" event between [from] and [to]. */
    fun resumedEvents(context: Context, from: Long, to: Long): List<Resume> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        return try {
            val events = usm.queryEvents(from, to) ?: return emptyList()
            val event = UsageEvents.Event()
            val result = ArrayList<Resume>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == RESUMED) result += Resume(event.packageName, event.timeStamp)
            }
            result
        } catch (e: Exception) {
            CalmLog.w("usage events query failed", e)
            emptyList()
        }
    }

    /** ACTIVITY_RESUMED (API 29+) has the same value as the older MOVE_TO_FOREGROUND. */
    @Suppress("DEPRECATION")
    private val RESUMED: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_RESUMED
        else UsageEvents.Event.MOVE_TO_FOREGROUND
}
