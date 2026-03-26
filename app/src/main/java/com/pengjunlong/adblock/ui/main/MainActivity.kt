package com.pengjunlong.adblock.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.framework.network.update.UpdateInfo
import com.example.framework.ui.base.BaseActivity
import com.example.framework.ui.ext.toast
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.pengjunlong.adblock.R
import com.pengjunlong.adblock.databinding.ActivityMainBinding
import com.pengjunlong.adblock.databinding.DialogAppPickerBinding
import com.pengjunlong.adblock.service.AdBlockConfig
import com.pengjunlong.adblock.service.AdBlockForegroundService
import com.pengjunlong.adblock.service.AdBlockService
import com.pengjunlong.adblock.service.DiagnosticOverlay

/**
 * 主界面：广告跳过服务控制面板
 *
 * 功能：
 * - 显示/跳转无障碍服务开启状态
 * - 服务总开关
 * - 今日跳过统计与重置
 * - 关键词增删、恢复默认
 * - 黑名单包名增删（仅名单内应用才会被自动跳过；为空则对所有非系统应用生效）
 * - 点击延迟、Toast 开关
 * - 检查更新
 */
class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val viewModel: MainViewModel by viewModels()

    /** 监听广告跳过统计更新（由 AdBlockService 广播发来） */
    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AdBlockForegroundService.ACTION_UPDATE_STATS) {
                viewModel.refreshStats()
            }
        }
    }

    // ─── 生命周期 ──────────────────────────────────────────────────────────────

    override fun initViews() {
        supportActionBar?.title = getString(R.string.app_name)
        startForegroundService()
        setupStatusCard()
        setupStatsCard()
        setupKeywordsCard()
        setupWhitelistCard()
        setupSettingsCard()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新无障碍服务状态（用户可能刚从设置页回来）
        updateAccessibilityStatus()
        viewModel.refreshStats()
        // 刷新悬浮窗权限按钮（用户可能刚授权完回来）
        updateOverlayPermissionButton()
        // 若诊断模式开启且悬浮窗已消失（如被系统销毁），重新显示
        if (AdBlockConfig.diagnosticMode && DiagnosticOverlay.canDrawOverlays(this)) {
            DiagnosticOverlay.show(this)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AdBlockForegroundService.ACTION_UPDATE_STATS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statsReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(statsReceiver) } catch (_: Exception) {}
    }

    override fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun showError(message: String) {
        toast("错误：$message")
    }

    // ─── 卡片1：服务状态 ──────────────────────────────────────────────────────

    private fun setupStatusCard() {
        updateAccessibilityStatus()

        binding.btnOpenAccessibility.setOnClickListener {
            // 跳转到系统无障碍设置页
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.switchEnable.isChecked = AdBlockConfig.isEnabled
        binding.switchEnable.setOnCheckedChangeListener { _, checked ->
            viewModel.setEnabled(checked)
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.text = if (enabled) {
            getString(R.string.accessibility_enabled)
        } else {
            getString(R.string.accessibility_disabled)
        }
        binding.btnOpenAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // 优先通过静态字段判断（服务连接时会置 true）
        if (AdBlockService.isConnected) return true
        // fallback：通过系统 ENABLED_ACCESSIBILITY_SERVICES 字符串查询
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expectedComponent = "${packageName}/${AdBlockService::class.java.name}"
        return enabledServices.contains(expectedComponent)
    }

    // ─── 卡片2：统计 ──────────────────────────────────────────────────────────

    private fun setupStatsCard() {
        binding.btnResetStats.setOnClickListener {
            viewModel.resetStats()
            toast(getString(R.string.stats_reset_toast))
        }
    }

    // ─── 卡片3：关键词 ────────────────────────────────────────────────────────

    private fun setupKeywordsCard() {
        binding.btnAddKeyword.setOnClickListener { showAddKeywordDialog() }

        binding.btnRestoreKeywords.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_default_keywords)
                .setMessage("将清空当前所有关键词并恢复内置默认列表，确认？")
                .setPositiveButton(R.string.confirm) { _, _ ->
                    viewModel.resetKeywordsToDefault()
                    toast(getString(R.string.keywords_reset_toast))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showAddKeywordDialog() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.keyword_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.keyword_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val kw = input.text?.toString()?.trim() ?: ""
                if (kw.isEmpty()) {
                    toast(getString(R.string.keyword_empty_warn))
                    return@setPositiveButton
                }
                if (!viewModel.addKeyword(kw)) {
                    toast(getString(R.string.keyword_exists_warn))
                } else {
                    toast(getString(R.string.keyword_added_toast, kw))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun renderKeywords(keywords: List<String>) {
        binding.tvKeywordCount.text = getString(R.string.keyword_count_hint, keywords.size)
        binding.chipGroupKeywords.removeAllViews()
        keywords.forEach { kw ->
            val chip = Chip(this).apply {
                text = kw
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    viewModel.removeKeyword(kw)
                    toast(getString(R.string.keyword_deleted_toast, kw))
                }
            }
            binding.chipGroupKeywords.addView(chip)
        }
    }

    // ─── 卡片4：黑名单 ────────────────────────────────────────────────────────

    private fun setupWhitelistCard() {
        binding.btnPickApps.setOnClickListener {
            // 触发加载（首次调用才真正扫描，后续走缓存）
            viewModel.loadInstalledApps(this)
            showAppPickerDialog()
        }
    }

    /**
     * 弹出应用选择器对话框。
     * - 列表加载中显示进度提示
     * - 搜索框实时过滤
     * - 点击行切换勾选，「确定」后批量写入黑名单
     */
    private fun showAppPickerDialog() {
        val dialogBinding = DialogAppPickerBinding.inflate(layoutInflater)
        val adapter = AppPickerAdapter(
            initialSelected = viewModel.targetList.value.toSet(),
        )
        dialogBinding.rvApps.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvApps.adapter = adapter

        // 用当前已有列表初始化（可能为空，等 Flow 更新）
        adapter.setFullList(viewModel.installedApps.value)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_picker_title)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.app_picker_confirm, adapter.selectedPackages.size)) { _, _ ->
                val before = viewModel.targetList.value.toSet()
                val after  = adapter.selectedPackages.toSet()

                // 新增的
                (after - before).forEach { pkg ->
                    viewModel.addToTargetList(pkg)
                    toast(getString(R.string.blacklist_added_toast, pkg))
                }
                // 移除的
                (before - after).forEach { pkg ->
                    viewModel.removeFromTargetList(pkg)
                    toast(getString(R.string.blacklist_removed_toast, pkg))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 搜索框监听
        dialogBinding.etSearch.addTextChangedListener { editable ->
            adapter.filter(editable?.toString() ?: "")
        }

        // 监听应用列表加载完成后刷新（列表首次加载时可能还是空的）
        launchWhenStarted {
            viewModel.installedApps.collect { apps ->
                if (apps.isNotEmpty() && dialog.isShowing) {
                    adapter.setFullList(apps)
                    // 重新应用当前搜索词
                    adapter.filter(dialogBinding.etSearch.text?.toString() ?: "")
                }
            }
        }

        // 加载中：显示 loading 文字在列表区域
        launchWhenStarted {
            viewModel.appsLoading.collect { loading ->
                if (dialog.isShowing) {
                    dialogBinding.rvApps.visibility = if (loading) View.INVISIBLE else View.VISIBLE
                }
            }
        }

        dialog.show()

        // 动态更新「确定」按钮文字（选中数量变化时）
        dialogBinding.rvApps.post {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn ->
                // 每次点击 item 时刷新按钮文字
                dialogBinding.rvApps.addOnChildAttachStateChangeListener(
                    object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(view: android.view.View) {
                            view.setOnClickListener(null) // 由 adapter 处理，这里只更新按钮
                        }
                        override fun onChildViewDetachedFromWindow(view: android.view.View) {}
                    }
                )
            }
        }
    }

    private fun renderTargetList(list: List<String>) {
        binding.chipGroupWhitelist.removeAllViews()
        list.forEach { pkg ->
            val chip = Chip(this).apply {
                text = pkg
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    viewModel.removeFromTargetList(pkg)
                    toast(getString(R.string.blacklist_removed_toast, pkg))
                }
            }
            binding.chipGroupWhitelist.addView(chip)
        }
    }

    // ─── 卡片5：设置 ──────────────────────────────────────────────────────────

    private fun setupSettingsCard() {
        // 点击延迟
        binding.etClickDelay.setText(AdBlockConfig.clickDelayMs.toString())
        binding.etClickDelay.doAfterTextChanged { editable ->
            val ms = editable?.toString()?.toLongOrNull() ?: return@doAfterTextChanged
            if (ms in 0..5000) viewModel.setClickDelay(ms)
        }

        // Toast 开关
        binding.switchShowToast.isChecked = AdBlockConfig.showToast
        binding.switchShowToast.setOnCheckedChangeListener { _, checked ->
            viewModel.setShowToast(checked)
        }

        // 诊断模式开关
        binding.switchDiagnostic.isChecked = AdBlockConfig.diagnosticMode
        updateOverlayPermissionButton()
        binding.switchDiagnostic.setOnCheckedChangeListener { _, checked ->
            if (checked && !DiagnosticOverlay.canDrawOverlays(this)) {
                // 未授权：提示用户，不真正打开
                binding.switchDiagnostic.isChecked = false
                binding.btnOverlayPermission.visibility = View.VISIBLE
                toast(getString(R.string.overlay_permission_required))
                return@setOnCheckedChangeListener
            }
            AdBlockConfig.diagnosticMode = checked
            if (checked) {
                DiagnosticOverlay.show(this)
                DiagnosticOverlay.log("诊断模式已开启 ✅")
            } else {
                DiagnosticOverlay.hide()
            }
            updateOverlayPermissionButton()
        }

        // 悬浮窗权限跳转按钮
        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // 检查更新
        binding.btnCheckUpdate.setOnClickListener {
            viewModel.checkUpdate(this)
        }
    }

    /** 每次回到前台都更新授权按钮可见性 */
    private fun updateOverlayPermissionButton() {
        val needShow = !DiagnosticOverlay.canDrawOverlays(this)
        binding.btnOverlayPermission.visibility = if (needShow) View.VISIBLE else View.GONE
    }

    // ─── 数据观察 ──────────────────────────────────────────────────────────────

    override fun initObservers() {
        launchWhenStarted { viewModel.statsCount.collect { binding.tvStatsCount.text = it.toString() } }
        launchWhenStarted { viewModel.lastApp.collect { binding.tvLastApp.text = it.ifEmpty { getString(R.string.stats_none) } } }
        launchWhenStarted { viewModel.keywords.collect { renderKeywords(it) } }
        launchWhenStarted { viewModel.targetList.collect { renderTargetList(it) } }
        launchWhenStarted { viewModel.isLoading.collect { showLoading(it) } }
        launchWhenStarted { viewModel.errorEvent.collect { showError(it) } }

        launchWhenStarted {
            viewModel.isCheckingUpdate.collect { checking ->
                binding.btnCheckUpdate.isEnabled = !checking
                binding.btnCheckUpdate.text = if (checking) getString(R.string.checking_update)
                                              else getString(R.string.check_update)
            }
        }
        launchWhenStarted { viewModel.updateAvailableEvent.collect { showUpdateDialog(it) } }
        launchWhenStarted { viewModel.noUpdateEvent.collect { toast(it) } }
    }

    // ─── 更新弹窗 ──────────────────────────────────────────────────────────────

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
                    appendLine(); appendLine("更新内容：")
                    appendLine(info.releaseNotes.replace(Regex("#{1,6}\\s*"), "").take(300))
                }
            })
            .setPositiveButton(positiveLabel) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            }
            .setCancelable(!info.isForceUpdate)

        if (!info.isForceUpdate) dialog.setNegativeButton("以后再说", null)
        dialog.show()
    }

    // ─── 前台服务 ──────────────────────────────────────────────────────────────

    private fun startForegroundService() {
        val intent = Intent(this, AdBlockForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

