package app.calmlauncher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.calmlauncher.engine.EndReason

// All DAO functions are blocking. They run only on CalmGraph's single database thread, never on
// the main thread, which also keeps every write in the order it was made.

@Dao
interface TrackedAppDao {
    @Query("SELECT * FROM tracked_app")
    fun all(): List<TrackedAppEntity>

    @Upsert
    fun upsert(app: TrackedAppEntity)

    @Query("DELETE FROM tracked_app WHERE pkg = :pkg AND user_serial = :userSerial")
    fun delete(pkg: String, userSerial: Long)
}

@Dao
interface ExemptAppDao {
    @Query("SELECT * FROM exempt_app")
    fun all(): List<ExemptAppEntity>

    @Upsert
    fun upsert(app: ExemptAppEntity)

    /** Adds rows that are not there yet; never changes an existing row (a user choice wins). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(apps: List<ExemptAppEntity>)

    @Query("DELETE FROM exempt_app WHERE pkg = :pkg")
    fun delete(pkg: String)
}

@Dao
abstract class SessionDao {
    @Query("SELECT * FROM session WHERE ended_at IS NULL ORDER BY started_at DESC")
    abstract fun active(): List<SessionEntity>

    /** Active sessions plus those that ended at or after [since] (rules history, unplanned opens). */
    @Query("SELECT * FROM session WHERE ended_at IS NULL OR ended_at >= :since ORDER BY started_at")
    abstract fun recent(since: Long): List<SessionEntity>

    @Query("SELECT * FROM session ORDER BY started_at")
    abstract fun all(): List<SessionEntity>

    @Insert
    protected abstract fun insert(session: SessionEntity): Long

    @Query("UPDATE session SET ended_at = :at, end_reason = 'INTERRUPTED' WHERE ended_at IS NULL")
    protected abstract fun interruptAllActive(at: Long): Int

    @Query(
        "UPDATE session SET deadline_at = :deadlineAt, extensions = :extensions, overrides = :overrides, " +
            "left_at = :leftAt WHERE pkg = :pkg AND user_serial = :userSerial AND started_at = :startedAt " +
            "AND ended_at IS NULL"
    )
    protected abstract fun updateActive(
        pkg: String, userSerial: Long, startedAt: Long,
        deadlineAt: Long, extensions: Int, overrides: Int, leftAt: Long?,
    ): Int

    @Query(
        "UPDATE session SET ended_at = :endedAt, end_reason = :endReason, deadline_at = :deadlineAt, " +
            "extensions = :extensions, overrides = :overrides, left_at = :leftAt " +
            "WHERE pkg = :pkg AND user_serial = :userSerial AND started_at = :startedAt AND ended_at IS NULL"
    )
    protected abstract fun endActive(
        pkg: String, userSerial: Long, startedAt: Long,
        endedAt: Long, endReason: EndReason,
        deadlineAt: Long, extensions: Int, overrides: Int, leftAt: Long?,
    ): Int

    /**
     * Starts a session. Runs in one transaction that first closes any other active row as
     * INTERRUPTED, so the table never holds more than one row with `ended_at IS NULL`.
     */
    @Transaction
    open fun startSession(session: SessionEntity): Long {
        interruptAllActive(session.startedAt)
        return insert(session.copy(id = 0, endedAt = null, endReason = null))
    }

    /** Updates the active row for [session], or starts it if it is not in the table yet. */
    @Transaction
    open fun saveActive(session: SessionEntity) {
        val updated = updateActive(
            session.pkg, session.userSerial, session.startedAt,
            session.deadlineAt, session.extensions, session.overrides, session.leftAt,
        )
        if (updated == 0) startSession(session)
    }

    /** Closes the active row for [session]; if it was never stored, stores it already closed. */
    @Transaction
    open fun end(session: SessionEntity) {
        val reason = session.endReason ?: EndReason.INTERRUPTED
        val endedAt = session.endedAt ?: session.deadlineAt
        val updated = endActive(
            session.pkg, session.userSerial, session.startedAt, endedAt, reason,
            session.deadlineAt, session.extensions, session.overrides, session.leftAt,
        )
        if (updated == 0) insert(session.copy(id = 0, endedAt = endedAt, endReason = reason))
    }
}

@Dao
interface EventLogDao {
    @Insert
    fun insert(event: EventLogEntity)

    @Query("SELECT * FROM event_log WHERE at >= :since ORDER BY at")
    fun since(since: Long): List<EventLogEntity>
}
