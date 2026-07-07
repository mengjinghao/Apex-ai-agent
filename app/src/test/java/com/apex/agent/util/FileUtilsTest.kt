package com.apex.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileUtilsTest {

    @Test
    fun `isTextBasedExtension returns true for txt`() {
        assertTrue(FileUtils.isTextBasedExtension("txt"))
    }

    @Test
    fun `isTextBasedExtension returns true for java`() {
        assertTrue(FileUtils.isTextBasedExtension("java"))
    }

    @Test
    fun `isTextBasedExtension returns true for kt`() {
        assertTrue(FileUtils.isTextBasedExtension("kt"))
    }

    @Test
    fun `isTextBasedExtension returns true for py`() {
        assertTrue(FileUtils.isTextBasedExtension("py"))
    }

    @Test
    fun `isTextBasedExtension returns true for html`() {
        assertTrue(FileUtils.isTextBasedExtension("html"))
    }

    @Test
    fun `isTextBasedExtension returns true for js`() {
        assertTrue(FileUtils.isTextBasedExtension("js"))
    }

    @Test
    fun `isTextBasedExtension returns true for json`() {
        assertTrue(FileUtils.isTextBasedExtension("json"))
    }

    @Test
    fun `isTextBasedExtension returns true for xml`() {
        assertTrue(FileUtils.isTextBasedExtension("xml"))
    }

    @Test
    fun `isTextBasedExtension returns true for md`() {
        assertTrue(FileUtils.isTextBasedExtension("md"))
    }

    @Test
    fun `isTextBasedExtension returns true for css`() {
        assertTrue(FileUtils.isTextBasedExtension("css"))
    }

    @Test
    fun `isTextBasedExtension returns true for cpp`() {
        assertTrue(FileUtils.isTextBasedExtension("cpp"))
    }

    @Test
    fun `isTextBasedExtension is case insensitive`() {
        assertTrue(FileUtils.isTextBasedExtension("TXT"))
        assertTrue(FileUtils.isTextBasedExtension("Java"))
        assertTrue(FileUtils.isTextBasedExtension("KT"))
    }

    @Test
    fun `isTextBasedExtension returns false for binary extensions`() {
        assertFalse(FileUtils.isTextBasedExtension("png"))
        assertFalse(FileUtils.isTextBasedExtension("jpg"))
        assertFalse(FileUtils.isTextBasedExtension("mp4"))
        assertFalse(FileUtils.isTextBasedExtension("dex"))
        assertFalse(FileUtils.isTextBasedExtension("so"))
    }

    @Test
    fun `isTextBasedExtension returns false for empty string`() {
        assertFalse(FileUtils.isTextBasedExtension(""))
    }

    @Test
    fun `isTextBasedExtension returns false for blank string`() {
        assertFalse(FileUtils.isTextBasedExtension(" "))
    }

    @Test
    fun `isTextBasedFileName returns true for known text names`() {
        assertTrue(FileUtils.isTextBasedFileName("readme"))
        assertTrue(FileUtils.isTextBasedFileName("Makefile"))
        assertTrue(FileUtils.isTextBasedFileName("Dockerfile"))
        assertTrue(FileUtils.isTextBasedFileName("CHANGELOG"))
    }

    @Test
    fun `isTextBasedFileName returns true for files with text extensions`() {
        assertTrue(FileUtils.isTextBasedFileName("index.html"))
        assertTrue(FileUtils.isTextBasedFileName("main.java"))
        assertTrue(FileUtils.isTextBasedFileName("script.js"))
        assertTrue(FileUtils.isTextBasedFileName("style.css"))
    }

    @Test
    fun `isTextBasedFileName returns false for binary files`() {
        assertFalse(FileUtils.isTextBasedFileName("image.png"))
        assertFalse(FileUtils.isTextBasedFileName("video.mp4"))
    }

    @Test
    fun `isTextBasedFileName returns false for blank string`() {
        assertFalse(FileUtils.isTextBasedFileName(""))
        assertFalse(FileUtils.isTextBasedFileName("  "))
    }

    @Test
    fun `isTextBasedFileName is case insensitive`() {
        assertTrue(FileUtils.isTextBasedFileName("README"))
        assertTrue(FileUtils.isTextBasedFileName("MAKEFILE"))
        assertTrue(FileUtils.isTextBasedFileName("Index.HTML"))
    }

    @Test
    fun `isTextBasedFile returns true for files with text extensions`() {
        assertTrue(FileUtils.isTextBasedFile(File("test.java")))
        assertTrue(FileUtils.isTextBasedFile(File("app.kt")))
        assertTrue(FileUtils.isTextBasedFile(File("readme.md")))
    }

    @Test
    fun `isTextBasedFile returns false for binary files`() {
        assertFalse(FileUtils.isTextBasedFile(File("image.png")))
        assertFalse(FileUtils.isTextBasedFile(File("music.mp3")))
    }

    @Test
    fun `isTextBasedFile checks known filenames without extension`() {
        assertTrue(FileUtils.isTextBasedFile(File("Makefile")))
        assertTrue(FileUtils.isTextBasedFile(File("Dockerfile")))
    }

    @Test
    fun `isTextBasedFile returns false for unknown files without extension`() {
        assertFalse(FileUtils.isTextBasedFile(File("myfile")))
    }

    @Test
    fun `isTextLikeBytes returns true for ascii text`() {
        val bytes = "Hello, World!".toByteArray()
        assertTrue(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes returns true for utf8 text`() {
        val bytes = "你好世界".toByteArray(Charsets.UTF_8)
        assertTrue(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes returns true for mixed ascii and utf8`() {
        val bytes = "Hello 你好 World".toByteArray(Charsets.UTF_8)
        assertTrue(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes returns false for binary data with null bytes`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        assertFalse(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes returns true for empty array`() {
        assertTrue(FileUtils.isTextLike(ByteArray(0)))
    }

    @Test
    fun `isTextLikeBytes returns true for text with whitespace`() {
        val bytes = "line1\nline2\tindented\r\n".toByteArray()
        assertTrue(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes returns false for high ratio of binary`() {
        val bytes = ByteArray(100) { i -> if (i % 5 == 0) 0x00 else 0x41 }
        assertFalse(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes returns true for mostly text with few nulls`() {
        val text = "A".repeat(200)
        val bytes = text.toByteArray()
        assertTrue(FileUtils.isTextLike(bytes))
    }

    @Test
    fun `isTextLikeBytes handles control characters as non-text`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertFalse(FileUtils.isTextLike(bytes))
    }
}
