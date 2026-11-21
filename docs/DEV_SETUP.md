# Developer setup (command line only)

This project builds and installs with Gradle and `adb`. Android Studio is optional.
Verified on 2026-11-21 on Windows 11 using Git Bash: baseline `assembleDebug` and `test` pass.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** | AGP 8.9 runs on JDK 17. Newer JDKs (for example JDK 27) are not supported by this Gradle/AGP version. Install it side by side; don't replace your default JDK |
| Android SDK | platform `android-36`, build-tools `35.x`, `platform-tools` | Path used here: `C:\Users\<you>\AppData\Local\Android\Sdk` |
| Gradle | none to install | `./gradlew` downloads Gradle 8.11.1 on first run |
| git, adb | any recent | `adb` lives in `<sdk>/platform-tools` |

### Installing SDK packages without Android Studio
```sh
# cmdline-tools must sit in <sdk>/cmdline-tools/latest/
SDK="$LOCALAPPDATA/Android/Sdk"
"$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-36" "build-tools;35.0.0"
"$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" --licenses
```

## 2. Setup fixes applied on this machine

1. **Wrong JDK and `JAVA_HOME`.** `java` on PATH was JDK 27, and `JAVA_HOME` pointed at `...\jdk-27...\bin`. It must point at the JDK root, not `bin`. Choose JDK 17 per shell, without changing the global setting:
   ```sh
   # Git Bash
   export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.15.6-hotspot"
   export PATH="$JAVA_HOME/bin:$PATH"
   ```
   ```powershell
   # PowerShell
   $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.15.6-hotspot"
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   ```
   Alternative: add `org.gradle.java.home=C:/Program Files/Microsoft/jdk-17.0.15.6-hotspot` to your **user** `~/.gradle/gradle.properties`. Don't add it to the repo, because the path is machine-specific.
2. **SDK location.** Neither `ANDROID_HOME` nor `local.properties` existed. Create `local.properties` in the repo root; it is gitignored:
   ```properties
   sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
   ```
   Use forward slashes. Backslashes must be escaped in `.properties` files.
3. **First build is slow.** About 12 minutes, because it downloads Gradle and all dependencies. Later builds take seconds.
4. The Kotlin compiler creates a `.kotlin/` folder in the repo root, which is now in `.gitignore`.

No source or Gradle file changes were needed for the baseline build. The Kotlin deprecation
warnings in upstream (`Extensions.kt`, `Utils.kt`, `SettingsFragment.kt`) are expected.

## 3. Everyday commands

```sh
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # JVM unit tests (no device needed)
./gradlew :engine:test           # pure-Kotlin engine tests, a few seconds
./gradlew lint                   # optional, slow
./gradlew clean
```

Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

## 4. Installing on the phone (Samsung / One UI)

1. Turn on Developer options: **Settings → About phone → Software information → tap "Build number" 7 times**.
2. Turn on **Settings → Developer options → USB debugging**. Connect the phone by USB and accept the RSA prompt.
3. Install and run:
   ```sh
   adb devices                                  # phone must show as "device", not "unauthorized"
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n app.calmlauncher.debug/app.olauncher.MainActivity
   ```
   The application ID is `app.calmlauncher`, and debug builds add the suffix `.debug`, so the debug package is `app.calmlauncher.debug`. The part after the `/` is the class name. It stays `app.olauncher.MainActivity` because the Kotlin package (`namespace`) was not renamed. Debug and release can be installed side by side.
4. Make it the default launcher: **Settings → Apps → Choose default apps → Home app**.
5. Wireless (optional): **Developer options → Wireless debugging**, then `adb pair <ip:port>` and `adb connect <ip:port>`.

### Logs
```sh
adb logcat --pid=$(adb shell pidof -s app.calmlauncher.debug)   # only our process
adb logcat -s CalmLauncher:*                                     # our log tag
adb logcat -c                                                    # clear buffer
```

### Samsung-specific notes for sideloaded builds
- **Restricted settings (Android 13+).** Android blocks turning on an AccessibilityService for an app installed from a file, not from a store. To allow it: **Settings → Apps → Calm Launcher → ⋮ (top-right) → Allow restricted settings**, then enable the service. `adb install` may not trigger the restriction, but installing the APK from Files does.
- **Battery.** Set **Settings → Apps → Calm Launcher → Battery → Unrestricted**. Also check **Settings → Battery → Background usage limits** and make sure the app is not in *Sleeping apps* or *Deep sleeping apps*.
- Reinstalling with `adb install -r` keeps data and permissions. Uninstalling clears both, including accessibility and usage access.

## 5. Spec Kit

- Installed: `uv tool install specify-cli --force --from git+https://github.com/github/spec-kit.git@v1.4.3`.
- Initialized: `specify init --here --force --non-interactive --integration claude --script sh`. The `sh` scripts run in Git Bash; `ps` needs PowerShell 7 (`pwsh`), which isn't installed.
- In Claude Code, opened in the repo root, the steps are **skills**: `/speckit-constitution`, `/speckit-specify`, `/speckit-clarify`, `/speckit-plan`, `/speckit-tasks`, `/speckit-analyze`, `/speckit-implement`. Older guides use `/speckit.specify` or `/specify`, which v1.4.3 doesn't use.
- The constitution is at `.specify/memory/constitution.md`. Feature specs live under `specs/`.

## 6. Calm Launcher on the phone

### Install and set as Home
```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk      # installs next to upstream Olauncher
adb shell am start -n app.calmlauncher.debug/app.olauncher.MainActivity
```
Then **Settings → Apps → Choose default apps → Home app → Calm Launcher**. Onboarding opens on the next
return to the home screen; it can be reopened from **Settings (long-press home) → Screen time limits →
Permissions status**, or by tapping the health line.

### Permissions to grant
Onboarding links to each screen. For quick test setups the same can be done over adb:

| Permission | On the phone | adb shortcut (debug build) |
|---|---|---|
| Notifications (Android 13+) | onboarding page 1 | `adb shell pm grant app.calmlauncher.debug android.permission.POST_NOTIFICATIONS` |
| Display over other apps | onboarding page 2 | `adb shell appops set app.calmlauncher.debug SYSTEM_ALERT_WINDOW allow` |
| Usage access | onboarding page 3 | `adb shell appops set app.calmlauncher.debug GET_USAGE_STATS allow` |
| Alarms & reminders (optional) | onboarding page 4 | `adb shell appops set app.calmlauncher.debug SCHEDULE_EXACT_ALARM allow` |
| Battery unrestricted | onboarding page 5, plus One UI *Battery → Unrestricted* and off the *Sleeping apps* lists | `adb shell dumpsys deviceidle whitelist +app.calmlauncher.debug` |

Revoke one (for example `... SYSTEM_ALERT_WINDOW deny`) to check the fallback and the home health line.

### Keystore note
Debug builds are signed with the per-machine debug key (`~/.android/debug.keystore`). `adb install -r`
only works over a build signed with the same key: after switching computers, or after deleting that
file, uninstall first (`adb uninstall app.calmlauncher.debug`), which also clears sessions, settings and
granted permissions. Keep `debug.keystore` if you want to update the phone from more than one machine.
No release keystore is needed for this personal, sideloaded app; never commit one (`*.jks` is ignored).

### Escape hatches
If anything ever gets in the way: pull down the notification shade and open Settings → Apps → Choose
default apps → Home app, or `adb uninstall app.calmlauncher.debug`, or boot into Samsung safe mode.
The device test sheet is [DEVICE_TESTS.md](DEVICE_TESTS.md).
