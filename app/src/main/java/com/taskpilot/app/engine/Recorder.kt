package com.taskpilot.app.engine

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Looper
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
import com.taskpilot.app.core.EngineMode
import com.taskpilot.app.core.OptAction
import com.taskpilot.app.core.OptKind
import com.taskpilot.app.core.Owner
import com.taskpilot.app.core.RecEffect
import com.taskpilot.app.core.RecEvent
import com.taskpilot.app.core.RecMode
import com.taskpilot.app.core.RecSession
import com.taskpilot.app.core.RecState
import com.taskpilot.app.core.RecorderMachine
import com.taskpilot.app.core.Refusal
import com.taskpilot.app.core.ScreenOption
import com.taskpilot.app.core.ScreenWatch
import com.taskpilot.app.core.Trace
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.ui.AppIcons
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Build it in the real app": TaskPilot opens another app (Gmail, D-Nothi, Chrome…) and floats a
 * small bar over it. The options panel lists everything on the app's current screen; pick one and
 * what to do with it — TaskPilot does it in the real app, saves it as a step, and the list moves
 * on to the next screen BY ITSELF. Or switch on "Record my taps" and just use the app.
 *
 * This file is only the Android half:
 *  - WHAT state the recorder is in and how it changes → [RecorderMachine] (pure, tested).
 *  - WHEN to re-read the screen → [ScreenWatch] (pure, tested), fed by accessibility events.
 *  - Here: read the screen into [ScreenOption]s, carry out [RecEffect]s, show the overlay windows.
 *
 * Every change goes through [send]. Nothing is sent anywhere; passwords are never recorded.
 */
object Recorder {

    private val _state = MutableStateFlow<RecState?>(null)

    /** null = the recorder is closed. */
    val state: StateFlow<RecState?> = _state

    /** Shown by TaskPilot when you come back ("Added 6 steps from Gmail"). */
    val finished = MutableStateFlow<String?>(null)

    val isActive: Boolean get() = _state.value != null

    private var svc: AutomationService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var discardTimer: Job? = null
    private var watch = ScreenWatch()

    /** The live screen nodes behind the options of the last read, by [ScreenOption.sig]. */
    private var nodes: Map<String, AccessibilityNodeInfo> = emptyMap()
    private var typingNode: AccessibilityNodeInfo? = null

    private var owner: OverlayOwner? = null
    private var barView: View? = null
    private var panelView: View? = null
    private var hiView: View? = null

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ start / close

    fun start(service: AutomationService, session: RecSession, addOpenStep: Boolean): Refusal? {
        Engine.start(EngineMode.Recording(session.pkg, session.appName))?.let { return it }
        svc = service
        watch = ScreenWatch()
        nodes = emptyMap()
        typingNode = null
        _state.value = RecorderMachine.start(session, addOpenStep)
        Trace.log("Recorder", "opened")
        service.packageManager.getLaunchIntentForPackage(session.pkg)?.let {
            try { service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        showOverlays(service)
        startTicker()
        scope.launch {
            // No fixed wait: as soon as the app is really in front, its options are read.
            val found = withContext(Dispatchers.Default) { service.waitForPackage(session.pkg, 12_000) }
            send(RecEvent.AppReady(found))
        }
        return null
    }

    /** Removes the overlays and gives the screen back. Safe to call twice. */
    private fun close() {
        if (_state.value == null) return
        ticker?.cancel()
        discardTimer?.cancel()
        val wm = svc?.getSystemService(WindowManager::class.java)
        listOf(barView, panelView, hiView).forEach { v -> if (v != null) try { wm?.removeView(v) } catch (_: Exception) {} }
        barView = null; panelView = null; hiView = null
        owner?.destroy()
        owner = null
        nodes = emptyMap()
        typingNode = null
        _state.value = null
        svc = null
        Trace.log("Recorder", "closed")
        Engine.finish(Owner.RECORD)
    }

    fun serviceLost() {
        if (_state.value == null) return
        close()
        finished.value = "Recording stopped — the accessibility service was switched off"
    }

    // ------------------------------------------------------------------ the one way state changes

    fun send(e: RecEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch { send(e) }
            return
        }
        val before = _state.value ?: return
        val (after, effects) = RecorderMachine.reduce(before, e)
        _state.value = after
        Trace.move("Recorder", before.mode.tag, after.mode.tag, e.tag)
        syncWindows(before, after)
        if (after.discardArmed && !before.discardArmed) {
            discardTimer?.cancel()
            discardTimer = scope.launch { delay(4000); send(RecEvent.DiscardTimeout) }
        }
        effects.forEach(::perform)
    }

    /** "Save text" on the bar: read what the user typed into the box. */
    fun saveTyped() {
        val node = typingNode ?: svc?.focusedInput()
        typingNode = null
        try { node?.refresh() } catch (_: Exception) {}
        val text = node?.text?.toString() ?: ""
        val hint = if (node != null && Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null
        send(RecEvent.SaveTyped(if (text == hint) "" else text, password = node?.isPassword == true))
    }

    private fun perform(fx: RecEffect) {
        val service = svc ?: return
        when (fx) {
            is RecEffect.Perform -> scope.launch {
                val node = nodes[fx.sig]
                delay(250)                                  // the panel has just left the screen
                if (node != null) when (fx.action) {
                    OptAction.TAP -> if (!service.clickNode(node, false)) service.tapNode(node, false)
                    OptAction.LONG_PRESS -> if (!service.clickNode(node, true)) service.tapNode(node, true)
                    OptAction.SCROLL_DOWN -> service.scrollNode(node, down = true)
                    OptAction.SCROLL_UP -> service.scrollNode(node, down = false)
                    OptAction.TYPE, OptAction.WAIT_FOR -> {}
                }
                afterAction()
            }
            is RecEffect.PressKey -> scope.launch {
                service.globalKey(fx.key)
                afterAction()
            }
            is RecEffect.Focus -> {
                val node = nodes[fx.sig]
                typingNode = node
                node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Skip when the screen was read a moment ago and hasn't changed since.
            RecEffect.ReadScreen ->
                if (watch.dirty || watch.lastReadDone == ScreenWatch.NONE || now() - watch.lastReadDone > 500) read()
            is RecEffect.Commit -> {
                val s = _state.value?.session
                val wf = s?.let { Repo.get(it.wfId) }
                finished.value = if (s != null && wf != null && fx.steps.isNotEmpty()) {
                    val list = wf.steps.toMutableList()
                    list.addAll(s.insertAt.coerceIn(0, list.size), fx.steps)
                    Repo.upsert(wf.copy(steps = list))
                    "Added ${fx.steps.size} step${if (fx.steps.size == 1) "" else "s"} from ${s.appName}"
                } else "Nothing was recorded"
                close()
                service.bringAppToFront()
            }
            RecEffect.Abandon -> {
                close()
                finished.value = "Recording discarded"
                service.bringAppToFront()
            }
        }
    }

    /**
     * TaskPilot just did something in the app. Wait until the app has stopped changing and its new
     * screen has been read (the ticker does the reading), then report [RecEvent.ActionDone] —
     * at least 0.7 s (a tap needs a moment to show anything), at most 6 s (a page that never rests).
     */
    private suspend fun afterAction() {
        val t0 = now()
        watch = watch.event(t0)
        while (_state.value != null) {
            delay(150)
            val waited = now() - t0
            if (waited >= 6000 || (waited >= 700 && watch.settled(now()))) break
        }
        send(RecEvent.ActionDone)
    }

    // ------------------------------------------------------------------ following the screen

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(200)
                val st = _state.value ?: break
                if (st.mode != RecMode.Opening && watch.due(now(), heartbeat = st.panelOpen)) read()
            }
        }
    }

    /** Reads the options of the screen in front (off the main thread) and reports them. */
    private fun read() {
        val service = svc ?: return
        val session = _state.value?.session ?: return
        if (watch.reading) return
        watch = watch.readStarted(now())
        send(RecEvent.ReadStarted)
        scope.launch {
            val (options, map) = withContext(Dispatchers.Default) {
                try { collect(service, session) } catch (_: Exception) { emptyList<ScreenOption>() to emptyMap<String, AccessibilityNodeInfo>() }
            }
            watch = watch.readDone(now())
            if (_state.value == null) return@launch
            nodes = map
            send(RecEvent.ScreenRead(options))
        }
    }

    /** Called by the accessibility service for every screen event while the recorder is open. */
    fun onEvent(service: AutomationService, e: AccessibilityEvent) {
        val st = _state.value ?: return
        val pkg = e.packageName?.toString()
        if (pkg == service.packageName) return                       // our own bar and panel
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            -> watch = watch.event(now())                            // the app's screen is changing
        }
        if (st.mode != RecMode.Watching) return
        if (pkg == null || pkg == "com.android.systemui") return
        val src = e.source ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (src.isEditable) return                           // tapping into a box: the typing is what matters
                val label = labelOf(src)
                val size = service.screenSize()
                send(
                    RecEvent.UserTapped(
                        paramsFor(src, label, size.x, size.y) + mapOf("pkg" to pkg, "appName" to appName(service, pkg, st.session)),
                        label,
                        long = e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                    ),
                )
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val label = labelOf(src)
                val size = service.screenSize()
                val password = e.isPassword || src.isPassword
                send(
                    RecEvent.UserTyped(
                        paramsFor(src, label, size.x, size.y) + mapOf("pkg" to pkg, "appName" to appName(service, pkg, st.session)),
                        label,
                        text = if (password) "" else e.text?.joinToString("") ?: src.text?.toString() ?: "",
                        password = password,
                    ),
                )
            }
        }
    }

    private fun appName(service: AutomationService, pkg: String?, s: RecSession): String =
        if (pkg.isNullOrBlank() || pkg == s.pkg) s.appName else AppIcons.label(service, pkg) ?: pkg

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

    /** Everything that can be automated on the screen in front, top to bottom, with its live node. */
    private fun collect(service: AutomationService, session: RecSession): Pair<List<ScreenOption>, Map<String, AccessibilityNodeInfo>> {
        val size = service.screenSize()
        val screenArea = size.x.toLong() * size.y
        val own = service.packageName
        val roots = try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        } catch (_: Exception) { emptyList() }
            .filter { val p = it.packageName?.toString(); p != own && p != "com.android.systemui" }
            .ifEmpty { service.roots(null).filter { it.packageName?.toString() != own } }

        class Found(val node: AccessibilityNodeInfo, val kind: OptKind, val box: Rect)
        val found = ArrayList<Found>()
        val seen = HashSet<String>()
        var visited = 0
        fun add(n: AccessibilityNodeInfo, kind: OptKind, r: Rect) {
            if (seen.add("${kind.name}:${r.flattenToString()}")) found += Found(n, kind, Rect(r))
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
                        n.isEditable -> add(n, OptKind.FIELD, r)
                        n.isCheckable -> add(n, OptKind.TOGGLE, r)
                        clickable && !(huge && !labelled) ->
                            add(n, if (labelled || cls.contains("Button") || cls.contains("ImageView")) OptKind.BUTTON else OptKind.ITEM, r)
                    }
                    if (n.isScrollable) add(n, OptKind.SCROLL, r)
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                walk(c, depth + 1)
            }
        }
        roots.forEach { walk(it, 0) }

        val options = ArrayList<ScreenOption>()
        val map = HashMap<String, AccessibilityNodeInfo>()
        val twins = HashMap<String, Int>()
        for (f in found.sortedWith(compareBy<Found>({ it.box.top / 24 }, { it.box.left })).take(250)) {
            val n = f.node
            val label = labelOf(n)
            val idShort = n.viewIdResourceName?.substringAfter(":id/", "").orEmpty()
            val cls = n.className?.toString()?.substringAfterLast('.').orEmpty()
            val pkg = n.packageName?.toString().orEmpty()
            // Identity that survives a re-read and a scroll: what it is, not where it is.
            val base = "${f.kind.name}|$idShort|$label"
            val nth = (twins[base] ?: 0) + 1
            twins[base] = nth
            val sig = if (nth == 1) base else "$base#$nth"
            options += ScreenOption(
                sig = sig,
                kind = f.kind,
                label = label + if (f.kind == OptKind.TOGGLE) (if (n.isChecked) " (on)" else " (off)") else "",
                detail = listOf(idShort, cls).filter { it.isNotBlank() }.joinToString(" · "),
                left = f.box.left, top = f.box.top, right = f.box.right, bottom = f.box.bottom,
                params = paramsFor(n, label, size.x, size.y) + mapOf("pkg" to pkg, "appName" to appName(service, pkg, session)),
                password = n.isPassword,
            )
            map[sig] = n
        }
        return options to map
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
    }

    /** The windows are a picture of the state: the panel exists exactly while the mode is Choosing. */
    private fun syncWindows(before: RecState, after: RecState) {
        val service = svc ?: return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val o = owner ?: return
        if (after.panelOpen && panelView == null) {
            val h = (service.screenSize().y * 0.52f).toInt()
            panelView = compose(service, o) { RecorderPanel() }.also {
                try { wm.addView(it, lp(WindowManager.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM, true)) } catch (_: Exception) {}
            }
        } else if (!after.panelOpen && panelView != null) {
            try { wm.removeView(panelView) } catch (_: Exception) {}
            panelView = null
        }
        if (before.barOnTop != after.barOnTop) {
            val v = barView ?: return
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return
            p.gravity = (if (after.barOnTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
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
