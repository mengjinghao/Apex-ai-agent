package com.apex.util

import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale

/**
 * NIO Path 工具类，提供路径标准化、解析、相对化、Glob 匹配等操作。
 */
object PathUtils {

    /**
     * 标准化路径，解析 "." 和 ".." 并统一分隔符。
     *
     * @param path 输入路径
     * @return 标准化后的路径
     */
    fun normalize(path: String): String {
        return Paths.get(path).normalize().toString().replace('\\', '/')
    }

    /**
     * 将路径解析为绝对路径（相对于当前工作目录）。
     *
     * @param path 输入路径
     * @return 绝对路径
     */
    fun toAbsolute(path: String): String {
        return Paths.get(path).toAbsolutePath().normalize().toString().replace('\\', '/')
    }

    /**
     * 将多个路径组件连接为一个路径。
     *
     * @param first 第一个路径组件
     * @vararg others 其他路径组件
     * @return 连接后的路径
     */
    fun resolve(first: String, vararg others: String): String {
        var result = Paths.get(first)
        for (other in others) {
            result = result.resolve(other)
        }
        return result.normalize().toString().replace('\\', '/')
    }

    /**
     * 计算从 base 路径到 target 路径的相对路径。
     *
     * @param base 基础路径
     * @param target 目标路径
     * @return 相对路径
     */
    fun relativize(base: String, target: String): String {
        val basePath = Paths.get(base).normalize()
        val targetPath = Paths.get(target).normalize()
        return basePath.relativize(targetPath).toString().replace('\\', '/')
    }

    /**
     * 判断路径是否匹配 Glob 模式。
     *
     * @param path 待匹配的路径
     * @param globPattern Glob 模式（如 "**/*.kt"）
     * @return 是否匹配
     */
    fun matches(path: String, globPattern: String): Boolean {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$globPattern")
        return matcher.matches(Paths.get(path))
    }

    /**
     * 获取文件扩展名（包含点号）。
     *
     * @param path 文件路径
     * @return 扩展名，如 ".txt"，无扩展名返回空字符串
     */
    fun getExtension(path: String): String {
        val name = Paths.get(path).fileName?.toString() ?: return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot) else ""
    }

    /**
     * 获取不带扩展名的文件名。
     *
     * @param path 文件路径
     * @return 不带扩展名的文件名
     */
    fun getNameWithoutExtension(path: String): String {
        val name = Paths.get(path).fileName?.toString() ?: return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(0, dot) else name
    }

    /**
     * 获取文件名（包含扩展名）。
     *
     * @param path 文件路径
     * @return 文件名
     */
    fun getFileName(path: String): String {
        return Paths.get(path).fileName?.toString() ?: ""
    }

    /**
     * 获取父目录路径。
     *
     * @param path 文件路径
     * @return 父目录路径，无父目录返回空字符串
     */
    fun getParent(path: String): String {
        val parent = Paths.get(path).parent
        return parent?.toString()?.replace('\\', '/') ?: ""
    }

    /**
     * 判断路径是否为绝对路径。
     *
     * @param path 输入路径
     * @return 是否为绝对路径
     */
    fun isAbsolute(path: String): Boolean {
        return Paths.get(path).isAbsolute
    }

    /**
     * 将路径中的所有分隔符统一为 Unix 风格（/）。
     *
     * @param path 输入路径
     * @return 统一后的路径
     */
    fun toUnixPath(path: String): String {
        return path.replace('\\', '/')
    }

    /**
     * 将路径中的所有分隔符统一为 Windows 风格（\）。
     *
     * @param path 输入路径
     * @return 统一后的路径
     */
    fun toWindowsPath(path: String): String {
        return path.replace('/', '\\')
    }

    /**
     * 判断路径是否指向某个目录的子路径。
     *
     * @param base 父目录路径
     * @param child 子路径
     * @return 如果 child 在 base 之下返回 true
     */
    fun isSubPath(base: String, child: String): Boolean {
        return try {
            val basePath = Paths.get(base).normalize()
        val childPath = Paths.get(child).normalize()
            childPath.startsWith(basePath)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 将路径转换为 URI 字符串。
     *
     * @param path 输入路径
     * @return URI 字符串
     */
    fun toUri(path: String): URI {
        return Paths.get(path).toUri()
    }

    /**
     * 将 URI 字符串转换为路径。
     *
     * @param uri URI 字符串
     * @return 路径字符串
     */
    fun fromUri(uri: String): String {
        return Paths.get(URI.create(uri)).toString().replace('\\', '/')
    }

    /**
     * 创建所有不存在的父目录。
     *
     * @param path 文件路径
     * @return 创建成功返回 true
     */
    fun createDirectories(path: String): Boolean {
        return try {
            Files.createDirectories(Paths.get(path))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断路径是否存在。
     *
     * @param path 输入路径
     * @return 是否存在
     */
    fun exists(path: String): Boolean {
        return Files.exists(Paths.get(path))
    }

    /**
     * 获取文件大小。
     *
     * @param path 文件路径
     * @return 文件大小（字节），获取失败返回 -1
     */
    fun size(path: String): Long {
        return try {
            Files.size(Paths.get(path))
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * 删除文件或空目录。
     *
     * @param path 待删除的路径
     * @return 删除成功返回 true
     */
    fun delete(path: String): Boolean {
        return try {
            Files.deleteIfExists(Paths.get(path))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 递归删除目录及所有子文件。
     *
     * @param path 待删除的目录路径
     * @return 删除成功返回 true
     */
    fun deleteRecursively(path: String): Boolean {
        return try {
            Files.walk(Paths.get(path))
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取文件扩展名（不含点号，小写）。
     *
     * @param path 文件路径
     * @return 扩展名，无扩展名返回空字符串
     */
    fun getExtensionLower(path: String): String {
        val ext = getExtension(path)
        return if (ext.isNotEmpty()) ext.substring(1).lowercase(Locale.ROOT) else ""
    }

    /**
     * 将路径转换为规范路径（解析符号链接）。
     *
     * @param path 输入路径
     * @return 规范路径
     */
    fun toRealPath(path: String): String {
        return try {
            Paths.get(path).toRealPath().toString().replace('\\', '/')
        } catch (_: Exception) {
            normalize(path)
        }
    }

    /**
     * 获取路径中的每个组件。
     *
     * @param path 输入路径
     * @return 路径组件列表
     */
    fun pathComponents(path: String): List<String> {
        val components = mutableListOf<String>()
        Paths.get(path).forEach { components.add(it.toString()) }
        return components
    }

    /**
     * 在路径末尾添加指定字符串，自动插入分隔符。
     *
     * @param base 基础路径
     * @param toAppend 要追加的路径段
     * @return 拼接后的路径
     */
    fun appendPath(base: String, toAppend: String): String {
        val baseStr = base.trimEnd('/', '\\')
        val appendStr = toAppend.trimStart('/', '\\')
        return "$baseStr/$appendStr"
    }
}
