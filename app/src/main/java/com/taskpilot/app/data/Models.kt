package com.taskpilot.app.data

import com.taskpilot.app.engine.FileEdit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.DayOfWeek
import java.util.UUID

@Serializable
enum class StepType {
    WAKE_UNLOCK,
    LAUNCH_APP,
    OPEN_FILE,
    TAP_ELEMENT,
    WAIT_ELEMENT,
    INPUT_TEXT,
    SYSTEM_KEY,
    DELAY,
    WAIT_NEW_FILES,
    SELECT_FILES,
    SEND_WHATSAPP,
    SHARE_FILES,
    NOTIFY,
    LOCK_SCREEN,
    EDIT_FILE,
}

/**
 * One step of an automation. Settings live in a flat string map so every step type
 * can be stored, copied and edited the same way.
 */
@Serializable
data class Step(
    val id: String = UUID.randomUUID().toString(),
    val type: StepType,
    val enabled: Boolean = true,
    val label: String = "",
    val p: Map<String, String> = emptyMap(),
) {
    fun s(key: String, def: String = ""): String = p[key] ?: def
    fun i(key: String, def: Int): Int = p[key]?.trim()?.toIntOrNull() ?: def
    fun b(key: String, def: Boolean): Boolean = p[key]?.toBooleanStrictOrNull() ?: def
    fun put(key: String, value: String): Step = copy(p = p + (key to value))
    fun putAll(values: Map<String, String>): Step = copy(p = p + values)
}

@Serializable
data class Schedule(
    val enabled: Boolean = false,
    val hour: Int = 0,
    val minute: Int = 40,
    /** ISO days: 1 = Monday … 7 = Sunday */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
)

@Serializable
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "⚡",
    val steps: List<Step> = emptyList(),
    val schedule: Schedule = Schedule(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val lastStatus: String = "",
)

@Serializable
data class StepLog(
    val index: Int,
    val title: String,
    val status: String, // ok | failed | skipped
    val message: String = "",
    val durationMs: Long = 0L,
)

@Serializable
data class RunRecord(
    val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val emoji: String = "⚡",
    val trigger: String,
    val startedAt: Long,
    val endedAt: Long,
    val status: String, // success | failed | stopped
    val summary: String = "",
    val logs: List<StepLog> = emptyList(),
)

/** How a tap target inside another app is remembered. */
data class Selector(
    val pkg: String,
    val id: String,
    val text: String,
    val desc: String,
    val cls: String,
    val x: Float,
    val y: Float,
    val screenW: Int,
    val screenH: Int,
    val label: String,
) {
    val hasKeys: Boolean get() = id.isNotBlank() || text.isNotBlank() || desc.isNotBlank()
    val hasPoint: Boolean get() = x >= 0f && y >= 0f
    val isEmpty: Boolean get() = !hasKeys && !hasPoint

    companion object {
        const val PKG = "sel.pkg"
        const val ID = "sel.id"
        const val TEXT = "sel.text"
        const val DESC = "sel.desc"
        const val CLS = "sel.cls"
        const val X = "sel.x"
        const val Y = "sel.y"
        const val SW = "sel.sw"
        const val SH = "sel.sh"
        const val LABEL = "sel.label"
        val KEYS = listOf(ID, TEXT, DESC, CLS, X, Y, SW, SH, LABEL)

        fun from(step: Step): Selector = Selector(
            pkg = step.s(PKG).ifBlank { step.s("pkg") },
            id = step.s(ID),
            text = step.s(TEXT),
            desc = step.s(DESC),
            cls = step.s(CLS),
            x = step.s(X).toFloatOrNull() ?: -1f,
            y = step.s(Y).toFloatOrNull() ?: -1f,
            screenW = step.i(SW, 0),
            screenH = step.i(SH, 0),
            label = step.s(LABEL),
        )
    }
}

object Phone {
    /** "01521216482" → "8801521216482" (Bangladesh), "+44 7700…" → "447700…" */
    fun toInternational(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (d.startsWith("00")) d = d.drop(2)
        if (d.length == 11 && d.startsWith("01")) d = "88$d"
        return d
    }
}

/** Settings of the "Edit a file" step (the list of changes is stored as JSON text). */
object EditParams {
    private val listSer = ListSerializer(FileEdit.serializer())

    fun edits(step: Step): List<FileEdit> = try {
        Repo.json.decodeFromString(listSer, step.s("edits", "[]"))
    } catch (_: Exception) {
        emptyList()
    }

    fun withEdits(step: Step, list: List<FileEdit>): Step = step.put("edits", Repo.json.encodeToString(listSer, list))

    /** ISO day number: 1 = Monday … 6 = Saturday, 7 = Sunday (default). */
    fun weekStart(step: Step): DayOfWeek = DayOfWeek.of(step.i("weekStart", 7).coerceIn(1, 7))
}
