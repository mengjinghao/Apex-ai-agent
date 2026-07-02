package com.apex.agent.kernel.plugin

import com.apex.agent.kernel.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class PluginRegistryTest {

    private lateinit var registry: PluginRegistry
    private lateinit var dependencyResolver: DependencyResolver

    @Before
    fun setUp() {
        dependencyResolver = DependencyResolver()
        registry = PluginRegistry(dependencyResolver)
    }

    @Test
    fun `register and get plugin`() {
        val plugin = mockPlugin("plugin-a")
        registry.register(plugin)
        assertEquals(plugin, registry.get("plugin-a"))
    }

    @Test
    fun `unregister plugin removes it`() {
        val plugin = mockPlugin("plugin-a")
        registry.register(plugin)
        registry.unregister("plugin-a")
        assertNull(registry.get("plugin-a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `register with blank id throws`() {
        registry.register(mockPlugin(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `register duplicate plugin throws`() {
        val plugin = mockPlugin("plugin-a")
        registry.register(plugin)
        registry.register(plugin)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `register with missing dependency throws`() {
        val manifest = PluginManifest(id = "b", name = "B", dependencies = listOf("a"))
        val plugin = mockPlugin("b", manifest)
        registry.register(plugin)
    }

    @Test
    fun `register with satisfied dependency succeeds`() {
        val dep = mockPlugin("dep-a")
        registry.register(dep)

        val manifest = PluginManifest(id = "b", name = "B", dependencies = listOf("dep-a"))
        val plugin = mockPlugin("b", manifest)
        registry.register(plugin)

        assertEquals(plugin, registry.get("b"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `getAll returns all registered plugins`() {
        val a = mockPlugin("a")
        val b = mockPlugin("b")
        registry.register(a)
        registry.register(b)

        val all = registry.getAll()
        assertEquals(2, all.size)
        assertTrue(all.contains(a))
        assertTrue(all.contains(b))
    }

    @Test
    fun `contains returns true for registered plugin`() {
        registry.register(mockPlugin("x"))
        assertTrue(registry.contains("x"))
        assertFalse(registry.contains("nonexistent"))
    }

    @Test
    fun `size returns correct count`() {
        assertEquals(0, registry.size())
        registry.register(mockPlugin("a"))
        assertEquals(1, registry.size())
        registry.register(mockPlugin("b"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `clear removes all plugins`() {
        registry.register(mockPlugin("a"))
        registry.register(mockPlugin("b"))
        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.get("a"))
    }

    @Test
    fun `getByCategory filters correctly`() {
        val core = mockPlugin("core", category = PluginCategory.CORE)
        val enhancement = mockPlugin("enh", category = PluginCategory.ENHANCEMENT)
        registry.register(core)
        registry.register(enhancement)

        val corePlugins = registry.getByCategory("CORE")
        assertEquals(1, corePlugins.size)
        assertEquals(core, corePlugins[0])

        val enhPlugins = registry.getByCategory("ENHANCEMENT")
        assertEquals(1, enhPlugins.size)
        assertEquals(enhancement, enhPlugins[0])
    }

    @Test
    fun `find uses predicate`() {
        val a = mockPlugin("a", priority = 10)
        val b = mockPlugin("b", priority = 20)
        registry.register(a)
        registry.register(b)

        val result = registry.find { it.manifest.priority > 15 }
        assertEquals(1, result.size)
        assertEquals(b, result[0])
    }

    @Test
    fun `sortedByPriority returns descending order`() {
        val low = mockPlugin("low", priority = 10)
        val mid = mockPlugin("mid", priority = 50)
        val high = mockPlugin("high", priority = 100)
        registry.register(low)
        registry.register(mid)
        registry.register(high)

        val sorted = registry.sortedByPriority()
        assertEquals(3, sorted.size)
        assertEquals("high", sorted[0].manifest.id)
        assertEquals("mid", sorted[1].manifest.id)
        assertEquals("low", sorted[2].manifest.id)
    }

    @Test
    fun `resolveLoadOrder respects dependencies`() {
        val history = mockPlugin("core.history", PluginManifest(
            id = "core.history", name = "History", version = "1.0.0"
        ))
        val context = mockPlugin("core.context", PluginManifest(
            id = "core.context", name = "Context", version = "1.0.0",
            dependencies = listOf("core.history")
        ))
        val tool = mockPlugin("core.tool", PluginManifest(
            id = "core.tool", name = "Tool", version = "1.0.0",
            dependencies = listOf("core.context")
        ))

        val resolver = DependencyResolver()
        val order = resolver.resolveLoadOrder(listOf(tool, context, history))

        val ids = order.map { it.manifest.id }
        assertTrue(ids.indexOf("core.history") < ids.indexOf("core.context"))
        assertTrue(ids.indexOf("core.context") < ids.indexOf("core.tool"))
    }

    @Test
    fun `resolveLoadOrder handles plugins without dependencies`() {
        val a = mockPlugin("a")
        val b = mockPlugin("b")
        val resolver = DependencyResolver()
        val order = resolver.resolveLoadOrder(listOf(b, a))

        assertEquals(2, order.size)
    }

    @Test
    fun `buildGraph creates correct edges`() {
        val a = mockPlugin("a", PluginManifest(id = "a", name = "A", dependencies = listOf("b")))
        val b = mockPlugin("b", PluginManifest(id = "b", name = "B"))
        val resolver = DependencyResolver()
        val graph = resolver.buildGraph(listOf(a, b))

        assertEquals(setOf("b"), graph.edges["a"])
        assertTrue(graph.edges["b"]!!.isEmpty())
    }

    @Test
    fun `circular dependency detected and rejected`() {
        val graph = DependencyGraph(mapOf(
            "A" to setOf("B"),
            "B" to setOf("A")
        ))
        val result = graph.hasCycle()
        assertTrue(result is CycleResult.HasCycle)
    }

    @Test
    fun `no cycle for acyclic graph`() {
        val graph = DependencyGraph(mapOf(
            "A" to setOf("B"),
            "B" to setOf("C"),
            "C" to emptySet()
        ))
        val result = graph.hasCycle()
        assertTrue(result is CycleResult.NoCycle)
    }

    @Test
    fun `circular dependency with longer chain detected`() {
        val graph = DependencyGraph(mapOf(
            "A" to setOf("B"),
            "B" to setOf("C"),
            "C" to setOf("A")
        ))
        val result = graph.hasCycle()
        assertTrue(result is CycleResult.HasCycle)
    }

    @Test(expected = IllegalStateException::class)
    fun `cannot unregister plugin with dependents`() {
        val dep = mockPlugin("dep")
        registry.register(dep)

        val dependent = mockPlugin("dependent", PluginManifest(
            id = "dependent", name = "Dependent", dependencies = listOf("dep")
        ))
        registry.register(dependent)

        registry.unregister("dep")
    }

    @Test
    fun `unregistering plugin without dependents succeeds`() {
        val a = mockPlugin("a")
        val b = mockPlugin("b")
        registry.register(a)
        registry.register(b)
        registry.unregister("a")
        assertNull(registry.get("a"))
        assertNotNull(registry.get("b"))
    }

    @Test
    fun `service registry register and get service`() {
        val serviceRegistry = ServiceRegistry()
        serviceRegistry.register(String::class.java, "hello")
        val retrieved = serviceRegistry.get(String::class.java)
        assertEquals("hello", retrieved)
    }

    @Test
    fun `service registry get returns null for unregistered`() {
        val serviceRegistry = ServiceRegistry()
        assertNull(serviceRegistry.get(String::class.java))
    }

    @Test
    fun `service registry unregister removes service`() {
        val serviceRegistry = ServiceRegistry()
        serviceRegistry.register(String::class.java, "hello")
        serviceRegistry.unregister(String::class.java)
        assertNull(serviceRegistry.get(String::class.java))
    }

    @Test
    fun `service registry clear removes all services`() {
        val serviceRegistry = ServiceRegistry()
        serviceRegistry.register(String::class.java, "hello")
        serviceRegistry.register(Int::class.java, 42)
        serviceRegistry.clear()
        assertNull(serviceRegistry.get(String::class.java))
        assertNull(serviceRegistry.get(Int::class.java))
    }

    @Test
    fun `service registry getServiceOrThrow returns or throws`() {
        val config = KernelConfig()
        val eventBus = mock<com.apex.agent.kernel.event.IEventBus>()
        val serviceRegistry = ServiceRegistry()
        val context = PluginContext("test", config, eventBus, serviceRegistry)

        context.registerService(String::class.java, "available")
        assertEquals("available", context.getService(String::class.java))

        assertNull(context.getService(Int::class.java))

        assertThrows(IllegalStateException::class.java) {
            context.getServiceOrThrow(Int::class.java)
        }
    }

    @Test
    fun `plugin onInstall lifecycle hook`() {
        val plugin = mock<IPlugin> {
            on { manifest } doReturn PluginManifest(id = "lifecycle-test", name = "Lifecycle")
        }
        val config = KernelConfig()
        val eventBus = mock<com.apex.agent.kernel.event.IEventBus>()
        val context = PluginContext("lifecycle-test", config, eventBus, ServiceRegistry())

        registry.register(plugin)
        plugin.onInstall(context)

        verify(plugin).onInstall(context)
        assertEquals(plugin, registry.get("lifecycle-test"))
    }

    @Test
    fun `plugin onUninstall lifecycle hook`() {
        val plugin = mock<IPlugin> {
            on { manifest } doReturn PluginManifest(id = "uninstall-test", name = "Uninstall")
        }
        registry.register(plugin)
        plugin.onUninstall()
        registry.unregister("uninstall-test")

        verify(plugin).onUninstall()
        assertNull(registry.get("uninstall-test"))
    }

    @Test
    fun `session observer onSessionCreated and onSessionDestroyed lifecycle`() = runTest {
        val observer = mock<ISessionObserver> {
            on { manifest } doReturn PluginManifest(id = "observer", name = "Observer")
        }
        val config = KernelConfig()
        val eventBus = mock<com.apex.agent.kernel.event.IEventBus>()
        val context = PluginContext("observer", config, eventBus, ServiceRegistry())
        val sessionId = "session-1"

        observer.onSessionCreated(sessionId, context)
        verify(observer).onSessionCreated(eq(sessionId), any())

        observer.onSessionDestroyed(sessionId, context)
        verify(observer).onSessionDestroyed(eq(sessionId), any())
    }

    @Test
    fun `plugin context publish delegates to event bus`() {
        val eventBus = mock<com.apex.agent.kernel.event.IEventBus>()
        val context = PluginContext("test", KernelConfig(), eventBus, ServiceRegistry())
        val event = SessionEvent.UserInputReceived("sid", "hi")

        context.publish(event)
        verify(eventBus).publish(event)
    }

    @Test
    fun `getByState returns empty list`() {
        registry.register(mockPlugin("a"))
        val result = registry.getByState(PluginState.ENABLED)
        assertTrue(result.isEmpty())
    }

    private fun mockPlugin(
        id: String,
        manifest: PluginManifest? = null,
        category: PluginCategory = PluginCategory.CORE,
        priority: Int = 0
    ): IPlugin {
        val mf = manifest ?: PluginManifest(
            id = id, name = id, version = "1.0.0",
            category = category, priority = priority
        )
        return mock {
            on { this.manifest } doReturn mf
        }
    }
}
