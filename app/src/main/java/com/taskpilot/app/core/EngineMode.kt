package com.taskpilot.app.core

/**
 * WHO IS USING THE SCREEN RIGHT NOW — exactly one of these, held in `Engine.mode`.
 *
 * Before this existed the answer was spread over `Runner.job`, `Runner.state`,
 * `CaptureStore.waiting`, `Recorder.session` and a `setActive(Boolean)` that seven places called
 * (last caller won — a capture finishing could switch the service to idle in the middle of a run).
 *
 *     Idle ──Start──▶ Running ──Finish──▶ Idle
 *     Idle ──Start──▶ Capturing(PREPARING → AIMING → READING) ──Finish──▶ Idle
 *     Idle ──Start──▶ Recording ──Finish──▶ Idle
 *     anything ──ServiceLost──▶ Idle
 *
 * Nothing starts unless the engine is Idle, and only the current owner can update or finish.
 */
sealed interface EngineMode {
    val owner: Owner?

    data object Idle : EngineMode {
        override val owner: Owner? get() = null
    }

    /** An automation is running its steps. */
    data class Running(val run: RunProgress) : EngineMode {
        override val owner: Owner get() = Owner.RUN
    }

    /** "Pick on screen" in the step editor. [run] is set while the earlier steps are being run first. */
    data class Capturing(val phase: CapturePhase, val run: RunProgress? = null) : EngineMode {
        override val owner: Owner get() = Owner.CAPTURE
    }

    /** "Build it in the real app" is open over another app. */
    data class Recording(val pkg: String, val appName: String) : EngineMode {
        override val owner: Owner get() = Owner.RECORD
    }

    /** Short name for logs: "Running", "Capturing.AIMING"… */
    val tag: String
        get() = when (this) {
            Idle -> "Idle"
            is Running -> "Running"
            is Capturing -> "Capturing.$phase"
            is Recording -> "Recording"
        }
}

enum class Owner { RUN, CAPTURE, RECORD }

enum class CapturePhase {
    /** Running the automation's earlier steps to reach the right screen. */
    PREPARING,

    /** The "◉ Capture" pill is showing; the user is getting to the screen they want. */
    AIMING,

    /** Reading the screen and taking the picture. */
    READING,
}

/** Progress of the steps being run (shown on Home, in the editor and on the floating pill). */
data class RunProgress(
    val wfId: String,
    val wfName: String,
    val index: Int,
    val total: Int,
    val stepTitle: String,
    val startedAt: Long,
)

/** Why something could not start. The message is shown to the user as-is. */
enum class Refusal(val message: String) {
    SERVICE_OFF("The TaskPilot accessibility service is off. Turn it on in Setup."),
    RUNNING("Another automation is already running."),
    RECORDING("You were building in an app — tap Done on the floating bar first."),
    CAPTURING("You were picking a button — capture or cancel that first."),
    GONE("That automation no longer exists."),
}

sealed interface EngineEvent {
    /** Idle → [mode]. Refused when the engine is busy or the service is off. */
    data class Start(val mode: EngineMode) : EngineEvent

    /** Progress inside the same owner (next step, next capture phase). */
    data class Update(val mode: EngineMode) : EngineEvent

    /** The owner is done → Idle. Ignored if someone else owns the engine. */
    data class Finish(val owner: Owner) : EngineEvent

    /** Android stopped the accessibility service → Idle, whatever was going on. */
    data object ServiceLost : EngineEvent
}

object EngineRules {
    sealed interface Result {
        data class Moved(val mode: EngineMode) : Result
        data class Refused(val why: Refusal) : Result

        /** Not a legal move from the current mode — nothing changes. */
        data object Ignored : Result
    }

    fun busyReason(mode: EngineMode): Refusal? = when (mode) {
        EngineMode.Idle -> null
        is EngineMode.Running -> Refusal.RUNNING
        is EngineMode.Capturing -> Refusal.CAPTURING
        is EngineMode.Recording -> Refusal.RECORDING
    }

    fun next(mode: EngineMode, event: EngineEvent, serviceReady: Boolean): Result = when (event) {
        is EngineEvent.Start -> {
            val busy = busyReason(mode)
            when {
                event.mode.owner == null -> Result.Ignored
                !serviceReady -> Result.Refused(Refusal.SERVICE_OFF)
                busy != null -> Result.Refused(busy)
                else -> Result.Moved(event.mode)
            }
        }
        is EngineEvent.Update ->
            if (mode.owner != null && mode.owner == event.mode.owner) Result.Moved(event.mode) else Result.Ignored
        is EngineEvent.Finish ->
            if (mode.owner == event.owner) Result.Moved(EngineMode.Idle) else Result.Ignored
        EngineEvent.ServiceLost ->
            if (mode == EngineMode.Idle) Result.Ignored else Result.Moved(EngineMode.Idle)
    }

    /** The service listens to every screen event only while someone is using the screen. */
    fun serviceActive(mode: EngineMode): Boolean = mode != EngineMode.Idle
}
