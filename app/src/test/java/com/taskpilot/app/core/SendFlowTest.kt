package com.taskpilot.app.core

import com.taskpilot.app.core.SendFlow.Decision
import com.taskpilot.app.core.SendFlow.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendFlowTest {
    /** What WhatsApp shows at a given moment. */
    private data class Screen(val confirm: Boolean = false, val inChat: Boolean = false, val composerSend: Boolean = false)

    private class Outcome(val presses: List<Target>, val confirmed: Boolean, val endedAt: Long, val composerLooks: Int)

    /**
     * Drives a SendFlow the way AutomationService.sendAndConfirm does. [screen] gets the time and
     * the presses so far and says what is showing; [pressOk] says whether a press lands.
     */
    private fun drive(
        caption: Boolean,
        pressOk: (Target) -> Boolean = { true },
        screen: (now: Long, presses: List<Target>) -> Screen,
    ): Outcome {
        var now = 0L
        var flow = SendFlow(startedAt = 0, captionTyped = caption)
        val presses = ArrayList<Target>()
        var composerLooks = 0
        repeat(10_000) {
            val s = screen(now, presses)
            val look = object : SendFlow.Look {
                override fun confirmVisible() = s.confirm
                override fun filesInChat() = s.inChat
                override fun composerSendVisible(): Boolean { composerLooks++; return s.composerSend }
            }
            var (f, d) = flow.decide(now, look)
            if (d is Decision.Press) {
                val ok = pressOk(d.target)
                if (ok) presses += d.target
                val r = f.pressed(now, d.target, ok)
                f = r.first; d = r.second
            }
            flow = f
            when (d) {
                is Decision.Done -> return Outcome(presses, d.confirmed, now, composerLooks)
                is Decision.Wait -> now += d.ms
                is Decision.Press -> error("pressed() must not ask for another press")
            }
        }
        error("the flow never finished")
    }

    @Test fun `without a caption the round button is never pressed - it is the microphone`() {
        // The 1_1_2 bug: files sent, then the chat button (now a mic) was tapped → voice notes.
        val o = drive(caption = false) { _, _ -> Screen(inChat = true, composerSend = true) }
        assertEquals(emptyList<Target>(), o.presses)
        assertTrue(o.confirmed)
        assertEquals("without a caption the composer isn't even looked for", 0, o.composerLooks)
    }

    @Test fun `presses the confirm dialog, then stops once the files are in the chat`() {
        val o = drive(caption = false) { _, presses ->
            if (presses.isEmpty()) Screen(confirm = true) else Screen(inChat = true, composerSend = true)
        }
        assertEquals(listOf(Target.CONFIRM), o.presses)
        assertTrue(o.confirmed)
    }

    @Test fun `a typed caption is sent with exactly one press of the composer`() {
        val o = drive(caption = true) { _, _ -> Screen(inChat = true, composerSend = true) }
        assertEquals(listOf(Target.COMPOSER), o.presses)
        assertTrue(o.confirmed)
    }

    @Test fun `with a caption but no Send showing nothing is pressed`() {
        val o = drive(caption = true) { _, _ -> Screen(inChat = true, composerSend = false) }
        assertEquals(emptyList<Target>(), o.presses)
        assertTrue(o.confirmed)
    }

    @Test fun `WhatsApp sending by itself counts as sent`() {
        val o = drive(caption = false) { _, _ -> Screen(inChat = true) }
        assertEquals(0, o.presses.size)
        assertTrue(o.confirmed)
        assertEquals("two looks, half a second apart", SendFlow.LOOK_EVERY_MS, o.endedAt)
    }

    @Test fun `never more than four presses`() {
        val o = drive(caption = false) { _, _ -> Screen(confirm = true) }   // a dialog that never goes away
        assertEquals(4, o.presses.size)
        assertFalse(o.confirmed)
    }

    @Test fun `confirm comes before everything else`() {
        val o = drive(caption = true) { _, presses ->
            if (presses.isEmpty()) Screen(confirm = true, inChat = true, composerSend = true) else Screen(inChat = true)
        }
        assertEquals(listOf(Target.CONFIRM), o.presses)
    }

    @Test fun `nothing to press and nothing in the chat ends at the timeout`() {
        val o = drive(caption = false) { _, _ -> Screen() }
        assertEquals(0, o.presses.size)
        assertFalse(o.confirmed)
        assertEquals(45_000, o.endedAt)
    }

    @Test fun `gives up ten seconds after the last press if the files never show`() {
        val o = drive(caption = false) { _, presses -> if (presses.isEmpty()) Screen(confirm = true) else Screen() }
        assertEquals(1, o.presses.size)
        assertFalse(o.confirmed)
        assertTrue("ended at ${o.endedAt}", o.endedAt in 10_001..13_000)
    }

    @Test fun `a composer press that does not land is not retried`() {
        val o = drive(caption = true, pressOk = { it != Target.COMPOSER }) { _, _ -> Screen(inChat = true, composerSend = true) }
        assertEquals(emptyList<Target>(), o.presses)
        assertTrue(o.confirmed)
    }

    @Test fun `a confirm press that does not land is tried again`() {
        var tries = 0
        val o = drive(caption = false, pressOk = { tries++; tries > 2 }) { _, presses ->
            if (presses.isEmpty()) Screen(confirm = true) else Screen(inChat = true)
        }
        assertEquals(listOf(Target.CONFIRM), o.presses)
        assertEquals(3, tries)
        assertTrue(o.confirmed)
    }

    @Test fun `files that disappear from view restart the count`() {
        var look = 0
        val o = drive(caption = false) { _, _ -> look++; Screen(inChat = look != 2) }
        assertTrue(o.confirmed)
        assertEquals("looks 1 ✓, 2 ✗, 3 ✓, 4 ✓", 4, look)
    }
}
