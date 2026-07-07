package com.apex.agent.orchestration.agent.model

import com.apex.agent.core.memory.unified.AgentMode
import com.apex.agent.core.memory.unified.ModeAwareMemoryConfig
import com.apex.agent.orchestration.agent.AgentCapabilityProfile

class Agent(
    val id: String,
    val name: String,
    val role: String,
    val avatar: String? = null,
    val systemPrompt: String = "",
    val mode: AgentMode = AgentMode.SINGLE_AGENT,
    val modelConfig: ModelConfig = ModelConfig(),
    val permissions: AgentPermissions = AgentPermissions(),
    val specialties: List<String> = emptyList(),
    val orderIndex: Int = 0,
    val capabilityProfile: AgentCapabilityProfile = AgentCapabilityProfile(),
    val availableTools: List<String> = emptyList(),
    val apiEndpoints: List<String> = emptyList(),
    val customCapabilities: Map<String, Any> = emptyMap(),
    val learningConfig: LearningConfig = LearningConfig(),
    val memoryConfig: MemoryConfig = MemoryConfig(),
    val unifiedMemoryConfig: ModeAwareMemoryConfig? = null,
    val configId: String? = null,
    val useGlobalConfig: Boolean = false
) {
    enum class ConfigSource {
        SELF,
        SPECIFIC_CONFIG,
        GLOBAL_CONFIG
    }

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

class ModelConfig(
    val provider: String = "openai",
    val apiKey: String = "",
    val model: String = "gpt-4o",
    val temperature: Double = 0.7,
    val topP: Double = 0.95,
    val maxTokens: Int = 4096,
    val presencePenalty: Double = 0.0,
    val frequencyPenalty: Double = 0.0,
    val responseFormat: String = "text",
    val stop: List<String> = emptyList()
)

class AgentPermissions(
    val canUseTools: Boolean = true,
    val canAccessInternet: Boolean = true,
    val canReadFiles: Boolean = true,
    val canWriteFiles: Boolean = true,
    val canCallOtherAgents: Boolean = true,
    val availableTools: List<String> = emptyList(),
    val apiEndpoints: List<String> = emptyList(),
    val customCapabilities: Map<String, Any> = emptyMap(),
    val maxConcurrentTasks: Int = 3,
    val timeoutSeconds: Int = 300
)

class LearningConfig(
    val enabled: Boolean = true,
    val adaptationRate: Double = 0.01,
    val memoryRetentionThreshold: Double = 0.7,
    val feedbackWeight: Double = 0.3,
    val autoTuningEnabled: Boolean = false
)

class MemoryConfig(
    val priorityLevels: Map<String, Double> = emptyMap(),
    val maxMemoryItems: Int = 1000,
    val memoryDecayRate: Double = 0.95,
    val sharedMemoryEnabled: Boolean = false,
    val contextInheritanceEnabled: Boolean = true
)
