package com.pengjunlong.adblock.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pengjunlong.adblock.service.DiagnosticOverlay.clear
import com.pengjunlong.adblock.service.DiagnosticOverlay.hide
import com.pengjunlong.adblock.service.DiagnosticOverlay.log
import com.pengjunlong.adblock.service.DiagnosticOverlay.show

/**
 * 悬浮诊断窗口（无需 adb，节点扫描结果直接显示在屏幕上）
 *
 * 使用方式：
 * - [show]  显示窗口（首次调用会创建 View）
 * - [log]   追加一行诊断信息
 * - [clear] 清空内容
 * - [hide]  隐藏并销毁窗口
 *
 * 截图功能：
 * - 悬浮窗右上角「📸」按钮，点击后截取当前屏幕
 * - 截图需要 [AdBlockService] 提供实例（Android 9+ AccessibilityService.takeScreenshot）
 * - 截图保存到 files/screenshots/<pkg>.jpg，与 RegionMarkActivity 共享
 *
 * 要求：
 * - 已授权 SYSTEM_ALERT_WINDOW（悬浮窗）权限
 * - [Settings.canDrawOverlays] 返回 true
 *
 * 全部操作均在主线程执行；内部持有 [Handler] 用于从后台线程安全投递。
 */
@SuppressLint("StaticFieldLeak")  // View 通过 WindowManager.removeView 管理生命周期，不会真正泄漏
object DiagnosticOverlay {

    private const val MAX_LINES = 40    // 最多保留多少行（防止内存无限增长）
    private const val WINDOW_ALPHA = 0.88f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null

    // AdBlockService 弱引用，用于调用 takeScreenshot
    private var serviceRef: java.lang.ref.WeakReference<AdBlockService>? = null

    private val lines = ArrayDeque<String>(MAX_LINES + 1)

    /** 注册 AdBlockService 实例（onServiceConnected 时调用） */
    fun attachService(service: AdBlockService) {
        serviceRef = java.lang.ref.WeakReference(service)
    }

    /** 取消注册（onDestroy 时调用） */
    fun detachService() {
        serviceRef = null
    }

    /** 是否已显示 */
    val isShowing: Boolean
        get() = rootView?.windowToken != null

    /**
     * 检查当前设备是否已授权悬浮窗权限。
     * Android M(23) 以下不需要动态申请，直接返回 true。
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /** 显示悬浮诊断窗口（幂等，重复调用无副作用） */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        mainHandler.post {
            if (isShowing) return@post
            if (!canDrawOverlays(context)) return@post

            val appContext = context.applicationContext

            // ── 文本滚动区 ──
            val tv = TextView(appContext).apply {
                setBackgroundColor(Color.argb((WINDOW_ALPHA * 255).toInt(), 0, 0, 0))
                setTextColor(Color.parseColor("#00FF88"))
                textSize = 10f
                setPadding(12, 8, 12, 8)
                text = "[ AdBlock 诊断模式 ]\n等待事件…"
                fontFeatureSettings = "tnum"
            }
            val sv = ScrollView(appContext).apply {
                addView(tv)
                // 留出顶部工具栏高度（约 44dp）
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).also { it.topMargin = 44.dpToPx(appContext) }
                layoutParams = lp
            }

            // ── 顶部工具栏 ──
            val toolbar = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.argb(220, 20, 20, 20))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dpToPx(appContext), 4.dpToPx(appContext),
                           8.dpToPx(appContext), 4.dpToPx(appContext))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 44.dpToPx(appContext)
                )
            }

            // 标题
            val titleTv = TextView(appContext).apply {
                text = "AdBlock 诊断"
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // 「📸 截图」按钮
            val btnScreenshot = createToolButton(appContext, "📸")
            btnScreenshot.setOnClickListener {
                takeScreenshot(appContext)
            }

            // 「🗑️ 清空」按钮
            val btnClear = createToolButton(appContext, "🗑️")
            btnClear.setOnClickListener {
                clear()
            }

            toolbar.addView(titleTv)
            toolbar.addView(btnScreenshot)
            toolbar.addView(btnClear)

            // ── 根容器 ──
            val root = FrameLayout(appContext).apply {
                addView(sv)
                addView(toolbar)
            }

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                400,                // 固定高度 400px，可看到多行
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0; y = 0
                alpha = WINDOW_ALPHA
            }

            // 允许工具栏按钮接收触摸（FLAG_NOT_FOCUSABLE 下按钮也能点击）
            root.setOnTouchListener { _, event ->
                // 透传：非工具栏区域的触摸不消费，让后面的窗口处理
                event.y > 44.dpToPx(appContext)
            }

            wm.addView(root, params)
            windowManager = wm
            rootView = root
            scrollView = sv
            textView = tv
        }
    }

    /** 追加一行诊断信息，并自动滚动到底部 */
    fun log(message: String) {
        mainHandler.post {
            lines.addLast(message)
            while (lines.size > MAX_LINES) lines.removeFirst()
            textView?.let { tv ->
                tv.text = "[ AdBlock 诊断 ]\n" + lines.joinToString("\n")
                scrollView?.post {
                    scrollView?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    /** 清空内容 */
    fun clear() {
        mainHandler.post {
            lines.clear()
            textView?.text = "[ AdBlock 诊断模式 ]\n等待事件…"
        }
    }

    /** 隐藏并销毁悬浮窗 */
    fun hide() {
        mainHandler.post {
            try {
                rootView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            windowManager = null
            rootView = null
            scrollView = null
            textView = null
            lines.clear()
        }
    }

    // ─── 截图 ──────────────────────────────────────────────────────────────────

    /**
     * 截取当前屏幕并保存。
     *
     * - Android 9+（API 28）：使用 AccessibilityService.takeScreenshot()（API 30+）；
     *   API 28-29 降级为提示用户手动截图。
     * - 保存路径：<filesDir>/screenshots/<pkg>.jpg
     * - 截图完成后将路径写入 AdBlockConfig，并弹 Toast 提示。
     */
    private fun takeScreenshot(context: Context) {
        val service = serviceRef?.get()
        if (service == null) {
            mainHandler.post {
                Toast.makeText(context, "无障碍服务未运行，无法截图", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ 使用系统截图 API
            service.requestScreenshot(context)
        } else {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Android 11 以下请手动截图后使用「从相册选图」功能",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun createToolButton(context: Context, emoji: String): TextView {
        return TextView(context).apply {
            text = emoji
            textSize = 18f
            setPadding(12.dpToPx(context), 2.dpToPx(context), 12.dpToPx(context), 2.dpToPx(context))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = 4.dpToPx(context) }
            isClickable = true
            isFocusable = true
        }
    }

    private fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()
}

