package com.example.android_sample.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.android_sample.databinding.ActivityMainBinding
import com.example.framework.network.update.UpdateInfo
import com.example.framework.ui.base.BaseActivity
import com.example.framework.ui.ext.toast

/**
 * 示例 MainActivity
 *
 * 演示：
 * - 继承 [BaseActivity]，通过 ViewBinding 访问 View
 * - 通过 [launchWhenStarted] 安全订阅 Flow
 * - 统一处理 loading / error 状态
 * - ACRA 崩溃测试
 */
class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val viewModel: MainViewModel by viewModels()

    /** 请求通知权限（Android 13+，ACRA 崩溃通知需要） */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toast("✅ 通知权限已授权，崩溃后将弹出上报通知")
        } else {
            toast("⚠️ 通知权限被拒绝，崩溃上报通知将无法显示")
        }
    }

    override fun initViews() {
        supportActionBar?.title = "Android Framework Sample"

        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refresh()
        }

        // Android 13+ 请求通知权限（ACRA 崩溃通知需要）
        requestNotificationPermissionIfNeeded()

        // 触发崩溃：崩溃发生时立即弹出通知，点「分享日志」可导出堆栈信息
        binding.btnTriggerCrash.setOnClickListener {
            throw RuntimeException("ACRA 崩溃测试：这是一个故意触发的未捕获异常")
        }

        // 检查更新
        binding.btnCheckUpdate.setOnClickListener {
            viewModel.checkUpdate(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    override fun initObservers() {
        launchWhenStarted {
            viewModel.posts.collect { posts ->
                binding.tvContent.text = buildString {
                    appendLine("✅ 已加载 ${posts.size} 条数据")
                    appendLine()
                    posts.take(5).forEachIndexed { index, post ->
                        appendLine("${index + 1}. ${post.title}")
                    }
                    if (posts.size > 5) appendLine("... 共 ${posts.size} 条")
                }
            }
        }

        launchWhenStarted {
            viewModel.isLoading.collect { loading ->
                showLoading(loading)
            }
        }

        launchWhenStarted {
            viewModel.errorEvent.collect { message ->
                showError(message)
            }
        }

        // 检查更新：按钮 loading 态
        launchWhenStarted {
            viewModel.isCheckingUpdate.collect { checking ->
                binding.btnCheckUpdate.isEnabled = !checking
                binding.btnCheckUpdate.text = if (checking) "检查中…" else "检查更新"
            }
        }

        // 检查更新：有新版本
        launchWhenStarted {
            viewModel.updateAvailableEvent.collect { info ->
                showUpdateDialog(info)
            }
        }

        // 检查更新：无新版本 / 失败
        launchWhenStarted {
            viewModel.noUpdateEvent.collect { message ->
                toast(message)
            }
        }
    }

    override fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.swipeRefreshLayout.isRefreshing = false
    }

    override fun showError(message: String) {
        toast("请求失败：$message")
    }

    /**
     * 展示更新弹窗。
     * - 有 APK 下载链接时：「立即下载」打开浏览器下载；
     * - 无下载链接时：「查看详情」跳转 GitHub Release 页面；
     * - 强制更新时隐藏「以后再说」按钮。
     */
    private fun showUpdateDialog(info: UpdateInfo) {
        val positiveLabel = if (info.downloadUrl != null) "立即下载" else "查看详情"
        val targetUrl     = info.downloadUrl ?: info.releasePageUrl

        val dialog = AlertDialog.Builder(this)
            .setTitle("发现新版本  v${info.latestVersion}")
            .setMessage(buildString {
                appendLine("当前版本：v${info.currentVersion}")
                appendLine("最新版本：v${info.latestVersion}")
                appendLine("发布时间：${info.publishedAt.take(10)}")
                if (info.releaseNotes.isNotBlank()) {
                    appendLine()
                    appendLine("更新内容：")
                    // 简单去除 Markdown 标记，只展示前 300 字
                    appendLine(info.releaseNotes.replace(Regex("#{1,6}\\s*"), "").take(300))
                }
            })
            .setPositiveButton(positiveLabel) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            }
            .setCancelable(!info.isForceUpdate)

        if (!info.isForceUpdate) {
            dialog.setNegativeButton("以后再说", null)
        }

        dialog.show()
    }
}

