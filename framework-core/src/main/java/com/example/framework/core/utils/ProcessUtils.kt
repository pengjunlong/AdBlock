package com.example.framework.core.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.example.framework.core.AppContext

/**
 * 进程工具函数
 */
object ProcessUtils {

    /** 获取当前进程名 */
    fun getCurrentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName()
        }
        val pid = Process.myPid()
        val am = AppContext.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: ""
    }

    /** 是否在主进程 */
    fun isMainProcess(): Boolean {
        val processName = getCurrentProcessName()
        return processName.isNotEmpty() && processName == AppContext.context.applicationInfo.packageName
    }
}

