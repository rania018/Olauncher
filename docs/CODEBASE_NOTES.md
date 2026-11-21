# Olauncher codebase notes

Upstream: `tanujnotes/Olauncher` at commit `480d897` (v6.11.5, versionCode 117).
Paths are relative to `app/src/main/java/app/olauncher/` unless they start with `app/`.
Line numbers refer to that commit.

## Shape of the app

- A single activity, `MainActivity.kt`, hosts three fragments through the Navigation component (`app/src/main/res/navigation/`): `mainFragment` (`ui/HomeFragment.kt`), `appListFragment` (`ui/AppDrawerFragment.kt`) and `settingsFragment` (`ui/SettingsFragment.kt`).
- `MainViewModel.kt` is shared by all fragments. It holds LiveData for the UI and performs the actual app launches.
- UI uses XML layouts with viewBinding, not Jetpack Compose. Persistence is plain `SharedPreferences`; there is no database.
- `MainActivity` is declared as the HOME activity with `launchMode="singleTask"` and `excludeFromRecents` (`app/src/main/AndroidManifest.xml`).
- Tests: two JVM unit tests in `app/src/test/` (`ShortcutIdentityTest`, `AppSearchMatcherTest`). There are no instrumented tests.

## How apps are listed

- `helper/Utils.kt:67` `getAppsList()` loops over every user profile (`UserManager.userProfiles`). For each profile it calls `LauncherApps.getActivityList(null, profile)` to get launchable activities and builds `AppModel.App` entries (`data/AppModel.kt`). Hidden apps are filtered using `Prefs.hiddenApps`, a set of `"package|userHandle"` strings (`data/Prefs.kt:232`). Renamed labels come from `Prefs.getAppRenameLabel`. The launcher excludes itself via `BuildConfig.APPLICATION_ID`.
- Private Space (Android 15+) is handled separately: `Utils.kt:241` `getPrivateSpaceApps`.
- `MainViewModel.getAppList()` (~L401) runs this on a coroutine. `MainActivity.onResume()` (L133) refreshes it on every return to the launcher.
- Drawer and search: `ui/AppDrawerFragment.kt` with `ui/AppDrawerAdapter.kt`, which has a `Filter` and search matching in `helper/AppSearchMatcher.kt`. **Auto-launch:** when a search narrows to one result, `AppDrawerAdapter.autoLaunch()` (L160) opens it immediately. Pressing Enter calls `launchFirstInList()` (L199).
- The home screen shows up to 8 text labels (`homeApp1..8`). They are stored as numbered prefs keys such as `APP_PACKAGE_1` (`Prefs.kt:56`) and read through `prefs.getAppPackage(location)` and similar.

## How apps are launched (the single chokepoint)

Every launcher-initiated app or shortcut launch ends in **`MainViewModel.selectedApp(appModel, flag)`** (`MainViewModel.kt:72`):

- `flag == FLAG_LAUNCH_APP` (100) or `FLAG_HIDDEN_APPS` (101) launches the app. Any other flag means "the user is picking an app for a slot" (home 1–8, swipe left/right, clock, calendar, screen-time) and only saves it.
- `launchApp()` (`MainViewModel.kt:367`) resolves the activity with `LauncherApps.getActivityList` and calls `LauncherApps.startMainActivity(component, user, null, null)`.
- `launchShortcut()` (`MainViewModel.kt:108`) calls `LauncherApps.startShortcut(...)` for pinned shortcuts.

Callers:

| Entry point | Code |
|---|---|
| Tap a home app | `HomeFragment.onClick` → `homeAppClicked()` (L499) → `launchAppOrShortcut()` (L439) → `launchApp()` (L485) / `launchShortcut()` (~L471) → `viewModel.selectedApp(..., FLAG_LAUNCH_APP)` |
| Swipe left/right | `openSwipeLeftApp()` (L523) / `openSwipeRightApp()` (L510), same path. Fallbacks `openCameraApp` and `openDialerApp` (`Utils.kt:497/488`) fire a plain `Intent` directly |
| Tap clock / date / screen-time text | `openClockApp()` (L109), `openCalendarApp()` (L121), `openScreenTimeDigitalWellbeing()` (L604), going through `selectedApp` if a custom app is set, otherwise via a direct `Intent` (`openAlarmApp`, `openCalendar`, Digital Wellbeing component) |
| Drawer tap, search auto-launch, Enter | `AppDrawerFragment` adapter `appClickListener` (L151–158) → `viewModel.selectedApp(appModel, flag)`, then **immediately** `popBackStack(R.id.mainFragment)` |

Implications for interception:

- Hooking `selectedApp` (launch flags only) catches every launch made *through the launcher*. The direct-`Intent` fallbacks open dialer, camera, alarm and calendar, which belong on the exempt list anyway.
- The drawer pops its fragment right after calling `selectedApp`, so a "How long?" prompt must **not** be a dialog owned by `AppDrawerFragment`. A separate small Activity (or a dialog hosted by `MainActivity`) survives that navigation.
- **Search auto-launch** fires with no extra tap, so a tracked app found by search must still go through the prompt, which happens automatically if the hook is in `selectedApp`.

## Settings and preferences

- `data/Prefs.kt`: one `SharedPreferences` file named `"app.olauncher"` (L10, L121), with typed Kotlin properties for every setting (home apps, swipe apps, theme, alignment, lock mode, hidden apps, wallpaper, and so on).
- The UI is `ui/SettingsFragment.kt`, a single long scroll of text toggles defined in `res/layout/fragment_settings.xml`, with a separate `layout-land` copy that must be kept in sync.
- Constants such as flags, dialogs and URLs are in `data/Constants.kt`.
- Settings are opened with a long-press on an empty area of the home screen (`HomeFragment` `onLongClick`, ~L663).

## Gestures

- `listener/OnSwipeTouchListener.kt` (whole home screen) and `listener/ViewSwipeTouchListener.kt` (individual home labels) wrap a `GestureDetector`. Their callbacks are `onSwipeLeft/Right/Up/Down`, `onClick`, `onLongClick`, and `onDoubleClick`.
- Wiring is in `HomeFragment.kt` ~L640–720:
  - swipe up opens the drawer (`showAppList(FLAG_LAUNCH_APP)`)
  - swipe down expands notifications (`Utils.kt:476`)
  - left/right open the swipe apps
  - long-press opens settings
  - double-tap locks the screen
- **Double-tap to lock** (`HomeFragment` ~L673): on Android 9+ it clicks the hidden `R.id.lock` view. `helper/MyAccessibilityService.kt` sees that click (a `typeViewClicked` event with that content description) and calls `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`. Below Android 9 it uses Device Admin (`listener/DeviceAdmin.kt`).

## Existing building blocks we can reuse

- **AccessibilityService already exists:** `helper/MyAccessibilityService.kt`, configured in `app/src/main/res/xml/accessibility_service_config.xml`. Note these points:
  - It runs in a **separate process** (`android:process=":serviceProcess"` in the manifest) but writes `Prefs` (SharedPreferences), which is not multi-process safe.
  - It listens only to `typeViewClicked` and only for `packageNames="app.olauncher.debug, app.olauncher"`. **These package names are hard-coded, so changing `applicationId` breaks double-tap-to-lock unless this XML is updated.**
  - `Utils.kt:533` `isAccessServiceEnabled()` checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. The enable flow is in `SettingsFragment.kt:305` and L392–410.
- **Usage stats:**
  - `helper/usageStats/EventLogWrapper.kt` parses `UsageStatsManager` events into per-app foreground time. It is adapted from usageDirect and handles unmatched open/close events.
  - The permission check is `Extensions.kt:180` `appUsagePermissionGranted()`, and the request is `MainActivity` L266 (`ACTION_USAGE_ACCESS_SETTINGS`).
  - `MainViewModel.getTodaysScreenTime()` (~L448) already shows today's total screen time on the home screen.
- `MainActivity.onStop()`, `onUserLeaveHint()` and `onNewIntent()` (L169–187) call `backToHomeScreen()`. Pressing Home always resets the launcher to the home fragment, so "send the user home" can be done by starting `MainActivity` with the HOME intent.

## Network code (conflicts with privacy-first; to be removed)

- Manifest: `<uses-permission android:name="android.permission.INTERNET" />`, commented as "Used only for downloading wallpapers".
- `helper/WallpaperWorker.kt` is a WorkManager job, scheduled by `MainViewModel.setWallpaperWorker()` (~L420) with a network constraint.
- `Utils.kt:350–470` holds `getBitmapFromURL`, `setWallpaper`, `getTodaysWallpaper` (fetches `Constants.URL_WALLPAPERS`) and `getBackupWallpaper`.
- Triggers: `MainActivity` L192 and L222, `HomeFragment.changeAppTheme()`, and the "daily wallpaper" toggle in `SettingsFragment`.
- `Context.openUrl()` links (About, Privacy, GitHub, Play Store, "Olauncher Pro" message) only open the browser. They need no permission but should be removed or replaced, since they point to upstream.

## Files to touch to intercept an app launch

Minimum set for launcher-initiated launches, as planned in `docs/TECHNICAL_DESIGN.md`:

1. `MainViewModel.kt`: in `selectedApp()`, for `FLAG_LAUNCH_APP` and `FLAG_HIDDEN_APPS`, ask a new `LaunchGate` (our package) whether to launch now, show the prompt, or deny. This is a one-line hook plus the import.
2. `AndroidManifest.xml`: register our new activities and services (prompt activity, session service, alarm receiver, our accessibility service), plus permissions.
3. `res/xml/accessibility_service_config.xml`: package names, needed only for the existing lock feature after the rename.
4. `app/build.gradle`: new `applicationId` and dependencies (Room, DataStore, `:engine`). `settings.gradle`: `include ':engine'`.
5. `SettingsFragment.kt` and `fragment_settings.xml` (both orientations): one entry row that opens our own settings screen.

Launches that do **not** pass through the launcher (notification taps, links, the Recents screen, other apps) can't be caught here. They need the AccessibilityService or UsageStats approach in `TECHNICAL_DESIGN.md`.
