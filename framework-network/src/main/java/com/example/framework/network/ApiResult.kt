package com.example.framework.network

/**
 * 统一 API 请求结果封装
 *
 * 用于在 ViewModel / Repository 层安全地传递请求结果，避免使用 try-catch 散落各处。
 *
 * ### 使用示例
 * ```kotlin
 * // Repository
 * suspend fun fetchUser(id: String): ApiResult<User> = safeApiCall {
 *     apiService.getUser(id)
 * }
 *
 * // ViewModel
 * when (val result = repo.fetchUser("123")) {
 *     is ApiResult.Success -> showUser(result.data)
 *     is ApiResult.Error   -> showError(result.message)
 *     is ApiResult.Loading -> showLoading()
 * }
 * ```
 */
sealed class ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>()

    data class Error(
        val message: String,
        val code: Int = -1,
        val cause: Throwable? = null,
    ) : ApiResult<Nothing>()

    object Loading : ApiResult<Nothing>()

    // ─── 扩展属性 ─────────────────────────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun dataOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): Error? = this as? Error

    /** 成功时执行 [block] */
    inline fun onSuccess(block: (T) -> Unit): ApiResult<T> {
        if (this is Success) block(data)
        return this
    }

    /** 失败时执行 [block] */
    inline fun onError(block: (Error) -> Unit): ApiResult<T> {
        if (this is Error) block(this)
        return this
    }

    /** 加载中时执行 [block] */
    inline fun onLoading(block: () -> Unit): ApiResult<T> {
        if (this is Loading) block()
        return this
    }
}

