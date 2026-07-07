package com.apex.agent.orchestration.sanxing

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.agent.model.ModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * 三星制（中书�?/ 门下�?/ 尚书省及六部）角色接�? */
interface SanxingRole {
    val roleId: String
    val roleName: String
    val systemPrompt: String
    val temperature: Float
    val permissions: Set<String>

    suspend fun handleMessage(message: AgentMessage): Flow<Result<AgentMessage>>
    fun getAgent(): Agent
}

/**
 * 共享的基础实现，具体角色只需提供 [SanxingRoleConfig]�? */
abstract class BaseSanxingRole : SanxingRole {
    abstract val config: SanxingRoleConfig

    override val roleId: String get() = config.roleId
    override val roleName: String get() = config.roleName
    override val systemPrompt: String get() = config.systemPrompt
    override val temperature: Float get() = config.temperature.toFloat()
    override val permissions: Set<String> get() = config.permissionTags

    override fun getAgent(): Agent = Agent(
        id = roleId,
        name = roleName,
        role = "${config.roleName}�?{config.title}",
        systemPrompt = systemPrompt,
        modelConfig = ModelConfig(
            provider = config.provider,
            model = config.defaultModel,
            temperature = config.temperature,
            topP = config.topP,
            maxTokens = config.maxTokens
        ),
        permissions = config.toAgentPermissions(),
        apiEndpoints = listOf(config.endpoint)
    )

    override suspend fun handleMessage(message: AgentMessage): Flow<Result<AgentMessage>> = flow {
        emit(
            Result.Success(
                AgentMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = roleId,
                    receiverId = message.senderId,
                    content = "�?{config.roleName}�?{config.title}】已处理消息�?{message.content}",
                    timestamp = System.currentTimeMillis()
                )
            )
        )
    }
}
