package com.example.framework.core.initializer

import android.app.Application
import android.util.Log
import com.example.framework.core.initializer.FrameworkInitializer.init
import com.example.framework.core.initializer.FrameworkInitializer.initCrashReporters

/**
 * 框架统一初始化调度器
 *
 * 负责按 [IInitializer.priority] 顺序执行所有已注册的模块初始化器。
 *
 * ### 初始化分两个阶段
 * 1. **[initCrashReporters]**（在 `Application.attachBaseContext` 中调用）：
 *    仅执行优先级为 [Int.MIN_VALUE] 的初始化器（如 CrashReporter），
 *    确保在 App 重启发送崩溃报告时也能正确初始化。
 * 2. **[init]**（在 `Application.onCreate` 中调用）：
 *    执行其余所有初始化器（日志、网络、存储等）。
 *
 * ### 使用方式
 * ```kotlin
 * class MyApp : BaseApplication() {
 *     override fun registerInitializers() {
 *         FrameworkInitializer.register(CrashReporter.initializer(crashConfig))
 *         FrameworkInitializer.register(LoggerInitializer())
 *         FrameworkInitializer.register(NetworkInitializer(networkConfig))
 *         FrameworkInitializer.register(StorageInitializer())
 *     }
 *
 *     override fun onAppCreate() {
 *         // 所有框架模块已初始化完毕，在此做业务初始化
 *     }
 * }
 * ```
 */
object FrameworkInitializer {

    private const val TAG = "FrameworkInitializer"

    /** 崩溃上报初始化器的固定优先级 */
    const val CRASH_REPORTER_PRIORITY = Int.MIN_VALUE

    private val initializers = mutableListOf<IInitializer>()

    @Volatile
    private var crashReportersInitialized = false

    @Volatile
    private var initialized = false

    /**
     * 注册初始化器（必须在 [init] 调用之前注册）
     */
    fun register(initializer: IInitializer) {
        check(!initialized) {
            "Cannot register initializer after FrameworkInitializer.init() has been called."
        }
        initializers.add(initializer)
    }

    /**
     * 在 `Application.attachBaseContext` 阶段仅初始化崩溃上报模块（priority == Int.MIN_VALUE）。
     * 由 [com.example.framework.core.BaseApplication] 内部调用。
     */
    internal fun initCrashReporters(application: Application) {
        if (crashReportersInitialized) return
        initializers
            .filter { it.priority() == CRASH_REPORTER_PRIORITY }
            .forEach { runInitializer(it, application) }
        crashReportersInitialized = true
    }

    /**
     * 按优先级顺序执行其余所有已注册的初始化器（跳过已在 attachBaseContext 执行过的崩溃上报）。
     * 由 [com.example.framework.core.BaseApplication] 内部调用。
     */
    internal fun init(application: Application) {
        if (initialized) return

        initializers
            .filter { it.priority() != CRASH_REPORTER_PRIORITY }
            .sortedBy { it.priority() }
            .forEach { runInitializer(it, application) }

        initialized = true
        Log.i(TAG, "All ${initializers.size} framework module(s) initialized.")
    }

    private fun runInitializer(initializer: IInitializer, application: Application) {
        val name = initializer::class.java.simpleName
        val start = System.currentTimeMillis()
        runCatching {
            initializer.initialize(application)
        }.onSuccess {
            Log.d(TAG, "$name initialized in ${System.currentTimeMillis() - start}ms")
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize $name", e)
        }
    }

    val isInitialized: Boolean get() = initialized
}

