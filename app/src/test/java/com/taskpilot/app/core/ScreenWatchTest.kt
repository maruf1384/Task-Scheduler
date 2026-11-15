package com.taskpilot.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenWatchTest {
    private val w = ScreenWatch()

    @Test fun `a clean screen is not read again without the heartbeat`() {
        assertFalse(w.due(10_000))
        val read = w.readStarted(0).readDone(100)
        assertFalse(read.due(60_000))
    }

    @Test fun `reads once the app has gone quiet`() {
        val d = w.event(1000)
        assertFalse(d.due(1200))            // still changing a moment ago
        assertTrue(d.due(1000 + w.quietMs)) // quiet long enough
    }

    @Test fun `an app that never goes quiet is still read`() {
        var d = w
        var t = 1000L
        while (t < 1000 + w.maxWaitMs) { d = d.event(t); t += 100 }   // an event every 100 ms
        assertFalse(d.due(1000 + w.maxWaitMs - 200))
        assertTrue(d.due(1000 + w.maxWaitMs))
    }

    @Test fun `does not start a second read while one is running`() {
        val d = w.event(1000).readStarted(1500)
        assertFalse(d.due(5000))
    }

    @Test fun `a change during the read keeps it dirty`() {
        val d = w.event(1000).readStarted(1500).event(1600).readDone(1700)
        assertTrue(d.dirty)
        assertFalse(d.due(1800))                       // too soon after the last read
        assertTrue(d.due(1700 + w.minGapMs))
    }

    @Test fun `a read with no change in between leaves it clean and settled`() {
        val d = w.event(1000).readStarted(1500).readDone(1700)
        assertFalse(d.dirty)
        assertTrue(d.settled(1700))
        assertFalse(d.event(1750).settled(1800))
    }

    @Test fun `the heartbeat re-reads a silent app while the panel is open`() {
        val d = w.readStarted(0).readDone(100)
        assertFalse(d.due(100 + w.heartbeatMs - 1, heartbeat = true))
        assertTrue(d.due(100 + w.heartbeatMs, heartbeat = true))
        assertTrue(w.due(0, heartbeat = true))         // never read yet
    }

    @Test fun `nothing is settled before the first read`() {
        assertFalse(w.settled(99_999))
    }
}
