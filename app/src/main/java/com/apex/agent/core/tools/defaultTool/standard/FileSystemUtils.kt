package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.util.AppLogger
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.apex.agent.util.FileUtils
import com.apex.agent.util.SyntaxCheckUtil
import com.apex.agent.util.PathMapper
import com.apex.agent.util.ImagePoolManager
import com.apex.agent.util.MediaPoolManager
import com.apex.agent.util.HttpMultiPartDownloader
import com.apex.agent.util.FFmpegUtil
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap

object FileSystemUtils {
    private const val TAG = "FileSystemUtils"

    // 特殊文件类型扩展名列表（需要特殊处理提取文本的文件类型�?
    val SPECIAL_FILE_EXTENSIONS = listOf(
        "doc", "docx",      // Word documents
        "pdf",              // PDF documents
        "jpg", "jpeg",      // Image files
        "png", "gif", "bmp",
        "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus",
        "mp4", "mkv", "mov", "webm", "avi", "m4v"
    )

    /** 添加行号到内容字符串 */
    fun addLineNumbers(content: String): String {
        val lines = content.lines()
        if (lines.isEmpty()) return ""
        val maxDigits = lines.size.toString().length
        return lines.mapIndexed { index, line ->
            "${(index + 1).toString().padStart(maxDigits, ' ')}| ${line}"
        }.joinToString("\n")
    }

    /** 添加行号到内容字符串，从指定行号开�?*/
    fun addLineNumbers(content: String, startLine: Int, totalLines: Int): String {
        val lines = content.lines()
        if (lines.isEmpty()) return ""
        val maxDigits = if (totalLines > 0) totalLines.toString().length else lines.size.toString().length
        return lines.mapIndexed { index, line ->
            "${(startLine + index + 1).toString().padStart(maxDigits, ' ')}| ${line}"
        }.joinToString("\n")
    }

    /** 判断是否为需要特殊处理的文件类型 */
    fun isSpecialFileType(fileExtension: String): Boolean {
        return fileExtension.lowercase() in SPECIAL_FILE_EXTENSIONS
    }

    /** 统计文件总行�?*/
    fun countFileLines(file: File): Int {
        var totalLines = 0
        file.bufferedReader().use {
            while (it.readLine() != null) {
                totalLines++
            }
        }
        return totalLines
    }

    /** 从文件中读取指定范围的行 */
    fun readLinesFromFile(file: File, startLine: Int, endLine: Int): String {
        val partContent = StringBuilder()
        var currentLine = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach {
                if (currentLine >= endLine) return@useLines
                if (currentLine >= startLine) {
                    partContent.append(it).append('\n')
                }
                currentLine++
            }
        }
        // Remove last newline if content is not empty
        if (partContent.isNotEmpty()) {
            partContent.setLength(partContent.length - 1)
        }
        return partContent.toString()
    }

    /** 压缩文件或目�?*/
    fun zipFiles(source: File, destination: File): Boolean {
        try {
            ZipOutputStream(FileOutputStream(destination)).use { zos ->
                fun addFileToZip(file: File, basePath: String) {
                    val entryName = if (basePath.isBlank()) file.name else "${basePath}/${file.name}"
                    val entry = ZipEntry(entryName)
                    zos.putNextEntry(entry)
                    
                    if (file.isFile) {
                        FileInputStream(file).use { fis ->
                            val buffer = ByteArray(1024)
                            var length: Int
                            while (fis.read(buffer).also { length = it } > 0) {
                                zos.write(buffer, 0, length)
                            }
                        }
                    } else if (file.isDirectory) {
                        file.listFiles()?.forEach {
                            addFileToZip(it, entryName)
                        }
                    }
                    
                    zos.closeEntry()
                }
                
                addFileToZip(source, "")
            }
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error zipping files", e)
            return false
        }
    }

    /** 解压文件 */
    fun unzipFiles(source: File, destination: File): Boolean {
        try {
            if (!destination.exists()) {
                destination.mkdirs()
            }
            
            ZipInputStream(FileInputStream(source)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val entryFile = File(destination, entry!!.name)
                    
                    if (entry!!.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { fos ->
                            val buffer = ByteArray(1024)
                            var length: Int
                            while (zis.read(buffer).also { length = it } > 0) {
                                fos.write(buffer, 0, length)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error unzipping files", e)
            return false
        }
    }

    /** 获取文件的MIME类型 */
    fun getMimeType(file: File): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    /** 打开文件 */
    fun openFile(context: Context, file: File): Boolean {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            AppLogger.e(TAG, "No app found to open file", e)
            return false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error opening file", e)
            return false
        }
    }

    /** 分享文件 */
    fun shareFile(context: Context, file: File, title: String = "Share File"): Boolean {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error sharing file", e)
            return false
        }
    }

    /** 安全地删除文件或目录 */
    fun deleteFile(file: File): Boolean {
        if (!file.exists()) return true
        
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                deleteFile(it)
            }
        }
        
        return file.delete()
    }

    /** 复制文件 */
    fun copyFile(source: File, destination: File): Boolean {
        try {
            if (!source.exists()) return false
            
            destination.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            
            FileInputStream(source).use { fis ->
                FileOutputStream(destination).use { fos ->
                    val buffer = ByteArray(1024)
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        fos.write(buffer, 0, length)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error copying file", e)
            return false
        }
    }

    /** 移动文件 */
    fun moveFile(source: File, destination: File): Boolean {
        return copyFile(source, destination) && deleteFile(source)
    }
}
