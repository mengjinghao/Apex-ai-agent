package com.apex.agent.test.burstmode

import com.apex.data.burstmode.config.BurstModeConfig
import com.apex.data.burstmode.config.ConfigLoader
import com.apex.data.burstmode.config.ConfigPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BurstModeConfigTest {

    @Before
    fun setUp() {
        BurstModeConfig.resetToDefaults()
    }

    @Test
    fun `default values should be correctly set`() {
        assertEquals(10_000_000L, BurstModeConfig.maxTokenLimit)
        assertEquals(100_000L, BurstModeConfig.defaultTokenLimit)
        assertEquals(500L, BurstModeConfig.maxFileSizeMB)
        assertEquals(500, BurstModeConfig.l2CacheSizeMB)
        assertEquals(5000, BurstModeConfig.l3StorageSizeMB)
        assertEquals(95f, BurstModeConfig.cpuUsageThreshold, 0.01f)
        assertEquals(95f, BurstModeConfig.memoryUsageThreshold, 0.01f)
        assertEquals(12, BurstModeConfig.defaultConcurrency)
        assertEquals(32, BurstModeConfig.maxConcurrency)
    }

    @Test
    fun `config modification should persist values`() {
        BurstModeConfig.maxTokenLimit = 20_000_000L
        BurstModeConfig.maxFileSizeMB = 800L
        BurstModeConfig.defaultConcurrency = 24

        assertEquals(20_000_000L, BurstModeConfig.maxTokenLimit)
        assertEquals(800L, BurstModeConfig.maxFileSizeMB)
        assertEquals(24, BurstModeConfig.defaultConcurrency)
    }

    @Test
    fun `resetToDefaults should restore default values`() {
        BurstModeConfig.maxTokenLimit = 99_000_000L
        BurstModeConfig.defaultConcurrency = 99
        BurstModeConfig.resetToDefaults()

        assertEquals(10_000_000L, BurstModeConfig.maxTokenLimit)
        assertEquals(100_000L, BurstModeConfig.defaultTokenLimit)
        assertEquals(500L, BurstModeConfig.maxFileSizeMB)
        assertEquals(500, BurstModeConfig.l2CacheSizeMB)
        assertEquals(5000, BurstModeConfig.l3StorageSizeMB)
        assertEquals(12, BurstModeConfig.defaultConcurrency)
        assertEquals(32, BurstModeConfig.maxConcurrency)
    }

    @Test
    fun `validation should pass for default config`() {
        BurstModeConfig.resetToDefaults()
        val result = BurstModeConfig.validate()
        assertTrue("Default config should be valid", result.isValid)
    }

    @Test
    fun `validation should fail for negative maxTokenLimit`() {
        BurstModeConfig.maxTokenLimit = -100L
        val result = BurstModeConfig.validate()
        assertFalse("Negative maxTokenLimit should be invalid", result.isValid)
    }

    @Test
    fun `validation should fail for zero maxFileSizeMB`() {
        BurstModeConfig.maxFileSizeMB = 0L
        val result = BurstModeConfig.validate()
        assertFalse("Zero maxFileSizeMB should be invalid", result.isValid)
    }

    @Test
    fun `validation should fail for negative defaultConcurrency`() {
        BurstModeConfig.defaultConcurrency = -1
        val result = BurstModeConfig.validate()
        assertFalse("Negative concurrency should be invalid", result.isValid)
    }

    @Test
    fun `validation should fail for maxConcurrency less than defaultConcurrency`() {
        BurstModeConfig.defaultConcurrency = 20
        BurstModeConfig.maxConcurrency = 10
        val result = BurstModeConfig.validate()
        assertFalse("maxConcurrency < defaultConcurrency should be invalid", result.isValid)
    }

    @Test
    fun `validation should fail for cpuUsageThreshold over 100`() {
        BurstModeConfig.cpuUsageThreshold = 110f
        val result = BurstModeConfig.validate()
        assertFalse("CPU threshold over 100 should be invalid", result.isValid)
    }

    @Test
    fun `validation should fail for memoryUsageThreshold over 100`() {
        BurstModeConfig.memoryUsageThreshold = 101f
        val result = BurstModeConfig.validate()
        assertFalse("Memory threshold over 100 should be invalid", result.isValid)
    }

    @Test
    fun `validation should include multiple errors`() {
        BurstModeConfig.maxTokenLimit = -1L
        BurstModeConfig.defaultConcurrency = -5
        val result = BurstModeConfig.validate()
        assertFalse(result.isValid)
        assertTrue("Should have multiple errors", result.errors.size >= 2)
    }

    @Test
    fun `applyPreset LIGHT_USER should configure conservative settings`() {
        BurstModeConfig.applyPreset(ConfigPreset.LIGHT_USER)
        val result = BurstModeConfig.validate()
        assertTrue("LIGHT_USER preset should be valid", result.isValid)
        assertTrue("LIGHT_USER should have conservative token limit",
            BurstModeConfig.maxTokenLimit <= 10_000_000L)
    }

    @Test
    fun `applyPreset DEVELOPER should configure moderate settings`() {
        BurstModeConfig.applyPreset(ConfigPreset.DEVELOPER)
        val result = BurstModeConfig.validate()
        assertTrue("DEVELOPER preset should be valid", result.isValid)
        assertTrue("DEVELOPER should have moderate concurrency",
            BurstModeConfig.defaultConcurrency > 4)
    }

    @Test
    fun `applyPreset PROFESSIONAL should configure aggressive settings`() {
        BurstModeConfig.applyPreset(ConfigPreset.PROFESSIONAL)
        val result = BurstModeConfig.validate()
        assertTrue("PROFESSIONAL preset should be valid", result.isValid)
        assertTrue("PROFESSIONAL should have higher file size limit",
            BurstModeConfig.maxFileSizeMB >= 500L)
    }

    @Test
    fun `config hot reload with save and load roundtrip`() = runBlocking {
        BurstModeConfig.maxTokenLimit = 8_000_000L
        BurstModeConfig.maxFileSizeMB = 600L

        val testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val saveOk = ConfigLoader.saveToFile(testContext)
        assertTrue("Save should succeed", saveOk)

        BurstModeConfig.resetToDefaults()
        assertEquals(10_000_000L, BurstModeConfig.maxTokenLimit)

        val loadOk = ConfigLoader.loadFromFile(testContext)
        assertTrue("Load should succeed", loadOk)

        assertEquals(8_000_000L, BurstModeConfig.maxTokenLimit)
        assertEquals(600L, BurstModeConfig.maxFileSizeMB)
    }

    @Test
    fun `applyPreset then resetToDefaults should revert`() {
        BurstModeConfig.applyPreset(ConfigPreset.PROFESSIONAL)
        val profTokenLimit = BurstModeConfig.maxTokenLimit

        BurstModeConfig.resetToDefaults()
        assertEquals(10_000_000L, BurstModeConfig.maxTokenLimit)
        assertTrue("PROFESSIONAL token limit should differ from default",
            profTokenLimit != BurstModeConfig.maxTokenLimit)
    }

    @Test
    fun `device capabilities should return reasonable values`() {
        val maxMemory = BurstModeConfig.getMaxMemoryMB()
        val processors = BurstModeConfig.getAvailableProcessors()
        val availableMemory = BurstModeConfig.getAvailableMemoryMB()

        assertTrue("Max memory should be positive", maxMemory > 0)
        assertTrue("Processors should be positive", processors > 0)
        assertTrue("Available memory should be >= 0", availableMemory >= 0)
    }

    @Test
    fun `ConfigLoader generateDefaultConfigExample should produce valid content`() {
        val example = ConfigLoader.generateDefaultConfigExample()
        assertNotNull("Example config should not be null", example)
        assertTrue("Example should contain maxTokenLimit", example.contains("maxTokenLimit"))
        assertTrue("Example should contain maxFileSizeMB", example.contains("maxFileSizeMB"))
        assertTrue("Example should contain concurrency settings", example.contains("defaultConcurrency"))
    }

    @Test
    fun `multiple preset applications should be idempotent`() {
        BurstModeConfig.applyPreset(ConfigPreset.LIGHT_USER)
        val firstTokenLimit = BurstModeConfig.maxTokenLimit

        BurstModeConfig.applyPreset(ConfigPreset.LIGHT_USER)
        assertEquals("Applying same preset twice should produce same result",
            firstTokenLimit, BurstModeConfig.maxTokenLimit)
    }

    @Test
    fun `switching presets should change values`() {
        BurstModeConfig.applyPreset(ConfigPreset.LIGHT_USER)
        val lightTokenLimit = BurstModeConfig.maxTokenLimit

        BurstModeConfig.applyPreset(ConfigPreset.PROFESSIONAL)
        val profTokenLimit = BurstModeConfig.maxTokenLimit

        assertTrue("Different presets should have different token limits",
            lightTokenLimit != profTokenLimit)
    }

    @Test
    fun `enableStreaming property should exist and default to true`() {
        assertTrue("Streaming should be enabled by default", BurstModeConfig.enableStreaming)
    }

    @Test
    fun `config changes within valid range should pass validation`() {
        BurstModeConfig.maxTokenLimit = 5_000_000L
        BurstModeConfig.maxFileSizeMB = 250L
        BurstModeConfig.l2CacheSizeMB = 256
        BurstModeConfig.l3StorageSizeMB = 2000
        BurstModeConfig.cpuUsageThreshold = 80f
        BurstModeConfig.memoryUsageThreshold = 85f
        BurstModeConfig.defaultConcurrency = 8
        BurstModeConfig.maxConcurrency = 16

        val result = BurstModeConfig.validate()
        assertTrue("All values within valid range should pass", result.isValid)
    }
}
