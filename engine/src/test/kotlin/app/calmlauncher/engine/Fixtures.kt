package app.calmlauncher.engine

/** Shared test data. Times are fixed epoch milliseconds, so every test is deterministic. */
object Fx {
    const val T0 = 1_700_000_000_000L

    val INSTAGRAM = AppKey("com.instagram.android", 0)
    val INSTAGRAM_WORK = AppKey("com.instagram.android", 10)
    val YOUTUBE = AppKey("com.google.android.youtube", 0)
    val DIALER = AppKey("com.samsung.android.dialer", 0)
    val NOTES = AppKey("com.example.notes", 0)

    val CFG = Config()

    fun tracked(vararg keys: AppKey, cooldownMin: Int = 10): Map<AppKey, TrackedApp> =
        keys.associateWith { TrackedApp(it, TrackedApp.DEFAULT_PRESETS, cooldownMin) }

    val NO_EXEMPTIONS = Exemptions()
    val DIALER_EXEMPT = Exemptions(defaults = setOf(DIALER.pkg))

    fun min(m: Int): Long = m * MINUTE_MS
    fun sec(s: Int): Long = s * SECOND_MS

    /** An active session for [key] that started at [start] and lasts [minutes]. */
    fun active(key: AppKey = INSTAGRAM, start: Long = T0, minutes: Int = 5): SessionRecord =
        SessionRecord(key = key, startedAt = start, declaredMs = min(minutes), deadlineAt = start + min(minutes))

    /** A session for [key] that ended at [endedAt] for [reason]. */
    fun ended(key: AppKey = INSTAGRAM, endedAt: Long, reason: EndReason, minutes: Int = 5): SessionRecord {
        val start = endedAt - min(minutes)
        return SessionRecord(
            key = key, startedAt = start, declaredMs = min(minutes), deadlineAt = start + min(minutes),
            endedAt = endedAt, endReason = reason,
        )
    }

    /** A list that throws as soon as anyone looks at it: simulates corrupt input. */
    fun <T> explodingList(): List<T> = object : AbstractList<T>() {
        override val size: Int get() = throw IllegalStateException("corrupt list")
        override fun get(index: Int): T = throw IllegalStateException("corrupt list")
        override fun iterator(): Iterator<T> = throw IllegalStateException("corrupt list")
    }

    /** A map that throws on every lookup: simulates a corrupt tracked-app cache. */
    fun <K, V> explodingMap(): Map<K, V> = object : AbstractMap<K, V>() {
        override val entries: Set<Map.Entry<K, V>> get() = throw IllegalStateException("corrupt map")
        override fun get(key: K): V? = throw IllegalStateException("corrupt map")
        override fun containsKey(key: K): Boolean = throw IllegalStateException("corrupt map")
    }
}
