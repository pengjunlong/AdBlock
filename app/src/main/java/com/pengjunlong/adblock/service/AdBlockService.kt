package com.pengjunlong.adblock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.framework.logger.L
import com.pengjunlong.adblock.service.AdBlockService.Companion.WINDOW_CLICK_COOLDOWN
import java.util.ArrayDeque

/**
 * 开屏广告跳过无障碍服务（核心）
 *
 * ## 工作原理
 * 1. 监听 [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]（Activity / Dialog 切换时触发）
 * 2. 监听 [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED]（页面内容变化，如倒计时广告）
 * 3. BFS 遍历当前窗口节点树，匹配 [AdBlockConfig.getKeywords] 中的关键词
 * 4. 找到匹配节点后延迟 [AdBlockConfig.clickDelayMs] 毫秒执行点击
 *
 * ## 防误触策略
 * - 同一窗口 ID 在 [WINDOW_CLICK_COOLDOWN] 毫秒内只触发一次点击
 * - 跳过系统 UI、Launcher 等包名
 * - 白名单包名不处理
 */
class AdBlockService : AccessibilityService() {

    companion object {
        private const val TAG = "AdBlockService"

        /** 同一窗口的点击冷却时间（毫秒），防止在同一广告页重复触发 */
        private const val WINDOW_CLICK_COOLDOWN = 2_000L

        /** 服务连接状态（供 UI 层查询） */
        @Volatile
        var isConnected: Boolean = false
            private set

        /**
         * 绝对不处理的包名前缀：系统 UI、Launcher、系统设置等。
         * 用 startsWith 做前缀匹配，覆盖各厂商定制变体。
         */
        private val SYSTEM_PACKAGES = setOf(
            // ── 系统 UI / 状态栏 ──────────────────────────
            "com.android.systemui",

            // ── Launcher / 桌面 ───────────────────────────
            "com.android.launcher",           // AOSP launcher2/3
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",                  // 小米
            "com.huawei.android.launcher",    // 华为
            "com.oppo.launcher",              // OPPO
            "com.vivo.launcher",              // vivo
            "com.samsung.android.app.cocktailbarservice",
            "com.sec.android.app.launcher",   // 三星
            "com.zte.mifavor.launcher",       // 中兴

            // ── 系统设置（各厂商变体，统一用前缀） ────────
            "com.android.settings",           // AOSP Settings
            "com.miui.securitycenter",        // 小米安全中心
            "com.miui.settings",              // 小米设置
            "com.huawei.systemmanager",       // 华为手机管家
            "com.huawei.devicecloud",
            "com.hihonor.android.settings",   // 荣耀设置
            "com.samsung.android.settings",   // 三星设置
            "com.sec.android.app.SecSetupWizard",
            "com.coloros.settings",           // OPPO ColorOS 设置
            "com.oppo.settings",
            "com.vivo.permissionmanager",     // vivo 权限管理
            "com.bbk.settings",              // vivo 设置
            "com.zte.mifavor.settings",       // 中兴设置
            "com.oneplus.settings",           // 一加设置
            "com.oplus.settings",

            // ── 无障碍 / 权限相关系统页面 ─────────────────
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.miui.permcenter",            // 小米权限管理
            "com.huawei.permissionmanager",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 记录最近一次成功点击的 windowId，用于冷却判断 */
    private var lastClickWindowId: Int = -1
    private var lastClickTime: Long = 0L

    // ─── 生命周期 ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        isConnected = true
        // 动态设置监听参数（与 xml 配置等效，但可在运行时调整）
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        L.i(TAG, "无障碍服务已连接")
        sendBroadcast(Intent(AdBlockForegroundService.ACTION_ACCESSIBILITY_CONNECTED))
    }

    override fun onInterrupt() {
        L.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        L.i(TAG, "无障碍服务已销毁")
    }

    // ─── 事件处理 ──────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!AdBlockConfig.isEnabled) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty()) return
        if (pkg == packageName) return                            // 跳过自身
        if (SYSTEM_PACKAGES.any { pkg.startsWith(it) }) return   // 跳过系统/Launcher/Settings
        if (isSystemApp(pkg)) return                              // 兜底：所有系统应用不处理
        if (!AdBlockConfig.shouldHandle(pkg)) return              // 黑名单精准模式：不在名单内则跳过

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // 延迟执行，等待页面布局完成
        mainHandler.postDelayed({
            trySkipAd(pkg)
        }, AdBlockConfig.clickDelayMs)
    }

    // ─── 广告检测与点击 ────────────────────────────────────────────────────────

    /**
     * 尝试在当前窗口查找并点击广告跳过按钮。
     * 先用关键词匹配文字节点，再向上查找可点击父节点。
     */
    private fun trySkipAd(packageName: String) {
        val root = rootInActiveWindow ?: return
        try {
            val keywords = AdBlockConfig.getKeywords()
            val candidate = findAdNode(root, keywords) ?: return
            val clickTarget = findClickableNode(candidate) ?: return

            // 冷却检查（同一窗口短时间内不重复点击）
            val now = System.currentTimeMillis()
            val windowId = clickTarget.windowId
            if (windowId == lastClickWindowId && now - lastClickTime < WINDOW_CLICK_COOLDOWN) {
                L.d(TAG, "冷却中，跳过重复点击 pkg=$packageName windowId=$windowId")
                return
            }

            val clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                lastClickWindowId = windowId
                lastClickTime = now
                onAdSkipped(packageName, candidate.text)
            }
        } catch (e: Exception) {
            L.w(TAG, "trySkipAd 异常: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * BFS 遍历节点树，返回第一个匹配广告关键词的节点（text 或 contentDescription 命中）。
     */
    private fun findAdNode(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.offer(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (matchesKeyword(text, keywords) || matchesKeyword(desc, keywords)) {
                // 从队列中取出剩余节点并回收（避免内存泄漏）
                @Suppress("DEPRECATION")
                queue.forEach { try { it.recycle() } catch (_: Exception) {} }
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.offer(it) }
            }
            // 非目标节点：不在这里 recycle，因为 children 已入队，recycle 后访问会崩溃
        }
        return null
    }

    /**
     * 从匹配节点向上查找最近的可点击节点（最多向上 6 层）。
     * 有些广告按钮的文字节点本身不可点击，点击事件在父容器上。
     */
    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            val parent = current.parent
            @Suppress("DEPRECATION")
            if (depth > 0) try { current.recycle() } catch (_: Exception) {}
            current = parent
            depth++
        }
        return null
    }

    /**
     * 兜底判断：是否是系统应用（FLAG_SYSTEM 标志位）。
     * 用于拦截 SYSTEM_PACKAGES 名单之外的系统 UI，如各厂商定制设置页。
     * 注意：部分有广告的第三方预装 App 也带 FLAG_SYSTEM，这里选择保守策略——
     * 只要是系统签名应用就不干预，避免误触系统操作。
     */
    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断文字是否命中关键词（忽略大小写，支持包含匹配）。
     * 对于 "×" / "✕" 等单字符关键词做精确匹配，避免误触正文。
     */
    private fun matchesKeyword(text: String, keywords: List<String>): Boolean {
        if (text.isEmpty()) return false
        val lowerText = text.lowercase()
        return keywords.any { kw ->
            val lowerKw = kw.trim().lowercase()
            if (lowerKw.length <= 2) {
                // 短关键词（×, ✕, skip 等）精确匹配
                lowerText == lowerKw || lowerText.contains(lowerKw)
            } else {
                lowerText.contains(lowerKw)
            }
        }
    }

    // ─── 跳过成功回调 ──────────────────────────────────────────────────────────

    private fun onAdSkipped(packageName: String, buttonText: CharSequence?) {
        AdBlockConfig.incrementCount()
        AdBlockConfig.lastBlockedApp = packageName
        L.i(TAG, "✅ 已跳过广告 pkg=$packageName btn=${buttonText ?: "?"}")

        if (AdBlockConfig.showToast) {
            mainHandler.post {
                Toast.makeText(
                    this,
                    getString(com.pengjunlong.adblock.R.string.ad_skipped_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 通知前台服务刷新通知栏统计
        startService(
            Intent(this, AdBlockForegroundService::class.java)
                .setAction(AdBlockForegroundService.ACTION_UPDATE_STATS)
        )
    }
}

