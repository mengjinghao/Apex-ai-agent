package com.apex.agent.test.util

import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.stubbing.OngoingStubbing

/**
 * Mockito 测试辅助扩展
 *
 * 提供更简洁的 Mockito 语法包装，包括 whenever、参数匹配器和 verify 快捷方法。
 */
object MockExtensions {

    /**
     * Mockito `when` 的别名，提供更自然的语法
     *
     * @param methodCall Mock 对象的方法调用
     * @return OngoingStubbing 用于链式指定返回值
     */
    inline fun <reified T> whenever(methodCall: T): OngoingStubbing<T> {
        return Mockito.`when`(methodCall)
    }

    /** 匹配任意 String 参数 */
    fun anyString(): String = ArgumentMatchers.anyString()

    /** 匹配任意 Int 参数 */
    fun anyInt(): Int = ArgumentMatchers.anyInt()

    /** 匹配任意 Long 参数 */
    fun anyLong(): Long = ArgumentMatchers.anyLong()

    /** 匹配任意 Boolean 参数 */
    fun anyBoolean(): Boolean = ArgumentMatchers.anyBoolean()

    /** 匹配任意 List 参数 */
    fun anyList(): List<Any> = ArgumentMatchers.anyList()

    /** 匹配任意指定类型的对象 */
    inline fun <reified T> anyObject(): T = ArgumentMatchers.any()

    /** 匹配任意可为 null 的指定类型对象 */
    inline fun <reified T> anyNullable(): T? = ArgumentMatchers.isNull() ?: ArgumentMatchers.any()

    /**
     * 验证 mock 上的指定方法从未被调用
     *
     * @param mock Mock 对象
     * @param verification 验证表达式
     */
    fun verifyNever(mock: Any, verification: () -> Unit) {
        verification.invoke()
        Mockito.verify(mock, Mockito.never())
    }

    /**
     * 验证 mock 上的指定方法被恰好调用一次
     *
     * @param mock Mock 对象
     * @param verification 验证表达式
     */
    fun verifyOnce(mock: Any, verification: () -> Unit) {
        verification.invoke()
        Mockito.verify(mock, Mockito.times(1))
    }

    /**
     * 验证 mock 上的指定方法被调用了指定次数
     *
     * @param times 期望的调用次数
     * @param mock Mock 对象
     * @param verification 验证表达式
     */
    fun verifyTimes(times: Int, mock: Any, verification: () -> Unit) {
        verification.invoke()
        Mockito.verify(mock, Mockito.times(times))
    }

    /**
     * 验证 mock 上没有任何交互
     *
     * @param mock Mock 对象
     */
    fun verifyNoInteractions(mock: Any) {
        Mockito.verifyNoInteractions(mock)
    }

    /**
     * 验证 mock 上除了指定的验证之外没有其他交互
     *
     * @param mock Mock 对象
     */
    fun verifyNoMoreInteractions(mock: Any) {
        Mockito.verifyNoMoreInteractions(mock)
    }
}
