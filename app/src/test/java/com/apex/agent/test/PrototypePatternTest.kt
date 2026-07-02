package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 原型模式测试
 *
 * 验证对象克隆、深/浅拷贝和原型注册表。
 */
class PrototypePatternTest : BaseUnitTest {

    private lateinit var registry: PrototypeRegistry

    @Before
    override fun setUp() {
        super.setUp()
        registry = PrototypeRegistry()
    }

    @Test
    fun `shallow copy should share references`() {
        val original = PrototypeDocument("Doc1", listOf("section1"))
        val copy = original.shallowCopy()
        assertEquals(original.title, copy.title)
        assertSame(original.sections, copy.sections)
    }

    @Test
    fun `deep copy should create independent copies`() {
        val original = PrototypeDocument("Doc1", mutableListOf("section1"))
        val copy = original.deepCopy()
        assertEquals(original.title, copy.title)
        assertNotSame(original.sections, copy.sections)
    }

    @Test
    fun `registry should register and create prototypes`() {
        val doc = PrototypeDocument("Template", listOf("A", "B"))
        registry.register("template", doc)
        val cloned = registry.createClone("template")
        assertNotNull(cloned)
        assertEquals(doc.title, cloned!!.title)
    }

    @Test
    fun `registry should return null for unknown`() {
        assertNull(registry.createClone("unknown"))
    }

    @Test
    fun `deep copy should not affect original when modified`() {
        val original = PrototypeDocument("Orig", mutableListOf("item"))
        val copy = original.deepCopy()
        (copy.sections as MutableList).add("new_item")
        assertEquals(1, original.sections.size)
        assertEquals(2, copy.sections.size)
    }

    @Test
    fun `shallow copy should affect original when modified`() {
        val original = PrototypeDocument("Orig", mutableListOf("item"))
        val copy = original.shallowCopy()
        (copy.sections as MutableList).add("new_item")
        assertEquals(2, original.sections.size)
    }

    @Test
    fun `registry should override existing prototype`() {
        val doc1 = PrototypeDocument("D1", emptyList())
        val doc2 = PrototypeDocument("D2", emptyList())
        registry.register("key", doc1)
        registry.register("key", doc2)
        val cloned = registry.createClone("key")
        assertEquals("D2", cloned!!.title)
    }
}

data class PrototypeDocument(val title: String, val sections: List<String>) {
    fun shallowCopy() = copy()
    fun deepCopy() = copy(sections = sections.toList())
}

class PrototypeRegistry {
    private val prototypes = mutableMapOf<String, PrototypeDocument>()

    fun register(key: String, prototype: PrototypeDocument) { prototypes[key] = prototype }
    fun createClone(key: String): PrototypeDocument? = prototypes[key]?.deepCopy()
}
