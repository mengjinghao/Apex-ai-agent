package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 配置提供者测试
 *
 * 验证 Properties、JSON 和环境变量提供者。
 */
class ConfigProviderTest : BaseUnitTest {

    private lateinit var propertiesProvider: PropertiesProvider
    private lateinit var jsonProvider: JsonConfigProvider
    private lateinit var envProvider: EnvironmentProvider

    @Before
    override fun setUp() {
        super.setUp()
        propertiesProvider = PropertiesProvider()
        propertiesProvider.load("key1=value1\nkey2=value2")

        jsonProvider = JsonConfigProvider()
        jsonProvider.load("""{"host": "localhost", "port": 8080}""")

        envProvider = EnvironmentProvider()
    }

    @Test
    fun `properties provider should parse key value pairs`() {
        assertEquals("value1", propertiesProvider.get("key1"))
        assertEquals("value2", propertiesProvider.get("key2"))
    }

    @Test
    fun `properties provider should return null for missing`() {
        assertNull(propertiesProvider.get("missing"))
    }

    @Test
    fun `json provider should parse json config`() {
        assertEquals("localhost", jsonProvider.get("host"))
        assertEquals("8080", jsonProvider.get("port"))
    }

    @Test
    fun `json provider should handle nested keys`() {
        jsonProvider.load("""{"db": {"host": "db.local", "port": 3306}}""")
        assertEquals("db.local", jsonProvider.get("db.host"))
    }

    @Test
    fun `environment provider should read system env`() {
        val path = envProvider.get("PATH")
        assertNotNull(path)
        assertTrue(path!!.isNotEmpty())
    }

    @Test
    fun `providers should have a name`() {
        assertEquals("properties", propertiesProvider.name())
        assertEquals("json", jsonProvider.name())
        assertEquals("environment", envProvider.name())
    }

    @Test
    fun `json provider should handle empty object`() {
        jsonProvider.load("{}")
        assertNull(jsonProvider.get("anything"))
    }

    @Test
    fun `providers should be composable`() {
        val composite = CompositeConfigProvider(listOf(propertiesProvider, jsonProvider))
        assertEquals("value1", composite.get("key1"))
        assertEquals("localhost", composite.get("host"))
    }
}

interface ConfigProvider {
    fun name(): String
    fun get(key: String): String?
}

class PropertiesProvider : ConfigProvider {
    private val props = mutableMapOf<String, String>()
    override fun name() = "properties"
    override fun get(key: String) = props[key]
    fun load(content: String) {
        content.lines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) props[parts[0].trim()] = parts[1].trim()
        }
    }
}

class JsonConfigProvider : ConfigProvider {
    private var data = mutableMapOf<String, String>()
    override fun name() = "json"
    override fun get(key: String): String? {
        val parts = key.split(".")
        var current: Any? = data
        for (p in parts) {
            current = when (current) {
                is Map<*, *> -> (current as Map<String, Any>)[p]
                else -> null
            }
        }
        return current?.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun load(json: String) {
        if (json.isBlank() || json == "{}") return
        try {
            val trimmed = json.trim().removeSurrounding("{").removeSuffix("}")
            trimmed.split(",").forEach { pair ->
                val p = pair.split(":", limit = 2)
                if (p.size == 2) {
                    val k = p[0].trim().removeSurrounding("\"")
                    val v = p[1].trim().removeSurrounding("\"")
                    data[k] = v
                }
            }
        } catch (_: Exception) {}
    }
}

class EnvironmentProvider : ConfigProvider {
    override fun name() = "environment"
    override fun get(key: String) = System.getenv(key)
}

class CompositeConfigProvider(private val providers: List<ConfigProvider>) : ConfigProvider {
    override fun name() = "composite"
    override fun get(key: String): String? {
        for (p in providers) { p.get(key)?.let { return it } }
        return null
    }
}
