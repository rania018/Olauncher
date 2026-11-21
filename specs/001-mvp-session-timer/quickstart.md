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
- Set Calm Launcher as the Home app, then complete onboarding: notifications, overlay, usage access, alarms, battery Unrestricted, and pick exempt apps.
- Expected: upstream Olauncher (if installed) is still present; app name "Calm Launcher".

## 3. Scenario checks (map to spec user stories)

| # | Steps | Expected | Spec |
|---|---|---|---|
| 1 | Track Instagram; open it via home label, swipe, drawer, search auto-launch, Enter | Prompt each time; Cancel → home | US1 |
| 2 | Choose 2 min | Countdown in shade; warning at 1:00 left; card at 0 within 5 s | US1–3 |
| 3 | On the card: Extend → wait → +2 (twice), let it expire again | Third expiry shows no Extend; override works and gives 5 min | US3 |
| 4 | After "Go home", reopen within 10 min | Cooldown screen with time; override works | US3 sc. 7 |
| 5 | Start 5 min; go home and stay 2+ min | Countdown disappears; session `LEFT_EARLY` | US6 |
| 6 | Start 2 min; at 1:30 place a phone call (or WhatsApp call) past the deadline | No card during the call; card ≤ 5 s after hang-up if still in Instagram | US4 |
| 7 | Start 2 min; lock the screen past the deadline; unlock | No card over the keyguard; session closed as expired | US4, US5 |
| 8 | Mark the dialer as tracked | It still opens instantly (exempt wins) | US4 |
| 9 | Start 3 min; `adb shell am kill app.calmlauncher.debug` | Expiry still within 60 s of the deadline | US5 |
| 10 | Start 10 min; `adb reboot`; unlock at once | Countdown restored with correct time | US5 |
| 11 | Start 2 min; `adb reboot`; unlock after 3 min | Session closed as expired; no card | US5 |
| 12 | `adb shell appops set app.calmlauncher.debug SYSTEM_ALERT_WINDOW deny`; let a session expire | Fallback notification instead of card; health line mentions it | Edge cases |
| 13 | Double-tap home (after enabling the accessibility toggle) | Screen locks (upstream feature intact) | US7 |

Useful inspection:
```sh
adb shell dumpsys activity services app.calmlauncher.debug | grep -i SessionService
adb shell dumpsys alarm | grep -A3 calmlauncher
adb logcat -s CalmLauncher:*
```

## 4. Done when
All rows in §3 pass on the phone, §1 is green, and `git diff upstream/master --stat` shows only
the upstream files listed in `docs/TECHNICAL_DESIGN.md` §2 among upstream paths.
