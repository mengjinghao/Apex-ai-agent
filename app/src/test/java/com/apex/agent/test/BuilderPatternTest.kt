package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 构建器模式测试
 *
 * 验证流式构建器、参数验证、默认值和不可变构建结果。
 */
class BuilderPatternTest : BaseUnitTest {

    private lateinit var builder: ProductBuilder

    @Before
    override fun setUp() {
        super.setUp()
        builder = ProductBuilder()
    }

    @Test
    fun `builder should create product with defaults`() {
        val product = builder.build()
        assertNotNull(product)
        assertEquals("default", product.name)
        assertEquals(0.0, product.price, 0.001)
    }

    @Test
    fun `fluent setters should chain correctly`() {
        val product = builder
            .name("Laptop")
            .price(999.99)
            .category("Electronics")
            .build()
        assertEquals("Laptop", product.name)
        assertEquals(999.99, product.price, 0.001)
        assertEquals("Electronics", product.category)
    }

    @Test
    fun `builder should validate required fields`() {
        assertThrows(IllegalStateException::class.java) {
            ProductBuilder().negativePrice(true).build()
        }
    }

    @Test
    fun `builder should create product with tags`() {
        val product = builder
            .name("Phone")
            .price(699.0)
            .tag("new")
            .tag("featured")
            .build()
        assertEquals(2, product.tags.size)
    }

    @Test
    fun `builder should reset state`() {
        builder.name("Temp").price(10.0)
        builder.reset()
        val product = builder.build()
        assertEquals("default", product.name)
    }

    @Test
    fun `builder should support immutability`() {
        val product = builder.name("Immutable").price(50.0).build()
        assertThrows(UnsupportedOperationException::class.java) {
            (product.tags as MutableList).add("hack")
        }
    }

    @Test
    fun `builder should accept nullable fields`() {
        val product = builder.name("Nullable").price(0.0).description(null).build()
        assertNull(product.description)
    }

    @Test
    fun `complex fluent chain should work`() {
        val product = builder
            .name("Complex")
            .price(199.99)
            .category("Gadgets")
            .tag("a").tag("b").tag("c")
            .description("Complex product")
            .build()
        assertEquals("Complex product", product.description)
        assertEquals(3, product.tags.size)
    }
}

data class Product(
    val name: String,
    val price: Double,
    val category: String?,
    val description: String?,
    val tags: List<String>
)

class ProductBuilder {
    private var name: String = "default"
    private var price: Double = 0.0
    private var category: String? = null
    private var description: String? = null
    private val tags = mutableListOf<String>()
    private var throwOnNegative = false

    fun name(value: String) = apply { this.name = value }
    fun price(value: Double) = apply { this.price = value }
    fun category(value: String) = apply { this.category = value }
    fun description(value: String?) = apply { this.description = value }
    fun tag(value: String) = apply { this.tags.add(value) }
    fun negativePrice(throwOn: Boolean) = apply { this.throwOnNegative = throwOn }

    fun reset() {
        name = "default"; price = 0.0; category = null; description = null; tags.clear()
    }

    fun build(): Product {
        if (throwOnNegative && price < 0) throw IllegalStateException("Price cannot be negative")
        return Product(name, price, category, description, tags.toList())
    }
}
