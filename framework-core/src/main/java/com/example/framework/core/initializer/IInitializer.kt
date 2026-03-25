package com.example.framework.core.initializer

import android.app.Application

/**
 * 框架模块初始化器接口
 *
 * 每个框架子模块实现此接口，并在 [FrameworkInitializer] 中注册，
 * 由 [com.example.framework.core.BaseApplication] 统一按优先级顺序调用。
 */
interface IInitializer {

    /**
     * 执行初始化逻辑
     */
    fun initialize(application: Application)

    /**
     * 初始化优先级（值越小越先执行），默认 0
     */
    fun priority(): Int = 0
}

