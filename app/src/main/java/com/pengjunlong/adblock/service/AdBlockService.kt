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

    // ─── 坐标规则触发时机状态 ──────────────────────────────────────────────────
    //
    // 「开屏广告」只在 App 冷启动或长时间后台恢复后的最初几秒出现。
    // App 内部多个 Activity 跳转不应重复触发。
    //
    // 核心思路：
    //   记录该 pkg「本轮进入前台」的起始时间（fgTime），只在起始后 adWindowSec 内允许触发。
    //   fgTime 仅在「距上次点击 >= reEnterGapSec」时才重置——这才是真正的新一轮启动。
    //   App 内 Activity 跳转虽然会产生新的 STATE_CHANGED 事件，但只要距上次点击时间短，
    //   fgTime 就不会被刷新，sinceForeground 会越来越大，很快超过 adWindowSec，自然停止触发。
    //
    // 触发条件（同时满足）：
    //   ① fgTime 已记录（该 pkg 本轮进入前台后产生过 STATE_CHANGED 事件）
    //   ② 距本次进入前台的时间 <= adWindowSec（还在广告时间窗口内）
    //   ③ 距上次点击 >= reEnterGapSec（防止在同一轮内重复点击）

    /** pkg → 本轮进入前台的起始时间戳（满足 reEnterGapSec 条件时才重置） */
    private val pkgForegroundTime = mutableMapOf<String, Long>()

    /** pkg → 最近一次坐标规则点击成功的时间戳 */
    private val pkgLastRegionClickTime = mutableMapOf<String, Long>()

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
        DiagnosticOverlay.attachService(this)
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
        DiagnosticOverlay.detachService()
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

        // STATE_CHANGED 事件：记录该 pkg「本轮进入前台」的起始时间
        // 只有距上次点击 >= reEnterGapSec 才重置 fgTime，避免 App 内 Activity 跳转刷新计时
        if (!isContentChanged) {
            val now        = System.currentTimeMillis()
            val regionRule = AdBlockConfig.getRegionRule(pkg)
            val gapMs      = (regionRule?.reEnterGapSec ?: 30) * 1000L
            val lastClick  = pkgLastRegionClickTime[pkg] ?: 0L
            val lastFg     = pkgForegroundTime[pkg] ?: 0L

            if (now - lastClick >= gapMs) {
                // 距上次点击够久 → 认为是新一轮启动，重置进入前台时间
                if (lastFg == 0L || now - lastClick >= gapMs) {
                    pkgForegroundTime[pkg] = now
                    dlog("🔵 pkg=${pkg.substringAfterLast('.')} 新一轮进入前台（距上次点击 ${(now - lastClick) / 1000}s）")
                }
            } else {
                // 距上次点击太短 → App 内跳转，不重置 fgTime（让 sinceForeground 自然增大）
                dlog("⏩ pkg=${pkg.substringAfterLast('.')} Activity 跳转（距上次点击仅 ${(now - lastClick) / 1000}s，跳过重置）")
            }
        }

        mainHandler.postDelayed({
            trySkipAd(pkg, isContentChanged)
        }, AdBlockConfig.clickDelayMs)
    }

    // ─── 广告检测与点击 ────────────────────────────────────────────────────────

    private fun trySkipAd(packageName: String, isContentChanged: Boolean) {
        val root = rootInActiveWindow ?: return
        try {
            val now      = System.currentTimeMillis()
            val shortPkg = packageName.substringAfterLast('.')

            // ── 优先：坐标点击规则（用户手动标记的区域，绕过节点扫描） ─────────
            val regionRule = AdBlockConfig.getRegionRule(packageName)
            if (regionRule != null) {
                // 坐标规则只在 STATE_CHANGED（窗口切换）时触发，避免 CONTENT_CHANGED 高频重复点击
                if (!isContentChanged) {
                    val fgTime     = pkgForegroundTime[packageName] ?: 0L
                    val lastClick  = pkgLastRegionClickTime[packageName] ?: 0L
                    val windowMs   = regionRule.adWindowSec * 1000L
                    val gapMs      = regionRule.reEnterGapSec * 1000L

                    val sinceForeground = now - fgTime      // 距本轮进入前台的时长
                    val sinceLastClick  = now - lastClick   // 距上次坐标点击的时长

                    val shouldFire = fgTime > 0L                // ① 已记录本轮进入前台时间
                        && sinceForeground <= windowMs          // ② 还在广告时间窗口内
                        && sinceLastClick  >= gapMs             // ③ 距上次点击够久（防同轮重复）

                    dlog("📍 坐标规则检查 pkg=$shortPkg " +
                         "sinceLastClick=${sinceLastClick/1000}s gapReq=${regionRule.reEnterGapSec}s " +
                         "sinceFg=${sinceForeground/1000}s window=${regionRule.adWindowSec}s " +
                         "fire=$shouldFire")

                    if (shouldFire) {
                        val gestureResult = performGestureClick(regionRule.cx, regionRule.cy)
                        if (gestureResult) {
                            pkgLastRegionClickTime[packageName] = now
                            lastStateClickWindowId = Int.MAX_VALUE
                            lastStateClickTime     = now
                            onAdSkipped(packageName, "坐标(${regionRule.cx.toInt()},${regionRule.cy.toInt()})")
                            return
                        } else {
                            dlog("❌ 坐标规则手势失败 pkg=$shortPkg")
                        }
                    }
                }
                // 坐标规则存在时，跳过关键词扫描（不打印节点 dump），直接返回
                return
            }

            // ── 方案A：BFS 遍历所有节点，匹配关键词 ──────────────────────────
            val keywords  = AdBlockConfig.getKeywords()
            val candidate = findAdNode(root, keywords)

            if (candidate == null) {
                // 诊断：打印所有有文字的节点，帮助分析为何关键词未命中
                dumpVisibleTextNodes(root, packageName)
                return
            }

            // 冷却检查
            val windowId  = candidate.windowId
            val cooldown  = if (isContentChanged) COOLDOWN_CONTENT_CHANGED else COOLDOWN_STATE_CHANGED
            val lastWinId = if (isContentChanged) lastContentClickWindowId  else lastStateClickWindowId
            val lastTime  = if (isContentChanged) lastContentClickTime      else lastStateClickTime

            if (windowId == lastWinId && now - lastTime < cooldown) {
                L.d(TAG, "冷却中 pkg=$packageName winId=$windowId isContent=$isContentChanged")
                return
            }

            dlog("候选 pkg=$shortPkg " +
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
                dlog("❌ 三种点击策略均失败 pkg=$shortPkg btn='${candidate.text}'")
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

    // ─── 截图功能（供 DiagnosticOverlay 调用） ────────────────────────────────

    /**
     * 截取当前屏幕，保存为 <pkg>.jpg 到 files/screenshots/ 目录，
     * 并将路径记录到 [AdBlockConfig]。
     *
     * 需要 Android 11（API 30）及以上，使用 [takeScreenshot] API（无障碍服务内置，无需额外权限）。
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    fun requestScreenshot(context: android.content.Context) {
        val pkg = AdBlockConfig.lastBlockedApp.ifEmpty { "unknown" }
        val dir = java.io.File(context.filesDir, "screenshots").also { it.mkdirs() }
        val outFile = java.io.File(dir, "${pkg.replace('/', '_')}.jpg")

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        // ScreenshotResult.getHardwareBuffer() 返回 HardwareBuffer，
                        // 通过 Bitmap.wrapHardwareBuffer 包装，再 copy 为软件位图才能压缩保存
                        val hwBuffer = result.hardwareBuffer
                        val colorSpace = result.colorSpace
                        val hwBmp = android.graphics.Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            ?: throw IllegalStateException("wrapHardwareBuffer 返回 null")
                        hwBuffer.close()
                        val softBmp = hwBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hwBmp.recycle()
                        outFile.outputStream().use { out ->
                            softBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        softBmp.recycle()
                        AdBlockConfig.setScreenshotPath(pkg, outFile.absolutePath)
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                context,
                                "截图已保存：$pkg\n去「坐标规则」卡片点标记时会自动加载为背景",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        dlog("📸 截图成功 pkg=$pkg path=${outFile.absolutePath}")
                    } catch (e: Exception) {
                        dlog("📸 截图保存失败: ${e.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    dlog("📸 截图失败 code=$errorCode")
                    mainHandler.post {
                        android.widget.Toast.makeText(context, "截图失败（code=$errorCode）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
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

