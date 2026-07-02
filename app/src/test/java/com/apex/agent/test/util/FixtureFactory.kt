package com.apex.agent.test.util

import com.apex.agent.data.burstmode.model.BurstInput
import com.apex.agent.data.burstmode.model.BurstTask
import com.apex.agent.data.burstmode.model.TaskStatus
import com.apex.data.model.ChatMessage
import com.apex.data.model.Memory
import kotlin.random.Random

/**
 * 测试数据工厂
 *
 * 提供创建常用测试对象的便捷方法，用于单元测试中的数据准备。
 */
object FixtureFactory {

    private val random = Random

    /**
     * 创建聊天消息
     *
     * @param sender 发送者（"user" 或 "ai"），默认 "user"
     * @param content 消息内容，默认 "test message"
     * @param roleName 角色名称，默认 ""
     * @param provider 供应商，默认 ""
     * @param modelName 模型名称，默认 ""
     * @return ChatMessage 实例
     */
    fun createChatMessage(
        sender: String = "user",
        content: String = "test message",
        roleName: String = "",
        provider: String = "",
        modelName: String = ""
    ): ChatMessage = ChatMessage(
        sender = sender,
        content = content,
        roleName = roleName,
        provider = provider,
        modelName = modelName
    )

    /**
     * 创建记忆对象
     *
     * 注意：Memory 包含 ObjectBox 的 lateinit 字段（tags、properties、links 等），
     * 这些字段在纯单元测试中不可用。如需使用关联功能，请使用 Instrumented Test。
     *
     * @param title 记忆标题，默认 "test memory"
     * @param content 记忆内容，默认 "test content"
     * @param source 来源，默认 "test"
     * @return Memory 实例
     */
    fun createMemory(
        title: String = "test memory",
        content: String = "test content",
        source: String = "test"
    ): Memory = Memory(
        title = title,
        content = content,
        source = source
    )

    /**
     * 创建 Burst 任务
     *
     * @param id 任务 ID，默认 "task_1"
     * @param type 任务类型，默认 "text"
     * @param priority 优先级，默认 "normal"
     * @param status 任务状态，默认 TaskStatus.PENDING
     * @return BurstTask 实例
     */
    fun createBurstTask(
        id: String = "task_1",
        type: String = "text",
        priority: String = "normal",
        status: TaskStatus = TaskStatus.PENDING
    ): BurstTask = BurstTask(
        id = id,
        type = type,
        priority = priority,
        status = status,
        input = BurstInput(req = "test request")
    )

    /**
     * 创建指定数量的对象列表
     *
     * @param size 列表大小
     * @param factory 根据索引创建对象的工厂函数
     * @return 包含 [size] 个元素的对象列表
     */
    fun <T> createListOf(size: Int, factory: (Int) -> T): List<T> {
        return (0 until size).map(factory)
    }

    /**
     * 生成指定长度的随机字符串
     *
     * @param length 字符串长度，默认 10
     * @return 随机字符串（仅包含英文字母）
     */
    fun randomString(length: Int = 10): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * 生成指定范围内的随机整数
     *
     * @param min 最小值（包含），默认 0
     * @param max 最大值（包含），默认 100
     * @return 随机整数
     */
    fun randomInt(min: Int = 0, max: Int = 100): Int {
        return random.nextInt(min, max + 1)
    }
}
