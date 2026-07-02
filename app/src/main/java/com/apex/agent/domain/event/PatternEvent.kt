package com.apex.agent.domain.event

/**
 * 命令执行事件
 */
data class CommandExecutedEvent(
    val commandName: String,
    val commandId: String,
    val duration: Long,
    val success: Boolean,
    val undoable: Boolean
)

/**
 * 状态转换事件
 */
data class StateTransitionEvent(
    val agentId: String,
    val fromState: String,
    val toState: String,
    val trigger: String,
    val timestamp: Long
)

/**
 * 中介者消息事件
 */
data class MediatorMessageEvent(
    val from: String,
    val to: String,
    val messageType: String,
    val priority: Int,
    val delivered: Boolean
)

/**
 * 代理调用事件
 */
data class ProxyInvocationEvent(
    val proxyType: String,
    val targetMethod: String,
    val duration: Long,
    val cached: Boolean,
    val accessGranted: Boolean
)
