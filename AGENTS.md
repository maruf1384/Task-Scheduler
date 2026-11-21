# Working on TaskPilot — for AI agents and developers

TaskPilot is an Android app (Kotlin, Jetpack Compose) that runs step-by-step automations on the
phone at scheduled times: no server, no background service. The owner uses it on a Realme phone
and is not an Android developer — they install the APK the cloud build produces and report what
happened. Write everything user-facing in plain words.

## Build, test, release

There is no Android SDK requirement for you: **GitHub Actions is the build** (`.github/workflows/build.yml`).

| Push to | What happens |
|---|---|
| `ci/<anything>` | unit tests + debug/release build. Nothing is published. Use this to try changes. |
| `main` | the same, then a GitHub Release `v<versionName>-build<N>` with `TaskPilot-<versionName>.apk`. |

* On failure, compiler errors and failed tests are posted as **annotations** on the run
  (`.github/scripts/report.py`) — readable without logging in:
  `GET /repos/<owner>/<repo>/check-runs/<job id>/annotations`.
* Unit tests: `gradle testDebugUnitTest`. The tests in `app/src/test/…/core/` need only the Kotlin
  compiler + JUnit 4 (they compile together with `core/*.kt`, `data/Models.kt`, `engine/CodeEdit.kt`).
* Release = bump `versionCode` and `versionName` in `app/build.gradle.kts`, push to `main`.
  Every build is signed with `app/taskpilot.keystore`, so it installs over the previous one.
* Other sessions push to this repository too: `git fetch` and rebase before pushing.

## Layout

```
app/src/main/java/com/taskpilot/app/
  core/    State machines and rules as PURE Kotlin — no android.*, no androidx.* (CI enforces it).
           EngineMode · RecorderMachine · ScreenWatch · SendFlow · FilesWatch · Status · Flows · Trace
           Blocks · BlockEdits · Conditions · FlowMachine (If / Otherwise / Repeat / Stop, since 1.5.0)
  data/    Models (Workflow, Step, Schedule, RunRecord), Repo (JSON files), Templates
  engine/  Android glue: Engine (who owns the screen), Runner, StepExec, AutomationService
           (accessibility), Recorder, Capture, Scheduler, Updater, SecretStore, Diagnostics
  ui/      Compose screens. They draw state and send events; they don't decide.
app/src/test/java/…   JUnit 4 tests (core/ = pure; data/SavedFilesTest = saved-file compatibility)
docs/STATE_MACHINES.md   Every state machine: states, diagram, what it replaced, how to change it
```

## Rules that keep it working

1. **One owner of the screen.** A run, "Pick on screen" and the recorder all go through
   `Engine.start(mode)` / `Engine.finish(owner)`. Never call `AutomationService.setActive` directly.
2. **State is a type, not a set of flags.** If you need to remember "what is going on", add a state
   to the sealed type in `core/`, a transition to its pure function, and a test — then wire it.
   Read `docs/STATE_MACHINES.md` first; it also lists what was deliberately *not* turned into one.
3. **Saved files are forever.** `workflows.json` / `runs.json` on the user's phone were written by
   older versions. New fields need defaults; enum wire words (`RunStatus.wire` …) are never renamed.
   `SavedFilesTest` holds real 1.3-format samples — extend it when the format grows.
4. **WhatsApp:** never press the chat's round button unless a caption was typed and it says "Send"
   — otherwise it is the microphone (`core/SendFlow.kt`; this bug shipped once, in 1.1.2).
5. **Secrets:** the lock-screen PIN lives only in `SecretStore` (Android Keystore). It must never
   reach a `Step`, a log, `Trace`, a notification or `Diagnostics`. The recorder never records
   password fields. `Trace` takes state and event *names* only — no screen text, no typed text.
6. **Nothing leaves the phone.** No network code, no analytics, backups disabled. There is no
   INTERNET permission; `ACCESS_NETWORK_STATE` (1.5.0) only *reads* whether the phone is online.
7. **The step list is flat.** If / Otherwise / End / Repeat / Stop are ordinary steps (markers);
   `core/Blocks` works out the nesting every time, `core/FlowMachine` decides what runs next, and
   the editor changes the list only through `core/BlockEdits`, so it always fits together. Don't
   add a nested "children" field — positions are what "Run from here", the recorder and Capture use.

## When the owner reports a problem

Ask for **History → open the run → Copy report**. It contains the app and Android version, which
permissions are on, the run's steps with their messages, and the recent state changes
(`Engine: Idle → Running (Start)`, `Recorder: Choosing → Acting (Choose)` …). That usually shows
which state machine misbehaved before you read any code. You cannot run the app yourself: say
plainly what was verified by tests and the build, and what only the phone can confirm.
