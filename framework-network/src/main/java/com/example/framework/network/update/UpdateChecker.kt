package com.example.framework.network.update

import android.content.Context
import com.example.framework.core.utils.AppUtils
import com.example.framework.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 版本检查器
 *
 * 通过 GitHub REST API 获取最新 Release 信息，与当前安装版本比较，
 * 判断是否有新版本可用。
 *
 * ### 接入示例
 * ```kotlin
 * // 在 ViewModel 中
 * viewModelScope.launch {
 *     val checker = UpdateChecker(
 *         repoOwner = "your-org",
 *         repoName  = "your-app",
 *     )
 *     when (val result = checker.checkUpdate(context)) {
 *         is ApiResult.Success -> {
 *             val info = result.data
 *             if (info.hasUpdate) showUpdateDialog(info)
 *         }
 *         is ApiResult.Error -> L.w("Update check failed: ${result.message}")
 *         else -> Unit
 *     }
 * }
 * ```
 *
 * @param repoOwner    GitHub 用户名或组织名，如 "google"
 * @param repoName     仓库名，如 "accompanist"
 * @param includePreRelease 是否包含预发布版本（默认 false）
 * @param okHttpClient 可选：复用已有的 OkHttpClient；不传则创建轻量实例
 */
class UpdateChecker(
    private val repoOwner: String,
    private val repoName: String,
    private val includePreRelease: Boolean = false,
    okHttpClient: OkHttpClient? = null,
) {
    private val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 检查是否有新版本。
     *
     * 在 IO 线程执行网络请求，可直接在协程中调用。
     *
     * @param context 用于获取当前版本号
     * @return [ApiResult.Success] 含 [UpdateInfo]；[ApiResult.Error] 含错误信息
     */
    suspend fun checkUpdate(context: Context): ApiResult<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val currentVersion = AppUtils.getVersionName()
            val release = fetchLatestRelease() ?: return@withContext ApiResult.Error(
                message = "未找到任何 Release，请确认仓库 $repoOwner/$repoName 已发布过版本。"
            )
            ApiResult.Success(release.toUpdateInfo(currentVersion))
        } catch (e: Exception) {
            ApiResult.Error(
                message = "检查更新失败：${e.message}",
                cause = e
            )
        }
    }

    // ─── 内部实现 ──────────────────────────────────────────────────────────────

    /**
     * 调用 GitHub API 获取 Release 列表，取第一个满足条件的 Release。
     * 若 [includePreRelease] 为 false，则跳过预发布版本。
     */
    private fun fetchLatestRelease(): GitHubRelease? {
        // GitHub API: GET /repos/{owner}/{repo}/releases
        // 第一条即最新；若需过滤 pre-release 则取列表
        val url = if (includePreRelease) {
            "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=10"
        } else {
            "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("GitHub API 返回异常：HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw RuntimeException("GitHub API 返回空响应")

        return if (includePreRelease) {
            // 返回数组，取第一个（最新）
            val array = JSONArray(body)
            if (array.length() == 0) null
            else GitHubRelease.fromJson(array.getJSONObject(0))
        } else {
            // 返回单个对象
            GitHubRelease.fromJson(JSONObject(body))
        }
    }

    // ─── 内部数据模型 ──────────────────────────────────────────────────────────

    private data class GitHubRelease(
        val tagName: String,        // e.g. "v1.2.3" 或 "v1.2.3!"（含"!"视为强制更新）
        val name: String,           // Release 标题
        val body: String,           // Release Notes（Markdown）
        val htmlUrl: String,        // GitHub Release 页面
        val publishedAt: String,    // ISO 8601 发布时间
        val isPreRelease: Boolean,
        val apkDownloadUrl: String?,// 第一个 .apk 附件的下载地址
    ) {
        fun toUpdateInfo(currentVersion: String): UpdateInfo {
            val isForce = tagName.endsWith("!")
            val cleanTag = tagName.trimEnd('!').trimStart('v', 'V')
            return UpdateInfo(
                latestVersion   = cleanTag,
                currentVersion  = currentVersion.trimStart('v', 'V'),
                releaseNotes    = body.trim(),
                downloadUrl     = apkDownloadUrl,
                releasePageUrl  = htmlUrl,
                publishedAt     = publishedAt,
                isForceUpdate   = isForce,
            )
        }

        companion object {
            fun fromJson(json: JSONObject): GitHubRelease {
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }
                return GitHubRelease(
                    tagName        = json.optString("tag_name"),
                    name           = json.optString("name"),
                    body           = json.optString("body"),
                    htmlUrl        = json.optString("html_url"),
                    publishedAt    = json.optString("published_at"),
                    isPreRelease   = json.optBoolean("prerelease", false),
                    apkDownloadUrl = apkUrl,
                )
            }
        }
    }
}

