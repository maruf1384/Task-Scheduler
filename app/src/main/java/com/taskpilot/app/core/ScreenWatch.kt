package com.taskpilot.app.core

/**
 * Decides WHEN to re-read another app's screen, so the options list follows the app by itself.
 *
 *     Clean ──screen event──▶ Dirty ──quiet for [quietMs] (or [maxWaitMs] passed)──▶ Reading ──▶ Clean
 *                                                              │ event during the read
 *                                                              ▼
 *                                                            Dirty again
 *
 * "Quiet" = the app stopped changing (page finished loading, animation ended). Apps that never go
 * quiet (a spinner, a video) are still read every [maxWaitMs]. While the options panel is open a
 * slow [heartbeatMs] read catches apps that change without telling Android.
 *
 * Immutable: every call returns the next value. Times are milliseconds from any one clock.
 */
data class ScreenWatch(
    val quietMs: Long = 450,
    val maxWaitMs: Long = 2500,
    val minGapMs: Long = 600,
    val heartbeatMs: Long = 4000,
    val dirtySince: Long = NONE,
    val lastEvent: Long = NONE,
    val readStarted: Long = NONE,
    val lastReadDone: Long = NONE,
) {
    val dirty: Boolean get() = dirtySince != NONE
    val reading: Boolean get() = readStarted != NONE

    /** The other app's screen changed (or we just did something to it). */
    fun event(now: Long): ScreenWatch =
        copy(dirtySince = if (dirty) dirtySince else now, lastEvent = now)

    /** Should a read start now? [heartbeat] = also read on a slow timer (options panel is open). */
    fun due(now: Long, heartbeat: Boolean = false): Boolean {
        if (reading) return false
        if (lastReadDone != NONE && now - lastReadDone < minGapMs) return false
        if (dirty) return now - lastEvent >= quietMs || now - dirtySince >= maxWaitMs
        return heartbeat && (lastReadDone == NONE || now - lastReadDone >= heartbeatMs)
    }

    fun readStarted(now: Long): ScreenWatch = copy(readStarted = now)

    /** A read finished. Stays dirty if the screen changed again while we were reading. */
    fun readDone(now: Long): ScreenWatch {
        val changedDuringRead = reading && lastEvent != NONE && lastEvent > readStarted
        return copy(
            readStarted = NONE,
            lastReadDone = now,
            dirtySince = if (changedDuringRead) lastEvent else NONE,
        )
    }

    /** True once the screen has been read and nothing has changed since — "the screen has settled". */
    fun settled(now: Long): Boolean = !dirty && !reading && lastReadDone != NONE && now - lastEvent >= quietMs

    companion object {
        const val NONE = -1L
    }
}
