package com.taskpilot.app.core

import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMachineTest {
    // ---- tiny list builder: "a" = a normal step called a
    private fun st(name: String, vararg p: Pair<String, String>, on: Boolean = true) =
        Step(id = name, type = StepType.NOTIFY, label = name, enabled = on, p = p.toMap())
    private fun iff(on: Boolean = true) = Step(id = "if", type = StepType.IF, enabled = on)
    private val otherwise = Step(id = "else", type = StepType.ELSE)
    private val end = Step(id = "end", type = StepType.END)
    private fun rep(mode: String, vararg p: Pair<String, String>, on: Boolean = true) =
        Step(id = "rep", type = StepType.REPEAT, enabled = on, p = mapOf("mode" to mode) + p)
    private fun stop(result: String = "ok", msg: String = "") =
        Step(id = "stop", type = StepType.STOP, p = mapOf("result" to result, "message" to msg))

    private data class Out(val ran: List<String>, val finish: FlowNext.Finish, val notes: List<String>, val delays: List<Long>)

    /**
     * Drives the machine like Runner does. [ok] says whether a step works (by label and try number),
     * [check] answers each condition check in order, [files] answers "how many files".
     */
    private fun drive(
        steps: List<Step>,
        from: Int = 0,
        until: Int? = null,
        maxActions: Int = FlowMachine.MAX_ACTIONS,
        files: Int = 0,
        check: MutableList<Boolean> = mutableListOf(),
        ok: (String, Int) -> Boolean = { _, _ -> true },
    ): Out {
        val m = FlowMachine(steps, from, until, maxActions)
        val ran = ArrayList<String>()
        val notes = ArrayList<String>()
        val delays = ArrayList<Long>()
        var (s, n) = m.next(m.start())
        var guard = 0
        while (true) {
            check(guard++ < 100_000) { "the test driver looped" }
            val r = when (n) {
                is FlowNext.Finish -> return Out(ran, n, notes, delays)
                is FlowNext.Run -> {
                    val label = steps[n.pc].label
                    ran += if (n.attempt > 1) "$label#${n.attempt}" else label
                    if (n.delayMs > 0) delays += n.delayMs
                    m.answer(s, FlowAnswer.StepDone(ok(label, n.attempt)))
                }
                is FlowNext.Check -> m.answer(s, if (check.isEmpty()) FlowAnswer.CheckFailed("no answer") else FlowAnswer.Checked(check.removeAt(0)))
                is FlowNext.Count -> m.answer(s, FlowAnswer.Counted(files))
                is FlowNext.Note -> { notes += n.text; m.next(s) }
                is FlowNext.Skipped -> { ran += "-" + steps[n.pc].label; m.next(s) }
            }
            s = r.first; n = r.second
        }
    }

    @Test fun plainListRunsInOrder() {
        val o = drive(listOf(st("a"), st("b"), st("c")))
        assertEquals(listOf("a", "b", "c"), o.ran)
        assertEquals(RunStatus.SUCCESS, o.finish.status)
    }

    @Test fun turnedOffStepsAreSkipped() {
        assertEquals(listOf("a", "-b", "c"), drive(listOf(st("a"), st("b", on = false), st("c"))).ran)
    }

    @Test fun ifTrueRunsTheIfPartOnly() {
        val steps = listOf(iff(), st("yes"), otherwise, st("no"), end, st("after"))
        assertEquals(listOf("yes", "after"), drive(steps, check = mutableListOf(true)).ran)
    }

    @Test fun ifFalseRunsTheOtherwisePart() {
        val steps = listOf(iff(), st("yes"), otherwise, st("no"), end, st("after"))
        assertEquals(listOf("no", "after"), drive(steps, check = mutableListOf(false)).ran)
    }

    @Test fun ifFalseWithoutOtherwiseSkipsToAfterTheEnd() {
        val steps = listOf(iff(), st("yes"), end, st("after"))
        assertEquals(listOf("after"), drive(steps, check = mutableListOf(false)).ran)
    }

    @Test fun turnedOffIfCountsAsMet() {
        val steps = listOf(iff(on = false), st("yes"), otherwise, st("no"), end)
        assertEquals(listOf("yes"), drive(steps).ran)            // no check asked for
    }

    @Test fun nestedIfs() {
        val steps = listOf(iff(), iff(), st("both"), otherwise, st("outer only"), end, otherwise, st("neither"), end)
        assertEquals(listOf("outer only"), drive(steps, check = mutableListOf(true, false)).ran)
        assertEquals(listOf("both"), drive(steps, check = mutableListOf(true, true)).ran)
        assertEquals(listOf("neither"), drive(steps, check = mutableListOf(false)).ran)
    }

    @Test fun checkThatCannotBeMadeFailsTheRun() {
        val o = drive(listOf(iff(), st("x"), end))
        assertEquals(RunStatus.FAILED, o.finish.status)
        assertEquals("no answer", o.finish.message)
    }

    @Test fun repeatTimes() {
        val o = drive(listOf(rep("times", "count" to "3"), st("x"), end, st("after")))
        assertEquals(listOf("x", "x", "x", "after"), o.ran)
        assertEquals(listOf("Round 1 of 3", "Round 2 of 3", "Round 3 of 3"), o.notes)
    }

    @Test fun repeatZeroTimesSkipsTheBlock() {
        val o = drive(listOf(rep("times", "count" to "0"), st("x"), end, st("after")))
        assertEquals(listOf("after"), o.ran)
        assertEquals(listOf("Repeat 0 times — skipped"), o.notes)
    }

    @Test fun repeatOnceHasNoRoundNotes() {
        val o = drive(listOf(rep("times", "count" to "1"), st("x"), end))
        assertEquals(listOf("x"), o.ran)
        assertEquals(emptyList<String>(), o.notes)
    }

    @Test fun turnedOffRepeatRunsOnce() {
        assertEquals(listOf("x"), drive(listOf(rep("times", "count" to "5", on = false), st("x"), end)).ran)
        assertEquals(listOf("x"), drive(listOf(rep("while", on = false), st("x"), end)).ran)
    }

    @Test fun repeatForEachFile() {
        val o = drive(listOf(rep("each"), st("send"), end), files = 2)
        assertEquals(listOf("send", "send"), o.ran)
        assertEquals(listOf("File 1 of 2", "File 2 of 2"), o.notes)
    }

    @Test fun forEachWithNoFiles() {
        val o = drive(listOf(rep("each"), st("send"), end, st("after")), files = 0)
        assertEquals(listOf("after"), o.ran)
        assertEquals(listOf("No files were found — nothing to go through"), o.notes)
    }

    @Test fun repeatUntilChecksAfterEachRound() {
        // not yet, not yet, done
        val o = drive(listOf(rep("until"), st("x"), end, st("after")), check = mutableListOf(false, false, true))
        assertEquals(listOf("x", "x", "x", "after"), o.ran)
    }

    @Test fun repeatWhileChecksBeforeEachRound() {
        val o = drive(listOf(rep("while"), st("x"), end, st("after")), check = mutableListOf(true, true, false))
        assertEquals(listOf("x", "x", "after"), o.ran)
        val none = drive(listOf(rep("while"), st("x"), end, st("after")), check = mutableListOf(false))
        assertEquals(listOf("after"), none.ran)
    }

    @Test fun untilStopsAtItsLimitAndCarriesOn() {
        val o = drive(listOf(rep("until", "max" to "3"), st("x"), end, st("after")), check = MutableList(10) { false })
        assertEquals(listOf("x", "x", "x", "after"), o.ran)
        assertTrue(o.notes.last().startsWith("Stopped repeating after 3 rounds"))
        assertEquals(RunStatus.SUCCESS, o.finish.status)
    }

    @Test fun whileStopsAtItsLimit() {
        val o = drive(listOf(rep("while", "max" to "2"), st("x"), end, st("after")), check = MutableList(10) { true })
        assertEquals(listOf("x", "x", "after"), o.ran)
    }

    @Test fun loopsInsideLoops() {
        val steps = listOf(rep("times", "count" to "2"), st("a"), rep("times", "count" to "2"), st("b"), end, end)
        assertEquals(listOf("a", "b", "b", "a", "b", "b"), drive(steps).ran)
    }

    @Test fun ifInsideALoop() {
        val steps = listOf(rep("times", "count" to "3"), iff(), st("yes"), otherwise, st("no"), end, end)
        assertEquals(listOf("yes", "no", "yes"), drive(steps, check = mutableListOf(true, false, true)).ran)
    }

    @Test fun aLoopThatNeverEndsIsStopped() {
        val steps = listOf(rep("times", "count" to "1000"), rep("times", "count" to "1000"), st("x"), end, end)
        val o = drive(steps, maxActions = 50)
        assertEquals(RunStatus.FAILED, o.finish.status)
        assertTrue(o.finish.message.contains("never ends"))
        assertTrue(o.ran.size <= 50)
    }

    @Test fun emptyLoopsInsideLoopsAlsoEnd() {
        val o = drive(listOf(rep("times", "count" to "1000"), rep("times", "count" to "1000"), end, end), maxActions = 100)
        assertEquals(RunStatus.FAILED, o.finish.status)
    }

    @Test fun failedStepStopsTheRunByDefault() {
        val o = drive(listOf(st("a"), st("b"), st("c")), ok = { l, _ -> l != "b" })
        assertEquals(listOf("a", "b"), o.ran)
        assertEquals(RunStatus.FAILED, o.finish.status)
    }

    @Test fun retryThenWorks() {
        val steps = listOf(st("b", "onFail" to "retry", "retries" to "2", "retryWait" to "7"), st("c"))
        val o = drive(steps, ok = { l, a -> l != "b" || a == 3 })
        assertEquals(listOf("b", "b#2", "b#3", "c"), o.ran)
        assertEquals(listOf(7000L, 7000L), o.delays)
        assertEquals(listOf("Trying again (2 of 3)", "Trying again (3 of 3)"), o.notes)
        assertEquals(RunStatus.SUCCESS, o.finish.status)
    }

    @Test fun retryGivesUp() {
        val o = drive(listOf(st("b", "onFail" to "retry", "retries" to "1"), st("c")), ok = { l, _ -> l != "b" })
        assertEquals(listOf("b", "b#2"), o.ran)
        assertEquals(RunStatus.FAILED, o.finish.status)
    }

    @Test fun retryCountStartsAgainForTheNextStep() {
        val steps = listOf(st("a", "onFail" to "retry", "retries" to "1"), st("b", "onFail" to "retry", "retries" to "1"))
        val o = drive(steps, ok = { _, a -> a == 2 })
        assertEquals(listOf("a", "a#2", "b", "b#2"), o.ran)
    }

    @Test fun continueAfterFailureAndCheckIt() {
        // "If the previous step failed → notify"
        val steps = listOf(st("try", "onFail" to "continue"), iff(), st("worked"), otherwise, st("tell me"), end)
        val m = FlowMachine(steps)
        var (s, n) = m.next(m.start())
        assertEquals(FlowNext.Run(0, 1, 0), n)
        val r = m.answer(s, FlowAnswer.StepDone(false))
        s = r.first
        assertTrue(r.second is FlowNext.Note)
        assertEquals(false, s.lastOk)
        val c = m.next(s)
        assertEquals(FlowNext.Check(1), c.second)
        val o = drive(steps, ok = { l, _ -> l != "try" }, check = mutableListOf(false))
        assertEquals(listOf("try", "tell me"), o.ran)
        assertEquals(RunStatus.SUCCESS, o.finish.status)
    }

    @Test fun stopStep() {
        val ok = drive(listOf(st("a"), stop(), st("never")))
        assertEquals(listOf("a"), ok.ran)
        assertEquals(RunStatus.SUCCESS, ok.finish.status)
        assertTrue(ok.finish.byStopStep)
        val bad = drive(listOf(iff(), stop("fail", "No files today"), end, st("x")), check = mutableListOf(true))
        assertEquals(RunStatus.FAILED, bad.finish.status)
        assertEquals("No files today", bad.finish.message)
    }

    @Test fun turnedOffStopIsPassedOver() {
        val steps = listOf(st("a"), stop().copy(enabled = false), st("b"))
        assertEquals(listOf("a", "b"), drive(steps).ran)
    }

    @Test fun runFromTheMiddleOfABlock() {
        // "Run from here" on the step inside the Otherwise part: the End just passes.
        val steps = listOf(iff(), st("yes"), otherwise, st("no"), end, st("after"))
        assertEquals(listOf("no", "after"), drive(steps, from = 3).ran)
        // …inside the If part: reaching "Otherwise" jumps over it.
        assertEquals(listOf("yes", "after"), drive(steps, from = 1).ran)
        // …inside a loop body: one pass, no repeating.
        assertEquals(listOf("x", "after"), drive(listOf(rep("times", "count" to "3"), st("x"), end, st("after")), from = 1).ran)
    }

    @Test fun untilStopsBeforeThatPosition() {
        val steps = listOf(st("a"), iff(), st("b"), end, st("c"), st("target"))
        assertEquals(listOf("a", "b", "c"), drive(steps, until = 5, check = mutableListOf(true)).ran)
        assertEquals(listOf("only"), drive(listOf(st("a"), st("only")), from = 1, until = 2).ran)
    }

    @Test fun emptyListFinishesAtOnce() {
        assertEquals(RunStatus.SUCCESS, drive(emptyList()).finish.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun answeringTheWrongQuestionIsABug() {
        val m = FlowMachine(listOf(st("a")))
        val (s, _) = m.next(m.start())
        m.answer(s, FlowAnswer.Checked(true))
    }
}
