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

- [X] T001 Add Room 2.7.2, DataStore Preferences 1.1.7 and KSP 2.1.20-2.0.1 versions/libraries to `gradle/libs.versions.toml`, and the KSP plugin classpath to `build.gradle` (research R2)
- [X] T002 Create `:engine` module: `engine/build.gradle` (kotlin-jvm plugin, JVM toolchain 17, deps: stdlib + junit) and add `include ':engine'` to `settings.gradle` (research R3)
- [X] T003 Wire `app/build.gradle`: `implementation project(':engine')`, Room runtime/ktx + `ksp` compiler, DataStore; apply KSP plugin. **Dependency spike:** `./gradlew assembleDebug test` must pass with no source changes (research R2 verification)
- [X] T004 [P] Add `engine/src/test/kotlin/app/calmlauncher/engine/SmokeTest.kt` (one trivial test) and confirm `./gradlew :engine:test` runs in under 30 s

**Checkpoint**: Two-module build is green; upstream behaviour unchanged.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Engine types, persistence, safety helpers and the empty hook wiring that every story uses.

**⚠️ No user-story work starts before this phase is complete.**

### Engine core (test-first)

- [X] T005 [P] Create engine model in `E/Model.kt`: `AppKey`, `TrackedApp` (validate `data-model.md` `TrackedApp.presetsMin` and `TrackedApp.cooldownMin`), `Exemptions`, `Config` with `sanitized()` (clamps `data-model.md` `Config.overrideWaitSec` and `Config.warningLeadSec`), `SessionRecord` (`data-model.md` `SessionRecord.endedAt` semantics)
- [X] T006 [P] Create `E/Decision.kt`: `Decision`, `AllowReason`, `BlockKind`, with `Blocked.overridable` fixed to `true` (`data-model.md` `Decision.Blocked`, contracts/engine-api invariant 3)
- [X] T007 [P] Create `E/FailOpen.kt`: `inline fun <T> failOpen(fallback: T, onError: (Throwable) -> Unit = {}, block: () -> T): T`
- [X] T008 Write failing tests in `ET/RulesEngineTest.kt`, one test per row of the TECHNICAL_DESIGN §4.2 checks that apply to the MVP:
  - exempt → `Allow(EXEMPT)`
  - not tracked → `Allow(NOT_TRACKED)`
  - running → `Allow(RUNNING)`
  - otherwise `AskDuration(presets, 60)`
- [X] T009 Implement `E/RulesEngine.kt` `decide()` with the fixed check order, wrapped in `failOpen(Allow(FAIL_OPEN))`, until T008 passes
- [X] T010 [P] Write failing tests in `ET/InvariantsTest.kt` for contracts/engine-api invariants 1, 2 and 3 (exempt always wins across all inputs; malformed history never throws; every `Blocked` is overridable), plus the `data-model.md` "State transitions" row "exception in `reduce`": `SessionMachine.reduce` never throws, keeps the state unchanged and emits only `Log(ERROR)`

### Persistence and app plumbing

- [X] T011 [P] Create Room entities in `A/data/Entities.kt` for `tracked_app` (composite primary key per `data-model.md` `tracked_app.pkg` + `tracked_app.user_serial`, i.e. one row per app per user profile), `exempt_app`, `session`, `event_log`, enforcing `data-model.md` `exempt_app.source`, `exempt_app.category`, `session.end_reason` and `event_log.type` as Kotlin enums with type converters. First-use comment explaining Room
- [X] T012 Create DAOs in `A/data/Daos.kt`. `SessionDao.startSession()` runs in a transaction that guarantees at most one row with `ended_at IS NULL` (`data-model.md` `session.ended_at`). Create `A/data/CalmDatabase.kt` (`calm.db`, version 1, `exportSchema = true` into `app/schemas/`)
- [X] T013 [P] Create `A/data/SettingsStore.kt` (Preferences DataStore `calm_settings`; keys per data-model "Settings") returning `Config.sanitized()`. First-use comment explaining DataStore
- [X] T014 [P] Create `A/data/Mappers.kt` (entity ↔ engine model) plus JVM test `app/src/test/java/app/calmlauncher/data/MappersTest.kt` for the CSV presets round-trip and `userSerial` mapping
- [X] T015 [P] Create `A/util/FailOpen.kt` (Android wrapper that logs to `event_log` type `ERROR` and Logcat tag `CalmLauncher`) and `A/util/Log.kt`. Every write goes through one `logEvent()` function that enforces `data-model.md` `event_log.detail`: it stores only the exception class and our own messages, never text read from other apps' screens
- [X] T016 Create `A/CalmGraph.kt` (lazy singletons: database, settings, repository) with a comment on why no DI framework is used
- [X] T017 Create `A/exempt/DefaultExemptions.kt`. It resolves dialer/SMS roles via `RoleManager` (API 29+) or `resolveActivity` fallbacks, plus `geo:`, image capture, alarms and the Settings package (research R8). It seeds `exempt_app` rows with `source = DEFAULT`. Add `A/exempt/KnownApps.kt` (suggestion list for messaging, banking, auth and transport by package name)
- [X] T018 Add the upstream hook skeletons and run them as no-ops:
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

- [X] T020 [US7] Identity:
  - set `applicationId "app.calmlauncher"` in `app/build.gradle`
  - add `app/src/main/res/values/calm_strings.xml` (`calm_app_name`) and set the manifest `android:label`
  - update `packageNames` in `app/src/main/res/xml/accessibility_service_config.xml` (research R12)
- [X] T021 [US7] Remove network access in `app/src/main/AndroidManifest.xml`: delete `INTERNET` and add `ACCESS_NETWORK_STATE` with `tools:node="remove"`. In `U/MainActivity.kt` (L192–194, L222) and `U/ui/HomeFragment.kt` `changeAppTheme()`, stop scheduling `WallpaperWorker`; call `cancelWallpaperWorker()` once on start. Verify with apkanalyzer (quickstart §1)
- [X] T022 [P] [US7] Hide the "daily wallpaper" row and upstream promotional links (About/Pro/Rate/Share/Twitter) with `visibility="gone"` in `app/src/main/res/layout/fragment_settings.xml` and `app/src/main/res/layout-land/fragment_settings.xml`, and add a "Screen time" row. In `U/ui/SettingsFragment.kt`, wire that row to open `ScreenTimeSettingsActivity`
- [X] T023 [P] [US7] Exclude `calm.db` and the `calm_settings` DataStore from backup in `app/src/main/res/xml/data_extraction_rules.xml` and `app/src/main/res/xml/backup_rules.xml` (research R11)
- [X] T024 [US7] Create `A/settings/ScreenTimeSettingsActivity.kt` + `app/src/main/res/layout/calm_settings.xml`, with:
  - a searchable app list with Tracked/Exempt toggles (reuse upstream `getAppsList()` from `U/helper/Utils.kt:67`)
  - presets editor (`data-model.md` `TrackedApp.presetsMin`)
  - warning lead, extension wait and cooldown fields (`data-model.md` `TrackedApp.cooldownMin`, `Config.warningLeadSec`)
  - a confirmation dialog when removing a `DEFAULT` exempt app (spec FR-021)
- [X] T025 [US7] Create `A/onboarding/PermissionStatus.kt`, a pure status check for each item:
  - notifications
  - overlay
  - usage access, reusing upstream `appUsagePermissionGranted()` in `U/helper/Extensions.kt:180`
  - exact alarms
  - battery optimisation

  Add a JVM-testable mapping from statuses to the health-line text in `A/CalmHooks.kt` `healthLine()`
- [X] T026 [US7] Create `A/onboarding/OnboardingActivity.kt` + layout: one page per permission (why, what is reduced, deep-link button, live ✓/✗ in `onResume`, Skip). Add a final page that suggests messaging, banking, auth and transport apps from `KnownApps` to add as exempt. Launch it once from `CalmHooks.onLauncherResumed` when `onboarding_version` < 1
- [X] T027 [US7] Add the health line `TextView` (`calmHealth`) to `app/src/main/res/layout/fragment_home.xml` and bind it in `U/ui/HomeFragment.kt` `onResume`. Tapping it opens the matching onboarding page
- [ ] T028 [US7] Manifest entries for our activities (`PromptActivity`, `ScreenTimeSettingsActivity`, `OnboardingActivity`), with `excludeFromRecents` and `taskAffinity=""` for the prompt. Then run quickstart §1–2 on the phone

**Checkpoint**: The fork is installable, private and configurable; no enforcement yet.

---

## Phase 4: User Story 1 - Declare a duration before opening a tracked app (Priority: P1) 🎯 MVP core

**Goal**: Launcher opens of tracked apps show "How long?". Cancel goes home; a choice launches the app and records a session.

**Independent Test**: quickstart §3 row 1 and row 8. Untracked apps show no delay.

- [X] T029 [P] [US1] Write failing tests in `ET/SessionMachineTest.kt` for the Idle/Prompting rows of `data-model.md` "State transitions": `LaunchRequested`→Prompting; `Cancelled`→Idle with `Log(PROMPT_CANCELLED)`; `DurationChosen(min)` within 1..60 → Running, with `PersistSession` + `LaunchApp` + `StartCountdown`; out-of-range minutes rejected; a second app's `DurationChosen` ends the first as `LEFT_EARLY` (invariant 4)
- [X] T030 [US1] Create `E/SessionState.kt` (states, events, effects, `Transition`) and implement those transitions in `E/SessionMachine.kt` until T029 passes
- [X] T031 [US1] Create `A/session/SessionRepository.kt`. It loads the tracked/exempt sets into an in-memory cache, refreshed on change and package events, so `decide()` stays ≤ 5 ms. It applies `Transition` effects to Room (`startSession`, `endSession`, `log`)
- [X] T032 [US1] Implement `A/gate/LaunchGate.kt` `intercept()`. It converts upstream `AppModel` to `AppKey` (user serial from the `UserHandle`) and calls `RulesEngine.decide`. `Allow` returns false; `AskDuration` or `Blocked` start `PromptActivity` with extras and return true. The whole body is wrapped in `failOpen(false)`
- [X] T033 [US1] Create `A/prompt/PromptActivity.kt` + `app/src/main/res/layout/calm_prompt.xml`, DURATION mode only: presets, custom field (1–60, inline error), Cancel as the largest button, and Back = Cancel. On choice it launches via `LauncherApps.startMainActivity` (same as upstream `U/MainViewModel.kt:367`) and records the session through the repository. Comment explaining Activities and `FLAG_ACTIVITY_NEW_TASK`
- [ ] T034 [US1] Device check: quickstart §3 rows 1 and 8. Check all 7 entry points (home label, swipe left/right, drawer, search auto-launch, Enter, clock or calendar when set to a tracked app) and record the results in the PR description

**Checkpoint**: The core habit-breaker works on its own (sessions are recorded, though there is no timer UI yet).

---

## Phase 5: User Story 2 - See time left and get a warning (Priority: P1)

**Goal**: Silent countdown in the shade, one warning, and "End now".

**Independent Test**: quickstart §3 row 2 (countdown and warning parts).

- [X] T035 [P] [US2] Add failing tests to `ET/SessionMachineTest.kt`:
  - `Tick` → Warning only when declared > lead (`data-model.md` `Config.warningLeadSec`)
  - exactly one `ShowWarning` per session
  - `EndedByUser` → Idle with `EndSession(ENDED_BY_USER)` + `GoHome`
- [X] T036 [US2] Implement those transitions in `E/SessionMachine.kt` until T035 passes
- [X] T037 [US2] Create `A/session/Notifications.kt`. Define the channels `calm_session` (low), `calm_warning` (high, respects silent mode) and `calm_expiry` (high). The countdown notification uses `setUsesChronometer(true)`, `setChronometerCountDown(true)` and `setWhen(deadline)`, with an "End now" action. Comment explaining notification channels
- [X] T038 [US2] Create `A/session/SessionService.kt`, a foreground service of type `specialUse`. Add the manifest `<service>` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, and the `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS` permissions. It:
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

- [X] T040 [P] [US3] Add failing tests in `ET/SessionMachineTest.kt` for the expiry rows of `data-model.md` "State transitions":
  - Running/Warning → Expired
  - `ForegroundIsOther` / `ForegroundUnknown` → end quietly
  - `WentHome` / `CardTimedOut` → Cooldown or Idle
  - `Extended` only while extensions < `data-model.md` `Config.maxExtensions` (2; invariant 5), and only after `data-model.md` `Config.extensionWaitSec` has elapsed
  - `OverrideCompleted` → session of `data-model.md` `Config.overrideGrantMin` (5 min) with `Log(OVERRIDE)`
- [X] T041 [P] [US3] Add failing tests in `ET/CooldownTest.kt`:
  - cooldown only after `EXPIRED` (`data-model.md` `session.end_reason`)
  - `cooldownMin = 0` disables it
  - `decide` returns `Blocked(COOLDOWN, until)` inside the window and `AskDuration` after it
  - `ENDED_BY_USER` and `LEFT_EARLY` never start a cooldown (research R10)
- [X] T042 [US3] Implement the expiry and override transitions in `E/SessionMachine.kt`, and the cooldown check (step 7) in `E/RulesEngine.kt`, until T040–T041 pass
- [X] T043 [US3] Create `A/enforce/ForegroundProbe.kt`, a one-shot `UsageStatsManager.queryEvents` for the last 10 min that returns the last resumed package or `null`, with one retry after 2 s (research R6). Comment explaining UsageStatsManager
- [X] T044 [US3] Create `A/enforce/GoHome.kt` (HOME intent; research R5) and `A/enforce/OverlayController.kt`. The overlay is full-screen and opaque (`TYPE_APPLICATION_OVERLAY`), with focus and Back handling. It contains:
  - Go home (default)
  - Extend: a visible countdown of `data-model.md` `Config.extensionWaitSec` (15 s), then +2/+5 (`data-model.md` `Config.extensionOptionsMin`), hidden after `data-model.md` `Config.maxExtensions` (2) extensions
  - "I really need this": a countdown of `data-model.md` `Config.overrideWaitSec` (30 s, never below 10) that resets on visibility loss, then `data-model.md` `Config.overrideGrantMin` (5 min)

  It removes itself after 120 s (`data-model.md` `Config.cardTimeoutSec`, logged as `OVERLAY_TIMEOUT`). Add the `SYSTEM_ALERT_WINDOW` manifest permission and a comment explaining overlay windows and ⚠️ the Android 12+ touch rules
- [X] T045 [US3] Fallback when the overlay permission is missing: in `A/session/Notifications.kt`, post a high-priority `calm_expiry` notification whose tap goes home (spec edge case)
- [X] T046 [US3] Add BLOCKED mode to `A/prompt/PromptActivity.kt` for cooldown ("available again at HH:mm", Go home, override with a 30 s countdown that resets in `onPause`)
- [X] T047 [US3] In `A/CalmHooks.kt`, remove any visible overlay in `onLauncherResumed` (the Home press while the card shows)
- [ ] T048 [US3] Device check: quickstart §3 rows 2, 3 and 4

**Checkpoint**: The full core loop (prompt → timer → end → cooldown) works.

---

## Phase 7: User Story 4 - The phone always stays usable (Priority: P1)

**Goal**: Calls, the keyguard and exempt apps are never disturbed; every surface can be escaped.

**Independent Test**: quickstart §3 rows 6, 7 and 8.

- [X] T049 [P] [US4] Add failing tests in `ET/SessionMachineTest.kt`:
  - `Expired` + `InCallOrLocked` → `Expired(deferred = true)` with `DeferUntilClear`
  - after clearing, the card is shown only if the foreground app is still the tracked app
- [X] T050 [US4] Implement the deferred-expiry transitions in `E/SessionMachine.kt` until T049 passes
- [X] T051 [US4] Create `A/enforce/CallGuard.kt`. It checks `AudioManager.mode` (in-call, in-communication, ringtone) and `KeyguardManager.isKeyguardLocked`. It listens with `OnModeChangedListener` on API 31+; below that, it re-checks every 5 s **only while deferred**, as justified in plan.md Complexity Tracking. It also uses an `ACTION_USER_PRESENT` receiver registered by `SessionService` (research R7)
- [X] T052 [US4] Gate every `OverlayController.show()` and warning through `CallGuard` and the exemption check (re-read at show time). Add an instrumented-free JVM test of the gate logic using a fake `CallGuard` in `app/src/test/java/app/calmlauncher/enforce/ShowGateTest.kt`
- [X] T053 [US4] Loop guard in `A/enforce/OverlayController.kt`: more than 3 enforcement actions on the same app in 30 s disables enforcement for that app for 10 min and sets a health notice (TECHNICAL_DESIGN §6 item 6)
- [ ] T054 [US4] Device check: quickstart §3 rows 6, 7 and 8, including a WhatsApp call

**Checkpoint**: The MVP is safe to use daily.

---

## Phase 8: User Story 5 - Session survives reboot and process death (Priority: P2)

**Goal**: The deadline is honoured after a kill or reboot, closed silently if it has passed, and a notice is shown if the timer could not run.

**Independent Test**: quickstart §3 rows 9, 10 and 11.

- [X] T055 [P] [US5] Add failing tests in `ET/SessionMachineTest.kt` for `Reconcile(now)`:
  - a future deadline means continue and reschedule
  - a past deadline means `EndSession(EXPIRED, at = deadline)` with no UI effect (invariant 6)
  - a past deadline also starts the cooldown from the deadline
  - `AppUninstalled(key)` from any state → Idle with `EndSession(INTERRUPTED)` (`data-model.md` "State transitions"; spec edge case "tracked app uninstalled")
- [X] T056 [US5] Implement `Reconcile` in `E/SessionMachine.kt` until T055 passes
- [X] T057 [US5] Create `A/session/BootReceiver.kt` (`RECEIVE_BOOT_COMPLETED`, manifest entry) that calls `SessionRepository.reconcile(now)` and restarts `SessionService` if a session is still active. While a session runs, `SessionService` also registers receivers for `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` that call reconcile and reschedule the callbacks (spec edge case "system clock changed"). `ACTION_PACKAGE_REMOVED` for the session's app feeds `AppUninstalled`. Comment explaining BroadcastReceivers and 🅢 boot delays on One UI
- [X] T058 [US5] Create `A/session/SessionAlarmReceiver.kt`, plus an exact-alarm backup at the deadline when `canScheduleExactAlarms()`. Add the `SCHEDULE_EXACT_ALARM` manifest permission, and call reconcile from `CalmHooks.onLauncherResumed`. Comment explaining AlarmManager and ⚠️ Android 12/14 exact-alarm rules
- [X] T059 [US5] On reconcile of a session whose timer evidently did not run (deadline passed > 60 s before any callback), log `SERVICE_INTERRUPTED` and show the health line "Timer was interrupted, check battery settings" once
- [ ] T060 [US5] Device check: quickstart §3 rows 9, 10 and 11

**Checkpoint**: Timers are robust against Samsung battery management.

---

## Phase 9: User Story 6 - Leaving the app early (Priority: P2)

**Goal**: Wall clock with a 2-minute grace period; staying home ends the session as `LEFT_EARLY`.

**Independent Test**: quickstart §3 row 5.

- [X] T061 [P] [US6] Add failing tests in `ET/SessionMachineTest.kt`:
  - `LauncherResumed` schedules `GraceCheck(now + graceSec)` (`data-model.md` `Config.graceSec`)
  - `GraceElapsed` while still on the launcher → `EndSession(LEFT_EARLY)`
  - `LauncherPaused` before the grace ends cancels the check
  - `LaunchRequested` for the same app during grace continues the session
- [X] T062 [US6] Implement those transitions in `E/SessionMachine.kt` until T061 passes
- [X] T063 [US6] Wire `CalmHooks.onLauncherResumed` / `onLauncherPaused` to the repository, and run the grace callback in `SessionService`. When usage access is available, cross-check with `ForegroundProbe` (research R9). Record `session.left_at` (`data-model.md` `session.left_at`)
- [ ] T064 [US6] Device check: quickstart §3 row 5

---

## Phase 10: Polish and cross-cutting

- [X] T065 [P] Unplanned-open counting on launcher resume: one UsageStats query since the last resume, logging `UNPLANNED_OPEN` events (`data-model.md` `event_log.type`), in `A/session/SessionRepository.kt`
- [ ] T066 [P] Instrumented DAO test `app/src/androidTest/java/app/calmlauncher/data/DaoTest.kt`. It checks the single-active-session transaction and the enum round-trips, run with `./gradlew connectedDebugAndroidTest` with the phone attached
- [X] T067 [P] Write `docs/DEVICE_TESTS.md` from quickstart §3, adding results and dates, and update `docs/DEV_SETUP.md` (permissions to grant, keystore note)
- [X] T068 [P] Re-check `docs/TECHNICAL_DESIGN.md` §2 "Upstream files modified" against `git diff upstream/master --stat` and fix any drift
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
4. **Out of scope for this task list:** schedules, caps, launch delay, intent prompts, delayed loosening, stats screen and content filtering (Phase 2/3 roadmap). Accessibility-based interception was brought into scope by the third device test (Phase 13).

---

## Implementation status (2026-11-21)

**Current (after the third device test):** Phases 1–12 are built and approved; 162 JVM tests (engine and app) pass. Phase 13 (T083–T104) is written and awaits owner approval; device checks T076, T082 and the Phase 13 checks are open. The paragraphs below describe the state after Phase 10.

After Phase 10, all code tasks were done; the build, `./gradlew :engine:test` (97 tests) and `./gradlew test` are green, and
the merged manifest has no network permission. The open tasks all need the Samsung phone (none was
attached), and are tracked in `docs/DEVICE_TESTS.md`:

- **T019**: no spike was built. The full build exercises the same two assumptions; check them first with
  `docs/DEVICE_TESTS.md` §2. If HOME-from-overlay fails, only `enforce/GoHome.kt` and the card's
  "Go home" path change (research R5 fallback).
- **T028**: manifest entries done, quickstart §1 passes; §2 (install and onboard) needs the phone.
- **T066**: `DaoTest` written and compiling (`assembleDebugAndroidTest`); run `connectedDebugAndroidTest`.
- **T034, T039, T048, T054, T060, T064, T069**: device checks. T069 also opens the PR, after review.

Notes on how tasks were done:
- Engine tests for every story were written first and seen failing (48 of 89), then the state machine was
  implemented until green. The cooldown check (rules step 7) was implemented with T009.
- T017: dialer and SMS holders come from `TelecomManager` / `Telephony.Sms` (RoleManager's holder lookup is
  a system API); see research R8 implementation note.
- T057: uninstalls are detected with `LauncherApps.Callback.onPackageRemoved` (covers work profiles and
  ignores updates) instead of an `ACTION_PACKAGE_REMOVED` receiver.
- T065: the unplanned-open matching is a pure engine function (`UnplannedOpens`) with its own tests.
- Spec documents updated where implementation refined them: data-model state table, engine-api contract,
  ui-surfaces, research R7/R8, plan Complexity Tracking, TECHNICAL_DESIGN §2.

---

## Phase 11: Owner feedback after the first device run (2026-11-21)

**Owner approval**: reviewed and approved by the owner on 2026-11-21, after implementation (built before approval; analysis D1). Device check T076 still open.

**Input**: owner analysis after using the build on the Samsung; decisions recorded in spec.md Clarifications
("owner feedback after the first run on the phone"), FR-004, FR-010/FR-029, FR-012 and ui-surfaces.md.

- [X] T070 [P] Engine: unplanned opens count only after the app became tracked. Failing test first in `ET/UnplannedOpensTest.kt`, then `UnplannedOpens.find` takes `trackedSince: Map<String, Long>`
- [X] T071 [P] Pure time-left logic in `A/session/TimeLeft.kt` (label "4 min" / "0:42", bar fraction, next refresh delay) with JVM test `app/src/test/java/app/calmlauncher/session/TimeLeftTest.kt`
- [X] T072 [P] Tracked and exempt exclusive: pure rule in `A/exempt/ExemptRules.kt` + JVM test; `SessionRepository` removes tracked rows of exempt packages at load and whenever exemptions change; `setTracked` refuses SYSTEM exemptions
- [X] T073 Settings readability in `A/settings/ScreenTimeSettingsActivity.kt` and layouts: visible input lines, outlined Save, Track/Exempt checkboxes that exclude each other (confirmation before removing a default exemption), "Time left on screen" choice (pill / bar / off) stored as `time_left_display` (data-model.md Settings `time_left_display`)
- [X] T074 `A/session/TimeLeftOverlay.kt`: draggable pill (tap → session sheet) and touch-through bar, driven by `SessionService`, hidden with the card, during calls (API 31+ listener) and on the lock screen. Comment on overlay touch rules
- [X] T075 Time's-up card as a pop-up over the dimmed app (`calm_card.xml`, `OverlayController`), with transient audio focus while shown so media pauses
- [ ] T076 Device check: quickstart §3 rows 8, 14 and 15, plus the settings screen readability

## Phase 12: Owner feedback, second round (visibility, settings, accent colour)

**Owner approval**: reviewed and approved by the owner on 2026-11-21, after implementation (built before approval; analysis D1). Device check T082 still open.

**Goal**: The time-left reminder is impossible to miss, settings read as a real settings screen, the
prompt fits on one line, and the user can pick one accent colour (spec Clarifications, second round;
FR-024, FR-029, FR-030).

- [X] T077 [US2] Accent colour (FR-030): `calmAccent` / `calmOnAccent` theme attributes, `CalmAccent.*` overlays (values, values-night, values-v31 system palette), `A/ui/CalmLook.kt` (`Accent`, SharedPreferences `calm_look`, data-model.md Settings `calm_look.accent`, excluded from backup), applied in `CalmActivity` and `themedContext`; notifications tinted. Test: `app/src/test/java/app/calmlauncher/ui/AccentTest.kt`
- [X] T078 [US4] Screen time limits redesign: cards (setup status, sessions, time left, accent, apps), `SettingSteps` steppers and session-length chips (max 6, data-model.md `TrackedApp.presetsMin`), app icons, status lines, Track / Exempt toggles, tracked apps first, tap a tracked app → per-app editor; every change saves at once. Test: `app/src/test/java/app/calmlauncher/settings/SettingStepsTest.kt`
- [X] T079 [US2] Pill and bar redesign (FR-029): solid accent pill with shadow and an emptying ring, 6 dp accent bar on a dark track, amber from the warning on, pulse at the warning; `TimeLeft.nextRefreshMs` also steps the ring. Test: `TimeLeftTest`
- [X] T080 [US1] "How long?" presets as chips on one line (`A/ui/FlowLayout.kt`), app icon on the prompt; time's-up card with a filled Go home and chip extensions
- [X] T081 Docs: spec (clarifications, FR-024, FR-029, FR-030), ui-surfaces, data-model, plan Complexity Tracking, quickstart rows 14 and 16–18, `docs/DEVICE_TESTS.md`
- [ ] T082 Device check on the phone: quickstart rows 14, 16, 17 and 18

---

## Phase 13: Third device test — every open, leaving for another app, pill only in the app (2026-11-21)

**Owner approval**: Phase 13 tasks (T083–T104) approved by the owner on 2026-11-21, before implementation.

**Input**: spec Clarifications "clarify after the third device test" (FR-015, FR-029, FR-031, SC-008);
plan update of the same date; research R13–R17; data-model `LaunchSource`, `SessionState`, state
transitions and `event_log.detail`; contracts/engine-api invariants 7–10; quickstart rows 19–24;
constitution 1.1.0.

**Rules for this phase**: no task starts before the owner approves this list. Engine tests come first
and must fail before their implementation task (Constitution VI). New paths: `A/access/` =
`app/src/main/java/app/calmlauncher/access/`, `AT/` = `app/src/test/java/app/calmlauncher/`.

### 13.1 Foundational: the accessibility service and the device gate

**Purpose**: prove on the phone that the toggle can be turned on for an adb install and see which
window events One UI sends, before anything is built on them.

- [X] T083 Create `A/access/CalmAccessibilityService.kt` (skeleton), `app/src/main/res/xml/calm_accessibility_config.xml` (`typeWindowStateChanged` only, `canRetrieveWindowContent="false"`, no `packageNames`, `notificationTimeout="0"`), its `<service>` entry in `app/src/main/AndroidManifest.xml` (exported false, `BIND_ACCESSIBILITY_SERVICE`, label "Calm Launcher app limits"), and label/description strings in `app/src/main/res/values/calm_strings.xml` (research R13). The skeleton only logs `packageName` and `className` at debug level, never `event.text`; 2–4 line comment explaining an AccessibilityService (Constitution VIII); body in `failOpen { }`. Done: build passes and the service appears in Android's Accessibility list
- [X] T084 Device gate (needs the phone): install with `adb install -r`, turn the toggle on and note whether "Restricted setting" appears and whether App info → ⋮ → "Allow restricted settings" fixes it. With `adb logcat -s CalmLauncher:*`, record the events for: Recents open, link open, notification open, keyboard, notification shade, edge panel, Samsung Recents screen, in-app navigation, our launcher and prompt. Write the findings and the final ignore list into research.md R14. **Gates T088 and the tasks that need the service's events (T089–T091, T094, T097).** T086 can start with the default ignore list and add the One UI packages found here. If the toggle cannot be turned on, stop and revisit research R13

### 13.2 User Story 1 — every open of a tracked app is asked "How long?" (FR-031, SC-008)

**Goal**: with the toggle on, a tracked app opened from the recent-apps list, a link or a notification gets the prompt or the cooldown screen.
**Independent test**: quickstart rows 19 and 20.

- [X] T085 [P] [US1] Engine tests first, in new file `engine/src/test/kotlin/app/calmlauncher/engine/OutsideOpenTest.kt` (must fail before T087), for contracts/engine-api invariants 7 and 10: an OUTSIDE `Allow` emits no `LaunchApp`; OUTSIDE `AskDuration` → `Prompting(source = OUTSIDE)` + `ShowPrompt`; `DurationChosen` from an OUTSIDE prompt → `Running` with no `LaunchApp`; `OverrideCompleted` from an OUTSIDE cooldown screen → `Running` with no `LaunchApp`; `LaunchRequested(same key, OUTSIDE)` while `Prompting(key)` → unchanged, no effects; an OUTSIDE cancel and an OUTSIDE cooldown screen log with `detail` `src=outside`; LAUNCHER launches behave as before
- [X] T086 [P] [US1] Pure `E/ForegroundFilter.kt` (no Android types: the service passes the package, an is-activity flag and the time) with JVM tests first in `ET/ForegroundFilterTest.kt` (research R14, R15; contracts/engine-api `ForegroundFilter`): non-activity windows, `com.android.systemui`, input methods and the Recents screen (class `com.android.quickstep.RecentsActivity`, found in T084) → IGNORE; our package → OWN and remembered; the same package twice → IGNORE; A → OWN → A → A again in front; `noteCancelled(pkg)` ignores that package for 3 s, then allows it; a null package → IGNORE
- [X] T087 [US1] Implement launch sources in `engine/src/main/kotlin/app/calmlauncher/engine/SessionState.kt` and `engine/src/main/kotlin/app/calmlauncher/engine/SessionMachine.kt`: `LaunchSource` (data-model.md `LaunchSource`), `LaunchRequested.source` defaulting to LAUNCHER, `Prompting.source` (data-model.md `SessionState`), and the OUTSIDE rules from the data-model state table; `Log` effects from an OUTSIDE prompt (`PROMPT_CANCELLED`, `COOLDOWN_SHOWN`) carry `detail = "src=outside"` (data-model.md `event_log.detail`). T085 and all existing engine tests green
- [X] T088 [US1] Wire `A/access/CalmAccessibilityService.kt` (after T084, T086, T087): resolve "is an activity" with a cached `PackageManager.getActivityInfo(ComponentName(pkg, className))`, run `ForegroundFilter`, and for an app newly in front call a new `SessionRepository.onAppInFront(pkg)` in `A/session/SessionRepository.kt`. That dispatches `ForegroundChanged(pkg)`, and when `pkg` is tracked (main-profile entry first, research R13), `LaunchRequested(key, RulesEngine.decide(...), cooldownMin, OUTSIDE)`. Keep the service's connected state in `A/access/AccessState.kt` (`onServiceConnected` / `onUnbind`). Never read `event.text` or nodes; everything in `failOpen { }`
- [X] T089 [US1] Outside prompts in `A/session/SessionRepository.kt` and `A/prompt/PromptActivity.kt` (after T088): `ShowPrompt` / `ShowBlocked` from an OUTSIDE launch start `PromptActivity` from the application context (it already uses `FLAG_ACTIVITY_NEW_TASK`). When `Cancelled` ends a `Prompting(source = OUTSIDE)`, call `ForegroundFilter.noteCancelled(pkg, now)`; the prompt already goes home. The engine tags these log entries (T087)
- [X] T090 [US1] Skip the unplanned-open UsageStats query in `A/session/SessionRepository.kt` while `AccessState` says the service is connected (data-model.md unplanned-open note, research R15)
- [ ] T091 [US1] Device check (needs the phone): quickstart rows 19 and 20, including the ≤ 1 s target (SC-008); and SC-007: with the toggle on, untracked apps opened from the launcher and from Recents show no visible delay; record in `docs/DEVICE_TESTS.md`

### 13.3 User Story 6 — leaving for another app ends the session (FR-015)

**Goal**: going home, or switching to any other app, for 2+ minutes ends the session as left early; coming back within 2 minutes continues it.
**Independent test**: quickstart rows 21, 22 and the grace part of row 23.

- [X] T092 [P] [US6] Engine tests first, in new file `engine/src/test/kotlin/app/calmlauncher/engine/LeavingTest.kt` (must fail before T093), for contracts/engine-api invariants 8 and 9 and the data-model transitions: `ForegroundChanged(other)` starts the grace (`leftAt`, `ScheduleAt(GRACE)`) only when none is pending; `ForegroundChanged(session pkg)` and `LaunchRequested(session key, Allow)` (both sources) clear it; `LauncherPaused` leaves it pending; `GraceElapsed(false)` ends `LEFT_EARLY` with `leftAt`; `GraceElapsed(true)` and `GraceElapsed(null)` clear it and continue; `ForegroundChanged` while another app's prompt is open reaches the session behind it. Update the existing grace tests in `engine/src/test/kotlin/app/calmlauncher/engine/SessionMachineTest.kt` (`GraceElapsed(stillOnLauncher)`, "LauncherPaused clears the grace") to the new meaning
- [X] T093 [US6] Implement in `engine/src/main/kotlin/app/calmlauncher/engine/SessionState.kt` and `engine/src/main/kotlin/app/calmlauncher/engine/SessionMachine.kt` (after T087, same files): `ForegroundChanged(pkg)`, `GraceElapsed(trackedInFront: Boolean?)` replacing `stillOnLauncher`, and `LauncherPaused` no longer clearing the grace (data-model.md state table, research R16). T092 and all engine tests green
- [X] T094 [US6] Grace result in `A/session/SessionService.kt` (after T088, T093): at the GRACE wake, compute `trackedInFront` as in research R16 from `AccessState`'s last app in front, whether the launcher is still resumed (`A/CalmHooks.kt`), and `ForegroundProbe.lastResumed`; dispatch `GraceElapsed(trackedInFront)`. Unknown → `null`
- [ ] T095 [US6] Device check (needs the phone): quickstart rows 21, 22 and 23 (grace part); record in `docs/DEVICE_TESTS.md`

### 13.4 User Story 2 — the pill or bar shows only in the tracked app (FR-029)

**Goal**: with the toggle on, the reminder is hidden on the home screen and in other apps and comes back with the app; no custom animation.
**Independent test**: quickstart row 23 (pill part) and row 14.

- [X] T096 [P] [US2] Pure rule `TimeLeft.shouldShow(counting, graceCheckAt, accessibilityOn)` in `A/session/TimeLeft.kt` with tests first in `AT/session/TimeLeftTest.kt` (research R17): shown when counting and (toggle off or no grace pending); hidden otherwise
- [X] T097 [US2] Use it in `A/session/TimeLeftOverlay.kt` (after T088, T093, T096): read `graceCheckAt` from the Running/Warning state and `AccessState`; remove `pulse()` and its call (Constitution IV, 1.1.0). `SessionService.reschedule` already calls `sync` after every state change
- [ ] T098 [US2] Device check (needs the phone): quickstart row 23 (pill hides while away and comes back; keyboard and shade do not hide it) and row 14 again; record in `docs/DEVICE_TESTS.md`

### 13.5 User Story 7 — setup shows the new toggle (FR-022, FR-023)

**Goal**: onboarding has an "App limits" page, and the health line and settings status show when the toggle is off.
**Independent test**: quickstart row 24 and the onboarding page.

- [X] T099 [P] [US7] In `A/onboarding/PermissionStatus.kt`: `OnboardingPage.ACCESSIBILITY`, `PermissionStatus.accessibility` (our component in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`), and `HealthLine.Item.ACCESSIBILITY` ("Limits apply only from this launcher: app limits are off in Accessibility", after OVERLAY in priority). Update `AT/onboarding/HealthLineTest.kt` first
- [X] T100 [US7] Onboarding page in `A/onboarding/OnboardingActivity.kt` and `app/src/main/res/values/calm_strings.xml` (after T099): why, what is reduced, a button to `Settings.ACTION_ACCESSIBILITY_SETTINGS`, and the restricted-settings tip. Raise `SettingsStore.ONBOARDING_CURRENT` in `A/data/SettingsStore.kt` so existing installs see the new page once. Count the toggle in "Permissions and setup" in `A/settings/ScreenTimeSettingsActivity.kt`
- [ ] T101 [US7] Device check (needs the phone): the onboarding page, live status, and quickstart row 24 (toggle off: launcher-only, health line, pill everywhere, row 22 still ends the session); record in `docs/DEVICE_TESTS.md`

### 13.6 Polish and constitution fixes

- [X] T102 [P] Docs: rows 19–24 in `docs/DEVICE_TESTS.md`; the restricted-settings tip in `docs/DEV_SETUP.md` §6; the new manifest service and our own accessibility config in `docs/TECHNICAL_DESIGN.md` §2 (the manifest is already listed as an upstream-edited file)
- [X] T103 Move the track/exempt rule into the engine (Constitution VI, analysis D2): new `E/ExemptRules.kt` with `enum class ExemptionKind { ALWAYS, DEFAULT, USER }`, `whenTracking(kind: ExemptionKind?)` and `trackedToDrop(tracked, exemptPkgs)`; move the tests from `AT/exempt/ExemptRulesTest.kt` to `ET/ExemptRulesTest.kt` first (they fail until the engine code exists); `A/session/SessionRepository.kt` maps `exempt_app.category` and `exempt_app.source` to `ExemptionKind` (data-model.md `exempt_app.category`: SYSTEM → ALWAYS; data-model.md `exempt_app.source`: DEFAULT, USER); delete `A/exempt/ExemptRules.kt`. Also make `TrackedApp.of` keep at most 6 presets, with a test in `ET/` (data-model.md `TrackedApp.presetsMin`, analysis C3). No phone needed; it edits `SessionRepository.kt`, so run it before T088 or after T090, not in between
- [ ] T104 Before committing: remove the Sync Impact Report comment from `.specify/memory/constitution.md` (constitution workflow note), run `./gradlew :engine:test test assembleDebug` and the apkanalyzer check (quickstart §1: no network permission), then commit when the owner asks

### Phase 13 implementation notes

- T084: toggle turned on with no restricted-settings prompt; event findings and the final ignore rules are in research.md R14 (the Samsung recent-apps screen is ignored by class name).
- T085/T086: 17 new engine tests seen failing against stubs, then green with T087.
- T088: `SessionRepository.onAppInFront` dispatches only the outside `LaunchRequested` for now; the `ForegroundChanged` dispatch arrives with US6 (T093/T094), where that event is introduced.
- T092–T094, T096–T097, T099–T100, T102–T103 were written before a JDK was installed, so the new tests were not seen failing first; they compiled and passed on the first run of T111 (2026-11-24).
- T092/T093: `LeavingTest.kt` (24 tests) covers invariants 8 and 9; the four old grace tests in `SessionMachineTest.kt` now use `GraceElapsed(trackedInFront)` and "LauncherPaused keeps the grace".
- T094: the grace answer is the pure `GraceCheck.trackedInFront` in `:engine` (tests in `LeavingTest.kt`). `onAppInFront` now dispatches `ForegroundChanged(pkg)` before the outside `LaunchRequested`. Not in the task text but needed by research R16: `LaunchGate` sends `ForegroundChanged(session pkg)` on `Allow(RUNNING)`, because upstream opens a running session's app without the engine and `LauncherPaused` no longer ends the grace.
- T097: `pulse()` is gone; the call rule from Phase 14 still applies on top of `TimeLeft.shouldShow`.
- T099: the health line reads "Limits only from this launcher: app limits are off in Accessibility" (67 characters). The wording in the task text is 73 characters and would fail the existing `linesAreShortAndCalm` test (at most 70).
- T100: `ONBOARDING_CURRENT` is 2; an install that already saw onboarding opens once at the "App limits" page. The page status reads "On" / "Off".
- T103: the six shortest session lengths are kept (`TrackedApp.MAX_PRESETS`). The row-to-kind mapping is `Mappers.toExemptionKind` (tested in `MappersTest`), called from `SessionRepository.requestTrack`.

### Phase 13 dependencies

- T083 → T084 (gate). The engine and pure tasks do not need the phone: T085, T086, T092, T096 and T099 can start at once, in parallel.
- T085 → T087 → T093 (T093 also needs T092). T087 and T093 edit the same two engine files, so they run one after the other.
- T084 + T086 + T087 → T088 → T089 → T090 → T091.
- T088 + T093 → T094 → T095.
- T088 + T093 + T096 → T097 → T098.
- T099 → T100 → T101.
- T102 any time after T084; T103 any time before T088 or after T090; T104 last.
- Still open from earlier phases and best checked in the same phone session: T076 and T082 (rows 8, 14–18).

### Parallel example: Phase 13 start

```text
T083 CalmAccessibilityService skeleton | T085 OutsideOpenTest.kt | T086 ForegroundFilter(+Test)
T092 LeavingTest.kt | T096 TimeLeft.shouldShow(+Test) | T099 PermissionStatus/HealthLine(+Test)
```

### Phase 13 strategy

1. **Gate first:** T083–T084 on the phone. If the toggle cannot be turned on for an adb install, nothing else in this phase is useful.
2. **US1 next:** catching opens from Recents is the biggest gap the owner found; ship and check it (T091) before US6 and US2.
3. **US6, then US2:** both reuse the grace and the service state; US2 is the smallest change.
4. **US7 last:** the page and health line, then one device session for all open checks (T091, T095, T098, T101 plus T076/T082).

---

## Phase 14: A tracked app used during a call (2026-11-23)

**Owner approval**: the rule ("pill and card when the tracked app is in front during a call") was chosen by the owner on 2026-11-23, before implementation.

**Input**: spec Clarifications "Session 2026-11-23", US4 sc. 3 and 7, FR-018, FR-029, FR-012; research R7 and R6 updates; contracts/ui-surfaces rows Warning, Time's-up card and Time left on screen; quickstart rows 6 and 25.

- [X] T105 [US4] Pure rule `E/CallRule.kt` `blocks(call, locked, screenOff, trackedInFront)` with JVM tests in `ET/CallRuleTest.kt` (research R7): lock screen, dark screen and a ringing call always block; a running call blocks unless the tracked app is in front
- [X] T106 [US4] `A/enforce/CallGuard.kt`: `callState`, `isBlocked(context, pkg)` reading `AccessState.inFront`, `guardState(context, pkg)`, `onForegroundChanged`; `check` and the 2 s settle use the session's app. `A/enforce/Enforcer.kt`: the card gates pass the app, the warning keeps "any call blocks"
- [X] T107 [US2] `A/session/TimeLeftOverlay.kt`: the reminder asks `CallGuard.isBlocked(app, pkg)` and gains `onForegroundChanged`; `A/access/CalmAccessibilityService.kt` calls both hooks whenever the app in front changes (no polling)
- [X] T108 [US3] `A/enforce/OverlayController.kt`: no audio-focus request while a call rings or runs (FR-012, FR-018)
- [X] T109 [US3] `A/enforce/ForegroundProbe.kt`: the usage-history query reaches back to the session start (research R6 update)
- [X] T110 [US1] `A/gate/LaunchGate.kt`: debug builds log why a launch was not prompted (`no prompt for <pkg>: RUNNING`)
- [X] T111 Run `./gradlew :engine:test test assembleDebug` (2026-11-24, on the Windows PC: 155 engine tests and 66 app tests green, debug APK built, apkanalyzer shows no network permission)
- [ ] T112 [US4] Device check (needs the phone): quickstart rows 6 and 25; record in `docs/DEVICE_TESTS.md`

### Phase 14 notes

- T097 (pill only in the tracked app, done 2026-11-23) keeps the call rule: during a call the reminder needs both "no grace pending" and `CallGuard.isBlocked(app, pkg) == false`.
- The 2026-11-23 run also saw Instagram open from the launcher with no prompt during the call. The launcher gate has no call logic; it skips the prompt for a tracked app only while that app's session is still running (FR-008). T110's log line settles this at the next device check.
- Phone log of 2026-11-23, 20:45 (build of 2026-11-21 still installed): `Tick: Warning -> Expired [ProbeForegroundThenShowCard]` then `InCallOrLocked: Expired -> Expired [DeferUntilClear]` during a WhatsApp call, and the session closed quietly at the next `LauncherResumed`. That is the deferral this phase narrows.
- Phone database read the same evening (`session` and `event_log`): no `ERROR` event has ever been recorded, so the launcher gate never failed open on an exception. All five sessions that expired that evening ended exactly at their deadline, which only happens when no card was shown. Instagram sessions were running 19:52–19:57 and 20:39–20:40; a launcher open of Instagram inside such a window gets no prompt by design (FR-008). The minute of the reported open is not known, so this fits the "session still running, hidden by the call" explanation without proving it; T110's log line will.
