package com.taskpilot.app.core

import com.taskpilot.app.data.Step
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One test used by an If or a Repeat-until/while step. Saved as {"kind":…,"not":…,"p":{…}}. */
data class Cond(
    /** A [CondKind.wire] word. */
    val kind: String = CondKind.TEXT_ON_SCREEN.wire,
    /** Turned around: true when the thing is NOT so. */
    val not: Boolean = false,
    val p: Map<String, String> = emptyMap(),
) {
    val k: CondKind? get() = CondKind.entries.firstOrNull { it.wire == kind }
    fun s(key: String, def: String = ""): String = p[key] ?: def
}

enum class CondGroup(val title: String, val blurb: String) {
    SCREEN("On screen", "What an app is showing"),
    FILES("Files", "Whether files exist or were found"),
    TIME("Date & time", "Day of the week, time of day, day of the month"),
    RUN("This run", "How the previous step went"),
    PHONE("Phone", "Battery, charging, internet"),
}

enum class CondKind(val wire: String, val group: CondGroup, val label: String, val hint: String) {
    TEXT_ON_SCREEN("screen_text", CondGroup.SCREEN, "Text is on screen", "e.g. “[Program finished]” in Pydroid, or an error message"),
    APP_IN_FRONT("app_front", CondGroup.SCREEN, "An app is open in front", "e.g. WhatsApp is showing"),
    PATH_EXISTS("path_exists", CondGroup.FILES, "A file or folder exists", "Dates like {yesterday|dd-MM-yyyy} work in the name"),
    FILES_FOUND("files_found", CondGroup.FILES, "Files were found earlier in this run", "By a “Select files” or “Wait for new files” step"),
    WEEKDAY("weekday", CondGroup.TIME, "Day of the week", "e.g. only on Sundays"),
    TIME_BETWEEN("time_between", CondGroup.TIME, "Time of day is between", "e.g. 00:00 to 06:00 (can cross midnight)"),
    DAY_OF_MONTH("day_of_month", CondGroup.TIME, "Day of the month", "e.g. 1, 15 or the last day"),
    PREV_OK("prev_ok", CondGroup.RUN, "The previous step worked", "Turn it around for “the previous step failed”"),
    BATTERY("battery", CondGroup.PHONE, "Battery is at least", "e.g. 30%"),
    CHARGING("charging", CondGroup.PHONE, "Phone is charging", ""),
    ONLINE("online", CondGroup.PHONE, "Internet is connected", "Mobile data or Wi-Fi that really reaches the internet"),
}

/** What the Android side looks up for the conditions that need the phone. */
interface CondEnv {
    /** [text] is shown (anywhere, any case) in app [pkg], or in any app when [pkg] is blank. */
    fun textOnScreen(text: String, pkg: String): Boolean
    fun appInFront(pkg: String): Boolean

    /** [path] has its {tokens} already filled in. */
    fun pathExists(path: String): Boolean
    fun filesFound(): Int
    fun batteryPercent(): Int
    fun charging(): Boolean
    fun online(): Boolean

    /** Fills {yesterday|dd-MM-yyyy} and the other placeholders. */
    fun expand(s: String): String = s
}

data class CondResult(val description: String, val met: Boolean)

data class Evaluation(val met: Boolean, val parts: List<CondResult>) {
    /** "Yes — ✓ Today is Sun · ✗ “x” is on screen" */
    val detail: String
        get() = (if (met) "Yes" else "No") +
            if (parts.isEmpty()) "" else " — " + parts.joinToString(" · ") { (if (it.met) "✓ " else "✗ ") + it.description }
}

/**
 * The conditions of an If or Repeat step: stored as JSON text in the step (`conds`) plus whether
 * ALL of them or ANY of them must hold (`match`).
 */
object Conditions {
    /**
     * Read by hand rather than with a generated serializer, so this file needs nothing but the
     * JSON library (and unknown fields from a newer version are simply ignored).
     */
    fun of(step: Step): List<Cond> = try {
        Json.parseToJsonElement(step.s("conds", "[]")).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val not = (o["not"] as? JsonPrimitive)?.booleanOrNull ?: false
            val p = (o["p"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            Cond(kind, not, p)
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun with(step: Step, conds: List<Cond>): Step = step.put("conds", buildJsonArray {
        for (c in conds) add(buildJsonObject {
            put("kind", c.kind)
            put("not", c.not)
            put("p", buildJsonObject { c.p.forEach { (k, v) -> put(k, v) } })
        })
    }.toString())

    fun matchAll(step: Step): Boolean = step.s("match", "all") != "any"

    private val DAY_NAMES = DayOfWeek.entries.associateWith { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

    fun days(c: Cond): List<DayOfWeek> =
        c.s("days").split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { d -> d in 1..7 } }.distinct().sorted().map { DayOfWeek.of(it) }

    /** "1, 15, last" → [1, 15] + last-day flag. */
    private fun monthDays(c: Cond): Pair<List<Int>, Boolean> {
        val parts = c.s("days").split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return parts.mapNotNull { it.toIntOrNull()?.takeIf { d -> d in 1..31 } }.distinct().sorted() to ("last" in parts)
    }

    fun parseTime(s: String): LocalTime? = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(s)?.let { m ->
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun orList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
    }

    private fun shortPath(p: String): String {
        val parts = p.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return if (parts.size <= 2) p else "…/" + parts.takeLast(2).joinToString("/")
    }

    /** A sentence for the condition, already turned around when [Cond.not] is set. */
    fun describe(c: Cond): String {
        val no = c.not
        return when (c.k) {
            CondKind.TEXT_ON_SCREEN -> {
                val where = c.s("appName").ifBlank { c.s("pkg") }.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""
                "“${c.s("text")}” is ${if (no) "not " else ""}on screen$where"
            }
            CondKind.APP_IN_FRONT -> "${c.s("appName").ifBlank { c.s("pkg").ifBlank { "the app" } }} is ${if (no) "not " else ""}open in front"
            CondKind.PATH_EXISTS -> "${shortPath(c.s("path"))} ${if (no) "doesn't exist" else "exists"}"
            CondKind.FILES_FOUND -> {
                val min = c.s("min", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
                when {
                    !no && min == 1 -> "Files were found"
                    !no -> "At least $min files were found"
                    min == 1 -> "No files were found"
                    else -> "Fewer than $min files were found"
                }
            }
            CondKind.WEEKDAY -> "Today is ${if (no) "not " else ""}${orList(days(c).map { DAY_NAMES.getValue(it) })}"
            CondKind.TIME_BETWEEN -> "Time is ${if (no) "not " else ""}between ${c.s("from", "00:00")} and ${c.s("to", "06:00")}"
            CondKind.DAY_OF_MONTH -> {
                val (nums, last) = monthDays(c)
                "Day of the month is ${if (no) "not " else ""}${orList(nums.map { it.toString() } + if (last) listOf("the last day") else emptyList())}"
            }
            CondKind.PREV_OK -> if (no) "The previous step failed" else "The previous step worked"
            CondKind.BATTERY -> {
                val min = c.s("min", "30")
                if (no) "Battery is below $min%" else "Battery is at least $min%"
            }
            CondKind.CHARGING -> "Phone is ${if (no) "not " else ""}charging"
            CondKind.ONLINE -> "Internet is ${if (no) "not " else ""}connected"
            null -> "Unknown check “${c.kind}”"
        }
    }

    /** What still needs filling in, or null. */
    fun problem(c: Cond): String? = when (c.k) {
        CondKind.TEXT_ON_SCREEN -> if (c.s("text").isBlank()) "Enter the text to look for" else null
        CondKind.APP_IN_FRONT -> if (c.s("pkg").isBlank()) "Choose the app" else null
        CondKind.PATH_EXISTS -> if (c.s("path").isBlank()) "Choose the file or folder" else null
        CondKind.FILES_FOUND -> if ((c.s("min", "1").toIntOrNull() ?: 0) < 1) "Enter how many files (1 or more)" else null
        CondKind.WEEKDAY -> if (days(c).isEmpty()) "Pick at least one day" else null
        CondKind.TIME_BETWEEN -> when {
            parseTime(c.s("from", "00:00")) == null -> "Start time must look like 06:30"
            parseTime(c.s("to", "06:00")) == null -> "End time must look like 18:00"
            else -> null
        }
        CondKind.DAY_OF_MONTH -> monthDays(c).let { (n, last) -> if (n.isEmpty() && !last) "Enter days like 1, 15, last" else null }
        CondKind.BATTERY -> if ((c.s("min", "30").toIntOrNull() ?: -1) !in 1..100) "Battery must be 1 to 100" else null
        CondKind.PREV_OK, CondKind.CHARGING, CondKind.ONLINE -> null
        null -> "This check isn't known to this version of TaskPilot"
    }

    /** Problems of a whole If / Repeat step's conditions. */
    fun problems(step: Step): List<String> {
        val cs = of(step)
        if (cs.isEmpty()) return listOf("Add a condition")
        return cs.mapNotNull { c -> problem(c)?.let { "${c.k?.label ?: c.kind}: $it" } }
    }

    /** Conditions that only need the clock / the run — no phone lookups. */
    fun needsPhone(c: Cond): Boolean = c.k?.group in setOf(CondGroup.SCREEN, CondGroup.FILES, CondGroup.PHONE)

    /**
     * Raw truth of one condition (before [Cond.not]). [lastOk] = how the previous step went
     * (null = nothing has run yet, which counts as "worked").
     */
    fun raw(c: Cond, env: CondEnv, now: LocalDateTime, lastOk: Boolean?): Boolean = when (c.k) {
        CondKind.TEXT_ON_SCREEN -> env.textOnScreen(c.s("text"), c.s("pkg"))
        CondKind.APP_IN_FRONT -> env.appInFront(c.s("pkg"))
        CondKind.PATH_EXISTS -> env.pathExists(env.expand(c.s("path")))
        CondKind.FILES_FOUND -> env.filesFound() >= (c.s("min", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1)
        CondKind.WEEKDAY -> now.dayOfWeek in days(c)
        CondKind.TIME_BETWEEN -> {
            val from = parseTime(c.s("from", "00:00")) ?: LocalTime.MIDNIGHT
            val to = parseTime(c.s("to", "06:00")) ?: LocalTime.MIDNIGHT
            val t = now.toLocalTime()
            when {
                from == to -> true                                  // the whole day
                from < to -> !t.isBefore(from) && t.isBefore(to)    // 06:00–18:00
                else -> !t.isBefore(from) || t.isBefore(to)         // 22:00–06:00, across midnight
            }
        }
        CondKind.DAY_OF_MONTH -> {
            val (nums, last) = monthDays(c)
            val d = now.toLocalDate()
            d.dayOfMonth in nums || (last && d == d.with(TemporalAdjusters.lastDayOfMonth()))
        }
        CondKind.PREV_OK -> lastOk ?: true
        CondKind.BATTERY -> env.batteryPercent() >= (c.s("min", "30").toIntOrNull() ?: 30)
        CondKind.CHARGING -> env.charging()
        CondKind.ONLINE -> env.online()
        null -> false
    }

    fun eval(conds: List<Cond>, matchAll: Boolean, env: CondEnv, now: LocalDateTime, lastOk: Boolean?): Evaluation {
        val parts = conds.map { c ->
            val met = try { raw(c, env, now, lastOk) != c.not } catch (_: Exception) { false }
            CondResult(describe(c), met)
        }
        val met = if (parts.isEmpty()) false else if (matchAll) parts.all { it.met } else parts.any { it.met }
        return Evaluation(met, parts)
    }

    /** One line for the step list: "If Today is Sun and Files were found". */
    fun summary(step: Step): String {
        val cs = of(step)
        if (cs.isEmpty()) return "No condition yet"
        return cs.joinToString(if (matchAll(step)) " and " else " or ") { describe(it) }
    }
}
