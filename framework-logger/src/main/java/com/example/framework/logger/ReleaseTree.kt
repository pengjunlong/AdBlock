package com.example.framework.logger

import android.util.Log
import timber.log.Timber

/**
 * Release 环境日志树
 *
 * - 过滤掉 VERBOSE / DEBUG 日志（避免敏感信息泄露）
 * - ERROR / WTF 级别日志通过 [errorReporter] 上报到崩溃平台
 *
 * 如需集成崩溃上报，注入 [errorReporter]：
 * ```kotlin
 * ReleaseTree { throwable -> CrashReporter.reportSilent(throwable) }
 * ```
 */
class ReleaseTree(
    private val errorReporter: ((Throwable) -> Unit)? = null
) : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        // Release 下只保留 INFO 及以上
        return priority >= Log.INFO
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority)) return

        // 上报 ERROR 级别异常
        if (priority == Log.ERROR && t != null) {
            errorReporter?.invoke(t)
        }

        // 仍然输出到系统日志（线上日志收集工具可以拿到）
        when (priority) {
            Log.INFO  -> Log.i(tag, message, t)
            Log.WARN  -> Log.w(tag, message, t)
            Log.ERROR -> Log.e(tag, message, t)
            Log.ASSERT -> Log.wtf(tag, message, t)
        }
    }
}

