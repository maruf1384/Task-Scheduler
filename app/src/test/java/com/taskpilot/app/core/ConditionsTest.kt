package com.taskpilot.app.core

import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ConditionsTest {
    private class Env(
        var screen: Map<String, List<String>> = emptyMap(),
        var front: String = "",
        var paths: Set<String> = emptySet(),
        var files: Int = 0,
        var battery: Int = 50,
        var charging: Boolean = false,
        var online: Boolean = true,
    ) : CondEnv {
        override fun textOnScreen(text: String, pkg: String) =
            screen.filterKeys { pkg.isBlank() || it == pkg }.values.flatten().any { it.contains(text, ignoreCase = true) }
        override fun appInFront(pkg: String) = front == pkg
        override fun pathExists(path: String) = path in paths
        override fun filesFound() = files
        override fun batteryPercent() = battery
        override fun charging() = charging
        override fun online() = online
        override fun expand(s: String) = s.replace("{yesterday|dd-MM-yyyy}", "21-11-2026")
    }

    // Sunday 22 Nov 2026, 00:40
    private val sun0040 = LocalDateTime.of(2026, 11, 22, 0, 40)
    private fun c(k: CondKind, not: Boolean = false, vararg p: Pair<String, String>) = Cond(k.wire, not, p.toMap())
    private fun ok(cond: Cond, env: CondEnv = Env(), now: LocalDateTime = sun0040, lastOk: Boolean? = null) =
        Conditions.eval(listOf(cond), true, env, now, lastOk).met

    @Test fun savedAndReadBack() {
        val conds = listOf(c(CondKind.WEEKDAY, false, "days" to "7"), c(CondKind.ONLINE, true))
        val step = Conditions.with(Step(type = StepType.IF), conds)
        assertEquals(conds, Conditions.of(step))
    }

    @Test fun brokenJsonReadsAsNoConditions() {
        assertEquals(emptyList<Cond>(), Conditions.of(Step(type = StepType.IF, p = mapOf("conds" to "{oops"))))
        assertEquals(listOf("Add a condition"), Conditions.problems(Step(type = StepType.IF)))
    }

    @Test fun unknownKindFromANewerVersion() {
        val step = Step(type = StepType.IF, p = mapOf("conds" to """[{"kind":"moon_phase","p":{"x":"1"},"extra":true}]"""))
        val cs = Conditions.of(step)
        assertEquals(1, cs.size)
        assertNull(cs[0].k)
        assertFalse(ok(cs[0]))
        assertEquals(listOf("moon_phase: This check isn't known to this version of TaskPilot"), Conditions.problems(step))
    }

    @Test fun textOnScreen() {
        val env = Env(screen = mapOf("ru.iiec.pydroid3" to listOf("[Program finished]")))
        assertTrue(ok(c(CondKind.TEXT_ON_SCREEN, false, "text" to "program finished"), env))
        assertTrue(ok(c(CondKind.TEXT_ON_SCREEN, false, "text" to "finished", "pkg" to "ru.iiec.pydroid3"), env))
        assertFalse(ok(c(CondKind.TEXT_ON_SCREEN, false, "text" to "finished", "pkg" to "com.whatsapp"), env))
        assertTrue(ok(c(CondKind.TEXT_ON_SCREEN, true, "text" to "Traceback"), env))
    }

    @Test fun pathUsesPlaceholders() {
        val env = Env(paths = setOf("/sdcard/Download/out_21-11-2026.xlsx"))
        assertTrue(ok(c(CondKind.PATH_EXISTS, false, "path" to "/sdcard/Download/out_{yesterday|dd-MM-yyyy}.xlsx"), env))
        assertTrue(ok(c(CondKind.PATH_EXISTS, true, "path" to "/sdcard/nope"), env))
    }

    @Test fun filesFound() {
        assertFalse(ok(c(CondKind.FILES_FOUND), Env(files = 0)))
        assertTrue(ok(c(CondKind.FILES_FOUND), Env(files = 1)))
        assertFalse(ok(c(CondKind.FILES_FOUND, false, "min" to "3"), Env(files = 2)))
        assertTrue(ok(c(CondKind.FILES_FOUND, true), Env(files = 0)))
        assertEquals("No files were found", Conditions.describe(c(CondKind.FILES_FOUND, true)))
    }

    @Test fun weekday() {
        assertTrue(ok(c(CondKind.WEEKDAY, false, "days" to "7")))
        assertFalse(ok(c(CondKind.WEEKDAY, false, "days" to "1,2,3")))
        assertEquals("Today is Mon or Sun", Conditions.describe(c(CondKind.WEEKDAY, false, "days" to "7,1")))
        assertEquals("Pick at least one day", Conditions.problem(c(CondKind.WEEKDAY, false, "days" to "")))
    }

    @Test fun timeBetweenAcrossMidnight() {
        val night = c(CondKind.TIME_BETWEEN, false, "from" to "22:00", "to" to "06:00")
        assertTrue(ok(night, now = sun0040))
        assertTrue(ok(night, now = sun0040.withHour(22).withMinute(0)))
        assertFalse(ok(night, now = sun0040.withHour(6).withMinute(0)))   // the end is not included
        assertFalse(ok(night, now = sun0040.withHour(12)))
        val day = c(CondKind.TIME_BETWEEN, false, "from" to "06:00", "to" to "18:00")
        assertFalse(ok(day, now = sun0040))
        assertTrue(ok(day, now = sun0040.withHour(9)))
        assertTrue(ok(c(CondKind.TIME_BETWEEN, false, "from" to "05:00", "to" to "05:00"), now = sun0040))
        assertEquals("Start time must look like 06:30", Conditions.problem(c(CondKind.TIME_BETWEEN, false, "from" to "6pm")))
    }

    @Test fun dayOfMonth() {
        assertTrue(ok(c(CondKind.DAY_OF_MONTH, false, "days" to "1, 22")))
        assertFalse(ok(c(CondKind.DAY_OF_MONTH, false, "days" to "last")))
        assertTrue(ok(c(CondKind.DAY_OF_MONTH, false, "days" to "last"), now = LocalDateTime.of(2026, 11, 30, 9, 0)))
        assertTrue(ok(c(CondKind.DAY_OF_MONTH, false, "days" to "last"), now = LocalDateTime.of(2028, 2, 29, 9, 0)))
        assertEquals("Day of the month is 1, 15 or the last day", Conditions.describe(c(CondKind.DAY_OF_MONTH, false, "days" to "15,1,last")))
        assertEquals("Enter days like 1, 15, last", Conditions.problem(c(CondKind.DAY_OF_MONTH, false, "days" to "x")))
    }

    @Test fun previousStep() {
        assertTrue(ok(c(CondKind.PREV_OK), lastOk = null))
        assertTrue(ok(c(CondKind.PREV_OK), lastOk = true))
        assertFalse(ok(c(CondKind.PREV_OK), lastOk = false))
        assertTrue(ok(c(CondKind.PREV_OK, true), lastOk = false))
        assertEquals("The previous step failed", Conditions.describe(c(CondKind.PREV_OK, true)))
    }

    @Test fun phone() {
        assertTrue(ok(c(CondKind.BATTERY, false, "min" to "30"), Env(battery = 30)))
        assertFalse(ok(c(CondKind.BATTERY, false, "min" to "30"), Env(battery = 29)))
        assertTrue(ok(c(CondKind.CHARGING, true), Env(charging = false)))
        assertFalse(ok(c(CondKind.ONLINE), Env(online = false)))
        assertEquals("Battery must be 1 to 100", Conditions.problem(c(CondKind.BATTERY, false, "min" to "0")))
    }

    @Test fun allOrAny() {
        val env = Env(files = 2, online = false)
        val conds = listOf(c(CondKind.FILES_FOUND), c(CondKind.ONLINE))
        assertFalse(Conditions.eval(conds, true, env, sun0040, null).met)
        val any = Conditions.eval(conds, false, env, sun0040, null)
        assertTrue(any.met)
        assertEquals("Yes — ✓ Files were found · ✗ Internet is connected", any.detail)
        assertFalse(Conditions.eval(emptyList(), true, env, sun0040, null).met)
    }

    @Test fun aLookupThatThrowsCountsAsNotMet() {
        val env = object : CondEnv by Env() {
            override fun online(): Boolean = error("no service")
        }
        assertFalse(ok(c(CondKind.ONLINE), env))
    }

    @Test fun summaryLine() {
        val s = Conditions.with(Step(type = StepType.IF, p = mapOf("match" to "any")),
            listOf(c(CondKind.WEEKDAY, false, "days" to "7"), c(CondKind.CHARGING)))
        assertEquals("Today is Sun or Phone is charging", Conditions.summary(s))
        assertEquals("No condition yet", Conditions.summary(Step(type = StepType.IF)))
    }
}
