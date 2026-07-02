package com.apex.sdk.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 跨 APK 共享存储桥。
 *
 * 由于所有 APK 共享 [ApexSuite.SHARED_USER_ID] + 同一进程，
 * 它们共享同一个 [Context] 的 DataStore 实例。
 * 业务侧调用 [ApexDataStore.getString] 等方法读写配置，效果等价于单 APK。
 *
 * **注意**：DataStore 文件位于 `/data/data/com.apex.agent/files/datastore/`，
 * 同 UID 的 APK 都可读写。
 *
 * 对 Room 数据库同理 — 同 UID 下所有 APK 可直接打开同一个 .db 文件。
 */
private val Context.apexDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "apex_suite_shared"
)

object ApexDataStore {

    private const val TAG_SUB = "ApexDataStore"

    fun string(context: Context, key: String, default: String = ""): Flow<String> =
        context.apexDataStore.data.map { it[stringPreferencesKey(key)] ?: default }

    fun int(context: Context, key: String, default: Int = 0): Flow<Int> =
        context.apexDataStore.data.map { it[intPreferencesKey(key)] ?: default }

    fun boolean(context: Context, key: String, default: Boolean = false): Flow<Boolean> =
        context.apexDataStore.data.map { it[booleanPreferencesKey(key)] ?: default }

    suspend fun putString(context: Context, key: String, value: String) {
        context.apexDataStore.edit { it[stringPreferencesKey(key)] = value }
        ApexLog.v(ApexSuite.ApkId.MAIN, "[$TAG_SUB] putString: $key")
    }

    suspend fun putInt(context: Context, key: String, value: Int) {
        context.apexDataStore.edit { it[intPreferencesKey(key)] = value }
    }

    suspend fun putBoolean(context: Context, key: String, value: Boolean) {
        context.apexDataStore.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun getStringSync(context: Context, key: String, default: String = ""): String =
        string(context, key, default).first()

    suspend fun getIntSync(context: Context, key: String, default: Int = 0): Int =
        int(context, key, default).first()

    suspend fun getBooleanSync(context: Context, key: String, default: Boolean = false): Boolean =
        boolean(context, key, default).first()

    suspend fun remove(context: Context, key: String) {
        context.apexDataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}

/**
 * 跨 APK 共享的 Key 命名空间 — 避免各 APK 自定义 key 冲突。
 */
object ApexDataKeys {
    const val SELECTED_MODEL_PROVIDER = "model.provider"
    const val SELECTED_MODEL_NAME = "model.name"
    const val SELECTED_MODEL_API_KEY = "model.api_key"
    const val DEFAULT_WORKING_DIR = "working_dir.default"
    const val THEME_MODE = "ui.theme_mode"
    const val LANGUAGE = "ui.language"
    const val BURST_MODE_PROFILE = "burst.profile"
    const val LAST_ACTIVE_APK = "suite.last_active_apk"
}
