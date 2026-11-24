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
    data class Blocked(val kind: BlockKind, val until: Long) : Decision { val overridable get() = true } // MVP kind: COOLDOWN
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

### Launch source, foreground changes and the grace result (added 2026-11-21, research R15–R16)

```kotlin
enum class LaunchSource { LAUNCHER, OUTSIDE }

sealed interface SessionEvent {
    data class LaunchRequested(val key: AppKey, val decision: Decision, val cooldownMin: Int,
                               val source: LaunchSource = LaunchSource.LAUNCHER) : SessionEvent
    /**
     * [pkg] came to the front: seen by the accessibility service, or sent by `LaunchGate` when the
     * launcher reopens the session's app (decision `Allow(RUNNING)`). Never our own package.
     */
    data class ForegroundChanged(val pkg: String) : SessionEvent
    /** End of the grace: true = the session's app is in front, false = another app or the launcher, null = unknown. */
    data class GraceElapsed(val trackedInFront: Boolean?) : SessionEvent
    // other events unchanged
}
```

Required invariants (each has a test):
1. `decide` returns `Allow(EXEMPT)` for every exempt package, whatever the other inputs.
2. `decide` never throws; if `history` or `tracked` is malformed, it returns `Allow(FAIL_OPEN)`.
3. `Blocked.overridable` is always `true`.
4. `reduce` at most one active session; a `DurationChosen` while another session is active ends the other one as `LEFT_EARLY`.
5. Extensions never exceed `maxExtensions`; overrides are unlimited but each logs `OVERRIDE`.
6. A deadline in the past at `Reconcile` produces `EndSession(EXPIRED, at = deadline)` and **no** UI effect.
7. A launch with `source = OUTSIDE` never produces `LaunchApp`, whatever the decision or the later `DurationChosen` / `OverrideCompleted`.
8. `LauncherPaused` never clears a pending grace; only the session's app coming back (`ForegroundChanged` or `LaunchRequested` for its key) does.
9. `GraceElapsed` ends a session only when `trackedInFront == false`.
10. `LaunchRequested(key, OUTSIDE)` while `Prompting(key)` leaves the state unchanged and has no effects.

## Adapter entry points (package `app.calmlauncher`)

```kotlin
object LaunchGate {
    /**
     * Called from upstream MainViewModel.selectedApp(). true = we handled it (prompt shown), false = upstream launches.
     * On `Allow(RUNNING)` it also tells the engine `ForegroundChanged(session pkg)`, which ends a pending grace (R16).
     */
    fun intercept(context: Context, app: app.olauncher.data.AppModel): Boolean
}

object CalmHooks {
    /** Called from upstream MainActivity.onResume(). Reconciles sessions, removes stray overlays, starts grace check, refreshes health line. */
    fun onLauncherResumed(activity: Activity)
    /** Called from upstream MainActivity.onPause(). Only records that the home screen left the front; a pending grace stays (R16, invariant 8). */
    fun onLauncherPaused(activity: Activity)
    /** Text for the home health line, or null when everything is fine. */
    fun healthLine(context: Context): String?
    /** Sets the health line view's text, visibility and tap target (opens the matching onboarding page). */
    fun bindHealthLine(view: TextView)
}
```

```kotlin
/** Our accessibility service (research R13). Only window-state events; never reads text or nodes. */
class CalmAccessibilityService : android.accessibilityservice.AccessibilityService {
    // onAccessibilityEvent: ForegroundFilter.accept(...) → repository:
    //   ForegroundChanged(pkg) for any other app; LaunchRequested(key, decision, OUTSIDE) when pkg is tracked
    // onServiceConnected / onUnbind: marks the toggle on/off for the health line and the pill rule
}
```

All entry points wrap their body in `failOpen { ... }`: on any `Throwable`, `intercept` returns
`false`, and the hooks do nothing except log.

## Upstream call sites (exact)

| Upstream file | Location | Inserted code |
|---|---|---|
| `MainViewModel.kt` | `selectedApp()`, branches `FLAG_LAUNCH_APP` and `FLAG_HIDDEN_APPS` | `if (LaunchGate.intercept(appContext, appModel)) return` before the launch call |
| `MainActivity.kt` | `onResume()` / `onPause()` | `CalmHooks.onLauncherResumed(this)` / `CalmHooks.onLauncherPaused(this)` |
| `MainActivity.kt` | `checkForMessages()` | `return` as the first line: no upstream wallpaper, rating, sharing or Pro dialogs (FR-002) |
| `ui/HomeFragment.kt` | `onResume` (existing) | `CalmHooks.bindHealthLine(binding.calmHealth)` |
| `ui/SettingsFragment.kt` | `onClick`, `initClickListeners` | `R.id.calmScreenTime -> ScreenTimeSettingsActivity.open(...)` and its click listener |

## Additional engine helpers (added during implementation)

```kotlin
RulesEngine.cooldownUntil(app, history, cooldownMin, now): Long?        // decide step 7 and state rebuild
fun SessionState.session(): SessionState                                // the state behind an open prompt
val SessionState.activeRecord: SessionRecord?                           // running or expired session, if any
UnplannedOpens.find(resumes, trackedSince, sessions, now): List<Resume> // T065, pure; opens before tracking began do not count
ExemptRules.whenTracking(kind: ExemptionKind?): TrackRequest           // T103; ExemptionKind = ALWAYS, DEFAULT, USER
ExemptRules.trackedToDrop(tracked, exemptPkgs): List<AppKey>           // T103
TrackedApp.MAX_PRESETS = 6; TrackedApp.of keeps the 6 shortest lengths  // T103
GraceCheck.trackedInFront(sessionPkg, accessibilityFront, launcherResumed, usageFront): Boolean?
                                                                        // T094, research R16: the value for GraceElapsed
CallRule.blocks(call, locked, screenOff, trackedInFront): Boolean       // T105, research R7: Call = NONE, RINGING, ACTIVE

/** Pure (research R14, R15; T086). The accessibility service passes only strings, a flag and the time. */
class ForegroundFilter(ownPkg: String, ignoredPkgs: Set<String>, ignoredClasses: Set<String>) {
    /** The package newly in front, OWN for our own app, or IGNORE. */
    fun accept(pkg: String?, className: String?, isActivity: Boolean, now: Long): Result
    /** After an OUTSIDE prompt is cancelled: ignore outside opens of [pkg] for 3 s. */
    fun noteCancelled(pkg: String, now: Long)
}
```
