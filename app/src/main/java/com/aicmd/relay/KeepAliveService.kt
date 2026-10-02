package com.aicmd.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 后台保活前台服务：把 App 进程标记为前台，避免被系统在内存紧张/后台整理时一分钟内杀掉。
 * 用户切到别的 App 时，进程依然存活，正在流式输出的对话不会中断。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台保活",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 AI 指令中转站后台运行，防止被系统清理"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle("AI指令中转站")
            .setContentText("正在后台运行，防止进程被清理")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1001
    }
}
