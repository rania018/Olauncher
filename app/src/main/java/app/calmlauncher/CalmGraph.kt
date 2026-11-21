package app.calmlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.calmlauncher.data.CalmDatabase
import app.calmlauncher.data.SettingsStore
import app.calmlauncher.session.SessionRepository
import app.calmlauncher.util.CalmLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The few long-lived objects Calm Launcher needs, created on first use.
 *
 * We do not use a dependency-injection framework (Constitution VIII): there are only three
 * singletons, and a plain object with `by lazy` properties is easier to read and debug than
 * generated code. Every entry point calls [init] with any Context first.
 */
object CalmGraph {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context): CalmGraph {
        if (appContext == null) appContext = context.applicationContext
        return this
    }

    val context: Context
        get() = appContext ?: error("CalmGraph.init() was not called")

    val isInitialized: Boolean get() = appContext != null

    val database: CalmDatabase by lazy { CalmDatabase.open(context) }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val repository: SessionRepository by lazy { SessionRepository(context, database, settings) }

    /** The main (UI) thread. All session state changes happen here, one at a time. */
    val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** One background thread for every database read and write, so writes keep their order. */
    private val databaseThread: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "calm-db").apply { isDaemon = true } }
    }

    /** Runs [block] on the database thread. Errors are printed to Logcat and never thrown. */
    fun onDatabaseThread(block: (CalmDatabase) -> Unit) {
        if (!isInitialized) return
        databaseThread.execute {
            try {
                block(database)
            } catch (t: Throwable) {
                CalmLog.e("database task failed", t)
            }
        }
    }

    /** Runs [block] on the database thread and then [then] with its result on the main thread. */
    fun <T> loadThen(block: (CalmDatabase) -> T, then: (T) -> Unit) {
        onDatabaseThread { db ->
            val result = block(db)
            main.post { then(result) }
        }
    }
}
