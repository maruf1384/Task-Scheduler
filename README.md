# TaskPilot — on-device task automation for Android

Build step-by-step automations that run **only at the time you schedule**, entirely on the phone.
No server, no account, no background service.

## What it can do

| Group | Steps |
|---|---|
| Files & folders | **Select files** (all / newest sub-folder / newest file / *made during this run*), **Wait for new files** |
| Apps | **Open file in app** (e.g. `.py` in Pydroid 3), **Open app** |
| On-screen actions | **Tap a button** (picked from a live screenshot), **Wait for something on screen**, **Type text**, **Back / Home / Recents** |
| Send & share | **Send to WhatsApp number** (auto-presses Send), **Share files to any app** |
| Device & flow | **Wake & unlock** (optional PIN), **Wait**, **Notification**, **Lock screen** |

Captions and notifications understand `{date}`, `{time}`, `{count}`, `{files}`.

## Ready-made recipe: *Weekly Demand → WhatsApp*

Pre-loaded on first launch:

1. Wake & unlock
2. Open `Download/1. Weekly Demand. Use and Shortage/weekly_demand_use_shortage_phone.py` in **Pydroid 3**
3. Wait 4 s
4. **Tap “Run”** in Pydroid ← *pick this once with “Pick on screen”*
5. Wait for new `.xlsx` files in that folder (sub-folders included)
6. Select the files made during this run
7. Send them to **01521216482** on WhatsApp (auto-send)
8. Wait 3 s → Home → Lock screen

Set the time (default 12:40 AM) and days in the Schedule card, then switch it on.

## First-time setup (Setup tab walks you through it)

1. **Accessibility service** — needed to tap Run/Send. If the switch is greyed out: *App info → ⋮ → Allow restricted settings*.
2. **All files access**
3. **Exact alarms**
4. **No battery restriction** — on Realme/OPPO also enable *Allow auto-launch*.
5. **Notifications**

**Screen lock:** Android never lets apps bypass a PIN/pattern. For night runs, either save your **PIN** in the *Wake & unlock* step (kept only inside the app), or use Swipe/None.

## Picking a button (the “branch → sub-branch” flow)

*Add step → On-screen actions → Tap a button → Pick on screen.*
TaskPilot opens the app (or runs the earlier steps first to reach the right screen), shows a floating **◉ Capture** bar, and brings back a picture of that screen with every tappable thing outlined. Tap the one you want (e.g. *Run*, *Undo*, *Redo*) — or use the List tab to search by name.

## Battery

The system alarm clock wakes TaskPilot at the scheduled minute; nothing else runs in between. The accessibility service listens to almost nothing while idle and switches to full mode only during a run or capture.

## Building

* **Cloud (no PC needed):** push this folder to a GitHub repo → the *Build APK* workflow builds it and publishes `TaskPilot.apk` as a Release.
* **Android Studio:** open the folder and Run. (Gradle 8.9, AGP 8.5, Kotlin 2.0, Compose/Material 3, minSdk 26, targetSdk 34.)

All builds are signed with the included `app/taskpilot.keystore`, so updates install over each other and keep your automations.

## Project layout

```
app/src/main/java/com/taskpilot/app/
  data/     Models (Workflow, Step, Schedule, RunRecord), JSON storage, the template
  engine/   Scheduler (alarm clock), AutomationService (taps/captures), Runner, StepExec, FileOps, Perms
  ui/       Compose UI: Home (Automations / History / Setup), Editor, Step editor, pickers
```
