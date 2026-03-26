package com.pengjunlong.adblock.ui.main

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.viewModelScope
import com.example.framework.logger.L
import com.example.framework.network.update.UpdateChecker
import com.example.framework.network.update.UpdateInfo
import com.example.framework.ui.base.BaseViewModel
import com.pengjunlong.adblock.service.AdBlockConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用信息数据类，供应用选择器使用 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
)

/**
 * 主界面 ViewModel
 *
 * 管理：
 * - 服务开关状态
 * - 关键词列表
 * - 黑名单列表（仅名单内应用才会被处理；为空时对所有非系统应用生效）
 * - 今日统计（跳过次数、最近跳过应用）
 * - 点击延迟、Toast 开关等设置
 * - 检查更新
 */
class MainViewModel : BaseViewModel() {

    // ─── 服务开关 ──────────────────────────────────────────────────────────────

    private val _isEnabled = MutableStateFlow(AdBlockConfig.isEnabled)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        AdBlockConfig.isEnabled = enabled
        _isEnabled.value = enabled
    }

    // ─── 统计 ──────────────────────────────────────────────────────────────────

    private val _statsCount = MutableStateFlow(AdBlockConfig.getTodayCount())
    val statsCount: StateFlow<Int> = _statsCount.asStateFlow()

    private val _lastApp = MutableStateFlow(AdBlockConfig.lastBlockedApp)
    val lastApp: StateFlow<String> = _lastApp.asStateFlow()

    fun refreshStats() {
        _statsCount.value = AdBlockConfig.getTodayCount()
        _lastApp.value = AdBlockConfig.lastBlockedApp
    }

    fun resetStats() {
        AdBlockConfig.resetStats()
        refreshStats()
    }

    // ─── 关键词 ────────────────────────────────────────────────────────────────

    private val _keywords = MutableStateFlow(AdBlockConfig.getKeywords())
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    fun refreshKeywords() {
        _keywords.value = AdBlockConfig.getKeywords()
    }

    fun addKeyword(keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        if (AdBlockConfig.getKeywords().contains(trimmed)) return false
        AdBlockConfig.addKeyword(trimmed)
        refreshKeywords()
        return true
    }

    fun removeKeyword(keyword: String) {
        AdBlockConfig.removeKeyword(keyword)
        refreshKeywords()
    }

    fun resetKeywordsToDefault() {
        AdBlockConfig.resetKeywordsToDefault()
        refreshKeywords()
    }

    // ─── 黑名单 ────────────────────────────────────────────────────────────────

    private val _targetList = MutableStateFlow(AdBlockConfig.getTargetList().toList())
    val targetList: StateFlow<List<String>> = _targetList.asStateFlow()

    fun refreshTargetList() {
        _targetList.value = AdBlockConfig.getTargetList().toList()
    }

    fun addToTargetList(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        AdBlockConfig.addToTargetList(trimmed)
        refreshTargetList()
        return true
    }

    fun removeFromTargetList(packageName: String) {
        AdBlockConfig.removeFromTargetList(packageName)
        refreshTargetList()
    }

    // ─── 应用列表（供黑名单选择器使用） ───────────────────────────────────────────

    /** 已安装的用户应用列表（懒加载，首次调用 [loadInstalledApps] 后填充） */
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    /**
     * 异步加载已安装的用户应用列表（排除系统应用），按名称排序。
     * 每次调用都会重新扫描，确保列表是最新的。
     */
    fun loadInstalledApps(context: Context) {
        if (_appsLoading.value) return   // 防止并发重复加载
        viewModelScope.launch {
            _appsLoading.value = true
            _installedApps.value = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        // 只保留非系统应用（FLAG_SYSTEM 未设置），且有可启动的 Launcher Intent
                        // Android 11+ 需要 QUERY_ALL_PACKAGES 权限才能拿到完整列表
                        val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                        val hasLaunchIntent = pm.getLaunchIntentForPackage(app.packageName) != null
                        // 排除自身
                        val isSelf = app.packageName == context.packageName
                        isUserApp && hasLaunchIntent && !isSelf
                    }
                    .map { app ->
                        AppInfo(
                            packageName = app.packageName,
                            appName     = pm.getApplicationLabel(app).toString(),
                            icon        = try { pm.getApplicationIcon(app.packageName) } catch (_: Exception) { null },
                        )
                    }
                    .sortedBy { it.appName }
            }
            _appsLoading.value = false
        }
    }

    // ─── 设置 ──────────────────────────────────────────────────────────────────

    private val _clickDelay = MutableStateFlow(AdBlockConfig.clickDelayMs)
    val clickDelay: StateFlow<Long> = _clickDelay.asStateFlow()

    fun setClickDelay(ms: Long) {
        AdBlockConfig.clickDelayMs = ms
        _clickDelay.value = ms
    }

    private val _showToast = MutableStateFlow(AdBlockConfig.showToast)
    val showToast: StateFlow<Boolean> = _showToast.asStateFlow()

    fun setShowToast(show: Boolean) {
        AdBlockConfig.showToast = show
        _showToast.value = show
    }

    // ─── 检查更新 ──────────────────────────────────────────────────────────────

    private val updateChecker = UpdateChecker(
        repoOwner = "pengjunlong",
        repoName  = "AdBlock",
    )

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateAvailableEvent = MutableSharedFlow<UpdateInfo>()
    val updateAvailableEvent: SharedFlow<UpdateInfo> = _updateAvailableEvent.asSharedFlow()

    private val _noUpdateEvent = MutableSharedFlow<String>()
    val noUpdateEvent: SharedFlow<String> = _noUpdateEvent.asSharedFlow()

    fun checkUpdate(context: Context) {
        if (_isCheckingUpdate.value) return
        request(
            showLoading = false,
            block = {
                _isCheckingUpdate.value = true
                updateChecker.checkUpdate(context)
            },
            onSuccess = { info ->
                _isCheckingUpdate.value = false
                if (info.hasUpdate) {
                    L.i("MainViewModel", "发现新版本: ${info.currentVersion} → ${info.latestVersion}")
                    viewModelScope.launch { _updateAvailableEvent.emit(info) }
                } else {
                    viewModelScope.launch {
                        _noUpdateEvent.emit("当前已是最新版本（${info.currentVersion}）")
                    }
                }
            },
            onError = { error ->
                _isCheckingUpdate.value = false
                viewModelScope.launch {
                    _noUpdateEvent.emit("检查更新失败：${error.message}")
                }
            },
        )
    }
}

