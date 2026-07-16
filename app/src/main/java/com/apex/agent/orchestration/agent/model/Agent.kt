package com.apex.agent.orchestration.agent.model

import com.apex.agent.core.memory.unified.AgentMode
import com.apex.agent.core.memory.unified.ModeAwareMemoryConfig
import com.apex.agent.core.multiagent.AgentCapabilityProfile


    fun getConfigSource(): ConfigSource {
        return when {
            useGlobalConfig -> ConfigSource.GLOBAL_CONFIG
            configId != null -> ConfigSource.SPECIFIC_CONFIG
            else -> ConfigSource.SELF
        }
    }

    fun copy(
        id: String = this.id,
        name: String = this.name,
        role: String = this.role,
        avatar: String? = this.avatar,
        systemPrompt: String = this.systemPrompt,
        mode: AgentMode = this.mode,
        modelConfig: ModelConfig = this.modelConfig,
        permissions: AgentPermissions = this.permissions,
        specialties: List<String> = this.specialties,
        orderIndex: Int = this.orderIndex,
        capabilityProfile: AgentCapabilityProfile = this.capabilityProfile,
        availableTools: List<String> = this.availableTools,
        apiEndpoints: List<String> = this.apiEndpoints,
        customCapabilities: Map<String, Any> = this.customCapabilities,
        learningConfig: LearningConfig = this.learningConfig,
        memoryConfig: MemoryConfig = this.memoryConfig,
        unifiedMemoryConfig: ModeAwareMemoryConfig? = this.unifiedMemoryConfig,
        configId: String? = this.configId,
        useGlobalConfig: Boolean = this.useGlobalConfig
    ): Agent = Agent(
        id = id,
        name = name,
        role = role,
        avatar = avatar,
        systemPrompt = systemPrompt,
        mode = mode,
        modelConfig = modelConfig,
        permissions = permissions,
        specialties = specialties,
        orderIndex = orderIndex,
        capabilityProfile = capabilityProfile,
        availableTools = availableTools,
        apiEndpoints = apiEndpoints,
        customCapabilities = customCapabilities,
        learningConfig = learningConfig,
        memoryConfig = memoryConfig,
        unifiedMemoryConfig = unifiedMemoryConfig,
        configId = configId,
        useGlobalConfig = useGlobalConfig
    )
}




