package com.taskpilot.app.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object FileOps {
    val root: File get() = Environment.getExternalStorageDirectory()

    /** "/storage/emulated/0/Download/x" → "Device storage › Download › x" */
    fun pretty(path: String): String {
        if (path.isBlank()) return ""
        val r = root.absolutePath
        val rel = if (path.startsWith(r)) path.removePrefix(r).trim('/') else path.trim('/')
        return if (rel.isEmpty()) "Device storage" else "Device storage › " + rel.split('/').joinToString(" › ")
    }

    fun parseExts(s: String): Set<String> =
        s.split(',', ' ', ';', '|').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun ok(f: File, exts: Set<String>): Boolean =
        f.isFile && !f.isHidden && !f.name.startsWith("~$") && !f.name.endsWith(".tmp") &&
            (exts.isEmpty() || f.extension.lowercase() in exts)

    /** Files changed after [since] (ms), searching up to [maxDepth] folders deep. */
    fun scanNew(folder: File, since: Long, exts: Set<String>, recursive: Boolean = true, maxDepth: Int = 4): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (f.isDirectory) {
                    if (recursive && depth < maxDepth && !f.isHidden) walk(f, depth + 1)
                } else if (ok(f, exts) && f.lastModified() >= since) out += f
            }
        }
        walk(folder, 0)
        return out.sortedBy { it.name.lowercase() }
    }

    fun listAll(folder: File, exts: Set<String>, recursive: Boolean, maxDepth: Int = 4): List<File> =
        scanNew(folder, Long.MIN_VALUE, exts, recursive, maxDepth)

    fun newestSubdir(folder: File): File? =
        folder.listFiles()?.filter { it.isDirectory && !it.isHidden }?.maxByOrNull { it.lastModified() }

    fun newestFile(folder: File, exts: Set<String>, recursive: Boolean): File? =
        listAll(folder, exts, recursive).maxByOrNull { it.lastModified() }

    fun mime(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "py" -> "text/x-python"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun commonMime(files: List<File>): String {
        val m = files.map { mime(it) }.distinct()
        if (m.size == 1) return m[0]
        val tops = m.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1 && tops[0] != "*") "${tops[0]}/*" else "*/*"
    }

    fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
}
