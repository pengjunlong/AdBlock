package com.example.framework.network

import okhttp3.Interceptor

/**
 * 网络模块配置
 *
 * ### 示例
 * ```kotlin
 * val config = NetworkConfig(
 *     baseUrl = "https://api.example.com/",
 *     connectTimeoutSeconds = 10,
 *     readTimeoutSeconds = 30,
 *     writeTimeoutSeconds = 30,
 *     enableLogging = BuildConfig.DEBUG,
 *     interceptors = listOf(AuthInterceptor()),
 * )
 * ```
 */
data class NetworkConfig(
    /** API 基础地址，必须以 "/" 结尾 */
    val baseUrl: String,

    /** 连接超时（秒），默认 10s */
    val connectTimeoutSeconds: Long = 10L,

    /** 读取超时（秒），默认 30s */
    val readTimeoutSeconds: Long = 30L,

    /** 写入超时（秒），默认 30s */
    val writeTimeoutSeconds: Long = 30L,

    /** 是否开启 HTTP 请求/响应日志（仅 Debug 推荐开启）*/
    val enableLogging: Boolean = false,

    /** 自定义 OkHttp 拦截器（如鉴权 Token 注入、公共参数等）*/
    val interceptors: List<Interceptor> = emptyList(),

    /** 自定义 Network 拦截器（如响应缓存、请求重试等）*/
    val networkInterceptors: List<Interceptor> = emptyList(),
)

