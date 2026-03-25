package com.example.framework.network

import android.app.Application
import com.example.framework.core.initializer.IInitializer
import com.example.framework.logger.L
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络管理器
 *
 * 封装 OkHttp + Retrofit，提供统一的网络访问入口和 Service 创建。
 *
 * ### 接入方式
 * ```kotlin
 * // BaseApplication.registerInitializers()
 * FrameworkInitializer.register(
 *     NetworkManager.initializer(
 *         NetworkConfig(baseUrl = "https://api.example.com/", enableLogging = BuildConfig.DEBUG)
 *     )
 * )
 *
 * // 创建 API Service
 * val userApi = NetworkManager.createService(UserApiService::class.java)
 * ```
 */
object NetworkManager {

    private const val TAG = "NetworkManager"

    private var _okHttpClient: OkHttpClient? = null
    private var _retrofit: Retrofit? = null
    private var _config: NetworkConfig? = null

    val okHttpClient: OkHttpClient
        get() = _okHttpClient ?: error("NetworkManager is not initialized.")

    val retrofit: Retrofit
        get() = _retrofit ?: error("NetworkManager is not initialized.")

    val config: NetworkConfig
        get() = _config ?: error("NetworkManager is not initialized.")

    /**
     * 创建框架初始化器
     */
    fun initializer(config: NetworkConfig): IInitializer = NetworkInitializer(config)

    /**
     * 创建 Retrofit API Service 实例（线程安全，可缓存）
     */
    fun <T> createService(serviceClass: Class<T>): T = retrofit.create(serviceClass)

    // ─── 内部初始化器 ────────────────────────────────────────────────────────────

    private class NetworkInitializer(private val cfg: NetworkConfig) : IInitializer {

        override fun priority(): Int = -50

        override fun initialize(application: Application) {
            _config = cfg

            // 构建 OkHttpClient
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(cfg.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(cfg.readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(cfg.writeTimeoutSeconds, TimeUnit.SECONDS)

            // 添加应用层拦截器
            cfg.interceptors.forEach { clientBuilder.addInterceptor(it) }

            // 添加网络层拦截器
            cfg.networkInterceptors.forEach { clientBuilder.addNetworkInterceptor(it) }

            // 日志拦截器（放在最后，打印最终请求）
            if (cfg.enableLogging) {
                val loggingInterceptor = HttpLoggingInterceptor { message ->
                    L.tag("OkHttp").d(message)
                }.apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
                clientBuilder.addInterceptor(loggingInterceptor)
            }

            _okHttpClient = clientBuilder.build()

            // 构建 Retrofit
            _retrofit = Retrofit.Builder()
                .baseUrl(cfg.baseUrl)
                .client(_okHttpClient!!)
                .addConverterFactory(
                    GsonConverterFactory.create(
                        GsonBuilder()
                            .setLenient()
                            .serializeNulls()
                            .create()
                    )
                )
                .build()

            L.i("$TAG initialized. baseUrl=${cfg.baseUrl}")
        }
    }
}

