package com.taskpilot.app.data

import com.taskpilot.app.core.RunStatus
import com.taskpilot.app.core.StepStatus
import com.taskpilot.app.core.Trigger
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files on the user's phone were written by versions 1.0–1.3, where statuses were plain
 * strings. These tests pin that every one of them still loads, and that what this version writes
 * is something those older versions could read back.
 */
class SavedFilesTest {
    private val workflows13 = """
        [{"id":"w1","name":"Weekly Demand → WhatsApp","emoji":"📊",
          "steps":[
            {"id":"s1","type":"WAKE_UNLOCK","enabled":true,"label":"","p":{"strict":"true","usePin":"true"}},
            {"id":"s2","type":"EDIT_FILE","enabled":true,"label":"Set the dates to last week","p":{"path":"/storage/emulated/0/Download/x.py","save":"copy","weekStart":"7","edits":"[]"}},
            {"id":"s3","type":"SEND_WHATSAPP","enabled":false,"label":"","p":{"phone":"01521216482","app":"com.whatsapp","autoSend":"true","caption":""}}
          ],
          "schedule":{"enabled":true,"hour":0,"minute":40,"days":[1,2,3,4,5,6,7]},
          "createdAt":1761600000000,"lastRunAt":1761700000000,"lastStatus":"success"},
         {"id":"w2","name":"Gmail automation","emoji":"🧭","steps":[],
          "schedule":{"enabled":false,"hour":0,"minute":40,"days":[1,2,3,4,5,6,7]},
          "createdAt":1761600000001,"lastRunAt":0,"lastStatus":""},
         {"id":"w3","name":"Old","steps":[],"lastStatus":"stopped"},
         {"id":"w4","name":"Failed","steps":[],"lastStatus":"failed"}]
    """.trimIndent()

    private val runs13 = """
        [{"id":"r1","workflowId":"w1","workflowName":"Weekly Demand → WhatsApp","emoji":"📊","trigger":"Scheduled",
          "startedAt":1761700000000,"endedAt":1761700090000,"status":"failed","summary":"Step 7 · Send to WhatsApp number — No Send button found",
          "logs":[{"index":0,"title":"Wake & unlock","status":"ok","message":"Screen on","durationMs":700},
                  {"index":1,"title":"Wait","status":"skipped","message":"Turned off","durationMs":0},
                  {"index":6,"title":"Send to WhatsApp number","status":"failed","message":"No Send button found","durationMs":45000}]},
         {"id":"r2","workflowId":"w1","workflowName":"Weekly","emoji":"📊","trigger":"Manual","startedAt":1,"endedAt":2,"status":"success","summary":"10 steps done","logs":[]},
         {"id":"r3","workflowId":"w1","workflowName":"Weekly","emoji":"📊","trigger":"Manual","startedAt":1,"endedAt":2,"status":"stopped","summary":"Stopped by you","logs":[]},
         {"id":"r4","workflowId":"w1","workflowName":"Weekly","trigger":"Scheduled","startedAt":1,"endedAt":1,"status":"failed","summary":"The TaskPilot accessibility service is off. Turn it on in Setup."}]
    """.trimIndent()

    @Test fun `automations saved by 1_3 load unchanged`() {
        val w = Repo.json.decodeFromString<List<Workflow>>(workflows13)
        assertEquals(4, w.size)
        assertEquals(RunStatus.SUCCESS, w[0].lastStatus)
        assertEquals(RunStatus.NEVER, w[1].lastStatus)
        assertEquals(RunStatus.STOPPED, w[2].lastStatus)
        assertEquals(RunStatus.FAILED, w[3].lastStatus)
        assertEquals(listOf(StepType.WAKE_UNLOCK, StepType.EDIT_FILE, StepType.SEND_WHATSAPP), w[0].steps.map { it.type })
        assertEquals("01521216482", w[0].steps[2].s("phone"))
        assertEquals(false, w[0].steps[2].enabled)
        assertEquals(40, w[0].schedule.minute)
        assertEquals("⚡", w[2].emoji)
    }

    @Test fun `history saved by 1_3 loads unchanged`() {
        val r = Repo.json.decodeFromString<List<RunRecord>>(runs13)
        assertEquals(listOf(RunStatus.FAILED, RunStatus.SUCCESS, RunStatus.STOPPED, RunStatus.FAILED), r.map { it.status })
        assertEquals(listOf(Trigger.SCHEDULED, Trigger.MANUAL, Trigger.MANUAL, Trigger.SCHEDULED), r.map { it.trigger })
        assertEquals(listOf(StepStatus.OK, StepStatus.SKIPPED, StepStatus.FAILED), r[0].logs.map { it.status })
        assertEquals(45000L, r[0].logs[2].durationMs)
        assertTrue(r[3].logs.isEmpty())
    }

    @Test fun `what this version writes uses the same words`() {
        val w = Repo.json.decodeFromString<List<Workflow>>(workflows13)
        val out = Repo.json.encodeToString(w)
        assertTrue(out.contains("\"lastStatus\":\"success\""))
        assertTrue(out.contains("\"lastStatus\":\"\""))
        assertEquals(w, Repo.json.decodeFromString<List<Workflow>>(out))

        val r = Repo.json.decodeFromString<List<RunRecord>>(runs13)
        val runsOut = Repo.json.encodeToString(r)
        assertTrue(runsOut.contains("\"trigger\":\"Scheduled\""))
        assertTrue(runsOut.contains("\"status\":\"ok\""))
        assertTrue(runsOut.contains("\"status\":\"skipped\""))
        assertEquals(r, Repo.json.decodeFromString<List<RunRecord>>(runsOut))
    }

    @Test fun `words from a newer version do not break loading`() {
        val w = Repo.json.decodeFromString<List<Workflow>>("""[{"id":"w","name":"n","steps":[],"lastStatus":"paused","someNewField":1}]""")
        assertEquals(RunStatus.NEVER, w[0].lastStatus)
        val r = Repo.json.decodeFromString<List<RunRecord>>(
            """[{"id":"r","workflowId":"w","workflowName":"n","trigger":"Widget","startedAt":1,"endedAt":2,"status":"queued",
                 "logs":[{"index":0,"title":"t","status":"retried"}]}]""",
        )
        assertEquals(Trigger.MANUAL, r[0].trigger)
        assertEquals(RunStatus.NEVER, r[0].status)
        assertEquals(StepStatus.SKIPPED, r[0].logs[0].status)
    }

    @Test fun `a skipped scheduled run is saved and read back`() {
        val rec = RunRecord(
            workflowId = "w", workflowName = "n", trigger = Trigger.SCHEDULED, startedAt = 1, endedAt = 1,
            status = RunStatus.SKIPPED, summary = "Another automation is already running.",
        )
        val out = Repo.json.encodeToString(listOf(rec))
        assertTrue(out.contains("\"status\":\"skipped\""))
        assertEquals(RunStatus.SKIPPED, Repo.json.decodeFromString<List<RunRecord>>(out)[0].status)
    }
}
