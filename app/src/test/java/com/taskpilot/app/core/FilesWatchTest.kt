package com.taskpilot.app.core

import com.taskpilot.app.core.FilesWatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesWatchTest {
    private val w = FilesWatch(startedAt = 0, timeoutMs = 600_000, settleMs = 8_000)

    /** The real output the user's phone showed on 29 Oct 2026 (shortened). */
    private val traceback = listOf(
        "Downloading 18-10-2026 00:00 -> 24-10-2026 23:59 ...",
        "Traceback (most recent call last):\n  File \"/data/user/0/ru.iiec.pydroid3/files/lib/python3.13/urllib/request.py\", line 1319, in do_open",
        "ssl.SSLCertVerificationError: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed (_ssl.c:1032)",
        "  File \"<string>\", line 259, in http_get",
        "RuntimeError: SSL certificate problem. In Pydroid: Pip -> install 'certifi', then run again.",
        "",
        "[Program finished]",
    )

    @Test fun `files are ready once they stop changing`() {
        var (a, v) = w.files(1_000, "a.xlsx:10:1")
        assertEquals(Verdict.Waiting, v)
        v = a.files(5_000, "a.xlsx:10:1").second
        assertEquals("only 4 s unchanged", Verdict.Waiting, v)
        v = a.files(9_000, "a.xlsx:10:1").second
        assertEquals(Verdict.Ready, v)
    }

    @Test fun `a file that is still being written restarts the wait`() {
        val a = w.files(1_000, "a.xlsx:10:1").first
        val b = a.files(8_000, "a.xlsx:99:2").first          // grew
        assertEquals(Verdict.Waiting, b.files(9_500, "a.xlsx:99:2").second)
        assertEquals(Verdict.Ready, b.files(16_000, "a.xlsx:99:2").second)
    }

    @Test fun `a crash without files ends the wait after the grace time with the python error`() {
        var (a, v) = w.screen(20_000, traceback)
        assertEquals("grace time first", Verdict.Waiting, v)
        v = a.screen(31_000, traceback).second
        assertEquals(Verdict.Waiting, v)
        v = a.screen(32_001, emptyList()).second             // even if the text scrolled away
        assertTrue(v is Verdict.ScriptEnded)
        val end = (v as Verdict.ScriptEnded).end
        assertTrue(end.crashed)
        assertEquals("crashed", end.how)
        assertEquals("RuntimeError: SSL certificate problem. In Pydroid: Pip -> install 'certifi', then run again.", end.errorLine)
    }

    @Test fun `files that arrive during the grace time win`() {
        val a = w.screen(20_000, listOf("Saved: x.xlsx", "Done.", "", "[Program finished]")).first
        assertFalse(a.end!!.crashed)
        val b = a.files(22_000, "x.xlsx:5:1").first
        assertEquals(Verdict.Ready, b.files(30_000, "x.xlsx:5:1").second)
    }

    @Test fun `a script that is still running is left alone`() {
        val (a, v) = w.screen(20_000, listOf("Downloading 18-10-2026 ...", "   18-10-2026: 25 rows"))
        assertEquals(Verdict.Waiting, v)
        assertNull(a.end)
        assertEquals(Verdict.Waiting, a.screen(500_000, listOf("   19-10-2026: 25 rows")).second)
    }

    @Test fun `finished without a crash has no error line`() {
        val end = ScriptEnd.detect(listOf("Done.\n\n[Program finished]"))!!
        assertFalse(end.crashed)
        assertEquals("finished", end.how)
        assertEquals("", end.errorLine)
    }

    @Test fun `timeout and countdown`() {
        assertFalse(w.timedOut(599_999))
        assertTrue(w.timedOut(600_000))
        assertEquals(1_000, w.msLeft(599_000))
        assertEquals(0, w.msLeft(700_000))
    }

    @Test fun `no files resets the settle clock`() {
        val a = w.files(1_000, "a:1:1").first.files(2_000, "").first
        assertFalse(a.hasFiles)
        assertEquals(Verdict.Waiting, a.files(20_000, "a:1:1").second)
    }
}
