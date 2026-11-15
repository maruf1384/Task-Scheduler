package com.taskpilot.app.core

import com.taskpilot.app.core.EngineRules.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRulesTest {
    private val run = RunProgress("wf", "Weekly", 0, 3, "Wake", 1000)
    private val running = EngineMode.Running(run)
    private val aiming = EngineMode.Capturing(CapturePhase.AIMING)
    private val recording = EngineMode.Recording("com.google.android.gm", "Gmail")

    private fun next(mode: EngineMode, e: EngineEvent, ready: Boolean = true) = EngineRules.next(mode, e, ready)

    @Test fun `anything can start from idle`() {
        for (m in listOf(running, aiming, recording)) {
            assertEquals(Result.Moved(m), next(EngineMode.Idle, EngineEvent.Start(m)))
        }
    }

    @Test fun `nothing starts while the service is off`() {
        assertEquals(Result.Refused(Refusal.SERVICE_OFF), next(EngineMode.Idle, EngineEvent.Start(running), ready = false))
    }

    @Test fun `a second owner is refused with the reason`() {
        assertEquals(Result.Refused(Refusal.RUNNING), next(running, EngineEvent.Start(recording)))
        assertEquals(Result.Refused(Refusal.RUNNING), next(running, EngineEvent.Start(running)))
        assertEquals(Result.Refused(Refusal.CAPTURING), next(aiming, EngineEvent.Start(running)))
        assertEquals(Result.Refused(Refusal.RECORDING), next(recording, EngineEvent.Start(running)))
        assertEquals(Result.Refused(Refusal.RECORDING), next(recording, EngineEvent.Start(aiming)))
    }

    @Test fun `starting Idle is not a thing`() {
        assertEquals(Result.Ignored, next(EngineMode.Idle, EngineEvent.Start(EngineMode.Idle)))
    }

    @Test fun `the owner can report progress`() {
        val step2 = EngineMode.Running(run.copy(index = 1, stepTitle = "Open file"))
        assertEquals(Result.Moved(step2), next(running, EngineEvent.Update(step2)))
        val reading = EngineMode.Capturing(CapturePhase.READING)
        assertEquals(Result.Moved(reading), next(aiming, EngineEvent.Update(reading)))
    }

    @Test fun `progress from someone who is not the owner is ignored`() {
        assertEquals(Result.Ignored, next(recording, EngineEvent.Update(running)))
        assertEquals(Result.Ignored, next(EngineMode.Idle, EngineEvent.Update(running)))
    }

    @Test fun `only the owner can finish`() {
        assertEquals(Result.Moved(EngineMode.Idle), next(running, EngineEvent.Finish(Owner.RUN)))
        // The old bug: a capture ending switched the service to idle in the middle of a run.
        assertEquals(Result.Ignored, next(running, EngineEvent.Finish(Owner.CAPTURE)))
        assertEquals(Result.Ignored, next(running, EngineEvent.Finish(Owner.RECORD)))
        assertEquals(Result.Ignored, next(EngineMode.Idle, EngineEvent.Finish(Owner.RUN)))
    }

    @Test fun `losing the service always ends in idle`() {
        for (m in listOf(running, aiming, recording)) {
            assertEquals(Result.Moved(EngineMode.Idle), next(m, EngineEvent.ServiceLost, ready = false))
        }
        assertEquals(Result.Ignored, next(EngineMode.Idle, EngineEvent.ServiceLost, ready = false))
    }

    @Test fun `the service is active exactly when someone owns the screen`() {
        assertFalse(EngineRules.serviceActive(EngineMode.Idle))
        for (m in listOf(running, aiming, recording)) assertTrue(EngineRules.serviceActive(m))
    }

    @Test fun `tags are short and stable`() {
        assertEquals("Idle", EngineMode.Idle.tag)
        assertEquals("Running", running.tag)
        assertEquals("Capturing.AIMING", aiming.tag)
        assertEquals("Recording", recording.tag)
    }
}
