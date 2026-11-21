package app.calmlauncher.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.calmlauncher.engine.EndReason
import app.calmlauncher.engine.EventType

// Room is Android's SQLite library. Each table is described by an annotated Kotlin class (an
// "entity") and each query by an annotated function in a DAO. At build time the KSP compiler
// plugin generates the SQL code and checks every query against these tables, so a typo in a
// query is a compile error instead of a crash on the phone. Tables follow data-model.md.

/** Where an exemption comes from (`exempt_app.source`). */
enum class ExemptSource { DEFAULT, USER }

/**
 * Kind of exempt app (`exempt_app.category`). SYSTEM marks the defaults that can never be removed
 * (dialer, SMS, Settings), so the default list can never become empty (Constitution II).
 */
enum class ExemptCategory { MESSAGING, BANKING, AUTH, TRANSPORT, SYSTEM }

/** One tracked app per package and user profile. */
@Entity(tableName = "tracked_app", primaryKeys = ["pkg", "user_serial"])
data class TrackedAppEntity(
    @ColumnInfo(name = "pkg") val pkg: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "presets_csv") val presetsCsv: String,
    @ColumnInfo(name = "cooldown_min", defaultValue = "10") val cooldownMin: Int = 10,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** An app that is never prompted, timed or blocked, in any profile. */
@Entity(tableName = "exempt_app")
data class ExemptAppEntity(
    @PrimaryKey @ColumnInfo(name = "pkg") val pkg: String,
    @ColumnInfo(name = "source") val source: ExemptSource,
    @ColumnInfo(name = "category") val category: ExemptCategory?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A session that actually started. A cancelled prompt is an event, not a session. */
@Entity(tableName = "session", indices = [Index(value = ["pkg", "ended_at"])])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "pkg") val pkg: String,
    @ColumnInfo(name = "user_serial") val userSerial: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "declared_ms") val declaredMs: Long,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "end_reason") val endReason: EndReason? = null,
    @ColumnInfo(name = "extensions", defaultValue = "0") val extensions: Int = 0,
    @ColumnInfo(name = "overrides", defaultValue = "0") val overrides: Int = 0,
    @ColumnInfo(name = "left_at") val leftAt: Long? = null,
)

/**
 * Something notable that happened. [detail] only ever holds our own short text or an exception
 * class name, never text read from another app's screen (Constitution I); see `util/Log.kt`.
 */
@Entity(tableName = "event_log")
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "pkg") val pkg: String?,
    @ColumnInfo(name = "type") val type: EventType,
    @ColumnInfo(name = "detail") val detail: String?,
)

/**
 * Stores enums as their names. An unknown name (for example written by a future version) reads
 * back as a safe value instead of crashing.
 */
class Converters {
    @TypeConverter
    fun endReasonToText(value: EndReason?): String? = value?.name

    @TypeConverter
    fun textToEndReason(value: String?): EndReason? =
        value?.let { text -> EndReason.entries.firstOrNull { it.name == text } ?: EndReason.INTERRUPTED }

    @TypeConverter
    fun eventTypeToText(value: EventType): String = value.name

    @TypeConverter
    fun textToEventType(value: String): EventType =
        EventType.entries.firstOrNull { it.name == value } ?: EventType.ERROR

    @TypeConverter
    fun sourceToText(value: ExemptSource): String = value.name

    @TypeConverter
    fun textToSource(value: String): ExemptSource =
        ExemptSource.entries.firstOrNull { it.name == value } ?: ExemptSource.USER

    @TypeConverter
    fun categoryToText(value: ExemptCategory?): String? = value?.name

    @TypeConverter
    fun textToCategory(value: String?): ExemptCategory? =
        value?.let { text -> ExemptCategory.entries.firstOrNull { it.name == text } }
}
