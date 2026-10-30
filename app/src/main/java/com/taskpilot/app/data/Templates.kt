package com.taskpilot.app.data

import android.content.Context
import com.taskpilot.app.engine.FileEdit
import android.os.Environment
import java.io.File

object Templates {
    const val PYDROID = "ru.iiec.pydroid3"
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    private val weeklyFolder: String
        get() = File(Environment.getExternalStorageDirectory(), "Download/1. Weekly Demand. Use and Shortage").absolutePath

    fun weeklyDemand(): Workflow {
        val folder = weeklyFolder
        val script = "$folder/weekly_demand_use_shortage_phone.py"
        return Workflow(
            name = "Weekly Demand → WhatsApp",
            emoji = "📊",
            schedule = Schedule(enabled = false, hour = 0, minute = 40),
            steps = listOf(
                Step(type = StepType.WAKE_UNLOCK),
                weeklyDatesStep(script),
                Step(
                    type = StepType.OPEN_FILE,
                    p = mapOf("path" to script, "pkg" to PYDROID, "appName" to "Pydroid 3", "uriMode" to "file"),
                ),
                Step(type = StepType.DELAY, p = mapOf("seconds" to "4")),
                Step(
                    type = StepType.TAP_ELEMENT,
                    label = "Press Run in Pydroid",
                    p = mapOf(Selector.PKG to PYDROID, "pkg" to PYDROID, "appName" to "Pydroid 3", "timeout" to "25", "verify" to "true"),
                ),
                Step(
                    type = StepType.WAIT_NEW_FILES,
                    label = "Wait for the report files",
                    p = mapOf("folder" to folder, "timeout" to "600", "settle" to "8", "ext" to "xlsx", "stopEarly" to "true"),
                ),
                Step(
                    type = StepType.SELECT_FILES,
                    p = mapOf("folder" to folder, "mode" to "run", "ext" to "xlsx", "recursive" to "true"),
                ),
                Step(
                    type = StepType.SEND_WHATSAPP,
                    p = mapOf("phone" to "01521216482", "app" to WHATSAPP, "autoSend" to "true", "caption" to ""),
                ),
                Step(type = StepType.DELAY, p = mapOf("seconds" to "3")),
                Step(type = StepType.SYSTEM_KEY, p = mapOf("key" to "HOME")),
                Step(type = StepType.LOCK_SCREEN),
            ),
        )
    }

    /** "Edit a file": set START/END to last week (Sun–Sat) in a fresh copy that Pydroid then opens. */
    fun weeklyDatesStep(script: String, names: Pair<String, String> = "START_DATETIME" to "END_DATETIME"): Step =
        EditParams.withEdits(
            Step(
                type = StepType.EDIT_FILE,
                label = "Set the dates to last week",
                p = mapOf("path" to script, "save" to "copy", "weekStart" to "7"),
            ),
            listOf(
                FileEdit("var", names.first, "{lastweek.start} 00:00"),
                FileEdit("var", names.second, "{lastweek.end} 23:59"),
            ),
        )

    fun seedIfNeeded(ctx: Context) {
        if (Repo.flag(ctx, "seeded")) return
        if (Repo.workflows.value.isEmpty()) Repo.upsert(weeklyDemand())
        Repo.setFlag(ctx, "seeded", true)
    }
}
