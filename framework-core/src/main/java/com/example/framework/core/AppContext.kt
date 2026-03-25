package com.example.framework.core

import android.app.Application
import android.content.Context

/**
 * 全局 Application 上下文持有者
 *
 * 在 [BaseApplication.onCreate] 中自动完成初始化，外部直接调用 [AppContext.application] 或
 * [AppContext.context] 获取全局上下文。
 */
object AppContext {

    private var _application: Application? = null

    /** Application 实例 */
    val application: Application
        get() = _application ?: error(
            "AppContext is not initialized. Make sure your Application extends BaseApplication."
        )

    /** ApplicationContext（attachBaseContext 阶段可直接用 application 实例） */
    val context: Context
        get() = _application ?: error(
            "AppContext is not initialized. Make sure your Application extends BaseApplication."
        )

    internal fun init(app: Application) {
        if (_application == null) {
            _application = app
        }
    }
}

