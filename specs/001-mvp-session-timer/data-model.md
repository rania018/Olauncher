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
| `SessionState` | `Idle`, `Prompting(key)`, `Running(rec)`, `Warning(rec)`, `Expired(rec, deferred)`, `Cooldown(key, until)` | See transitions below |

### State transitions (MVP subset of TECHNICAL_DESIGN §4.1)

| From | Event | Guard | To | Effects |
|---|---|---|---|---|
| Idle | `LaunchRequested(key)` | decide = `AskDuration` | Prompting | `ShowPrompt` |
| Idle | `LaunchRequested(key)` | decide = `Allow` | Idle | `LaunchApp` |
| Idle | `LaunchRequested(key)` | decide = `Blocked(Cooldown)` | Cooldown | `ShowBlocked(until)` |
| Prompting | `Cancelled` | | Idle | `Log(PROMPT_CANCELLED)` |
| Prompting | `DurationChosen(min)` | 1 ≤ min ≤ 60 | Running | `PersistSession`, `LaunchApp`, `StartCountdown(deadline)`, `ScheduleAt(warning)`, `ScheduleAt(deadline)` |
| Running | `Tick(now)` | now ≥ deadline − lead and declared > lead | Warning | `ShowWarning` |
| Running / Warning | `Tick(now)` | now ≥ deadline | Expired | `ProbeForegroundThenShowCard` |
| Expired | `ForegroundIsOther` / `ForegroundUnknown` | | Idle (Cooldown if `cooldownMin > 0`) | `EndSession(EXPIRED, at=deadline)`, `StopCountdown` |
| Expired | `InCallOrLocked` | | Expired(deferred = true) | `DeferUntilClear` |
| Expired | `WentHome` / `CardTimedOut` | | Cooldown or Idle | `EndSession(EXPIRED)`, `StopCountdown`, `GoHome` (not on timeout) |
| Expired | `Extended(min)` | extensions < 2, wait done | Running | `extensions++`, `Log(EXTENDED)`, reschedule |
| Expired / Cooldown | `OverrideCompleted` | wait ≥ overrideWaitSec | Running | new or extended session of 5 min, `Log(OVERRIDE)` |
| Running / Warning | `EndedByUser` | | Idle | `EndSession(ENDED_BY_USER)`, `GoHome` |
| Running / Warning | `LauncherResumed(now)` | | same | `ScheduleAt(now + grace, GraceCheck)` |
| Running / Warning | `GraceElapsed` | still on launcher | Idle | `EndSession(LEFT_EARLY)` |
| Running / Warning | `LaunchRequested(other key)` | | Prompting(other) | `EndSession(LEFT_EARLY)` for the current one |
| Any active | `Reconcile(now)` | now ≥ deadline and process restarted / after boot | Idle or Cooldown | `EndSession(EXPIRED, at=deadline)` with no UI |
| Any | `AppUninstalled(key)` | | Idle | `EndSession(INTERRUPTED)` |
| Any | exception in `reduce` | | unchanged | `Log(ERROR)` only; the adapter treats it as `Allow` / no UI |

## Persistence (`:app`, Room database `calm.db`, version 1)

| Table | Columns | Constraints |
|---|---|---|
| `tracked_app` | `pkg TEXT`, `user_serial INTEGER`, `presets_csv TEXT`, `cooldown_min INTEGER DEFAULT 10`, `created_at INTEGER` | PK (`pkg`, `user_serial`) |
| `exempt_app` | `pkg TEXT PK`, `source TEXT CHECK IN ('DEFAULT','USER')`, `category TEXT NULL` (`MESSAGING`, `BANKING`, `AUTH`, `TRANSPORT`, `SYSTEM`), `added_at INTEGER` | |
| `session` | `id INTEGER PK AUTOINCREMENT`, `pkg`, `user_serial`, `started_at`, `declared_ms`, `deadline_at`, `ended_at NULL`, `end_reason NULL CHECK IN ('EXPIRED','ENDED_BY_USER','LEFT_EARLY','INTERRUPTED')`, `extensions INTEGER DEFAULT 0`, `overrides INTEGER DEFAULT 0`, `left_at NULL` | Index on (`pkg`, `ended_at`); at most one row with `ended_at IS NULL` (enforced in a DAO transaction) |
| `event_log` | `id INTEGER PK`, `at INTEGER`, `pkg TEXT NULL`, `type TEXT`, `detail TEXT NULL` | `type` ∈ `PROMPT_CANCELLED`, `EXTENDED`, `OVERRIDE`, `OVERLAY_TIMEOUT`, `UNPLANNED_OPEN`, `SERVICE_INTERRUPTED`, `ERROR`, `COOLDOWN_SHOWN`. `detail` never contains screen text from other apps |

- "Cancelled" prompts are events, not sessions, so `session` only holds sessions that actually started.
- Unplanned opens (MVP) are counted when the launcher resumes: one UsageStats query since the last resume, finding resumed tracked packages with no matching session start. They are stored as `UNPLANNED_OPEN` events.

## Settings (Preferences DataStore `calm_settings`)

`warning_lead_sec`, `extension_wait_sec`, `max_extensions`, `override_wait_sec`, `grace_sec`,
`default_presets_csv`, `default_cooldown_min`, `onboarding_version`, `last_health_notice`.
The engine receives them as an immutable `Config`. Invalid values are clamped to the valid range.
