# Implementation Plan: MVP session timer launcher

**Branch**: `feature/screen-time-core` | **Date**: 2026-11-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-mvp-session-timer/spec.md`

## Summary

Turn the Olauncher fork into "Calm Launcher". It has no network access. Opening a tracked app from
the launcher asks "How long?". The session then runs on a persisted deadline, with a countdown
notification, one warning, and a calm time's-up pop-up over the dimmed app. That card offers an
effortful extension (max 2) and an always-available 30 s override.

All decisions live in a pure-Kotlin `:engine` module: rules order, state machine, cooldown and
exemptions. Thin Android adapters execute them: prompt activity, foreground service, overlay,
alarms, and boot/resume reconciliation. Upstream is touched only through a few listed one-line
hooks. Approach and trade-offs: `docs/TECHNICAL_DESIGN.md` §§2–6, 9–11; decisions: [research.md](research.md).

**Plan update 2026-11-21 (third device test; spec Clarifications "clarify after the third device
test"; constitution 1.1.0).** A new, separate accessibility service, `CalmAccessibilityService`,
tells the engine which app comes to the front, and nothing else: no screen content (R13, R14).
With its toggle on:
- a tracked app opened from the recent-apps list, a link or a notification gets the same prompt or
  cooldown screen as a launcher open (FR-031, R15);
- leaving the tracked app for any other app starts the 2-minute grace, so the session ends as
  "left early" if the user does not come back (FR-015, R16);
- the pill or bar shows only while the tracked app is in front (FR-029, R17).

With the toggle off, everything falls back to today's launcher-only behaviour. The engine gets a
launch source, a foreground-change event and a three-way grace result; the database schema does
not change. The pill's pulse animation is removed (Constitution IV), and onboarding, the health
line and the settings status gain the new toggle.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (upstream), JVM target 17, built with JDK 17

**Primary Dependencies**:
- AndroidX already in upstream: appcompat, core-ktx, lifecycle, recyclerview, material
- new: Room 2.7.2 (KSP 2.1.20-2.0.1) and DataStore Preferences 1.1.7
- `:engine` uses stdlib + JUnit only

**Storage**: Room `calm.db` for tracked apps, exempt apps, sessions and events; DataStore `calm_settings`; SharedPreferences `calm_look` for the accent colour (read before the first frame, FR-030); upstream `Prefs` untouched. Excluded from backup.

**Testing**:
- JUnit 4 for `:engine` (main suite, `./gradlew :engine:test`)
- JUnit 4 for small JVM-testable app helpers
- minimal instrumented tests (Room DAO) with `connectedDebugAndroidTest`
- manual device script in [quickstart.md](quickstart.md)

**Target Platform**: Android, minSdk 24 / targetSdk 36 (upstream). Primary device: Samsung, One UI, Android 14–16, sideloaded with adb.

**Project Type**: Mobile app (single-activity launcher) + pure-Kotlin library module

**Performance Goals**:
- prompt visible ≤ 300 ms after a tap
- prompt or cooldown screen over an app opened from outside the launcher ≤ 1 s after it comes to the front (SC-008)
- time's-up card ≤ 5 s after the deadline
- untracked app launch adds no perceptible delay (decision work ≤ 5 ms, on-device cache of tracked/exempt sets)

**Constraints**:
- no `INTERNET`
- no polling while idle; foreground service only during a session
- fail open on every error
- exempt apps never touched
- no drawing over the keyguard or over a call (ringing, or running while the tracked app is not confirmed to be in front; research R7)
- upstream diff limited to the listed files
- the accessibility service never reads screen content: `canRetrieveWindowContent="false"`, only window-state events, only package and class name used (R13)

**Scale/Scope**: 1 user, ~5–15 tracked apps, ≤ 50 sessions/day; ~6 new screens/surfaces; ~25 new Kotlin files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Pre-design | Post-design |
|---|---|---|---|
| I. Privacy First | No `INTERNET`/network perms; no SDKs; local data only; no screen text stored; backup excluded | ✅ | ✅ (R11, data-model `detail` rule; apkanalyzer check in quickstart). Accessibility: no window content can be retrieved, event text is never read, only package/class kept in memory (R13) |
| II. Fail Open | Exempt first; errors mean allow; Home always works; no lock screens; card self-removes | ✅ | ✅ (contracts invariants 1–2, R6, R7, ui-surfaces "Must never"). Accessibility off, disconnected or failing = launcher-only behaviour; outside opens use the same rules (exempt first); an unknown grace result keeps the session instead of ending it (R15, R16) |
| III. Override | Override on every block/card, ≥10 s wait, always succeeds, logged; loosening delay N/A in MVP | ✅ | ✅ (`Blocked.overridable` always true; FR-014) |
| IV. Minimal UI (1.1.0) | Home and upstream screens text-only; one user-chosen accent and identifying app icons only on Calm screens; black-and-white choice; reminders visible on light and dark apps; no custom animations | ✅ | ✅ (ui-surfaces, FR-029, FR-030). The pill's pulse is removed (R17) |
| V. Battery-light | Event-driven; foreground service only during a session; one-shot UsageStats queries | ✅ | ⚠️ Bounded exceptions justified below (call re-check on API < 31, R7; time-left refresh). Foreground changes come from accessibility window events, which the constitution names as an event source; no polling added (R13) |
| VI. Testable core | `:engine` with no Android deps; time injected; tests for all transitions | ✅ | ✅ (R3, contracts) |
| VII. Upstream isolation | New code in `app.calmlauncher` / `:engine`; edits listed | ✅ | ✅ (contracts/engine-api "Upstream call sites" + TECHNICAL_DESIGN §2 table). The new accessibility service is our own component; upstream's `MyAccessibilityService` and its config are not touched (R13) |
| VIII. Simple, explained Kotlin | No DI framework; comment each first-use Android concept | ✅ | ✅ (tasks include comment requirement) |
| Platform constraints | Kotlin only, Gradle CLI, flags for Android/OEM/Play | ✅ | ✅ (research flags ⚠️ 🅢). 🅢 AccessibilityService used for a non-accessibility purpose (Play policy), acceptable for sideloaded use; ⚠️ Android 13+ restricted settings for sideloaded apps (R13) |

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
    │   ├── UnplannedOpens.kt           # unplanned opens (T065, T070)
    │   ├── ExemptRules.kt              # track/exempt rule (T103)
    │   ├── ForegroundFilter.kt         # which window events are app switches (R14, T086)
    │   └── FailOpen.kt                 # failOpen { } helper used by engine entry points
    └── test/kotlin/app/calmlauncher/engine/
        ├── RulesEngineTest.kt
        ├── SessionMachineTest.kt
        ├── CooldownTest.kt
        ├── InvariantsTest.kt
        ├── UnplannedOpensTest.kt
        ├── OutsideOpenTest.kt, LeavingTest.kt           # Phase 13
        └── ExemptRulesTest.kt, ForegroundFilterTest.kt  # Phase 13
app/
├── build.gradle                        # applicationId, deps, ksp                   (upstream edit)
└── src/
    ├── main/AndroidManifest.xml        # perms, components, label, -INTERNET          (upstream edit)
    ├── main/res/xml/accessibility_service_config.xml, data_extraction_rules.xml, backup_rules.xml (upstream edits)
    ├── main/res/xml/calm_accessibility_config.xml  # our service: window-state events only, no content
    ├── main/res/layout/fragment_home.xml, fragment_settings.xml, layout-land/fragment_settings.xml (upstream edits)
    ├── main/java/app/olauncher/MainViewModel.kt, MainActivity.kt, ui/HomeFragment.kt, ui/SettingsFragment.kt (upstream hooks)
    ├── main/java/app/calmlauncher/
    │   ├── CalmGraph.kt                # lazily created singletons (db, repo, settings)
    │   ├── CalmHooks.kt                # onLauncherResumed/Paused, healthLine
    │   ├── gate/LaunchGate.kt
    │   ├── access/CalmAccessibilityService.kt, AccessState.kt  # which app is in front (R13); no screen content
    │   ├── prompt/PromptActivity.kt    # modes: DURATION, BLOCKED
    │   ├── session/SessionRepository.kt, SessionService.kt, EffectRunner.kt, SessionAlarmReceiver.kt, BootReceiver.kt, Notifications.kt, TimeLeft.kt, TimeLeftOverlay.kt
    │   ├── enforce/Enforcer.kt, OverlayController.kt, ForegroundProbe.kt, CallGuard.kt, GoHome.kt, ShowGate.kt, LoopGuard.kt
    │   ├── exempt/DefaultExemptions.kt, KnownApps.kt
    │   ├── data/CalmDatabase.kt, Entities.kt, Daos.kt, SettingsStore.kt, Mappers.kt
    │   ├── settings/ScreenTimeSettingsActivity.kt, SettingSteps.kt
    │   ├── ui/CalmActivity.kt, CalmLook.kt, FlowLayout.kt  # shared look, accent colour (FR-030), chip rows
    │   ├── onboarding/OnboardingActivity.kt, PermissionStatus.kt
    │   └── util/FailOpen.kt, Log.kt, Apps.kt
    ├── main/res/layout/calm_*.xml, drawable/calm_*.xml, color/calm_*.xml
    ├── main/res/values/calm_strings.xml, calm_styles.xml, calm_attrs.xml; values{,-night,-v31,-night-v31}/calm_colors.xml
    ├── test/java/app/calmlauncher/...  # JVM tests: mappers, show gate, loop guard, health line, event detail, time left, setting steps, accent
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
| 5 s re-check timer while expiry is deferred by a call, or while the time's-up card is up (at most 120 s), API < 31 only (Principle V) | Android < 12 has no callback for audio-mode changes; without a re-check the card would never show after a call, and an incoming call would not hide the card | `TelephonyCallback` needs `READ_PHONE_STATE` and misses VoIP calls; the loop runs only in those two moments and stops immediately after |
| On-screen time-left reminder refresh (Principle V): bar, and the ring in the pill, every max(1 s, session length / 100); the pill's text also once per minute and once per second in the last minute; only while a session runs and the reminder is on | The owner asked for a constant on-screen reminder (FR-029); the self-updating system chronometer exists only inside notifications | A per-frame animation would keep the GPU busy for the whole session; no refresh at all cannot show time passing |
| `SYSTEM_ALERT_WINDOW` for the time's-up card and the time-left reminder, even now that an accessibility service exists | Only reliable way to show the card over another app and return home without background-activity-start failures (R5); it works whether or not the accessibility toggle is on | A full-screen-intent notification only becomes a heads-up while the screen is on, and Android 14+ limits it. Accessibility overlays work only while that toggle is on, and a second drawing path would double the testing |

## Phase 0 / Phase 1 outputs

- Phase 0: [research.md](research.md). All unknowns are resolved; there are no remaining NEEDS CLARIFICATION.
- Phase 1: [data-model.md](data-model.md), [contracts/engine-api.md](contracts/engine-api.md), [contracts/ui-surfaces.md](contracts/ui-surfaces.md), [quickstart.md](quickstart.md).
- Device-verification items carried into tasks:
  - HOME start from the overlay on One UI (R5)
  - `specialUse` foreground service restart after boot/kill (R4)
  - UsageStats event lag (R6)
  - the accessibility toggle can be turned on for an adb-installed build on Android 15 / One UI (restricted settings, R13)
  - One UI window events: keyboard, notification shade, edge panel and the Samsung Recents screen are not mistaken for app switches (R14)
  - starting the prompt from the accessibility service while another app is in front (R15)
