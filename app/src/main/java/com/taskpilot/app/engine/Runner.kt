package com.taskpilot.app.engine

import android.content.Context
import android.os.PowerManager
import com.taskpilot.app.core.EngineMode
import com.taskpilot.app.core.Owner
import com.taskpilot.app.core.Refusal
import com.taskpilot.app.core.RunProgress
import com.taskpilot.app.core.RunStatus
import com.taskpilot.app.core.StepStatus
import com.taskpilot.app.core.Trigger
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Shared between the steps of one run (e.g. the files chosen by "Select files"). */
class RunContext(val startMs: Long) {
    var files: List<File> = emptyList()
    /** Set by "Edit a file" in fresh-copy mode: open [editedFile] wherever [editedFrom] is opened. */
    var editedFrom: String? = null
    var editedFile: File? = null
    /** The app the run last opened (used to watch it for a crashed script). */
    var lastPkg: String = ""
    /** "▶ 4/10 · Wait for new files" — steps can add live info after it. */
    var pillPrefix: String = ""
}

data class Outcome(val ok: Boolean, val message: String = "")

/** How a stretch of steps ended. */
class StepsResult(val status: RunStatus, val summary: String)

/**
 * Runs automations. Whether one is running is not kept here — it is `Engine.mode`
 * (Idle → Running → Idle); this object only does the work once the engine has said yes.
 */
object Runner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Starts a run. Returns null when it started, otherwise why it could not. */
    fun launch(
        ctx: Context,
        wfId: String,
        trigger: Trigger,
        startAt: Int = 0,
        endExclusive: Int? = null,
    ): Refusal? {
        val app = ctx.applicationContext
        val wf = Repo.get(wfId) ?: return Refusal.GONE
        val first = RunProgress(wf.id, wf.name, startAt, wf.steps.size, "Starting…", System.currentTimeMillis())
        val refusal = Engine.start(EngineMode.Running(first))
        val svc = AutomationService.instance
        if (refusal != null || svc == null) {
            val why = refusal ?: Refusal.SERVICE_OFF
            if (refusal == null) Engine.finish(Owner.RUN)       // the service went away in between
            if (trigger == Trigger.SCHEDULED) recordRefusal(app, wf, why)
            return why
        }
        job = scope.launch { execute(app, svc, wf, trigger, startAt, endExclusive) }
        return null
    }

    fun stop() {
        job?.cancel()
    }

    /**
     * A scheduled run that could not start always leaves a trace: a notification AND a History
     * entry (before, two of the three reasons only showed a notification).
     */
    private fun recordRefusal(ctx: Context, wf: Workflow, why: Refusal) {
        val serviceOff = why == Refusal.SERVICE_OFF
        val now = System.currentTimeMillis()
        Notifier.post(
            ctx, Notifier.CH_ALERTS,
            "${wf.emoji} ${wf.name} ${if (serviceOff) "didn't run" else "skipped"}",
            why.message,
        )
        Repo.addRun(
            RunRecord(
                workflowId = wf.id, workflowName = wf.name, emoji = wf.emoji, trigger = Trigger.SCHEDULED,
                startedAt = now, endedAt = now,
                status = if (serviceOff) RunStatus.FAILED else RunStatus.SKIPPED,
                summary = why.message,
            ),
        )
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

    /**
     * Runs steps [from, until) of [wf], reporting each one through [progress] and adding to [logs]
     * (so a caller that is cancelled still has the steps done so far). Used by a normal run and by
     * "Pick on screen → run the earlier steps first". Throws CancellationException when stopped.
     */
    internal suspend fun runSteps(
        ctx: Context,
        svc: AutomationService,
        wf: Workflow,
        from: Int,
        until: Int?,
        startedAt: Long,
        logs: MutableList<StepLog>,
        progress: (RunProgress) -> Unit,
    ): StepsResult {
        val rc = RunContext(startedAt)
        for ((i, step) in wf.steps.withIndex()) {
            if (i < from) continue
            if (until != null && i >= until) break
            val title = StepCatalog.titleOf(step)
            if (!step.enabled) {
                logs += StepLog(i, title, StepStatus.SKIPPED, "Turned off")
                continue
            }
            progress(RunProgress(wf.id, wf.name, i, wf.steps.size, title, startedAt))
            rc.pillPrefix = "▶ ${i + 1}/${wf.steps.size} · $title"
            svc.updateRunPill(rc.pillPrefix)
            val t0 = System.currentTimeMillis()
            val out: Outcome = try {
                withTimeoutOrNull(40 * 60_000L) { StepExec.run(ctx, svc, step, rc) }
                    ?: Outcome(false, "Took longer than 40 minutes")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome(false, e.message ?: e.javaClass.simpleName)
            }
            logs += StepLog(i, title, if (out.ok) StepStatus.OK else StepStatus.FAILED, out.message, System.currentTimeMillis() - t0)
            if (!out.ok) return StepsResult(RunStatus.FAILED, "Step ${i + 1} · $title — ${out.message}")
        }
        return StepsResult(RunStatus.SUCCESS, "${logs.count { it.status == StepStatus.OK }} steps done")
    }

    private suspend fun execute(
        ctx: Context,
        svc: AutomationService,
        wf: Workflow,
        trigger: Trigger,
        startAt: Int,
        endExclusive: Int?,
    ) {
        val start = System.currentTimeMillis()
        val logs = ArrayList<StepLog>()
        var result = StepsResult(RunStatus.STOPPED, "Stopped by you")
        val wl = wakeLock(ctx)
        svc.showRunPill(wf.name)
        try {
            result = runSteps(ctx, svc, wf, startAt, endExclusive, start, logs) { Engine.update(EngineMode.Running(it)) }
        } catch (e: CancellationException) {
            result = StepsResult(RunStatus.STOPPED, "Stopped by you")
        } finally {
            withContext(NonCancellable) {
                svc.hideRunPill()
                try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                Engine.finish(Owner.RUN)
                val end = System.currentTimeMillis()
                Repo.addRun(
                    RunRecord(
                        workflowId = wf.id, workflowName = wf.name, emoji = wf.emoji, trigger = trigger,
                        startedAt = start, endedAt = end, status = result.status, summary = result.summary, logs = logs,
                    ),
                )
                Repo.markRun(wf.id, start, result.status)
                val secs = (end - start) / 1000
                val dur = if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"
                val head = when (result.status) {
                    RunStatus.SUCCESS -> "✓ ${wf.name} finished"
                    RunStatus.STOPPED -> "■ ${wf.name} stopped"
                    RunStatus.FAILED, RunStatus.SKIPPED, RunStatus.NEVER -> "✗ ${wf.name} failed"
                }
                Notifier.post(
                    ctx,
                    if (result.status == RunStatus.FAILED) Notifier.CH_ALERTS else Notifier.CH_RUNS,
                    head,
                    "${result.summary} · $dur",
                )
            }
        }
    }
}
