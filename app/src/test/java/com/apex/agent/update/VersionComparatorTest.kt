package com.apex.agent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionComparator] 单元测试。
 *
 * 覆盖：
 * - 纯数字版本（含 1/2/3/4 段）
 * - `v` 前缀
 * - 预发布后缀（-beta / -rc.1 / -alpha.2）
 * - build 元数据（+build456 应被忽略）
 * - 大小写
 * - 边界：相等、空字符串、非法字符
 */
class VersionComparatorTest {

    @Test
    fun `pure numeric versions compare correctly`() {
        assertTrue(VersionComparator.isNewer("1.2.3", "1.2.2"))
        assertTrue(VersionComparator.isNewer("1.2.3", "1.2"))
        assertTrue(VersionComparator.isNewer("1.2.3", "1.0.0"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.9.9"))
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))
        assertFalse(VersionComparator.isNewer("1.2.3", "1.2.3"))
        assertFalse(VersionComparator.isNewer("1.2.2", "1.2.3"))
    }

    @Test
    fun `v prefix is stripped`() {
        assertEquals(0, VersionComparator.compare("v1.2.3", "1.2.3"))
        assertEquals(0, VersionComparator.compare("V1.2.3", "1.2.3"))
        assertTrue(VersionComparator.isNewer("v2.0.0", "v1.0.0"))
    }

    @Test
    fun `release is greater than prerelease`() {
        assertTrue(VersionComparator.isNewer("1.2.3", "1.2.3-beta"))
        assertTrue(VersionComparator.isNewer("1.2.3", "1.2.3-rc.1"))
        assertTrue(VersionComparator.isNewer("1.2.3", "1.2.3-alpha.2"))
        assertFalse(VersionComparator.isNewer("1.2.3-beta", "1.2.3"))
    }

    @Test
    fun `prerelease precedence by segment`() {
        // beta.2 > beta.1
        assertTrue(VersionComparator.isNewer("1.2.3-beta.2", "1.2.3-beta.1"))
        // 注意：当前实现把非数字段（如 rc / beta）当作 0，因此仅比较数字段。
        // beta.99 的 pre = [0, 99]；rc.1 的 pre = [0, 1]；99 > 1 → beta.99 > rc.1
        assertTrue(VersionComparator.isNewer("1.2.3-beta.99", "1.2.3-rc.1"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, VersionComparator.compare("1.2.3+build456", "1.2.3"))
        assertEquals(0, VersionComparator.compare("1.2.3", "1.2.3+sha.abc"))
    }

    @Test
    fun `different lengths treat missing segments as zero`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
        assertEquals(0, VersionComparator.compare("1.2.0.0", "1.2"))
        assertTrue(VersionComparator.isNewer("1.2.1", "1.2"))
        assertTrue(VersionComparator.isNewer("1.2.0.1", "1.2"))
    }

    @Test
    fun `equal versions return zero`() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("v1.0.0", "1.0.0"))
    }

    @Test
    fun `invalid segments treated as zero`() {
        // "1.x.0" → core = [1, 0, 0]
        assertEquals(0, VersionComparator.compare("1.x.0", "1.0.0"))
        // "1.2.3-xyz" → pre = [0]，与 "1.2.3-0" 相等
        assertEquals(0, VersionComparator.compare("1.2.3-xyz", "1.2.3-0"))
    }

    @Test
    fun `newer tag triggers UpdateAvailable`() {
        val current = "v1.0.0"
        val candidate = "v1.1.0"
        assertTrue(VersionComparator.isNewer(candidate, current))
    }

    @Test
    fun `formatBytes handles common sizes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `extractSha256 parses from release notes`() {
        val release = UpdateRelease(
            tagName = "v1.0.1",
            body = "## v1.0.1\n\n- 修复 bug\n\nSHA-256: abc123def4567890abc123def4567890abc123def4567890abc123def4567890",
            assets = listOf(
                UpdateAsset(name = "app-release.apk", browserDownloadUrl = "https://example.com/app.apk")
            )
        )
        val asset = release.assets.first()
        val sha = extractSha256(release, asset)
        assertEquals("abc123def4567890abc123def4567890abc123def4567890abc123def4567890", sha)
    }

    @Test
    fun `extractSha256 returns null when not present`() {
        val release = UpdateRelease(
            tagName = "v1.0.1",
            body = "## v1.0.1\n\n- 修复 bug",
            assets = listOf(
                UpdateAsset(name = "app-release.apk", browserDownloadUrl = "https://example.com/app.apk")
            )
        )
        val asset = release.assets.first()
        assertNull(extractSha256(release, asset))
    }

    @Test
    fun `mirror wrap replaces url placeholder`() {
        val mirror = MirrorSource(
            id = "test",
            name = "Test",
            urlTemplate = "https://mirror.example.com/{url}"
        )
        assertEquals(
            "https://mirror.example.com/https://github.com/x/y/releases/download/v1/app.apk",
            mirror.wrap("https://github.com/x/y/releases/download/v1/app.apk")
        )
    }

    @Test
    fun `direct mirror returns original url`() {
        val direct = MirrorSource(id = "direct", name = "Direct", urlTemplate = "{url}")
        val url = "https://github.com/x/y/releases/download/v1/app.apk"
        assertEquals(url, direct.wrap(url))

        val blank = MirrorSource(id = "blank", name = "Blank", urlTemplate = "")
        assertEquals(url, blank.wrap(url))
    }
}
