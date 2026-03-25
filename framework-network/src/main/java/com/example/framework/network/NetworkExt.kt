package com.example.framework.network

import com.example.framework.logger.L
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 网络请求安全调用扩展
 *
 * 将 Retrofit suspend 函数调用包装为 [ApiResult]，统一处理网络异常。
 *
 * ### 使用示例
 * ```kotlin
 * class UserRepository {
 *     suspend fun fetchUser(id: String): ApiResult<User> = safeApiCall {
 *         NetworkManager.createService(UserApiService::class.java).getUser(id)
 *     }
 * }
 * ```
 */
suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        val message = "HTTP ${e.code()}: ${e.message()}"
        L.e(e, "safeApiCall HttpException: $message")
        ApiResult.Error(message = message, code = e.code(), cause = e)
    } catch (e: UnknownHostException) {
        L.e(e, "safeApiCall: No network or DNS error")
        ApiResult.Error(message = "无网络连接，请检查网络设置", cause = e)
    } catch (e: ConnectException) {
        L.e(e, "safeApiCall: Connection refused")
        ApiResult.Error(message = "连接服务器失败，请稍后重试", cause = e)
    } catch (e: SocketTimeoutException) {
        L.e(e, "safeApiCall: Request timed out")
        ApiResult.Error(message = "请求超时，请稍后重试", cause = e)
    } catch (e: Exception) {
        L.e(e, "safeApiCall: Unexpected error")
        ApiResult.Error(message = e.message ?: "未知错误", cause = e)
    }
}

