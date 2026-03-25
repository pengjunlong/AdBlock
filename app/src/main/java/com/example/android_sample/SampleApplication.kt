package com.example.android_sample

import com.example.framework.core.BaseApplication
import com.example.framework.core.initializer.FrameworkInitializer
import com.example.framework.crash.CrashConfig
import com.example.framework.crash.CrashReporter
import com.example.framework.logger.L
import com.example.framework.logger.LoggerInitializer
import com.example.framework.network.NetworkConfig
import com.example.framework.network.NetworkManager
import com.example.framework.storage.StorageInitializer

/**
 * 示例 Application
 *
 * 演示如何通过 [BaseApplication] 接入框架各模块。
 */
class SampleApplication : BaseApplication() {

    override fun registerInitializers() {
        // 1. 崩溃上报（最高优先级，在 attachBaseContext 阶段初始化）
        //    崩溃后重启 App 会弹通知，点击「发送报告」调起系统分享菜单
        FrameworkInitializer.register(
            CrashReporter.initializer(
                CrashConfig.Builder()
                    .enableInDebug(BuildConfig.DEBUG) // Debug 包也启用，方便测试
                    .toastEnabled(true)               // 崩溃时 Toast 提示
                    .crashListener { _, throwable ->
                        L.e(throwable, "App crashed!")
                    }
                    .build()
            )
        )

        // 2. 日志（优先级 -100）
        FrameworkInitializer.register(LoggerInitializer())

        // 3. 存储（优先级 -80）
        FrameworkInitializer.register(StorageInitializer())

        // 4. 网络（优先级 -50）
        FrameworkInitializer.register(
            NetworkManager.initializer(
                NetworkConfig(
                    baseUrl = "https://jsonplaceholder.typicode.com/",
                    enableLogging = BuildConfig.DEBUG,
                )
            )
        )
    }

    override fun onAppCreate() {
        L.i("SampleApplication started. version=${packageName}")
    }
}

