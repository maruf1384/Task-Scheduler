package com.taskpilot.app

import android.app.Application
import android.os.StrictMode
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Templates
import com.taskpilot.app.engine.Notifier
import com.taskpilot.app.engine.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Allows handing a plain file path (file://) to apps like Pydroid 3 that want a real path.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        Repo.init(this)
        Notifier.createChannels(this)
        Templates.seedIfNeeded(this)
        Scheduler.scheduleAll(this)
    }
}
