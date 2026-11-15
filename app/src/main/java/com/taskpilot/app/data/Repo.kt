package com.taskpilot.app.data

import android.content.Context
import com.taskpilot.app.core.RunStatus
import com.taskpilot.app.core.Trace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Everything is stored as small JSON files in the app's private folder. No server. */
object Repo {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private lateinit var dir: File
    private val lock = Any()

    private val _workflows = MutableStateFlow<List<Workflow>>(emptyList())
    val workflows: StateFlow<List<Workflow>> = _workflows

    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val runs: StateFlow<List<RunRecord>> = _runs

    private val _loadProblem = MutableStateFlow<String?>(null)

    /**
     * Set when a saved file could not be read at start-up. "Couldn't load" used to look exactly
     * like "nothing saved yet" — and the next save then wrote an empty list over the real file.
     * Now the unreadable file is first copied aside, and Home shows this message.
     */
    val loadProblem: StateFlow<String?> = _loadProblem

    private val wfFile get() = File(dir, "workflows.json")
    private val runFile get() = File(dir, "runs.json")

    fun init(ctx: Context) {
        dir = ctx.filesDir
        _workflows.value = read(wfFile) ?: emptyList()
        _runs.value = read(runFile) ?: emptyList()
    }

    private inline fun <reified T> read(f: File): T? = try {
        if (f.exists()) json.decodeFromString<T>(f.readText()) else null
    } catch (e: Exception) {
        setAside(f, e)
        null
    }

    /** Keeps a copy of a file that couldn't be read, so nothing is lost when the app saves again. */
    private fun setAside(f: File, e: Exception) {
        val copy = File(f.parentFile, "${f.name}.unreadable-${System.currentTimeMillis()}")
        val kept = try { f.copyTo(copy, overwrite = true); true } catch (_: Exception) { false }
        Trace.log("Repo", "could not read ${f.name}: ${e.javaClass.simpleName}; copy kept=$kept")
        _loadProblem.value = "TaskPilot couldn't read ${f.name} (${e.javaClass.simpleName})." +
            if (kept) " A copy was kept as ${copy.name} inside the app, so it can be recovered — use History → Copy report and ask for help."
            else " Please use History → Copy report and ask for help before changing anything."
    }

    private inline fun <reified T> write(f: File, value: T) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(value))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun get(id: String): Workflow? = _workflows.value.firstOrNull { it.id == id }

    fun upsert(wf: Workflow) = synchronized(lock) {
        val list = _workflows.value.toMutableList()
        val i = list.indexOfFirst { it.id == wf.id }
        if (i >= 0) list[i] = wf else list.add(wf)
        _workflows.value = list
        write(wfFile, list)
    }

    fun insertAt(index: Int, wf: Workflow) = synchronized(lock) {
        val list = _workflows.value.filterNot { it.id == wf.id }.toMutableList()
        list.add(index.coerceIn(0, list.size), wf)
        _workflows.value = list
        write(wfFile, list)
    }

    fun delete(id: String) = synchronized(lock) {
        val list = _workflows.value.filterNot { it.id == id }
        _workflows.value = list
        write(wfFile, list)
    }

    fun markRun(id: String, at: Long, status: RunStatus) {
        val wf = get(id) ?: return
        upsert(wf.copy(lastRunAt = at, lastStatus = status))
    }

    fun addRun(r: RunRecord) = synchronized(lock) {
        val list = (listOf(r) + _runs.value).take(150)
        _runs.value = list
        write(runFile, list)
    }

    fun clearRuns() = synchronized(lock) {
        _runs.value = emptyList()
        write(runFile, emptyList<RunRecord>())
    }

    // --- tiny key/value settings ---
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("taskpilot", Context.MODE_PRIVATE)
    fun flag(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean(key, false)
    fun setFlag(ctx: Context, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(key, v).apply()
}
