package com.apex.util

import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * MIME 类型工具类，提供扩展名检测、魔术字节检测、内容类型协商等功能。
 */
object MimeTypeUtils {

    private val EXTENSION_TO_MIME = mapOf(
        "txt" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "csv" to "text/csv",
        "xml" to "application/xml",
        "json" to "application/json",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "ts" to "application/typescript",
        "md" to "text/markdown",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "toml" to "application/toml",
        "properties" to "text/plain",
        "ini" to "text/plain",
        "cfg" to "text/plain",
        "log" to "text/plain",
        "env" to "text/plain",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "mp4" to "video/mp4",
        "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "webm" to "video/webm",
        "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg",
        "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
        "wma" to "audio/x-ms-wma",
        "m4a" to "audio/mp4",
        "opus" to "audio/opus",
        "mid" to "audio/midi",
        "midi" to "audio/midi",
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        "epub" to "application/epub+zip",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "gzip" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        "7z" to "application/x-7z-compressed",
        "zst" to "application/zstd",
        "apk" to "application/vnd.android.package-archive",
        "jar" to "application/java-archive",
        "war" to "application/java-archive",
        "class" to "application/java-vm",
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "java" to "text/x-java-source",
        "py" to "text/x-python",
        "rb" to "text/x-ruby",
        "php" to "text/x-php",
        "go" to "text/x-go",
        "rs" to "text/x-rust",
        "swift" to "text/x-swift",
        "c" to "text/x-csrc",
        "cpp" to "text/x-c++src",
        "h" to "text/x-csrc",
        "hpp" to "text/x-c++src",
        "cs" to "text/x-csharp",
        "sh" to "application/x-sh",
        "bat" to "application/x-msdos-program",
        "ps1" to "application/x-powershell",
        "sql" to "application/sql",
        "wasm" to "application/wasm",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject"
    )
        private val MIME_TO_EXTENSION = EXTENSION_TO_MIME.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { it.value.first() }

    // 魔术字节到 MIME 类型的映射
    private val MAGIC_BYTES: List<Pair<ByteArray, String>> = listOf(
        byteArrayOf(0x89, 0x50, 0x4E, 0x47) to "image/png",
        byteArrayOf(0xFF, 0xD8, 0xFF) to "image/jpeg",
        byteArrayOf(0x47, 0x49, 0x46, 0x38) to "image/gif",
        byteArrayOf(0x42, 0x4D) to "image/bmp",
        byteArrayOf(0x52, 0x49, 0x46, 0x46) to "image/webp",
        byteArrayOf(0x25, 0x50, 0x44, 0x46) to "application/pdf",
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) to "application/zip",
        byteArrayOf(0x50, 0x4B, 0x05, 0x06) to "application/zip",
        byteArrayOf(0x50, 0x4B, 0x07, 0x08) to "application/zip",
        byteArrayOf(0x1F, 0x8B) to "application/gzip",
        byteArrayOf(0x42, 0x5A, 0x68) to "application/x-bzip2",
        byteArrayOf(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) to "application/x-xz",
        byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) to "application/vnd.rar",
        byteArrayOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) to "application/x-7z-compressed",
        byteArrayOf(0x25, 0x21) to "application/postscript",
        byteArrayOf(0xEF, 0xBB, 0xBF) to "text/plain",
        byteArrayOf(0xFE, 0xFF) to "text/plain",
        byteArrayOf(0xFF, 0xFE) to "text/plain",
        byteArrayOf(0x49, 0x44, 0x33) to "audio/mpeg",
        byteArrayOf(0x66, 0x74, 0x79, 0x70) to "video/mp4",
        byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70) to "video/mp4",
        byteArrayOf(0x1A, 0x45, 0xDF, 0xA3) to "video/webm",
        byteArrayOf(0x4F, 0x67, 0x67, 0x53) to "audio/ogg",
        byteArrayOf(0x52, 0x49, 0x46, 0x46) to "audio/wav",
        byteArrayOf(0x64, 0x6E, 0x73, 0x2E) to "application/x-ns-proxy-autoconfig"
    )

    /**
     * 根据文件扩展名获取 MIME 类型。
     *
     * @param extension 文件扩展名（可带或不带点号）
     * @return MIME 类型字符串，未知扩展名返回 "application/octet-stream"
     */
    fun fromExtension(extension: String): String {
        val ext = extension.trimStart('.').lowercase(Locale.ROOT)
        return EXTENSION_TO_MIME[ext] ?: "application/octet-stream"
    }

    /**
     * 根据 MIME 类型获取常见的文件扩展名。
     *
     * @param mimeType MIME 类型
     * @return 文件扩展名（不含点号），未知 MIME 类型返回 null
     */
    fun toExtension(mimeType: String): String? {
        return MIME_TO_EXTENSION[mimeType.lowercase(Locale.ROOT)]
    }

    /**
     * 根据魔术字节检测 MIME 类型。
     *
     * @param bytes 文件头字节数组
     * @return 检测到的 MIME 类型，未识别返回 null
     */
    fun fromMagicBytes(bytes: ByteArray): String? {
        for ((magic, mime) in MAGIC_BYTES) {
            if (bytes.size >= magic.size) {
                var match = true
                for (i in magic.indices) {
                    if (bytes[i] != magic[i]) {
                        match = false
                        break
                    }
                }
        if (match) return mime
            }
        }
        return null
    }

    /**
     * 根据输入流的前 N 字节检测 MIME 类型。
     *
     * @param input 输入流
     * @param maxBytes 读取的最大字节数，默认 16
     * @return 检测到的 MIME 类型，未识别返回 null
     */
    fun fromStream(input: InputStream, maxBytes: Int = 16): String? {
        return try {
            val bytes = ByteArray(maxBytes)
        val read = input.read(bytes, 0, maxBytes)
        if (read > 0) {
                fromMagicBytes(bytes.copyOf(read))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 根据文件内容检测 MIME 类型，结合扩展名和魔术字节。
     *
     * @param file 文件对象
     * @return MIME 类型字符串
     */
    fun fromFile(file: File): String {
        val extMime = fromExtension(file.extension)
        if (extMime != "application/octet-stream") return extMime
        return try {
            file.inputStream().use { input ->
                fromStream(input) ?: extMime
            }
        } catch (_: Exception) {
            extMime
        }
    }

    /**
     * 根据文件名检测 MIME 类型。
     *
     * @param fileName 文件名
     * @return MIME 类型字符串
     */
    fun fromFileName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0) return "application/octet-stream"
        val ext = fileName.substring(dot + 1)
        return fromExtension(ext)
    }

    /**
     * 判断 MIME 类型是否为文本类型。
     *
     * @param mimeType MIME 类型
     * @return 是否为文本类型
     */
    fun isText(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType in setOf(
                "application/json", "application/xml", "application/javascript",
                "application/typescript", "application/yaml", "application/toml",
                "application/sql", "application/x-sh"
            )
    }

    /**
     * 判断 MIME 类型是否为图片类型。
     *
     * @param mimeType MIME 类型
     * @return 是否为图片类型
     */
    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    /**
     * 判断 MIME 类型是否为视频类型。
     *
     * @param mimeType MIME 类型
     * @return 是否为视频类型
     */
    fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    /**
     * 判断 MIME 类型是否为音频类型。
     *
     * @param mimeType MIME 类型
     * @return 是否为音频类型
     */
    fun isAudio(mimeType: String): Boolean = mimeType.startsWith("audio/")

    /**
     * 判断 MIME 类型是否为应用类型。
     *
     * @param mimeType MIME 类型
     * @return 是否为应用类型
     */
    fun isApplication(mimeType: String): Boolean = mimeType.startsWith("application/")

    /**
     * 判断 MIME 类型是否为字体类型。
     *
     * @param mimeType MIME 类型
     * @return 是否为字体类型
     */
    fun isFont(mimeType: String): Boolean = mimeType.startsWith("font/")

    /**
     * 协商适合 Accept 头的最佳 MIME 类型。
     *
     * @param available 可用的 MIME 类型列表
     * @param acceptHeader Accept 头内容，如 "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
     * @return 最佳匹配的 MIME 类型，无匹配返回第一个可用类型
     */
    fun negotiate(available: List<String>, acceptHeader: String): String? {
        if (available.isEmpty()) return null
        if (acceptHeader.isBlank()) return available.first()
        val accepted = acceptHeader.split(",")
            .map { it.trim() }
            .mapNotNull { entry ->
                val parts = entry.split(";")
        val type = parts[0].trim()
        val q = parts.drop(1).firstOrNull { it.trim().startsWith("q=") }
                    ?.substringAfter("=")?.trim()?.toFloatOrNull() ?: 1.0f
                type to q
            }
            .sortedByDescending { it.second }
        for ((type, _) in accepted) {
            if (type == "*/*") return available.first()
        val typePrefix = type.substringBefore("/")
        val typeSuffix = type.substringAfter("/")
        if (type in available) return type
            val matching = available.filter {
                it.substringBefore("/") == typePrefix && typeSuffix == "*"
            }
        if (matching.isNotEmpty()) return matching.first()
        }
        return available.first()
    }

    /**
     * 获取 MIME 类型的大类描述。
     *
     * @param mimeType MIME 类型
     * @return 大类名称，如 "text", "image", "audio", "video", "application", "font"
     */
    fun getCategory(mimeType: String): String {
        return mimeType.substringBefore("/")
    }

    /**
     * 判断两个 MIME 类型是否匹配（支持通配符）。
     *
     * @param mimeType MIME 类型
     * @param pattern 匹配模式，如 "image/*"
     * @return 是否匹配
     */
    fun matches(mimeType: String, pattern: String): Boolean {
        if (pattern == "*/*" || pattern == "*") return true
        if (pattern == mimeType) return true
        val patternPrefix = pattern.substringBefore("/")
        val patternSuffix = pattern.substringAfter("/")
        if (patternSuffix == "*" && mimeType.substringBefore("/") == patternPrefix) return true
        return false
    }
}
