package com.apex.agent.test.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * 协程测试工具类
 *
 * 提供用于协程单元测试的辅助函数，包括测试调度器、测试作用域、时间推进和 Flow 断言。
 */
object CoroutineTestUtils {

    /**
     * 创建标准测试调度器
     *
     * @return StandardTestDispatcher 实例
     */
    fun testDispatcher(): StandardTestDispatcher = StandardTestDispatcher()

    /**
     * 创建测试作用域
     *
     * @param dispatcher 测试调度器，默认创建新的 StandardTestDispatcher
     * @return TestScope 实例
     */
    fun testScope(dispatcher: TestDispatcher = StandardTestDispatcher()): TestScope = TestScope(dispatcher)

    /**
     * 在测试作用域中推进虚拟时间
     *
     * @param ms 要推进的时间（毫秒）
     * @param scope 测试作用域
     */
    suspend fun advanceTimeBy(ms: Long, scope: TestScope) {
        scope.testScheduler.advanceTimeBy(ms)
    }

    /**
     * 使用测试调度器运行协程测试
     *
     * @param dispatcher 测试调度器，默认创建新的 StandardTestDispatcher
     * @param block 测试代码块
     */
    fun runTestBlocking(dispatcher: TestDispatcher = StandardTestDispatcher(), block: suspend TestScope.() -> Unit) {
        runTest(dispatcher) { block() }
    }

    /**
     * 断言 Flow 发射的值与预期列表一致
     *
     * 注意：此函数会收集 Flow 直到取消，适用于有限 Flow。
     *
     * @param flow 待测 Flow
     * @param expected 预期的值列表
     * @param timeoutMs 超时时间（毫秒），默认 5000ms
     * @throws AssertionError 当实际值与预期值不匹配时
     * @throws TimeoutCancellationException 当超时时
     */
    suspend fun <T> assertFlowValues(flow: Flow<T>, expected: List<T>, timeoutMs: Long = 5000) {
        val actual = withTimeout(timeoutMs) { flow.toList() }
        kotlin.test.assertEquals(expected, actual, "Flow 发射的值与预期不匹配")
    }

    /**
     * 创建一个可控制虚拟时间的 StandardTestDispatcher
     *
     * 使用 [advanceTimeBy] 或 [TestScope.testScheduler] 来控制时间推进。
     *
     * @return StandardTestDispatcher 实例
     */
    fun controllableDispatcher(): StandardTestDispatcher = StandardTestDispatcher()
}
