package com.taskpilot.app.engine

import com.taskpilot.app.core.EngineEvent
import com.taskpilot.app.core.EngineMode
import com.taskpilot.app.core.EngineRules
import com.taskpilot.app.core.Owner
import com.taskpilot.app.core.Refusal
import com.taskpilot.app.core.RunProgress
import com.taskpilot.app.core.Trace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one place that knows who is using the screen: a run, "Pick on screen", or the recorder.
 * See [EngineMode] for the states and [EngineRules] for the (tested) rules.
 *
 * Everything that wants the screen asks here first ([start]); the accessibility service is
 * switched between its quiet and its full-attention setting here and nowhere else.
 */
object Engine {
    private val _mode = MutableStateFlow<EngineMode>(EngineMode.Idle)
    val mode: StateFlow<EngineMode> = _mode

    /** Steps being run right now (a normal run, or the preparation of "Pick on screen"). */
    val progress: RunProgress?
        get() = when (val m = _mode.value) {
            is EngineMode.Running -> m.run
            is EngineMode.Capturing -> m.run
            else -> null
        }

    /** Applies [event]. Returns why it was refused, or null. */
    @Synchronized
    fun send(event: EngineEvent): Refusal? {
        val before = _mode.value
        val svc = AutomationService.instance
        return when (val r = EngineRules.next(before, event, serviceReady = svc != null)) {
            is EngineRules.Result.Moved -> {
                _mode.value = r.mode
                Trace.move("Engine", before.tag, r.mode.tag, event::class.simpleName ?: "?")
                val active = EngineRules.serviceActive(r.mode)
                if (active != EngineRules.serviceActive(before)) svc?.setActive(active)
                null
            }
            is EngineRules.Result.Refused -> {
                Trace.log("Engine", "${before.tag}: refused ${(event as? EngineEvent.Start)?.mode?.tag} — ${r.why}")
                r.why
            }
            EngineRules.Result.Ignored -> {
                Trace.log("Engine", "${before.tag}: ignored ${event::class.simpleName}")
                null
            }
        }
    }

    fun start(mode: EngineMode): Refusal? = send(EngineEvent.Start(mode))
    fun update(mode: EngineMode) { send(EngineEvent.Update(mode)) }
    fun finish(owner: Owner) { send(EngineEvent.Finish(owner)) }

    /** The "Stop" button: stops the steps that are running, whoever started them. */
    fun stopRun() {
        Runner.stop()
        Capture.cancel()
    }

    /** Android stopped the accessibility service: nothing can go on. */
    fun serviceLost() {
        Runner.stop()
        Capture.serviceLost()
        Recorder.serviceLost()
        send(EngineEvent.ServiceLost)
    }
}
