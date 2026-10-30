package com.taskpilot.app.engine

import kotlinx.serialization.Serializable
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** One change made by the "Edit a file" step. */
@Serializable
data class FileEdit(
    /** "var" = set a setting (NAME = value) · "replace" = find & replace text */
    val kind: String = "var",
    val name: String = "",
    val value: String = "",
    val find: String = "",
    val replace: String = "",
)

/** A setting line found in a file, e.g. START_DATETIME = "2026-10-18 00:00". */
data class Assignment(val name: String, val rawValue: String, val quoted: Boolean, val line: Int, val inner: String)

data class EditOutcome(
    val text: String,
    val changes: List<String>,
    val problems: List<String>,
)

/**
 * Pure text logic (no Android) so it can be tested anywhere.
 *
 * Smart values — write any of these inside a value, they're replaced when the step runs:
 *   {today} {yesterday} {tomorrow}
 *   {week.start} {week.end}            this week (week start day is a setting)
 *   {lastweek.start} {lastweek.end}    the previous full week
 *   {month.start} {month.end} {lastmonth.start} {lastmonth.end}
 *   add / subtract days:  {today-7}  {lastweek.end+1}
 *   choose the format:    {lastweek.start|dd-MM-yyyy}   (default yyyy-MM-dd)
 */
object CodeEdit {

    private val TOKEN = Regex(
        """\{(today|yesterday|tomorrow|week\.start|week\.end|lastweek\.start|lastweek\.end|month\.start|month\.end|lastmonth\.start|lastmonth\.end)([+-]\d{1,4})?(?:\|([^{}]+))?\}""",
    )

    data class TokenHelp(val token: String, val label: String)

    val QUICK_TOKENS = listOf(
        TokenHelp("{lastweek.start}", "Last week · first day"),
        TokenHelp("{lastweek.end}", "Last week · last day"),
        TokenHelp("{today}", "Today"),
        TokenHelp("{yesterday}", "Yesterday"),
        TokenHelp("{week.start}", "This week · first day"),
        TokenHelp("{week.end}", "This week · last day"),
        TokenHelp("{lastmonth.start}", "Last month · first day"),
        TokenHelp("{lastmonth.end}", "Last month · last day"),
    )

    fun hasTokens(s: String) = TOKEN.containsMatchIn(s)

    fun expandDates(s: String, now: LocalDateTime, weekStart: DayOfWeek): String {
        val today = now.toLocalDate()
        val ws = today.with(TemporalAdjusters.previousOrSame(weekStart))
        return TOKEN.replace(s) { m ->
            val base: LocalDate = when (m.groupValues[1]) {
                "today" -> today
                "yesterday" -> today.minusDays(1)
                "tomorrow" -> today.plusDays(1)
                "week.start" -> ws
                "week.end" -> ws.plusDays(6)
                "lastweek.start" -> ws.minusDays(7)
                "lastweek.end" -> ws.minusDays(1)
                "month.start" -> today.withDayOfMonth(1)
                "month.end" -> today.with(TemporalAdjusters.lastDayOfMonth())
                "lastmonth.start" -> today.minusMonths(1).withDayOfMonth(1)
                "lastmonth.end" -> today.withDayOfMonth(1).minusDays(1)
                else -> today
            }
            val off = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L
            val pattern = m.groupValues[3].ifBlank { "yyyy-MM-dd" }
            try {
                base.plusDays(off).format(DateTimeFormatter.ofPattern(pattern))
            } catch (_: Exception) {
                m.value                       // bad pattern → leave as typed (reported by validate())
            }
        }
    }

    /** Problems with how a value is written (bad date format). */
    fun validate(s: String): String? {
        for (m in TOKEN.findAll(s)) {
            val p = m.groupValues[3]
            if (p.isNotBlank()) try { DateTimeFormatter.ofPattern(p) } catch (e: Exception) {
                return "Date format “$p” isn't valid"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ reading settings

    private val ASSIGN = Regex("""^([A-Za-z_][A-Za-z0-9_]*)([ \t]*=[ \t]*)(.*)$""")

    /** Top-level `NAME = value` lines (no indentation), in file order. */
    fun assignments(text: String): List<Assignment> {
        val out = ArrayList<Assignment>()
        text.split('\n').forEachIndexed { i, rawLine ->
            val line = rawLine.removeSuffix("\r")
            val m = ASSIGN.find(line) ?: return@forEachIndexed
            val rhs = m.groupValues[3]
            if (rhs.startsWith("=")) return@forEachIndexed                 // "==" comparison
            val parsed = splitValue(rhs)
            out += Assignment(m.groupValues[1], parsed.value.trim(), parsed.quote != null, i + 1, parsed.inner)
        }
        return out
    }

    private class Parsed(val value: String, val rest: String, val quote: Char?, val prefix: String, val inner: String)

    /** Splits the right-hand side into value and trailing comment, understanding quotes. */
    private fun splitValue(rhs: String): Parsed {
        val m = Regex("""^([rRbBuU]{0,2})(["'])""").find(rhs)
        if (m != null && !rhs.startsWith("\"\"\"") && !rhs.startsWith("'''")) {
            val q = m.groupValues[2][0]
            val start = m.value.length
            var i = start
            while (i < rhs.length) {
                val c = rhs[i]
                if (c == '\\') { i += 2; continue }
                if (c == q) break
                i++
            }
            if (i < rhs.length) {
                return Parsed(rhs.substring(0, i + 1), rhs.substring(i + 1), q, m.groupValues[1], rhs.substring(start, i))
            }
        }
        // unquoted: value runs until a '#' that's outside quotes
        var inQ: Char? = null
        for ((i, c) in rhs.withIndex()) {
            if (inQ != null) { if (c == inQ) inQ = null; continue }
            if (c == '"' || c == '\'') inQ = c
            else if (c == '#') {
                val v = rhs.substring(0, i)
                val trimmed = v.trimEnd()
                return Parsed(trimmed, v.substring(trimmed.length) + rhs.substring(i), null, "", trimmed)
            }
        }
        val trimmed = rhs.trimEnd()
        return Parsed(trimmed, rhs.substring(trimmed.length), null, "", trimmed)
    }

    private fun escapeFor(q: Char, s: String) = s.replace("\\", "\\\\").replace(q.toString(), "\\$q")

    // ------------------------------------------------------------------ applying edits

    fun apply(text: String, edits: List<FileEdit>, now: LocalDateTime, weekStart: DayOfWeek): EditOutcome {
        var t = text
        val changes = ArrayList<String>()
        val problems = ArrayList<String>()
        for (e in edits) {
            when (e.kind) {
                "replace" -> {
                    if (e.find.isEmpty()) { problems += "A find & replace has nothing to find"; continue }
                    val repl = expandDates(e.replace, now, weekStart)
                    val n = Regex.escape(e.find).toRegex().findAll(t).count()
                    if (n == 0) { problems += "Couldn't find “${e.find.take(40)}”"; continue }
                    t = t.replace(e.find, repl)
                    changes += "Replaced “${e.find.take(30)}” → “${repl.take(30)}” ($n×)"
                }
                else -> {
                    if (e.name.isBlank()) { problems += "A setting has no name"; continue }
                    val bad = validate(e.value)
                    if (bad != null) { problems += "${e.name}: $bad"; continue }
                    val newValue = expandDates(e.value, now, weekStart)
                    val lines = t.split('\n').toMutableList()
                    val idx = lines.indexOfFirst { l ->
                        val m = ASSIGN.find(l.removeSuffix("\r"))
                        m != null && m.groupValues[1] == e.name && !m.groupValues[3].startsWith("=")
                    }
                    if (idx < 0) { problems += "Couldn't find the setting ${e.name} in the file"; continue }
                    val cr = lines[idx].endsWith("\r")
                    val line = lines[idx].removeSuffix("\r")
                    val m = ASSIGN.find(line)!!
                    val p = splitValue(m.groupValues[3])
                    val newRhs = if (p.quote != null) {
                        p.prefix + p.quote + escapeFor(p.quote, newValue) + p.quote + p.rest
                    } else {
                        newValue + p.rest
                    }
                    val before = p.value
                    lines[idx] = m.groupValues[1] + m.groupValues[2] + newRhs + (if (cr) "\r" else "")
                    t = lines.joinToString("\n")
                    val after = if (p.quote != null) "${p.quote}$newValue${p.quote}" else newValue
                    changes += if (before == after) "${e.name} already $after" else "${e.name}: $before → $after"
                }
            }
        }
        return EditOutcome(t, changes, problems)
    }

    // ------------------------------------------------------------------ files

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Name of the fresh copy for this run, e.g. ".weekly_report.run-20261101-004000.py" */
    fun copyFor(original: File, now: LocalDateTime): File {
        val ext = original.extension.let { if (it.isEmpty()) "" else ".$it" }
        return File(original.parentFile, ".${original.nameWithoutExtension}.run-${now.format(STAMP)}$ext")
    }

    /** Deletes older run copies of [original], keeping the newest [keep]. */
    fun cleanCopies(original: File, keep: Int = 3) {
        val prefix = ".${original.nameWithoutExtension}.run-"
        original.parentFile?.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.sortedByDescending { it.name }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    /** Writes atomically: a temp file in the same folder, then a rename over the target. */
    fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tp-tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
