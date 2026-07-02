package com.aaswordman.file

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class FileManager(private val context: Context) {

    fun readFile(path: String): String? {
        return try {
            File(path).readText()
        } catch (e: IOException) {
            null
        }
    }

    fun writeFile(path: String, content: String): Boolean {
        return try {
            File(path).writeText(content)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun listFiles(dirPath: String): List<FileInfo> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        return dir.listFiles()?.map { file ->
            FileInfo(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = file.length(),
                lastModified = file.lastModified()
            )
        } ?: emptyList()
    }

    fun copyFile(sourcePath: String, destPath: String): Boolean {
        return try {
            val source = File(sourcePath)
            val dest = File(destPath)
            source.copyTo(dest, overwrite = true)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun deleteFile(path: String): Boolean {
        return File(path).deleteRecursively()
    }

    fun exists(path: String): Boolean {
        return File(path).exists()
    }

    fun mkdirs(path: String): Boolean {
        return File(path).mkdirs()
    }

    fun getFileSize(path: String): Long {
        return File(path).length()
    }

    fun renameFile(oldPath: String, newName: String): Boolean {
        return try {
            val oldFile = File(oldPath)
            val newFile = File(oldFile.parent, newName)
            oldFile.renameTo(newFile)
        } catch (e: Exception) {
            false
        }
    }

    fun getFileExtension(filename: String): String {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < filename.length - 1) {
            filename.substring(lastDot + 1).lowercase()
        } else {
            ""
        }
    }

    data class FileInfo(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )
}
