package com.example.framework.logger

import android.app.Application
import com.example.framework.core.initializer.IInitializer
import com.example.framework.core.utils.AppUtils
import timber.log.Timber

/**
 * 日志模块初始化器
 *
 * 在 Debug 构建中种植 [Timber.DebugTree]（输出到 Logcat，含文件名/行号）。
 * 在 Release 构建中种植 [ReleaseTree]（过滤 DEBUG/VERBOSE，ERROR 上报到崩溃系统）。
 *
 * ### 接入方式
 * ```kotlin
 * // BaseApplication.registerInitializers()
 * FrameworkInitializer.register(LoggerInitializer())
 * ```
 */
class LoggerInitializer : IInitializer {

    override fun priority(): Int = -100 // 早于网络/存储，晚于崩溃上报（MIN_VALUE）

    override fun initialize(application: Application) {
        if (AppUtils.isDebug()) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        Timber.d("Logger initialized. debug=${AppUtils.isDebug()}")
    }
}

