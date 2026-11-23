package app.calmlauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.calmlauncher.engine.Config
import app.calmlauncher.engine.TrackedApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// DataStore is Jetpack's replacement for SharedPreferences: a small key-value file that is read
// as a Kotlin Flow and written atomically on a background thread, so a crash mid-write cannot
// corrupt it. Our settings live in their own file, `calm_settings`, so upstream's Prefs stay
// untouched. The file is excluded from cloud backup.
private val Context.calmDataStore: DataStore<Preferences> by preferencesDataStore(name = SettingsStore.FILE_NAME)

/** Calm Launcher settings (data-model.md "Settings"). Every value read back is clamped to its range. */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.calmDataStore

    /** Everything we keep in DataStore, already sanitized. */
    data class Snapshot(
        val config: Config,
        val defaultPresets: List<Int>,
        val defaultCooldownMin: Int,
        val onboardingVersion: Int,
        val healthNotice: HealthNotice?,
        val removedDefaults: Set<String>,
        /** "PILL", "BAR" or "OFF"; read with TimeLeft.Display.parse (spec FR-029). */
        val timeLeftDisplay: String? = null,
    )

    /** A one-off notice for the home health line, such as "the timer was interrupted". */
    data class HealthNotice(val code: String, val at: Long)

    suspend fun read(): Snapshot = toSnapshot(store.data.first())

    /** Blocking read, for background threads only. */
    fun readBlocking(): Snapshot = runBlocking { read() }

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    fun editBlocking(block: (MutablePreferences) -> Unit) = runBlocking { edit(block) }

    /** When the launcher was last in front (for counting unplanned opens). Background threads only. */
    fun lastLauncherResumeBlocking(): Long? = runBlocking { store.data.first()[Keys.LAST_LAUNCHER_RESUME] }

    private fun toSnapshot(p: Preferences): Snapshot {
        val defaults = Config()
        val config = Config(
            warningLeadSec = p[Keys.WARNING_LEAD_SEC] ?: defaults.warningLeadSec,
            extensionWaitSec = p[Keys.EXTENSION_WAIT_SEC] ?: defaults.extensionWaitSec,
            maxExtensions = p[Keys.MAX_EXTENSIONS] ?: defaults.maxExtensions,
            overrideWaitSec = p[Keys.OVERRIDE_WAIT_SEC] ?: defaults.overrideWaitSec,
            graceSec = p[Keys.GRACE_SEC] ?: defaults.graceSec,
        ).sanitized()
        val presets = Mappers.parsePresets(p[Keys.DEFAULT_PRESETS_CSV].orEmpty())
            .ifEmpty { TrackedApp.DEFAULT_PRESETS }
        val cooldown = (p[Keys.DEFAULT_COOLDOWN_MIN] ?: TrackedApp.DEFAULT_COOLDOWN_MIN)
            .coerceIn(0, TrackedApp.MAX_COOLDOWN_MIN)
        val notice = p[Keys.LAST_HEALTH_NOTICE]?.let { decodeNotice(it) }
        val removed = p[Keys.REMOVED_DEFAULT_EXEMPT].orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return Snapshot(config, presets, cooldown, p[Keys.ONBOARDING_VERSION] ?: 0, notice, removed, p[Keys.TIME_LEFT_DISPLAY])
    }

    object Keys {
        val WARNING_LEAD_SEC = intPreferencesKey("warning_lead_sec")
        val EXTENSION_WAIT_SEC = intPreferencesKey("extension_wait_sec")
        val MAX_EXTENSIONS = intPreferencesKey("max_extensions")
        val OVERRIDE_WAIT_SEC = intPreferencesKey("override_wait_sec")
        val GRACE_SEC = intPreferencesKey("grace_sec")
        val DEFAULT_PRESETS_CSV = stringPreferencesKey("default_presets_csv")
        val DEFAULT_COOLDOWN_MIN = intPreferencesKey("default_cooldown_min")
        val ONBOARDING_VERSION = intPreferencesKey("onboarding_version")
        val LAST_HEALTH_NOTICE = stringPreferencesKey("last_health_notice")
        val REMOVED_DEFAULT_EXEMPT = stringPreferencesKey("removed_default_exempt")
        val LAST_LAUNCHER_RESUME = longPreferencesKey("last_launcher_resume")
        val TIME_LEFT_DISPLAY = stringPreferencesKey("time_left_display")
    }

    companion object {
        const val FILE_NAME = "calm_settings"
        const val ONBOARDING_CURRENT = 1

        fun encodeNotice(notice: HealthNotice): String = "${notice.code}|${notice.at}"

        fun decodeNotice(text: String): HealthNotice? {
            val parts = text.split('|')
            if (parts.size != 2 || parts[0].isBlank()) return null
            val at = parts[1].toLongOrNull() ?: return null
            return HealthNotice(parts[0], at)
        }
    }
}
