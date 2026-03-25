package com.example.framework.core.utils

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * 线程调度工具
 *
 * 提供主线程 Handler 调度和常用后台线程池。
 * 如项目已引入 Coroutines，推荐直接使用 [kotlinx.coroutines.Dispatchers]。
 */
object ThreadUtils {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** IO 密集型线程池（网络/文件读写等） */
    val ioExecutor = Executors.newCachedThreadPool(namedThreadFactory("framework-io"))

    /** CPU 密集型线程池（数据处理/计算等） */
    val cpuExecutor = Executors.newFixedThreadPool(
        maxOf(2, Runtime.getRuntime().availableProcessors()),
        namedThreadFactory("framework-cpu")
    )

    /** 定时任务线程池 */
    val scheduledExecutor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2, namedThreadFactory("framework-scheduled"))

    /** 是否在主线程 */
    val isMainThread: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    /** 在主线程执行，若已在主线程则直接执行 */
    fun runOnMain(action: () -> Unit) {
        if (isMainThread) action() else mainHandler.post(action)
    }

    /** 延迟在主线程执行 */
    fun runOnMainDelayed(delayMillis: Long, action: () -> Unit) {
        mainHandler.postDelayed(action, delayMillis)
    }

    /** 在 IO 线程池执行 */
    fun runOnIo(action: () -> Unit) = ioExecutor.execute(action)

    private fun namedThreadFactory(prefix: String) = object : java.util.concurrent.ThreadFactory {
        private val count = AtomicInteger(1)
        override fun newThread(r: Runnable) = Thread(r, "$prefix-${count.getAndIncrement()}").also {
            it.isDaemon = false
            it.priority = Thread.NORM_PRIORITY
        }
    }
}

