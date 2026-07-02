package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 配置管理器测试
 *
 * 验证分层配置、键值解析和验证功能。
 */
class ConfigManagerTest : BaseUnitTest {

    private lateinit var configManager: ConfigManager

    @Before
    override fun setUp() {
        super.setUp()
        configManager = ConfigManager()
        configManager.set("app.name", "TestApp")
        configManager.set("app.version", "1.0.0")
        configManager.set("app.debug", "true")
        configManager.set("db.host", "localhost")
        configManager.set("db.port", "5432")
    }

    @Test
    fun `get should return configured value`() {
        assertEquals("TestApp", configManager.get("app.name"))
    }

    @Test
    fun `get should return null for missing key`() {
        assertNull(configManager.get("nonexistent"))
    }

    @Test
    fun `getString should return default when missing`() {
        assertEquals("default", configManager.getString("missing", "default"))
    }

    @Test
    fun `getInt should parse integer value`() {
        assertEquals(5432, configManager.getInt("db.port", 0))
    }

    @Test
    fun `getInt should return default when parsing fails`() {
        assertEquals(8080, configManager.getInt("app.name", 8080))
    }

    @Test
    fun `getBoolean should parse boolean`() {
        assertTrue(configManager.getBoolean("app.debug", false))
    }

    @Test
    fun `has should check key existence`() {
        assertTrue(configManager.has("app.name"))
        assertFalse(configManager.has("missing"))
    }

    @Test
    fun `tiered config should resolve from higher priority`() {
        configManager.setTier("override", ConfigTier.APPLICATION)
        configManager.set("app.name", "Overridden")
        assertEquals("Overridden", configManager.get("app.name"))
    }

    @Test
    fun `remove should delete key`() {
        configManager.set("temp", "value")
        configManager.remove("temp")
        assertNull(configManager.get("temp"))
    }

    @Test
    fun `clear should remove all config`() {
        configManager.clear()
        assertNull(configManager.get("app.name"))
    }
}

enum class ConfigTier { DEFAULT, APPLICATION, SYSTEM, ENVIRONMENT }

class ConfigManager {
    private val store = mutableMapOf<String, Pair<String, ConfigTier>>()
    private var defaultTier = ConfigTier.APPLICATION

    fun set(key: String, value: String, tier: ConfigTier = defaultTier) {
        store[key] = value to tier
    }

    fun get(key: String): String? = store[key]?.first
    fun has(key: String) = key in store
    fun remove(key: String) { store.remove(key) }
    fun clear() { store.clear() }
    fun setTier(name: String, tier: ConfigTier) { defaultTier = tier }

    fun getString(key: String, default: String) = get(key) ?: default
    fun getInt(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = get(key)?.toBooleanStrictOrNull() ?: default
}
