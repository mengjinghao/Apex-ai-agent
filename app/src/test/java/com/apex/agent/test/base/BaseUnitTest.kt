package com.apex.agent.test.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.mockito.Mockito

/**
 * 所有单元测试的基类
 *
 * 提供协程测试调度器、测试作用域、Mock 创建等通用功能
 */
abstract class BaseUnitTest {

    /** 标准测试调度器 */
    protected val testDispatcher: TestDispatcher = StandardTestDispatcher()

    /** 测试作用域 */
    protected val testScope = TestScope(testDispatcher)

    @Before
    open fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    open fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 在指定超时内运行测试块
     *
     * @param timeoutMs 超时时间（毫秒），默认 5000ms
     * @param block 测试代码块
     */
    protected fun runTestWithTimeout(timeoutMs: Long = 5000, block: suspend TestScope.() -> Unit) {
        val scope = testScope
        scope.runTest {
            withTimeout(timeoutMs) {
                scope.block()
            }
        }
    }

    /**
     * 创建 Mock 对象
     *
     * @param type Class 类型
     * @return Mock 实例
     */
    protected fun <T> mock(type: Class<T>): T = Mockito.mock(type)

    /**
     * 使用 reified 类型参数创建 Mock 对象
     *
     * @return Mock 实例
     */
    protected inline fun <reified T> mockRelaxed(): T = Mockito.mock(T::class.java)
}
