# Contract: user-visible surfaces (MVP)

All text is plain, calm and English. The look reuses upstream theme attributes and text sizes,
with no icons (Constitution IV).

| Surface | Kind | Content | Actions | Must never |
|---|---|---|---|---|
| Duration prompt | `PromptActivity` (translucent, own task, excluded from Recents) | "How long?" + app name; preset buttons; custom number field (1–60) | Preset → launch; custom → launch; **Cancel** (largest) and Back → home | Appear for exempt or untracked apps |
| Cooldown screen | `PromptActivity` mode `BLOCKED` | "Instagram is available again at 14:10" | **Go home** (default); "I really need this" → 30 s countdown → 5-min session | Hide the override |
| Countdown | Ongoing silent notification (channel `calm_session`, low importance) | "Instagram · 3:42 left" (system chronometer, counting down) | Tap → small session sheet (`PromptActivity` mode `SESSION`) with "End now"; also an "End now" action on the notification | Make sound; be shown when no session runs |
| Warning | Heads-up notification (channel `calm_warning`, high importance, no sound in silent mode) | "1 minute left in Instagram" | Tap → dismiss | Repeat in the same session |
| Time's-up card | Full-screen overlay (`TYPE_APPLICATION_OVERLAY`, opaque) | "Time's up: 5 min in Instagram" | **Go home** (default, Back too); Extend → wait 15 s → +2/+5 (max 2); "I really need this" → 30 s → +5 min | Show over an exempt or other app, over the lock screen, or during a call; stay > 120 s untouched |
| Time's-up fallback | High-priority notification (channel `calm_expiry`) | "Time's up in Instagram" | Tap → home | Be used when the overlay permission is granted |
| Health line | One `TextView` on the home screen under the clock/date | e.g. "Timer paused: notifications are off" | Tap → onboarding page for that item | Show more than one line; use red or alarm wording |
| Onboarding | `OnboardingActivity` (one page per item) | Why / what is reduced / status ✓✗ | Open setting; Skip; Next | Block use of the launcher |
| Screen-time settings | `ScreenTimeSettingsActivity`, opened from one new row in upstream settings, labelled "Screen time limits" (upstream already has a "Screen time" row for its usage display) | Tracked apps (search list with toggles), exempt apps, presets, warning lead, extension wait, cooldown | Edit; removing a default exempt app asks for confirmation | Change upstream settings |

Notification channel IDs and the overlay are owned by `app.calmlauncher.enforce` /
`app.calmlauncher.session`. The override countdown resets when the surface loses visibility
(`onPause` for activities, `onWindowVisibilityChanged` for the overlay).
