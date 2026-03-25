package com.pengjunlong.adblock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.framework.logger.L

/**
 * 开机自启广播接收器
 *
 * 收到 [Intent.ACTION_BOOT_COMPLETED] 后自动启动 [AdBlockForegroundService]，
 * 保证设备重启后广告跳过服务依然常驻后台。
 *
 * > 注意：无障碍服务需要用户在「系统设置 → 无障碍」中手动开启，
 * >       此处仅保证前台服务在开机后运行。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        L.i("BootReceiver", "设备已启动，拉起前台服务")

        val serviceIntent = Intent(context, AdBlockForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

