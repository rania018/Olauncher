package app.calmlauncher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Calm Launcher's own database file, `calm.db`. Upstream's SharedPreferences are untouched.
 * The schema is exported to `app/schemas/` so later versions can add tables with reviewed migrations.
 * The file is excluded from cloud backup (res/xml/data_extraction_rules.xml, backup_rules.xml).
 */
@Database(
    entities = [TrackedAppEntity::class, ExemptAppEntity::class, SessionEntity::class, EventLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CalmDatabase : RoomDatabase() {
    abstract fun trackedApps(): TrackedAppDao
    abstract fun exemptApps(): ExemptAppDao
    abstract fun sessions(): SessionDao
    abstract fun events(): EventLogDao

    companion object {
        const val FILE_NAME = "calm.db"

        fun open(context: Context): CalmDatabase =
            Room.databaseBuilder(context.applicationContext, CalmDatabase::class.java, FILE_NAME).build()
    }
}
