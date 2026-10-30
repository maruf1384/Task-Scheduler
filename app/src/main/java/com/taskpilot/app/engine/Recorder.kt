package com.taskpilot.app.engine

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import com.taskpilot.app.ui.RecorderBar
import com.taskpilot.app.ui.RecorderHighlight
import com.taskpilot.app.ui.RecorderPanel
import com.taskpilot.app.ui.theme.TaskPilotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Build it in the real app": TaskPilot opens another app (Gmail, D-Nothi, Chrome…) and floats a
 * small bar over it. "Options" lists everything you can do on the current screen — buttons, text
 * boxes, list items, switches, scrollable areas. Pick one and an action (tap, type, wait, scroll):
 * TaskPilot does it in the real app, saves it as a step, and lists the options of the next screen.
 * Or switch on "Record my taps" and just use the app — your taps and typing are saved as steps.
 *
 * Nothing is sent anywhere; passwords are never recorded.
 */
object Recorder {

    data class Session(val wfId: String, val insertAt: Int, val pkg: String, val appName: String)

    enum class Kind(val title: String) {
        BUTTON("Buttons"), FIELD("Text boxes"), ITEM("Items"), TOGGLE("Switches"), SCROLL("Scroll areas"),
    }

    data class Opt(
        val key: Int,
        val kind: Kind,
        val label: String,
        val detail: String,
        val bounds: Rect,
        val params: Map<String, String>,
        val node: AccessibilityNodeInfo,
        val password: Boolean,
    )

    // ------------------------------------------------------------------ state the overlay shows
    val session = MutableStateFlow<Session?>(null)
    val steps = MutableStateFlow<List<Step>>(emptyList())
    val options = MutableStateFlow<List<Opt>>(emptyList())
    val panelOpen = MutableStateFlow(true)
    val liveRecord = MutableStateFlow(false)
    val selected = MutableStateFlow<Opt?>(null)
    val busy = MutableStateFlow(false)
    val typingInto = MutableStateFlow<Opt?>(null)
    val message = MutableStateFlow("")
    val barOnTop = MutableStateFlow(true)
    val confirmCancel = MutableStateFlow(false)

    /** Shown by TaskPilot when you come back ("Added 6 steps from Gmail"). */
    val finished = MutableStateFlow<String?>(null)

    val isActive: Boolean get() = session.value != null

    private var svc: AutomationService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var owner: OverlayOwner? = null
    private var barView: View? = null
    private var panelView: View? = null
    private var hiView: View? = null
    private var ignoreEventsUntil = 0L
    private var pendingText: Pair<Map<String, String>, String>? = null

    // ------------------------------------------------------------------ start / stop

    fun start(service: AutomationService, s: Session, addOpenStep: Boolean): String? {
        if (Runner.isRunning) return "An automation is running — wait for it to finish first."
        stop()
        svc = service
        session.value = s
        steps.value = if (addOpenStep) listOf(
            Step(type = StepType.LAUNCH_APP, p = mapOf("pkg" to s.pkg, "appName" to s.appName)),
        ) else emptyList()
        options.value = emptyList()
        selected.value = null
        typingInto.value = null
        liveRecord.value = false
        panelOpen.value = true
        confirmCancel.value = false
        message.value = "Opening ${s.appName}…"
        service.setActive(true)
        service.packageManager.getLaunchIntentForPackage(s.pkg)?.let {
            try { service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        showOverlays(service)
        scope.launch {
            delay(1800)
            refresh()
            message.value = "Pick what to do on this screen"
        }
        return null
    }

    fun finish() {
        flushText()
        val s = session.value ?: return
        val recorded = steps.value
        val wf = Repo.get(s.wfId)
        if (wf != null && recorded.isNotEmpty()) {
            val list = wf.steps.toMutableList()
            list.addAll(s.insertAt.coerceIn(0, list.size), recorded)
            Repo.upsert(wf.copy(steps = list))
            finished.value = "Added ${recorded.size} step${if (recorded.size == 1) "" else "s"} from ${s.appName}"
        } else {
            finished.value = "Nothing was recorded"
        }
        val service = svc
        stop()
        service?.bringAppToFront()
    }

    fun cancel() {
        val service = svc
        stop()
        finished.value = "Recording discarded"
        service?.bringAppToFront()
    }

    fun stop() {
        refreshJob?.cancel()
        val wm = svc?.getSystemService(WindowManager::class.java)
        listOf(barView, panelView, hiView).forEach { v -> if (v != null) try { wm?.removeView(v) } catch (_: Exception) {} }
        barView = null; panelView = null; hiView = null
        owner?.destroy()
        owner = null
        if (session.value != null) svc?.setActive(false)
        session.value = null
        options.value = emptyList()
        selected.value = null
        typingInto.value = null
        pendingText = null
        svc = null
    }

    // ------------------------------------------------------------------ actions from the overlay

    fun togglePanel() {
        val open = !panelOpen.value
        panelOpen.value = open
        if (open) { liveRecord.value = false; refresh() }
        updatePanelWindow()
    }

    fun setLive(on: Boolean) {
        liveRecord.value = on
        if (on) {
            panelOpen.value = false
            updatePanelWindow()
            message.value = "Use ${session.value?.appName ?: "the app"} normally — your taps and typing are saved"
        } else {
            flushText()
            message.value = "Record my taps: off"
        }
    }

    fun moveBar() {
        barOnTop.value = !barOnTop.value
        val v = barView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.gravity = (if (barOnTop.value) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        try { svc?.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    fun undo() {
        val l = steps.value
        if (l.isEmpty()) return
        steps.value = l.dropLast(1)
        message.value = "Removed the last step (the app itself isn't changed back)"
    }

    fun select(o: Opt?) { selected.value = o }

    fun refresh() {
        val service = svc ?: return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            busy.value = true
            val list = withContext(Dispatchers.Default) { try { collect(service) } catch (_: Exception) { emptyList() } }
            options.value = list
            selected.value = null
            busy.value = false
        }
    }

    /** Does [action] in the real app and saves it as a step. */
    fun choose(o: Opt, action: String) {
        val service = svc ?: return
        val s = session.value ?: return
        val base = o.params + mapOf("pkg" to (o.params[Selector.PKG] ?: s.pkg), "appName" to appNameFor(o.params[Selector.PKG], s))
        when (action) {
            "wait" -> {
                add(Step(type = StepType.WAIT_ELEMENT, label = "Wait for “${o.label}”", p = base + mapOf("mode" to "appear", "timeout" to "60")))
                message.value = "Saved: wait for “${o.label}”"
                return
            }
            "type" -> {
                if (o.password) {
                    message.value = "Password boxes are never recorded — TaskPilot doesn't store passwords."
                    return
                }
                o.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                o.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                typingInto.value = o
                panelOpen.value = false
                updatePanelWindow()
                message.value = "Type in “${o.label}” using the app's keyboard, then tap Save text"
                return
            }
        }
        scope.launch {
            ignoreEventsUntil = System.currentTimeMillis() + 1500
            val reopen = panelOpen.value
            panelOpen.value = false
            updatePanelWindow()
            delay(250)
            when (action) {
                "tap", "long" -> {
                    val long = action == "long"
                    add(Step(type = StepType.TAP_ELEMENT, label = (if (long) "Long-press" else "Tap") + " “${o.label}”",
                        p = base + mapOf("timeout" to "20", "coords" to "true", "long" to long.toString())))
                    if (!service.clickNode(o.node, long)) service.tapNode(o.node, long)
                    message.value = "Saved: ${if (long) "long-press" else "tap"} “${o.label}”"
                }
                "down", "up" -> {
                    add(Step(type = StepType.SCROLL, label = "Scroll ${o.label} $action", p = base + mapOf("dir" to action, "times" to "1", "timeout" to "15")))
                    service.scrollNode(o.node, down = action == "down")
                    message.value = "Saved: scroll $action"
                }
            }
            delay(1300)
            if (reopen) { panelOpen.value = true; updatePanelWindow() }
            refresh()
        }
    }

    fun phoneKey(key: String) {
        val service = svc ?: return
        when (key) {
            "WAIT" -> {
                add(Step(type = StepType.DELAY, p = mapOf("seconds" to "2")))
                message.value = "Saved: wait 2 s"
                return
            }
        }
        add(Step(type = StepType.SYSTEM_KEY, p = mapOf("key" to key)))
        scope.launch {
            ignoreEventsUntil = System.currentTimeMillis() + 1500
            service.globalKey(key)
            message.value = "Saved: ${key.lowercase()}"
            delay(1200)
            refresh()
        }
    }

    fun saveTyped() {
        val o = typingInto.value ?: return
        o.node.refresh()
        val text = o.node.text?.toString() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= 26) o.node.hintText?.toString() else null
        val typed = if (text == hint) "" else text
        typingInto.value = null
        if (o.node.isPassword) {
            message.value = "Password boxes are never recorded."
        } else {
            val s = session.value ?: return
            add(Step(type = StepType.INPUT_TEXT, label = "Type in “${o.label}”",
                p = o.params + mapOf("pkg" to s.pkg, "appName" to s.appName, "text" to typed, "timeout" to "15")))
            message.value = "Saved: type “${typed.take(30)}”"
        }
        panelOpen.value = true
        updatePanelWindow()
        refresh()
    }

    fun cancelTyping() {
        typingInto.value = null
        panelOpen.value = true
        updatePanelWindow()
        refresh()
    }

    private fun add(step: Step) { steps.value = steps.value + step }

    private fun appNameFor(pkg: String?, s: Session): String =
        if (pkg.isNullOrBlank() || pkg == s.pkg) s.appName
        else svc?.let { com.taskpilot.app.ui.AppIcons.label(it, pkg) } ?: pkg

    // ------------------------------------------------------------------ "Record my taps"

    fun onEvent(service: AutomationService, e: AccessibilityEvent) {
        if (!liveRecord.value || session.value == null) return
        if (System.currentTimeMillis() < ignoreEventsUntil) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == service.packageName || pkg == "com.android.systemui") return
        val src = e.source ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (src.isEditable) return                         // tapping into a box: the typing is what matters
                flushText()
                val long = e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                val label = labelOf(src)
                val s = session.value ?: return
                val size = service.screenSize()
                add(Step(type = StepType.TAP_ELEMENT, label = (if (long) "Long-press" else "Tap") + " “$label”",
                    p = paramsFor(src, label, size.x, size.y) + mapOf("pkg" to pkg, "appName" to appNameFor(pkg, s),
                        "timeout" to "20", "coords" to "true", "long" to long.toString())))
                message.value = "Saved: tap “$label”"
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (e.isPassword || src.isPassword) { pendingText = null; return }
                val size = service.screenSize()
                val text = e.text?.joinToString("") ?: src.text?.toString() ?: ""
                pendingText = paramsFor(src, labelOf(src), size.x, size.y) + mapOf("pkg" to pkg) to text
            }
        }
    }

    private fun flushText() {
        val (p, text) = pendingText ?: return
        pendingText = null
        val s = session.value ?: return
        add(Step(type = StepType.INPUT_TEXT, label = "Type in “${p[Selector.LABEL] ?: "text box"}”",
            p = p + mapOf("appName" to appNameFor(p["pkg"], s), "text" to text, "timeout" to "15")))
        message.value = "Saved: type “${text.take(30)}”"
    }

    // ------------------------------------------------------------------ reading the screen

    private fun t(n: AccessibilityNodeInfo) = n.text?.toString()?.trim().orEmpty()
    private fun d(n: AccessibilityNodeInfo) = n.contentDescription?.toString()?.trim().orEmpty()

    private fun firstChildText(n: AccessibilityNodeInfo, max: Int = 3): String {
        val parts = ArrayList<String>()
        fun go(x: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= max || depth > 8) return
            for (i in 0 until x.childCount) {
                val c = try { x.getChild(i) } catch (_: Exception) { null } ?: continue
                val s = t(c).ifBlank { d(c) }
                if (s.isNotBlank() && c.isVisibleToUser) parts += s
                go(c, depth + 1)
                if (parts.size >= max) return
            }
        }
        go(n, 0)
        return parts.joinToString(" · ").take(60)
    }

    fun labelOf(n: AccessibilityNodeInfo): String {
        val own = t(n).ifBlank { d(n) }
        if (own.isNotBlank()) return own.take(60)
        if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
        firstChildText(n).takeIf { it.isNotBlank() }?.let { return it }
        val id = n.viewIdResourceName?.substringAfter(":id/", "").orEmpty()
        if (id.isNotBlank()) return id.replace('_', ' ')
        return n.className?.toString()?.substringAfterLast('.') ?: "item"
    }

    /** How a step will find this element again: its own id/text/description, else a text inside it. */
    private fun paramsFor(n: AccessibilityNodeInfo, label: String, sw: Int, sh: Int): Map<String, String> {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val id = n.viewIdResourceName.orEmpty()
        val own = t(n)
        val desc = d(n)
        val text = when {
            n.isEditable -> ""
            own.isNotBlank() && own.length <= 60 -> own
            id.isBlank() && desc.isBlank() -> firstChildText(n, 1).takeIf { it.length in 1..60 }.orEmpty()
            else -> ""
        }
        return mapOf(
            Selector.PKG to (n.packageName?.toString() ?: ""),
            Selector.ID to id,
            Selector.TEXT to text,
            Selector.DESC to (if (desc.length <= 80) desc else ""),
            Selector.CLS to (n.className?.toString() ?: ""),
            Selector.X to r.exactCenterX().toString(),
            Selector.Y to r.exactCenterY().toString(),
            Selector.SW to sw.toString(),
            Selector.SH to sh.toString(),
            Selector.LABEL to label,
        )
    }

    private fun collect(service: AutomationService): List<Opt> {
        val size = service.screenSize()
        val screenArea = size.x.toLong() * size.y
        val own = service.packageName
        val roots = try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        } catch (_: Exception) { emptyList() }
            .filter { val p = it.packageName?.toString(); p != own && p != "com.android.systemui" }
            .ifEmpty { service.roots(null).filter { it.packageName?.toString() != own } }

        val out = ArrayList<Opt>()
        val seen = HashSet<String>()
        var visited = 0
        fun add(n: AccessibilityNodeInfo, kind: Kind, r: Rect) {
            val sig = "${kind.name}:${r.flattenToString()}"
            if (!seen.add(sig)) return
            val label = labelOf(n) + if (kind == Kind.TOGGLE) (if (n.isChecked) " (on)" else " (off)") else ""
            val idShort = n.viewIdResourceName?.substringAfter(":id/", "").orEmpty()
            val cls = n.className?.toString()?.substringAfterLast('.').orEmpty()
            val detail = listOf(idShort, cls).filter { it.isNotBlank() }.joinToString(" · ")
            out += Opt(out.size, kind, label, detail, Rect(r), paramsFor(n, label.removeSuffix(" (on)").removeSuffix(" (off)"), size.x, size.y), n, n.isPassword)
        }
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 5000 || depth > 60) return
            if (n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 3 && r.height() > 3) {
                    val clickable = n.isClickable || n.isLongClickable
                    val labelled = t(n).isNotBlank() || d(n).isNotBlank()
                    val huge = r.width().toLong() * r.height() > screenArea * 0.8
                    val cls = n.className?.toString().orEmpty()
                    when {
                        n.isEditable -> add(n, Kind.FIELD, r)
                        n.isCheckable -> add(n, Kind.TOGGLE, r)
                        clickable && !(huge && !labelled) ->
                            add(n, if (labelled || cls.contains("Button") || cls.contains("ImageView")) Kind.BUTTON else Kind.ITEM, r)
                    }
                    if (n.isScrollable) add(n, Kind.SCROLL, r)
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                walk(c, depth + 1)
            }
        }
        roots.forEach { walk(it, 0) }
        return out.sortedWith(compareBy<Opt>({ it.bounds.top / 24 }, { it.bounds.left })).mapIndexed { i, o -> o.copy(key = i) }.take(250)
    }

    // ------------------------------------------------------------------ overlay windows

    private fun lp(w: Int, h: Int, gravity: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS),
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun compose(service: AutomationService, o: OverlayOwner, content: @androidx.compose.runtime.Composable () -> Unit): View =
        ComposeView(service).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { TaskPilotTheme { content() } }
        }

    private fun showOverlays(service: AutomationService) {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val o = OverlayOwner().also { it.create() }
        owner = o
        hiView = compose(service, o) { RecorderHighlight() }.also {
            try { wm.addView(it, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START, false)) } catch (_: Exception) {}
        }
        barView = compose(service, o) { RecorderBar() }.also {
            try {
                wm.addView(it, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL, true)
                    .apply { y = (30 * service.resources.displayMetrics.density).toInt() })
            } catch (_: Exception) {}
        }
        updatePanelWindow()
    }

    private fun updatePanelWindow() {
        val service = svc ?: return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val o = owner ?: return
        val want = panelOpen.value && typingInto.value == null
        if (want && panelView == null) {
            val h = (service.screenSize().y * 0.52f).toInt()
            panelView = compose(service, o) { RecorderPanel() }.also {
                try { wm.addView(it, lp(WindowManager.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM, true)) } catch (_: Exception) {}
            }
        } else if (!want && panelView != null) {
            try { wm.removeView(panelView) } catch (_: Exception) {}
            panelView = null
            selected.value = null
        }
    }

    /** Minimal lifecycle so Compose can live in a window owned by a service. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        fun create() {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
