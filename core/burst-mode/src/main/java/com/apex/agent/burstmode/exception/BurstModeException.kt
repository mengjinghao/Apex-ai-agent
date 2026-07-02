package com.apex.agent.burstmode.exception

/**
 * 狂暴模式异常基类。
 *
 * 所有狂暴模式相关的异常都继承自此基类。
 * 业务侧可统一捕获 [BurstModeException] 处理所有狂暴模式错误。
 */
sealed class BurstModeException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * 未初始化异常。
     * 在 [com.apex.agent.burstmode.api.BurstMode] 未初始化时调用 execute 抛出。
     */
    class NotInitialized :
        BurstModeException("BurstMode is not initialized. Call initialize() first.")

    /**
     * 超时异常。
     * 任务执行超过配置的超时时间。
     *
     * @param taskId 任务 ID
     * @param timeoutMs 超时时间（毫秒）
     */
    class Timeout(
        val taskId: String,
        val timeoutMs: Long
    ) : BurstModeException("Task $taskId timed out after ${timeoutMs}ms")

    /**
     * 执行失败异常。
     * 任务执行过程中发生错误。
     *
     * @param taskId 任务 ID
     * @param errorMessage 错误消息
     * @param cause 原始异常
     */
    class ExecutionFailed(
        val taskId: String,
        val errorMessage: String,
        cause: Throwable? = null
    ) : BurstModeException("Task $taskId failed: $errorMessage", cause)

    /**
     * 配置异常。
     * 配置项无效或不一致。
     *
     * @param configField 配置字段名
     * @param reason 失败原因
     */
    class InvalidConfig(
        val configField: String,
        val reason: String
    ) : BurstModeException("Invalid config '$configField': $reason")

    /**
     * 预设切换失败异常。
     * 切换预设时所需的资源不可用（如本地 LLM 未安装）。
     *
     * @param preset 目标预设
     * @param reason 失败原因
     */
    class PresetSwitchFailed(
        val preset: com.apex.agent.burstmode.preset.BurstPreset,
        val reason: String
    ) : BurstModeException("Failed to switch to preset ${preset.displayName}: $reason")

    /**
     * LLM 服务不可用异常。
     * LLM 配置错误或服务无响应。
     *
     * @param provider LLM 提供方
     * @param reason 失败原因
     */
    class LlmUnavailable(
        val provider: com.apex.agent.burstmode.config.LlmProvider,
        val reason: String
    ) : BurstModeException("LLM provider ${provider.displayName} unavailable: $reason")

    /**
     * 内核异常。
     * [com.apex.agent.kernel.burst.BurstKernel] 内部错误。
     *
     * @param operation 操作名称
     * @param cause 原始异常
     */
    class KernelError(
        val operation: String,
        cause: Throwable
    ) : BurstModeException("BurstKernel error during '$operation': ${cause.message}", cause)

    /**
     * 资源不足异常。
     * 内存、并发数等资源不足。
     *
     * @param resource 资源类型
     * @param required 需要量
     * @param available 可用量
     */
    class InsufficientResource(
        val resource: String,
        val required: Int,
        val available: Int
    ) : BurstModeException("Insufficient $resource: required $required, available $available")
}
