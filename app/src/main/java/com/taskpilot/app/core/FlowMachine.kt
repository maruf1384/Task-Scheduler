package com.taskpilot.app.core

import com.taskpilot.app.data.Step

/** How a Repeat step repeats. */
enum class RepeatMode(val wire: String, val label: String) {
    TIMES("times", "A number of times"),
    EACH_FILE("each", "For each file found"),
    UNTIL("until", "Until something is true"),
    WHILE("while", "As long as something is true");

    companion object {
        fun of(step: Step): RepeatMode = entries.firstOrNull { it.wire == step.s("mode") } ?: TIMES
    }
}

/** What a normal step does when it fails. */
enum class OnFail(val wire: String) {
    STOP("stop"), RETRY("retry"), CONTINUE("continue");

    companion object {
        fun of(step: Step): OnFail = entries.firstOrNull { it.wire == step.s("onFail") } ?: STOP
    }
}

/** One Repeat that is going round. */
data class LoopFrame(
    /** Position of the Repeat step. */
    val at: Int,
    val end: Int,
    val mode: RepeatMode,
    /** 1-based round now running (0 = a While loop that hasn't checked yet). */
    val round: Int,
    /** Rounds in all for TIMES / EACH_FILE; the safety limit for UNTIL / WHILE. */
    val limit: Int,
)

/** Where a run is. Immutable; [FlowMachine] returns the next one. */
data class FlowState(
    val pc: Int,
    val frames: List<LoopFrame> = emptyList(),
    /** Try number of the step at [pc] (1 = first try). */
    val attempt: Int = 1,
    /** How the last normal step went (null = none has run yet). */
    val lastOk: Boolean? = null,
    /** Steps run + conditions checked so far — a guard against loops that never end. */
    val actions: Int = 0,
    val waiting: Waiting = Waiting.NOTHING,
    /** Wait before the next try of a failed step. */
    val delayMs: Long = 0,
) {
    enum class Waiting { NOTHING, STEP, CHECK, COUNT }

    /** The innermost Repeat that is going round, if any. */
    val loop: LoopFrame? get() = frames.lastOrNull()
}

/** What the run must do next. */
sealed interface FlowNext {
    /** Run the normal step at [pc]; answer [FlowAnswer.StepDone]. */
    data class Run(val pc: Int, val attempt: Int, val delayMs: Long) : FlowNext

    /** The step at [pc] is turned off: log it and call [FlowMachine.next] again. */
    data class Skipped(val pc: Int) : FlowNext

    /** Check the conditions of the If / Repeat at [pc]; answer [FlowAnswer.Checked]. */
    data class Check(val pc: Int) : FlowNext

    /** "For each file" at [pc] is starting: answer [FlowAnswer.Counted] with how many files there are. */
    data class Count(val pc: Int) : FlowNext

    /** Something worth a line in History; call [FlowMachine.next] again. */
    data class Note(val pc: Int, val text: String, val warning: Boolean = false) : FlowNext

    /**
     * The run is over. [byStopStep] = a "Stop the automation" step ended it (with [message]).
     * [status] SUCCESS also covers "reached the end".
     */
    data class Finish(val status: RunStatus, val message: String = "", val byStopStep: Boolean = false) : FlowNext
}

sealed interface FlowAnswer {
    data class StepDone(val ok: Boolean) : FlowAnswer
    data class Checked(val met: Boolean) : FlowAnswer

    /** The conditions couldn't be checked (none set, or broken). */
    data class CheckFailed(val reason: String) : FlowAnswer
    data class Counted(val n: Int) : FlowAnswer
}

/**
 * Runs a flat list of steps that contains If / Otherwise / End and Repeat / End markers.
 *
 *     next(state) ──▶ Run(pc)    → the caller runs the step ──▶ answer(StepDone(ok))
 *                 ──▶ Check(pc)  → the caller checks the conditions ──▶ answer(Checked(met))
 *                 ──▶ Count(pc)  → the caller counts the files ──▶ answer(Counted(n))
 *                 ──▶ Skipped / Note → the caller logs it ──▶ next(state)
 *                 ──▶ Finish
 *
 * Rules (each has a test in FlowMachineTest):
 *  - If: conditions met → the If part; not met → the Otherwise part (or past the End).
 *    Reaching "Otherwise" from the If part jumps past the End.
 *  - Repeat TIMES n / EACH_FILE: n rounds (0 → skipped). UNTIL: body first, then check; stops
 *    when met. WHILE: check first; stops when not met. UNTIL/WHILE stop at their round limit
 *    and carry on after the End, with a warning in History.
 *  - A failed step: STOP (default) ends the run; RETRY tries again up to `retries` times, waiting
 *    `retryWait` seconds; CONTINUE goes on (and "the previous step worked" is then false).
 *  - A turned-off If counts as met; a turned-off Repeat runs its steps once; a turned-off Stop
 *    is passed over.
 *  - [until]: stop as soon as the run reaches or passes that position (for "Pick on screen →
 *    run the earlier steps first" and "Run only this step").
 *  - Never more than [maxActions] steps + checks: a loop that never ends is stopped.
 *  - Started in the middle ("Run from here"), an End whose Repeat isn't going round just passes.
 */
class FlowMachine(
    private val steps: List<Step>,
    private val from: Int = 0,
    private val until: Int? = null,
    val maxActions: Int = MAX_ACTIONS,
) {
    val blocks: Blocks = Blocks.of(steps)

    fun start(): FlowState = FlowState(pc = from.coerceIn(0, steps.size))

    fun next(state: FlowState): Pair<FlowState, FlowNext> {
        var s = state.copy(waiting = FlowState.Waiting.NOTHING)
        while (true) {
            if (until != null && s.pc >= until) return s to FlowNext.Finish(RunStatus.SUCCESS)
            if (s.pc >= steps.size) return s to FlowNext.Finish(RunStatus.SUCCESS)
            if (s.actions >= maxActions) {
                return s to FlowNext.Finish(RunStatus.FAILED, "Stopped after $maxActions steps — a Repeat probably never ends")
            }
            val st = steps[s.pc]
            when (blocks.roles[s.pc]) {
                Role.STEP -> {
                    if (!st.enabled) return s.copy(pc = s.pc + 1) to FlowNext.Skipped(s.pc)
                    val d = s.delayMs
                    return s.copy(waiting = FlowState.Waiting.STEP, delayMs = 0) to FlowNext.Run(s.pc, s.attempt, d)
                }
                Role.IF -> {
                    if (!st.enabled) { s = s.copy(pc = s.pc + 1); continue }
                    return s.copy(waiting = FlowState.Waiting.CHECK) to FlowNext.Check(s.pc)
                }
                Role.ELSE -> {
                    // Arrived here from the If part: the Otherwise part is not for this run.
                    val e = blocks.end(s.pc)
                    s = s.copy(pc = if (e >= 0) e + 1 else s.pc + 1)
                }
                Role.END -> {
                    val f = s.loop
                    val opener = blocks.opener(s.pc)
                    if (f == null || f.at != opener) { s = s.copy(pc = s.pc + 1); continue }
                    when (f.mode) {
                        RepeatMode.UNTIL ->
                            if (steps[f.at].enabled) return s.copy(waiting = FlowState.Waiting.CHECK) to FlowNext.Check(f.at)
                            else { s = exitLoop(s); continue }
                        RepeatMode.WHILE -> {
                            // Back to the Repeat to check again before the next round.
                            s = s.copy(pc = f.at)
                        }
                        RepeatMode.TIMES, RepeatMode.EACH_FILE -> {
                            if (f.round < f.limit) {
                                val nf = f.copy(round = f.round + 1)
                                // A round counts as an action, so even empty loops inside loops end.
                                s = s.copy(pc = f.at + 1, frames = s.frames.dropLast(1) + nf, actions = s.actions + 1)
                                val what = if (f.mode == RepeatMode.EACH_FILE) "File" else "Round"
                                return s to FlowNext.Note(f.at, "$what ${nf.round} of ${nf.limit}")
                            }
                            s = exitLoop(s)
                        }
                    }
                }
                Role.REPEAT -> {
                    val f = s.loop
                    if (f != null && f.at == s.pc && f.mode == RepeatMode.WHILE) {
                        return s.copy(waiting = FlowState.Waiting.CHECK) to FlowNext.Check(s.pc)
                    }
                    val end = blocks.end(s.pc)
                    if (end < 0) { s = s.copy(pc = s.pc + 1); continue }      // broken: runs refuse to start
                    val mode = if (st.enabled) RepeatMode.of(st) else RepeatMode.TIMES
                    when (mode) {
                        RepeatMode.TIMES -> {
                            val n = if (st.enabled) st.i("count", 3).coerceIn(0, MAX_ROUNDS) else 1
                            if (n == 0) {
                                val at = s.pc
                                s = s.copy(pc = end + 1)
                                return s to FlowNext.Note(at, "Repeat 0 times — skipped")
                            }
                            s = s.copy(pc = s.pc + 1, frames = s.frames + LoopFrame(s.pc, end, mode, 1, n))
                            if (n > 1) return s to FlowNext.Note(s.pc - 1, "Round 1 of $n")
                        }
                        RepeatMode.EACH_FILE -> return s.copy(waiting = FlowState.Waiting.COUNT) to FlowNext.Count(s.pc)
                        RepeatMode.UNTIL -> {
                            s = s.copy(pc = s.pc + 1, frames = s.frames + LoopFrame(s.pc, end, mode, 1, limitOf(st)))
                        }
                        RepeatMode.WHILE -> {
                            s = s.copy(frames = s.frames + LoopFrame(s.pc, end, mode, 0, limitOf(st)), waiting = FlowState.Waiting.CHECK)
                            return s to FlowNext.Check(s.pc)
                        }
                    }
                }
                Role.STOP -> {
                    if (!st.enabled) { s = s.copy(pc = s.pc + 1); continue }
                    val failed = st.s("result", "ok") == "fail"
                    val msg = st.s("message").ifBlank { if (failed) "Stopped by a “Stop” step" else "Finished early by a “Stop” step" }
                    return s to FlowNext.Finish(if (failed) RunStatus.FAILED else RunStatus.SUCCESS, msg, byStopStep = true)
                }
            }
        }
    }

    fun answer(state: FlowState, a: FlowAnswer): Pair<FlowState, FlowNext> {
        val s = state
        return when (a) {
            is FlowAnswer.StepDone -> {
                require(s.waiting == FlowState.Waiting.STEP) { "StepDone while waiting for ${s.waiting}" }
                val counted = s.copy(actions = s.actions + 1, waiting = FlowState.Waiting.NOTHING)
                if (a.ok) return next(counted.copy(pc = s.pc + 1, attempt = 1, lastOk = true))
                val st = steps[s.pc]
                when (OnFail.of(st)) {
                    OnFail.RETRY -> {
                        val retries = st.i("retries", 2).coerceIn(1, 10)
                        if (s.attempt <= retries) {
                            val wait = st.i("retryWait", 5).coerceIn(0, 600) * 1000L
                            return counted.copy(attempt = s.attempt + 1, delayMs = wait) to
                                FlowNext.Note(s.pc, "Trying again (${s.attempt + 1} of ${retries + 1})", warning = true)
                        }
                        counted to FlowNext.Finish(RunStatus.FAILED)
                    }
                    OnFail.CONTINUE -> {
                        val n = counted.copy(pc = s.pc + 1, attempt = 1, lastOk = false)
                        n to FlowNext.Note(s.pc, "Failed — carrying on, as set for this step", warning = true)
                    }
                    OnFail.STOP -> counted to FlowNext.Finish(RunStatus.FAILED)
                }
            }
            is FlowAnswer.CheckFailed -> s.copy(waiting = FlowState.Waiting.NOTHING) to FlowNext.Finish(RunStatus.FAILED, a.reason)
            is FlowAnswer.Checked -> {
                require(s.waiting == FlowState.Waiting.CHECK) { "Checked while waiting for ${s.waiting}" }
                val c = s.copy(actions = s.actions + 1, waiting = FlowState.Waiting.NOTHING)
                when (blocks.roles[s.pc]) {
                    Role.IF -> {
                        val target = if (a.met) s.pc + 1 else {
                            val o = blocks.otherwise(s.pc)
                            if (o >= 0) o + 1 else blocks.end(s.pc) + 1
                        }
                        next(c.copy(pc = target))
                    }
                    Role.REPEAT -> loopChecked(c, a.met)
                    // An Until loop is checked at its End: pc is the End, the frame says which Repeat.
                    Role.END -> loopChecked(c, a.met)
                    else -> next(c.copy(pc = s.pc + 1))
                }
            }
            is FlowAnswer.Counted -> {
                require(s.waiting == FlowState.Waiting.COUNT) { "Counted while waiting for ${s.waiting}" }
                val end = blocks.end(s.pc)
                val c = s.copy(waiting = FlowState.Waiting.NOTHING)
                if (a.n <= 0) return c.copy(pc = end + 1) to FlowNext.Note(s.pc, "No files were found — nothing to go through")
                val n = a.n.coerceAtMost(MAX_ROUNDS)
                c.copy(pc = s.pc + 1, frames = c.frames + LoopFrame(s.pc, end, RepeatMode.EACH_FILE, 1, n)) to
                    FlowNext.Note(s.pc, "File 1 of $n")
            }
        }
    }

    private fun loopChecked(s: FlowState, met: Boolean): Pair<FlowState, FlowNext> {
        val f = s.loop ?: return next(s.copy(pc = s.pc + 1))
        return when (f.mode) {
            RepeatMode.WHILE ->
                if (!met) next(exitLoop(s))
                else if (f.round >= f.limit) {
                    exitLoop(s) to FlowNext.Note(f.at, "Stopped repeating after ${f.limit} rounds (the limit set for this Repeat)", warning = true)
                } else {
                    val nf = f.copy(round = f.round + 1)
                    s.copy(pc = f.at + 1, frames = s.frames.dropLast(1) + nf) to FlowNext.Note(f.at, "Round ${nf.round}")
                }
            RepeatMode.UNTIL ->
                if (met) next(exitLoop(s))
                else if (f.round >= f.limit) {
                    exitLoop(s) to FlowNext.Note(f.at, "Stopped repeating after ${f.limit} rounds (the limit set for this Repeat)", warning = true)
                } else {
                    val nf = f.copy(round = f.round + 1)
                    s.copy(pc = f.at + 1, frames = s.frames.dropLast(1) + nf) to FlowNext.Note(f.at, "Round ${nf.round}")
                }
            RepeatMode.TIMES, RepeatMode.EACH_FILE -> next(s.copy(pc = s.pc + 1))
        }
    }

    private fun exitLoop(s: FlowState): FlowState {
        val f = s.loop ?: return s.copy(pc = s.pc + 1)
        return s.copy(pc = f.end + 1, frames = s.frames.dropLast(1))
    }

    private fun limitOf(st: Step): Int = if (st.enabled) st.i("max", 20).coerceIn(1, MAX_ROUNDS) else 1

    companion object {
        const val MAX_ACTIONS = 10_000
        const val MAX_ROUNDS = 1000
    }
}
