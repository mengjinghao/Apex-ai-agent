package com.apex.agent.common.extensions

import java.io.File
import java.nio.charset.Charset

/**
 * [File] 的扩展函数集合，提供安全的文件读写、尺寸格式化、扩展名判断等便捷方法。
 */

/**
 * 安全读取文件内容为字符串，读取失败时返回 null。
 *
 * @param charset 字符编码，默认 UTF-8
 * @return 文件内容字符串，读取失败返回 null
 */
fun File.readTextSafe(charset: Charset = Charsets.UTF_8): String? {
    return try {
        readText(charset)
    } catch (_: Exception) {
        null
    }
}

/**
 * 安全地将字符串写入文件，写入成功返回 true。
 *
 * @param text    待写入的文本
 * @param charset 字符编码，默认 UTF-8
 * @return 写入成功返回 true，失败返回 false
 */
fun File.writeTextSafe(text: String, charset: Charset = Charsets.UTF_8): Boolean {
    return try {
        writeText(text, charset)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 安全地向文件追加文本内容，追加成功返回 true。
 *
 * @param text    待追加的文本
 * @param charset 字符编码，默认 UTF-8
 * @return 追加成功返回 true，失败返回 false
 */
fun File.appendTextSafe(text: String, charset: Charset = Charsets.UTF_8): Boolean {
    return try {
        appendText(text, charset)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 将文件大小格式化为人类可读的字符串，例如 "1.5 KB"、"23.0 MB"。
 *
 * @return 格式化后的文件大小字符串
 */
fun File.sizeAsString(): String {
    if (!exists()) return "0 B"
    val bytes = length()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var unitIndex = 0
    var size = bytes.toDouble()
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$size B"
    } else {
        "%.1f %s".format(size, units[unitIndex])
    }
}

/**
 * 获取文件扩展名（小写），不包含点号。
 * 例如 "example.txt" -> "txt"，无扩展名返回空字符串 ""。
 */
val File.extension: String
    get() {
        val name = name
        val lastDot = name.lastIndexOf('.')
        return if (lastDot >= 0) name.substring(lastDot + 1).lowercase() else ""
    }

/**
 * 判断文件是否为文本类型的文件（基于扩展名判断）。
 * 支持的文本扩展名：txt、xml、json、csv、html、htm、css、js、ts、kt、java、py、md、log、yml、yaml、properties、cfg、ini、sh、bat、sql、gradle、kts 等。
 *
 * @return 文本类型返回 true
 */
fun File.isTextBased(): Boolean {
    val textExtensions = setOf(
        "txt", "xml", "json", "csv", "html", "htm", "css", "js", "ts",
        "kt", "java", "py", "md", "log", "yml", "yaml", "properties",
        "cfg", "ini", "sh", "bat", "cmd", "sql", "gradle", "kts",
        "conf", "toml", "env", "rc", "svg", "tex", "rst", "adoc"
    )
    return extension in textExtensions
}

/**
 * 获取文件名（不含扩展名）。
 * 例如 "/path/to/example.txt" -> "example"。
 */
val File.nameWithoutExtension: String
    get() {
        val name = name
        val lastDot = name.lastIndexOf('.')
        return if (lastDot >= 0) name.substring(0, lastDot) else name
    }
