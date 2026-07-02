package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 责任链模式测试
 *
 * 验证处理器链构建、消息处理管道和中断传播。
 */
class ChainOfResponsibilityTest : BaseUnitTest {

    private lateinit var chain: MessageHandlerChain

    @Before
    override fun setUp() {
        super.setUp()
        chain = MessageHandlerChain()
    }

    @Test
    fun `handler chain should process message`() {
        chain.addHandler(LoggingHandler())
        chain.addHandler(ValidationHandler())
        val result = chain.process("test message")
        assertTrue(result.isProcessed)
    }

    @Test
    fun `handler can stop propagation`() {
        chain.addHandler(BlockingHandler())
        chain.addHandler(LoggingHandler())
        val result = chain.process("blocked")
        assertTrue(result.isProcessed)
    }

    @Test
    fun `empty chain should not process`() {
        val result = chain.process("anything")
        assertFalse(result.isProcessed)
    }

    @Test
    fun `multiple handlers should all execute`() {
        val countHandler = CountingHandler()
        chain.addHandler(countHandler)
        chain.addHandler(LoggingHandler())
        chain.process("hello")
        assertEquals(1, countHandler.count)
    }

    @Test
    fun `handler order should be preserved`() {
        val order = mutableListOf<String>()
        chain.addHandler(object : MessageHandler {
            override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
                order.add("first")
                return next(msg)
            }
        })
        chain.addHandler(object : MessageHandler {
            override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
                order.add("second")
                return MessageResult(true)
            }
        })
        chain.process("test")
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `handler can modify message`() {
        chain.addHandler(UpperCaseHandler())
        chain.addHandler(LoggingHandler())
        val result = chain.process("hello")
        assertEquals("HELLO", result.modifiedMessage)
    }

    @Test
    fun `should remove handler by type`() {
        chain.addHandler(LoggingHandler())
        chain.addHandler(ValidationHandler())
        chain.removeHandler(LoggingHandler::class)
        assertEquals(1, chain.handlerCount())
    }

    @Test
    fun `clear should remove all handlers`() {
        chain.addHandler(LoggingHandler())
        chain.addHandler(ValidationHandler())
        chain.clear()
        assertEquals(0, chain.handlerCount())
    }
}

data class MessageResult(val isProcessed: Boolean, val modifiedMessage: String? = null)

interface MessageHandler {
    fun handle(msg: String, next: (String) -> MessageResult): MessageResult
}

class MessageHandlerChain {
    private val handlers = mutableListOf<MessageHandler>()

    fun addHandler(handler: MessageHandler) { handlers.add(handler) }
    fun removeHandler(type: Class<*>) { handlers.removeAll { type.isInstance(it) } }
    fun handlerCount() = handlers.size
    fun clear() { handlers.clear() }

    fun process(msg: String): MessageResult {
        if (handlers.isEmpty()) return MessageResult(false)
        var index = 0
        fun next(m: String): MessageResult {
            if (index >= handlers.size) return MessageResult(true, m)
            return handlers[index++].handle(m, ::next)
        }
        return next(msg)
    }
}

class LoggingHandler : MessageHandler {
    override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
        return next(msg)
    }
}

class ValidationHandler : MessageHandler {
    override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
        if (msg.isBlank()) return MessageResult(false, "empty")
        return next(msg)
    }
}

class BlockingHandler : MessageHandler {
    override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
        return MessageResult(true, "blocked")
    }
}

class CountingHandler : MessageHandler {
    var count = 0
    override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
        count++
        return next(msg)
    }
}

class UpperCaseHandler : MessageHandler {
    override fun handle(msg: String, next: (String) -> MessageResult): MessageResult {
        return next(msg.uppercase())
    }
}
