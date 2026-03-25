package com.example.framework.crash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.framework.core.initializer.IInitializer
import com.example.framework.core.utils.AppUtils
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfigurationBuilder
import org.acra.config.ToastConfigurationBuilder
import org.acra.sender.HttpSender
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃上报管理器（基于 ACRA 5.13）
 *
 * ### 功能
 * - 自动捕获未处理的 Uncaught Exception
 * - **崩溃发生时立即弹系统通知**，点击「分享日志」调起系统分享菜单（无需重启）
 * - 支持 HTTP 上报到自建崩溃收集服务器
 * - Debug 模式下 Toast 提示崩溃信息
 * - 支持自定义 [CrashListener] 回调（写本地日志等）
 * - 支持手动上报 try-catch 捕获的非崩溃异常
 *
 * ### 接入方式
 * 在 `BaseApplication.registerInitializers()` 中注册：
 * ```kotlin
 * FrameworkInitializer.register(
 *     CrashReporter.initializer(
 *         CrashConfig.Builder()
 *             .enableInDebug(true)   // Debug 包也上报（方便测试）
 *             .toastEnabled(true)
 *             .crashListener { _, throwable -> LocalCrashLog.write(throwable) }
 *             .build()
 *     )
 * )
 * ```
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_NOTIF_CHANNEL_ID = "crash_report_channel"
    private const val CRASH_NOTIF_ID = 0x4143 // "AC"

    @Volatile
    private var config: CrashConfig? = null

    /**
     * 创建崩溃模块初始化器（priority = Int.MIN_VALUE，在 attachBaseContext 阶段执行）
     */
    fun initializer(config: CrashConfig = CrashConfig()): IInitializer =
        CrashInitializer(config)

    /**
     * 手动上报一个可捕获的异常（如 try-catch 内的异常）
     */
    fun report(throwable: Throwable) {
        if (!ACRA.isInitialised) {
            Log.w(TAG, "ACRA not initialized, skip reporting: ${throwable.message}")
            return
        }
        ACRA.errorReporter.handleException(throwable)
    }

    /**
     * 静默上报（不触发通知/Toast，适合非致命错误）
     */
    fun reportSilent(throwable: Throwable) {
        if (!ACRA.isInitialised) {
            Log.w(TAG, "ACRA not initialized, skip silent reporting: ${throwable.message}")
            return
        }
        ACRA.errorReporter.handleSilentException(throwable)
    }

    /**
     * 附加自定义键值到崩溃报告（如用户 ID、AB 实验分组等）
     */
    fun putCustomData(key: String, value: String) {
        if (!ACRA.isInitialised) return
        ACRA.errorReporter.putCustomData(key, value)
    }

    /** 获取当前配置 */
    fun getConfig(): CrashConfig? = config

    // ─── 内部：崩溃时立即发通知 ─────────────────────────────────────────────────

    /**
     * 崩溃发生时立即发通知。
     * 将堆栈信息写入缓存文件，通知的「分享日志」按钮附带该文件并调起系统分享菜单。
     */
    internal fun showCrashNotification(context: Context, throwable: Throwable, cfg: CrashConfig) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // Android 8+ 需要创建 channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CRASH_NOTIF_CHANNEL_ID,
                    cfg.notificationTitle ?: "崩溃上报",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { setSound(null, null) }
                nm.createNotificationChannel(channel)
            }

            // 把堆栈写入缓存文件，供分享使用
            val logFile = writeCrashLog(context, throwable)

            // 构建「分享日志」Intent（系统分享菜单）
            val shareIntent = buildShareIntent(context, throwable, logFile)
            val sharePending = PendingIntent.getActivity(
                context, 0, shareIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            val title = cfg.notificationTitle ?: "检测到崩溃"
            val text  = cfg.notificationText  ?: "${throwable.javaClass.simpleName}: ${throwable.message?.take(80)}"

            val notif = NotificationCompat.Builder(context, CRASH_NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_share, "分享日志", sharePending)
                .build()

            nm.notify(CRASH_NOTIF_ID, notif)
            Log.i(TAG, "Crash notification posted.")
            // 给通知 IPC 留出时间完成，再让进程退出
            Thread.sleep(300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post crash notification", e)
        }
    }

    /** 将完整堆栈信息写入 cache 目录的临时文件，返回文件 */
    private fun writeCrashLog(context: Context, throwable: Throwable): File {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "crash_$stamp.txt")
        file.writeText(buildCrashReport(context, throwable, sw.toString()))
        return file
    }

    /** 构建完整的崩溃报告文本 */
    private fun buildCrashReport(context: Context, throwable: Throwable, stackTrace: String): String {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { actManager?.getMemoryInfo(it) }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val displayMetrics = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(it)
        }
        val totalRam  = memInfo.totalMem.toMB()
        val availRam  = memInfo.availMem.toMB()
        val usedRam   = (memInfo.totalMem - memInfo.availMem).toMB()
        val runtime   = Runtime.getRuntime()
        val heapMax   = runtime.maxMemory().toMB()
        val heapUsed  = (runtime.totalMemory() - runtime.freeMemory()).toMB()
        val heapFree  = runtime.freeMemory().toMB()

        return buildString {
            appendLine("╔══════════════════════════════════════════════════╗")
            appendLine("║              CRASH REPORT                        ║")
            appendLine("╚══════════════════════════════════════════════════╝")
            appendLine()

            // ── 基础信息 ────────────────────────────────────────────────
            appendLine("▌ APP INFO")
            appendLine("  Package     : ${context.packageName}")
            appendLine("  Version     : ${AppUtils.getVersionName()} (${AppUtils.getVersionCode()})")
            appendLine("  Crash Time  : ${Date()}")
            appendLine("  Exception   : ${throwable.javaClass.name}")
            appendLine("  Message     : ${throwable.message}")
            appendLine()

            // ── 设备信息 ────────────────────────────────────────────────
            appendLine("▌ DEVICE INFO")
            appendLine("  Brand       : ${Build.BRAND}")
            appendLine("  Manufacturer: ${Build.MANUFACTURER}")
            appendLine("  Model       : ${Build.MODEL}")
            appendLine("  Device      : ${Build.DEVICE}")
            appendLine("  Product     : ${Build.PRODUCT}")
            appendLine("  Hardware    : ${Build.HARDWARE}")
            appendLine("  Board       : ${Build.BOARD}")
            appendLine()

            // ── 系统信息 ────────────────────────────────────────────────
            appendLine("▌ OS INFO")
            appendLine("  Android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("  Codename    : ${Build.VERSION.CODENAME}")
            appendLine("  Build ID    : ${Build.ID}")
            appendLine("  Fingerprint : ${Build.FINGERPRINT}")
            appendLine("  ABI         : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("  Locale      : ${Locale.getDefault()}")
            appendLine("  Timezone    : ${java.util.TimeZone.getDefault().id}")
            appendLine()

            // ── 内存信息 ────────────────────────────────────────────────
            appendLine("▌ MEMORY INFO")
            appendLine("  RAM Total   : $totalRam MB")
            appendLine("  RAM Used    : $usedRam MB")
            appendLine("  RAM Avail   : $availRam MB  ${if (memInfo.lowMemory) "[LOW MEMORY]" else ""}")
            appendLine("  Heap Max    : $heapMax MB")
            appendLine("  Heap Used   : $heapUsed MB")
            appendLine("  Heap Free   : $heapFree MB")
            appendLine()

            // ── 屏幕信息 ────────────────────────────────────────────────
            appendLine("▌ DISPLAY INFO")
            appendLine("  Resolution  : ${displayMetrics.widthPixels} x ${displayMetrics.heightPixels} px")
            appendLine("  Density     : ${displayMetrics.density}x (${displayMetrics.densityDpi} dpi)")
            appendLine()

            // ── 堆栈信息 ────────────────────────────────────────────────
            appendLine("▌ STACK TRACE")
            appendLine(stackTrace)
        }
    }

    /** Long 字节转 MB（保留一位小数） */
    private fun Long.toMB(): String = String.format(Locale.US, "%.1f", this / 1024f / 1024f)

    /** 构建调起系统分享菜单的 Intent */
    private fun buildShareIntent(context: Context, throwable: Throwable, logFile: File): Intent {
        // 用 FileProvider / content URI 分享文件
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.crash_provider",
            logFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "崩溃日志 - ${context.packageName}")
            putExtra(Intent.EXTRA_TEXT,
                "${throwable.javaClass.simpleName}: ${throwable.message}\n\n详细日志见附件。")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(shareIntent, "分享崩溃日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ─── 内部初始化器 ────────────────────────────────────────────────────────────

    private class CrashInitializer(private val cfg: CrashConfig) : IInitializer {

        // 最高优先级，在 attachBaseContext 阶段执行（BaseApplication.initCrashReporters）
        override fun priority(): Int = Int.MIN_VALUE

        override fun initialize(application: Application) {
            config = cfg

            // 构建 ACRA 配置（仅用于保存报告 & HTTP 上报，不再依赖 ACRA notification interaction）
            val builder = CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportContent(
                    ReportField.APP_VERSION_CODE,
                    ReportField.APP_VERSION_NAME,
                    ReportField.ANDROID_VERSION,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                    ReportField.CUSTOM_DATA,
                    ReportField.CRASH_CONFIGURATION,
                    ReportField.TOTAL_MEM_SIZE,
                    ReportField.AVAILABLE_MEM_SIZE,
                    ReportField.USER_APP_START_DATE,
                    ReportField.USER_CRASH_DATE,
                )

            // 配置 HTTP 上报（可选）
            if (cfg.httpReportEnabled && cfg.reportUrl != null) {
                val httpConfig = HttpSenderConfigurationBuilder()
                    .withUri(cfg.reportUrl)
                    .withHttpMethod(HttpSender.Method.POST)
                    .withEnabled(true)
                    .build()
                builder.withPluginConfigurations(httpConfig)
                Log.d(TAG, "ACRA HTTP sender enabled: ${cfg.reportUrl}")
            }

            // Debug 模式下启用 Toast 提示
            if (cfg.toastEnabled && AppUtils.isDebug()) {
                val toastConfig = ToastConfigurationBuilder()
                    .withText(cfg.toastText ?: "App crashed!")
                    .withEnabled(true)
                    .build()
                builder.withPluginConfigurations(toastConfig)
                Log.d(TAG, "ACRA Toast reporter enabled")
            }

            ACRA.init(application, builder, cfg.enableInDebug)

            // 在 ACRA.init() 之后注册我们的 handler，确保包裹在 ACRA handler 外层。
            // 崩溃时：我们先回调业务监听并发通知，再把控制权交给 ACRA（保存/上报报告）。
            val acraHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // 1. 业务回调（写本地日志等）
                runCatching { cfg.crashListener?.onCrash(thread, throwable) }
                // 2. 立即发系统通知（崩溃当下，无需等待重启）
                try {
                    showCrashNotification(application, throwable, cfg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show crash notification", e)
                }
                // 3. 交给 ACRA 保存/上报，然后系统终止进程
                acraHandler?.uncaughtException(thread, throwable)
            }

            // 附加版本信息到每条崩溃报告
            ACRA.errorReporter.run {
                putCustomData("app_version_name", AppUtils.getVersionName())
                putCustomData("app_version_code", AppUtils.getVersionCode().toString())
                putCustomData("package_name", AppUtils.getPackageName())
            }

            Log.i(TAG, "CrashReporter (ACRA) initialized. HTTP=${cfg.httpReportEnabled}, ImmediateNotification=enabled")
        }
    }
}

