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
 * - STATE_CHANGED：同一 windowId 2s 内只触发一次点击
 * - CONTENT_CHANGED（倒计时广告）：同一 windowId 600ms 内只触发一次，保证每秒刷新的倒计时都能被响应
 * - 跳过系统 UI、Launcher 等包名
 * - 黑名单模式：黑名单非空时只处理名单内应用
 *
 * ## 倒计时广告兼容（百度地图 / 高德 / 抖音等）
 * - 按钮文字形如"跳过 3"、"跳过广告 5s"，通过关键词包含匹配可命中
 * - 按钮节点本身可能 isClickable=false，[findClickableNode] 向上查找 10 层
 * - 仍找不到可点击节点时，直接对文字节点发送 ACTION_CLICK（兜底）
 */
class AdBlockService : AccessibilityService() {

    companion object {
        private const val TAG = "AdBlockService"

        /**
         * 窗口切换事件（STATE_CHANGED）的冷却时间（毫秒）。
         * 同一 windowId 内，STATE_CHANGED 触发的点击 2s 内只执行一次。
         */
        private const val COOLDOWN_STATE_CHANGED = 2_000L

        /**
         * 内容变化事件（CONTENT_CHANGED）的冷却时间（毫秒）。
         * 倒计时广告每秒更新一次，600ms 的冷却确保每秒都能尝试点击。
         */
        private const val COOLDOWN_CONTENT_CHANGED = 600L

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

    /**
     * 分别记录两类事件的最近点击信息：
     * - [lastStateClickWindowId] / [lastStateClickTime]  对应 STATE_CHANGED
     * - [lastContentClickWindowId] / [lastContentClickTime] 对应 CONTENT_CHANGED
     *
     * 分开维护是为了让倒计时广告（CONTENT_CHANGED）使用更短的冷却时间，
     * 同时不影响普通开屏广告（STATE_CHANGED）的 2s 冷却。
     */
    private var lastStateClickWindowId: Int = -1
    private var lastStateClickTime: Long = 0L
    private var lastContentClickWindowId: Int = -1
    private var lastContentClickTime: Long = 0L

    // ─── 生命周期 ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        isConnected = true
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

        val isContentChanged = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        // 延迟执行，等待页面布局完成
        mainHandler.postDelayed({
            trySkipAd(pkg, isContentChanged)
        }, AdBlockConfig.clickDelayMs)
    }

    // ─── 广告检测与点击 ────────────────────────────────────────────────────────

    /**
     * 尝试在当前窗口查找并点击广告跳过按钮。
     *
     * @param isContentChanged 是否由 CONTENT_CHANGED 事件触发（用于选择冷却策略）
     */
    private fun trySkipAd(packageName: String, isContentChanged: Boolean) {
        val root = rootInActiveWindow ?: return
        try {
            val keywords = AdBlockConfig.getKeywords()
            val candidate = findAdNode(root, keywords) ?: return

            // 冷却检查（分别对两类事件维护独立冷却）
            val now = System.currentTimeMillis()
            val windowId = candidate.windowId
            val cooldown = if (isContentChanged) COOLDOWN_CONTENT_CHANGED else COOLDOWN_STATE_CHANGED
            val lastWindowId = if (isContentChanged) lastContentClickWindowId else lastStateClickWindowId
            val lastTime    = if (isContentChanged) lastContentClickTime     else lastStateClickTime

            if (windowId == lastWindowId && now - lastTime < cooldown) {
                L.d(TAG, "冷却中，跳过重复点击 pkg=$packageName windowId=$windowId isContent=$isContentChanged")
                return
            }

            // 查找可点击节点：向上找 10 层；若仍找不到则直接对匹配节点发 ACTION_CLICK（兜底）
            val clickTarget = findClickableNode(candidate) ?: candidate

            val clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                if (isContentChanged) {
                    lastContentClickWindowId = windowId
                    lastContentClickTime = now
                } else {
                    lastStateClickWindowId = windowId
                    lastStateClickTime = now
                }
                onAdSkipped(packageName, candidate.text)
                L.d(TAG, "点击成功 pkg=$packageName btn=${candidate.text} clickable=${clickTarget.isClickable}")
            } else {
                L.d(TAG, "点击失败（节点不可点击或已消失）pkg=$packageName btn=${candidate.text}")
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
                @Suppress("DEPRECATION")
                queue.forEach { try { it.recycle() } catch (_: Exception) {} }
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.offer(it) }
            }
        }
        return null
    }

    /**
     * 从匹配节点向上查找最近的可点击节点（最多向上 10 层）。
     * 有些广告按钮的文字节点本身不可点击，点击事件在父容器上。
     * 若 10 层内都找不到，返回 null，调用方应回退到直接点击原节点。
     */
    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 10) {
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
     *
     * 匹配规则：
     * - 长度 <= 1 的关键词（×、✕）：必须精确相等，避免误触包含该字符的正文
     * - 其他关键词：只要文字包含关键词即命中（"跳过 3" 包含 "跳过"，可以命中）
     *
     * 注意：之前对长度 <= 2 的关键词做精确匹配会导致"跳过"（2字符）无法匹配
     * "跳过 3"、"跳过广告 5s" 等带数字/后缀的倒计时文字，已改为仅对单字符符号精确匹配。
     */
    private fun matchesKeyword(text: String, keywords: List<String>): Boolean {
        if (text.isEmpty()) return false
        val lowerText = text.lowercase()
        return keywords.any { kw ->
            val lowerKw = kw.trim().lowercase()
            if (lowerKw.length <= 1) {
                // 仅单字符符号（×, ✕）精确匹配，避免误触正文
                lowerText == lowerKw
            } else {
                // 其他关键词：包含匹配（"跳过 3" 能命中 "跳过"）
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

