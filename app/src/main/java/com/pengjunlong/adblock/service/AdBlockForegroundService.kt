package com.pengjunlong.adblock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.framework.logger.L
import com.pengjunlong.adblock.R
import com.pengjunlong.adblock.ui.main.MainActivity

/**
 * 常驻前台服务
 *
 * 职责：
 * 1. 保持进程不被系统回收（通过前台通知）
 * 2. 监听无障碍服务连接状态，刷新通知栏内容
 * 3. 统计今日已跳过广告次数并展示在通知上
 */
class AdBlockForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        private const val NOTIFICATION_ID = 9527
        private const val CHANNEL_ID = "adblock_channel"

        /** 无障碍服务已连接广播 */
        const val ACTION_ACCESSIBILITY_CONNECTED = "com.pengjunlong.adblock.ACCESSIBILITY_CONNECTED"

        /** 统计更新广播（跳过一次广告后发送） */
        const val ACTION_UPDATE_STATS = "com.pengjunlong.adblock.UPDATE_STATS"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ACCESSIBILITY_CONNECTED -> updateNotification()
                ACTION_UPDATE_STATS            -> updateNotification()
            }
        }
    }

    // ─── 生命周期 ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceiver()
        L.i(TAG, "前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_STATS -> updateNotification()
        }
        // 被杀死后自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        L.i(TAG, "前台服务已销毁")
    }

    // ─── 通知 ──────────────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val count = AdBlockConfig.getTodayCount()
        val contentText = if (count > 0) {
            getString(R.string.notification_content_blocked, count)
        } else {
            getString(R.string.notification_content)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    // ─── 广播注册 ──────────────────────────────────────────────────────────────

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ACCESSIBILITY_CONNECTED)
            addAction(ACTION_UPDATE_STATS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }
}

