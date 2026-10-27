package com.taskpilot.app.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Schedule
import com.taskpilot.app.data.Workflow
import com.taskpilot.app.ui.MainActivity
import java.time.ZonedDateTime

/**
 * Uses the system alarm clock: nothing of ours runs until the exact minute you chose.
 * That is what keeps battery use near zero.
 */
object Scheduler {
    const val ACTION_RUN = "com.taskpilot.app.RUN"
    const val EXTRA_ID = "workflow_id"

    fun nextTrigger(s: Schedule, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!s.enabled || s.days.isEmpty()) return null
        for (d in 0..7) {
            val date = now.toLocalDate().plusDays(d.toLong())
            if (date.dayOfWeek.value !in s.days) continue
            val t = date.atTime(s.hour, s.minute).atZone(now.zone)
            if (t.isAfter(now)) return t
        }
        return null
    }

    fun scheduleAll(ctx: Context, justFired: Boolean = false) {
        Repo.workflows.value.forEach { schedule(ctx, it, justFired) }
    }

    fun schedule(ctx: Context, wf: Workflow, justFired: Boolean = false) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, wf.id)
        val from = if (justFired) ZonedDateTime.now().plusSeconds(61) else ZonedDateTime.now()
        val next = nextTrigger(wf.schedule, from)
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = next.toInstant().toEpochMilli()
        try {
            if (canExact(ctx)) {
                val show = PendingIntent.getActivity(
                    ctx, 7, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, id))
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RUN).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Fires at the scheduled minute and hands the job to the automation engine. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_RUN) return
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        Scheduler.scheduleAll(ctx, justFired = true)
        Runner.launch(ctx.applicationContext, id, "Scheduled")
    }
}

/** Re-arms the alarms after a reboot, an app update, or a clock/time-zone change. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleAll(ctx)
    }
}
