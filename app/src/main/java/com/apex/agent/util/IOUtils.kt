package com.apex.util

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset

/**
 * IO 工具类，提供输入输出流、文件读写、资源操作等常用 IO 功能
 */
object IOUtils {

    private const val DEFAULT_BUFFER_SIZE = 8192

    /**
     * 将输入流中的数据复制到输出流中
     *
     * @param inputStream 输入流
     * @param outputStream 输出流
     * @param bufferSize 缓冲区大小（字节），默认为 8192
     * @return 实际复制的字节数
     */
    fun copyStream(inputStream: InputStream, outputStream: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
        val buffer = ByteArray(bufferSize)
        var bytesCopied = 0L
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            bytesCopied += bytesRead
        }
        outputStream.flush()
        return bytesCopied
    }

    /**
     * 从输入流中读取所有字节
     *
     * @param inputStream 输入流
     * @return 字节数组
     */
    fun readBytes(inputStream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            copyStream(inputStream, output)
        return output.toByteArray()
        } finally {
            try {
                output.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 将输入流读取为字符串
     *
     * @param inputStream 输入流
     * @param charset 字符编码，默认为 UTF-8
     * @return 字符串内容
     */
    fun readString(inputStream: InputStream, charset: Charset = Charsets.UTF_8): String {
        return String(readBytes(inputStream), charset)
    }

    /**
     * 将字符串写入输出流
     *
     * @param outputStream 输出流
     * @param content 待写入的字符串
     * @param charset 字符编码，默认为 UTF-8
     */
    fun writeString(outputStream: OutputStream, content: String, charset: Charset = Charsets.UTF_8) {
        outputStream.write(content.toByteArray(charset))
        outputStream.flush()
    }

    /**
     * 安静地关闭 Closeable 对象（不抛出异常）
     *
     * @param closeable 可关闭的对象
     */
    fun closeQuietly(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (ignored: IOException) {
            }
        }
    }

    /**
     * 安静地关闭多个 Closeable 对象
     *
     * @param closeables 可关闭对象的列表
     */
    fun closeQuietly(closeables: List<Closeable?>) {
        for (c in closeables) {
            closeQuietly(c)
        }
    }

    /**
     * 安静地关闭输入流
     *
     * @param inputStream 输入流
     */
    fun closeQuietly(inputStream: InputStream?) {
        closeQuietly(inputStream as? Closeable)
    }

    /**
     * 安静地关闭输出流
     *
     * @param outputStream 输出流
     */
    fun closeQuietly(outputStream: OutputStream?) {
        closeQuietly(outputStream as? Closeable)
    }

    /**
     * 安静地关闭 Reader
     *
     * @param reader Reader 对象
     */
    fun closeQuietly(reader: Reader?) {
        closeQuietly(reader as? Closeable)
    }

    /**
     * 安静地关闭 Writer
     *
     * @param writer Writer 对象
     */
    fun closeQuietly(writer: Writer?) {
        closeQuietly(writer as? Closeable)
    }

    /**
     * 将输入流包装为带缓冲的输入流
     *
     * @param inputStream 原始输入流
     * @return 缓冲输入流
     */
    fun toBufferedInputStream(inputStream: InputStream): BufferedInputStream {
        return if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
    }

    /**
     * 将输出流包装为带缓冲的输出流
     *
     * @param outputStream 原始输出流
     * @return 缓冲输出流
     */
    fun toBufferedOutputStream(outputStream: OutputStream): BufferedOutputStream {
        return if (outputStream is BufferedOutputStream) outputStream else BufferedOutputStream(outputStream)
    }

    /**
     * 将 Reader 包装为带缓冲的 BufferedReader
     *
     * @param reader 原始 Reader
     * @return BufferedReader
     */
    fun toBufferedReader(reader: Reader): BufferedReader {
        return if (reader is BufferedReader) reader else BufferedReader(reader)
    }

    /**
     * 将 Writer 包装为带缓冲的 BufferedWriter
     *
     * @param writer 原始 Writer
     * @return BufferedWriter
     */
    fun toBufferedWriter(writer: Writer): BufferedWriter {
        return if (writer is BufferedWriter) writer else BufferedWriter(writer)
    }

    /**
     * 从输入流中按行读取所有文本行
     *
     * @param inputStream 输入流
     * @param charset 字符编码，默认为 UTF-8
     * @return 所有行的列表
     */
    fun readLines(inputStream: InputStream, charset: Charset = Charsets.UTF_8): List<String> {
        val lines = mutableListOf<String>()
        val reader = BufferedReader(InputStreamReader(inputStream, charset))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lines.add(line!!)
            }
        } finally {
            closeQuietly(reader)
        }
        return lines
    }

    /**
     * 将输入流转换为字符串（readString 的别名方法）
     *
     * @param inputStream 输入流
     * @param charset 字符编码，默认为 UTF-8
     * @return 字符串内容
     */
    fun inputStreamToString(inputStream: InputStream, charset: Charset = Charsets.UTF_8): String {
        return readString(inputStream, charset)
    }

    /**
     * 将字符串转换为 ByteArrayInputStream
     *
     * @param content 字符串内容
     * @param charset 字符编码，默认为 UTF-8
     * @return ByteArrayInputStream
     */
    fun stringToInputStream(content: String, charset: Charset = Charsets.UTF_8): ByteArrayInputStream {
        return ByteArrayInputStream(content.toByteArray(charset))
    }

    /**
     * 将 File 对象转换为 FileInputStream
     *
     * @param file 文件对象
     * @return FileInputStream
     * @throws IOException 如果文件不存在或无法读取
     */
    @Throws(IOException::class)
        fun fileToInputStream(file: File): FileInputStream {
        return FileInputStream(file)
    }

    /**
     * 从资源 ID 读取输入流
     *
     * @param context 上下文
     * @param resId 资源 ID（raw 资源）
     * @return 输入流，失败返回 null
     */
    fun resourceToInputStream(context: Context, resId: Int): InputStream? {
        return try {
            context.resources.openRawResource(resId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将输入流的内容写入文件
     *
     * @param inputStream 输入流
     * @param targetFile 目标文件
     * @param append 是否追加到文件末尾，默认为 false（覆盖写入）
     * @return 写入成功返回 true，失败返回 false
     */
    fun writeToFile(inputStream: InputStream, targetFile: File, append: Boolean = false): Boolean {
        return try {
            val parentDir = targetFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }
        val outputStream = FileOutputStream(targetFile, append)
            try {
                copyStream(inputStream, outputStream)
                true
            } finally {
                closeQuietly(outputStream)
            }
        } catch (e: Exception) {
            false
        }
    }
}
