package com.apex.agent.presentation.enhancedterminal.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.terminalDataStore: DataStore<Preferences> by preferencesDataStore(name = "enhanced_terminal")

/**
 * 终端持久化存储 — DataStore Preferences
 *
 * 持久化:命令历史 / 别名 / 代码段 / 主题 / 字体大小
 */
class TerminalPreferences(private val context: Context) {

    private object K {
        val HISTORY = stringPreferencesKey("command_history")
        val ALIASES = stringPreferencesKey("aliases")
        val SNIPPETS = stringPreferencesKey("snippets")
        val THEME_ID = stringPreferencesKey("theme_id")
        val FONT_SIZE = intPreferencesKey("font_size")
    }

    // ============ 命令历史 ============
    val historyFlow: Flow<List<Pair<String, Long>>> = context.terminalDataStore.data.map { p ->
        val json = p[K.HISTORY] ?: return@map emptyList()
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("cmd") to o.getLong("ts")
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun saveHistory(history: List<Pair<String, Long>>) {
        val arr = JSONArray()
        history.takeLast(500).forEach { (cmd, ts) ->
            arr.put(JSONObject().put("cmd", cmd).put("ts", ts))
        }
        context.terminalDataStore.edit { it[K.HISTORY] = arr.toString() }
    }

    // ============ 别名 ============
    val aliasesFlow: Flow<Map<String, Pair<String, String?>>> = context.terminalDataStore.data.map { p ->
        val json = p[K.ALIASES] ?: return@map emptyMap()
        try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { key ->
                val v = o.getJSONObject(key)
                v.getString("command") to (if (v.has("desc")) v.getString("desc") else null)
            }
        } catch (e: Exception) { emptyMap() }
    }

    suspend fun saveAliases(aliases: Map<String, com.apex.agent.presentation.enhancedterminal.data.CommandAlias>) {
        val o = JSONObject()
        aliases.forEach { (key, alias) ->
            o.put(key, JSONObject().put("command", alias.command).apply {
                alias.description?.let { put("desc", it) }
            })
        }
        context.terminalDataStore.edit { it[K.ALIASES] = o.toString() }
    }

    // ============ 代码段 ============
    val snippetsFlow: Flow<List<com.apex.agent.presentation.enhancedterminal.data.Snippet>> = context.terminalDataStore.data.map { p ->
        val json = p[K.SNIPPETS] ?: return@map emptyList()
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.apex.agent.presentation.enhancedterminal.data.Snippet(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    content = o.getString("content"),
                    language = o.optString("language", "bash"),
                    tags = o.optJSONArray("tags")?.let { ta -> (0 until ta.length()).map { ta.getString(it) } } ?: emptyList(),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun saveSnippets(snippets: List<com.apex.agent.presentation.enhancedterminal.data.Snippet>) {
        val arr = JSONArray()
        snippets.forEach { s ->
            val tagsArr = JSONArray()
            s.tags.forEach { tagsArr.put(it) }
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("content", s.content)
                put("language", s.language); put("tags", tagsArr); put("createdAt", s.createdAt)
            })
        }
        context.terminalDataStore.edit { it[K.SNIPPETS] = arr.toString() }
    }

    // ============ 主题 ============
    val themeIdFlow: Flow<String> = context.terminalDataStore.data.map { p ->
        p[K.THEME_ID] ?: "apex_dark"
    }

    suspend fun saveThemeId(id: String) {
        context.terminalDataStore.edit { it[K.THEME_ID] = id }
    }

    // ============ 字体大小 ============
    val fontSizeFlow: Flow<Int> = context.terminalDataStore.data.map { p ->
        p[K.FONT_SIZE] ?: 12
    }

    suspend fun saveFontSize(size: Int) {
        context.terminalDataStore.edit { it[K.FONT_SIZE] = size }
    }
}
