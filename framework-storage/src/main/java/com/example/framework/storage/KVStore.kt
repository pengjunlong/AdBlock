package com.example.framework.storage

import com.tencent.mmkv.MMKV

/**
 * KV 存储门面（基于 MMKV）
 *
 * 提供类型安全的读写接口，支持默认值，支持多实例（通过 [mmapID] 区分业务场景）。
 *
 * ### 使用示例
 * ```kotlin
 * // 默认实例（全局共享）
 * KVStore.putString("token", "Bearer xxx")
 * val token = KVStore.getString("token", "")
 *
 * // 为特定业务创建独立实例（数据互相隔离）
 * val userStore = KVStore.of("user_prefs")
 * userStore.putBoolean("is_agreed", true)
 * ```
 */
object KVStore {

    private val default: MMKV by lazy { MMKV.defaultMMKV() }

    /** 获取指定 mmapID 的独立 MMKV 实例 */
    fun of(mmapID: String): MMKV = MMKV.mmkvWithID(mmapID)

    // ─── String ──────────────────────────────────────────────────────────────

    fun putString(key: String, value: String?) = default.encode(key, value)
    fun getString(key: String, default: String = ""): String =
        this.default.decodeString(key, default) ?: default

    // ─── Int ─────────────────────────────────────────────────────────────────

    fun putInt(key: String, value: Int) = default.encode(key, value)
    fun getInt(key: String, default: Int = 0): Int = this.default.decodeInt(key, default)

    // ─── Long ────────────────────────────────────────────────────────────────

    fun putLong(key: String, value: Long) = default.encode(key, value)
    fun getLong(key: String, default: Long = 0L): Long = this.default.decodeLong(key, default)

    // ─── Float ───────────────────────────────────────────────────────────────

    fun putFloat(key: String, value: Float) = default.encode(key, value)
    fun getFloat(key: String, default: Float = 0f): Float = this.default.decodeFloat(key, default)

    // ─── Double ──────────────────────────────────────────────────────────────

    fun putDouble(key: String, value: Double) = default.encode(key, value)
    fun getDouble(key: String, default: Double = 0.0): Double =
        this.default.decodeDouble(key, default)

    // ─── Boolean ─────────────────────────────────────────────────────────────

    fun putBoolean(key: String, value: Boolean) = default.encode(key, value)
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        this.default.decodeBool(key, default)

    // ─── Bytes ───────────────────────────────────────────────────────────────

    fun putBytes(key: String, value: ByteArray?) = default.encode(key, value)
    fun getBytes(key: String): ByteArray? = default.decodeBytes(key)

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    fun contains(key: String): Boolean = default.containsKey(key)

    fun remove(key: String) = default.removeValueForKey(key)

    fun clearAll() = default.clearAll()

    fun allKeys(): Array<String>? = default.allKeys()
}

