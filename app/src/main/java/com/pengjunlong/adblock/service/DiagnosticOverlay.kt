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
import android.widget.ScrollView
import android.widget.TextView
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
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null

    private val lines = ArrayDeque<String>(MAX_LINES + 1)

    /** 是否已显示 */
    val isShowing: Boolean
        get() = scrollView?.windowToken != null

    /**
     * 检查当前设备是否已授权悬浮窗权限。
     * Android M(23) 以下不需要动态申请，直接返回 true。
     */
    fun canDrawOverlays(context: Context): Boolean {
        // SDK_INT >= 23(M) 才需要动态申请悬浮窗权限
        return Settings.canDrawOverlays(context)
    }

    /** 显示悬浮诊断窗口（幂等，重复调用无副作用） */
    fun show(context: Context) {
        mainHandler.post {
            if (isShowing) return@post
            if (!canDrawOverlays(context)) return@post

            val appContext = context.applicationContext

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

            wm.addView(sv, params)
            windowManager = wm
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
                scrollView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            windowManager = null
            scrollView = null
            textView = null
            lines.clear()
        }
    }
}

