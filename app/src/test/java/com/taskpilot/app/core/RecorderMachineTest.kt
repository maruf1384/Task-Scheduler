package com.taskpilot.app.core

import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.StepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderMachineTest {
    private val gmail = RecSession("wf1", 0, "com.google.android.gm", "Gmail")

    private fun opt(
        sig: String,
        kind: OptKind = OptKind.BUTTON,
        label: String = sig,
        password: Boolean = false,
        pkg: String = gmail.pkg,
    ) = ScreenOption(
        sig, kind, label, "", 0, 0, 100, 50,
        params = mapOf(Selector.PKG to pkg, Selector.TEXT to label, Selector.LABEL to label, "pkg" to pkg, "appName" to if (pkg == gmail.pkg) "Gmail" else pkg),
        password = password,
    )

    private val inbox = listOf(
        opt("compose", label = "Compose"),
        opt("search", OptKind.FIELD, "Search in mail"),
        opt("list", OptKind.SCROLL, "Inbox"),
        opt("row1", OptKind.ITEM, "PGCB · Daily report"),
    )

    /** Feeds events; returns the final state and every effect, in order. */
    private fun play(start: RecState, vararg events: RecEvent): Pair<RecState, List<RecEffect>> {
        var s = start
        val fx = ArrayList<RecEffect>()
        for (e in events) {
            val (n, f) = RecorderMachine.reduce(s, e)
            s = n
            fx += f
        }
        return s to fx
    }

    private fun choosing(options: List<ScreenOption> = inbox): RecState =
        play(RecorderMachine.start(gmail, addOpenStep = true), RecEvent.AppReady(true), RecEvent.ScreenRead(options)).first

    // ------------------------------------------------------------------ opening

    @Test fun `starts by opening the app, with an Open app step`() {
        val s = RecorderMachine.start(gmail, addOpenStep = true)
        assertEquals(RecMode.Opening, s.mode)
        assertEquals(listOf(StepType.LAUNCH_APP), s.steps.map { it.type })
        assertEquals("com.google.android.gm", s.steps[0].s("pkg"))
        assertFalse(s.panelOpen)
    }

    @Test fun `no Open app step when an earlier step already opened the app`() {
        assertTrue(RecorderMachine.start(gmail, addOpenStep = false).steps.isEmpty())
    }

    @Test fun `when the app is in front the options are read by themselves`() {
        val (s, fx) = play(RecorderMachine.start(gmail, true), RecEvent.AppReady(true))
        assertEquals(RecMode.Choosing(), s.mode)
        assertEquals(listOf(RecEffect.ReadScreen), fx)
        assertTrue(s.panelOpen)
    }

    @Test fun `if the app never shows up the panel still opens and says so`() {
        val (s, fx) = play(RecorderMachine.start(gmail, true), RecEvent.AppReady(false))
        assertEquals(RecMode.Choosing(), s.mode)
        assertTrue(s.message.contains("Couldn't see Gmail"))
        assertEquals(listOf(RecEffect.ReadScreen), fx)
    }

    @Test fun `the panel cannot be toggled while the app is still opening`() {
        val (s, _) = play(RecorderMachine.start(gmail, true), RecEvent.TogglePanel)
        assertEquals(RecMode.Opening, s.mode)
    }

    // ------------------------------------------------------------------ choosing → acting → next options

    @Test fun `tapping an option saves a step, taps it, and waits for the next screen`() {
        val (s, fx) = play(choosing(), RecEvent.Expand("compose"), RecEvent.Choose("compose", OptAction.TAP))
        assertEquals(RecMode.Acting, s.mode)
        assertEquals(listOf(RecEffect.Perform("compose", OptAction.TAP)), fx)
        val st = s.steps.last()
        assertEquals(StepType.TAP_ELEMENT, st.type)
        assertEquals("Tap “Compose”", st.label)
        assertEquals("Compose", st.s(Selector.TEXT))
        assertEquals("false", st.s("long"))
        assertEquals("Gmail", st.s("appName"))
        assertFalse("the panel is out of the way while TaskPilot taps", s.panelOpen)
    }

    @Test fun `when the action is done the panel comes back and the screen is read again`() {
        val (s, fx) = play(choosing(), RecEvent.Choose("compose", OptAction.TAP), RecEvent.ActionDone)
        assertEquals(RecMode.Choosing(), s.mode)
        assertEquals(RecEffect.ReadScreen, fx.last())
    }

    @Test fun `nothing else is accepted while an action is being performed`() {
        val acting = play(choosing(), RecEvent.Choose("compose", OptAction.TAP)).first
        val (s, fx) = play(
            acting,
            RecEvent.Choose("row1", OptAction.TAP), RecEvent.Key("BACK"), RecEvent.TogglePanel,
            RecEvent.Watch(true), RecEvent.AddWait, RecEvent.Undo, RecEvent.Expand("row1"),
        )
        assertEquals(RecMode.Acting, s.mode)
        assertEquals(acting.steps, s.steps)
        assertTrue(fx.isEmpty())
    }

    @Test fun `long press, scroll and wait make the right steps`() {
        var (s, fx) = play(choosing(), RecEvent.Choose("row1", OptAction.LONG_PRESS))
        assertEquals("true", s.steps.last().s("long"))
        assertEquals("Long-press “PGCB · Daily report”", s.steps.last().label)

        val r = play(choosing(), RecEvent.Choose("list", OptAction.SCROLL_DOWN))
        s = r.first; fx = r.second
        assertEquals(StepType.SCROLL, s.steps.last().type)
        assertEquals("down", s.steps.last().s("dir"))
        assertEquals(listOf(RecEffect.Perform("list", OptAction.SCROLL_DOWN)), fx)

        val w = play(choosing(), RecEvent.Choose("compose", OptAction.WAIT_FOR))
        assertEquals(StepType.WAIT_ELEMENT, w.first.steps.last().type)
        assertEquals("appear", w.first.steps.last().s("mode"))
        assertEquals("a wait only saves a step — nothing is done in the app", emptyList<RecEffect>(), w.second)
        assertEquals(RecMode.Choosing(), w.first.mode)
    }

    @Test fun `an action that does not fit the kind of option is ignored`() {
        val before = choosing()
        val (s, fx) = play(before, RecEvent.Choose("list", OptAction.TAP), RecEvent.Choose("compose", OptAction.TYPE))
        assertEquals(before.steps, s.steps)
        assertTrue(fx.isEmpty())
    }

    @Test fun `choosing an option that has left the screen does nothing but say so`() {
        val before = choosing()
        val (s, fx) = play(before, RecEvent.Choose("gone", OptAction.TAP))
        assertEquals(before.steps, s.steps)
        assertTrue(fx.isEmpty())
        assertTrue(s.message.contains("no longer"))
    }

    @Test fun `Back and Home are steps too`() {
        val (s, fx) = play(choosing(), RecEvent.Key("BACK"))
        assertEquals(StepType.SYSTEM_KEY, s.steps.last().type)
        assertEquals("BACK", s.steps.last().s("key"))
        assertEquals(listOf(RecEffect.PressKey("BACK")), fx)
        assertEquals(RecMode.Acting, s.mode)
    }

    @Test fun `Wait 2 s adds a pause without touching the app`() {
        val (s, fx) = play(choosing(), RecEvent.AddWait)
        assertEquals(StepType.DELAY, s.steps.last().type)
        assertEquals("2", s.steps.last().s("seconds"))
        assertTrue(fx.isEmpty())
    }

    @Test fun `an option of another app keeps that app's name`() {
        val dialog = listOf(opt("allow", label = "Allow", pkg = "com.android.permissioncontroller"))
        val (s, _) = play(choosing(dialog), RecEvent.Choose("allow", OptAction.TAP))
        assertEquals("com.android.permissioncontroller", s.steps.last().s("pkg"))
    }

    // ------------------------------------------------------------------ typing

    @Test fun `typing uses the app's keyboard and saves on Save text`() {
        val (typing, fx) = play(choosing(), RecEvent.Choose("search", OptAction.TYPE))
        assertTrue(typing.mode is RecMode.Typing)
        assertEquals(listOf(RecEffect.Focus("search")), fx)
        assertFalse(typing.panelOpen)

        val (s, fx2) = play(typing, RecEvent.SaveTyped("daily report", password = false))
        assertEquals(RecMode.Choosing(), s.mode)
        val st = s.steps.last()
        assertEquals(StepType.INPUT_TEXT, st.type)
        assertEquals("daily report", st.s("text"))
        assertEquals("Type in “Search in mail”", st.label)
        assertEquals(listOf(RecEffect.ReadScreen), fx2)
    }

    @Test fun `typing survives the screen changing under it (keyboard opening)`() {
        val typing = play(choosing(), RecEvent.Choose("search", OptAction.TYPE)).first
        val (s, _) = play(typing, RecEvent.ScreenRead(listOf(opt("kbd_done", label = "Done"))), RecEvent.SaveTyped("x", false))
        assertEquals("x", s.steps.last().s("text"))
        assertEquals("Search in mail", s.steps.last().s(Selector.LABEL))
    }

    @Test fun `cancelling typing saves nothing`() {
        val before = choosing()
        val (s, _) = play(before, RecEvent.Choose("search", OptAction.TYPE), RecEvent.CancelTyping)
        assertEquals(before.steps, s.steps)
        assertEquals(RecMode.Choosing(), s.mode)
    }

    @Test fun `passwords are never recorded`() {
        val login = listOf(opt("pw", OptKind.FIELD, "Password", password = true), opt("user", OptKind.FIELD, "Email"))
        val before = choosing(login)

        val (a, fxA) = play(before, RecEvent.Choose("pw", OptAction.TYPE))
        assertEquals("a password box cannot even be typed into through TaskPilot", RecMode.Choosing(), a.mode)
        assertTrue(fxA.isEmpty())

        // A box that turns out to be a password box only when it is read back.
        val (b, _) = play(before, RecEvent.Choose("user", OptAction.TYPE), RecEvent.SaveTyped("hunter2", password = true))
        assertEquals(before.steps, b.steps)

        // While "Record my taps" is on.
        val (c, _) = play(
            before, RecEvent.Watch(true),
            RecEvent.UserTyped(mapOf(), "Password", "hunter2", password = true), RecEvent.Done,
        )
        assertTrue(c.steps.none { it.s("text") == "hunter2" })
        assertEquals(before.steps.size, c.steps.size)
    }

    // ------------------------------------------------------------------ record my taps

    @Test fun `taps are only recorded while Record my taps is on`() {
        val tap = RecEvent.UserTapped(mapOf(Selector.TEXT to "Send"), "Send", long = false)
        val off = play(choosing(), tap).first
        assertEquals(1, off.steps.size)

        val (on, _) = play(choosing(), RecEvent.Watch(true), tap)
        assertEquals(RecMode.Watching, on.mode)
        assertFalse(on.panelOpen)
        assertEquals("Tap “Send”", on.steps.last().label)
        assertEquals("the recorded app is assumed when the tap doesn't say", gmail.pkg, on.steps.last().s("pkg"))
    }

    @Test fun `typing while watching becomes one step, saved when the user moves on`() {
        val box = mapOf(Selector.ID to "com.google.android.gm:id/subject")
        val (s, _) = play(
            choosing(), RecEvent.Watch(true),
            RecEvent.UserTyped(box, "Subject", "W", false),
            RecEvent.UserTyped(box, "Subject", "We", false),
            RecEvent.UserTyped(box, "Subject", "Weekly", false),
        )
        assertEquals("not a step yet", 1, s.steps.size)
        assertEquals("…but it counts on the bar", 2, s.stepCount)

        val (t, _) = play(s, RecEvent.UserTapped(mapOf(Selector.TEXT to "Send"), "Send", false))
        assertEquals(listOf(StepType.LAUNCH_APP, StepType.INPUT_TEXT, StepType.TAP_ELEMENT), t.steps.map { it.type })
        assertEquals("Weekly", t.steps[1].s("text"))
        assertNull(t.pendingText)
    }

    @Test fun `typed text is not lost when recording is switched off, the panel opened, or Done pressed`() {
        val typed = play(choosing(), RecEvent.Watch(true), RecEvent.UserTyped(mapOf(), "To", "boss@pgcb.gov.bd", false)).first
        assertEquals("boss@pgcb.gov.bd", play(typed, RecEvent.Watch(false)).first.steps.last().s("text"))
        assertEquals("boss@pgcb.gov.bd", play(typed, RecEvent.TogglePanel).first.steps.last().s("text"))
        val (_, fx) = play(typed, RecEvent.Done)
        val commit = fx.single() as RecEffect.Commit
        assertEquals("boss@pgcb.gov.bd", commit.steps.last().s("text"))
    }

    @Test fun `opening the panel stops recording taps`() {
        val (s, fx) = play(choosing(), RecEvent.Watch(true), RecEvent.TogglePanel)
        assertEquals(RecMode.Choosing(), s.mode)
        assertFalse(s.watching)
        assertEquals(listOf(RecEffect.ReadScreen), fx)
    }

    @Test fun `hiding the panel just lets the user use the app`() {
        val (s, fx) = play(choosing(), RecEvent.TogglePanel)
        assertEquals(RecMode.Peeking, s.mode)
        assertTrue(fx.isEmpty())
        val tapped = play(s, RecEvent.UserTapped(mapOf(), "Compose", false)).first
        assertEquals("nothing is recorded while peeking", s.steps, tapped.steps)
    }

    // ------------------------------------------------------------------ following the screen

    @Test fun `the same screen read again changes nothing visible`() {
        val a = choosing()
        val (b, _) = play(a, RecEvent.ReadStarted, RecEvent.ScreenRead(inbox.map { it.copy(top = it.top + 40, bottom = it.bottom + 40) }))
        assertEquals(a.screenNo, b.screenNo)
        assertFalse(b.reading)
        assertTrue(b.options.none { it.isNew })
        assertEquals("positions are refreshed", 40, b.options[0].top)
    }

    @Test fun `options that appear on the same screen are marked new`() {
        val a = choosing()
        val (b, _) = play(a, RecEvent.ScreenRead(inbox + opt("menu_settings", label = "Settings")))
        assertEquals(listOf("menu_settings"), b.options.filter { it.isNew }.map { it.sig })
        assertEquals(a.screenNo + 1, b.screenNo)
        // …and stay marked while that screen is simply re-read
        val (c, _) = play(b, RecEvent.ScreenRead(inbox + opt("menu_settings", label = "Settings")))
        assertEquals(listOf("menu_settings"), c.options.filter { it.isNew }.map { it.sig })
    }

    @Test fun `a completely different screen is a clean list`() {
        val compose = listOf(opt("to", OptKind.FIELD, "To"), opt("subject", OptKind.FIELD, "Subject"), opt("send", label = "Send"))
        val (s, _) = play(choosing(), RecEvent.ScreenRead(compose))
        assertEquals(compose.map { it.sig }, s.options.map { it.sig })
        assertTrue(s.options.none { it.isNew })
    }

    @Test fun `the first read marks nothing new`() {
        assertTrue(choosing().options.none { it.isNew })
    }

    @Test fun `an expanded option that leaves the screen is closed`() {
        val (s, _) = play(choosing(), RecEvent.Expand("row1"), RecEvent.ScreenRead(inbox.filter { it.sig != "row1" }))
        assertEquals(RecMode.Choosing(null), s.mode)
        assertNull(s.expanded)
    }

    @Test fun `an expanded option that is still there stays open`() {
        val (s, _) = play(choosing(), RecEvent.Expand("row1"), RecEvent.ScreenRead(inbox + opt("new")))
        assertEquals("row1", s.expanded?.sig)
    }

    @Test fun `the screen keeps being followed in every mode`() {
        val compose = listOf(opt("send", label = "Send"))
        for (mode in listOf<Array<RecEvent>>(arrayOf(RecEvent.TogglePanel), arrayOf(RecEvent.Watch(true)), arrayOf(RecEvent.Choose("compose", OptAction.TAP)))) {
            val (s, _) = play(play(choosing(), *mode).first, RecEvent.ScreenRead(compose))
            assertEquals(listOf("send"), s.options.map { it.sig })
        }
    }

    @Test fun `expanding something that is not on screen expands nothing`() {
        val (s, _) = play(choosing(), RecEvent.Expand("nope"))
        assertNull(s.expanded)
    }

    // ------------------------------------------------------------------ undo / discard / done

    @Test fun `undo removes the last step, or the text being typed`() {
        val two = play(choosing(), RecEvent.AddWait).first
        assertEquals(1, play(two, RecEvent.Undo).first.steps.size)
        assertEquals(0, play(two, RecEvent.Undo, RecEvent.Undo, RecEvent.Undo).first.steps.size)

        val typed = play(choosing(), RecEvent.Watch(true), RecEvent.UserTyped(mapOf(), "To", "x", false)).first
        val (u, _) = play(typed, RecEvent.Undo)
        assertNull(u.pendingText)
        assertEquals(typed.steps, u.steps)
    }

    @Test fun `discard needs two taps in a row`() {
        val (armed, fx1) = play(choosing(), RecEvent.Discard)
        assertTrue(armed.discardArmed)
        assertTrue(fx1.isEmpty())
        val (_, fx2) = play(armed, RecEvent.Discard)
        assertEquals(listOf(RecEffect.Abandon), fx2)
    }

    @Test fun `doing anything else disarms discard`() {
        // The old bug: tap Discard once, keep working for ten minutes, brush Discard → everything gone.
        for (e in listOf(RecEvent.AddWait, RecEvent.TogglePanel, RecEvent.Expand("compose"), RecEvent.Undo, RecEvent.MoveBar, RecEvent.DiscardTimeout)) {
            val (s, fx) = play(choosing(), RecEvent.AddWait, RecEvent.Discard, e, RecEvent.Discard)
            assertTrue("after ${e.tag} the next Discard only arms again", s.discardArmed)
            assertFalse(fx.contains(RecEffect.Abandon))
        }
    }

    @Test fun `the screen refreshing by itself does not disarm discard`() {
        val (_, fx) = play(choosing(), RecEvent.Discard, RecEvent.ReadStarted, RecEvent.ScreenRead(inbox), RecEvent.Discard)
        assertEquals(listOf(RecEffect.Abandon), fx)
    }

    @Test fun `the discard hint goes away when its time is up`() {
        val (s, _) = play(choosing(), RecEvent.Discard, RecEvent.DiscardTimeout)
        assertFalse(s.discardArmed)
        assertEquals("", s.message)
    }

    @Test fun `discard with nothing recorded closes at once`() {
        val (_, fx) = play(RecorderMachine.start(gmail, addOpenStep = false), RecEvent.Discard)
        assertEquals(listOf(RecEffect.Abandon), fx)
    }

    @Test fun `done hands over every step in order`() {
        val (_, fx) = play(
            choosing(),
            RecEvent.Choose("compose", OptAction.TAP), RecEvent.ActionDone,
            RecEvent.AddWait, RecEvent.Done,
        )
        val commit = fx.last() as RecEffect.Commit
        assertEquals(listOf(StepType.LAUNCH_APP, StepType.TAP_ELEMENT, StepType.DELAY), commit.steps.map { it.type })
    }

    @Test fun `the bar can be moved`() {
        val a = choosing()
        assertEquals(!a.barOnTop, play(a, RecEvent.MoveBar).first.barOnTop)
    }

    @Test fun `every option kind offers actions and the first is its main one`() {
        assertEquals(OptAction.TYPE, OptKind.FIELD.actions().first())
        assertEquals(OptAction.SCROLL_DOWN, OptKind.SCROLL.actions().first())
        assertEquals(OptAction.TAP, OptKind.BUTTON.actions().first())
        assertEquals("Switch it", OptAction.TAP.labelFor(OptKind.TOGGLE))
        assertEquals("Tap", OptAction.TAP.labelFor(OptKind.BUTTON))
        for (k in OptKind.entries) assertTrue(k.actions().isNotEmpty())
    }
}
