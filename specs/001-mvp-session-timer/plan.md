# Implementation Plan: MVP session timer launcher

**Branch**: `feature/screen-time-core` | **Date**: 2026-11-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-mvp-session-timer/spec.md`

## Summary

Turn the Olauncher fork into "Calm Launcher". It has no network access. Opening a tracked app from
the launcher asks "How long?". The session then runs on a persisted deadline, with a countdown
notification, one warning, and a calm full-screen time's-up card. That card offers an
effortful extension (max 2) and an always-available 30 s override.

All decisions live in a pure-Kotlin `:engine` module: rules order, state machine, cooldown and
exemptions. Thin Android adapters execute them: prompt activity, foreground service, overlay,
alarms, and boot/resume reconciliation. Upstream is touched only through a few listed one-line
hooks. Approach and trade-offs: `docs/TECHNICAL_DESIGN.md` §§2–6, 9–11; decisions: [research.md](research.md).

## Technical Context

**Language/Version**: Kotlin 2.1.20 (upstream), JVM target 17, built with JDK 17

**Primary Dependencies**:
- AndroidX already in upstream: appcompat, core-ktx, lifecycle, recyclerview, material
- new: Room 2.7.2 (KSP 2.1.20-2.0.1) and DataStore Preferences 1.1.7
- `:engine` uses stdlib + JUnit only

**Storage**: Room `calm.db` for tracked apps, exempt apps, sessions and events; DataStore `calm_settings`; upstream `Prefs` untouched. Excluded from backup.

**Testing**:
- JUnit 4 for `:engine` (main suite, `./gradlew :engine:test`)
- JUnit 4 for small JVM-testable app helpers
- minimal instrumented tests (Room DAO) with `connectedDebugAndroidTest`
- manual device script in [quickstart.md](quickstart.md)

**Target Platform**: Android, minSdk 24 / targetSdk 36 (upstream). Primary device: Samsung, One UI, Android 14–16, sideloaded with adb.

**Project Type**: Mobile app (single-activity launcher) + pure-Kotlin library module

**Performance Goals**:
- prompt visible ≤ 300 ms after a tap
- time's-up card ≤ 5 s after the deadline
- untracked app launch adds no perceptible delay (decision work ≤ 5 ms, on-device cache of tracked/exempt sets)

**Constraints**:
- no `INTERNET`
- no polling while idle; foreground service only during a session
- fail open on every error
- exempt apps never touched
- no drawing over the keyguard or during calls
- upstream diff limited to the listed files

**Scale/Scope**: 1 user, ~5–15 tracked apps, ≤ 50 sessions/day; ~6 new screens/surfaces; ~25 new Kotlin files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Pre-design | Post-design |
|---|---|---|---|
| I. Privacy First | No `INTERNET`/network perms; no SDKs; local data only; no screen text stored; backup excluded | ✅ | ✅ (R11, data-model `detail` rule; apkanalyzer check in quickstart) |
| II. Fail Open | Exempt first; errors mean allow; Home always works; no lock screens; card self-removes | ✅ | ✅ (contracts invariants 1–2, R6, R7, ui-surfaces "Must never") |
| III. Override | Override on every block/card, ≥10 s wait, always succeeds, logged; loosening delay N/A in MVP | ✅ | ✅ (`Blocked.overridable` always true; FR-014) |
| IV. Minimal UI | Text-only, upstream theme, calm copy | ✅ | ✅ (ui-surfaces) |
| V. Battery-light | Event-driven; foreground service only during a session; one-shot UsageStats queries | ✅ | ⚠️ One bounded exception: 5 s re-check **only while expiry is deferred by a call** on API < 31 (R7). Justified below |
| VI. Testable core | `:engine` with no Android deps; time injected; tests for all transitions | ✅ | ✅ (R3, contracts) |
| VII. Upstream isolation | New code in `app.calmlauncher` / `:engine`; edits listed | ✅ | ✅ (contracts/engine-api "Upstream call sites" + TECHNICAL_DESIGN §2 table) |
| VIII. Simple, explained Kotlin | No DI framework; comment each first-use Android concept | ✅ | ✅ (tasks include comment requirement) |
| Platform constraints | Kotlin only, Gradle CLI, flags for Android/OEM/Play | ✅ | ✅ (research flags ⚠️ 🅢) |

## Project Structure

### Documentation (this feature)

```text
specs/001-mvp-session-timer/
├── plan.md              # This file
├── research.md          # Phase 0 decisions R1–R12
├── data-model.md        # Engine types, state transitions, Room tables, settings
├── quickstart.md        # Build, install and device validation script
├── contracts/
│   ├── engine-api.md    # Engine API, adapter entry points, exact upstream call sites
│   └── ui-surfaces.md   # Screens, notifications, overlay: content, actions, "must never"
├── checklists/requirements.md
└── tasks.md             # Created by /speckit-tasks
```

### Source Code (repository root)

```text
settings.gradle                         # + include ':engine'                       (upstream edit)
build.gradle                            # + KSP classpath                            (upstream edit)
gradle/libs.versions.toml               # + room, datastore, ksp                     (upstream edit)
engine/
├── build.gradle                        # kotlin-jvm, toolchain 17, junit
└── src/
    ├── main/kotlin/app/calmlauncher/engine/
    │   ├── Model.kt                    # AppKey, TrackedApp, Exemptions, Config, SessionRecord
    │   ├── Decision.kt                 # Decision, AllowReason, BlockKind
    │   ├── RulesEngine.kt
    │   ├── SessionState.kt             # states, events, effects, Transition
    │   ├── SessionMachine.kt
    │   └── FailOpen.kt                 # failOpen { } helper used by engine entry points
    └── test/kotlin/app/calmlauncher/engine/
        ├── RulesEngineTest.kt
        ├── SessionMachineTest.kt
        ├── CooldownTest.kt
        └── InvariantsTest.kt
app/
├── build.gradle                        # applicationId, deps, ksp                   (upstream edit)
└── src/
    ├── main/AndroidManifest.xml        # perms, components, label, -INTERNET          (upstream edit)
    ├── main/res/xml/accessibility_service_config.xml, data_extraction_rules.xml, backup_rules.xml (upstream edits)
    ├── main/res/layout/fragment_home.xml, fragment_settings.xml, layout-land/fragment_settings.xml (upstream edits)
    ├── main/java/app/olauncher/MainViewModel.kt, MainActivity.kt, ui/HomeFragment.kt, ui/SettingsFragment.kt (upstream hooks)
    ├── main/java/app/calmlauncher/
    │   ├── CalmGraph.kt                # lazily created singletons (db, repo, settings)
    │   ├── CalmHooks.kt                # onLauncherResumed/Paused, healthLine
    │   ├── gate/LaunchGate.kt
    │   ├── prompt/PromptActivity.kt    # modes: DURATION, BLOCKED
    │   ├── session/SessionRepository.kt, SessionService.kt, SessionAlarmReceiver.kt, BootReceiver.kt, Notifications.kt
    │   ├── enforce/OverlayController.kt, ForegroundProbe.kt, CallGuard.kt, GoHome.kt
    │   ├── exempt/DefaultExemptions.kt, KnownApps.kt
    │   ├── data/CalmDatabase.kt, Entities.kt, Daos.kt, SettingsStore.kt, Mappers.kt
    │   ├── settings/ScreenTimeSettingsActivity.kt
    │   ├── onboarding/OnboardingActivity.kt, PermissionStatus.kt
    │   ├── spike/SpikeActivity.kt      # TEMPORARY (T019 device check), deleted after
    │   └── util/FailOpen.kt, Log.kt
    ├── main/res/layout/calm_*.xml, values/calm_strings.xml
    ├── test/java/app/calmlauncher/...  # JVM tests for mappers, Config sanitizing
    └── androidTest/java/app/calmlauncher/data/DaoTest.kt
```

**Structure Decision**: Two Gradle modules. `:engine` (pure Kotlin) holds every rule and state
transition. `:app` stays upstream's module, with our Android code in the separate source package
`app.calmlauncher`, which imports `app.olauncher.R` because the namespace is unchanged. Our screens are
standalone Activities, so upstream's navigation graph is untouched.

**Upstream edits added by this plan** (TECHNICAL_DESIGN §2 updated accordingly):
- `MainActivity.onPause()` (new override, 1 line)
- `data_extraction_rules.xml` and `backup_rules.xml` (backup exclusion, R11)

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 5 s re-check timer while expiry is deferred by a call, API < 31 only (Principle V) | Android < 12 has no callback for audio-mode changes; without a re-check the card would never show after a call | `TelephonyCallback` needs `READ_PHONE_STATE` and misses VoIP calls; the loop runs only while a call blocks an expiry and stops immediately after |
| `SYSTEM_ALERT_WINDOW` before Phase 2 accessibility | Only reliable way to show the time's-up card over another app and return home without background-activity-start failures (R5) | A full-screen-intent notification only becomes a heads-up while the screen is on, and Android 14+ limits it |

## Phase 0 / Phase 1 outputs

- Phase 0: [research.md](research.md). All unknowns are resolved; there are no remaining NEEDS CLARIFICATION.
- Phase 1: [data-model.md](data-model.md), [contracts/engine-api.md](contracts/engine-api.md), [contracts/ui-surfaces.md](contracts/ui-surfaces.md), [quickstart.md](quickstart.md).
- Device-verification items carried into tasks:
  - HOME start from the overlay on One UI (R5)
  - `specialUse` foreground service restart after boot/kill (R4)
  - UsageStats event lag (R6)
