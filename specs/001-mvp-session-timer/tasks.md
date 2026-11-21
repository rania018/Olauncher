---
description: "Task list for the MVP session timer launcher"
---

# Tasks: MVP session timer launcher

**Input**: Design documents from `/specs/001-mvp-session-timer/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Requested by the owner and required by Constitution VI. Engine tests are written **first** and must fail before the implementation task that follows them.

**Organization**: Tasks are grouped by user story (US1–US7 from spec.md). Each task is small (about ≤ 1–2 h), touches one main file, and ends with something you can check: a test passing, a build succeeding, or a visible behaviour on the phone.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: user story from spec.md
- Paths are repository-relative. `E/` = `engine/src/main/kotlin/app/calmlauncher/engine/`, `ET/` = `engine/src/test/kotlin/app/calmlauncher/engine/`, `A/` = `app/src/main/java/app/calmlauncher/`, `U/` = `app/src/main/java/app/olauncher/` (upstream, hooks only)
- Every task that introduces an Android concept for the first time (Service, foreground service, notification channel, overlay window, AlarmManager, BroadcastReceiver, Room, DataStore, UsageStatsManager, RoleManager) adds a 2–4 line comment explaining it (Constitution VIII)
- Every Android entry point is wrapped in `failOpen { }` (Constitution II)
- Done check for every task: `./gradlew :engine:test` (engine tasks) or `./gradlew test assembleDebug` (app tasks) passes

---

## Phase 1: Setup (shared infrastructure)

**Purpose**: Build changes and module skeleton. No behaviour change yet.

- [ ] T001 Add Room 2.7.2, DataStore Preferences 1.1.7 and KSP 2.1.20-2.0.1 versions/libraries to `gradle/libs.versions.toml`, and the KSP plugin classpath to `build.gradle` (research R2)
- [ ] T002 Create `:engine` module: `engine/build.gradle` (kotlin-jvm plugin, JVM toolchain 17, deps: stdlib + junit) and add `include ':engine'` to `settings.gradle` (research R3)
- [ ] T003 Wire `app/build.gradle`: `implementation project(':engine')`, Room runtime/ktx + `ksp` compiler, DataStore; apply KSP plugin. **Dependency spike:** `./gradlew assembleDebug test` must pass with no source changes (research R2 verification)
- [ ] T004 [P] Add `engine/src/test/kotlin/app/calmlauncher/engine/SmokeTest.kt` (one trivial test) and confirm `./gradlew :engine:test` runs in under 30 s

**Checkpoint**: Two-module build is green; upstream behaviour unchanged.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Engine types, persistence, safety helpers and the empty hook wiring that every story uses.

**⚠️ No user-story work starts before this phase is complete.**

### Engine core (test-first)

- [ ] T005 [P] Create engine model in `E/Model.kt`: `AppKey`, `TrackedApp` (validate `data-model.md` `TrackedApp.presetsMin` and `TrackedApp.cooldownMin`), `Exemptions`, `Config` with `sanitized()` (clamps `data-model.md` `Config.overrideWaitSec` and `Config.warningLeadSec`), `SessionRecord` (`data-model.md` `SessionRecord.endedAt` semantics)
- [ ] T006 [P] Create `E/Decision.kt`: `Decision`, `AllowReason`, `BlockKind`, with `Blocked.overridable` fixed to `true` (`data-model.md` `Decision.Blocked`, contracts/engine-api invariant 3)
- [ ] T007 [P] Create `E/FailOpen.kt`: `inline fun <T> failOpen(fallback: T, onError: (Throwable) -> Unit = {}, block: () -> T): T`
- [ ] T008 Write failing tests in `ET/RulesEngineTest.kt`, one test per row of the TECHNICAL_DESIGN §4.2 checks that apply to the MVP:
  - exempt → `Allow(EXEMPT)`
  - not tracked → `Allow(NOT_TRACKED)`
  - running → `Allow(RUNNING)`
  - otherwise `AskDuration(presets, 60)`
- [ ] T009 Implement `E/RulesEngine.kt` `decide()` with the fixed check order, wrapped in `failOpen(Allow(FAIL_OPEN))`, until T008 passes
- [ ] T010 [P] Write failing tests in `ET/InvariantsTest.kt` for contracts/engine-api invariants 1, 2 and 3 (exempt always wins across all inputs; malformed history never throws; every `Blocked` is overridable), plus the `data-model.md` "State transitions" row "exception in `reduce`": `SessionMachine.reduce` never throws, keeps the state unchanged and emits only `Log(ERROR)`

### Persistence and app plumbing

- [ ] T011 [P] Create Room entities in `A/data/Entities.kt` for `tracked_app` (composite primary key per `data-model.md` `tracked_app.pkg` + `tracked_app.user_serial`, i.e. one row per app per user profile), `exempt_app`, `session`, `event_log`, enforcing `data-model.md` `exempt_app.source`, `exempt_app.category`, `session.end_reason` and `event_log.type` as Kotlin enums with type converters. First-use comment explaining Room
- [ ] T012 Create DAOs in `A/data/Daos.kt`. `SessionDao.startSession()` runs in a transaction that guarantees at most one row with `ended_at IS NULL` (`data-model.md` `session.ended_at`). Create `A/data/CalmDatabase.kt` (`calm.db`, version 1, `exportSchema = true` into `app/schemas/`)
- [ ] T013 [P] Create `A/data/SettingsStore.kt` (Preferences DataStore `calm_settings`; keys per data-model "Settings") returning `Config.sanitized()`. First-use comment explaining DataStore
- [ ] T014 [P] Create `A/data/Mappers.kt` (entity ↔ engine model) plus JVM test `app/src/test/java/app/calmlauncher/data/MappersTest.kt` for the CSV presets round-trip and `userSerial` mapping
- [ ] T015 [P] Create `A/util/FailOpen.kt` (Android wrapper that logs to `event_log` type `ERROR` and Logcat tag `CalmLauncher`) and `A/util/Log.kt`. Every write goes through one `logEvent()` function that enforces `data-model.md` `event_log.detail`: it stores only the exception class and our own messages, never text read from other apps' screens
- [ ] T016 Create `A/CalmGraph.kt` (lazy singletons: database, settings, repository) with a comment on why no DI framework is used
- [ ] T017 Create `A/exempt/DefaultExemptions.kt`. It resolves dialer/SMS roles via `RoleManager` (API 29+) or `resolveActivity` fallbacks, plus `geo:`, image capture, alarms and the Settings package (research R8). It seeds `exempt_app` rows with `source = DEFAULT`. Add `A/exempt/KnownApps.kt` (suggestion list for messaging, banking, auth and transport by package name)
- [ ] T018 Add the upstream hook skeletons and run them as no-ops:
  - `A/gate/LaunchGate.kt` with `intercept()` returning `false`
  - `A/CalmHooks.kt` with `onLauncherResumed`, `onLauncherPaused` and `healthLine` as no-ops
  - hook calls in `U/MainViewModel.kt` `selectedApp()` (both launch branches), and in `U/MainActivity.kt` `onResume()` plus a new `onPause()` (contracts/engine-api "Upstream call sites")
  - the build is green and the app behaves exactly as upstream

### Early device check of the riskiest platform assumptions (spike, removed after)

- [ ] T019 Spike on the Samsung: a debug-only menu entry in `A/spike/SpikeActivity.kt` that (a) shows a `TYPE_APPLICATION_OVERLAY` card over another app and, on tap, starts the HOME intent; (b) starts a `specialUse` foreground service with a countdown chronometer notification, then survives `adb shell am kill`. Record the results in `specs/001-mvp-session-timer/research.md` (R4 and R5, "Verified on device"). Delete the spike files afterwards. **If (a) fails, stop and revisit R5 with the owner before Phase 5**

**Checkpoint**: Engine decides correctly in tests, the database and settings exist, hooks are wired but inactive, and platform assumptions are confirmed on the phone.

---

## Phase 3: User Story 7 - Setup: identity, privacy, permissions, tracked list (Priority: P1)

**Goal**: The app installs as Calm Launcher with no network access. It explains permissions and lets me choose tracked and exempt apps.

**Independent Test**: quickstart §1 (apkanalyzer shows no INTERNET) and §2. Settings let me track Instagram, and the change is stored. Double-tap-to-lock still works (quickstart §3 row 13).

- [ ] T020 [US7] Identity:
  - set `applicationId "app.calmlauncher"` in `app/build.gradle`
  - add `app/src/main/res/values/calm_strings.xml` (`calm_app_name`) and set the manifest `android:label`
  - update `packageNames` in `app/src/main/res/xml/accessibility_service_config.xml` (research R12)
- [ ] T021 [US7] Remove network access in `app/src/main/AndroidManifest.xml`: delete `INTERNET` and add `ACCESS_NETWORK_STATE` with `tools:node="remove"`. In `U/MainActivity.kt` (L192–194, L222) and `U/ui/HomeFragment.kt` `changeAppTheme()`, stop scheduling `WallpaperWorker`; call `cancelWallpaperWorker()` once on start. Verify with apkanalyzer (quickstart §1)
- [ ] T022 [P] [US7] Hide the "daily wallpaper" row and upstream promotional links (About/Pro/Rate/Share/Twitter) with `visibility="gone"` in `app/src/main/res/layout/fragment_settings.xml` and `app/src/main/res/layout-land/fragment_settings.xml`, and add a "Screen time" row. In `U/ui/SettingsFragment.kt`, wire that row to open `ScreenTimeSettingsActivity`
- [ ] T023 [P] [US7] Exclude `calm.db` and the `calm_settings` DataStore from backup in `app/src/main/res/xml/data_extraction_rules.xml` and `app/src/main/res/xml/backup_rules.xml` (research R11)
- [ ] T024 [US7] Create `A/settings/ScreenTimeSettingsActivity.kt` + `app/src/main/res/layout/calm_settings.xml`, with:
  - a searchable app list with Tracked/Exempt toggles (reuse upstream `getAppsList()` from `U/helper/Utils.kt:67`)
  - presets editor (`data-model.md` `TrackedApp.presetsMin`)
  - warning lead, extension wait and cooldown fields (`data-model.md` `TrackedApp.cooldownMin`, `Config.warningLeadSec`)
  - a confirmation dialog when removing a `DEFAULT` exempt app (spec FR-021)
- [ ] T025 [US7] Create `A/onboarding/PermissionStatus.kt`, a pure status check for each item:
  - notifications
  - overlay
  - usage access, reusing upstream `appUsagePermissionGranted()` in `U/helper/Extensions.kt:180`
  - exact alarms
  - battery optimisation

  Add a JVM-testable mapping from statuses to the health-line text in `A/CalmHooks.kt` `healthLine()`
- [ ] T026 [US7] Create `A/onboarding/OnboardingActivity.kt` + layout: one page per permission (why, what is reduced, deep-link button, live ✓/✗ in `onResume`, Skip). Add a final page that suggests messaging, banking, auth and transport apps from `KnownApps` to add as exempt. Launch it once from `CalmHooks.onLauncherResumed` when `onboarding_version` < 1
- [ ] T027 [US7] Add the health line `TextView` (`calmHealth`) to `app/src/main/res/layout/fragment_home.xml` and bind it in `U/ui/HomeFragment.kt` `onResume`. Tapping it opens the matching onboarding page
- [ ] T028 [US7] Manifest entries for our activities (`PromptActivity`, `ScreenTimeSettingsActivity`, `OnboardingActivity`), with `excludeFromRecents` and `taskAffinity=""` for the prompt. Then run quickstart §1–2 on the phone

**Checkpoint**: The fork is installable, private and configurable; no enforcement yet.

---

## Phase 4: User Story 1 - Declare a duration before opening a tracked app (Priority: P1) 🎯 MVP core

**Goal**: Launcher opens of tracked apps show "How long?". Cancel goes home; a choice launches the app and records a session.

**Independent Test**: quickstart §3 row 1 and row 8. Untracked apps show no delay.

- [ ] T029 [P] [US1] Write failing tests in `ET/SessionMachineTest.kt` for the Idle/Prompting rows of `data-model.md` "State transitions": `LaunchRequested`→Prompting; `Cancelled`→Idle with `Log(PROMPT_CANCELLED)`; `DurationChosen(min)` within 1..60 → Running, with `PersistSession` + `LaunchApp` + `StartCountdown`; out-of-range minutes rejected; a second app's `DurationChosen` ends the first as `LEFT_EARLY` (invariant 4)
- [ ] T030 [US1] Create `E/SessionState.kt` (states, events, effects, `Transition`) and implement those transitions in `E/SessionMachine.kt` until T029 passes
- [ ] T031 [US1] Create `A/session/SessionRepository.kt`. It loads the tracked/exempt sets into an in-memory cache, refreshed on change and package events, so `decide()` stays ≤ 5 ms. It applies `Transition` effects to Room (`startSession`, `endSession`, `log`)
- [ ] T032 [US1] Implement `A/gate/LaunchGate.kt` `intercept()`. It converts upstream `AppModel` to `AppKey` (user serial from the `UserHandle`) and calls `RulesEngine.decide`. `Allow` returns false; `AskDuration` or `Blocked` start `PromptActivity` with extras and return true. The whole body is wrapped in `failOpen(false)`
- [ ] T033 [US1] Create `A/prompt/PromptActivity.kt` + `app/src/main/res/layout/calm_prompt.xml`, DURATION mode only: presets, custom field (1–60, inline error), Cancel as the largest button, and Back = Cancel. On choice it launches via `LauncherApps.startMainActivity` (same as upstream `U/MainViewModel.kt:367`) and records the session through the repository. Comment explaining Activities and `FLAG_ACTIVITY_NEW_TASK`
- [ ] T034 [US1] Device check: quickstart §3 rows 1 and 8. Check all 7 entry points (home label, swipe left/right, drawer, search auto-launch, Enter, clock or calendar when set to a tracked app) and record the results in the PR description

**Checkpoint**: The core habit-breaker works on its own (sessions are recorded, though there is no timer UI yet).

---

## Phase 5: User Story 2 - See time left and get a warning (Priority: P1)

**Goal**: Silent countdown in the shade, one warning, and "End now".

**Independent Test**: quickstart §3 row 2 (countdown and warning parts).

- [ ] T035 [P] [US2] Add failing tests to `ET/SessionMachineTest.kt`:
  - `Tick` → Warning only when declared > lead (`data-model.md` `Config.warningLeadSec`)
  - exactly one `ShowWarning` per session
  - `EndedByUser` → Idle with `EndSession(ENDED_BY_USER)` + `GoHome`
- [ ] T036 [US2] Implement those transitions in `E/SessionMachine.kt` until T035 passes
- [ ] T037 [US2] Create `A/session/Notifications.kt`. Define the channels `calm_session` (low), `calm_warning` (high, respects silent mode) and `calm_expiry` (high). The countdown notification uses `setUsesChronometer(true)`, `setChronometerCountDown(true)` and `setWhen(deadline)`, with an "End now" action. Comment explaining notification channels
- [ ] T038 [US2] Create `A/session/SessionService.kt`, a foreground service of type `specialUse`. Add the manifest `<service>` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, and the `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS` permissions. It:
  - starts from `PromptActivity` after a choice
  - posts `Handler` callbacks at the warning and the deadline, feeding `Tick` to the repository
  - stops itself when no session is active

  Add comments explaining foreground services, ⚠️ the Android 12+ start rules and the Android 14 types
- [ ] T039 [US2] Device check: quickstart §3 row 2 (countdown visible, single warning, End now goes home)

**Checkpoint**: Sessions are visible and warned; expiry is not yet enforced.

---

## Phase 6: User Story 3 - Safe, calm end of session with effortful extension and cooldown (Priority: P1)

**Goal**: A time's-up card with Go home, Extend (wait, max 2) and Override (30 s → 5 min), plus the cooldown after an expired session.

**Independent Test**: quickstart §3 rows 2 (card part), 3 and 4.

- [ ] T040 [P] [US3] Add failing tests in `ET/SessionMachineTest.kt` for the expiry rows of `data-model.md` "State transitions":
  - Running/Warning → Expired
  - `ForegroundIsOther` / `ForegroundUnknown` → end quietly
  - `WentHome` / `CardTimedOut` → Cooldown or Idle
  - `Extended` only while extensions < `data-model.md` `Config.maxExtensions` (2; invariant 5), and only after `data-model.md` `Config.extensionWaitSec` has elapsed
  - `OverrideCompleted` → session of `data-model.md` `Config.overrideGrantMin` (5 min) with `Log(OVERRIDE)`
- [ ] T041 [P] [US3] Add failing tests in `ET/CooldownTest.kt`:
  - cooldown only after `EXPIRED` (`data-model.md` `session.end_reason`)
  - `cooldownMin = 0` disables it
  - `decide` returns `Blocked(COOLDOWN, until)` inside the window and `AskDuration` after it
  - `ENDED_BY_USER` and `LEFT_EARLY` never start a cooldown (research R10)
- [ ] T042 [US3] Implement the expiry and override transitions in `E/SessionMachine.kt`, and the cooldown check (step 7) in `E/RulesEngine.kt`, until T040–T041 pass
- [ ] T043 [US3] Create `A/enforce/ForegroundProbe.kt`, a one-shot `UsageStatsManager.queryEvents` for the last 10 min that returns the last resumed package or `null`, with one retry after 2 s (research R6). Comment explaining UsageStatsManager
- [ ] T044 [US3] Create `A/enforce/GoHome.kt` (HOME intent; research R5) and `A/enforce/OverlayController.kt`. The overlay is full-screen and opaque (`TYPE_APPLICATION_OVERLAY`), with focus and Back handling. It contains:
  - Go home (default)
  - Extend: a visible countdown of `data-model.md` `Config.extensionWaitSec` (15 s), then +2/+5 (`data-model.md` `Config.extensionOptionsMin`), hidden after `data-model.md` `Config.maxExtensions` (2) extensions
  - "I really need this": a countdown of `data-model.md` `Config.overrideWaitSec` (30 s, never below 10) that resets on visibility loss, then `data-model.md` `Config.overrideGrantMin` (5 min)

  It removes itself after 120 s (`data-model.md` `Config.cardTimeoutSec`, logged as `OVERLAY_TIMEOUT`). Add the `SYSTEM_ALERT_WINDOW` manifest permission and a comment explaining overlay windows and ⚠️ the Android 12+ touch rules
- [ ] T045 [US3] Fallback when the overlay permission is missing: in `A/session/Notifications.kt`, post a high-priority `calm_expiry` notification whose tap goes home (spec edge case)
- [ ] T046 [US3] Add BLOCKED mode to `A/prompt/PromptActivity.kt` for cooldown ("available again at HH:mm", Go home, override with a 30 s countdown that resets in `onPause`)
- [ ] T047 [US3] In `A/CalmHooks.kt`, remove any visible overlay in `onLauncherResumed` (the Home press while the card shows)
- [ ] T048 [US3] Device check: quickstart §3 rows 2, 3 and 4

**Checkpoint**: The full core loop (prompt → timer → end → cooldown) works.

---

## Phase 7: User Story 4 - The phone always stays usable (Priority: P1)

**Goal**: Calls, the keyguard and exempt apps are never disturbed; every surface can be escaped.

**Independent Test**: quickstart §3 rows 6, 7 and 8.

- [ ] T049 [P] [US4] Add failing tests in `ET/SessionMachineTest.kt`:
  - `Expired` + `InCallOrLocked` → `Expired(deferred = true)` with `DeferUntilClear`
  - after clearing, the card is shown only if the foreground app is still the tracked app
- [ ] T050 [US4] Implement the deferred-expiry transitions in `E/SessionMachine.kt` until T049 passes
- [ ] T051 [US4] Create `A/enforce/CallGuard.kt`. It checks `AudioManager.mode` (in-call, in-communication, ringtone) and `KeyguardManager.isKeyguardLocked`. It listens with `OnModeChangedListener` on API 31+; below that, it re-checks every 5 s **only while deferred**, as justified in plan.md Complexity Tracking. It also uses an `ACTION_USER_PRESENT` receiver registered by `SessionService` (research R7)
- [ ] T052 [US4] Gate every `OverlayController.show()` and warning through `CallGuard` and the exemption check (re-read at show time). Add an instrumented-free JVM test of the gate logic using a fake `CallGuard` in `app/src/test/java/app/calmlauncher/enforce/ShowGateTest.kt`
- [ ] T053 [US4] Loop guard in `A/enforce/OverlayController.kt`: more than 3 enforcement actions on the same app in 30 s disables enforcement for that app for 10 min and sets a health notice (TECHNICAL_DESIGN §6 item 6)
- [ ] T054 [US4] Device check: quickstart §3 rows 6, 7 and 8, including a WhatsApp call

**Checkpoint**: The MVP is safe to use daily.

---

## Phase 8: User Story 5 - Session survives reboot and process death (Priority: P2)

**Goal**: The deadline is honoured after a kill or reboot, closed silently if it has passed, and a notice is shown if the timer could not run.

**Independent Test**: quickstart §3 rows 9, 10 and 11.

- [ ] T055 [P] [US5] Add failing tests in `ET/SessionMachineTest.kt` for `Reconcile(now)`:
  - a future deadline means continue and reschedule
  - a past deadline means `EndSession(EXPIRED, at = deadline)` with no UI effect (invariant 6)
  - a past deadline also starts the cooldown from the deadline
  - `AppUninstalled(key)` from any state → Idle with `EndSession(INTERRUPTED)` (`data-model.md` "State transitions"; spec edge case "tracked app uninstalled")
- [ ] T056 [US5] Implement `Reconcile` in `E/SessionMachine.kt` until T055 passes
- [ ] T057 [US5] Create `A/session/BootReceiver.kt` (`RECEIVE_BOOT_COMPLETED`, manifest entry) that calls `SessionRepository.reconcile(now)` and restarts `SessionService` if a session is still active. While a session runs, `SessionService` also registers receivers for `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` that call reconcile and reschedule the callbacks (spec edge case "system clock changed"). `ACTION_PACKAGE_REMOVED` for the session's app feeds `AppUninstalled`. Comment explaining BroadcastReceivers and 🅢 boot delays on One UI
- [ ] T058 [US5] Create `A/session/SessionAlarmReceiver.kt`, plus an exact-alarm backup at the deadline when `canScheduleExactAlarms()`. Add the `SCHEDULE_EXACT_ALARM` manifest permission, and call reconcile from `CalmHooks.onLauncherResumed`. Comment explaining AlarmManager and ⚠️ Android 12/14 exact-alarm rules
- [ ] T059 [US5] On reconcile of a session whose timer evidently did not run (deadline passed > 60 s before any callback), log `SERVICE_INTERRUPTED` and show the health line "Timer was interrupted, check battery settings" once
- [ ] T060 [US5] Device check: quickstart §3 rows 9, 10 and 11

**Checkpoint**: Timers are robust against Samsung battery management.

---

## Phase 9: User Story 6 - Leaving the app early (Priority: P2)

**Goal**: Wall clock with a 2-minute grace period; staying home ends the session as `LEFT_EARLY`.

**Independent Test**: quickstart §3 row 5.

- [ ] T061 [P] [US6] Add failing tests in `ET/SessionMachineTest.kt`:
  - `LauncherResumed` schedules `GraceCheck(now + graceSec)` (`data-model.md` `Config.graceSec`)
  - `GraceElapsed` while still on the launcher → `EndSession(LEFT_EARLY)`
  - `LauncherPaused` before the grace ends cancels the check
  - `LaunchRequested` for the same app during grace continues the session
- [ ] T062 [US6] Implement those transitions in `E/SessionMachine.kt` until T061 passes
- [ ] T063 [US6] Wire `CalmHooks.onLauncherResumed` / `onLauncherPaused` to the repository, and run the grace callback in `SessionService`. When usage access is available, cross-check with `ForegroundProbe` (research R9). Record `session.left_at` (`data-model.md` `session.left_at`)
- [ ] T064 [US6] Device check: quickstart §3 row 5

---

## Phase 10: Polish and cross-cutting

- [ ] T065 [P] Unplanned-open counting on launcher resume: one UsageStats query since the last resume, logging `UNPLANNED_OPEN` events (`data-model.md` `event_log.type`), in `A/session/SessionRepository.kt`
- [ ] T066 [P] Instrumented DAO test `app/src/androidTest/java/app/calmlauncher/data/DaoTest.kt`. It checks the single-active-session transaction and the enum round-trips, run with `./gradlew connectedDebugAndroidTest` with the phone attached
- [ ] T067 [P] Write `docs/DEVICE_TESTS.md` from quickstart §3, adding results and dates, and update `docs/DEV_SETUP.md` (permissions to grant, keystore note)
- [ ] T068 [P] Re-check `docs/TECHNICAL_DESIGN.md` §2 "Upstream files modified" against `git diff upstream/master --stat` and fix any drift
- [ ] T069 Run the full quickstart (§1–§3), including row 13, which checks that upstream features still work (FR-025): home labels, search, gestures, hidden apps, Private Space. Run `/speckit-analyze` for consistency, then open a PR from `feature/screen-time-core` to the fork's `master` for owner review

---

## Dependencies and execution order

### Phase dependencies

- Setup (T001–T004) → Foundational (T005–T019) → US7 (T020–T028) → US1 (T029–T034) → US2 (T035–T039) → US3 (T040–T048) → US4 (T049–T054) → US5 (T055–T060) / US6 (T061–T064) → Polish
- US5 and US6 are independent of each other and both depend on US3.
- **T019 (device spike) gates US3 and US4.** If HOME-from-overlay fails on One UI, stop and revisit research R5.

### Within each story

- Engine test task → engine implementation task → Android adapter tasks → device check.
- Tasks that touch `E/SessionMachine.kt` are sequential across stories (same file).

### Parallel opportunities

- Setup: T004 alongside T003, once T002 is done.
- Foundational: T005, T006, T007 and T010 together; T011, T013, T014 and T015 together.
- US7: T022 and T023 alongside T020–T021.
- Each story's engine test task [P] can be written while the previous story's device check runs.

### Parallel example: Foundational

```text
T005 Model.kt  |  T006 Decision.kt  |  T007 FailOpen.kt  |  T010 InvariantsTest.kt
T011 Entities.kt  |  T013 SettingsStore.kt  |  T014 Mappers.kt  |  T015 util/FailOpen.kt
```

## Implementation strategy

1. **MVP first:** Phases 1–7 (through US4) give a safe, usable daily build. Stop and use it for a few days before Phases 8–9.
2. **Incremental delivery:** each phase ends with a device check and a commit. Every phase leaves the launcher fully usable, and a feature that is not yet wired fails open.
3. **Baseline week:** after Phase 3 (US7), install the build with tracking enabled but no tracked apps. This collects 7 days of UsageStats baseline (PRODUCT_REQUIREMENTS §5) while Phases 4+ are built.
4. **Out of scope for this task list:** accessibility-based interception, schedules, caps, launch delay, intent prompts, delayed loosening, stats screen and content filtering (Phase 2/3 roadmap).
