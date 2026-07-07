package com.apex.util

import android.content.Context
import android.net.Uri
import com.apex.util.AppLogger
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.DigestInputStream
import java.text.DecimalFormat
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object FileUtils {

    private const val TAG = "FileUtils"
    private const val BACKGROUND_IMAGES_DIR = "background_images"
    private const val BACKGROUND_VIDEOS_DIR = "background_videos"

    // List of common video file extensions
    private val VIDEO_EXTENSIONS = listOf("mp4", "3gp", "webm", "mkv", "avi", "mov", "flv", "wmv")

    private val TEXT_BASED_EXTENSIONS = setOf(
        "txt", "md", "log", "ini", "env", "csv", "tsv", "text", "me",
        "html", "htm", "css", "js", "json", "xml", "yaml", "yml", "svg", "url",
        "sass", "scss", "less", "ejs", "hbs", "pug", "rss", "atom", "vtt", "webmanifest", "jsp", "asp", "aspx",
        "java", "kt", "kts", "gradle",
        "c", "cpp", "h", "hpp", "cs", "m",
        "py", "rb", "php", "go", "swift",
        "ts", "tsx", "jsx",
        "sh", "bat", "ps1", "zsh",
        "sql", "groovy", "lua", "perl", "pl", "r", "dart", "rust", "rs", "scala",
        "asm", "pas", "f", "f90", "for", "lisp", "hs", "erl", "vb", "vbs", "tcl", "d", "nim", "sol", "zig", "vala", "cob", "cbl",
        "properties", "toml", "dockerfile", "gitignore", "gitattributes", "editorconfig", "conf", "cfg",
        "jsonc", "json5", "reg", "iml", "inf",
        "rtf", "tex", "srt", "sub", "asciidoc", "adoc", "rst", "org", "wiki", "mediawiki",
        "vcf", "ics", "gpx", "kml", "opml"
    )

    private val TEXT_BASED_FILENAMES = setOf(
        "readme", "makefile", "dockerfile", "license", "changelog", "authors",
        "contributors", "copying", "install", "news", "todo", "version",
        "gemfile", "rakefile", "vagrantfile", "buildfile"
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif", "heic", "heif")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "aac", "wma", "m4a", "opus")
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "tex", "epub")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst")

    /**
     * Checks if a file extension corresponds to a text-based file format.
     * @param extension The file extension without the dot (e.g., "txt", "java").
     * @return True if the extension is for a known text-based file, false otherwise.
     */
    fun isTextBasedExtension(extension: String): Boolean {
        return extension.lowercase() in TEXT_BASED_EXTENSIONS
    }

    fun isTextBasedFileName(fileName: String): Boolean {
        val name = fileName.trim()
        if (name.isBlank()) return false

        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return if (ext.isBlank() || ext == name) {
            name.lowercase() in TEXT_BASED_FILENAMES
        } else {
            isTextBasedExtension(ext)
        }
    }

    /**
     * Checks if a file appears to be text-like by reading its first few bytes.
     * This is more reliable than extension checking as it analyzes actual content.
     *
     * @param file The file to check
     * @param sampleSize Number of bytes to read for analysis (default: 512)
     * @return True if the file appears to contain text, false otherwise
     */
    fun isTextLike(file: File, sampleSize: Int = 512): Boolean {
        if (!file.exists() || !file.canRead()) {
            return false
        }

        if (file.length() == 0L) {
            return true
        }

        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(minOf(sampleSize.toLong(), file.length()).toInt())
                val bytesRead = input.read(buffer)

                if (bytesRead <= 0) {
                    return true
                }

                return isTextLikeBytes(buffer, bytesRead)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking if file is text-like: ${file.path}", e)
            return false
        }
    }

    /**
     * Checks if a file appears to be text-like by reading its first few bytes from a path.
     *
     * @param path The file path to check
     * @param sampleSize Number of bytes to read for analysis (default: 512)
     * @return True if the file appears to contain text, false otherwise
     */
    fun isTextLike(path: String, sampleSize: Int = 512): Boolean {
        return isTextLike(File(path), sampleSize)
    }

    /**
     * Check if the given byte array appears to be text content.
     */
    fun isTextLike(bytes: ByteArray): Boolean {
        return isTextLikeBytes(bytes, bytes.size)
    }

    private fun isTextLikeBytes(bytes: ByteArray, length: Int): Boolean {
        if (length == 0) return true

        var textChars = 0
        var nonTextChars = 0

        var i = 0
        while (i < length) {
            val byte = bytes[i].toInt() and 0xFF

            when {
                byte >= 32 && byte <= 126 || byte == 9 || byte == 10 || byte == 13 -> {
                    textChars++
                    i++
                }
                byte >= 0xC2 && byte <= 0xDF -> {
                    if (i + 1 < length && (bytes[i + 1].toInt() and 0xC0 == 0x80)) {
                        textChars += 2
                        i += 2
                    } else {
                        nonTextChars++
                        i++
                    }
                }
                byte >= 0xE0 && byte <= 0xEF -> {
                    if (i + 2 < length && (bytes[i + 1].toInt() and 0xC0 == 0x80) && (bytes[i + 2].toInt() and 0xC0 == 0x80)) {
                        textChars += 3
                        i += 3
                    } else {
                        nonTextChars++
                        i++
                    }
                }
                byte >= 0xF0 && byte <= 0xF4 -> {
                    if (i + 3 < length && (bytes[i + 1].toInt() and 0xC0 == 0x80) && (bytes[i + 2].toInt() and 0xC0 == 0x80) && (bytes[i + 3].toInt() and 0xC0 == 0x80)) {
                        textChars += 4
                        i += 4
                    } else {
                        nonTextChars++
                        i++
                    }
                }
                else -> {
                    nonTextChars++
                    i++
                }
            }
        }

        if (nonTextChars == 0) return true
        val totalChars = textChars + nonTextChars
        if (totalChars == 0) return true
        return (nonTextChars.toDouble() / totalChars) < 0.1
    }

    /**
     * Checks if a file is a text-based file, considering both extension and filename.
     */
    fun isTextBasedFile(file: File): Boolean {
        val extension = file.extension
        return if (extension.isEmpty()) {
            file.name.lowercase() in TEXT_BASED_FILENAMES
        } else {
            isTextBasedExtension(extension)
        }
    }

    /**
     * Checks if a file is a valid workspace file to be shown or backed up.
     */
    fun isWorkspaceFile(file: File, workspaceRoot: File, gitignoreRules: List<String>): Boolean {
        if (!file.isFile) return false

        if (com.apex.ui.features.chat.webview.workspace.process.GitIgnoreFilter.shouldIgnore(file, workspaceRoot, gitignoreRules)) {
            return false
        }

        return isTextBasedFile(file)
    }

    /**
     * Check if a URI points to a video file
     */
    fun isVideoFile(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri) ?: return false
        if (mimeType.startsWith("video/")) return true

        val extension = getFileExtension(context, uri)
        return extension != null && extension.lowercase() in VIDEO_EXTENSIONS
    }

    /**
     * Get the file extension from a URI
     */
    fun getFileExtension(context: Context, uri: Uri): String? {
        val mimeType = context.contentResolver.getType(uri)
        return if (mimeType != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        } else {
            val path = uri.path
            return path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Copies a file from a given URI to the app's internal storage.
     */
    suspend fun copyFileToInternalStorage(context: Context, uri: Uri, uniqueName: String): Uri? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                AppLogger.e("FileUtils", "Failed to open input stream for URI: $uri")
                return@withContext null
            }

            val originalExtension = getFileExtensionFromUri(context, uri) ?: "dat"
            val file = File(context.filesDir, "${uniqueName}_${UUID.randomUUID()}.${originalExtension}")
            outputStream = FileOutputStream(file)

            val buffer = ByteArray(4 * 1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            outputStream.flush()

            AppLogger.d("FileUtils", "File copied successfully to internal storage: ${file.absolutePath}")
            return@withContext Uri.fromFile(file)
        } catch (e: Exception) {
            AppLogger.e("FileUtils", "Error copying file to internal storage", e)
            return@withContext null
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: Exception) {
                AppLogger.e("FileUtils", "Error closing streams", e)
            }
        }
    }

    private suspend fun getFileExtensionFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val uriPath = uri.path
        if (uriPath != null) {
            val pathExtension = uriPath.substringAfterLast('.', "")
            if (pathExtension.isNotEmpty() && pathExtension.length <= 10 && !pathExtension.contains('/')) {
                return@withContext pathExtension.lowercase()
            }
        }

        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (extension != null) {
                return@withContext extension.lowercase()
            }
        }

        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val fileName = cursor.getString(nameIndex)
                    val fileExtension = fileName?.substringAfterLast('.', "")
                    if (!fileExtension.isNullOrEmpty() && fileExtension.length <= 10) {
                        return@withContext fileExtension.lowercase()
                    }
                }
            }
        }

        return@withContext null
    }

    private fun cleanOldBackgroundFiles(directory: File, currentFileName: String) {
        try {
            val files = directory.listFiles()
            if (files != null && files.size > 1) {
                files.forEach { file ->
                    if (file.name != currentFileName) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cleaning old background files", e)
        }
    }

    fun checkVideoSize(context: Context, uri: Uri, maxSizeMB: Int = 30): Boolean {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fileSize = pfd.statSize
                val maxSizeBytes = maxSizeMB * 1024 * 1024L
                return fileSize <= maxSizeBytes
            }
        } catch (e: Exception) {
            AppLogger.e("FileUtils", "检查视频大小时出错", e)
        }
        return true
    }

    // ==================== 新增方法 ====================

    /**
     * 安全读取文件内容，失败时返回 null。
     *
     * @param file 文件
     * @param charset 字符编码，默认 UTF-8
     * @return 文件内容，读取失败返回 null
     */
    fun readTextSafe(file: File, charset: Charset = Charsets.UTF_8): String? {
        return try {
            file.readText(charset)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 安全写入文件（先写临时文件再重命名，确保原子性）。
     *
     * @param file 目标文件
     * @param text 待写入的文本
     * @param charset 字符编码，默认 UTF-8
     * @return 写入成功返回 true
     */
    fun writeTextSafe(file: File, text: String, charset: Charset = Charsets.UTF_8): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, ".${file.name}.tmp")
            tempFile.writeText(text, charset)
            tempFile.renameTo(file)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 追加文本到文件末尾。
     *
     * @param file 目标文件
     * @param text 待追加的文本
     * @param charset 字符编码，默认 UTF-8
     * @return 追加成功返回 true
     */
    fun appendText(file: File, text: String, charset: Charset = Charsets.UTF_8): Boolean {
        return try {
            file.appendText(text, charset)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 安全复制文件，带校验。
     *
     * @param source 源文件
     * @param target 目标文件
     * @param overwrite 是否覆盖
     * @return 复制成功返回 true
     */
    fun copyToSafe(source: File, target: File, overwrite: Boolean = false): Boolean {
        if (!source.exists()) return false
        if (target.exists() && !overwrite) return false
        return try {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 移动文件。
     *
     * @param source 源文件
     * @param target 目标文件
     * @param overwrite 是否覆盖
     * @return 移动成功返回 true
     */
    fun moveTo(source: File, target: File, overwrite: Boolean = false): Boolean {
        if (!source.exists()) return false
        if (target.exists() && !overwrite) return false
        return try {
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            source.renameTo(target)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取文件扩展名。
     *
     * @param file 文件
     * @return 扩展名（不含点号）
     */
    fun extension(file: File): String = file.extension

    /**
     * 获取不带扩展名的文件名。
     *
     * @param file 文件
     * @return 文件名（不含扩展名）
     */
    fun nameWithoutExtension(file: File): String {
        val name = file.name
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(0, dot) else name
    }

    /**
     * 获取含扩展名的文件名。
     *
     * @param file 文件
     * @return 文件名
     */
    fun nameWithExtension(file: File): String = file.name

    /**
     * 判断文件是否为图片。
     */
    fun isImage(file: File): Boolean = file.extension.lowercase() in IMAGE_EXTENSIONS

    /**
     * 判断文件是否为视频。
     */
    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO_EXTENSIONS

    /**
     * 判断文件是否为音频。
     */
    fun isAudio(file: File): Boolean = file.extension.lowercase() in AUDIO_EXTENSIONS

    /**
     * 判断文件是否为文档。
     */
    fun isDocument(file: File): Boolean = file.extension.lowercase() in DOCUMENT_EXTENSIONS

    /**
     * 判断文件是否为压缩包。
     */
    fun isArchive(file: File): Boolean = file.extension.lowercase() in ARCHIVE_EXTENSIONS

    /**
     * 获取文件的 MIME 类型。
     *
     * @param file 文件
     * @return MIME 类型字符串
     */
    fun mimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "tar" -> "application/x-tar"
            "gz", "gzip" -> "application/gzip"
            else -> "application/octet-stream"
        }
    }

    /**
     * 获取人类可读的文件大小。
     *
     * @param file 文件
     * @return 格式化后的大小字符串
     */
    fun sizeFormatted(file: File): String {
        val bytes = file.length()
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var unitIndex = 0
        var size = bytes.toDouble()
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return DecimalFormat("#.##").format(size) + " " + units[unitIndex]
    }

    /**
     * 计算文件的 SHA-256 校验和。
     *
     * @param file 文件
     * @return 十六进制 SHA-256 字符串
     */
    fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(FileInputStream(file), digest).use { input ->
            val buffer = ByteArray(8192)
            while (input.read(buffer) >= 0) { /* consume */ }
        }
        return CryptoUtils.byteArrayToHex(digest.digest())
    }

    /**
     * 递归列出目录中所有文件。
     *
     * @param dir 目录
     * @param filter 可选的文件过滤器
     * @return 文件列表
     */
    fun listFilesRecursive(dir: File, filter: ((File) -> Boolean)? = null): List<File> {
        if (!dir.isDirectory) return if (filter?.invoke(dir) != false && dir.isFile) listOf(dir) else emptyList()
        val result = mutableListOf<File>()
        dir.walkTopDown().forEach { file ->
            if (file.isFile && (filter == null || filter(file))) {
                result.add(file)
            }
        }
        return result
    }

    /**
     * 根据文件名模式查找文件。
     *
     * @param dir 搜索目录
     * @param namePattern 文件名 Glob 模式
     * @param maxDepth 最大深度
     * @return 匹配的文件列表
     */
    fun findFiles(dir: File, namePattern: String, maxDepth: Int = Int.MAX_VALUE): List<File> {
        if (!dir.isDirectory) return emptyList()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:**/$namePattern")
        val result = mutableListOf<File>()
        try {
            Files.walkFileTree(dir.toPath(), setOf(java.nio.file.FileVisitOption.FOLLOW_LINKS), maxDepth,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (matcher.matches(file)) {
                            result.add(file.toFile())
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
        } catch (_: Exception) { }
        return result
    }

    /**
     * 统计文件行数。
     *
     * @param file 文本文件
     * @return 行数，失败返回 -1
     */
    fun countLines(file: File): Int {
        if (!file.isFile) return -1
        return try {
            file.readLines().size
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 读取文件末尾 N 行。
     *
     * @param file 文件
     * @param n 行数
     * @return 最后 N 行
     */
    fun tail(file: File, n: Int): List<String> {
        if (!file.isFile || n <= 0) return emptyList()
        return try {
            file.readLines().takeLast(n.coerceAtMost(Int.MAX_VALUE))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取文件开头 N 行。
     *
     * @param file 文件
     * @param n 行数
     * @return 前 N 行
     */
    fun head(file: File, n: Int): List<String> {
        if (!file.isFile || n <= 0) return emptyList()
        return try {
            file.readLines().take(n)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 创建文件或更新修改时间。
     *
     * @param file 文件
     * @return 文件对象
     */
    fun touch(file: File): File {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        return file
    }

    /**
     * 判断文件是否为空。
     *
     * @param file 文件
     * @return 若不存在或大小为 0 返回 true
     */
    fun isEmpty(file: File): Boolean = !file.exists() || file.length() == 0L

    /**
     * 确保文件的父目录存在。
     *
     * @param file 文件
     * @return 文件对象
     */
    fun ensureDirectoryExists(file: File): File {
        file.parentFile?.mkdirs()
        return file
    }

    /**
     * 分割文件为多个部分。
     *
     * @param file 源文件
     * @param maxSize 每部分最大字节数
     * @return 分割后的文件列表
     */
    fun split(file: File, maxSize: Long): List<File> {
        if (!file.exists() || file.length() <= maxSize) return listOf(file)
        val parts = mutableListOf<File>()
        val buffer = ByteArray(8192)
        var partIndex = 0
        try {
            file.inputStream().use { input ->
                var output: FileOutputStream? = null
                var currentSize = 0L
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) {
                    if (output == null || currentSize >= maxSize) {
                        output?.close()
                        val partFile = File(file.parentFile, "${file.name}.part${String.format("%03d", partIndex++)}")
                        output = FileOutputStream(partFile)
                        parts.add(partFile)
                        currentSize = 0L
                    }
                    output?.write(buffer, 0, read)
                    currentSize += read
                }
                output?.close()
            }
        } catch (_: Exception) { }
        return parts
    }

    /**
     * 合并分割的文件。
     *
     * @param parts 分割文件列表
     * @param target 合并后的目标文件
     * @return 合并成功返回 true
     */
    fun join(parts: List<File>, target: File): Boolean {
        return try {
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                for (part in parts.sortedBy { it.name }) {
                    part.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * GZIP 压缩文件。
     *
     * @param source 源文件
     * @param target 目标文件
     * @return 压缩成功返回 true
     */
    fun compressTo(source: File, target: File): Boolean {
        return try {
            target.parentFile?.mkdirs()
            GZIPOutputStream(FileOutputStream(target)).use { gzip ->
                source.inputStream().use { input -> input.copyTo(gzip) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * GZIP 解压文件。
     *
     * @param source 源文件（.gz）
     * @param target 目标文件
     * @return 解压成功返回 true
     */
    fun decompressTo(source: File, target: File): Boolean {
        return try {
            target.parentFile?.mkdirs()
            GZIPInputStream(FileInputStream(source)).use { gzip ->
                target.outputStream().use { output -> gzip.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取文件锁。
     *
     * @param file 文件
     * @param shared 是否为共享锁
     * @return FileLock 对象，需在 finally 中释放
     */
    fun lock(file: File, shared: Boolean = false): FileLock? {
        return try {
            val raf = RandomAccessFile(file, if (shared) "r" else "rw")
            raf.channel.lock(0L, Long.MAX_VALUE, shared)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 释放文件锁。
     *
     * @param lock 文件锁
     */
    fun unlock(lock: FileLock?) {
        try {
            lock?.release()
            lock?.channel()?.close()
        } catch (_: Exception) { }
    }

    /**
     * 将文件转换为 URI。
     *
     * @param file 文件
     * @return URI 字符串
     */
    fun toUri(file: File): String = file.toURI().toString()

    /**
     * 计算两个文件的二进制差异。
     *
     * @param a 第一个文件
     * @param b 第二个文件
     * @return 差异字节数，-1 表示出错
     */
    fun differenceWith(a: File, b: File): Long {
        if (!a.exists() || !b.exists()) return -1
        if (a.length() != b.length()) return a.length().coerceAtLeast(b.length()) - a.length().coerceAtMost(b.length())
        return try {
            val bytesA = a.readBytes()
            val bytesB = b.readBytes()
            var diffCount = 0L
            for (i in bytesA.indices) {
                if (bytesA[i] != bytesB[i]) diffCount++
            }
            diffCount
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 判断文件是否为符号链接。
     *
     * @param file 文件
     * @return 是否为符号链接
     */
    fun isSymlink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取文件的真实路径（解析符号链接）。
     *
     * @param file 文件
     * @return 真实路径
     */
    fun realPath(file: File): String {
        return try {
            file.toPath().toRealPath().toString()
        } catch (_: Exception) {
            file.absolutePath
        }
    }

    /**
     * 修改文件权限（Unix 系统）。
     *
     * @param file 文件
     * @param mode 权限模式，如 "755"
     * @return 成功返回 true
     */
    fun chmod(file: File, mode: String): Boolean {
        return try {
            val permissions = mutableSetOf<PosixFilePermission>()
            val owner = mode[0].digitToInt()
            val group = mode[1].digitToInt()
            val other = mode[2].digitToInt()
            if (owner and 4 != 0) permissions.add(PosixFilePermission.OWNER_READ)
            if (owner and 2 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
            if (owner and 1 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
            if (group and 4 != 0) permissions.add(PosixFilePermission.GROUP_READ)
            if (group and 2 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
            if (group and 1 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
            if (other and 4 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
            if (other and 2 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
            if (other and 1 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(file.toPath(), permissions)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取文件所有者。
     *
     * @param file 文件
     * @return 所有者名称，失败返回 null
     */
    fun getOwner(file: File): String? {
        return try {
            Files.getOwner(file.toPath())?.name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 设置文件所有者。
     *
     * @param file 文件
     * @param user 用户名
     * @return 成功返回 true
     */
    fun setOwner(file: File, user: String): Boolean {
        return try {
            Files.setOwner(file.toPath(), java.nio.file.attribute.UserPrincipalLookupService.`in`?.let {
                it.lookupPrincipalByName(user)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取文件组。
     *
     * @param file 文件
     * @return 组名，失败返回 null
     */
    fun getGroup(file: File): String? {
        return try {
            Files.getAttribute(file.toPath(), "unix:group")?.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 设置文件组。
     *
     * @param file 文件
     * @param group 组名
     * @return 成功返回 true
     */
    fun setGroup(file: File, group: String): Boolean {
        return try {
            Files.setAttribute(file.toPath(), "unix:group", group)
            true
        } catch (_: Exception) {
            false
        }
    }
}
