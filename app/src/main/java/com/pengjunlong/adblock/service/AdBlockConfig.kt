package com.pengjunlong.adblock.service

import com.example.framework.storage.KVStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 广告跳过配置管理（基于 KVStore/MMKV 持久化）
 *
 * 管理：
 * - 广告关键词列表（用于匹配按钮文字）
 * - 黑名单包名（只有加入黑名单的 App 才会被自动跳过，其余一概不处理）
 * - 功能开关、点击延迟、Toast 提示等
 * - 今日跳过统计
 *
 * > 黑名单为空时，对所有非系统应用生效（等价于旧版「全局模式」）。
 * > 黑名单不为空时，仅对名单内应用生效（精准模式）。
 */
object AdBlockConfig {

    private val store = KVStore.of("adblock_config")
    private val gson = Gson()

    // ─── keys ────────────────────────────────────────────────────────────────
    private const val KEY_ENABLED        = "enabled"
    private const val KEY_KEYWORDS       = "keywords"
    private const val KEY_TARGETLIST     = "targetlist"   // 黑名单（仅处理名单内的 App）
    private const val KEY_CLICK_DELAY    = "click_delay_ms"
    private const val KEY_SHOW_TOAST     = "show_toast"
    private const val KEY_STATS_COUNT    = "stats_count"
    private const val KEY_STATS_DATE     = "stats_date"
    private const val KEY_LAST_APP       = "last_app"
    private const val KEY_DIAGNOSTIC     = "diagnostic_mode"  // 悬浮窗诊断模式
    private const val KEY_REGION_RULES   = "region_rules"    // 坐标点击规则
    private const val KEY_SCREENSHOTS    = "screenshots"     // 包名→截图文件路径映射

    /** 内置默认关键词（中英文，覆盖主流场景） */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        "跳过",
        "跳过广告",
        "跳过片头",
        "跳过片尾",
        "立即跳过",
        "关闭广告",
        "关闭",
        "×",
        "✕",
        "skip",
        "SKIP",
        "Skip",
        "skip ad",
        "SKIP AD",
        "close ad",
    )

    // ─── 服务总开关 ────────────────────────────────────────────────────────────

    var isEnabled: Boolean
        get() = store.decodeBool(KEY_ENABLED, true)
        set(v) { store.encode(KEY_ENABLED, v) }

    // ─── 诊断模式（悬浮窗实时日志） ───────────────────────────────────────────
    /** 开启后 AdBlockService 会通过悬浮窗在屏幕上实时展示节点扫描和点击结果，无需 adb */
    var diagnosticMode: Boolean
        get() = store.decodeBool(KEY_DIAGNOSTIC, false)
        set(v) { store.encode(KEY_DIAGNOSTIC, v) }

    // ─── 关键词 ────────────────────────────────────────────────────────────────

    fun getKeywords(): MutableList<String> {
        val json = store.decodeString(KEY_KEYWORDS, null)
        if (json.isNullOrEmpty()) return DEFAULT_KEYWORDS.toMutableList()
        return try {
            val type = object : TypeToken<MutableList<String>>() {}.type
            gson.fromJson(json, type) ?: DEFAULT_KEYWORDS.toMutableList()
        } catch (e: Exception) {
            DEFAULT_KEYWORDS.toMutableList()
        }
    }

    fun saveKeywords(keywords: List<String>) {
        store.encode(KEY_KEYWORDS, gson.toJson(keywords))
    }

    fun addKeyword(keyword: String) {
        val list = getKeywords()
        val trimmed = keyword.trim()
        if (trimmed.isNotEmpty() && !list.contains(trimmed)) {
            list.add(trimmed)
            saveKeywords(list)
        }
    }

    fun removeKeyword(keyword: String) {
        val list = getKeywords()
        list.remove(keyword)
        saveKeywords(list)
    }

    fun resetKeywordsToDefault() = saveKeywords(DEFAULT_KEYWORDS)

    // ─── 黑名单（目标应用列表） ────────────────────────────────────────────────
    //
    // 黑名单为空  → 全局模式：对所有非系统应用生效
    // 黑名单非空 → 精准模式：仅对名单内应用生效

    fun getTargetList(): MutableSet<String> {
        val json = store.decodeString(KEY_TARGETLIST, null)
        if (json.isNullOrEmpty()) return mutableSetOf()
        return try {
            val type = object : TypeToken<MutableSet<String>>() {}.type
            gson.fromJson(json, type) ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    fun saveTargetList(list: Set<String>) {
        store.encode(KEY_TARGETLIST, gson.toJson(list))
    }

    fun addToTargetList(packageName: String) {
        val set = getTargetList()
        set.add(packageName)
        saveTargetList(set)
    }

    fun removeFromTargetList(packageName: String) {
        val set = getTargetList()
        set.remove(packageName)
        saveTargetList(set)
    }

    /**
     * 判断某个包名是否应该被处理：
     * - 黑名单为空 → 全局模式，所有应用都处理
     * - 黑名单非空 → 精准模式，只处理名单内的应用
     */
    fun shouldHandle(packageName: String): Boolean {
        val list = getTargetList()
        return list.isEmpty() || list.contains(packageName)
    }

    // ─── 点击延迟 ──────────────────────────────────────────────────────────────

    /** 检测到广告后延迟多少毫秒再点击（防误触），默认 300ms */
    var clickDelayMs: Long
        get() = store.decodeLong(KEY_CLICK_DELAY, 300L)
        set(v) { store.encode(KEY_CLICK_DELAY, v) }

    // ─── Toast 提示 ────────────────────────────────────────────────────────────

    var showToast: Boolean
        get() = store.decodeBool(KEY_SHOW_TOAST, true)
        set(v) { store.encode(KEY_SHOW_TOAST, v) }

    // ─── 统计 ──────────────────────────────────────────────────────────────────

    fun getTodayCount(): Int {
        val today = todayStr()
        val savedDate = store.decodeString(KEY_STATS_DATE, "")
        if (today != savedDate) {
            store.encode(KEY_STATS_COUNT, 0)
            store.encode(KEY_STATS_DATE, today)
            return 0
        }
        return store.decodeInt(KEY_STATS_COUNT, 0)
    }

    fun incrementCount() {
        val count = getTodayCount()
        store.encode(KEY_STATS_COUNT, count + 1)
        store.encode(KEY_STATS_DATE, todayStr())
    }

    fun resetStats() {
        store.encode(KEY_STATS_COUNT, 0)
        store.encode(KEY_STATS_DATE, todayStr())
    }

    var lastBlockedApp: String
        get() = store.decodeString(KEY_LAST_APP, "") ?: ""
        set(v) { store.encode(KEY_LAST_APP, v) }

    // ─── 坐标点击规则 ───────────────────────────────────────────────────────────
    //
    // 每条规则绑定一个包名，记录用户在标记界面划定的矩形中心点坐标（相对于屏幕像素）。
    // 服务检测到该包名的窗口事件后，直接用手势点击该坐标，优先级高于关键词扫描。

    /** 获取所有坐标点击规则（包名 → RegionRule 映射） */
    fun getRegionRules(): MutableMap<String, RegionRule> {
        val json = store.decodeString(KEY_REGION_RULES, null)
        if (json.isNullOrEmpty()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, RegionRule>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun saveRegionRules(rules: Map<String, RegionRule>) {
        store.encode(KEY_REGION_RULES, gson.toJson(rules))
    }

    fun setRegionRule(packageName: String, rule: RegionRule) {
        val map = getRegionRules()
        map[packageName] = rule
        saveRegionRules(map)
    }

    fun removeRegionRule(packageName: String) {
        val map = getRegionRules()
        map.remove(packageName)
        saveRegionRules(map)
    }

    fun getRegionRule(packageName: String): RegionRule? = getRegionRules()[packageName]

    // ─── 截图路径 ──────────────────────────────────────────────────────────────
    //
    // 用户点击悬浮窗「📸」按钮后，截图保存为 files/screenshots/<pkg>.jpg，
    // 此处存储包名→文件路径映射，供 RegionMarkActivity 加载背景图。

    private fun getScreenshotMap(): MutableMap<String, String> {
        val json = store.decodeString(KEY_SCREENSHOTS, null)
        if (json.isNullOrEmpty()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /** 保存某个包名对应的截图文件路径 */
    fun setScreenshotPath(packageName: String, filePath: String) {
        val map = getScreenshotMap()
        map[packageName] = filePath
        store.encode(KEY_SCREENSHOTS, gson.toJson(map))
    }

    /** 获取某个包名对应的截图文件路径（无截图时返回 null） */
    fun getScreenshotPath(packageName: String): String? = getScreenshotMap()[packageName]

    /** 删除某个包名的截图记录（不删除文件，文件由调用方处理） */
    fun removeScreenshotPath(packageName: String) {
        val map = getScreenshotMap()
        map.remove(packageName)
        store.encode(KEY_SCREENSHOTS, gson.toJson(map))
    }

    private fun todayStr(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}-" +
               "${cal.get(java.util.Calendar.MONTH) + 1}-" +
               "${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }
}

