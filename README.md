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

1. **Accessibility service** — needed to tap Run/Send. If the switch is greyed out or says *“Restricted setting”* (Android 13+ blocks apps installed from a file), the Setup tab shows step-by-step help tailored to your phone brand. The key step people miss: **tap the greyed switch once first** so the *“Restricted setting”* message appears — then tap **Allow** on that message, or **App info → ⋮ → Allow restricted settings** (on Honor/Huawei, which have no ⋮ menu, the Allow option is on the message itself).
2. **All files access**
3. **Exact alarms**
4. **No battery restriction** — on Realme/OPPO also enable *Allow auto-launch*.
5. **Notifications**

**Screen lock:** Android never lets apps bypass a PIN/pattern. For night runs, either save your **PIN** in the *Wake & unlock* step, or use Swipe/None. The PIN is encrypted (AES-256-GCM) with a key generated inside the phone's secure hardware (Android Keystore/StrongBox) — it's never shown again, never part of an automation, History or the APK, and backups / phone-to-phone transfer are disabled for the whole app.

**Updating without losing Accessibility:** download the new APK (don't open it), then *Setup → App updates → Install*. TaskPilot installs it through Android's installer-session API (the way app stores do), so Android doesn't re-apply “Restricted setting”. The very first switch to this method (installing 1.2.0 itself) still has to be done the old way.

**Edit a file:** change settings in a script before it runs — pick `START_DATETIME`, set it to `{lastweek.start} 00:00`. Smart dates: `{today}`, `{yesterday}`, `{week.start}`, `{week.end}`, `{lastweek.start}`, `{lastweek.end}`, `{month.start}`, `{lastmonth.end}`, offsets like `{today-7}`, formats like `{lastweek.start|dd-MM-yyyy}`. “Fresh copy” mode writes a hidden copy next to the script and the following *Open file* step opens it, so Pydroid always runs exactly the edited code.

## Build it in the real app (any app: Gmail, D-Nothi, Chrome…)

*New automation → Build it in the real app* (or *Add step → Build it in the real app*), then choose the app.
TaskPilot opens it with a floating bar. **Options** lists everything on the current screen — buttons, text boxes,
list items, switches, scroll areas (the one you select is outlined on the real screen). Pick one, then the action:
*Tap · Long press · Type text · Wait for it · Scroll*. TaskPilot does it in the app, saves it as a step and lists the
next screen's options — branch → sub-branch through the real app. Or turn on **Record my taps** and just use the app.
**Done** adds the steps to the automation. Passwords are never recorded.

New steps: **Open a web page** (Chrome or the default browser), **Send an email** (Gmail, with the selected files,
Send pressed automatically), **Scroll**. Smart dates (`{lastweek.start|d MMM}` …) work in captions, subjects, messages and links.

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
