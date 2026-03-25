package com.example.framework.crash

/**
 * 崩溃事件监听接口
 *
 * 可用于在崩溃时写本地日志、上报自定义监控平台等。
 * **注意：回调在崩溃处理线程中执行，不要进行耗时操作。**
 */
fun interface CrashListener {
    fun onCrash(thread: Thread, throwable: Throwable)
}

