package com.apex.di

import com.apex.core.model.ChatMessage
import com.apex.domain.config.GetApiConfigUseCase
import com.apex.engine.chat.LLMProvider
import com.apex.engine.chat.LLMRequestConfig
import com.apex.engine.chat.StreamEvent
import com.apex.lib.rage.RageEngine
import com.apex.lib.workflow.LlmInvoker
import com.apex.lib.workflow.WorkflowExecutor
import com.apex.rage.nativelib.RageNativeBridge
import com.apex.sdk.common.BridgeError
import com.apex.sdk.common.BridgeResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect

/**
 * Hilt module —— 把 :app 的 [LLMProvider]（OpenAICompatProvider）适配为
 * [:lib:workflow] 的 [LlmInvoker] 与 [:lib:rage] 的 [RageNativeBridge]
 * 所需的 suspend lambda。
 *
 * 设计动机：两个 lib 模块为保持"零 LLM 客户端依赖"原则，把 LLM 调用反查到宿主。
 * 本模块在宿主侧把 LLMProvider 的流式接口 [LLMProvider.stream] 收敛为单次 String
 * 响应：
 * - [WorkflowExecutor] 的 [LlmInvoker] 返回 [BridgeResult.Success] /
 *   [BridgeResult.Failure]（旧契约，保留）。
 * - [RageNativeBridge] 的 `llmInvoker: suspend (String, String?) -> String`
 *   返回纯 String；失败时抛异常（C++ 侧通过 JNI 异常检测失败）。
 *
 * 端点 / API Key 从 [GetApiConfigUseCase]（底层是 ApexKernel.configStore）
 * 实时读取——用户在设置页改 API Key 后，下一次工作流 / 狂暴任务自动用新配置。
 * 工作流节点的 `modelName` / `temperature` / `maxTokens` 通过 [LlmInvoker] 的
 * `config` 参数覆盖（仅在非空时生效）。
 *
 * 注：[LLMProvider.stream] 可能发射多次 [StreamEvent.Chunk] + 一次 [StreamEvent.Done]，
 * 这里用 [StringBuilder] + [collect] 把所有 Chunk.text 拼接成最终响应（PERF-39: 替代
 * 原来的 `fold(""){ acc + event.text }` 写法，O(n²)→O(n)）。错误用 [StreamEvent.Error] 表达：
 * - WorkflowExecutor 路径：转成 [BridgeResult.Failure] 返回。
 * - RageNativeBridge 路径：抛 [RuntimeException]（让 bridge 暴露给 C++ 侧）。
 *
 * **ARCH-3 变更**：原 `provideRageAgentArchitect` + `provideRageEngine(architect)`
 * 已移除（architect 类删除，行为下沉到 C++ 核心）。现改为
 * `provideRageNativeBridge` + `provideRageEngine(bridge)`。
 */
@Module
@InstallIn(SingletonComponent::class)
object InvokerModule {

    /**
     * 提供 [GetApiConfigUseCase] 单例。本身是无状态包装（读 ApexKernel.configStore），
     * 但通过 Hilt 暴露后便于其他 Hilt 模块（如本模块的 invoker）复用同一份配置读取逻辑。
     */
    @Provides
    @Singleton
    fun provideGetApiConfigUseCase(): GetApiConfigUseCase = GetApiConfigUseCase()

    /**
     * 提供 [WorkflowExecutor] 单例，注入 [LlmInvoker] 适配器。
     * LlmCall 节点会调用此 invoker 完成 LLM 推理。
     */
    @Provides
    @Singleton
    fun provideWorkflowExecutor(
        provider: LLMProvider,
        getConfig: GetApiConfigUseCase
    ): WorkflowExecutor {
        val executor = WorkflowExecutor()
        executor.llmInvoker = LlmInvoker { prompt, systemPrompt, config ->
            try {
                val baseConfig = getConfig.execute()
                val messages = buildList {
                    if (!systemPrompt.isNullOrBlank()) {
                        add(ChatMessage(role = "system", content = systemPrompt))
                    }
                    add(ChatMessage(role = "user", content = prompt))
                }
                val reqConfig = LLMRequestConfig(
                    endpoint = baseConfig.endpoint,
                    apiKey = baseConfig.apiKey,
                    model = (config?.get("modelName") as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?: baseConfig.model,
                    temperature = (config?.get("temperature") as? Number)?.toDouble()
                        ?: baseConfig.temperature,
                    maxTokens = (config?.get("maxTokens") as? Number)?.toInt()
                )
                // PERF-39: 用 StringBuilder + collect 替代 fold(""){ acc + event.text } (O(n²)→O(n))。
                // 长输出场景下原写法每 chunk 都新建 String,GC 压力 + 拷贝开销显著。
                val sb = StringBuilder()
                provider.stream(messages, emptyList(), reqConfig).collect { event ->
                    when (event) {
                        is StreamEvent.Chunk -> sb.append(event.text)
                        is StreamEvent.Error -> throw RuntimeException(event.message)
                        else -> {}
                    }
                }
                BridgeResult.Success(sb.toString())
            } catch (e: Exception) {
                BridgeResult.Failure(BridgeError.fromThrowable(e))
            }
        }
        return executor
    }

    /**
     * 提供 [RageNativeBridge] 单例 —— ARCH-3 替代原 `provideRageAgentArchitect`。
     *
     * Bridge 构造参数：
     * - `llmInvoker: suspend (String, String?) -> String` —— 适配 [LLMProvider.stream]
     *   为单次 String 响应（失败抛异常，C++ 侧通过 JNI 异常检测）。无 `config` 参数，
     *   使用 :app 当前配置即可（Rage 模式不暴露 per-call 模型覆盖）。
     * - `scope: CoroutineScope` —— bridge 内部用此 scope 把 C++ 同步回调转
     *   为 SharedFlow<NativeEvent>。使用 `SupervisorJob() + Dispatchers.IO`
     *   长生命周期 scope（与 bridge 单例同生命周期）。
     */
    @Provides
    @Singleton
    fun provideRageNativeBridge(
        provider: LLMProvider,
        getConfig: GetApiConfigUseCase
    ): RageNativeBridge {
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val llmInvoker: suspend (String, String?) -> String = { prompt, systemPrompt ->
            val baseConfig = getConfig.execute()
            val messages = buildList {
                if (!systemPrompt.isNullOrBlank()) {
                    add(ChatMessage(role = "system", content = systemPrompt))
                }
                add(ChatMessage(role = "user", content = prompt))
            }
            val reqConfig = LLMRequestConfig(
                endpoint = baseConfig.endpoint,
                apiKey = baseConfig.apiKey,
                model = baseConfig.model,
                temperature = baseConfig.temperature,
                maxTokens = null
            )
            // 失败时抛异常 —— C++ 侧通过 JNI ExceptionCheck 检测,
            // 由 AgentOrchestrator 决定是否重试 (受 maxRetries 约束)
            // PERF-39: StringBuilder + collect 替代 fold(+) (O(n²)→O(n))
            val sb = StringBuilder()
            provider.stream(messages, emptyList(), reqConfig).collect { event ->
                when (event) {
                    is StreamEvent.Chunk -> sb.append(event.text)
                    is StreamEvent.Error -> throw RuntimeException(event.message)
                    else -> {}
                }
            }
            sb.toString()
        }
        return RageNativeBridge(
            llmInvoker = llmInvoker,
            scope = bridgeScope
        )
    }

    /**
     * 提供 [RageEngine] 单例 —— 以注入好的 [RageNativeBridge] 作为底层桥,
     * 使所有 RageEngine API（startTask / cancelTask / listSkills / applyConfig ...）
     * 都共享同一个 bridge 实例（即同一个 llmInvoker 注入点）。
     *
     * **ARCH-3 变更**：原签名 `provideRageEngine(architect: RageAgentArchitect)`
     * 改为 `provideRageEngine(bridge: RageNativeBridge)`。RageEngine 构造参数
     * 也从 `architect = architect` 改为 `bridge = bridge`。
     */
    @Provides
    @Singleton
    fun provideRageEngine(bridge: RageNativeBridge): RageEngine =
        RageEngine(bridge = bridge)
}
