package com.example.framework.storage

import android.app.Application
import com.example.framework.core.initializer.IInitializer
import com.example.framework.logger.L
import com.tencent.mmkv.MMKV

/**
 * 存储模块初始化器
 *
 * 完成 MMKV 的全局初始化。
 *
 * ### 接入方式
 * ```kotlin
 * // BaseApplication.registerInitializers()
 * FrameworkInitializer.register(StorageInitializer())
 * ```
 */
class StorageInitializer : IInitializer {

    override fun priority(): Int = -80

    override fun initialize(application: Application) {
        val rootDir = MMKV.initialize(application)
        L.d("StorageInitializer: MMKV initialized at $rootDir")
    }
}

