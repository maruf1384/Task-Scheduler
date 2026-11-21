package com.taskpilot.app.engine

import android.content.Context
import android.os.PowerManager
import com.taskpilot.app.core.Blocks
import com.taskpilot.app.core.Conditions
import com.taskpilot.app.core.EngineMode
import com.taskpilot.app.core.FlowAnswer
import com.taskpilot.app.core.FlowMachine
import com.taskpilot.app.core.FlowNext
import com.taskpilot.app.core.FlowState
import com.taskpilot.app.core.LoopFrame
import com.taskpilot.app.core.OnFail
import com.taskpilot.app.core.RepeatMode
import com.taskpilot.app.core.Trace
import com.taskpilot.app.core.Owner
import com.taskpilot.app.core.Refusal
import com.taskpilot.app.core.RunProgress
import com.taskpilot.app.core.RunStatus
import com.taskpilot.app.core.StepStatus
import com.taskpilot.app.core.Trigger
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.RunRecord
import com.taskpilot.app.data.StepLog
import com.taskpilot.app.data.StepType
import com.taskpilot.app.data.Workflow
import com.taskpilot.app.ui.StepCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime

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
    /** Inside a Repeat: the round now running (1, 2 …) and how many in all (0 = until/while). */
    var loopRound: Int = 0
    var loopTotal: Int = 0
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
        Blocks.of(wf.steps).firstProblem()?.let {
            Trace.log("Flow", "refused: steps don't fit together")
            if (trigger == Trigger.SCHEDULED) recordRefusal(app, wf, Refusal.BROKEN_BLOCKS, it)
            return Refusal.BROKEN_BLOCKS
        }
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
    private fun recordRefusal(ctx: Context, wf: Workflow, why: Refusal, detail: String = "") {
        val serviceOff = why == Refusal.SERVICE_OFF || why == Refusal.BROKEN_BLOCKS
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
                summary = if (detail.isBlank()) why.message else "${why.message} $detail",
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

    /** History keeps this many lines of one run (a Repeat could otherwise write thousands). */
    private const val MAX_LOGS = 400

    /**
     * Runs [wf] from step [from] (stopping on reaching [until]), reporting each step through
     * [progress] and adding to [logs] (so a caller that is cancelled still has the steps done so
     * far). If / Otherwise / Repeat / Stop are decided by `core/FlowMachine`; this function only
     * does what it asks. Used by a normal run and by "Pick on screen → run the earlier steps first".
     * Throws CancellationException when stopped.
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
        val steps = wf.steps
        val rc = RunContext(startedAt)
        val machine = FlowMachine(steps, from, until)
        machine.blocks.firstProblem()?.let { return StepsResult(RunStatus.FAILED, it) }
        val env = PhoneConditions(ctx, svc, rc)
        var done = 0
        var carriedOn = 0
        var lastFailure = ""
        /** "For each file" loops: the files they go through, by the Repeat's position. */
        val eachFiles = HashMap<Int, List<File>>()

        fun log(l: StepLog) {
            when {
                logs.size < MAX_LOGS -> logs += l
                logs.size == MAX_LOGS -> logs += StepLog(l.index, "…", StepStatus.SKIPPED, "History keeps the first $MAX_LOGS lines of a run")
            }
        }

        fun title(i: Int) = StepCatalog.titleOf(steps[i])

        fun show(i: Int, extra: String = "") {
            val loop = rc.loopRound.takeIf { it > 0 }?.let { r -> if (rc.loopTotal > 0) " · round $r/${rc.loopTotal}" else " · round $r" } ?: ""
            progress(RunProgress(wf.id, wf.name, i, steps.size, title(i), startedAt))
            rc.pillPrefix = "▶ ${i + 1}/${steps.size} · ${title(i)}$loop$extra"
            svc.updateRunPill(rc.pillPrefix)
        }

        /** Keeps rc.files and the {loop.…} placeholders in step with the Repeat that is going round. */
        fun followLoops(old: List<LoopFrame>, new: List<LoopFrame>) {
            for (f in old) if (f.mode == RepeatMode.EACH_FILE && new.none { it.at == f.at }) {
                eachFiles.remove(f.at)?.let { rc.files = it }           // left the loop: all files again
            }
            for (f in new) if (f.mode == RepeatMode.EACH_FILE) {
                val before = old.firstOrNull { it.at == f.at }
                if (before == null || before.round != f.round) eachFiles[f.at]?.getOrNull(f.round - 1)?.let { rc.files = listOf(it) }
            }
            val inner = new.lastOrNull()
            rc.loopRound = inner?.round ?: 0
            rc.loopTotal = inner?.takeIf { it.mode == RepeatMode.TIMES || it.mode == RepeatMode.EACH_FILE }?.limit ?: 0
        }

        var (state, next) = machine.next(machine.start())
        while (true) {
            val before = state.frames
            val r: Pair<FlowState, FlowNext> = when (val n = next) {
                is FlowNext.Finish -> {
                    Trace.log("Flow", "Finish ${n.status.name}${if (n.byStopStep) " (Stop step)" else ""}")
                    val tail = if (carriedOn > 0) " · $carriedOn failed, carried on" else ""
                    return when {
                        n.status == RunStatus.SUCCESS && n.byStopStep -> StepsResult(RunStatus.SUCCESS, n.message + tail)
                        n.status == RunStatus.SUCCESS -> StepsResult(RunStatus.SUCCESS, "$done steps done$tail")
                        n.message.isNotBlank() -> StepsResult(n.status, n.message)
                        else -> StepsResult(n.status, lastFailure.ifBlank { "Failed" })
                    }
                }
                is FlowNext.Skipped -> {
                    log(StepLog(n.pc, title(n.pc), StepStatus.SKIPPED, "Turned off"))
                    machine.next(state)
                }
                is FlowNext.Note -> {
                    log(StepLog(n.pc, title(n.pc), if (n.warning) StepStatus.FAILED else StepStatus.OK, n.text))
                    machine.next(state)
                }
                is FlowNext.Count -> {
                    eachFiles[n.pc] = rc.files
                    machine.answer(state, FlowAnswer.Counted(rc.files.size))
                }
                is FlowNext.Check -> {
                    val step = steps[n.pc]
                    show(n.pc)
                    val t0 = System.currentTimeMillis()
                    val problems = Conditions.problems(step)
                    if (problems.isNotEmpty()) {
                        val msg = "Step ${n.pc + 1} · ${title(n.pc)} — ${problems.first()}"
                        log(StepLog(n.pc, title(n.pc), StepStatus.FAILED, problems.first()))
                        machine.answer(state, FlowAnswer.CheckFailed(msg))
                    } else {
                        val conds = Conditions.of(step)
                        val all = Conditions.matchAll(step)
                        // Only an If waits ("wait up to N s for it to become true"); Repeat checks once per round.
                        val waitMs = if (step.type == StepType.IF) step.i("waitUpTo", 0).coerceIn(0, 3600) * 1000L else 0L
                        var ev = Conditions.eval(conds, all, env, LocalDateTime.now(), state.lastOk)
                        while (!ev.met && System.currentTimeMillis() - t0 < waitMs) {
                            delay(1000)
                            ev = Conditions.eval(conds, all, env, LocalDateTime.now(), state.lastOk)
                        }
                        Trace.log("Flow", "Check step ${n.pc + 1}: ${if (ev.met) "met" else "not met"}")
                        log(StepLog(n.pc, title(n.pc), StepStatus.OK, ev.detail, System.currentTimeMillis() - t0))
                        machine.answer(state, FlowAnswer.Checked(ev.met))
                    }
                }
                is FlowNext.Run -> {
                    val i = n.pc
                    val step = steps[i]
                    if (n.delayMs > 0) {
                        show(i, " · trying again in ${n.delayMs / 1000}s")
                        delay(n.delayMs)
                    }
                    show(i, if (n.attempt > 1) " · try ${n.attempt}" else "")
                    val t0 = System.currentTimeMillis()
                    val out: Outcome = try {
                        withTimeoutOrNull(40 * 60_000L) { StepExec.run(ctx, svc, step, rc) }
                            ?: Outcome(false, "Took longer than 40 minutes")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Outcome(false, e.message ?: e.javaClass.simpleName)
                    }
                    log(StepLog(i, title(i), if (out.ok) StepStatus.OK else StepStatus.FAILED, out.message, System.currentTimeMillis() - t0))
                    if (out.ok) done++ else {
                        lastFailure = "Step ${i + 1} · ${title(i)} — ${out.message}"
                        if (OnFail.of(step) == OnFail.CONTINUE) carriedOn++
                    }
                    machine.answer(state, FlowAnswer.StepDone(out.ok))
                }
            }
            state = r.first
            next = r.second
            followLoops(before, state.frames)
        }
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
