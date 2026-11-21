# User stories: Calm Launcher

Format: story, then numbered Given/When/Then acceptance criteria.
**Priority**: MUST / SHOULD / COULD, matching PRODUCT_REQUIREMENTS §6. **Label**: MVP or Later (P2 or P3).
Feature IDs (T2, B6, …) refer to PRODUCT_REQUIREMENTS.md. "Tracked app" means an app on the
opt-in list. "Exempt app" means an app on the exempt list.

Defaults used below (configurable): presets 2/5/10/15 min, warning at 1 min left, extension wait
15 s, max 2 extensions per session, emergency override wait 30 s.

---

## Epic L: Launcher

### US-L1: Own identity, no network (MUST, MVP; L1, L2)
As a user, I want the fork to install as its own app without network access, so that it can sit
next to the original Olauncher and cannot leak data.
1. Given a fresh build, when I install it with `adb install`, then it appears as "Calm Launcher" with application id `app.calmlauncher` (debug: `app.calmlauncher.debug`), and an installed upstream Olauncher is unaffected.
2. Given the built APK, when I list its permissions (`apkanalyzer manifest permissions`), then `android.permission.INTERNET` is not present.
3. Given settings, when I look for "daily wallpaper", then the option is gone, and existing plain-colour wallpapers still work.
4. Given double-tap-to-lock was enabled, when the app id changes, then double-tap-to-lock still works after re-enabling the accessibility toggle.

### US-L2: Text-only home with search (MUST, MVP; L3)
As a user, I want a home screen of a few text labels with everything else behind search, so
that I'm not drawn in by icons.
1. Given the home screen, when it is shown, then there are no app icons, badges or counts. This is upstream behaviour and must not regress.
2. Given I swipe up and type, when one app matches, then it is launched through the same launch path as a home tap, so tracked-app rules apply (see US-T1).

### US-L3: Grayscale toggle (SHOULD, Later P2; L5, L6)
As a user, I want to switch the screen to grayscale from the launcher, so that colourful feeds
are less attractive.
1. Given I granted `WRITE_SECURE_SETTINGS` once via `adb`, when I tap the grayscale toggle, then the system colour filter switches to grayscale within 1 s, and tapping again restores colour.
2. Given that permission is missing, when I tap the toggle, then the app opens the system colour-correction screen and shows the one-line `adb` command needed.
3. Given a grayscale schedule 23:00–07:00, when 23:00 arrives, then grayscale turns on, and at 07:00 it returns to my previous setting.

### US-L4: Hide tracked apps from search (COULD, Later P2; L7)
As a user, I want time-sink apps hidden from partial search, so that I only find them on purpose.
1. Given Instagram is "silent", when I type "ins", then Instagram is not listed.
2. Given Instagram is "silent", when I type the full name "instagram", then it is listed and opens through the normal prompt.

---

## Epic T: Session timers

### US-T1: Prompt on opening a tracked app (MUST, MVP; T1, T2)
As a user, I want to be asked "How long?" when I open a tracked app, so that every session has
a declared length.
1. Given Instagram is tracked, when I open it from home, swipe, drawer, search auto-launch or Enter, then a "How long?" screen appears before Instagram opens.
2. Given the prompt, when I choose 5 min, then Instagram opens and a 5-minute session starts.
3. Given the prompt, when I enter a custom value, then values from 1 to 60 are accepted and others are rejected with an inline message.
4. Given the prompt, when I press Cancel or Back, then Instagram does not open, I'm on the home screen, and a "cancelled" event is logged.
5. Given an app is **not** tracked, when I open it, then it opens immediately with no prompt.
6. Given an app is exempt, when I open it, then it opens immediately, even if it is also marked tracked. Exempt wins.
7. Given a session for Instagram is already running, when I open Instagram again from the launcher, then it opens directly with no new prompt, and the existing countdown continues.

### US-T2: Visible countdown (MUST, MVP; T3, T4)
As a user, I want to see how much time is left without it dominating the screen, so that I stay
aware.
1. Given a running session, when I pull down the notification shade, then an ongoing, silent notification shows "Instagram · 3:42 left" counting down live.
2. Given a running session, when the session ends, then the notification is removed within 2 s.
3. Given the notification, when I tap it, then the app shows session details with an "End now" button. Tapping it ends the session and goes home.

### US-T3: Warning before the end (MUST, MVP; T5)
As a user, I want a heads-up before the session ends, so that I can finish my message.
1. Given a 5-min session and a warning at 1 min, when 4:00 has passed, then a single heads-up notification says "1 minute left in Instagram". It makes no sound if the phone is in silent mode.
2. Given a session of 1 min or less, when it starts, then no warning is shown.

### US-T4: End of session (MUST, MVP; T6, T7)
As a user, I want a clear end to each session that takes me home but lets me extend with some
effort, so that sessions stay short without causing panic.
1. Given a running session, when the deadline passes, then a calm full-screen "Time's up: 5 min in Instagram" screen appears over the app within 5 s.
2. Given the time's-up screen, when I tap "Go home" or press Back, then the home screen is shown, the session is logged as `EXPIRED`, and opening Instagram again starts a new prompt.
3. Given the time's-up screen, when I tap "Extend", then +2 / +5 buttons appear and stay disabled for a visible 15 s countdown. After that, choosing one returns me to Instagram with a new deadline.
4. Given a session has already been extended twice, when it expires, then "Extend" is not offered. The emergency override ("I really need this", 30 s wait) is still offered and always works.
5. Given any extension or override, when it is confirmed, then it is logged with the time and app.

### US-T5: Leaving the app early (MUST, MVP)
As a user, I want the session to stop when I'm done early, so that I'm not charged for time I
didn't use. The exact rule is settled in `/speckit-clarify`.
1. Given a running session, when I return to the home screen and stay away longer than the grace period (proposed: 2 min), then the session ends as `LEFT_EARLY` and the notification disappears.
2. Given a running session, when I go home and come back via Recents within the grace period, then the same session continues.

### US-T6: Phone reboots mid-session (MUST, MVP; T8; edge case)
As a user, I want a reboot to neither extend nor break my session.
1. Given a session with a deadline at 14:10, when the phone reboots and I unlock it at 14:05, then the countdown notification is restored showing about 5 min left.
2. Given the same session, when I unlock at 14:20, then the session is logged as `EXPIRED` (at its deadline), no time's-up screen is shown, and opening Instagram prompts again.
3. Given the boot receiver could not run (One UI blocked it), when I next open the launcher, then the same reconciliation happens on launcher resume.

### US-T7: Launcher process killed while a timer runs (MUST, MVP; T8; edge case)
As a user, I want the timer to work even if Android kills the launcher.
1. Given a running session, when the launcher process is killed (`adb shell am kill app.calmlauncher.debug`), then expiry still happens within 60 s of the deadline. This works through the persisted deadline, an exact alarm, and the service being restarted.
2. Given the process was killed and no alarm fired (permission missing or One UI deep sleep), when I return to the launcher after the deadline, then the session is closed as `EXPIRED` with the correct end time, and the home screen shows "Timer was interrupted, check battery settings" once.

### US-T8: Never interrupt a call (MUST, MVP; T9; edge case)
As a user, I want calls never to be interrupted by a timer.
1. Given a session expires while a phone call or VoIP call is active (audio mode in-call or in-communication), when the deadline passes, then no time's-up screen appears until the call ends. It then appears within 5 s of the call ending, if I'm still in the tracked app.
2. Given an incoming call during a session, when the dialer comes to the front, then nothing from the app covers the call screen.

---

## Epic F: Friction and awareness

### US-F1: Launch delay (SHOULD, Later P2; F1)
As a user, I want a short wait before a tracked app opens, so that the autopilot urge passes.
1. Given Instagram has a 10 s delay, when I open it, then a quiet countdown "Opening Instagram in 10…" shows with a prominent "Never mind" button, followed by the "How long?" prompt.
2. Given the countdown, when I leave the screen, then the countdown resets and nothing opens.

### US-F2: Intent prompt (SHOULD, Later P2; F2)
As a user, I want to state why I'm opening an app, so that I notice habit opens.
1. Given intent prompts are on for Instagram, when the prompt shows, then I pick a chip (Reply to message / Check someone / Specific search / Bored / Habit) or type text, and only then the duration options appear.
2. Given I answered, when the session ends, then the answer is stored with the session and visible in stats.

### US-F3: Cooldown (MUST, MVP: after expiry only, 10 min default; SHOULD, Later P2: after any session; F3)
As a user, I want a pause between sessions of the same app, so that I don't chain sessions.
1. Given a 30 min cooldown and a session that expired at 14:00, when I open Instagram at 14:10, then I see "Instagram is available again at 14:30" with "Go home" (default) and the emergency override.
2. Given the emergency override is completed, when it finishes, then the prompt appears and the override is logged.

### US-F4: "One more?" pause (COULD, Later P2; F4)
1. Given a session ended less than 10 min ago, when I reopen the app, then the prompt first shows "You were just here for 12 min. Open again?" with No as the default.

### US-F5: In-session nudges (SHOULD, Later P2; F5, F6)
As a user, I want gentle reminders during long sessions, so that time doesn't slip.
1. Given nudges every 5 min, when a 15-min session runs, then at 5 and 10 min a silent heads-up says "5 min in Instagram" and "10 min in Instagram".
2. Given accessibility is enabled and scrolling continues for 3 min in a feed (COULD, P3), then a single "You've been scrolling for 3 minutes" overlay appears for 3 s.

### US-F6: Delayed loosening (SHOULD, Later P2; F7)
As a user, I want rule changes that make things easier to take a day to apply, so that a weak
moment can't undo my setup.
1. Given I remove Instagram from the tracked list, when I save, then the settings show "Takes effect tomorrow at 14:32", and until then Instagram is still tracked.
2. Given I add an app to tracking or shorten presets, when I save, then the change applies immediately.
3. Given a pending loosening, when I cancel it, then nothing changes.

---

## Epic B: Blocking

### US-B1: Exempt list (MUST, MVP; B1)
As a user, I want essential apps never to be blocked, so that I'm always reachable and safe.
1. Given a fresh install, when I open the exempt list, then it includes the installed apps handling `ACTION_DIAL`, `ACTION_SENDTO sms:`, maps (`geo:` intent), my camera, clock, settings, and any apps I add (bank, authenticator).
2. Given an app is exempt, when any rule (tracking, schedule, cap, cooldown, content filter) applies to it, then the app still opens immediately. This is covered by a unit test for each rule type.
3. Given I try to remove a default exempt app, when I save, then the removal is a loosening and is delayed 24 h (US-F6).

### US-B2: Apps opened from notifications or links (SHOULD, Later P2; B2; edge case)
As a user, I want tracked apps opened from outside the launcher to be handled the same way, so that
notifications aren't a backdoor.
1. Given the AccessibilityService is on and Instagram is tracked, when I tap an Instagram notification, then within 1 s the "How long?" screen covers Instagram. Choosing a duration continues where the notification pointed; Cancel sends me home.
2. Given a link opens the YouTube app from the browser, then the same applies.
3. Given the AccessibilityService is off, then these launches are not intercepted, and they count as "unplanned opens" in stats via UsageStats. This is the MVP behaviour and is documented as a limitation.

### US-B3: Scheduled blocks (SHOULD, Later P2; B3)
1. Given a "Work" schedule Mon–Fri 09:00–17:00 covering Instagram and YouTube, when I open Instagram at 10:00, then I see "Blocked until 17:00 (Work)" with "Go home" and the emergency override.
2. Given a session is running at 09:00 when the schedule starts, then the session gets a 1-min warning and expires at 09:01.

### US-B4: Manual focus session (SHOULD, Later P2; B4)
1. Given I start "Focus 60 min" from the home screen, when I open any tracked app during it, then it is blocked as in US-B3, and the home screen shows "Focus until 15:40".

### US-B5: Daily caps (SHOULD, Later P2; B5)
1. Given Instagram has max 4 sessions/day and I've had 4, when I open it, then "Daily limit reached (4/4)" is shown with the override.
2. Given max 30 min/day and 26 min used, when the prompt shows, then options above 4 min are disabled.

### US-B6: Emergency call during a block (MUST, MVP + P2; edge case)
As a user, I want to call emergency services or anyone at any time.
1. Given any block or time's-up screen is visible, when I press Home, then I reach the home screen, and the dialer (exempt) opens with no prompt.
2. Given the lock screen, when I use the emergency call button, then nothing from this app appears. We never draw over the keyguard.
3. Given a focus session, when an incoming call arrives, then it rings and shows normally.

### US-B7: Emergency override (MUST, MVP on expiry; P2 on blocks; B6)
1. Given any blocking screen, when I tap "I really need this", then a 30 s countdown starts. Leaving the screen resets it, and at 0 the app opens with a 5-min session.
2. Given any state of the app, including errors, then the override button is never hidden or disabled.

---

## Epic C: In-app content filtering (experimental)

### US-C1: Leave Reels automatically (COULD, Later P3; C1, C2)
As a user, I want Instagram Reels to close when I land in them, so that I can use DMs without
getting pulled into short videos.
1. Given the "Instagram: block Reels" rule is on and the service is enabled, when the Reels viewer becomes visible (tab or a reel shared in a DM), then within 1 s the app navigates Back once and shows a 2-s toast "Reels are off".
2. Given the rule is off, when I enter Reels, then nothing happens.
3. Given I open a reel shared in a DM, when the rule fires, then I'm back in the DM thread, not thrown out of Instagram.

### US-C2: Feed guard (COULD, Later P3; C3, C4)
As a user, I want the suggested and sponsored parts of the feed to stop pulling me in, while
posts from people I follow stay reachable.
1. Given "Instagram: feed guard" is on, when the home feed is visible for more than the feed budget (for example 2 min per session), then a calm overlay suggests "Switch to Following feed", with buttons Switch (opens it) and Close.
2. Given "Suggested for you" or "Sponsored" text appears on screen, when it is detected, then the count is recorded (no screen text stored), and optionally the feed scrolls past it once (experimental sub-toggle).
3. Given I'm in DMs, notifications or a profile, when any filter rule is on, then nothing is changed.

### US-C3: Rule breaks after an app update (COULD, Later P3; C6; edge case)
As a user, I want a broken rule to switch itself off and tell me, instead of misbehaving.
1. Given a rule hasn't matched any of its signals in the last 10 Instagram sessions while its "presence anchor" (for example the bottom tab bar) was seen, then the rule is marked `STALE`, stops acting, and the home screen shows "Instagram Reels rule stopped matching (app updated?)".
2. Given a rule throws an error while evaluating, then it is disabled for the rest of the day, and Instagram works normally.

### US-C4: Update rules without rebuilding (COULD, Later P3; C5)
1. Given a JSON rules file on the phone, when I import it in settings, then it is validated (schema and version), the previous version is kept for rollback, and the new rules apply on the next app open.

---

## Epic S: Stats

### US-S1: Session log (MUST, MVP; S1)
1. Given any session ends (expired, ended early, left, cancelled at the prompt, extended, overridden), then a record with app, start, declared minutes, actual minutes, end reason and extension count is stored locally.
2. Given the app is uninstalled, then all data is removed. There are no backups: `allowBackup` is false for our data.

### US-S2: Stats screen (SHOULD, Later P3; S2–S5)
As a user, I want to see simple numbers, so that I know if this works.
1. Given a week of data, when I open stats, then I see per day: sessions, minutes in tracked apps, extensions, overrides, unplanned opens. Per app: sessions and average declared vs actual length.
2. Given a baseline week exists, then "time saved (estimate)" is shown, with its method shown on tap.
3. Given any figure, then no streaks, scores or red warnings are used.

---

## Epic X: Settings and safety

### US-X1: Permission onboarding (MUST, MVP; X1)
As a user, I want to understand each permission before I grant it.
1. Given first launch after the update, when onboarding runs, then each needed permission gets one screen with "why", "what breaks without it", a button to the exact settings page, and live status. MVP permissions: notifications, display over other apps, alarms & reminders, battery *Unrestricted*, and usage access (optional, for stats).
2. Given I skip a permission, then the app still works in reduced form (fail open), and the home health line names what is missing.

### US-X2: Accessibility disabled by the system (SHOULD, Later P2; edge case)
As a user, I want to know when the system has switched off the accessibility service, and the
phone must keep working normally.
1. Given One UI or an update disables the service, when I next return to the home screen, then a single health line shows "Outside-launcher blocking is off. Turn on", linking to accessibility settings.
2. Given the service is off, then launcher-initiated prompts and timers still work, and nothing is blocked because the service is missing.
3. Given "restricted settings" greys out the toggle, then the onboarding page shows the *App info → ⋮ → Allow restricted settings* steps.

### US-X3: Configure tracking (MUST, MVP; X3)
1. Given settings → "Screen time", then I can mark apps as tracked or exempt, edit presets (1–60 min), the warning time, and the extension wait. Each change is persisted immediately (tightening) or delayed (loosening, P2).
2. Given the upstream settings screen, then it has a single entry "Screen time" that opens our screen. No other upstream settings change.

### US-X4: Pause all enforcement (SHOULD, Later P2; X4)
1. Given I complete the emergency override from settings, when I choose "Pause 1 h", then no prompts, blocks or filters apply for 1 h. The home screen shows "Paused until 16:10", and the pause is logged.
