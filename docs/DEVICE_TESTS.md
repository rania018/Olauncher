# Device tests: MVP session timer

The manual script for the Samsung phone, from [quickstart §3](../specs/001-mvp-session-timer/quickstart.md).
Fill in **Result** (pass / fail + a short note) and **Date** after each run. A row passes only on the
real phone; the JVM tests cover the logic, not Android's behaviour.

Setup: [DEV_SETUP.md](DEV_SETUP.md) §6 (install, permissions, log filter).

## 1. Automated checks (no device)

| Check | Command | Result | Date |
|---|---|---|---|
| Engine tests (state machine, rules, invariants 1–10, call rule, leaving) | `./gradlew :engine:test` | pass: 155 tests | 2026-11-24 |
| App JVM tests + upstream tests | `./gradlew test` | pass: 66 tests per build variant | 2026-11-24 |
| Debug build | `./gradlew assembleDebug` | pass | 2026-11-24 |
| Instrumented test APK compiles | `./gradlew assembleDebugAndroidTest` | pass | 2026-11-21 |
| No network permission | `apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk` | pass: no `INTERNET`, no `ACCESS_NETWORK_STATE` | 2026-11-24 |
| Room DAO test on the phone (T066) | `./gradlew connectedDebugAndroidTest` | not run yet | |

## 2. Platform assumptions to confirm first (T019, research R4/R5)

These were meant to be checked with a throw-away spike before building on them. The full build exercises
the same behaviour, so check them with the real flow:

| # | Assumption | How | Result | Date |
|---|---|---|---|---|
| a | From the time's-up overlay, "Go home" reaches the home screen on One UI (Android 14+) | Row 2 below, tap **Go home** | | |
| b | The `specialUse` foreground service keeps the countdown and survives `adb shell am kill` | Rows 2 and 9 below | | |

If (a) fails, stop and revisit research R5 with the owner. The fallback (HOME from a notification
`PendingIntent` with `ActivityOptions.setPendingIntentBackgroundActivityStartMode`) would only change
`app/src/main/java/app/calmlauncher/enforce/GoHome.kt` and the card's "Go home" path.

## 3. Scenario checks

| # | Steps | Expected | Spec | Result | Date |
|---|---|---|---|---|---|
| 1 | Track Instagram; open it via home label, swipe, drawer, search auto-launch, Enter | Prompt each time; Cancel → home | US1 | | |
| 2 | Choose 2 min | Countdown in shade; warning at 1:00 left; card at 0 within 5 s | US1–3 | | |
| 3 | On the card: Extend → wait → +2 (twice), let it expire again | Third expiry shows no Extend; override works and gives 5 min | US3 | | |
| 4 | After "Go home", reopen within 10 min | Cooldown screen with time; override works | US3 sc. 7 | | |
| 5 | Start 5 min; go home and stay 2+ min | Countdown disappears; session `LEFT_EARLY` | US6 | | |
| 6 | Start 2 min; at 1:30 place a phone call (or WhatsApp call) and stay on the call screen past the deadline | No card and no pill over the call screen; card ≤ 5 s after hang-up if Instagram is in front again | US4 | | |
| 7 | Start 2 min; lock the screen past the deadline; unlock | No card over the keyguard; card after unlock only if Instagram is in front; session closed as expired | US4, US5 | | |
| 8 | Try to mark the dialer as tracked; mark WhatsApp tracked, then exempt | Dialer: "always exempt", never tracked, opens instantly. WhatsApp: turning Exempt on turns Track off, and the reverse | US4 | | |
| 9 | Start 3 min; `adb shell am kill app.calmlauncher.debug` | Expiry still within 60 s of the deadline | US5 | | |
| 10 | Start 10 min; `adb reboot`; unlock at once | Countdown restored with correct time | US5 | | |
| 11 | Start 2 min; `adb reboot`; unlock after 3 min | Session closed as expired; no card | US5 | | |
| 12 | `adb shell appops set app.calmlauncher.debug SYSTEM_ALERT_WINDOW deny`; let a session expire | Fallback notification instead of card; health line mentions it | Edge cases | | |
| 13 | Double-tap home (after enabling the accessibility toggle) | Screen locks (upstream feature intact) | US7 | | |
| 14 | Settings → Time left on screen: Pill, start 3 min; then Bar; then Off | Pill: solid accent label with an emptying ring, clearly visible over a white app, draggable, tap opens End now, amber at the warning; bar: 6 dp accent line visible over white and black apps, never blocks touches, amber at the warning; Off shows nothing | US2 sc. 5, FR-029 | | |
| 15 | Play a video in Instagram, let the session expire | Pop-up over the dimmed video; video and sound pause; touches outside the card do nothing; Extend resumes the app | US3 sc. 1, FR-012 | | |
| 16 | Open a tracked app | Prompt shows the app icon; session lengths on one line as chips | US1, FR-024 | | |
| 17 | Open Screen time limits | Grouped cards, steppers save at once, chips remove/add session lengths, app icons, tracked apps first, tap a tracked app → its own settings | FR-024 | | |
| 18 | Accent colour: Pink, then Phone's colour; change One UI Color palette | Every Calm surface and the notifications use the colour at once; Phone's colour follows the palette; home text stays black/white | FR-030 | | |
| 19 | Track Instagram; open it from the recent-apps list | Prompt over Instagram within 1 s; Cancel → home; choosing 2 min leaves Instagram where it was and starts the session | US1 sc. 6, FR-031, SC-008 | | |
| 20 | Open a tracked app from a link in another app, and from one of its notifications; repeat during its cooldown | Prompt each time; during the cooldown, the cooldown screen instead | FR-031, FR-028 | | |
| 21 | Start 5 min in Messenger; follow a link into another app and stay 2+ min | Messenger's session ends `LEFT_EARLY`; its pill and notification disappear | US6 sc. 4, FR-015 | | |
| 22 | Start 5 min; go home, open an exempt app, stay 2+ min | Session ends `LEFT_EARLY` | US6 sc. 1 | | |
| 23 | Start 5 min; switch to another app for 30 s and back; inside the tracked app open the keyboard and pull down the notification shade | Session continues; the pill hides while away and comes back; keyboard and shade do not hide the pill or start the grace | US6 sc. 2, FR-029 | | |
| 24 | Turn the app-limits toggle off; repeat rows 19 and 22 | Recents open not prompted (logged as unplanned open); health line names it; pill shows on every screen; row 22 still ends the session after 2 min (usage history) | FR-031 fallback, FR-015 | | |
| 25 | App-limits toggle on. Start a WhatsApp call, go home and open Instagram: choose 2 min. Stay in Instagram past the deadline with the call running. Then repeat and switch to the call screen before the deadline | Prompt as usual; pill visible over Instagram during the call; no warning sound; time's-up card at the deadline, the call's sound unchanged, "Go home" works. On the call screen: no pill, no card. With the toggle off: no pill or card until hang-up | US4 sc. 7, FR-018 | | |

### Rows 19–24 detail: the app-limits toggle (T091, T095, T098, T101)

- Turn the toggle on first: **Settings → Accessibility → Installed apps → Calm Launcher app limits**, or the
  "App limits" onboarding page. An install that already went through onboarding opens that page once.
- SC-007 with the toggle on: an untracked app opened from the launcher and from Recents shows no visible delay.
- Onboarding page "App limits": why, what is reduced, the button to Accessibility, a live On / Off status,
  and the restricted-settings tip.
- Row 23: watch the log while switching; the grace starts on `ForegroundChanged` and ends when the app is back.
  ```sh
  adb logcat -s CalmLauncher:* | grep -E "ForegroundChanged|GraceElapsed|LauncherResumed"
  ```

### Row 25 detail: the 2026-11-23 run that led to it

Before the fix, a tracked app used during a WhatsApp call showed no pill and no time's-up card until
hang-up: every call hid them, whichever app was in front. In that run Instagram also opened from the
launcher with **no prompt**. The launcher code skips the prompt for a tracked app only while its
session is still running (FR-008), and with the pill hidden nothing showed that. The phone's own
records fit this (no error event ever logged; Instagram sessions were running that evening; the
log shows `InCallOrLocked` at an expiry during the call), but the minute of that open is not known.
A debug build now logs the reason, so the next run can settle it:

```sh
adb logcat -s CalmLauncher:* | grep "no prompt for"     # e.g. "no prompt for com.instagram.android: RUNNING"
```

### Row 1 detail: the 7 launcher entry points (T034, SC-001)

| Entry point | Prompt shown | Date |
|---|---|---|
| Home screen label | | |
| Swipe left app | | |
| Swipe right app | | |
| App drawer tap | | |
| Search auto-launch (single result) | | |
| Search + Enter | | |
| Clock / date / screen-time text set to the tracked app | | |

### Also check while doing the rows above

- An untracked app opens with no visible delay (SC-007).
- Cancel, Back and Home on the prompt all leave the app closed, and the cancel is logged.
- Pressing Home while the card is up goes home and removes the card.
- The card removes itself after 2 minutes untouched.
- Upstream features still work (FR-025): home labels, search, gestures, hidden apps, Private Space.

## 4. Useful inspection

```sh
adb logcat -s CalmLauncher:*                                       # every state change and effect
adb shell dumpsys activity services app.calmlauncher.debug | grep -i SessionService
adb shell dumpsys alarm | grep -A3 calmlauncher
adb shell run-as app.calmlauncher.debug ls databases               # calm.db
```
