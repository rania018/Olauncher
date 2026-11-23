# Product requirements: Calm Launcher

Status: draft v1, 2026-11-21. Owner: the single user of this fork.
Related: [USER_STORIES.md](USER_STORIES.md), [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md),
constitution at `.specify/memory/constitution.md`.

## 1. Problem

I open social and video apps on autopilot. A quick check (a DM, one person's post) turns into
20–40 minutes of Reels, Shorts or suggested posts. Daily-total limits like Digital Wellbeing don't
help: by the time the daily cap fires, the damage is done, and the cap is easy to dismiss. I need
friction **at the moment of opening** and a hard edge **at the end of each session**, without
ever being cut off from calls, messages, maps or banking.

## 2. Goals

| # | Goal |
|---|---|
| G1 | Every open of a time-sink app is a conscious decision with a declared duration |
| G2 | Each session ends near the declared time, and extending it costs effort |
| G3 | The rest of the phone stays fully usable, whatever breaks |
| G4 | Reduce unplanned opens through delay, intent prompts, cooldowns and schedules |
| G5 | Allow the useful parts of social apps (DMs, people I follow) while blocking the addictive surfaces (Reels, suggestions), as an experiment |
| G6 | Show honest, local numbers so I can see whether this works |

## 3. Non-goals

- Multi-user, parental control, or remote management. There is one user, who also holds the override.
- Tamper-proofing against myself. Uninstalling or switching launchers is always possible; the design uses friction, not locks.
- Any cloud, account, sync, backup service, analytics or ads.
- Publishing on Google Play. Personal sideloading only; Play-policy conflicts are flagged, not solved.
- Icons, widgets, themes beyond upstream Olauncher's.
- Website blocking inside browsers. Out of scope, except as the documented alternative for content filtering in TECHNICAL_DESIGN §8.
- Changing other apps' layouts. An AccessibilityService can read and navigate other apps, but **cannot remove or restyle their views** (see §8).

## 4. Target user and context

- **User:** me, a software developer who is comfortable with `adb` and settings menus and wants friction rather than prohibition.
- **Device:** Samsung phone on One UI (Android 14–16), interface language English.
- **Time-sink apps (tracked):** Instagram, Facebook, YouTube, TikTok, browser (optional). The list is opt-in.
- **Never blocked (exempt by default):** Phone/dialer, Messages, Samsung or Google Contacts, Maps/navigation apps, banking apps, authenticator apps, emergency SOS, Settings, Camera, Clock. WhatsApp and Messenger are exempt by default as "messages", which is editable.

## 5. Success metrics

Baseline: run the fork for 7 days with tracking on and enforcement off, and read the numbers
from the existing UsageStats code (`helper/usageStats/EventLogWrapper.kt`). Then compare weeks 3–4.

| Metric | Source | Target after 4 weeks |
|---|---|---|
| Daily screen time (all apps) | UsageStats foreground time | −25% vs baseline |
| Daily time in tracked apps | UsageStats + session log | −40% vs baseline |
| Unplanned opens / day: tracked-app opens **not** started via the launcher prompt | UsageStats resume events minus prompt log | −50% |
| Sessions per tracked app / day | Session log | Trend down; no target on day 1 |
| Session overrun: share of sessions that end within +1 min of the declared duration | Session log | ≥ 80% |
| Extensions + emergency overrides / day | Override log | ≤ 1 per day, on average over a week |
| Lock-out incidents: needed an exempt function and couldn't reach it | Manual note | **0** (hard requirement) |
| Rule breakage notices (content filter disabled itself) | Rule health log | Visible in stats; no target |

## 6. Feature list (prioritized)

MoSCoW priority: **MUST** = needed for the product to make sense; **SHOULD** = high value, next
phase; **COULD** = nice to have or experimental. *Phase* follows the roadmap in TECHNICAL_DESIGN §14:
MVP, P2 or P3.

### 6.1 Launcher and UI

| ID | Feature | Priority | Phase |
|---|---|---|---|
| L1 | Fork identity: own app id and name ("Calm Launcher", `app.calmlauncher`), upstream attribution kept (GPL-3.0) | MUST | MVP |
| L2 | No network: remove `INTERNET` and the daily-wallpaper download; remove links to upstream promotions | MUST | MVP |
| L3 | Text-only home with a few pinned apps (upstream: up to 8), everything else via search (upstream behaviour kept) | MUST | MVP (exists) |
| L4 | No icons, badges or notification counts in the launcher (upstream already shows none). Add a settings shortcut to One UI's *App icon badges* switch so they can be turned off system-wide | SHOULD | P2 |
| L5 | Grayscale toggle from the launcher (long-press or settings), using `WRITE_SECURE_SETTINGS` granted once via `adb`; fallback opens the system colour-correction screen | SHOULD | P2 |
| L6 | Grayscale on a schedule (for example during sleep and focus periods) | COULD | P2 |
| L7 | Hide tracked apps from search results (a "silent" flag). They can still be opened by typing the exact full name | COULD | P2 |
| L8 | End-of-day line on the home screen ("Today: 3 sessions, 34 min in tracked apps"), as plain text | COULD | P3 |

### 6.2 Session timers (core)

| ID | Feature | Priority | Phase |
|---|---|---|---|
| T1 | Opt-in tracked list: only marked apps are intercepted | MUST | MVP |
| T2 | "How long?" prompt on launching a tracked app: presets (default 2, 5, 10, 15 min), custom minutes (1–60), and Cancel. Cancel is the easiest button | MUST | MVP |
| T3 | Per-session countdown, independent of daily totals | MUST | MVP |
| T4 | Visible but unobtrusive timer: ongoing notification with a live countdown (MVP); optional small overlay pill (P2) | MUST / SHOULD | MVP / P2 |
| T5 | Warning at a configurable point (default 1 min left), shown as a heads-up notification (MVP) or overlay (P2) | MUST | MVP |
| T6 | End of session: calm full-screen "Time's up" screen, then return to home. Choices: *Go home* (default) or *Extend* | MUST | MVP |
| T7 | Extension with friction: +2 / +5 min, available only after a 15 s wait, max 2 extensions per session, each logged. The emergency override grants a fixed 5 min | MUST | MVP |
| T8 | Timer survives launcher process death and reboot (state persisted, deadline-based) | MUST | MVP |
| T9 | Calls are never interrupted: expiry is deferred while the phone rings, and while a call (phone or VoIP) is active unless the tracked app itself is confirmed to be in front; the call's sound is never touched | MUST | MVP |

### 6.3 Friction and awareness

| ID | Feature | Priority | Phase |
|---|---|---|---|
| F1 | Launch delay per app (0–30 s, for example 10 s), shown as a calm countdown before the prompt; cancel at any time | SHOULD | P2 |
| F2 | "Why are you opening this?" intent prompt per app: quick chips (Reply to message / Check someone / Specific search / Bored / Habit) plus free text; answers logged locally | SHOULD | P2 |
| F3 | Cooldown between sessions of the same app. MVP: after an *expired* session only, default 10 min (0–60). P2: after any session, per-app length. During cooldown, opening shows "Available again at 14:20" with the emergency override | MUST (simple) / SHOULD | MVP / P2 |
| F4 | "One more?" pause: re-opening an app within N minutes of a session ending shows the previous session's length and asks again (softer than cooldown) | COULD | P2 |
| F5 | Gentle periodic nudge during a session (for example every 5 min: "5 min in Instagram") | SHOULD | P2 |
| F6 | Scrolling detector: "You have been scrolling for X min" when scroll events continue for X minutes (needs accessibility) | COULD | P3 |
| F7 | Delayed loosening: changes that weaken a rule (longer presets, removing an app from tracking, raising caps, shortening cooldowns or delays, disabling schedules) take effect after 24 h; tightening is immediate | SHOULD | P2 |

### 6.4 Blocking

| ID | Feature | Priority | Phase |
|---|---|---|---|
| B1 | Exempt list (defaults in §4) that no rule can block; defaults can't be removed in under 24 h (F7) | MUST | MVP |
| B2 | Interception of apps opened **outside** the launcher (notification, link, Recents, other apps) via an AccessibilityService | SHOULD | P2 |
| B3 | Scheduled blocks (for example work 09:00–17:00 weekdays, sleep 23:00–07:00) per app or app group | SHOULD | P2 |
| B4 | Manual focus session ("block social for 60 min") from the home screen | SHOULD | P2 |
| B5 | Daily caps per app: max sessions/day and max minutes/day | SHOULD | P2 |
| B6 | Emergency override on every block: 30 s wait (configurable, minimum 10 s), resets if you leave; always succeeds; logged | MUST (with any block) | MVP (on expiry) / P2 |
| B7 | Fail-open behaviour: lost permission, crash or unknown state means allow, plus a one-line notice on the home screen | MUST | MVP |

### 6.5 In-app content filtering (experimental, high risk)

| ID | Feature | Priority | Phase |
|---|---|---|---|
| C1 | Per-app, per-rule toggles (Instagram, Facebook); each rule can be switched off without affecting others | COULD | P3 |
| C2 | Block short-video surfaces: leave Instagram Reels and Facebook Reels/Video tab when entered (navigate Back or Home tab) | COULD | P3 |
| C3 | Feed guard: when the home feed is shown, allow it only for a short budget or redirect to the Following feed; detect "Suggested for you", "Sponsored" and "Follow" labels to count or skip them | COULD (experimental) | P3 |
| C4 | Allow-list surfaces: DMs, notifications, profiles, Following feed are never interfered with | COULD | P3 |
| C5 | Rules stored as versioned JSON config, editable without a rebuild (import file) | COULD | P3 |
| C6 | Rule health: a rule that stops matching for N app opens disables itself and shows a notice | COULD | P3 |

**Not realistically achievable:** pixel-perfect *hiding* of individual suggested or sponsored
posts inside the native apps. Accessibility can't remove views from another app. Covering them
with overlays while the user scrolls is janky and breaks with every layout change. The closest
workable alternatives are leaving the surface (C2), budgeting the feed (C3), using the apps'
own Following/Friends feeds, or using the mobile websites in Firefox with uBlock Origin cosmetic
filters. That last option is outside this app and is documented in TECHNICAL_DESIGN §8.

### 6.6 Stats

| ID | Feature | Priority | Phase |
|---|---|---|---|
| S1 | Local session log (app, start, declared minutes, actual minutes, end reason, extensions) | MUST (data) | MVP |
| S2 | Stats screen: sessions per day and per app, declared vs actual time, extensions and overrides count | SHOULD | P3 (basic list in P2) |
| S3 | "Time saved" estimate: baseline-week average minus current average for tracked apps (labelled as an estimate) | COULD | P3 |
| S4 | Intent answers summary (which reasons lead to the longest sessions) | COULD | P3 |
| S5 | Shame-free presentation: no streaks, no red, no scores; just numbers | MUST (with S2) | P3 |
| S6 | Export to a local CSV file chosen by the user (Storage Access Framework) | COULD | P3 |

### 6.7 Settings and safety

| ID | Feature | Priority | Phase |
|---|---|---|---|
| X1 | Permission onboarding: one screen per permission, explaining why, with a deep link to the setting and a live status | MUST | MVP |
| X2 | Health line on the home screen when something needed is missing ("Timer paused: notifications off") | MUST | MVP |
| X3 | Settings screen for tracked apps, presets, warning time, exempt list | MUST | MVP |
| X4 | "Pause all enforcement for 1 h" behind the emergency override | SHOULD | P2 |
| X5 | Rule config import/export (JSON file, local) | COULD | P3 |

## 7. Constraints

- Kotlin only, GPL-3.0, no network, no proprietary SDKs (see the constitution).
- Builds with `./gradlew` and installs with `adb`; JDK 17; minSdk 24 (upstream); target SDK 36.
- Main device is Samsung One UI. One UI puts background apps into *Sleeping* or *Deep sleeping* states, and Android 13+ restricts accessibility for sideloaded apps. Both need one-time manual steps (DEV_SETUP §4).
- English UI on the phone, so content-filter text rules are written for English first.
- Upstream changes stay minimal and listed, so upstream merges stay easy.

## 8. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Enforcement bug blocks an essential app (calls, maps, bank) | Low | **Critical** | Hard-coded default exempt list checked first; fail-open on any error; expiry only sends home; deferred while in a call; JVM tests for exemptions |
| R2 | One UI kills the timer service or alarm (Sleeping apps, battery optimisation) | Medium | High | Deadline persisted and re-checked on every launcher resume and boot; onboarding asks for *Unrestricted* battery; health line if the service was killed |
| R3 | Android blocks starting the expiry screen from the background (Android 10+ background-activity-start limits) | Medium | High | Hold `SYSTEM_ALERT_WINDOW` (an allowed exemption), use a full-screen-intent notification as fallback, and in P2 the AccessibilityService (also exempt, can `GLOBAL_ACTION_HOME`). Check on the device in the first MVP task |
| R4 | Instagram/Facebook update breaks content-filter selectors | **High** | Low–Medium | Multiple signals per rule, self-disable plus notice, JSON rules, fallback to "leave Reels" only; filtering is optional and off by default |
| R5 | Accessibility settings greyed out ("restricted setting") for the sideloaded APK | High on first install | Medium | Onboarding explains *App info → ⋮ → Allow restricted settings*; MVP works without accessibility |
| R6 | Play Protect / Android developer-verification rules for sideloaded apps (rolling out from 2026 in some countries) block or warn on install | Medium | Medium | `adb install` remains the documented path; check current Google policy before each phase; keep the APK debug-signed with a stable key |
| R7 | I defeat it (switch launcher, uninstall, extend every time) | Medium | Medium | Extensions and overrides logged and shown; delayed loosening (F7); accept this: the tool is about friction |
| R8 | Battery drain from accessibility events or the foreground service | Low | Medium | Window-state events only by default; content events only while a filtered app is in front; foreground service only during an active session |
| R9 | Upstream merges conflict with our hooks | Medium | Low | Hooks are one-liners in listed files; our code in separate packages |
| R10 | Losing the debug signing key makes updates require uninstall (losing permissions and data) | Low | Medium | Keep a dedicated keystore outside the repo (documented in DEV_SETUP when created) |

## 9. Decisions from clarification (2026-11-21)

These are recorded in `specs/001-mvp-session-timer/spec.md` → Clarifications.

1. Session clock: wall clock; staying on the home screen for 2+ minutes ends the session as "left early".
2. Extensions: at most 2 per session, then only the override.
3. Default exempt categories: phone, SMS, maps, camera, clock, settings, plus messaging, banking/payments, authenticator and transport/ride apps picked during onboarding.
4. Cooldown: in the MVP, 10 min after an expired session only.
5. Override: grants a fixed 5-minute session.
