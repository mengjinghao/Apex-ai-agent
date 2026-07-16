package com.apex.agent.core.patterns

/**
 * 职责链模式 - 消息预处理流水线
 * 将消息依次通过验证、清理、增强和路由处理器，每个处理器决定是否继续传递
 */

/** 消息上下文 */
data class MessageContext(
    val rawMessage: String,
    val userId: String,
    val sessionId: String,
    val metadata: MutableMap<String, Any> = mutableMapOf(),
    var isValid: Boolean = true,
    var errorMessage: String? = null,
    var processedMessage: String = rawMessage
)

/**
 * 处理器接口
 * @param T 处理上下文类型
 */
interface Handler<T> {
    /** 下一个处理器 */
    var next: Handler<T>?

    /** 处理上下文，返回 true 表示继续传递，false 表示中断 */
    fun handle(context: T): Boolean

    /** 设置下一个处理器并返回它，支持链式调用 */
    fun setNext(handler: Handler<T>): Handler<T> {
        next = handler
        return handler
    }
}

/**
 * 职责链 - 管理处理器的添加、插入、移除和顺序执行
 */
class HandlerChain<T> {
    private var head: Handler<T>? = null
    private var tail: Handler<T>? = null

    /** 添加处理器到链尾 */
    fun add(handler: Handler<T>): HandlerChain<T> {
        if (head == null) {
            head = handler
            tail = handler
        } else {
            tail?.setNext(handler)
            tail = handler
        }
        return this
    }

    /** 在指定位置后插入处理器 */
    fun insert(after: Handler<T>, handler: Handler<T>): Boolean {
        var current = head
        while (current != null) {
            if (current == after) {
                handler.next = current.next
                current.setNext(handler)
                if (current == tail) tail = handler
                return true
            }
            current = current.next
        }
        return false
    }

    /** 移除指定处理器 */
    fun remove(handler: Handler<T>): Boolean {
        if (head == null) return false
        if (head == handler) {
            head = head?.next
            if (head == null) tail = null
            return true
        }
        var current = head
        while (current?.next != null) {
            if (current.next == handler) {
                current.next = handler.next
                if (handler == tail) tail = current
                return true
            }
            current = current.next
        }
        return false
    }

    /** 从头开始处理上下文 */
    fun process(context: T): Boolean {
        return head?.handle(context) ?: true
    }

    fun clear() {
        head = null
        tail = null
    }
}

/** 消息验证处理器 */
class ValidationHandler : Handler<MessageContext> {
    override var next: Handler<MessageContext>? = null

    override fun handle(context: MessageContext): Boolean {
        if (context.rawMessage.isBlank()) {
            context.isValid = false
            context.errorMessage = "Message cannot be empty"
            return false
        }
        if (context.rawMessage.length > 10000) {
            context.isValid = false
            context.errorMessage = "Message exceeds max length of 10000"
            return false
        }
        return next?.handle(context) ?: true
    }
}

/** 消息清理处理器 */
class SanitizationHandler(private val blockedPatterns: List<Regex> = emptyList()) : Handler<MessageContext> {
    override var next: Handler<MessageContext>? = null

    override fun handle(context: MessageContext): Boolean {
        var sanitized = context.processedMessage.trim()
        blockedPatterns.forEach { pattern ->
            sanitized = sanitized.replace(pattern, "[REDACTED]")
        }
        context.processedMessage = sanitized
        context.metadata["sanitized"] = sanitized.length < context.rawMessage.length
        return next?.handle(context) ?: true
    }
}

/** 消息增强处理器 */
class EnrichmentHandler : Handler<MessageContext> {
    override var next: Handler<MessageContext>? = null

    override fun handle(context: MessageContext): Boolean {
        context.metadata["enriched"] = true
        context.metadata["processingTime"] = System.currentTimeMillis()
        context.processedMessage = "[${context.userId}] ${context.processedMessage}"
        return next?.handle(context) ?: true
    }
}

/** 消息路由处理器 */
class RoutingHandler : Handler<MessageContext> {
    override var next: Handler<MessageContext>? = null

    override fun handle(context: MessageContext): Boolean {
        val routed = when {
            context.processedMessage.contains("/ai") -> "ai_agent"
            context.processedMessage.contains("/tool") -> "tool_executor"
            context.processedMessage.contains("/workflow") -> "workflow_engine"
            else -> "default"
        }
        context.metadata["route"] = routed
        return next?.handle(context) ?: true
    }
}

/** 消息预处理器 - 组装完整职责链 */
class MessagePreprocessor {
    private val chain = HandlerChain<MessageContext>()

    init {
        chain.add(ValidationHandler())
            .add(SanitizationHandler(listOf(Regex("<script>.*?</script>", RegexOption.IGNORE_CASE))))
            .add(EnrichmentHandler())
            .add(RoutingHandler())
    }

    fun process(message: MessageContext): Boolean = chain.process(message)
}
