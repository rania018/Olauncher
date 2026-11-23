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
- **Updated (2026-11-23)**: the query now starts 10 minutes before the session start (`min(now, startedAt) - 10 min`) instead of 10 minutes before the deadline. Staying on one screen of the app logs no new `ACTIVITY_RESUMED`, so a session longer than 10 minutes (the 15-minute preset, an extension, or a deadline deferred by a long call) could find no event at all and end with the fallback notification instead of the card. Found by reading the code; not yet seen on the phone.

## R7. Calls and keyguard
- **Decision**: `AudioManager.mode` in {`MODE_IN_CALL`, `MODE_IN_COMMUNICATION`, `MODE_RINGTONE`} means the expiry UI is deferred. `KeyguardManager.isKeyguardLocked` means deferral. Re-checks happen on `AudioManager.OnModeChangedListener` (API 31+) or a 5-s re-check loop **only while deferred** (API < 31), and on `ACTION_USER_PRESENT`.
- **Rationale**: No `READ_PHONE_STATE` needed; covers VoIP (WhatsApp/Meet).
- **Alternative**: `TelephonyCallback` needs the phone-state permission and misses VoIP.
- **Implementation note (2026-11-21)**: the guard also watches while the time's-up card is up, so an incoming call hides the card (an app overlay would otherwise sit above the in-call screen). Screen off counts as "locked". When a deferral clears, the probe waits 2 s so the call screen is no longer the last resumed app in usage history.
- **Updated (2026-11-23, device test with a WhatsApp call)**: deferring for the whole call left a tracked app unlimited while a call ran in the background. The rule is now the pure `CallRule.blocks(call, locked, screenOff, trackedInFront)` in `:engine`: the lock screen, a dark screen and `MODE_RINGTONE` always block; `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` block unless the accessibility service (R13) last saw the session's app in front. `CallGuard.isBlocked(context, pkg)` feeds it, and the service calls `CallGuard.onForegroundChanged` and `TimeLeftOverlay.onForegroundChanged` whenever the app in front changes, so the pill and a waiting card follow the user between the call screen and the tracked app with no polling. With the toggle off `trackedInFront` is always false, which is the old behaviour (fail open). The card still passes the usage-history probe (R6) before it is drawn, so during a call both sources must agree. The card skips its audio-focus request while a call rings or runs; the warning keeps the strict rule (any call skips it), because a heads-up notification would make a sound in the call.

## R8. Default exemptions
- **Decision**: Resolve them on the device, and re-resolve on package changes:
  - `RoleManager` `ROLE_DIALER` and `ROLE_SMS` (API 29+); `resolveActivity(ACTION_DIAL)` and `ACTION_SENDTO sms:` below that
  - `ACTION_VIEW geo:0,0`
  - `MediaStore.ACTION_IMAGE_CAPTURE`
  - `AlarmClock.ACTION_SHOW_ALARMS`
  - the system Settings package

  Onboarding then asks for messaging, banking/payments, authenticator and transport/ride apps. It suggests matches by a known-package list (for example `com.whatsapp`, `org.telegram.messenger`, `com.google.android.apps.authenticator2`), and the user confirms them.
- **Rationale**: Works whichever apps the user has chosen as default; no hard-coded vendor assumptions.
- **Implementation note (2026-11-21)**: `RoleManager` only tells an app whether it holds a role itself (`getRoleHolders` is a system API), so the current dialer and SMS apps are read with the public `TelecomManager.getDefaultDialerPackage()` and `Telephony.Sms.getDefaultSmsPackage()`, with the intent fallbacks above. When an intent has no default handler, every candidate is exempted (the safe direction). Dialer, SMS and Settings are stored with category `SYSTEM` and cannot be removed, so the default list never becomes empty (Constitution II).

## R9. Leaving early (wall clock + 2-min grace)
- **Decision**: The launcher `onResume` hook records `leftAt` for the active session and schedules a grace check, as an in-service callback at `leftAt + 2 min`. When it fires:
  - if the launcher is still the last resumed activity (our `MainActivity` resume time > the session app's last resume per UsageStats), the session ends `LEFT_EARLY`
  - if usage access is missing, the session ends `LEFT_EARLY` when the launcher has been resumed continuously (no `onPause`) for 2 min
  - `onPause` of the launcher before the grace ends cancels the check
- **Rationale**: Uses existing lifecycle events; no polling.
- **Superseded in part by R16 (2026-11-21)**: opening another app from the launcher no longer cancels the check, and leaving for any other app starts it when the accessibility toggle is on.

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

## R13. Knowing which app is in front: our own accessibility service
- **Decision**: Add `app.calmlauncher.access.CalmAccessibilityService`, a separate accessibility
  service in the main process with its own config `calm_accessibility_config.xml`:
  `accessibilityEventTypes="typeWindowStateChanged"`, `canRetrieveWindowContent="false"`, no
  `packageNames` filter (it must see every app), `notificationTimeout="0"`, label "Calm Launcher app
  limits". It reads only `event.packageName` and `event.className`, never `event.text` or any node.
  It hands each event to `ForegroundFilter` (R14) and dispatches the result to the repository on the
  main thread. Its state ("connected or not") is read with `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
  for onboarding, the health line and the settings status.
- **Rationale**: Window-state events are the event source the constitution names (Principle V), so
  there is no polling. With content retrieval off, the system does not give the service the screen
  content at all, which enforces Principle I instead of only promising it. Being bound by the
  system also keeps the main process alive, which helps the session timer, and exempts the service
  from Android 10+ background-activity-start limits when it starts the prompt (R15).
- **Alternatives**:
  - Extend upstream's `MyAccessibilityService` (double-tap to lock): it runs in a separate process
    (`:serviceProcess`, so it would need IPC to reach the engine), retrieves window content, and is
    limited to our own package. Widening it is an upstream edit (Principle VII) and would tie the
    limits to the lock feature. Rejected; the cost is a second entry in Android's accessibility list.
  - Poll `UsageStatsManager` every few seconds during a session: violates Principle V and still
    misses opens while no session runs.
- **Flags**:
  - 🅢 Google Play restricts AccessibilityService for non-accessibility purposes; acceptable because
    distribution is personal sideloading (constitution Platform constraints).
  - ⚠️ Android 13+ "restricted settings" can grey out the toggle for sideloaded apps. Installs through
    `adb install` are expected to be unaffected; fallback: App info → ⋮ → "Allow restricted settings".
    The onboarding page explains the fallback. **Verified (T084, 2026-11-21, Samsung, One UI on
    Android 15): the toggle turned on for the adb-installed build with no restricted-settings prompt.**
  - ⚠️ One UI may switch the service off after a force stop or crash; the health line shows it.
  - ⚠️ Window events carry no user profile. A package matches the tracked entry of the main profile
    first, then any other profile's entry (work-profile limitation, documented in the spec edge cases).

## R14. Which window events count as an app switch
- **Decision**: `ForegroundFilter` (pure Kotlin in `:engine`, Constitution VI; the service passes only strings, a flag and the time)
  turns raw events into "app X is now in front", or nothing:
  - Ignore events whose window is not an activity of its package. The service checks this with one
    cached `PackageManager.getActivityInfo(ComponentName(pkg, className))` lookup and passes the
    result in. This drops dialogs, pop-ups, toasts and keyboards.
  - Ignore `com.android.systemui` (notification shade, volume, Recents overlays on some devices) and
    the enabled input methods.
  - Our own package counts as "Calm Launcher in front": it is remembered, so leaving and coming back to
    the same app is seen as a change, but it is not dispatched as a foreground change (the launcher's
    own resume/pause hooks and our screens handle themselves).
  - Repeated events for the package already in front are dropped (in-app navigation).
- **Rationale**: Activity checks are the usual way to tell real app switches from transient windows
  without reading content. Getting this wrong would start the grace while the user is still in the app.
- **Device findings (T084, 2026-11-21, Samsung, One UI on Android 15)**:
  - Each activity produces two events about 50–100 ms apart; the same-package rule drops the second.
  - Dropped by the activity check (not activities): notification shade and gesture overlays
    (`com.android.systemui` / `FrameLayout`), the Samsung keyboard (`com.samsung.android.honeyboard` /
    `SoftInputWindow`), Samsung launcher overlays (`com.sec.android.app.launcher` / `FrameLayout`), a
    Digital Wellbeing pop-up (`com.samsung.android.forest` / `LinearLayout`), app splash windows
    (`com.facebook.orca` / `FrameLayout`, `ImageView`), Play Store dialogs (obfuscated class names).
  - **The Samsung recent-apps screen is an activity**: `com.sec.android.app.launcher` /
    `com.android.quickstep.RecentsActivity`. It is ignored by class name, so passing through Recents
    is not "leaving" and does not make the pill flicker.
  - In-app navigation stays in one package (Instagram `InstagramMainActivity` ↔ `ModalActivity`) and is
    dropped. A link from Messenger into Facebook arrives as `com.facebook.katana` /
    `StoryDeepLinkLoadingActivity`; a notification tap arrived as `com.whatsapp` / `VoipActivityV2`.
  - Our launcher (`app.olauncher.MainActivity`) and prompt (`PromptActivity`) arrive under our package.
  - An app's activity event arrives within about 0.1–0.5 s of its first window, so the 1 s target
    (SC-008) looks reachable.
- **Final ignore rules**: windows that are not an activity of their package; packages
  `com.android.systemui` and the enabled input methods; class `com.android.quickstep.RecentsActivity`
  (any package).
- **Safety net**: at the end of the grace the usage history is consulted too (R16), so a missed
  "back in the app" event cannot end a session the user is still in.
- **Alternatives**: `getWindows()` (needs content retrieval); `TYPE_WINDOWS_CHANGED` (noisier, no
  package for many windows).

## R15. Prompting for opens from outside the launcher
- **Decision**: When `ForegroundFilter` reports a tracked package in front, the repository dispatches
  `LaunchRequested(key, decision, source = OUTSIDE)` with the same `RulesEngine.decide` result as a
  launcher open. The engine then:
  - `Allow`: no `LaunchApp` (the app is already in front); clears a pending grace if it is the
    session's app.
  - `AskDuration` / `Blocked`: `Prompting(source = OUTSIDE)` and `ShowPrompt` / `ShowBlocked`. The repository starts `PromptActivity` over the app from the application context; that is allowed
    because the app's accessibility service is bound by the system.
  - On `DurationChosen` or a completed override from an OUTSIDE prompt: start the session but emit
    no `LaunchApp`. The prompt finishes and the app underneath comes back.
  - `Cancelled` from an OUTSIDE prompt: `GoHome`, as for every prompt (FR-031).
  - A repeated `LaunchRequested` for the key already being prompted is ignored, so in-app window
    events cannot restart the prompt or its override countdown.

  While the toggle is on, the launcher's unplanned-open query is skipped: there are no uncaught
  opens, and a cancelled one is already logged as `PROMPT_CANCELLED` with `src=outside`.
- **Rationale**: One decision path for every open keeps the rules identical (exempt first,
  cooldown, running session). Opens through our launcher still pass `LaunchGate` first; by the time
  their window appears, the session is already running, so the outside path answers `Allow`.
- **Flags**: ⚠️ background activity starts (Android 10+): allowed for an app with a system-bound
  accessibility service; device check. The app may be visible for a moment before the prompt covers
  it (SC-008 allows up to 1 s).
- **Loop guard**: right after an OUTSIDE prompt is cancelled, outside opens of the same key are
  ignored for 3 s, so the app's last window events during the trip home do not re-prompt (pure rule
  in `ForegroundFilter`, tested).

## R16. Leaving for another app (FR-015, updates R9)
- **Decision**:
  - The grace (`leftAt`, `ScheduleAt(GRACE)`) starts on `LauncherResumed` (as before) and on
    `ForegroundChanged(pkg)` when `pkg` is not the session's app.
  - It is cleared by `ForegroundChanged(session pkg)`, or by a launcher or outside `LaunchRequested`
    for the session's app (FR-008).
  - `LauncherPaused` no longer clears it, because opening another app from the launcher is not
    "coming back" (the bug the owner found).
  - `GraceElapsed(trackedInFront: Boolean?)` replaces `GraceElapsed(stillOnLauncher)`. The adapter
    computes it:
    - `true` if the accessibility service last saw the session's app, or the usage history's last
      resumed app is the session's app;
    - `false` if the launcher is still resumed, or a source names another app and none names the
      session's app;
    - `null` if neither source knows.
  - The engine ends the session as `LEFT_EARLY` only on `false`; `true` and `null` keep it running
    (nothing is blocked either way).
- **Rationale**: Covers going home, going home and opening another app, and following a link, with
  one rule. Without the toggle, only the home screen starts the grace, and the end-of-grace check
  uses the usage history (spec FR-015).
- **Alternatives**: pausing the clock while away (rejected by the owner in clarification).

## R17. Showing the pill or bar only in the tracked app (FR-029)
- **Decision**: `TimeLeftOverlay` shows the reminder when a session counts down **and** either the
  accessibility toggle is off or no grace is pending (`graceCheckAt == null`). A pending grace means
  "away from the app", so with the toggle on the reminder hides on the home screen and in other
  apps, and reappears when the app is back in front. The grow-and-settle pulse at the warning is
  removed (Constitution IV, 1.1.0): the switch to amber is the cue.
- **Rationale**: No extra state: the engine's grace already says whether the user is away.
