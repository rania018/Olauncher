package app.calmlauncher.data

import app.calmlauncher.engine.AppKey
import app.calmlauncher.engine.ExemptionKind
import app.calmlauncher.engine.SessionRecord
import app.calmlauncher.engine.TrackedApp

/** Converts between Room rows and engine types. Pure Kotlin, so it is tested on the JVM. */
object Mappers {

    /** "2, 5,10" -> [2, 5, 10]. Anything that is not a whole number is skipped. */
    fun parsePresets(csv: String): List<Int> =
        csv.split(',').mapNotNull { it.trim().toIntOrNull() }

    fun presetsToCsv(presets: List<Int>): String = presets.joinToString(",")

    fun toTrackedApp(e: TrackedAppEntity): TrackedApp =
        TrackedApp.of(AppKey(e.pkg, e.userSerial), parsePresets(e.presetsCsv), e.cooldownMin)

    fun toEntity(app: TrackedApp, createdAt: Long): TrackedAppEntity =
        TrackedAppEntity(
            pkg = app.key.pkg,
            userSerial = app.key.userSerial,
            presetsCsv = presetsToCsv(app.presetsMin),
            cooldownMin = app.cooldownMin,
            createdAt = createdAt,
        )

    /**
     * How [row] is exempt, for the engine's track/exempt rule (data-model.md `exempt_app.category`
     * and `exempt_app.source`): SYSTEM is always exempt; otherwise the source decides. null = not exempt.
     */
    fun toExemptionKind(row: ExemptAppEntity?): ExemptionKind? = when {
        row == null -> null
        row.category == ExemptCategory.SYSTEM -> ExemptionKind.ALWAYS
        row.source == ExemptSource.DEFAULT -> ExemptionKind.DEFAULT
        else -> ExemptionKind.USER
    }

    fun toRecord(e: SessionEntity): SessionRecord =
        SessionRecord(
            id = e.id,
            key = AppKey(e.pkg, e.userSerial),
            startedAt = e.startedAt,
            declaredMs = e.declaredMs,
            deadlineAt = e.deadlineAt,
            endedAt = e.endedAt,
            endReason = e.endReason,
            extensions = e.extensions,
            overrides = e.overrides,
            leftAt = e.leftAt,
        )

    fun toEntity(r: SessionRecord): SessionEntity =
        SessionEntity(
            id = r.id,
            pkg = r.key.pkg,
            userSerial = r.key.userSerial,
            startedAt = r.startedAt,
            declaredMs = r.declaredMs,
            deadlineAt = r.deadlineAt,
            endedAt = r.endedAt,
            endReason = r.endReason,
            extensions = r.extensions,
            overrides = r.overrides,
            leftAt = r.leftAt,
        )
}
