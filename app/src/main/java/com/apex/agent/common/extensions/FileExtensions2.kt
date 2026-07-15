package com.apex.agent.common.extensions

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * [File]、[Path]、[InputStream]、[OutputStream] 的高级扩展函数集合。
 */

// ==================== File 扩展 ====================

/**
 * 安全地将文件内容读取为字符串，失败时返回 null。
 *
 * @param charset 字符编码，默认 UTF-8
 * @return 文件内容，读取失败返回 null
 */
fun File.readTextSafe(charset: Charset = Charsets.UTF_8): String? {
    return try {
        readText(charset)
    } catch (_: Exception) {
        null
    }
}

/**
 * 安全地将字符串写入文件（原子写入：先写临时文件再重命名）。
 *
 * @param text 待写入的文本
 * @param charset 字符编码，默认 UTF-8
 * @return 写入成功返回 true
 */
fun File.writeTextSafe(text: String, charset: Charset = Charsets.UTF_8): Boolean {
    return try {
        val tempFile = File(parentFile, ".${name}.tmp")
        tempFile.writeText(text, charset)
        tempFile.renameTo(this)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 将字节安全地写入文件（原子写入）。
 *
 * @param bytes 待写入的字节数组
 * @return 写入成功返回 true
 */
fun File.writeBytesSafe(bytes: ByteArray): Boolean {
    return try {
        val tempFile = File(parentFile, ".${name}.tmp")
        tempFile.writeBytes(bytes)
        tempFile.renameTo(this)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 复制文件到目标位置，并验证校验和。
 *
 * @param target 目标文件
 * @param overwrite 是否覆盖已存在的文件
 * @param verify 是否验证复制结果
 * @return 复制成功返回 true
 */
fun File.copyToSafe(target: File, overwrite: Boolean = false, verify: Boolean = true): Boolean {
    if (!exists()) return false
    if (target.exists() && !overwrite) return false
    return try {
        val checksumBefore = if (verify) sha256() else null
        copyTo(target, overwrite)
        if (verify && checksumBefore != null) {
            checksumBefore == target.sha256()
        } else true
    } catch (_: Exception) {
        false
    }
}

/**
 * 移动文件到目标位置。
 *
 * @param target 目标文件
 * @param overwrite 是否覆盖已存在的文件
 * @return 移动成功返回 true
 */
fun File.moveTo(target: File, overwrite: Boolean = false): Boolean {
    if (!exists()) return false
    if (target.exists() && !overwrite) return false
    return try {
        if (target.exists()) target.delete()
        renameTo(target)
    } catch (_: Exception) {
        false
    }
}

/**
 * 判断文件是否为常见图片格式。
 */
val File.isImage: Boolean
    get() = extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif", "heic", "heif")

/**
 * 判断文件是否为常见视频格式。
 */
val File.isVideo: Boolean
    get() = extension.lowercase() in setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "mpeg", "mpg", "3gp")

/**
 * 判断文件是否为常见音频格式。
 */
val File.isAudio: Boolean
    get() = extension.lowercase() in setOf("mp3", "wav", "ogg", "flac", "aac", "wma", "m4a", "opus", "mid", "midi")

/**
 * 判断文件是否为常见文档格式。
 */
val File.isDocument: Boolean
    get() = extension.lowercase() in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "tex", "epub", "mobi")

/**
 * 判断文件是否为常见压缩格式。
 */
val File.isArchive: Boolean
    get() = extension.lowercase() in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "lz4", "iso")

/**
 * 获取文件的 MIME 类型（基于扩展名猜测）。
 */
val File.mimeType: String
    get() {
        val ext = extension.lowercase()
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
            "gz" -> "application/gzip"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "md" -> "text/markdown"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

/**
 * 计算文件的 SHA-256 校验和。
 *
 * @return SHA-256 十六进制字符串
 */
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            digest.update(buffer, 0, read)
        }
    }
        return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 计算文件的 MD5 校验和。
 *
 * @return MD5 十六进制字符串
 */
fun File.md5(): String {
    val digest = MessageDigest.getInstance("MD5")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            digest.update(buffer, 0, read)
        }
    }
        return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 递归列出目录中所有符合条件的文件。
 *
 * @param filter 文件过滤器，默认包含所有文件
 * @return 文件列表
 */
fun File.listFilesRecursive(filter: ((File) -> Boolean)? = null): List<File> {
    if (!isDirectory) return if (filter?.invoke(this) != false) listOf(this) else emptyList()
        val result = mutableListOf<File>()
    walkTopDown().forEach { file ->
        if (file.isFile && (filter == null || filter(file))) {
            result.add(file)
        }
    }
        return result
}

/**
 * 查找匹配 glob 模式的文件（支持 **、*、? 通配符）。
 *
 * @param pattern 文件名 glob 模式
 * @param maxDepth 最大递归深度，默认不限
 * @return 匹配的文件列表
 */
fun File.findFiles(pattern: String, maxDepth: Int = Int.MAX_VALUE): List<File> {
    if (!isDirectory) return emptyList()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:**/$pattern")
        val result = mutableListOf<File>()
    Files.walkFileTree(toPath(), setOf(java.nio.file.FileVisitOption.FOLLOW_LINKS), maxDepth,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (matcher.matches(file)) {
                    result.add(file.toFile())
                }
        return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return result
}

/**
 * 高效统计文本文件行数。
 *
 * @return 行数，失败返回 -1
 */
fun File.countLines(): Int {
    if (!exists() || !isFile) return -1
    return try {
        readLines().size
    } catch (_: Exception) {
        -1
    }
}

/**
 * 读取文件的最后 N 行。
 *
 * @param n 行数
 * @return 最后 N 行列表
 */
fun File.tail(n: Int): List<String> {
    if (!exists() || n <= 0) return emptyList()
    try {
        val lines = readLines()
        return lines.takeLast(n.coerceAtMost(lines.size))
    } catch (_: Exception) {
        return emptyList()
    }
}

/**
 * 读取文件的前 N 行。
 *
 * @param n 行数
 * @return 前 N 行列表
 */
fun File.head(n: Int): List<String> {
    if (!exists() || n <= 0) return emptyList()
    try {
        val lines = readLines()
        return lines.take(n.coerceAtMost(lines.size))
    } catch (_: Exception) {
        return emptyList()
    }
}

/**
 * 创建文件（如不存在）并更新最后修改时间。
 *
 * @return 文件对象
 */
fun File.touch(): File {
    if (!exists()) {
        parentFile?.mkdirs()
        createNewFile()
    } else {
        setLastModified(System.currentTimeMillis())
    }
        return this
}

/**
 * 判断文件是否为空（不存在或大小为 0）。
 */
fun File.isEmpty(): Boolean = !exists() || length() == 0L

/**
 * 确保父目录存在。
 *
 * @return 文件对象
 */
fun File.ensureDirectoryExists(): File {
    parentFile?.mkdirs()
        return this
}

/**
 * 将文件按指定大小分割为多个部分。
 *
 * @param maxSize 每部分的最大字节数
 * @return 分割后的文件列表
 */
fun File.splitBySize(maxSize: Long): List<File> {
    if (!exists() || length() <= maxSize) return listOf(this)
        val parts = mutableListOf<File>()
        val buffer = ByteArray(8192)
        var partIndex = 0
    inputStream().use { input ->
        var output: FileOutputStream? = null
        var currentSize = 0L
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            if (output == null || currentSize >= maxSize) {
                output?.close()
        val partFile = File(parentFile, "${name}.part${String.format("%03d", partIndex++)}")
                output = FileOutputStream(partFile)
                parts.add(partFile)
                currentSize = 0L
            }
            output?.write(buffer, 0, read)
            currentSize += read
        }
        output?.close()
    }
        return parts
}

/**
 * 使用 GZIP 压缩文件到目标位置。
 *
 * @param target 目标文件
 * @return 压缩成功返回 true
 */
fun File.compressTo(target: File): Boolean {
    return try {
        target.parentFile?.mkdirs()
        GZIPOutputStream(FileOutputStream(target)).use { gzip ->
            inputStream().use { input ->
                input.copyTo(gzip)
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 使用 GZIP 解压文件到目标位置。
 *
 * @param target 目标文件
 * @return 解压成功返回 true
 */
fun File.decompressTo(target: File): Boolean {
    return try {
        target.parentFile?.mkdirs()
        GZIPInputStream(FileInputStream(this)).use { gzip ->
            target.outputStream().use { output ->
                gzip.copyTo(output)
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 获取文件锁。
 *
 * @param shared 是否为共享锁（读锁）
 * @return 文件锁对象，需在 finally 中释放
 */
fun File.lock(shared: Boolean = false): FileLock? {
    return try {
        val channel = RandomAccessFile(this, if (shared) "r" else "rw").channel
        channel.lock(0L, Long.MAX_VALUE, shared)
    } catch (_: Exception) {
        null
    }
}

/**
 * 将文件路径转换为 URI。
 */
val File.uri: URI
    get() = toURI()

/**
 * 判断文件是否为符号链接。
 */
val File.isSymlink: Boolean
    get() = try {
        Files.isSymbolicLink(toPath())
    } catch (_: Exception) {
        false
    }

/**
 * 获取符号链接的真实路径。
 *
 * @return 真实路径，非符号链接则返回自身
 */
val File.realPath: File
    get() = try {
        toPath().toRealPath().toFile()
    } catch (_: Exception) {
        this
    }

/**
 * 修改文件权限（仅 Unix/Linux 系统）。
 *
 * @param mode 权限模式，如 "755"
 * @return 修改成功返回 true
 */
fun File.chmod(mode: String): Boolean {
    return try {
        val permissions = mutableSetOf<PosixFilePermission>()
        val ownerBits = mode[0].digitToInt()
        val groupBits = mode[1].digitToInt()
        val otherBits = mode[2].digitToInt()
        if (ownerBits and 4 != 0) permissions.add(PosixFilePermission.OWNER_READ)
        if (ownerBits and 2 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
        if (ownerBits and 1 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
        if (groupBits and 4 != 0) permissions.add(PosixFilePermission.GROUP_READ)
        if (groupBits and 2 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
        if (groupBits and 1 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
        if (otherBits and 4 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
        if (otherBits and 2 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
        if (otherBits and 1 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(toPath(), permissions)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 获取文件的所有者。
 *
 * @return 所有者名称，获取失败返回 null
 */
fun File.getOwner(): String? {
    return try {
        Files.getOwner(toPath())?.name
    } catch (_: Exception) {
        null
    }
}

/**
 * 获取文件的组。
 *
 * @return 组名，获取失败返回 null
 */
fun File.getGroup(): String? {
    return try {
        Files.getAttribute(toPath(), "unix:group")?.toString()
    } catch (_: Exception) {
        null
    }
}

// ==================== Path 扩展 ====================

/**
 * 将 Path 转换为 File。
 */
fun Path.toFile(): File = this.toFile()

/**
 * 读取 Path 内容的字符串表示。
 *
 * @param charset 字符编码，默认 UTF-8
 * @return 文件内容
 */
fun Path.readTextSafe(charset: Charset = Charsets.UTF_8): String? {
    return try {
        readText(charset)
    } catch (_: Exception) {
        null
    }
}

/**
 * 将字符串写入 Path。
 *
 * @param text 待写入的文本
 * @param charset 字符编码，默认 UTF-8
 */
fun Path.writeTextSafe(text: String, charset: Charset = Charsets.UTF_8): Boolean {
    return try {
        writeText(text, charset)
        true
    } catch (_: Exception) {
        false
    }
}

// ==================== InputStream / OutputStream 扩展 ====================

/**
 * 读取 InputStream 的全部内容为字符串。
 *
 * @param charset 字符编码，默认 UTF-8
 * @return 字符串内容
 */
fun InputStream.readText(charset: Charset = Charsets.UTF_8): String {
    return use { it.readBytes().toString(charset) }
}

/**
 * 将字符串写入 OutputStream。
 *
 * @param text 待写入的文本
 * @param charset 字符编码，默认 UTF-8
 */
fun OutputStream.writeText(text: String, charset: Charset = Charsets.UTF_8) {
    use { it.write(text.toByteArray(charset)) }
}

/**
 * 将 InputStream 的内容写入 File。
 *
 * @param file 目标文件
 * @return 写入的字节数
 */
fun InputStream.copyTo(file: File): Long {
    return file.outputStream().use { output ->
        this.copyTo(output)
    }
}

/**
 * 从 File 读取内容到 OutputStream。
 *
 * @param file 源文件
 */
fun OutputStream.readFrom(file: File) {
    file.inputStream().use { input ->
        input.copyTo(this)
    }
}
