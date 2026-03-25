package com.example.framework.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.framework.logger.L
import com.example.framework.network.ApiResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel 基类
 *
 * 提供：
 * - 统一的 loading 状态管理（[isLoading]）
 * - 统一的错误事件流（[errorEvent]，用 SharedFlow 发送一次性事件）
 * - 安全的协程启动 [launch]（自动捕获未处理异常并发送到 [errorEvent]）
 * - 请求封装 [request]（自动处理 Loading/Error/Success）
 *
 * ### 使用示例
 * ```kotlin
 * class UserViewModel : BaseViewModel() {
 *
 *     private val _user = MutableStateFlow<User?>(null)
 *     val user: StateFlow<User?> = _user.asStateFlow()
 *
 *     fun loadUser(id: String) = request(
 *         loading = { showLoading(true) },
 *         block = { userRepo.fetchUser(id) },
 *         onSuccess = { _user.value = it },
 *     )
 * }
 * ```
 */
abstract class BaseViewModel : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorEvent = MutableSharedFlow<String>()
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    /** 协程异常兜底处理（防止崩溃，转为 errorEvent 通知 UI）*/
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        L.e(throwable, "${this::class.java.simpleName} uncaught coroutine exception")
        launch { _errorEvent.emit(throwable.message ?: "未知错误") }
    }

    /** 在 viewModelScope 中启动协程，异常由 [exceptionHandler] 兜底 */
    protected fun launch(block: suspend CoroutineScope.() -> Unit): kotlinx.coroutines.Job =
        viewModelScope.launch(exceptionHandler, block = block)

    /** 更新 loading 状态 */
    protected fun showLoading(show: Boolean) {
        _isLoading.value = show
    }

    /**
     * 统一封装 API 请求：自动管理 loading 状态，自动处理 [ApiResult.Error]
     *
     * @param showLoading 是否展示 loading，默认 true
     * @param block       实际请求，返回 [ApiResult]
     * @param onSuccess   成功时回调
     * @param onError     失败时回调（可选，默认发送到 [errorEvent]）
     */
    protected fun <T> request(
        showLoading: Boolean = true,
        block: suspend () -> ApiResult<T>,
        onSuccess: (T) -> Unit,
        onError: ((ApiResult.Error) -> Unit)? = null,
    ) = launch {
        if (showLoading) _isLoading.value = true
        try {
            when (val result = block()) {
                is ApiResult.Success -> onSuccess(result.data)
                is ApiResult.Error   -> {
                    L.e("request error: code=${result.code}, msg=${result.message}")
                    if (onError != null) {
                        onError(result)
                    } else {
                        _errorEvent.emit(result.message)
                    }
                }
                is ApiResult.Loading -> { /* 不处理 */ }
            }
        } finally {
            if (showLoading) _isLoading.value = false
        }
    }
}

