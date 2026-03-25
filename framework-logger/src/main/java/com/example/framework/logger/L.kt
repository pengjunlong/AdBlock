package com.example.framework.logger

import timber.log.Timber

/**
 * 日志快捷入口
 *
 * 封装 Timber，提供更简洁的调用方式，同时统一控制日志行为。
 *
 * ### 使用
 * ```kotlin
 * L.d("message")
 * L.e(exception, "Failed to load data")
 * L.tag("MyTag").i("custom tag log")
 * ```
 */
object L {

    /** VERBOSE */
    @JvmStatic fun v(message: String, vararg args: Any?) = Timber.v(message, *args)

    @JvmStatic fun v(t: Throwable, message: String, vararg args: Any?) = Timber.v(t, message, *args)

    /** DEBUG */
    @JvmStatic fun d(message: String, vararg args: Any?) = Timber.d(message, *args)

    @JvmStatic fun d(t: Throwable, message: String, vararg args: Any?) = Timber.d(t, message, *args)

    /** INFO */
    @JvmStatic fun i(message: String, vararg args: Any?) = Timber.i(message, *args)

    @JvmStatic fun i(t: Throwable, message: String, vararg args: Any?) = Timber.i(t, message, *args)

    /** WARN */
    @JvmStatic fun w(message: String, vararg args: Any?) = Timber.w(message, *args)

    @JvmStatic fun w(t: Throwable, message: String, vararg args: Any?) = Timber.w(t, message, *args)

    /** ERROR */
    @JvmStatic fun e(message: String, vararg args: Any?) = Timber.e(message, *args)

    @JvmStatic fun e(t: Throwable, message: String, vararg args: Any?) = Timber.e(t, message, *args)

    @JvmStatic fun e(t: Throwable) = Timber.e(t)

    /** 自定义 Tag */
    @JvmStatic fun tag(tag: String): Timber.Tree = Timber.tag(tag)
}

