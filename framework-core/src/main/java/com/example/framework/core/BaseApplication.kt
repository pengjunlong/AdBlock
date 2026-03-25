package com.example.framework.core

import android.app.Application
import android.content.Context
import com.example.framework.core.initializer.FrameworkInitializer

/**
 * Application 基类
 *
 * 所有接入此框架的项目 Application 均应继承此类。继承后：
 * 1. 重写 [registerInitializers] 注册各框架模块初始化器（如日志、崩溃上报、网络等）
 * 2. 重写 [onAppCreate] 编写业务层自定义初始化逻辑
 *
 * ### 初始化时机说明
 * - [attachBaseContext]：ACRA 等崩溃监控模块在此阶段初始化，确保 App 重启发送报告时也能正确捕获
 * - [onCreate]：其余模块（日志、网络、存储等）在此阶段初始化
 *
 * ### 示例
 * ```kotlin
 * class MyApp : BaseApplication() {
 *
 *     override fun registerInitializers() {
 *         FrameworkInitializer.register(
 *             CrashReporter.initializer(CrashConfig.Builder().mailTo("dev@example.com").build())
 *         )
 *         FrameworkInitializer.register(LoggerInitializer())
 *         FrameworkInitializer.register(StorageInitializer())
 *     }
 *
 *     override fun onAppCreate() {
 *         // 框架模块已全部就绪，在此做业务初始化
 *     }
 * }
 * ```
 */
abstract class BaseApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 全局 Context 必须最先初始化，后续模块（如 CrashReporter）会依赖它
        AppContext.init(this)
        // 注册初始化器（只注册一次）
        registerInitializers()
        // ACRA 要求在 attachBaseContext 中初始化，以便 App 重启发送报告时也能正确运行
        FrameworkInitializer.initCrashReporters(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 执行其余模块的初始化（日志、存储、网络等）
        FrameworkInitializer.init(this)
        // 业务层自定义初始化
        onAppCreate()
    }

    /**
     * 在此方法中注册框架各模块的 [com.example.framework.core.initializer.IInitializer]。
     * 在 [attachBaseContext] 中被调用一次，子类请勿手动调用。
     */
    protected open fun registerInitializers() {}

    /**
     * 框架所有模块初始化完毕后回调，子类在此执行业务层初始化。
     */
    protected abstract fun onAppCreate()
}

