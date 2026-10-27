package com.taskpilot.app.engine

import android.content.Context
import android.os.PowerManager
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.RunRecord
import com.taskpilot.app.data.StepLog
import com.taskpilot.app.data.Workflow
import com.taskpilot.app.ui.StepCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class RunState(
    val wfId: String,
    val wfName: String,
    val index: Int,
    val total: Int,
    val stepTitle: String,
    val startedAt: Long,
)

/** Shared between the steps of one run (e.g. the files chosen by "Select files"). */
class RunContext(val startMs: Long) {
    var files: List<File> = emptyList()
}

data class Outcome(val ok: Boolean, val message: String = "")

object Runner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts a run. Returns null when started, otherwise a reason it could not start.
     * [after] is used by the element picker: run the earlier steps, then capture the screen.
     */
    fun launch(
        ctx: Context,
        wfId: String,
        trigger: String,
        startAt: Int = 0,
        endExclusive: Int? = null,
        after: (suspend (AutomationService) -> Unit)? = null,
    ): String? {
        val app = ctx.applicationContext
        val wf = Repo.get(wfId) ?: return "That automation no longer exists."
        val svc = AutomationService.instance
        if (svc == null) {
            val why = "The TaskPilot accessibility service is off. Turn it on in Setup."
            if (trigger == "Scheduled") {
                Notifier.post(app, Notifier.CH_ALERTS, "${wf.emoji} ${wf.name} didn't run", why)
                Repo.addRun(
                    RunRecord(
                        workflowId = wf.id, workflowName = wf.name, emoji = wf.emoji, trigger = trigger,
                        startedAt = System.currentTimeMillis(), endedAt = System.currentTimeMillis(),
                        status = "failed", summary = why,
                    ),
                )
            }
            return why
        }
        if (isRunning) {
            if (trigger == "Scheduled") {
                Notifier.post(app, Notifier.CH_ALERTS, "${wf.emoji} ${wf.name} skipped", "Another automation was still running.")
            }
            return "Another automation is already running."
        }
        job = scope.launch { execute(app, svc, wf, trigger, startAt, endExclusive, after) }
        return null
    }

    fun stop() {
        job?.cancel()
    }

    @Suppress("DEPRECATION")
    private fun wakeLock(ctx: Context): PowerManager.WakeLock? = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "TaskPilot:run",
        )?.apply { acquire(45 * 60 * 1000L) }
    } catch (_: Exception) {
        null
    }

    private suspend fun execute(
        ctx: Context,
        svc: AutomationService,
        wf: Workflow,
        trigger: String,
        startAt: Int,
        endExclusive: Int?,
        after: (suspend (AutomationService) -> Unit)?,
    ) {
        val start = System.currentTimeMillis()
        val rc = RunContext(start)
        val logs = ArrayList<StepLog>()
        val record = after == null
        var status = "success"
        var summary = ""
        val wl = wakeLock(ctx)
        svc.setActive(true)
        svc.showRunPill(wf.name)
        try {
            for ((i, step) in wf.steps.withIndex()) {
                if (i < startAt) continue
                if (endExclusive != null && i >= endExclusive) break
                val title = StepCatalog.titleOf(step)
                if (!step.enabled) {
                    logs += StepLog(i, title, "skipped", "Turned off")
                    continue
                }
                _state.value = RunState(wf.id, wf.name, i, wf.steps.size, title, start)
                svc.updateRunPill("▶ ${i + 1}/${wf.steps.size} · $title")
                val t0 = System.currentTimeMillis()
                val out: Outcome = try {
                    withTimeoutOrNull(40 * 60_000L) { StepExec.run(ctx, svc, step, rc) }
                        ?: Outcome(false, "Took longer than 40 minutes")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Outcome(false, e.message ?: e.javaClass.simpleName)
                }
                logs += StepLog(i, title, if (out.ok) "ok" else "failed", out.message, System.currentTimeMillis() - t0)
                if (!out.ok) {
                    status = "failed"
                    summary = "Step ${i + 1} · $title — ${out.message}"
                    break
                }
            }
            if (status == "success") {
                summary = "${logs.count { it.status == "ok" }} steps done"
                after?.invoke(svc)
            }
        } catch (e: CancellationException) {
            status = "stopped"
            summary = "Stopped by you"
        } finally {
            withContext(NonCancellable) {
                _state.value = null
                svc.hideRunPill()
                if (after == null || status != "success") svc.setActive(false)
                try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                val end = System.currentTimeMillis()
                if (record) {
                    Repo.addRun(
                        RunRecord(
                            workflowId = wf.id, workflowName = wf.name, emoji = wf.emoji, trigger = trigger,
                            startedAt = start, endedAt = end, status = status, summary = summary, logs = logs,
                        ),
                    )
                    Repo.markRun(wf.id, start, status)
                    val secs = (end - start) / 1000
                    val dur = if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"
                    val head = when (status) {
                        "success" -> "✓ ${wf.name} finished"
                        "stopped" -> "■ ${wf.name} stopped"
                        else -> "✗ ${wf.name} failed"
                    }
                    Notifier.post(
                        ctx,
                        if (status == "failed") Notifier.CH_ALERTS else Notifier.CH_RUNS,
                        head,
                        "$summary · $dur",
                    )
                }
            }
        }
    }
}
