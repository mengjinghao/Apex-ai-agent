package com.apex.agent.common.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 工具类，提供对 JSON 字符串的解析、转换和操作功能。
 * 基于 Android 内置的 org.json 库实现，无需额外依赖。
 */
object JsonUtils {

    /**
     * 检查字符串是否为有效的 JSON。
     * 尝试解析为 JSONObject 或 JSONArray，若任一成功则返回 true。
     *
     * @param json 待检查的字符串
     * @return 如果是有效的 JSON 返回 true，否则返回 false
     */
    fun isValidJson(json: String): Boolean {
        return try {
            JSONObject(json)
            true
        } catch (_: Exception) {
            try {
                JSONArray(json)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 将任意对象转换为 JSON 字符串。
     * 支持 Map、Collection、Array、String、Number、Boolean 等常见类型。
     * 对于 org.json 原生类型直接使用 toString()，其他类型尝试 wrap 后转换。
     *
     * @param obj 待转换的对象
     * @return JSON 字符串，转换失败时返回 "null"
     */
    fun toJson(obj: Any?): String {
        return when (obj) {
            null -> "null"
            is JSONObject -> obj.toString()
            is JSONArray -> obj.toString()
            is Map<*, *> -> JSONObject(obj).toString()
            is Collection<*> -> JSONArray(obj.toList()).toString()
            is Array<*> -> JSONArray(obj).toString()
            is Int, is Long, is Float, is Double, is Boolean -> obj.toString()
            is String -> "\"$obj\""
            else -> try {
                JSONObject.wrap(obj).toString()
            } catch (_: Exception) {
                obj.toString()
            }
        }
    }

    /**
     * 将 JSON 对象字符串解析为 [Map]。
     *
     * @param json JSON 对象字符串
     * @return 解析成功返回 Map，失败返回 null
     */
    fun toMap(json: String): Map<String, Any>? {
        return try {
            val jsonObject = JSONObject(json)
            jsonObject.toMap()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将 JSON 数组字符串解析为 [List]。
     *
     * @param json JSON 数组字符串
     * @return 解析成功返回 List，失败返回 null
     */
    fun toList(json: String): List<Any>? {
        return try {
            val jsonArray = JSONArray(json)
            jsonArray.toList()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将 JSON 字符串格式化（美化）输出。
     *
     * @param json        JSON 字符串
     * @param indentFactor 缩进空格数，默认为 2
     * @return 格式化后的 JSON 字符串，失败返回原始字符串
     */
    fun prettyPrint(json: String, indentFactor: Int = 2): String {
        return try {
            when {
                json.trimStart().startsWith("{") -> JSONObject(json).toString(indentFactor)
                json.trimStart().startsWith("[") -> JSONArray(json).toString(indentFactor)
                else -> json
            }
        } catch (_: Exception) {
            json
        }
    }

    /**
     * 压缩 JSON 字符串，去除所有多余的空白字符。
     *
     * @param json JSON 字符串
     * @return 压缩后的 JSON 字符串，失败返回原始字符串
     */
    fun minify(json: String): String {
        return try {
            when {
                json.trimStart().startsWith("{") -> JSONObject(json).toString()
                json.trimStart().startsWith("[") -> JSONArray(json).toString()
                else -> json
            }
        } catch (_: Exception) {
            json
        }
    }

    /**
     * 从 JSON 对象中安全地获取字符串值。
     *
     * @param json JSON 对象字符串
     * @param key  键名
     * @return 键对应的字符串值，键不存在或类型不匹配时返回 null
     */
    fun getString(json: String, key: String): String? {
        return try {
            JSONObject(json).optString(key, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 JSON 对象中安全地获取整数值。
     *
     * @param json JSON 对象字符串
     * @param key  键名
     * @return 键对应的整数值，键不存在或类型不匹配时返回 null
     */
    fun getInt(json: String, key: String): Int? {
        return try {
            val jo = JSONObject(json)
            if (jo.has(key)) jo.getInt(key) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 JSON 对象中安全地获取长整数值。
     *
     * @param json JSON 对象字符串
     * @param key  键名
     * @return 键对应的长整数值，键不存在或类型不匹配时返回 null
     */
    fun getLong(json: String, key: String): Long? {
        return try {
            val jo = JSONObject(json)
            if (jo.has(key)) jo.getLong(key) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 JSON 对象中安全地获取双精度浮点数值。
     *
     * @param json JSON 对象字符串
     * @param key  键名
     * @return 键对应的双精度值，键不存在或类型不匹配时返回 null
     */
    fun getDouble(json: String, key: String): Double? {
        return try {
            val jo = JSONObject(json)
            if (jo.has(key)) jo.getDouble(key) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 JSON 对象中安全地获取布尔值。
     *
     * @param json JSON 对象字符串
     * @param key  键名
     * @return 键对应的布尔值，键不存在或类型不匹配时返回 null
     */
    fun getBoolean(json: String, key: String): Boolean? {
        return try {
            val jo = JSONObject(json)
            if (jo.has(key)) jo.getBoolean(key) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 JSON 对象中获取嵌套的 JSON 对象字符串。
     *
     * @param json JSON 对象字符串
     * @param key  嵌套对象对应的键名
     * @return 嵌套 JSON 对象的字符串形式，不存在或类型不匹配时返回 null
     */
    fun getJsonObject(json: String, key: String): String? {
        return try {
            JSONObject(json).optJSONObject(key)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 JSON 对象中获取嵌套的 JSON 数组字符串。
     *
     * @param json JSON 对象字符串
     * @param key  嵌套数组对应的键名
     * @return 嵌套 JSON 数组的字符串形式，不存在或类型不匹配时返回 null
     */
    fun getJsonArray(json: String, key: String): String? {
        return try {
            JSONObject(json).optJSONArray(key)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 向 JSON 对象中添加（或更新）一个键值对，返回新的 JSON 字符串（不可变操作）。
     *
     * @param json  JSON 对象字符串
     * @param key   键名
     * @param value 字符串值
     * @return 修改后的 JSON 字符串，如果输入不是有效 JSON 对象则返回原始字符串
     */
    fun putString(json: String, key: String, value: String): String {
        return try {
            JSONObject(json).apply { put(key, value) }.toString()
        } catch (_: Exception) {
            json
        }
    }

    /**
     * 从 JSON 对象中移除指定键，返回新的 JSON 字符串（不可变操作）。
     *
     * @param json JSON 对象字符串
     * @param key  要移除的键名
     * @return 移除键后的 JSON 字符串，如果输入不是有效 JSON 对象则返回原始字符串
     */
    fun removeKey(json: String, key: String): String {
        return try {
            JSONObject(json).apply { remove(key) }.toString()
        } catch (_: Exception) {
            json
        }
    }

    // 内部辅助方法

    /**
     * 将 [JSONObject] 递归转换为 [Map]。
     */
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                JSONObject.NULL -> Unit
                else -> value
            }
        }
        return map
    }

    /**
     * 将 [JSONArray] 递归转换为 [List]。
     */
    private fun JSONArray.toList(): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until length()) {
            val value = get(i)
            list.add(when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                JSONObject.NULL -> Unit
                else -> value
            })
        }
        return list
    }
}
