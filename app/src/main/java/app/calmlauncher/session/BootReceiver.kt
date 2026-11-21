package app.calmlauncher.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.calmlauncher.CalmGraph
import app.calmlauncher.util.failOpen

/**
 * Restores a session after a reboot (spec US5, T057).
 *
 * A BroadcastReceiver is a small entry point that Android calls when a system event happens,
 * even if our app is not running. BOOT_COMPLETED is sent once after the phone has started and
 * been unlocked for the first time. Loading the repository reconciles the stored session: a
 * future deadline restarts the countdown service (allowed from BOOT_COMPLETED), a past one is
 * closed as expired with no card.
 *
 * 🅢 One UI may deliver BOOT_COMPLETED late; the launcher's own resume reconciles anyway.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) = failOpen("BootReceiver.onReceive") {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return@failOpen
        CalmGraph.init(context)
        // goAsync() keeps the process alive until the database has loaded and reconciled.
        val pending = goAsync()
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                pending.finish()
            }
        }
        CalmGraph.repository.whenLoaded { finish() }
        CalmGraph.main.postDelayed({ finish() }, MAX_WAIT_MS)
    }

    private companion object {
        const val MAX_WAIT_MS = 8_000L
    }
}
