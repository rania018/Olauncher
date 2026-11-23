<!--
Sync Impact Report
- Version change: 1.0.0 → 1.1.0 (MINOR: Principle IV materially expanded; work that complied
  with 1.0.0 still complies, so no backward-incompatible redefinition)
- Modified principles: IV. Minimal, Calm UI (title unchanged): Calm Launcher's own screens may now
  use one user-chosen accent colour and show an app's icon where it identifies the app; the home
  screen and upstream screens stay text-only; time reminders must stay visible over light and dark
  apps; a black-and-white choice must always exist. Added a rationale.
- Added sections: none
- Removed sections: none
- Templates: plan/spec/tasks templates read this file at runtime; no template edits needed
- Follow-ups for feature 001:
  - plan.md Complexity Tracking: the "accent colour … icons" exception row is no longer an
    exception and should be removed at the next /speckit-plan.
  - The pill's grow-and-settle pulse at the warning (TimeLeftOverlay.pulse) is a custom animation
    and still conflicts with "no animations beyond what Android provides": remove it in the next
    tasks, or amend again.
- Deferred TODOs: none
-->
# Calm Launcher Constitution

Calm Launcher is a personal, GPL-3.0 fork of Olauncher whose purpose is to reduce the owner's
screen time through session limits, friction and blocking, without ever making the phone unsafe
to use.

## Core Principles

### I. Privacy First
- The app MUST NOT declare the `INTERNET` permission or any other network permission, and MUST
  NOT include analytics, crash-reporting, advertising or tracking SDKs.
- All data (rules, sessions, intent answers, stats) MUST stay on the device. Export is allowed only
  as a file the user explicitly saves.
- Data read from other apps (accessibility events, usage stats) MUST be processed in memory and
  only the minimum derived facts (package, timestamps, rule matched) MAY be stored. Screen text from
  other apps MUST NOT be persisted.

Rationale: the app sees everything the user does; trust requires it to be unable to leak.

### II. Fail Open (NON-NEGOTIABLE)
- If any enforcement component crashes, loses a permission, cannot match its rules, or is in an
  unknown state, the outcome MUST be "app is allowed", never "app is blocked".
- Apps on the exempt list (dialer, SMS/messages, maps/navigation, banking, authenticator,
  emergency/SOS, plus anything the user adds) MUST never be delayed, prompted, timed or blocked,
  and the exempt list MUST NOT be editable into an empty state for the defaults above.
- Nothing in the app may prevent leaving the launcher, changing the default launcher, reaching
  Android Settings, or placing an emergency call.
- Enforcement MUST send the user to the home screen; it MUST NOT use full-screen locks,
  device-admin restrictions, or overlays that cannot be dismissed by Back/Home.

Rationale: a broken focus tool that locks the owner out of calls, maps or banking is worse than
no tool at all.

### III. Effortful but Always-Available Override
- Every blocking or limiting feature MUST provide an emergency override that is reachable from the
  enforcement screen itself.
- The override MUST cost effort (default: a 30-second wait that resets if the user leaves the
  screen; configurable but never below 10 seconds) and MUST always succeed when completed.
- Every override and every session extension MUST be logged locally and shown in stats.
- Loosening a rule (raising a cap, removing an app from tracking, disabling a schedule) MAY be
  delayed; tightening MUST apply immediately.

Rationale: friction changes habits; impossibility causes the owner to uninstall the app.

### IV. Minimal, Calm UI
- The UI is text-first. The home screen and upstream Olauncher screens stay text-only: no app
  icons, badges, notification counts or colour accents there.
- Calm Launcher's own screens (prompts, settings, onboarding, the time-left reminder, the time's-up
  card, its notifications) MAY use one accent colour chosen by the user, and MAY show an app's icon
  where it identifies the app the screen is about. Badges and notification counts are not allowed
  anywhere.
- A black-and-white accent choice, which restores the text-only look, MUST always be available.
- Time-left and warning reminders MUST stay clearly visible over both light and dark apps.
- Prompts and enforcement screens use plain, neutral, non-shaming language and no animations
  beyond what Android provides.
- New screens MUST reuse the upstream Olauncher look (fonts, theme attributes, text sizes) and add
  only the accent colour on top.

Rationale: on the phone, text-only reminders were easy to miss and the settings did not read as
editable (owner feedback, 2026-11-21). Colour and icons are kept to Calm Launcher's own screens,
so the home screen stays calm.

### V. Battery-Light and Event-Driven
- Polling loops MUST NOT be used where an event source exists (accessibility window events,
  AlarmManager, broadcasts, lifecycle callbacks).
- A foreground service MAY run only while a session timer is active and MUST stop when no session
  is running.
- Any unavoidable periodic work MUST state its interval and justification in the plan.

### VI. Testable Pure-Kotlin Core
- Rules, scheduling, cooldowns, caps, exemptions and the session state machine MUST live in a
  module with no Android dependencies, and time MUST be injected (a `Clock` interface), so
  that the logic runs as plain JVM unit tests.
- Every rule and state transition MUST have JVM unit tests. Tests for the core are written before
  or together with the implementation, never after merge.
- Android-dependent code MUST stay thin: it translates Android events into core inputs and core
  outputs into Android actions. Instrumented tests are used only where JVM tests cannot work.

### VII. Upstream Isolation
- New code lives in its own packages/modules (for example `app.calmlauncher.*` and the `:engine`
  module), never mixed into upstream files.
- Edits to upstream Olauncher files MUST be minimal hook calls and MUST be listed in
  `docs/TECHNICAL_DESIGN.md` ("Upstream files modified"), so upstream merges stay easy.
- Upstream features that conflict with these principles (for example the network wallpaper
  download) are disabled with the smallest possible change.

### VIII. Simple, Explained Kotlin
- Prefer plain classes, `when` expressions and explicit state over clever abstractions, DSLs, or
  reflection. No dependency-injection framework.
- The first use of each Android concept (Service, AccessibilityService, AlarmManager, overlay
  window, Room, DataStore, permissions) MUST carry a short comment explaining what it is and why
  it is used.

## Platform & Distribution Constraints
- Language: Kotlin only (no Rust, Flutter, React Native or other cross-platform layers).
- Licence: GPL-3.0; the fork stays open source; no proprietary SDKs.
- Build: everything MUST build and test from the command line (`./gradlew assembleDebug`,
  `./gradlew test`) with JDK 17 and install via `adb`; Android Studio is optional.
- Distribution: personal sideloaded use. Any API that would face Google Play policy restrictions
  (AccessibilityService for non-accessibility purposes, `QUERY_ALL_PACKAGES`,
  `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, foreground service types) MUST be flagged in the
  design documents.
- Behaviour that depends on Android version or OEM (the primary target device is a Samsung phone on
  One UI) MUST be flagged where it is designed, together with its fallback.

## Development Workflow & Quality Gates
- Work follows Spec Kit: product docs → spec → clarify → plan → tasks → owner review →
  implement. No feature code is written before the owner approves the plan and tasks.
- A change is done when: `./gradlew assembleDebug` and `./gradlew test` pass, new core logic has
  unit tests, the upstream-files list is current, and the fail-open behaviour of the change is
  described in its spec.
- Where a requirement is not realistically achievable, the spec says so plainly and proposes the
  closest workable alternative.

## Governance
- This constitution overrides other project guidance. Plans MUST include a Constitution Check
  and justify any exception in a complexity/exception table.
- Amendments are made by the owner, recorded in this file with a version bump: MAJOR for removing or
  redefining a principle, MINOR for adding one or materially expanding it, PATCH for wording.
- Principle II (Fail Open) and Principle III (Override) MUST NOT be weakened by amendment
  without a written rationale in the Sync Impact Report.

**Version**: 1.1.0 | **Ratified**: 2026-11-21 | **Last Amended**: 2026-11-21
