package com.taskpilot.app.data

import com.taskpilot.app.engine.FileEdit
import kotlinx.serialization.builtins.ListSerializer
import java.time.DayOfWeek

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
