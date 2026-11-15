package com.taskpilot.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A short in-memory log of state changes ("Engine: Idle → Running"). It is what makes a bug
 * report useful: History → "Copy report" includes these lines, so a person or an AI can see
 * exactly which state the app was in when something went wrong.
 *
 * Rules: mode and event NAMES only — never typed text, PINs, phone numbers or screen contents.
 * Kept in memory (last [MAX] lines), never written to disk, never sent anywhere.
 */
object Trace {
    const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** Replaceable in tests. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    @Synchronized
    fun log(machine: String, text: String) {
        val t = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).format(fmt)
        lines.addLast("$t  $machine: $text")
        while (lines.size > MAX) lines.removeFirst()
    }

    /** "from → to (event)", skipped when nothing changed. */
    fun move(machine: String, from: String, to: String, event: String) {
        if (from != to) log(machine, "$from → $to ($event)")
    }

    @Synchronized
    fun dump(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
