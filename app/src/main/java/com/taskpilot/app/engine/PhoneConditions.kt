package com.taskpilot.app.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.taskpilot.app.core.CondEnv
import java.io.File

/**
 * Answers the conditions of If / Repeat steps from the phone. Every answer is read at the moment
 * it is asked — nothing is kept, nothing is watched in the background. Screen text is only
 * compared here; it is never logged or stored.
 */
class PhoneConditions(
    private val ctx: Context,
    /** Null in the editor's "right now" preview: screen checks then answer false. */
    private val svc: AutomationService?,
    private val rc: RunContext,
) : CondEnv {
    override fun textOnScreen(text: String, pkg: String): Boolean {
        val want = text.trim()
        if (want.isEmpty()) return false
        return (svc ?: return false).screenTexts(pkg, max = 1500).any { it.contains(want, ignoreCase = true) }
    }

    override fun appInFront(pkg: String): Boolean = pkg.isNotBlank() && svc?.foregroundPackage() == pkg

    override fun pathExists(path: String): Boolean = path.isNotBlank() && File(path).exists()

    override fun filesFound(): Int = rc.files.size

    override fun batteryPercent(): Int {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val p = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (p in 0..100) return p
        // Older phones: read the last battery broadcast (a "sticky" one — no receiver is kept).
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) level * 100 / scale else 0
    }

    override fun charging(): Boolean {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        if (bm != null && bm.isCharging) return true
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Connected AND Android has checked that the connection really reaches the internet. */
    override fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun expand(s: String): String = StepExec.expand(s, rc)
}
