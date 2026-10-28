package com.taskpilot.app.engine

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

object Perms {
    fun allFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun accessibility(ctx: Context): Boolean {
        if (AutomationService.instance != null) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(ctx, AutomationService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun notifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun exactAlarms(ctx: Context): Boolean = Scheduler.canExact(ctx)

    fun battery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    fun secureLock(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** (granted, total) for the required items */
    fun readiness(ctx: Context): Pair<Int, Int> {
        val list = listOf(allFiles(ctx), accessibility(ctx), notifications(ctx), exactAlarms(ctx), battery(ctx))
        return list.count { it } to list.size
    }

    // ---- intents that open the right settings page ----
    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun allFilesIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(ctx))
        else appInfoIntent(ctx)

    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * Best-effort deep link straight to TaskPilot's row on the Accessibility page.
     * Many skins honour the hidden fragment-args extras; if one doesn't, it still lands
     * on the Accessibility list where the service can be tapped.
     */
    fun accessibilityIntent(ctx: Context): Intent {
        val component = ComponentName(ctx, AutomationService::class.java).flattenToString()
        val args = android.os.Bundle().apply { putString(":settings:fragment_args_key", component) }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", args)
    }

    fun exactAlarmIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(ctx))
        else appInfoIntent(ctx)

    @Suppress("BatteryLife")
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(ctx))

    fun appInfoIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))

    fun notificationSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun securitySettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}

/**
 * Which phone skin is this, so Setup can give instructions that actually match the screens.
 * Android 13+ blocks Accessibility for apps installed from a file ("Restricted setting"), and
 * each maker hides the "Allow restricted settings" switch in a slightly different place.
 */
object Oem {
    private val maker: String = (Build.MANUFACTURER ?: "").lowercase()
    private val brand: String = (Build.BRAND ?: "").lowercase()

    private fun has(vararg names: String) = names.any { maker.contains(it) || brand.contains(it) }

    val isColorOs: Boolean get() = has("oppo", "realme", "oneplus")
    val isHuawei: Boolean get() = has("huawei", "honor")
    val isXiaomi: Boolean get() = has("xiaomi", "redmi", "poco")
    val isVivo: Boolean get() = has("vivo", "iqoo")
    val isSamsung: Boolean get() = has("samsung")

    /** Heavily-customised skins that also kill background apps / need Autostart. */
    val isHeavySkin: Boolean get() = isColorOs || isHuawei || isXiaomi || isVivo

    /** Friendly brand name for on-screen text, e.g. "Realme". */
    val label: String
        get() = (Build.MANUFACTURER ?: "your phone").trim().replaceFirstChar { it.uppercase() }

    /**
     * Does the App-info screen have a ⋮ overflow menu that holds "Allow restricted settings"?
     * Huawei/Honor (EMUI/MagicOS) typically show no overflow there — the option appears in the
     * "Restricted setting" dialog instead.
     */
    val appInfoHasOverflowMenu: Boolean get() = !isHuawei

    /** Only Android 13 (API 33)+ enforces the restricted-settings block. */
    val restrictedSettingsApply: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Step-by-step wording tuned to this skin. First item is the one people miss most. */
    fun restrictedSteps(): List<String> {
        val menu = if (appInfoHasOverflowMenu)
            "If it didn't, open App info and tap the ⋮ menu (top-right) → “Allow restricted settings”."
        else
            "If it didn't, open App info and look for an “Allow restricted settings” row (this phone's App info has no ⋮ menu)."
        return listOf(
            "Tap the service's switch once (use “Accessibility” below, then tap TaskPilot). A “Restricted setting” message pops up — this is the step most people skip.",
            "On that message, tap “Allow” or “OK”. Some phones unlock the switch right here.",
            menu,
            "Back on the Accessibility page, turn TaskPilot on — the switch now works.",
        )
    }
}
