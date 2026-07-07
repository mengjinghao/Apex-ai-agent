package com.apex.agent.test.plugins

import com.apex.plugins.PluginRegistry
import com.apex.agent.test.base.BaseUnitTest
import com.apex.plugins.Plugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TestPlugin(
    override val id: String = "test_plugin",
    override val name: String = "Test Plugin",
    override val version: String = "1.0.0"
) : Plugin {
    var registerCalled = false
    var unregisterCalled = false
    var failOnRegister = false

    override fun register() {
        if (failOnRegister) throw RuntimeException("Register failed")
        registerCalled = true
    }

    override fun unregister() {
        unregisterCalled = true
    }
}

class PluginRegistryTest : BaseUnitTest {

    @Before
    override fun setUp() {
        super.setUp()
        PluginRegistry.clear()
    }

    @Test
    fun `install should call register on the plugin`() {
        val plugin = TestPlugin()
        val result = PluginRegistry.install(plugin)

        assertTrue(result.isSuccess)
        assertTrue(plugin.registerCalled)
    }

    @Test
    fun `install should return success for valid plugin`() {
        val plugin = TestPlugin()
        val result = PluginRegistry.install(plugin)

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun `install should return failure when plugin throws`() {
        val plugin = TestPlugin(failOnRegister = true)
        val result = PluginRegistry.install(plugin)

        assertTrue(result.isFailure)
    }

    @Test
    fun `install should add plugin to registry`() {
        val plugin = TestPlugin(id = "my_plugin")
        PluginRegistry.install(plugin)

        assertNotNull(PluginRegistry.getPlugin("my_plugin"))
    }

    @Test
    fun `installAll should install all registered plugins`() {
        val p1 = TestPlugin(id = "p1", name = "Plugin 1")
        val p2 = TestPlugin(id = "p2", name = "Plugin 2")
        PluginRegistry.register(p1)
        PluginRegistry.register(p2)

        PluginRegistry.installAll()

        assertTrue(p1.registerCalled)
        assertTrue(p2.registerCalled)
        assertTrue(PluginRegistry.isInstalled("p1"))
        assertTrue(PluginRegistry.isInstalled("p2"))
    }

    @Test
    fun `installAll when one plugin throws should still install others`() {
        val goodPlugin = TestPlugin(id = "good", name = "Good Plugin")
        val badPlugin = object : TestPlugin(id = "bad", name = "Bad Plugin") {
            override fun register() {
                throw RuntimeException("Boom!")
            }
        }
        PluginRegistry.register(goodPlugin)
        PluginRegistry.register(badPlugin)

        PluginRegistry.installAll()

        assertTrue(goodPlugin.registerCalled)
        assertTrue(PluginRegistry.isInstalled("good"))
    }

    @Test
    fun `installAll should not reinstall already installed plugins`() {
        val plugin = TestPlugin(id = "p1")
        PluginRegistry.install(plugin)
        plugin.registerCalled = false

        PluginRegistry.installAll()

        assertFalse(plugin.registerCalled)
    }

    @Test
    fun `uninstall should call unregister on Plugin instance`() {
        val plugin = TestPlugin(id = "uninstall_me")
        PluginRegistry.install(plugin)
        assertTrue(PluginRegistry.isInstalled("uninstall_me"))

        val removed = PluginRegistry.uninstall("uninstall_me")

        assertTrue(removed)
        assertTrue(plugin.unregisterCalled)
        assertFalse(PluginRegistry.isInstalled("uninstall_me"))
    }

    @Test
    fun `uninstall should remove plugin from registry`() {
        val plugin = TestPlugin(id = "to_remove")
        PluginRegistry.install(plugin)

        PluginRegistry.uninstall("to_remove")

        assertNull(PluginRegistry.getPlugin("to_remove"))
    }

    @Test
    fun `uninstall should return false for non-existent plugin`() {
        val removed = PluginRegistry.uninstall("non_existent")

        assertFalse(removed)
    }

    @Test
    fun `getPlugin should return the correct plugin by id`() {
        val p1 = TestPlugin(id = "alpha")
        val p2 = TestPlugin(id = "beta")
        PluginRegistry.register(p1)
        PluginRegistry.register(p2)

        val found = PluginRegistry.getPlugin("alpha")

        assertNotNull(found)
        assertEquals("alpha", found?.id)
    }

    @Test
    fun `getPlugin should return null for non-existent id`() {
        val found = PluginRegistry.getPlugin("non_existent")

        assertNull(found)
    }

    @Test
    fun `isInstalled should return true for installed plugin`() {
        PluginRegistry.install(TestPlugin(id = "installed_plugin"))

        assertTrue(PluginRegistry.isInstalled("installed_plugin"))
    }

    @Test
    fun `isInstalled should return false for not installed plugin`() {
        assertFalse(PluginRegistry.isInstalled("not_installed"))
    }

    @Test
    fun `isInstalled should return false after uninstall`() {
        PluginRegistry.install(TestPlugin(id = "gone"))
        PluginRegistry.uninstall("gone")

        assertFalse(PluginRegistry.isInstalled("gone"))
    }

    @Test
    fun `pluginCount should return correct number of installed plugins`() {
        assertEquals(0, PluginRegistry.pluginCount())

        PluginRegistry.install(TestPlugin(id = "a"))
        assertEquals(1, PluginRegistry.pluginCount())

        PluginRegistry.install(TestPlugin(id = "b"))
        assertEquals(2, PluginRegistry.pluginCount())

        PluginRegistry.uninstall("a")
        assertEquals(1, PluginRegistry.pluginCount())
    }

    @Test
    fun `clear should remove all plugins`() {
        PluginRegistry.install(TestPlugin(id = "x"))
        PluginRegistry.install(TestPlugin(id = "y"))
        PluginRegistry.install(TestPlugin(id = "z"))
        assertEquals(3, PluginRegistry.pluginCount())

        PluginRegistry.clear()

        assertEquals(0, PluginRegistry.pluginCount())
        assertNull(PluginRegistry.getPlugin("x"))
        assertNull(PluginRegistry.getPlugin("y"))
        assertNull(PluginRegistry.getPlugin("z"))
        assertFalse(PluginRegistry.isInstalled("x"))
    }

    @Test
    fun `clear should call unregister on all plugins`() {
        val p1 = TestPlugin(id = "clear_1")
        val p2 = TestPlugin(id = "clear_2")
        PluginRegistry.install(p1)
        PluginRegistry.install(p2)

        PluginRegistry.clear()

        assertTrue(p1.unregisterCalled)
        assertTrue(p2.unregisterCalled)
    }

    @Test
    fun `getAllPlugins should return all registered plugins`() {
        PluginRegistry.register(TestPlugin(id = "a"))
        PluginRegistry.register(TestPlugin(id = "b"))

        val all = PluginRegistry.getAllPlugins()

        assertEquals(2, all.size)
        assertTrue(all.any { it.id == "a" })
        assertTrue(all.any { it.id == "b" })
    }

    @Test
    fun `register should replace existing plugin with same id`() {
        val original = TestPlugin(id = "dup", version = "1.0")
        PluginRegistry.register(original)

        val replacement = TestPlugin(id = "dup", version = "2.0")
        PluginRegistry.register(replacement)

        val found = PluginRegistry.getPlugin("dup")
        assertNotNull(found)
        assertEquals("2.0", (found as TestPlugin).version)
    }

    @Test
    fun `getAllPlugins should return a defensive copy`() {
        PluginRegistry.register(TestPlugin(id = "only"))
        val list1 = PluginRegistry.getAllPlugins()

        PluginRegistry.clear()

        val list2 = PluginRegistry.getAllPlugins()
        assertEquals(1, list1.size)
        assertEquals(0, list2.size)
    }
}
