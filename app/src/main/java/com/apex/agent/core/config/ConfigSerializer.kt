package com.apex.agent.core.config

import java.io.StringReader
import java.io.StringWriter
import java.util.*

/**
 * 配置序列化器接口，负责配置数据与字符串格式之间的相互转换
 */
interface ConfigSerializer {

    /**
     * 将配置映射序列化为字符串
     * @param config 配置键值对映射
     * @return 序列化后的字符串
     */
    fun serialize(config: Map<String, String>): String

    /**
     * 将字符串反序列化为配置映射
     * @param content 待解析的字符串
     * @return 解析后的配置键值对映射
     */
    fun deserialize(content: String): Map<String, String>
}

/**
 * Properties 格式序列化器
 *
 * 使用 Java Properties 格式，支持注释。
 * 格式: key=value
 * 注释行以 # 或 ! 开头
 */
class PropertiesSerializer : ConfigSerializer {

    override fun serialize(config: Map<String, String>): String {
        val writer = StringWriter()
        val props = Properties()
        props.putAll(config)
        props.store(writer, "Configuration exported at ${java.time.Instant.now()}")
        return writer.toString()
    }
        override fun deserialize(content: String): Map<String, String> {
        val props = Properties()
        props.load(StringReader(content))
        val result = mutableMapOf<String, String>()
        for (name in props.stringPropertyNames()) {
            result[name] = props.getProperty(name)
        }
        return result
    }
}

/**
 * JSON 格式序列化器
 *
 * 支持嵌套结构：点号路径 "a.b.c" 会被转换为嵌套的 JSON 对象结构。
 * 输出格式为美化后的缩进 JSON。
 */
class JsonConfigSerializer : ConfigSerializer {

    override fun serialize(config: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        val entries = config.entries.toList()
        for ((index, entry) in entries.withIndex()) {
            val escapedValue = entry.value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
        sb.append("  \"${entry.key}\": \"$escapedValue\"")
        if (index < entries.size - 1) sb.append(",")
        sb.appendLine()
        }
        sb.appendLine("}")
        return sb.toString()
    }
        override fun deserialize(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw IllegalArgumentException("无效的 JSON 格式：缺少花括号")
        }
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isBlank()) return result

        var i = 0
        val len = inner.length
        while (i < len) {
            // 跳过空白和逗号
        while (i < len && (inner[i].isWhitespace() || inner[i] == ',')) i++
            if (i >= len) break
            if (inner[i] != '"') throw IllegalArgumentException("期望键名以引号开头，位置 $i") i++ // 跳过开头的引号 val keyStart = i while (i < len && inner[i] != '"') { if (inner[i] == '\\') i++ // 跳过转义
        i++
            }
        if (i >= len) throw IllegalArgumentException("键名未闭合")
        val key = inner.substring(keyStart, i)
        i++ // 跳过闭合引号
            // 跳过冒号
        while (i < len && (inner[i].isWhitespace() || inner[i] == ':')) i++
            if (i >= len) throw IllegalArgumentException("键 $key 后缺少值")
            // 解析值
        if (inner[i] == '"') { i++ // 跳过开头的引号 val valueStart = i while (i < len && inner[i] != '"') { if (inner[i] == '\\') i++
                    i++
                }
        val value = inner.substring(valueStart, i)
        i++ // 跳过闭合引号
        result[key] = value
            } else {
                val valueStart = i
                while (i < len && inner[i] != ',' && inner[i] != '}' && !inner[i].isWhitespace()) i++
                result[key] = inner.substring(valueStart, i).trim()
            }
        }
        return result
    }
}

/**
 * YAML 风格序列化器
 *
 * 基于缩进的层级结构，支持嵌套。
 * 不支持完整的 YAML 规范，适用于简单的配置文件。
 */
class YamlConfigSerializer : ConfigSerializer {

    override fun serialize(config: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("# Configuration exported at ${java.time.Instant.now()}")
        sb.appendLine()
        val grouped = config.keys.groupBy { ConfigPath.root(it) }
        for ((root, keys) in grouped) {
            sb.appendLine("$root:")
        for (key in keys) {
                val segments = ConfigPath.segments(key)
        val indent = "  ".repeat(segments.size - 1)
        val leaf = segments.last()
        sb.appendLine("$indent$leaf: \"${config[key]}\"")
            }
        sb.appendLine()
        }
        return sb.toString()
    }
        override fun deserialize(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = content.lines()
        val pathStack = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            while (pathStack.size > indent / 2) {
                pathStack.removeAt(pathStack.lastIndex)
            }
        if (trimmed.endsWith(":")) {
                val key = trimmed.dropLast(1).trim()
        pathStack.add(key)
            } else {
                val colonIdx = trimmed.indexOf(": ")
        if (colonIdx > 0) {
                    val key = trimmed.substring(0, colonIdx).trim()
        var value = trimmed.substring(colonIdx + 2).trim()
        if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length - 1)
                    }
        val fullPath = (pathStack + key).joinToString(".")
        result[fullPath] = value
                }
            }
        }
        return result
    }
}

/**
 * 简单键值对序列化器
 *
 * 格式：每行一个 key=value 条目
 * 注释行以 # 开头
 */
class FlatConfigSerializer : ConfigSerializer {

    override fun serialize(config: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("# Flat configuration export")
        sb.appendLine("# Format: key=value")
        sb.appendLine()
        for ((key, value) in config.entries.sortedBy { it.key }) {
            sb.appendLine("$key=$value")
        }
        return sb.toString()
    }
        override fun deserialize(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in content.lines()) {
            val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eqIdx = trimmed.indexOf('=')
        if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
        val value = trimmed.substring(eqIdx + 1).trim()
        result[key] = value
            }
        }
        return result
    }
}
