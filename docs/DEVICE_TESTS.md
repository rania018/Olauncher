# Device tests: MVP session timer

The manual script for the Samsung phone, from [quickstart §3](../specs/001-mvp-session-timer/quickstart.md).
Fill in **Result** (pass / fail + a short note) and **Date** after each run. A row passes only on the
real phone; the JVM tests cover the logic, not Android's behaviour.

Setup: [DEV_SETUP.md](DEV_SETUP.md) §6 (install, permissions, log filter).

## 1. Automated checks (no device)

| Check | Command | Result | Date |
|---|---|---|---|
| Engine tests (state machine, rules, invariants 1–6) | `./gradlew :engine:test` | pass: 97 tests | 2026-11-21 |
| App JVM tests + upstream tests | `./gradlew test` | pass: 31 Calm + 11 upstream tests | 2026-11-21 |
| Debug build | `./gradlew assembleDebug` | pass | 2026-11-21 |
| Instrumented test APK compiles | `./gradlew assembleDebugAndroidTest` | pass | 2026-11-21 |
| No network permission | `apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk` | pass: no `INTERNET`, no `ACCESS_NETWORK_STATE` | 2026-11-21 |
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
| 6 | Start 2 min; at 1:30 place a phone call (or WhatsApp call) past the deadline | No card during the call; card ≤ 5 s after hang-up if still in Instagram | US4 | | |
| 7 | Start 2 min; lock the screen past the deadline; unlock | No card over the keyguard; card after unlock only if Instagram is in front; session closed as expired | US4, US5 | | |
| 8 | Mark the dialer as tracked | It still opens instantly (exempt wins) | US4 | | |
| 9 | Start 3 min; `adb shell am kill app.calmlauncher.debug` | Expiry still within 60 s of the deadline | US5 | | |
| 10 | Start 10 min; `adb reboot`; unlock at once | Countdown restored with correct time | US5 | | |
| 11 | Start 2 min; `adb reboot`; unlock after 3 min | Session closed as expired; no card | US5 | | |
| 12 | `adb shell appops set app.calmlauncher.debug SYSTEM_ALERT_WINDOW deny`; let a session expire | Fallback notification instead of card; health line mentions it | Edge cases | | |
| 13 | Double-tap home (after enabling the accessibility toggle) | Screen locks (upstream feature intact) | US7 | | |

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
