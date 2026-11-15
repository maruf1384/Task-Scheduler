package com.taskpilot.app.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    private val json = Json

    @Test fun `statuses keep the words older versions saved`() {
        assertEquals("\"success\"", json.encodeToString(RunStatus.Ser, RunStatus.SUCCESS))
        assertEquals("\"failed\"", json.encodeToString(RunStatus.Ser, RunStatus.FAILED))
        assertEquals("\"stopped\"", json.encodeToString(RunStatus.Ser, RunStatus.STOPPED))
        assertEquals("\"\"", json.encodeToString(RunStatus.Ser, RunStatus.NEVER))
        assertEquals("\"ok\"", json.encodeToString(StepStatus.Ser, StepStatus.OK))
        assertEquals("\"skipped\"", json.encodeToString(StepStatus.Ser, StepStatus.SKIPPED))
        assertEquals("\"Scheduled\"", json.encodeToString(Trigger.Ser, Trigger.SCHEDULED))
        assertEquals("\"Manual\"", json.encodeToString(Trigger.Ser, Trigger.MANUAL))
    }

    @Test fun `every value survives a round trip`() {
        for (v in RunStatus.entries) assertEquals(v, json.decodeFromString(RunStatus.Ser, json.encodeToString(RunStatus.Ser, v)))
        for (v in StepStatus.entries) assertEquals(v, json.decodeFromString(StepStatus.Ser, json.encodeToString(StepStatus.Ser, v)))
        for (v in Trigger.entries) assertEquals(v, json.decodeFromString(Trigger.Ser, json.encodeToString(Trigger.Ser, v)))
    }

    @Test fun `an unknown word never breaks loading`() {
        assertEquals(RunStatus.NEVER, json.decodeFromString(RunStatus.Ser, "\"paused\""))
        assertEquals(StepStatus.SKIPPED, json.decodeFromString(StepStatus.Ser, "\"retried\""))
        assertEquals(Trigger.MANUAL, json.decodeFromString(Trigger.Ser, "\"Capture\""))   // written by 1.1–1.3
    }

    @Test fun `wire words are unique`() {
        assertEquals(RunStatus.entries.size, RunStatus.entries.map { it.wire }.toSet().size)
        assertEquals(StepStatus.entries.size, StepStatus.entries.map { it.wire }.toSet().size)
        assertEquals(Trigger.entries.size, Trigger.entries.map { it.wire }.toSet().size)
    }

    @Test fun `accessibility has three states`() {
        assertEquals(A11yState.OFF, A11yState.of(enabledInSettings = false, connected = false))
        assertEquals(A11yState.ON_NOT_RUNNING, A11yState.of(enabledInSettings = true, connected = false))
        assertEquals(A11yState.READY, A11yState.of(enabledInSettings = true, connected = true))
        assertEquals(A11yState.READY, A11yState.of(enabledInSettings = false, connected = true))
    }

    @Test fun `the trace keeps only the last lines and skips non-moves`() {
        Trace.clear()
        Trace.clock = { 0L }
        Trace.move("Engine", "Idle", "Idle", "Finish")
        assertEquals(0, Trace.dump().size)
        repeat(Trace.MAX + 50) { Trace.log("Test", "line $it") }
        val d = Trace.dump()
        assertEquals(Trace.MAX, d.size)
        assertTrue(d.last().endsWith("Test: line ${Trace.MAX + 49}"))
        assertTrue(d.first().endsWith("Test: line 50"))
        Trace.clear()
        Trace.clock = System::currentTimeMillis
    }

    @Test fun `update messages`() {
        assertEquals(null, UpdateState.Idle.message)
        assertTrue(UpdateState.Preparing("TaskPilot-1.4.0.apk").busy)
        assertTrue(UpdateState.Failed("Not enough storage space.").isError)
        assertTrue(UpdateState.Failed("x").message!!.contains("x"))
        assertEquals("That file isn't a TaskPilot APK.", UpdateState.Rejected("That file isn't a TaskPilot APK.").message)
    }
}
