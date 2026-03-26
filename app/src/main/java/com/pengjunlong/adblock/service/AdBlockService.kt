package com.pengjunlong.adblock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.framework.logger.L
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 开屏广告跳过无障碍服务（核心）
 *
 * ## 工作原理
 * 1. 监听 TYPE_WINDOW_STATE_CHANGED（Activity/Dialog 切换）
 * 2. 监听 TYPE_WINDOW_CONTENT_CHANGED（页面内容变化，如倒计时广告）
 * 3. BFS 遍历当前窗口节点树，匹配关键词
 * 4. 找到匹配节点后延迟 clickDelayMs 毫秒，按优先级依次尝试点击：
 *    a. 节点本身可点击 → 直接点击
 *    b. 向上找 10 层可点击父节点 → 点击父节点
 *    c. 通过节点的屏幕坐标执行手势点击（GestureDescription）→ 兜底
 *
 * ## 防误触策略
 * - STATE_CHANGED：同一 windowId 2s 内只触发一次
 * - CONTENT_CHANGED（倒计时）：同一 windowId 600ms 内只触发一次
 * - 跳过系统 UI / Launcher / Settings 包名
 * - FLAG_SYSTEM 系统应用兜底过滤
 * - 黑名单精准模式：黑名单非空时只处理名单内应用
 */
class AdBlockService : AccessibilityService() {

    companion object {
        private const val TAG = "AdBlockService"

        private const val COOLDOWN_STATE_CHANGED   = 2_000L
        private const val COOLDOWN_CONTENT_CHANGED =   600L

        private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        @Volatile
        var isConnected: Boolean = false
            private set

        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.samsung.android.app.cocktailbarservice",
            "com.sec.android.app.launcher",
            "com.zte.mifavor.launcher",
            "com.android.settings",
            "com.miui.securitycenter",
            "com.miui.settings",
            "com.huawei.systemmanager",
            "com.huawei.devicecloud",
            "com.hihonor.android.settings",
            "com.samsung.android.settings",
            "com.sec.android.app.SecSetupWizard",
            "com.coloros.settings",
            "com.oppo.settings",
            "com.vivo.permissionmanager",
            "com.bbk.settings",
            "com.zte.mifavor.settings",
            "com.oneplus.settings",
            "com.oplus.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.miui.permcenter",
            "com.huawei.permissionmanager",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastStateClickWindowId:   Int  = -1
    private var lastStateClickTime:       Long = 0L
    private var lastContentClickWindowId: Int  = -1
    private var lastContentClickTime:     Long = 0L

    // ─── 诊断辅助 ──────────────────────────────────────────────────────────────

    /**
     * 同时输出到 Logcat（INFO）和屏幕悬浮窗（若诊断模式开启）。
     */
    private fun dlog(msg: String) {
        L.i(TAG, msg)
        if (AdBlockConfig.diagnosticMode) {
            val ts = timeFmt.format(Date())
            DiagnosticOverlay.log("[$ts] $msg")
        }
    }

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
        if (pkg == packageName) return
        if (SYSTEM_PACKAGES.any { pkg.startsWith(it) }) return
        if (isSystemApp(pkg)) return
        if (!AdBlockConfig.shouldHandle(pkg)) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val isContentChanged = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        mainHandler.postDelayed({
            trySkipAd(pkg, isContentChanged)
        }, AdBlockConfig.clickDelayMs)
    }

    // ─── 广告检测与点击 ────────────────────────────────────────────────────────

    private fun trySkipAd(packageName: String, isContentChanged: Boolean) {
        val root = rootInActiveWindow ?: return
        try {
            val keywords = AdBlockConfig.getKeywords()

            // ── 方案A：BFS 遍历所有节点，匹配关键词 ──────────────────────────
            val candidate = findAdNode(root, keywords)

            if (candidate == null) {
                // 诊断：打印所有有文字的节点，帮助分析为何关键词未命中
                dumpVisibleTextNodes(root, packageName)
                return
            }

            // 冷却检查
            val now      = System.currentTimeMillis()
            val windowId = candidate.windowId
            val cooldown    = if (isContentChanged) COOLDOWN_CONTENT_CHANGED else COOLDOWN_STATE_CHANGED
            val lastWinId   = if (isContentChanged) lastContentClickWindowId  else lastStateClickWindowId
            val lastTime    = if (isContentChanged) lastContentClickTime      else lastStateClickTime

            if (windowId == lastWinId && now - lastTime < cooldown) {
                L.d(TAG, "冷却中 pkg=$packageName winId=$windowId isContent=$isContentChanged")
                return
            }

            dlog("候选 pkg=${packageName.substringAfterLast('.')} " +
                "text='${candidate.text}' desc='${candidate.contentDescription}' " +
                "clickable=${candidate.isClickable} class=${candidate.className?.toString()?.substringAfterLast('.')}")

            // ── 依次尝试三种点击策略 ──────────────────────────────────────────
            val clicked = tryClick(candidate, packageName)

            if (clicked) {
                if (isContentChanged) {
                    lastContentClickWindowId = windowId
                    lastContentClickTime     = now
                } else {
                    lastStateClickWindowId = windowId
                    lastStateClickTime     = now
                }
                onAdSkipped(packageName, candidate.text)
            } else {
                dlog("❌ 三种点击策略均失败 pkg=${packageName.substringAfterLast('.')} btn='${candidate.text}'")
            }

        } catch (e: Exception) {
            dlog("⚠️ trySkipAd 异常: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 三级点击策略：
     * 1. 节点本身或向上 10 层的可点击父节点 → ACTION_CLICK
     * 2. 通过节点屏幕坐标执行手势点击（dispatchGesture）→ 兜底，适用于不暴露可点击节点的自定义 View
     */
    private fun tryClick(node: AccessibilityNodeInfo, packageName: String): Boolean {
        val shortPkg = packageName.substringAfterLast('.')
        // 策略1：找可点击节点执行 ACTION_CLICK
        val clickableNode = findClickableNode(node)
        if (clickableNode != null) {
            val result = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            dlog("策略1 ACTION_CLICK=$result pkg=$shortPkg")
            if (result) return true
        } else {
            // 节点本身 isClickable=false 且无可点击父节点，也尝试一次 ACTION_CLICK（部分 App 即使 isClickable=false 也能响应）
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            dlog("策略1(兜底原节点) ACTION_CLICK=$result pkg=$shortPkg")
            if (result) return true
        }

        // 策略2：坐标手势点击
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            dlog("策略2 手势(${cx.toInt()},${cy.toInt()}) pkg=$shortPkg")
            val gestureResult = performGestureClick(cx, cy)
            if (gestureResult) return true
        } else {
            dlog("策略2 跳过：边界为空 pkg=$shortPkg")
        }

        return false
    }

    /**
     * 通过 GestureDescription 在指定坐标执行点击手势。
     * 适用于自定义 View 不暴露 AccessibilityNodeInfo 可点击属性的情况（如百度地图开屏跳过按钮）。
     */
    private fun performGestureClick(x: Float, y: Float): Boolean {
        return try {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 1)
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            // dispatchGesture 是异步的，这里用同步标志等待结果
            var success = false
            val latch = java.util.concurrent.CountDownLatch(1)
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                    success = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                    latch.countDown()
                }
            }, null)
            latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            success
        } catch (e: Exception) {
            L.w(TAG, "手势点击异常: ${e.message}")
            false
        }
    }

    /**
     * BFS 遍历节点树，返回第一个匹配广告关键词的节点。
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
     * 向上查找最近的可点击父节点（最多 10 层），找不到返回 null。
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
     * 诊断辅助：当关键词匹配失败时，打印当前窗口所有有文字内容的节点，
     * 帮助分析按钮的真实文字是什么、是否被无障碍服务识别到。
     * 始终输出 Logcat（I 级别）；诊断模式下同时显示在悬浮窗。
     */
    private fun dumpVisibleTextNodes(root: AccessibilityNodeInfo, pkg: String) {
        val shortPkg = pkg.substringAfterLast('.')
        val sb = StringBuilder("[$shortPkg] 未命中关键词，节点列表:\n")
        val overlayLines = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.offer(root)
        var count = 0
        while (queue.isNotEmpty() && count < 60) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val clz = node.className?.toString()?.substringAfterLast('.') ?: "?"
                val line = "  t='$text' d='$desc' c=${node.isClickable} cls=$clz b=$bounds"
                sb.append(line).append('\n')
                // 悬浮窗显示精简版（节省空间）
                val display = if (text.isNotEmpty()) "'$text'" else "desc:'$desc'"
                overlayLines.add("$display [${clz}] clk=${node.isClickable}")
                count++
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.offer(it) }
            }
        }
        L.i(TAG, sb.toString())

        if (AdBlockConfig.diagnosticMode) {
            val ts = timeFmt.format(Date())
            DiagnosticOverlay.log("[$ts] ⚠️$shortPkg 未命中($count 节点):")
            overlayLines.take(12).forEach { DiagnosticOverlay.log("  $it") }
            if (overlayLines.size > 12) {
                DiagnosticOverlay.log("  … 还有 ${overlayLines.size - 12} 个节点")
            }
        }
    }

    /**
     * 兜底：通过 FLAG_SYSTEM 标志位判断是否为系统应用。
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
     * 关键词匹配：
     * - 单字符符号（×、✕）精确匹配，避免误触正文
     * - 其他关键词包含匹配（"跳过 3" 能命中 "跳过"）
     */
    private fun matchesKeyword(text: String, keywords: List<String>): Boolean {
        if (text.isEmpty()) return false
        val lowerText = text.lowercase()
        return keywords.any { kw ->
            val lowerKw = kw.trim().lowercase()
            if (lowerKw.length <= 1) {
                lowerText == lowerKw
            } else {
                lowerText.contains(lowerKw)
            }
        }
    }

    // ─── 跳过成功回调 ──────────────────────────────────────────────────────────

    private fun onAdSkipped(packageName: String, buttonText: CharSequence?) {
        AdBlockConfig.incrementCount()
        AdBlockConfig.lastBlockedApp = packageName
        val shortPkg = packageName.substringAfterLast('.')
        dlog("✅ 已跳过广告 pkg=$shortPkg btn='${buttonText ?: "?"}'  共${AdBlockConfig.getTodayCount()}次")

        if (AdBlockConfig.showToast) {
            mainHandler.post {
                Toast.makeText(
                    this,
                    getString(com.pengjunlong.adblock.R.string.ad_skipped_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        startService(
            Intent(this, AdBlockForegroundService::class.java)
                .setAction(AdBlockForegroundService.ACTION_UPDATE_STATS)
        )
    }
}

