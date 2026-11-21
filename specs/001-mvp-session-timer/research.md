# Research: MVP session timer launcher

Phase 0 output for [plan.md](plan.md). Background analysis lives in `docs/TECHNICAL_DESIGN.md`
(§ references below). This file records only the **decisions** the MVP depends on.

## R1. Where to intercept launcher launches
- **Decision**: One hook in `MainViewModel.selectedApp()` for `FLAG_LAUNCH_APP` / `FLAG_HIDDEN_APPS`, calling `LaunchGate.intercept()`. The prompt is a separate `PromptActivity`.
- **Rationale**: Every in-launcher entry point (home label, swipe, drawer, search auto-launch, Enter, clock/calendar/screen-time when set to an app) converges there (`docs/CODEBASE_NOTES.md`). `AppDrawerFragment` pops itself right after the call, so a fragment-owned dialog would be destroyed.
- **Alternatives**: hooking each caller (7 places, merge-hostile); dialog in `MainActivity` (couples us to upstream lifecycle).

## R2. Library versions compatible with upstream (Kotlin 2.1.20, AGP 8.9.1, Gradle 8.11.1, JDK 17)
- **Decision**:
  - Room **2.7.2** (`room-runtime`, `room-ktx`, `room-compiler` via KSP)
  - KSP **2.1.20-2.0.1**
  - DataStore Preferences **1.1.7**
  - kotlinx-coroutines from the existing transitive lifecycle dependencies, with no explicit pin
  - JUnit 4.13.2 (already in upstream)
- **Rationale**: The latest Room 2.8.x and DataStore 1.3.x may be compiled against newer Kotlin metadata than 2.1.20 can read. The 2.7 and 1.1 lines are stable and known to work with Kotlin 2.1. All versions were checked on Maven Central and Google Maven on 2026-11-21.
- **Alternatives**: bump Kotlin/AGP (touches upstream build files more and risks upstream merges); SharedPreferences + raw SQLite (more hand-written code).
- **Verification**: Task "dependency spike" builds `assembleDebug` with the new deps before any feature code.

## R3. Pure engine module
- **Decision**: `:engine` Gradle module with the `org.jetbrains.kotlin.jvm` plugin (already on the buildscript classpath via `kotlin-gradle-plugin`), JVM toolchain 17, dependencies: stdlib + JUnit only. Time is injected as `now: Long` arguments; there is no clock inside the engine.
- **Rationale**: The compiler then guarantees no `android.*` imports (Constitution VI), and tests run in seconds.
- **Alternatives**: a package inside `:app` (no enforcement; tests need the Android plugin's unit-test setup).

## R4. Timer execution
- **Decision**: Persisted deadline (Room) is the source of truth. `SessionService` is a foreground service with type `specialUse` and runs only during a session:
  - it shows a chronometer countdown notification
  - it posts in-process callbacks for the warning and the deadline

  A backup exact alarm is set when `SCHEDULE_EXACT_ALARM` is granted. `reconcile(now)` runs on launcher resume, boot, service restart, alarm and screen-on. (TECHNICAL_DESIGN §5)
- **Rationale**: Precise while the app is in use, no polling (the system animates the chronometer), and it degrades safely.
- **Flags**:
  - ⚠️ Android 14 requires an FGS type plus the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.
  - ⚠️ Android 12+ requires the service to start from the foreground, which `PromptActivity` is.
  - ⚠️ Exact alarms are denied by default on Android 14+, so the backup is optional.
  - 🅢 One UI sleeping apps can block service restarts and alarms; this is covered by reconciliation plus the health line.
- **Alternatives**: WorkManager (15-min minimum, not precise); a ViewModel coroutine (dies with the process).

## R5. Showing the time's-up card and going home
- **Decision**: A full-screen `TYPE_APPLICATION_OVERLAY` window (needs `SYSTEM_ALERT_WINDOW`) with focusable buttons. "Go home" removes the overlay and starts `Intent.ACTION_MAIN` + `CATEGORY_HOME`, which resolves to our `MainActivity`. If the overlay permission is missing, a high-priority notification is shown instead, and tapping it goes home. (TECHNICAL_DESIGN §6)
- **Rationale**: Overlays don't require starting an activity from the background (⚠️ Android 10+ limits). Holding the overlay permission with a visible overlay window is a documented exemption for the subsequent HOME start.
- **Back key**: the overlay root view is focusable and overrides `dispatchKeyEvent` for `KEYCODE_BACK`, which goes home. Home presses: the launcher `onResume` hook removes any overlay.
- **Verification**: The first device task confirms that starting HOME from the overlay works on the Samsung (One UI, Android 14+). If it doesn't, the fallback is the HOME start from the notification's `PendingIntent`, using the creator opt-in `ActivityOptions.setPendingIntentBackgroundActivityStartMode` on API 34+.

## R6. "Is the tracked app in front?" without accessibility
- **Decision**: A one-shot `UsageStatsManager.queryEvents(now - 10 min, now)` at the deadline. The last `ACTIVITY_RESUMED` (or `MOVE_TO_FOREGROUND` below API 29) gives the foreground package. If it equals the session package, the card is shown. If it differs or is unknown, the session ends quietly (fail open). The upstream permission check `appUsagePermissionGranted()` in `helper/Extensions.kt` is reused.
- **Rationale**: Event-driven (one query per expiry), no new permission beyond upstream's usage access.
- **Caveat**: Events can lag 1–5 s; the card latency target (≤5 s) allows a single retry after 2 s if the last event is older than the session start.

## R7. Calls and keyguard
- **Decision**: `AudioManager.mode` in {`MODE_IN_CALL`, `MODE_IN_COMMUNICATION`, `MODE_RINGTONE`} means the expiry UI is deferred. `KeyguardManager.isKeyguardLocked` means deferral. Re-checks happen on `AudioManager.OnModeChangedListener` (API 31+) or a 5-s re-check loop **only while deferred** (API < 31), and on `ACTION_USER_PRESENT`.
- **Rationale**: No `READ_PHONE_STATE` needed; covers VoIP (WhatsApp/Meet).
- **Alternative**: `TelephonyCallback` needs the phone-state permission and misses VoIP.

## R8. Default exemptions
- **Decision**: Resolve them on the device, and re-resolve on package changes:
  - `RoleManager` `ROLE_DIALER` and `ROLE_SMS` (API 29+); `resolveActivity(ACTION_DIAL)` and `ACTION_SENDTO sms:` below that
  - `ACTION_VIEW geo:0,0`
  - `MediaStore.ACTION_IMAGE_CAPTURE`
  - `AlarmClock.ACTION_SHOW_ALARMS`
  - the system Settings package

  Onboarding then asks for messaging, banking/payments, authenticator and transport/ride apps. It suggests matches by a known-package list (for example `com.whatsapp`, `org.telegram.messenger`, `com.google.android.apps.authenticator2`), and the user confirms them.
- **Rationale**: Works whichever apps the user has chosen as default; no hard-coded vendor assumptions.

## R9. Leaving early (wall clock + 2-min grace)
- **Decision**: The launcher `onResume` hook records `leftAt` for the active session and schedules a grace check, as an in-service callback at `leftAt + 2 min`. When it fires:
  - if the launcher is still the last resumed activity (our `MainActivity` resume time > the session app's last resume per UsageStats), the session ends `LEFT_EARLY`
  - if usage access is missing, the session ends `LEFT_EARLY` when the launcher has been resumed continuously (no `onPause`) for 2 min
  - `onPause` of the launcher before the grace ends cancels the check
- **Rationale**: Uses existing lifecycle events; no polling.

## R10. Cooldown (post-expiry only, MVP)
- **Decision**: The engine computes `cooldownUntil = expiredAt + cooldownMin` only for `EXPIRED` sessions, read from the session history (no extra table). `RulesEngine.decide` step 7 returns `Blocked(Cooldown, until, overridable = true)`.

## R11. Backup exclusion
- **Decision**: Add `<exclude domain="database" path="calm.db"/>` and the DataStore file to `data_extraction_rules.xml` and `backup_rules.xml`. These are upstream files, so add them to the upstream-files table.
- **Rationale**: FR-003 "excluded from cloud backup".

## R12. App identity
- **Decision**:
  - `applicationId "app.calmlauncher"`; debug suffix `.debug` stays
  - manifest `android:label="@string/calm_app_name"` in our own `res/values/calm_strings.xml`
  - `accessibility_service_config.xml` `packageNames` updated
  - `namespace` stays `app.olauncher`
- **Note**: Upstream's in-app strings that say "Olauncher" (dialogs) are left alone in the MVP to keep the diff small, apart from hiding promotional links.
