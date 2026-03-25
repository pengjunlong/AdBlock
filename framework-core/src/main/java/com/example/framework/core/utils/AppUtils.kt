package com.example.framework.core.utils

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.framework.core.AppContext

/**
 * 应用信息工具函数
 */
object AppUtils {

    /** 版本名，如 "1.0.0" */
    fun getVersionName(): String = runCatching {
        val ctx = AppContext.context
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /** 版本号（long） */
    fun getVersionCode(): Long = runCatching {
        val ctx = AppContext.context
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(-1L)

    /** 包名 */
    fun getPackageName(): String = AppContext.context.packageName

    /** 是否为 Debug 构建 */
    fun isDebug(): Boolean =
        AppContext.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** 应用名称 */
    fun getAppName(): String = runCatching {
        val ctx = AppContext.context
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        ctx.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault("")
}

