package com.taskpilot.app.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.taskpilot.app.data.Selector
import com.taskpilot.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max

/** A visible thing on another app's screen, captured for the element picker. */
data class UiElement(
    val pkg: String,
    val id: String,
    val text: String,
    val desc: String,
    val cls: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val depth: Int,
) {
    val shortId: String get() = id.substringAfter(":id/", id)
    val shortCls: String get() = cls.substringAfterLast('.')
    val label: String
        get() = text.ifBlank { desc }.ifBlank { shortId.replace('_', ' ') }.ifBlank { shortCls }.take(60)

    fun toParams(screenW: Int, screenH: Int): Map<String, String> = mapOf(
        Selector.PKG to pkg,
        Selector.ID to id,
        Selector.TEXT to (if (text.length <= 60 && !editable) text else ""),
        Selector.DESC to (if (desc.length <= 80) desc else ""),
        Selector.CLS to cls,
        Selector.X to bounds.exactCenterX().toString(),
        Selector.Y to bounds.exactCenterY().toString(),
        Selector.SW to screenW.toString(),
        Selector.SH to screenH.toString(),
        Selector.LABEL to label,
    )
}

data class CaptureResult(
    val elements: List<UiElement>,
    val shot: Bitmap?,
    val screenW: Int,
    val screenH: Int,
    val pkg: String,
)

object CaptureStore {
    val result = MutableStateFlow<CaptureResult?>(null)
    val waiting = MutableStateFlow(false)
}

/**
 * The part that taps buttons inside other apps.
 * While idle it listens to (almost) nothing; it is switched on only during a run or a capture.
 */
class AutomationService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutomationService? = null
            private set
        val connected = MutableStateFlow(false)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runPill: View? = null
    private var runText: TextView? = null
    private var capturePill: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.value = true
        setActive(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polled on demand — nothing to do */ }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        connected.value = false
        scope.cancel()
        super.onDestroy()
    }

    /** Idle = minimum events. Active = full events so the screen info stays fresh. */
    fun setActive(active: Boolean) {
        main.post {
            try {
                val info = serviceInfo ?: return@post
                info.eventTypes = if (active) AccessibilityEvent.TYPES_ALL_MASK else AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                info.notificationTimeout = if (active) 100L else 1000L
                serviceInfo = info
                if (active && Build.VERSION.SDK_INT >= 33) clearCache()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ screen reading

    fun roots(pkg: String?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p == packageName) continue
                if (pkg.isNullOrBlank() || p == pkg) out += r
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) {
            val r = try { rootInActiveWindow } catch (_: Exception) { null }
            if (r != null) {
                val p = r.packageName?.toString() ?: ""
                if (p != packageName && (pkg.isNullOrBlank() || p == pkg)) out += r
            }
        }
        return out
    }

    fun foregroundPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo, Int) -> Unit) {
        visit(n, depth)
        if (depth > 70) return
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            walk(c, depth + 1, visit)
        }
    }

    fun findFirst(pkg: String?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (r in roots(pkg)) {
            var found: AccessibilityNodeInfo? = null
            walk(r, 0) { n, _ -> if (found == null && pred(n)) found = n }
            if (found != null) return found
        }
        return null
    }

    fun screenSize(): Point {
        val p = Point()
        try {
            val d = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            d?.getRealSize(p)
        } catch (_: Exception) {
        }
        if (p.x <= 0 || p.y <= 0) {
            p.x = resources.displayMetrics.widthPixels
            p.y = resources.displayMetrics.heightPixels
        }
        return p
    }

    private fun txt(n: AccessibilityNodeInfo) = n.text?.toString()?.trim() ?: ""
    private fun dsc(n: AccessibilityNodeInfo) = n.contentDescription?.toString()?.trim() ?: ""

    /** Finds the best on-screen match for a remembered element. */
    fun find(sel: Selector): AccessibilityNodeInfo? {
        if (!sel.hasKeys) return null
        val size = screenSize()
        val sx = if (sel.screenW > 0) size.x.toFloat() / sel.screenW else 1f
        val sy = if (sel.screenH > 0) size.y.toFloat() / sel.screenH else 1f
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        val r = Rect()
        for (root in roots(sel.pkg)) {
            walk(root, 0) { n, _ ->
                if (!n.isVisibleToUser) return@walk
                val id = n.viewIdResourceName ?: ""
                val t = txt(n)
                val d = dsc(n)
                var score = 0.0
                if (sel.id.isNotBlank()) { if (id == sel.id) score += 4 else return@walk }
                if (sel.text.isNotBlank()) { if (t.equals(sel.text, ignoreCase = true)) score += 3 else return@walk }
                if (sel.desc.isNotBlank()) { if (d.equals(sel.desc, ignoreCase = true)) score += 3 else return@walk }
                if (sel.cls.isNotBlank() && n.className?.toString() == sel.cls) score += 0.5
                if (sel.hasPoint) {
                    n.getBoundsInScreen(r)
                    val dist = hypot(r.exactCenterX() - sel.x * sx, r.exactCenterY() - sel.y * sy)
                    score += 1.0 / (1.0 + dist / 120.0)
                }
                if (score > bestScore) { bestScore = score; best = n }
            }
        }
        return best
    }

    suspend fun waitFor(sel: Selector, timeoutMs: Long): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val n = find(sel)
            if (n != null) return n
            delay(400)
        }
        return null
    }

    suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (foregroundPackage() == pkg || roots(pkg).isNotEmpty()) return true
            delay(400)
        }
        return false
    }

    fun focusedInput(): AccessibilityNodeInfo? = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }

    // ------------------------------------------------------------------ acting

    fun clickNode(n: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 7) {
            val can = if (long) cur.isLongClickable else cur.isClickable
            if (can && cur.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        return false
    }

    suspend fun tapNode(n: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY(), long)
    }

    suspend fun tap(x: Float, y: Float, long: Boolean = false): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(max(0f, x), max(0f, y)) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60))
            .build()
        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    fun scaledPoint(sel: Selector): Pair<Float, Float> {
        val size = screenSize()
        val sx = if (sel.screenW > 0) size.x.toFloat() / sel.screenW else 1f
        val sy = if (sel.screenH > 0) size.y.toFloat() / sel.screenH else 1f
        return sel.x * sx to sel.y * sy
    }

    fun setText(n: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun globalKey(key: String): Boolean {
        val action = when (key) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    // ------------------------------------------------------------------ lock screen PIN

    private fun isDigitKey(n: AccessibilityNodeInfo, c: Char): Boolean {
        if (!n.isVisibleToUser) return false
        val id = n.viewIdResourceName ?: ""
        val t = txt(n)
        val d = dsc(n)
        return id.endsWith("key$c") || id.endsWith("digit$c") || id.endsWith("number_$c") ||
            t == "$c" || d == "$c" || t.startsWith("$c\n") || t.startsWith("$c ")
    }

    suspend fun enterPin(pin: String): Boolean {
        val end = System.currentTimeMillis() + 7000
        while (System.currentTimeMillis() < end && findFirst(null) { isDigitKey(it, '5') } == null) delay(300)
        for (c in pin) {
            val key = findFirst(null) { isDigitKey(it, c) } ?: return false
            if (!clickNode(key)) tapNode(key)
            delay(220)
        }
        delay(350)
        val enter = findFirst(null) { n ->
            val id = n.viewIdResourceName ?: ""
            val t = txt(n).lowercase()
            val d = dsc(n).lowercase()
            n.isVisibleToUser && (id.endsWith("key_enter") || id.endsWith("ok") || t == "ok" || t == "enter" ||
                d == "enter" || d == "ok" || d == "done" || d == "confirm")
        }
        if (enter != null && !clickNode(enter)) tapNode(enter)
        return true
    }

    // ------------------------------------------------------------------ WhatsApp send

    private val sendWords = setOf("send", "পাঠান", "পাঠাও")
    private val sendIds = listOf(":id/send", ":id/send_btn", ":id/send_media_btn", ":id/send_button", ":id/media_send", ":id/send_container")

    private fun isSendNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val id = n.viewIdResourceName ?: ""
        val t = txt(n).lowercase()
        val d = dsc(n).lowercase()
        return sendIds.any { id.endsWith(it) } ||
            d in sendWords ||
            ((n.isClickable || id == "android:id/button1") && t in sendWords)
    }

    private fun findSend(pkg: String): AccessibilityNodeInfo? = findFirst(pkg) { isSendNode(it) }

    /** True when any of [names] is shown as text inside [pkg] (e.g. sent document bubbles in the chat). */
    fun showsAnyText(pkg: String, names: List<String>): Boolean {
        val needles = names.map { it.lowercase() }.filter { it.length >= 4 }
        if (needles.isEmpty()) return false
        return findFirst(pkg) { n ->
            if (!n.isVisibleToUser) return@findFirst false
            val t = (txt(n) + " " + dsc(n)).lowercase()
            needles.any { t.contains(it) }
        } != null
    }

    /** Short description of what is on screen in [pkg] — saved in History when something goes wrong. */
    fun describeScreen(pkg: String, max: Int = 10): String {
        val parts = LinkedHashSet<String>()
        for (r in roots(pkg)) {
            walk(r, 0) { n, _ ->
                if (parts.size >= max || !n.isVisibleToUser) return@walk
                if (!(n.isClickable || n.isEditable)) return@walk
                val id = (n.viewIdResourceName ?: "").substringAfter(":id/", "")
                val label = txt(n).ifBlank { dsc(n) }.take(24)
                val p = listOf(id, if (label.isNotBlank()) "“$label”" else "").filter { it.isNotBlank() }.joinToString(" ")
                if (p.isNotBlank()) parts += p
            }
        }
        return if (parts.isEmpty()) "nothing tappable found" else parts.joinToString(", ")
    }

    data class SendResult(val clicks: Int, val seenInChat: Boolean, val screen: String)

    /**
     * Presses Send (and the "Send to …?" confirmation) until nothing is left to press, then checks
     * that the files really are in the chat. WhatsApp sometimes sends by itself or uses a button
     * we don't recognise — the chat check catches both cases.
     */
    suspend fun sendAndConfirm(pkg: String, fileNames: List<String>, timeoutMs: Long = 45_000): SendResult {
        val start = System.currentTimeMillis()
        val end = start + timeoutMs
        var clicks = 0
        var lastClick = 0L
        var quietChecks = 0          // consecutive checks with files in chat and no Send button
        while (System.currentTimeMillis() < end) {
            val n = if (clicks < 4) findSend(pkg) else null
            if (n != null) {
                quietChecks = 0
                if (clickNode(n) || tapNode(n)) {
                    clicks++
                    lastClick = System.currentTimeMillis()
                    delay(2200)
                    continue
                }
            } else if (showsAnyText(pkg, fileNames)) {
                quietChecks++
                if (quietChecks >= 3) return SendResult(clicks, true, "")
            } else {
                quietChecks = 0
            }
            if (clicks > 0 && System.currentTimeMillis() - lastClick > 8000) break
            delay(500)
        }
        return SendResult(clicks, false, describeScreen(pkg))
    }

    // ------------------------------------------------------------------ element capture

    fun beginCapture(targetPkg: String, launch: Boolean) {
        setActive(true)
        CaptureStore.result.value = null
        CaptureStore.waiting.value = true
        if (launch && targetPkg.isNotBlank()) {
            packageManager.getLaunchIntentForPackage(targetPkg)?.let {
                try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
        main.postDelayed({ showCapturePill() }, 600)
    }

    fun cancelCapture() {
        removeCapturePill()
        CaptureStore.waiting.value = false
        setActive(false)
        bringAppToFront()
    }

    private fun doCapture() {
        removeCapturePill()
        scope.launch {
            delay(450)
            val size = screenSize()
            val elements = collectElements()
            val shot = screenshot()
            CaptureStore.result.value = CaptureResult(
                elements = elements,
                shot = shot,
                screenW = size.x,
                screenH = size.y,
                pkg = elements.groupingBy { it.pkg }.eachCount().maxByOrNull { it.value }?.key ?: "",
            )
            CaptureStore.waiting.value = false
            setActive(false)
            bringAppToFront()
        }
    }

    private fun collectElements(): List<UiElement> {
        val out = ArrayList<UiElement>()
        val ws = try { windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        val roots = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
            .filter { it.packageName?.toString() != packageName }
            .ifEmpty { roots(null).filter { it.packageName?.toString() != "com.android.systemui" } }
        for (root in roots) {
            walk(root, 0) { n, depth ->
                if (!n.isVisibleToUser) return@walk
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() <= 2 || r.height() <= 2) return@walk
                val t = txt(n)
                val d = dsc(n)
                val id = n.viewIdResourceName ?: ""
                val interesting = n.isClickable || n.isLongClickable || n.isEditable || t.isNotBlank() || d.isNotBlank()
                if (!interesting) return@walk
                out += UiElement(
                    pkg = n.packageName?.toString() ?: "",
                    id = id,
                    text = t,
                    desc = d,
                    cls = n.className?.toString() ?: "",
                    bounds = r,
                    clickable = n.isClickable || n.isLongClickable,
                    editable = n.isEditable,
                    depth = depth,
                )
            }
        }
        return out
    }

    private suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bmp = try {
                            val hb = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            if (soft != null && soft.width > 1080) {
                                val h = (soft.height * (1080f / soft.width)).toInt()
                                Bitmap.createScaledBitmap(soft, 1080, h, true)
                            } else soft
                        } catch (_: Exception) {
                            null
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun bringAppToFront() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ floating pills

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pill(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(8), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor("#F01B2150"))
        }
        elevation = dp(8).toFloat()
    }

    private fun label(text: String, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, bg: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor(bg))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
    }

    private fun addOverlay(v: View, topDp: Int) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(topDp)
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun removeView(v: View?) {
        if (v == null) return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun showCapturePill() {
        removeCapturePill()
        val p = pill()
        p.addView(label("Open the screen, then"))
        p.addView(button("◉ Capture", "#FF3D4ED8") { doCapture() })
        p.addView(button("✕", "#33FFFFFF") { cancelCapture() })
        capturePill = p
        addOverlay(p, 40)
    }

    private fun removeCapturePill() {
        val v = capturePill
        capturePill = null
        main.post { removeView(v) }
    }

    fun showRunPill(title: String) {
        main.post {
            removeView(runPill)
            val p = pill()
            val t = label("▶ $title", bold = true)
            p.addView(t)
            p.addView(button("Stop", "#FFD9433A") { Runner.stop() })
            runText = t
            runPill = p
            addOverlay(p, 6)
        }
    }

    fun updateRunPill(text: String) {
        main.post { runText?.text = text }
    }

    fun hideRunPill() {
        main.post {
            removeView(runPill)
            runPill = null
            runText = null
        }
    }
}
