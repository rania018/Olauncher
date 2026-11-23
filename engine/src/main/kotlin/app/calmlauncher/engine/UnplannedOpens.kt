package app.calmlauncher.engine

/**
 * Finds "unplanned opens": a tracked app came to the front without a session, for example from
 * a notification, a link or Recents (data-model.md; spec edge case, US-B2). The MVP cannot stop
 * these, but it counts them so the owner can see how often they happen.
 */
object UnplannedOpens {

    /** One "activity resumed" event from usage history: [pkg] came to the front at [at]. */
    data class Resume(val pkg: String, val at: Long)

    /** A resume this close to a session start or end still belongs to that session. */
    const val SLACK_MS = 10_000L

    /**
     * Returns the opens in [resumes] of tracked apps that no session in [sessions] covers.
     * [trackedSince] maps each tracked package to the time it became tracked; earlier opens do
     * not count. Several resumes of the same app in a row (one app, several screens) count as one
     * open. Sessions still running are treated as lasting until [now].
     */
    fun find(resumes: List<Resume>, trackedSince: Map<String, Long>, sessions: List<SessionRecord>, now: Long): List<Resume> {
        val result = mutableListOf<Resume>()
        var previousPkg: String? = null
        for (r in resumes.sortedBy { it.at }) {
            val isNewOpen = r.pkg != previousPkg
            previousPkg = r.pkg
            val since = trackedSince[r.pkg] ?: continue
            if (!isNewOpen || r.at < since) continue
            val covered = sessions.any { s ->
                s.key.pkg == r.pkg && r.at >= s.startedAt - SLACK_MS && r.at <= (s.endedAt ?: now) + SLACK_MS
            }
            if (!covered) result += r
        }
        return result
    }
}
