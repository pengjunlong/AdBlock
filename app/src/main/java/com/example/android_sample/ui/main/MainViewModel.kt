package com.example.android_sample.ui.main

import androidx.lifecycle.viewModelScope
import com.example.android_sample.data.model.Post
import com.example.android_sample.data.repository.PostRepository
import com.example.framework.crash.CrashReporter
import com.example.framework.logger.L
import com.example.framework.network.update.UpdateChecker
import com.example.framework.network.update.UpdateInfo
import com.example.framework.storage.KVStore
import com.example.framework.ui.base.BaseViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 示例 ViewModel
 *
 * 演示：
 * - 通过 [request] 发起网络请求（自动 loading / error 处理）
 * - 通过 [KVStore] 读写本地存储
 * - 通过 [CrashReporter.putCustomData] 附加用户信息到崩溃报告
 * - 通过 [UpdateChecker] 检查 GitHub Release 是否有新版本
 */
class MainViewModel : BaseViewModel() {

    private val repo = PostRepository()

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()

    // ── 检查更新 ────────────────────────────────────────────────────────────────

    /** 正在检查更新 */
    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    /** 有新版本时发送（一次性事件） */
    private val _updateAvailableEvent = MutableSharedFlow<UpdateInfo>()
    val updateAvailableEvent: SharedFlow<UpdateInfo> = _updateAvailableEvent.asSharedFlow()

    /** 检查更新结束但无新版本时发送（一次性事件，携带提示消息） */
    private val _noUpdateEvent = MutableSharedFlow<String>()
    val noUpdateEvent: SharedFlow<String> = _noUpdateEvent.asSharedFlow()

    // TODO: 替换为你自己的 GitHub 仓库信息
    private val updateChecker = UpdateChecker(
        repoOwner = "pengjunlong",
        repoName  = "android_sample",
    )

    init {
        loadPosts()
        // 示例：附加用户 ID 到崩溃报告，方便定位问题
        val userId = KVStore.getString("user_id", "anonymous")
        CrashReporter.putCustomData("user_id", userId)
    }

    fun loadPosts() = request(
        block = { repo.fetchPosts() },
        onSuccess = { posts ->
            _posts.value = posts
            // 示例：存储数据条数
            KVStore.putInt("last_posts_count", posts.size)
        },
    )

    fun refresh() = loadPosts()

    /**
     * 检查 GitHub Release 是否有新版本。
     * - 有新版本 → 发送 [updateAvailableEvent]
     * - 无新版本 → 发送 [noUpdateEvent]
     * - 请求失败 → 发送 [errorEvent]（BaseViewModel 提供）
     */
    fun checkUpdate(context: android.content.Context) {
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
                    L.i("Update available: ${info.currentVersion} → ${info.latestVersion}")
                    viewModelScope.launch { _updateAvailableEvent.emit(info) }
                } else {
                    L.i("App is up to date: ${info.currentVersion}")
                    viewModelScope.launch {
                        _noUpdateEvent.emit("当前已是最新版本（${info.currentVersion}）")
                    }
                }
            },
            onError = { error ->
                _isCheckingUpdate.value = false
                L.w("Update check failed: ${error.message}")
                viewModelScope.launch {
                    _noUpdateEvent.emit("检查更新失败：${error.message}")
                }
            }
        )
    }
}

