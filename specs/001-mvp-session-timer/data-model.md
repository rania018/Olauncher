# Data model: MVP session timer launcher

This model covers only the MVP subset. The full model is in `docs/TECHNICAL_DESIGN.md` §9. Later
tables arrive with their phase through Room migrations.

## Engine types (`:engine`, pure Kotlin)

| Type | Fields | Rules |
|---|---|---|
| `AppKey` | `pkg: String`, `userSerial: Long` | Identity of an app per user profile. `userSerial` comes from `UserManager.getSerialNumberForUser`. Main profile = its serial (usually 0) |
| `TrackedApp` | `key: AppKey`, `presetsMin: List<Int>`, `cooldownMin: Int` | presets non-empty, each 1..60, sorted, distinct; `cooldownMin` 0..60 (0 = off) |
| `Exemptions` | `defaults: Set<String>`, `user: Set<String>` (package names) | Exempt applies to all profiles of a package |
| `Config` | `warningLeadSec=60`, `extensionWaitSec=15`, `extensionOptionsMin=[2,5]`, `maxExtensions=2`, `overrideWaitSec=30`, `overrideGrantMin=5`, `graceSec=120`, `customRangeMin=1..60`, `cardTimeoutSec=120` | `overrideWaitSec >= 10` (clamped on load); `warningLeadSec` 0..300 |
| `SessionRecord` | `id`, `key`, `startedAt`, `declaredMs`, `deadlineAt`, `endedAt?`, `endReason?`, `extensions`, `overrides` | `deadlineAt = startedAt + declaredMs + extensions/overrides added`; `endedAt == null` ⇔ active |
| `Decision` | `Allow(reason)`, `AskDuration(presets, customMax)`, `Blocked(kind, until, overridable = true)` | `overridable` is not a constructor parameter; it is always true |
| `SessionState` | `Idle`, `Prompting(key, previous, blockedUntil?)`, `Running(rec, graceCheckAt?)`, `Warning(rec, graceCheckAt?)`, `Expired(rec, deferred, cardShown)`, `Cooldown(key, until)` | See transitions below. `Prompting.previous` is the state to return to on cancel (it may hold another app's running session). `Prompting` with `blockedUntil` is the cooldown screen (BLOCKED mode) |

### State transitions (MVP subset of TECHNICAL_DESIGN §4.1)

| From | Event | Guard | To | Effects |
|---|---|---|---|---|
| Idle | `LaunchRequested(key)` | decide = `AskDuration` | Prompting | `ShowPrompt` |
| Idle | `LaunchRequested(key)` | decide = `Allow` | Idle | `LaunchApp` |
| Idle | `LaunchRequested(key)` | decide = `Blocked(Cooldown)` | Prompting (BLOCKED) | `ShowBlocked(until)`, `Log(COOLDOWN_SHOWN)` |
| Prompting | `Cancelled` | | Idle | `Log(PROMPT_CANCELLED)` |
| Prompting | `DurationChosen(min)` | 1 ≤ min ≤ 60 | Running | `PersistSession`, `LaunchApp`, `StartCountdown(deadline)`, `ScheduleAt(warning)`, `ScheduleAt(deadline)` |
| Running | `Tick(now)` | now ≥ deadline − lead and declared > lead | Warning | `ShowWarning` |
| Running / Warning | `Tick(now)` | now ≥ deadline | Expired | `ProbeForegroundThenShowCard` |
| Expired | `ForegroundIsTracked` | not deferred | Expired(cardShown) | `ShowCard(canExtend = extensions < maxExtensions)` |
| Expired | `ForegroundIsOther` / `ForegroundUnknown` / `CardUnavailable` | | Idle (Cooldown if `cooldownMin > 0`) | `EndSession(EXPIRED, at=deadline)`, `StopCountdown` |
| Expired | `InCallOrLocked` | | Expired(deferred = true) | `DeferUntilClear` (and `HideCard` if the card was up) |
| Expired(deferred) | `GuardCleared` | | Expired | `ProbeForegroundThenShowCard` |
| Expired | `WentHome` / `CardTimedOut` / `LauncherResumed` | | Cooldown or Idle | `EndSession(EXPIRED, at=now)`, `StopCountdown`, `GoHome` (only on `WentHome`, and before `HideCard`, research R5), `HideCard`, `Log(OVERLAY_TIMEOUT)` on timeout |
| Expired | `ExtendStarted` then `Extended(min)` | card shown, extensions < `maxExtensions`, `extensionWaitSec` since `ExtendStarted`, min in `extensionOptionsMin` | Warning (no second warning) | `extensions++`, deadline = now + min, `Log(EXTENDED)`, `HideCard`, reschedule |
| Expired / Prompting (BLOCKED) | `OverrideStarted` then `OverrideCompleted` | `overrideWaitSec` since `OverrideStarted` (`OverrideReset` on visibility loss clears it) | Warning (card) / Running (cooldown screen) | extended session (card) or new session (cooldown screen) of 5 min, `Log(OVERRIDE)` |
| Running / Warning | `EndedByUser` | | Idle | `EndSession(ENDED_BY_USER)`, `GoHome` |
| Running / Warning | `LauncherResumed` | | same, `leftAt = now` | `ScheduleAt(now + grace, GRACE)` |
| Running / Warning | `LauncherPaused` | grace pending | same, grace cleared | `CancelSchedule(GRACE)` |
| Running / Warning | `GraceElapsed(stillOnLauncher)` | grace due and still on launcher | Idle | `EndSession(LEFT_EARLY, leftAt)`, `StopCountdown` |
| Running / Warning | `DurationChosen(other key)` | prompt for another app confirmed | Running(other) | `EndSession(LEFT_EARLY)` for the current one, then as `DurationChosen` above (a cancelled prompt leaves the current session untouched) |
| Any active | `Reconcile(bootedAt)` | now ≥ deadline | Idle or Cooldown | `EndSession(EXPIRED, at=deadline)` with no UI; `Log(SERVICE_INTERRUPTED)` if the device was up and the deadline is > 60 s old |
| Any active | `Reconcile(bootedAt)` | now < deadline | same | `StartCountdown`, `ScheduleAt(...)` |
| Prompting | any event not about the prompt | | Prompting(previous = reduced) | the previous state's effects |
| Any | `AppUninstalled(key)` | | Idle | `EndSession(INTERRUPTED)` |
| Any | exception in `reduce` | | unchanged | `Log(ERROR)` only; the adapter treats it as `Allow` / no UI |

## Persistence (`:app`, Room database `calm.db`, version 1)

| Table | Columns | Constraints |
|---|---|---|
| `tracked_app` | `pkg TEXT`, `user_serial INTEGER`, `presets_csv TEXT`, `cooldown_min INTEGER DEFAULT 10`, `created_at INTEGER` | PK (`pkg`, `user_serial`) |
| `exempt_app` | `pkg TEXT PK`, `source TEXT CHECK IN ('DEFAULT','USER')`, `category TEXT NULL` (`MESSAGING`, `BANKING`, `AUTH`, `TRANSPORT`, `SYSTEM`), `added_at INTEGER` | |
| `session` | `id INTEGER PK AUTOINCREMENT`, `pkg`, `user_serial`, `started_at`, `declared_ms`, `deadline_at`, `ended_at NULL`, `end_reason NULL CHECK IN ('EXPIRED','ENDED_BY_USER','LEFT_EARLY','INTERRUPTED')`, `extensions INTEGER DEFAULT 0`, `overrides INTEGER DEFAULT 0`, `left_at NULL` | Index on (`pkg`, `ended_at`); at most one row with `ended_at IS NULL` (enforced in a DAO transaction) |
| `event_log` | `id INTEGER PK`, `at INTEGER`, `pkg TEXT NULL`, `type TEXT`, `detail TEXT NULL` | `type` ∈ `PROMPT_CANCELLED`, `EXTENDED`, `OVERRIDE`, `OVERLAY_TIMEOUT`, `UNPLANNED_OPEN`, `SERVICE_INTERRUPTED`, `ERROR`, `COOLDOWN_SHOWN`, `LOOP_GUARD`. `detail` never contains screen text from other apps (only our own short text or an exception class name, enforced in `util/Log.kt`) |

- "Cancelled" prompts are events, not sessions, so `session` only holds sessions that actually started.
- Unplanned opens (MVP) are counted when the launcher resumes: one UsageStats query since the last resume, finding resumed tracked packages with no matching session start. They are stored as `UNPLANNED_OPEN` events.

## Settings (Preferences DataStore `calm_settings`)

`warning_lead_sec`, `extension_wait_sec`, `max_extensions`, `override_wait_sec`, `grace_sec`,
`default_presets_csv`, `default_cooldown_min`, `onboarding_version`, `last_health_notice`,
`removed_default_exempt` (defaults the user removed, so re-resolution does not add them back),
`last_launcher_resume` (start of the next unplanned-open query).
The engine receives them as an immutable `Config`. Invalid values are clamped to the valid range.
