package com.taskpilot.app.engine

import android.content.Context
import android.content.Intent
import com.taskpilot.app.core.CaptureOutcome
import com.taskpilot.app.core.CapturePhase
import com.taskpilot.app.core.EngineMode
import com.taskpilot.app.core.Owner
import com.taskpilot.app.core.Refusal
import com.taskpilot.app.core.RunStatus
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.StepLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "Pick on screen" from the step editor.
 *
 *     start ──(run the earlier steps first?)──▶ PREPARING ──ok──▶ AIMING ──◉ Capture──▶ READING ──▶ Ready
 *       │                                          │ a step failed      │ ✕                │ unreadable
 *       └──────────────(no)──────────────▶ AIMING  ▼                    ▼                  ▼
 *                                               Failed              Cancelled            Failed
 *
 * The phase lives in `Engine.mode` (Capturing); the answer lands in [outcome], addressed to the
 * step that asked. Every ending brings TaskPilot back to the front — before, a failed preparation
 * left the user in the other app with no message.
 */
object Capture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var stepId = ""

    private val _outcome = MutableStateFlow<CaptureOutcome<CaptureResult>?>(null)
    val outcome: StateFlow<CaptureOutcome<CaptureResult>?> = _outcome

    /** The step editor has shown (or dropped) the answer. */
    fun consume() {
        _outcome.value = null
    }

    /**
     * @param pkg the app to open, or "" to capture whatever is in front
     * @param runFirst run steps 0 until [runFirst] of automation [wfId] to get to the right screen
     */
    fun start(ctx: Context, stepId: String, pkg: String, wfId: String? = null, runFirst: Int? = null): Refusal? {
        val app = ctx.applicationContext
        val wf = if (runFirst != null && wfId != null) Repo.get(wfId) ?: return Refusal.GONE else null
        val refusal = Engine.start(EngineMode.Capturing(if (wf != null) CapturePhase.PREPARING else CapturePhase.AIMING))
        if (refusal != null) return refusal
        val svc = AutomationService.instance
        if (svc == null) {
            Engine.finish(Owner.CAPTURE)
            return Refusal.SERVICE_OFF
        }
        this.stepId = stepId
        _outcome.value = null
        job = scope.launch {
            if (wf != null) {
                svc.showRunPill(wf.name)
                val result = try {
                    Runner.runSteps(app, svc, wf, 0, runFirst, System.currentTimeMillis(), ArrayList<StepLog>()) {
                        Engine.update(EngineMode.Capturing(CapturePhase.PREPARING, it))
                    }
                } finally {
                    svc.hideRunPill()
                }
                if (result.status != RunStatus.SUCCESS) {
                    end(CaptureOutcome.Failed(stepId, "Couldn't get to the screen: ${result.summary}"))
                    return@launch
                }
                Engine.update(EngineMode.Capturing(CapturePhase.AIMING))
            } else if (pkg.isNotBlank()) {
                svc.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    try { svc.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
            delay(600)
            svc.showCapturePill(onCapture = { read() }, onCancel = { cancel() })
        }
        return null
    }

    /** ◉ Capture was tapped. */
    private fun read() {
        val svc = AutomationService.instance ?: return
        val m = Engine.mode.value
        if (m !is EngineMode.Capturing || m.phase != CapturePhase.AIMING) return
        Engine.update(EngineMode.Capturing(CapturePhase.READING))
        svc.hideCapturePill()
        job = scope.launch {
            delay(450)                                   // let the pill leave the picture
            val result = try { svc.captureScreen() } catch (_: Exception) { null }
            end(
                if (result != null) CaptureOutcome.Ready(stepId, result)
                else CaptureOutcome.Failed(stepId, "Couldn't read that screen — try again"),
            )
        }
    }

    /** ✕ on the capture pill, or Stop while the earlier steps are running. */
    fun cancel() {
        if (Engine.mode.value !is EngineMode.Capturing) return
        job?.cancel()
        end(CaptureOutcome.Cancelled(stepId))
    }

    fun serviceLost() {
        if (Engine.mode.value !is EngineMode.Capturing) return
        job?.cancel()
        _outcome.value = CaptureOutcome.Failed(stepId, "The accessibility service stopped")
    }

    @Synchronized
    private fun end(o: CaptureOutcome<CaptureResult>) {
        if (Engine.mode.value !is EngineMode.Capturing) return      // already ended
        val svc = AutomationService.instance
        svc?.hideCapturePill()
        svc?.hideRunPill()
        _outcome.value = o
        Engine.finish(Owner.CAPTURE)
        svc?.bringAppToFront()
    }
}
