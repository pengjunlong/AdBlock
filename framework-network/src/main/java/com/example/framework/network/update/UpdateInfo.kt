package com.example.framework.network.update

/**
 * 版本更新信息
 *
 * @property latestVersion  最新版本号，如 "1.2.3"
 * @property currentVersion 当前安装的版本号
 * @property releaseNotes   更新日志（Markdown 原文）
 * @property downloadUrl    APK 下载地址（Release 附件），null 表示无 APK 附件
 * @property releasePageUrl GitHub Release 页面地址
 * @property publishedAt    发布时间（ISO 8601 格式）
 * @property isForceUpdate  是否强制更新（tag 名含 "!" 时视为强制，如 v1.2.3!）
 */
data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val releaseNotes: String,
    val downloadUrl: String?,
    val releasePageUrl: String,
    val publishedAt: String,
    val isForceUpdate: Boolean = false,
) {
    /** 是否有新版本可用 */
    val hasUpdate: Boolean
        get() = isNewerVersion(latestVersion, currentVersion)

    companion object {
        /**
         * 比较语义化版本号，判断 [candidate] 是否比 [current] 更新。
         * 支持 "1.2.3"、"v1.2.3" 格式；非标准格式退化为字符串比较。
         */
        fun isNewerVersion(candidate: String, current: String): Boolean {
            val c = candidate.trimStart('v', 'V').split(".").mapNotNull { it.toIntOrNull() }
            val cur = current.trimStart('v', 'V').split(".").mapNotNull { it.toIntOrNull() }
            val len = maxOf(c.size, cur.size)
            for (i in 0 until len) {
                val cv = c.getOrElse(i) { 0 }
                val curv = cur.getOrElse(i) { 0 }
                if (cv != curv) return cv > curv
            }
            return false
        }
    }
}

