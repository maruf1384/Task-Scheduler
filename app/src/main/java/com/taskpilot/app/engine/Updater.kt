package com.taskpilot.app.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Lets TaskPilot install its own updates from an APK file on the phone.
 *
 * Why: Android 13+ treats an app installed by tapping a downloaded APK as "sideloaded" and blocks
 * its Accessibility switch ("Restricted setting") — and does it again after every such update.
 * Apps installed through Android's installer-session API (the way app stores install) are not
 * restricted. So when TaskPilot installs the new APK itself, the Accessibility permission you
 * already gave simply carries over.
 *
 * Completely local: you download the APK yourself; TaskPilot only reads it from your storage.
 */
object Updater {
    const val ACTION_STATUS = "com.taskpilot.app.INSTALL_STATUS"

    data class Candidate(val file: File, val versionName: String, val versionCode: Long)

    fun currentVersion(ctx: Context): Pair<String, Long> {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return (pi.versionName ?: "?") to code
    }

    /** Reads an APK: is it TaskPilot, and which version? */
    fun inspect(ctx: Context, f: File): Candidate? {
        return try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) ?: return null
            if (pi.packageName != ctx.packageName) return null
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            Candidate(f, pi.versionName ?: "?", code)
        } catch (_: Exception) {
            null
        }
    }

    /** TaskPilot APKs in Download (and one folder deeper), newest version first. */
    fun findInDownloads(ctx: Context): List<Candidate> {
        val dl = File(FileOps.root, "Download")
        val files = ArrayList<File>()
        dl.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".apk", true)) files += f
            else if (f.isDirectory && !f.isHidden) f.listFiles()?.filter { it.isFile && it.name.endsWith(".apk", true) }?.let { files += it }
        }
        return files.filter { it.name.contains("taskpilot", true) || files.size <= 30 }
            .mapNotNull { inspect(ctx, it) }
            .sortedWith(compareByDescending<Candidate> { it.versionCode }.thenByDescending { it.file.lastModified() })
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Starts the install. Returns null when Android's confirmation is on its way, else a reason. */
    fun install(ctx: Context, c: Candidate): String? {
        if (!canInstall(ctx)) return "Allow TaskPilot to install updates first."
        val pkgInstaller = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(c.file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        return try {
            val id = pkgInstaller.createSession(params)
            pkgInstaller.openSession(id).use { session ->
                c.file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, c.file.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    ctx, id,
                    Intent(ctx, InstallReceiver::class.java).setAction(ACTION_STATUS),
                    flags,
                )
                session.commit(pi.intentSender)
            }
            null
        } catch (e: Exception) {
            "Couldn't start the update: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/** Receives the installer's answers: show Android's confirmation, or report the result. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let {
                    try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Notifier.post(ctx, Notifier.CH_RUNS, "TaskPilot updated", "Your automations and permissions were kept.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                val hint = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This APK can't replace the installed TaskPilot (different signature or older version)."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space."
                    else -> msg
                }
                Notifier.post(ctx, Notifier.CH_ALERTS, "TaskPilot update failed", hint)
            }
        }
    }
}
