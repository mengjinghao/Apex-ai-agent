package com.apex.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 工具类，提供 JSON 数据的解析、格式化、转换等操作方法
 */
object JsonUtils {

    /**
     * 检查字符串是否为有效的 JSON
     *
     * @param json JSON 字符串
     * @return 如果是有效的 JSON 则返回 true
     */
    fun isValidJson(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 格式化 JSON 字符串为带缩进的可读形式
     *
     * @param json JSON 字符串
     * @return 格式化后的 JSON 字符串
     */
    fun prettyPrint(json: String): String {
        if (json.isBlank()) return json
        return try {
            val trimmed = json.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> json
            }
        } catch (e: Exception) {
            json
        }
    }

    /**
     * 压缩 JSON 字符串，移除不必要的空白字符
     *
     * @param json JSON 字符串
     * @return 压缩后的 JSON 字符串
     */
    fun minify(json: String): String {
        if (json.isBlank()) return json
        return try {
            val trimmed = json.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString()
                trimmed.startsWith("[") -> JSONArray(trimmed).toString()
                else -> json
            }
        } catch (e: Exception) {
            json
        }
    }

    /**
     * 将 JSON 字符串转换为 Map
     *
     * @param json JSON 字符串
     * @return 转换成功返回 Map，失败返回 null
     */
    fun jsonToMap(json: String): Map<String, Any>? {
        return try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            val jsonObject = JSONObject(trimmed)
            jsonObjectToMap(jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将 Map 转换为 JSON 字符串
     *
     * @param map 待转换的 Map
     * @return JSON 字符串
     */
    fun mapToJson(map: Map<String, Any?>): String {
        val jsonObject = mapToJsonObject(map)
        return jsonObject.toString()
    }

    /**
     * 通过点号路径获取 JSON 中的值（例如 "data.user.name"）
     *
     * @param json JSON 字符串
     * @param path 点号分隔的路径
     * @return 路径对应的值，不存在则返回 null
     */
    fun getValueByPath(json: String, path: String): Any? {
        if (json.isBlank() || path.isBlank()) return null
        return try {
            val trimmed = json.trim()
            val root: Any = when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> return null
            }
            val parts = path.split(".")
            var current: Any? = root
            for (part in parts) {
                current = when (current) {
                    is JSONObject -> {
                        if (current.has(part)) current.get(part) else return null
                    }
                    is JSONArray -> {
                        val index = part.toIntOrNull() ?: return null
                        if (index in 0 until current.length()) current.get(index) else return null
                    }
                    else -> return null
                }
            }
            if (current === JSONObject.NULL) null else current
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 深度合并两个 JSON 对象（overlay 覆盖 base）
     *
     * @param base 基础 JSON 字符串
     * @param overlay 覆盖 JSON 字符串
     * @return 合并后的 JSON 字符串
     */
    fun mergeJson(base: String, overlay: String): String {
        return try {
            val baseObj = JSONObject(base.trim())
            val overlayObj = JSONObject(overlay.trim())
            val merged = deepMerge(baseObj, overlayObj)
            merged.toString()
        } catch (e: Exception) {
            base
        }
    }

    /**
     * 将嵌套 JSON 展平为点号路径的键值对
     *
     * @param json JSON 字符串
     * @param prefix 键前缀，默认为空
     * @return 展平后的 Map
     */
    fun flattenJson(json: String, prefix: String = ""): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val trimmed = json.trim()
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    flattenJsonObject(obj, prefix, result)
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    flattenJsonArray(arr, prefix, result)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * 检查 JSON 字符串是否为 JSON 数组
     *
     * @param json JSON 字符串
     * @return 如果是 JSON 数组则返回 true
     */
    fun isJsonArray(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val trimmed = json.trim()
            trimmed.startsWith("[") && JSONArray(trimmed).length() >= 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 JSON 字符串是否为 JSON 对象
     *
     * @param json JSON 字符串
     * @return 如果是 JSON 对象则返回 true
     */
    fun isJsonObject(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val trimmed = json.trim()
            trimmed.startsWith("{") && JSONObject(trimmed).length() >= 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 提取 JSON 中所有路径的列表
     *
     * @param json JSON 字符串
     * @return 路径字符串列表
     */
    fun extractJsonPaths(json: String): List<String> {
        val paths = mutableListOf<String>()
        try {
            val trimmed = json.trim()
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    extractPathsFromObject(obj, "", paths)
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    extractPathsFromArray(arr, "", paths)
                }
            }
        } catch (_: Exception) {
        }
        return paths.sorted()
    }

    /**
     * 移除 JSON 中所有值为 null 的字段
     *
     * @param json JSON 字符串
     * @return 移除 null 后的 JSON 字符串
     */
    fun removeNulls(json: String): String {
        return try {
            val trimmed = json.trim()
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    removeNullsFromObject(obj).toString()
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    removeNullsFromArray(arr).toString()
                }
                else -> json
            }
        } catch (e: Exception) {
            json
        }
    }

    /**
     * 对 JSON 对象的键按字母顺序排序
     *
     * @param json JSON 字符串
     * @return 键排序后的 JSON 字符串
     */
    fun sortJson(json: String): String {
        return try {
            val trimmed = json.trim()
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    sortJsonObject(obj).toString(2)
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    sortJsonArray(arr).toString(2)
                }
                else -> json
            }
        } catch (e: Exception) {
            json
        }
    }

    private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in jsonObject.keys()) {
            val value = jsonObject.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> "null"
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(jsonArray: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until jsonArray.length()) {
            val value = jsonArray.get(i)
            list.add(
                when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToList(value)
                    JSONObject.NULL -> "null"
                    else -> value
                }
            )
        }
        return list
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val jsonObject = JSONObject()
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    jsonObject.put(key, mapToJsonObject(value as Map<String, Any?>))
                }
                is List<*> -> {
                    jsonObject.put(key, listToJsonArray(value))
                }
                null -> jsonObject.put(key, JSONObject.NULL)
                else -> jsonObject.put(key, value)
            }
        }
        return jsonObject
    }

    private fun listToJsonArray(list: List<*>): JSONArray {
        val jsonArray = JSONArray()
        for (item in list) {
            when (item) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    jsonArray.put(mapToJsonObject(item as Map<String, Any?>))
                }
                is List<*> -> jsonArray.put(listToJsonArray(item))
                null -> jsonArray.put(JSONObject.NULL)
                else -> jsonArray.put(item)
            }
        }
        return jsonArray
    }

    private fun deepMerge(base: JSONObject, overlay: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in base.keys()) {
            result.put(key, base.get(key))
        }
        for (key in overlay.keys()) {
            val overlayValue = overlay.get(key)
            if (result.has(key)) {
                val baseValue = result.get(key)
                if (baseValue is JSONObject && overlayValue is JSONObject) {
                    result.put(key, deepMerge(baseValue, overlayValue))
                } else {
                    result.put(key, overlayValue)
                }
            } else {
                result.put(key, overlayValue)
            }
        }
        return result
    }

    private fun flattenJsonObject(obj: JSONObject, prefix: String, result: MutableMap<String, String>) {
        for (key in obj.keys()) {
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            val value = obj.get(key)
            when (value) {
                is JSONObject -> flattenJsonObject(value, fullKey, result)
                is JSONArray -> flattenJsonArray(value, fullKey, result)
                JSONObject.NULL -> result[fullKey] = "null"
                else -> result[fullKey] = value.toString()
            }
        }
    }

    private fun flattenJsonArray(arr: JSONArray, prefix: String, result: MutableMap<String, String>) {
        for (i in 0 until arr.length()) {
            val fullKey = "$prefix[$i]"
            val value = arr.get(i)
            when (value) {
                is JSONObject -> flattenJsonObject(value, fullKey, result)
                is JSONArray -> flattenJsonArray(value, fullKey, result)
                JSONObject.NULL -> result[fullKey] = "null"
                else -> result[fullKey] = value.toString()
            }
        }
    }

    private fun extractPathsFromObject(obj: JSONObject, prefix: String, paths: MutableList<String>) {
        for (key in obj.keys()) {
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            paths.add(fullKey)
            val value = obj.get(key)
            when (value) {
                is JSONObject -> extractPathsFromObject(value, fullKey, paths)
                is JSONArray -> extractPathsFromArray(value, fullKey, paths)
            }
        }
    }

    private fun extractPathsFromArray(arr: JSONArray, prefix: String, paths: MutableList<String>) {
        for (i in 0 until arr.length()) {
            val fullKey = "$prefix[$i]"
            paths.add(fullKey)
            val value = arr.get(i)
            when (value) {
                is JSONObject -> extractPathsFromObject(value, fullKey, paths)
                is JSONArray -> extractPathsFromArray(value, fullKey, paths)
            }
        }
    }

    private fun removeNullsFromObject(obj: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in obj.keys()) {
            val value = obj.get(key)
            when (value) {
                JSONObject.NULL -> { /* skip */ }
                is JSONObject -> result.put(key, removeNullsFromObject(value))
                is JSONArray -> result.put(key, removeNullsFromArray(value))
                else -> result.put(key, value)
            }
        }
        return result
    }

    private fun removeNullsFromArray(arr: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            when (value) {
                JSONObject.NULL -> { /* skip */ }
                is JSONObject -> result.put(removeNullsFromObject(value))
                is JSONArray -> result.put(removeNullsFromArray(value))
                else -> result.put(value)
            }
        }
        return result
    }

    private fun sortJsonObject(obj: JSONObject): JSONObject {
        val result = JSONObject()
        val sortedKeys = obj.keys().asSequence().sorted().toList()
        for (key in sortedKeys) {
            val value = obj.get(key)
            when (value) {
                is JSONObject -> result.put(key, sortJsonObject(value))
                is JSONArray -> result.put(key, sortJsonArray(value))
                else -> result.put(key, value)
            }
        }
        return result
    }

    private fun sortJsonArray(arr: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            when (value) {
                is JSONObject -> result.put(sortJsonObject(value))
                is JSONArray -> result.put(sortJsonArray(value))
                else -> result.put(value)
            }
        }
        return result
    }
}
