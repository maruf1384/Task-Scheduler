package com.taskpilot.app.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taskpilot.app.R
import com.taskpilot.app.ui.MainActivity

object Notifier {
    const val CH_RUNS = "runs"
    const val CH_ALERTS = "alerts"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNS, "Run results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A short summary after each automation finishes"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When an automation could not run"
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, channel: String, title: String, text: String, id: Int = (System.currentTimeMillis() % 100000).toInt()) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, 11,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF3D4ED8.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: Exception) {
        }
    }
}
