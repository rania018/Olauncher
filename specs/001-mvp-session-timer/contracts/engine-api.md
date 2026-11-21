# Contract: `:engine` public API and upstream hook points

These signatures are the boundary between the pure engine, our Android adapters and upstream.
Changing them means updating this file, and the tasks that depend on them.

## Engine (package `app.calmlauncher.engine`)

```kotlin
data class AppKey(val pkg: String, val userSerial: Long)

data class Config(
    val warningLeadSec: Int = 60, val extensionWaitSec: Int = 15,
    val extensionOptionsMin: List<Int> = listOf(2, 5), val maxExtensions: Int = 2,
    val overrideWaitSec: Int = 30, val overrideGrantMin: Int = 5,
    val graceSec: Int = 120, val customMaxMin: Int = 60, val cardTimeoutSec: Int = 120,
) { fun sanitized(): Config /* clamps, e.g. overrideWaitSec >= 10 */ }

sealed interface Decision {
    data class Allow(val reason: AllowReason) : Decision          // EXEMPT, NOT_TRACKED, RUNNING, FAIL_OPEN
    data class AskDuration(val presetsMin: List<Int>, val customMaxMin: Int) : Decision
    class Blocked(val kind: BlockKind, val until: Long) : Decision { val overridable = true } // MVP kind: COOLDOWN
}

object RulesEngine {
    /** Pure. Never throws: any internal error returns Allow(FAIL_OPEN). */
    fun decide(app: AppKey, now: Long, tracked: Map<AppKey, TrackedApp>,
               exemptions: Exemptions, history: List<SessionRecord>, cfg: Config): Decision
}

object SessionMachine {
    /** Pure. Never throws: on error returns Transition(state, listOf(Effect.Log(ERROR))). */
    fun reduce(state: SessionState, event: SessionEvent, now: Long, cfg: Config): Transition
}
```

Required invariants (each has a test):
1. `decide` returns `Allow(EXEMPT)` for every exempt package, whatever the other inputs.
2. `decide` never throws; if `history` or `tracked` is malformed, it returns `Allow(FAIL_OPEN)`.
3. `Blocked.overridable` is always `true`.
4. `reduce` at most one active session; a `DurationChosen` while another session is active ends the other one as `LEFT_EARLY`.
5. Extensions never exceed `maxExtensions`; overrides are unlimited but each logs `OVERRIDE`.
6. A deadline in the past at `Reconcile` produces `EndSession(EXPIRED, at = deadline)` and **no** UI effect.

## Adapter entry points (package `app.calmlauncher`)

```kotlin
object LaunchGate {
    /** Called from upstream MainViewModel.selectedApp(). true = we handled it (prompt shown), false = upstream launches. */
    fun intercept(context: Context, app: app.olauncher.data.AppModel): Boolean
}

object CalmHooks {
    /** Called from upstream MainActivity.onResume(). Reconciles sessions, removes stray overlays, starts grace check, refreshes health line. */
    fun onLauncherResumed(activity: Activity)
    /** Called from upstream MainActivity.onPause() — cancels the grace check. */
    fun onLauncherPaused(activity: Activity)
    /** Text for the home health line, or null when everything is fine. */
    fun healthLine(context: Context): String?
}
```

Both entry points wrap their body in `failOpen { ... }`: on any `Throwable`, `intercept` returns
`false`, and the hooks do nothing except log.

## Upstream call sites (exact)

| Upstream file | Location | Inserted code |
|---|---|---|
| `MainViewModel.kt` | `selectedApp()`, branches `FLAG_LAUNCH_APP` and `FLAG_HIDDEN_APPS` | `if (LaunchGate.intercept(appContext, appModel)) return` before the launch call |
| `MainActivity.kt` | `onResume()` / `onPause()` | `CalmHooks.onLauncherResumed(this)` / `CalmHooks.onLauncherPaused(this)` |
| `ui/HomeFragment.kt` | `onResume` (existing) | `binding.calmHealth.text = CalmHooks.healthLine(requireContext())` and visibility |
