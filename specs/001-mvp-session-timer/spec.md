# Feature Specification: MVP session timer launcher

**Feature Branch**: `feature/screen-time-core`

**Created**: 2026-11-21

**Status**: Draft

**Input**: User description: "MVP of Calm Launcher: fork identity without network access, opt-in tracked apps, a 'How long?' launch prompt, a per-session timer with warning, a safe end-of-session action with friction-based extension and emergency override, and an exempt list that is never blocked. Source material: docs/PRODUCT_REQUIREMENTS.md (features L1–L3, T1–T9, B1, B6–B7, S1, X1–X3) and docs/USER_STORIES.md (MVP-labelled stories)."

> This spec covers only the **MVP** scope. Detailed rationale, metrics and the full backlog live in
> `docs/PRODUCT_REQUIREMENTS.md`. Story IDs in brackets (for example [US-T1]) point to
> `docs/USER_STORIES.md`, where the full acceptance criteria are written; scenarios below are the
> MVP subset that must pass.

## Clarifications

### Session 2026-11-21

- Q: When the user leaves a tracked app mid-session, how does the timer behave? → A: Wall clock with a 2-minute grace period; staying on the home screen for 2+ minutes ends the session as "left early"; returning within 2 minutes continues it.
- Q: How many times can a single session be extended after it expires? → A: At most 2 extensions per session; after that only the emergency override is offered.
- Q: Which kinds of apps are exempt by default besides phone, messages, maps, camera, clock and settings? → A: Messaging apps (e.g. WhatsApp, Telegram, Signal, Messenger), banking and payment apps, authenticator/2FA apps, and transport/ride apps; the exact apps are picked on the device during onboarding.
- Q: Does the MVP include a cooldown after an expired session? → A: Yes; a simple per-app cooldown applies only after a session ends as expired; default 10 minutes, configurable 0–60; the cooldown screen offers "Go home" and the emergency override.
- Q: What does the completed emergency override grant? → A: A fixed 5-minute session; each override is recorded.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Declare a duration before opening a tracked app (Priority: P1) [US-T1, US-L2]

When I open an app I've marked as a time sink, the launcher first asks "How long?". I pick a
preset or type minutes, or I cancel. Untracked and exempt apps open as before.

**Why this priority**: This is the core habit-breaker; without it nothing else in the MVP matters.

**Independent Test**: Mark one app as tracked, open it via home, drawer and search. The prompt appears every time; Cancel returns home; choosing 5 opens the app. Untracked apps open instantly.

**Acceptance Scenarios**:

1. **Given** Instagram is tracked, **When** I open it from a home label, swipe, the drawer, search auto-launch or Enter, **Then** the "How long?" screen appears before Instagram is shown.
2. **Given** the prompt, **When** I pick a preset (2, 5, 10, 15 min) or enter a custom value 1–60, **Then** Instagram opens and a session of that length starts.
3. **Given** the prompt, **When** I press Cancel or Back, **Then** Instagram does not open, I'm on the home screen, and the cancellation is recorded.
4. **Given** an app that is not tracked, or is exempt (exempt wins over tracked), **When** I open it, **Then** it opens immediately with no prompt.
5. **Given** a session for Instagram is running, **When** I open Instagram again from the launcher, **Then** it opens directly and the existing session continues.

---

### User Story 2 - See time left and get a warning (Priority: P1) [US-T2, US-T3]

While in a tracked app, I can glance at how much time is left without it covering the app, and
I get one heads-up before the end.

**Why this priority**: Awareness during the session is what makes the end feel fair rather than abrupt.

**Independent Test**: Start a 3-min session. The countdown is visible in the notification shade, a single warning appears at 1 min left, and the countdown disappears when the session ends.

**Acceptance Scenarios**:

1. **Given** a running session, **When** I open the notification shade, **Then** a silent, ongoing entry shows the app name and a live countdown.
2. **Given** a session longer than the warning lead (default 1 min), **When** the warning point is reached, **Then** exactly one heads-up says "1 minute left in [app name]", silent if the phone is in silent mode.
3. **Given** a session of 1 min or less, **When** it runs, **Then** no warning is shown.
4. **Given** the countdown entry, **When** I tap it and choose "End now", **Then** the session ends and I'm taken home.

---

### User Story 3 - A safe, calm end of session with effortful extension (Priority: P1) [US-T4, US-B7]

When time is up, a calm full-screen card appears over the app. "Go home" is the default. I can
extend only after a short wait and only a limited number of times. An emergency override is
always available but takes longer.

**Why this priority**: The end of the session is the second half of the core loop.

**Independent Test**: Run a 2-min session in a tracked app; at expiry the card appears; Go home works; Extend becomes available only after the wait; after the maximum number of extensions only the override remains, and it works.

**Acceptance Scenarios**:

1. **Given** a running session and the tracked app is in front, **When** the deadline passes, **Then** the time's-up card covers the app within 5 seconds.
2. **Given** the card, **When** I tap "Go home" or press Back, **Then** I'm on the home screen and the session is recorded as expired.
3. **Given** the card, **When** I tap "Extend", **Then** +2 / +5 choices appear but stay disabled during a visible 15-second countdown; choosing one returns me to the app with a new deadline.
4. **Given** the session has already been extended 2 times, **When** it expires, **Then** "Extend" is not offered, while "I really need this" (30-second wait, resets if I leave the card) is still offered and always succeeds with a 5-minute session.
5. **Given** any extension or override, **When** it is confirmed, **Then** it is recorded with app and time.
6. **Given** the deadline passes while a different app is in front, or it can't be determined which app is in front, **When** expiry happens, **Then** no card is shown; the session ends quietly (fail open).
7. **Given** a session for Instagram ended as expired at 14:00 and the cooldown is 10 minutes, **When** I open Instagram from the launcher at 14:05, **Then** I see "Instagram is available again at 14:10" with "Go home" (default) and "I really need this"; completing the override opens Instagram with a 5-minute session.
8. **Given** a session ended as "ended by user" or "left early", **When** I reopen the app, **Then** no cooldown applies and the duration prompt appears.

---

### User Story 4 - The phone always stays usable (Priority: P1) [US-B1, US-B6, US-T8]

Essential apps are never prompted, timed or blocked. Calls are never interrupted. I can always
get home and reach settings.

**Why this priority**: This is a constitutional, non-negotiable safety requirement (Fail Open).

**Independent Test**: With a session expiring during a phone call, nothing appears until the call ends. Exempt apps marked as tracked still open instantly. Pressing Home from any card always works.

**Acceptance Scenarios**:

1. **Given** a fresh install, **When** I view the exempt list, **Then** it already contains my phone, messages, maps, camera, clock and settings apps, and onboarding has asked me to pick my messaging, banking/payment, authenticator and transport/ride apps, which are added to the list.
2. **Given** an exempt app, **When** it is also marked tracked, **Then** it opens immediately.
3. **Given** a session reaches its deadline during a phone or internet call, **When** the call is active, **Then** no card appears; it appears within 5 seconds after the call ends, and only if the tracked app is still in front.
4. **Given** the lock screen is showing, **When** a deadline passes, **Then** nothing is drawn over the lock screen or its emergency-call button.
5. **Given** any card from this app is visible, **When** I press Home, **Then** I reach the home screen.
6. **Given** a card has been shown for 2 minutes without interaction, **When** that time passes, **Then** it removes itself.

---

### User Story 5 - Session survives reboot and process death (Priority: P2) [US-T6, US-T7]

The timer neither breaks nor extends when the phone reboots or the launcher is killed.

**Why this priority**: The MVP is still usable without it, but Samsung's battery management makes it a frequent real-world case.

**Independent Test**: Start a session; kill the launcher process; the warning and expiry still happen within 60 s of their times. Reboot before the deadline; the countdown returns. Reboot after the deadline; the session is closed as expired with no card.

**Acceptance Scenarios**:

1. **Given** a running session, **When** the launcher process is killed, **Then** expiry still happens within 60 seconds of the deadline.
2. **Given** a session with a deadline in the future, **When** the phone reboots and I unlock it, **Then** the countdown reappears with the correct remaining time.
3. **Given** a session whose deadline passed during a reboot, **When** I unlock, **Then** the session is recorded as expired at its deadline and no card is shown.
4. **Given** the timer could not run (battery restrictions), **When** I next return to the launcher, **Then** the session is closed correctly and one notice suggests checking battery settings.

---

### User Story 6 - Leaving the app early (Priority: P2) [US-T5]

If I finish early and go home, I'm not charged for time I didn't use, but a quick switch back
doesn't restart the prompt.

**Why this priority**: Fairness matters for the stats and for trust, but the core loop works without it.

**Independent Test**: Start a 10-min session, go home, wait past the grace period; the countdown disappears and the session is recorded as left early. Repeat but return within the grace period; the session continues.

**Acceptance Scenarios**:

1. **Given** a running session, **When** I return to the home screen and stay there for 2 minutes or more, **Then** the session ends as "left early" and the countdown disappears.
2. **Given** a running session, **When** I go home and return to the app within 2 minutes, **Then** the same session continues.
3. **Given** a running session, **When** I'm away from the app, **Then** the countdown keeps running (wall-clock time); time spent away within the grace period is not refunded.

---

### User Story 7 - Setup: fork identity, privacy, permissions, tracked list (Priority: P1) [US-L1, US-X1, US-X3]

The launcher installs as its own private app, explains each permission it needs, and lets me
choose tracked apps, presets and the warning time.

**Why this priority**: Required to use any of the above on the real phone.

**Independent Test**: Install the build next to upstream Olauncher; it has its own name and id; its permissions don't include network access; onboarding shows each permission's purpose and status; settings let me track an app and change presets.

**Acceptance Scenarios**:

1. **Given** the build, **When** installed, **Then** it is named "Calm Launcher", installs alongside upstream Olauncher, and requests no network access; the daily-wallpaper option is gone.
2. **Given** first launch, **When** onboarding runs, **Then** each needed permission gets one page with why, what is reduced without it, a button to the exact setting, and a live granted/not-granted status; every page can be skipped.
3. **Given** a skipped permission, **When** I'm on the home screen, **Then** a single plain-text line names what is reduced; everything else keeps working.
4. **Given** upstream settings, **When** I open it, **Then** one "Screen time" entry leads to: tracked apps, exempt apps, presets (1–60 min), warning lead time and extension wait.
5. **Given** double-tap-to-lock was in use, **When** the fork is installed and its accessibility toggle enabled, **Then** double-tap-to-lock still works.

### Edge Cases

- Tracked app opened from a notification, link or Recents: **not intercepted in the MVP** (needs the Phase 2 accessibility service). It is recorded as an "unplanned open" when usage access is granted. This is a documented limitation [US-B2].
- Tracked app uninstalled while a session runs: the session ends as interrupted; the countdown disappears.
- Two tracked apps: starting a session for app B while app A's session runs ends A's session as "left early" (one foreground app at a time).
- User changes the system clock mid-session: the deadline is re-evaluated; no protection against deliberate manipulation is attempted.
- Overlay permission missing at expiry: a high-priority "Time's up" notification replaces the card; tapping it goes home. Nothing blocks.
- Notification permission missing: no countdown or warning is visible; the session and expiry still work; the home line names the reduction.
- Work-profile copy of a tracked app: treated as a separate app (tracked per profile).
- Search auto-launch selects a tracked app with a single keystroke: the prompt still appears.

## Requirements *(mandatory)*

### Functional Requirements

**Identity and privacy**
- **FR-001**: The app MUST install with its own identity (name "Calm Launcher") so it can coexist with upstream Olauncher.
- **FR-002**: The app MUST NOT request network access and MUST NOT contain the daily-wallpaper download or upstream promotional links.
- **FR-003**: All MVP data (tracked list, exempt list, settings, session records, events) MUST be stored only on the device and MUST be excluded from cloud backup.

**Tracking and prompt**
- **FR-004**: Users MUST be able to mark any launchable app (per user profile) as tracked or exempt.
- **FR-005**: Every launch of a tracked, non-exempt app initiated from the launcher (home label, swipe action, drawer, search auto-launch, Enter key, clock/calendar/screen-time shortcuts if set to that app) MUST show the duration prompt before the app opens.
- **FR-006**: The prompt MUST offer configurable presets (default 2, 5, 10, 15 min), a custom value from 1 to 60 minutes, and Cancel; Cancel and Back MUST leave the app unopened and return home.
- **FR-007**: An exempt app MUST never be prompted, timed, covered or blocked, regardless of any other setting.
- **FR-008**: If a session for the app is already running, a new launch MUST open the app without a prompt.

**Session timer**
- **FR-009**: A session MUST have a declared duration and a fixed deadline; it MUST persist across launcher process death and device reboot.
- **FR-010**: A running session MUST be visible as a silent, ongoing countdown outside the app's own screen (notification shade), and MUST offer "End now".
- **FR-011**: The system MUST show exactly one warning at a configurable lead time (default 60 s) before the deadline, unless the session is shorter than or equal to the lead time.
- **FR-012**: At the deadline, if and only if the tracked app is confirmed to be in front, the system MUST show a full-screen time's-up card with "Go home" as the default action.
- **FR-013**: "Extend" (+2 or +5 minutes) MUST become selectable only after a visible wait (default 15 s) and MUST be offered at most 2 times per session.
- **FR-014**: An emergency override MUST be available on every time's-up card and cooldown screen, require a wait of at least 10 s (default 30 s) that resets when the screen is left, always succeed, and grant a fixed 5-minute session.
- **FR-015**: The session countdown MUST run on wall-clock time; remaining on the home screen for at least the grace period (default 2 minutes) MUST end the session as "left early", and returning to the app within it MUST continue the same session.
- **FR-028**: After a session ends as expired, launcher-initiated opens of that app MUST show a cooldown screen until the cooldown (default 10 minutes, configurable 0–60 per app; 0 disables) has passed; sessions ending any other way MUST NOT start a cooldown.
- **FR-016**: When a deadline passes while the device was off or the app was not in front, the session MUST be closed with the deadline as its end time and no card.

**Safety (fail open)**
- **FR-017**: The default exempt list MUST include the current phone, SMS/messages, maps, camera, clock and system settings apps, resolved on the device; onboarding MUST ask the user to add their messaging, banking/payment, authenticator and transport/ride apps; users can add more at any time.
- **FR-018**: No card or prompt from this app MUST be shown while a phone or internet call is active or the lock screen is showing; expiry is deferred until both clear.
- **FR-019**: Any error or unknown state in tracking, timing or enforcement MUST result in the app being allowed and the error being recorded locally.
- **FR-020**: Every card MUST be dismissible with Home and Back, and MUST remove itself after 2 minutes without interaction.
- **FR-021**: Removing a default exempt app MUST require an explicit confirmation stating that the app will then be subject to tracking rules.

**Setup and visibility**
- **FR-022**: First launch MUST present a skippable onboarding with one page per permission (purpose, reduction if skipped, deep link, live status), re-openable from settings.
- **FR-023**: When a needed permission is missing or the timer was interrupted, the home screen MUST show one plain-text health line naming the reduction.
- **FR-024**: Settings MUST let the user edit tracked apps, exempt apps, presets, warning lead time and extension wait; upstream settings MUST gain only a single entry to reach them.
- **FR-025**: Existing upstream features (home labels, search, gestures, double-tap-to-lock, hidden apps, Private Space) MUST keep working.

**Records**
- **FR-026**: Each session MUST be recorded with app, start, declared minutes, actual minutes, end reason (expired, ended by user, left early, cancelled, interrupted) and number of extensions.
- **FR-027**: Prompt cancellations, extensions, overrides, card time-outs, fail-open errors and (if usage access is granted) unplanned opens MUST be recorded as events.

### Key Entities

- **Tracked app**: an app (package + user profile) the user opted into tracking; holds its presets and cooldown minutes.
- **Exempt app**: an app that must never be restricted; source is "default" (resolved on device) or "user".
- **Session**: one declared use of a tracked app; start, declared duration, deadline, end time, end reason, extensions. At most one is active.
- **Event**: a timestamped record of something notable (cancel, extension, override, error, unplanned open, card time-out).
- **Settings**: presets, warning lead time, extension wait, extension limit (2), override wait, override grant (5 min), grace period (2 min), default cooldown (10 min).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of launcher-initiated opens of tracked apps show the duration prompt (verified across all 7 entry points listed in FR-005).
- **SC-002**: The time's-up card appears within 5 seconds of the deadline in at least 95% of sessions in which the tracked app is in front and no call is active (measured over one week of real use).
- **SC-003**: Zero occurrences of an exempt app being delayed, prompted or covered, and zero interrupted calls, during a 2-week trial.
- **SC-004**: After a forced process kill or a reboot, the session ends within 60 seconds of its deadline (or is closed correctly on next unlock) in 100% of scripted device tests.
- **SC-005**: In weeks 3–4 of use, at least 80% of sessions end within 1 minute of the declared duration, and extensions plus overrides average at most 1 per day.
- **SC-006**: Time spent in tracked apps falls by at least 40% compared with the baseline week (see PRODUCT_REQUIREMENTS §5).
- **SC-007**: Opening any untracked app takes no longer than it did with upstream Olauncher (no visible delay).

## Assumptions

- Single user on one Samsung phone (One UI, Android 14–16), English interface; sideloaded via adb, not distributed on any store.
- The user grants notifications, display-over-other-apps, usage access and unrestricted battery during onboarding; without them the feature degrades as described (fail open), not breaks.
- Launches from outside the launcher are out of MVP scope (Phase 2).
- Launch delays, intent prompts, "one more?" prompts, schedules, caps, delayed loosening, stats screens and content filtering are out of MVP scope; the MVP records the data they will need. A simple post-expiry cooldown is in scope (FR-028).
- Defaults: presets 2/5/10/15 min, warning 60 s before end, extension wait 15 s, extension sizes +2/+5 min, max 2 extensions per session, override wait 30 s (minimum 10 s), override grants 5 min, grace period 2 min, cooldown 10 min after expiry, card self-removal after 2 min.
- "Confirmed to be in front" may rely on the device's usage history; if that is unavailable, the expiry is shown as a notification instead of a card.
