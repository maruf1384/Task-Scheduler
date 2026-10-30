package com.taskpilot.app

import android.app.Application
import android.os.StrictMode
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Templates
import com.taskpilot.app.engine.Notifier
import com.taskpilot.app.engine.Scheduler
import com.taskpilot.app.engine.SecretStore

class App : Application() {
    /** Older versions kept the PIN inside the automation. Move it into the hardware-locked store. */
    private fun movePinsToSecureStore() {
        for (wf in Repo.workflows.value) {
            if (wf.steps.none { it.p.containsKey("pin") }) continue
            val steps = wf.steps.map { st ->
                val pin = st.p["pin"] ?: return@map st
                val digits = pin.filter { it.isDigit() }
                if (digits.isNotEmpty() && !SecretStore.hasPin(this)) SecretStore.savePin(this, digits.toCharArray())
                st.copy(p = (st.p - "pin") + ("usePin" to (digits.isNotEmpty()).toString()))
            }
            Repo.upsert(wf.copy(steps = steps))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Allows handing a plain file path (file://) to apps like Pydroid 3 that want a real path.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        Repo.init(this)
        movePinsToSecureStore()
        Notifier.createChannels(this)
        Templates.seedIfNeeded(this)
        Scheduler.scheduleAll(this)
    }
}
