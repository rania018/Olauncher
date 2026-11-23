# Quickstart validation: MVP session timer launcher

How to prove the MVP works. Environment setup is in `docs/DEV_SETUP.md`; entity and behaviour
details are in [data-model.md](data-model.md) and [contracts/](contracts/).

## 1. Build and unit tests (no device)

```sh
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.15.6-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :engine:test          # expected: all engine tests pass (state machine, rules, invariants 1-6)
./gradlew test assembleDebug    # expected: BUILD SUCCESSFUL; upstream tests still pass
"$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat" manifest permissions app/build/outputs/apk/debug/app-debug.apk
# expected: no android.permission.INTERNET, no ACCESS_NETWORK_STATE
```

## 2. Install and onboard (Samsung phone, USB debugging on)

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | head -3
```
- Set Calm Launcher as the Home app, then complete onboarding: notifications, overlay, usage access, alarms, battery Unrestricted, the "App limits" accessibility toggle, and pick exempt apps. If Android shows "Restricted setting" for the toggle: App info → ⋮ → Allow restricted settings, then try again.
- Expected: upstream Olauncher (if installed) is still present; app name "Calm Launcher".

## 3. Scenario checks (map to spec user stories)

| # | Steps | Expected | Spec |
|---|---|---|---|
| 1 | Track Instagram; open it via home label, swipe, drawer, search auto-launch, Enter | Prompt each time; Cancel → home | US1 |
| 2 | Choose 2 min | Countdown in shade; warning at 1:00 left; card at 0 within 5 s | US1–3 |
| 3 | On the card: Extend → wait → +2 (twice), let it expire again | Third expiry shows no Extend; override works and gives 5 min | US3 |
| 4 | After "Go home", reopen within 10 min | Cooldown screen with time; override works | US3 sc. 7 |
| 5 | Start 5 min; go home and stay 2+ min | Countdown disappears; session `LEFT_EARLY` | US6 |
| 6 | Start 2 min; at 1:30 place a phone call (or WhatsApp call) and stay on the call screen past the deadline | No card and no pill over the call screen; card ≤ 5 s after hang-up if Instagram is in front again | US4 |
| 7 | Start 2 min; lock the screen past the deadline; unlock | No card over the keyguard; session closed as expired | US4, US5 |
| 8 | Try to mark the dialer as tracked; mark WhatsApp tracked, then exempt | Dialer: "always exempt", never tracked, opens instantly. WhatsApp: ticking Exempt unticks Track, and the reverse | US4 |
| 9 | Start 3 min; `adb shell am kill app.calmlauncher.debug` | Expiry still within 60 s of the deadline | US5 |
| 10 | Start 10 min; `adb reboot`; unlock at once | Countdown restored with correct time | US5 |
| 11 | Start 2 min; `adb reboot`; unlock after 3 min | Session closed as expired; no card | US5 |
| 12 | `adb shell appops set app.calmlauncher.debug SYSTEM_ALERT_WINDOW deny`; let a session expire | Fallback notification instead of card; health line mentions it | Edge cases |
| 13 | Double-tap home (after enabling the accessibility toggle) | Screen locks (upstream feature intact) | US7 |
| 14 | Settings → Time left on screen: Pill, start 3 min; then Bar; then Off | Pill: solid accent label "3 min left" with an emptying ring, clearly visible over a white app, can be dragged, tap opens End now, turns amber at the warning; bar: 6 dp accent line under the status bar, visible over white and black apps, shrinks, never blocks touches, turns amber at the warning; Off shows nothing | US2 sc. 5, FR-029 |
| 15 | Play a video in Instagram, let the session expire | Pop-up over the dimmed video; video and sound pause; touches outside the card do nothing; Extend resumes the app | US3 sc. 1, FR-012 |
| 16 | Open a tracked app | Prompt shows the app icon; session lengths sit on one line as chips | US1, FR-024 |
| 17 | Open Screen time limits | Grouped cards; steppers change cooldown, warning and wait at once; session-length chips remove on tap, "+ Add" adds; apps show icons, tracked first; tapping a tracked app opens its own lengths and cooldown | FR-024 |
| 18 | Screen time limits → Accent colour: pick Pink, then Phone's colour; change One UI Color palette | Settings, prompt, pill/bar, time's-up card and notifications use the chosen colour at once; Phone's colour follows the One UI palette; home-screen text stays black/white | FR-030 |
| 19 | Track Instagram; open it from the recent-apps list | Prompt over Instagram within 1 s; Cancel → home; choosing 2 min leaves Instagram where it was and starts the session | US1 sc. 6, FR-031, SC-008 |
| 20 | Open a tracked app from a link in another app, and from one of its notifications; repeat during its cooldown | Prompt each time; during the cooldown, the cooldown screen instead | FR-031, FR-028 |
| 21 | Start 5 min in Messenger; follow a link into another app and stay 2+ min | Messenger's session ends `LEFT_EARLY`; its pill and notification disappear | US6 sc. 4, FR-015 |
| 22 | Start 5 min; go home, open an exempt app, stay 2+ min | Session ends `LEFT_EARLY` | US6 sc. 1 |
| 23 | Start 5 min; switch to another app for 30 s and back; inside the tracked app open the keyboard and pull down the notification shade | Session continues; the pill hides while away and comes back; keyboard and shade do not hide the pill or start the grace | US6 sc. 2, FR-029 |
| 24 | Turn the app-limits toggle off; repeat rows 19 and 22 | Recents open not prompted (logged as unplanned open); health line names it; pill shows on every screen; row 22 still ends the session after 2 min (usage history) | FR-031 fallback, FR-015 |
| 25 | App-limits toggle on. Start a WhatsApp call, go home and open Instagram: choose 2 min. Stay in Instagram past the deadline with the call running. Then repeat and switch to the call screen before the deadline | Prompt as usual; pill visible over Instagram during the call; no warning sound; time's-up card at the deadline, the call's sound unchanged, "Go home" works. On the call screen: no pill, no card. With the toggle off: no pill or card until hang-up | US4 sc. 7, FR-018 |

Useful inspection:
```sh
adb shell dumpsys activity services app.calmlauncher.debug | grep -i SessionService
adb shell dumpsys alarm | grep -A3 calmlauncher
adb logcat -s CalmLauncher:*
```

## 4. Done when
All rows in §3 pass on the phone, §1 is green, and `git diff upstream/master --stat` shows only
the upstream files listed in `docs/TECHNICAL_DESIGN.md` §2 among upstream paths.
