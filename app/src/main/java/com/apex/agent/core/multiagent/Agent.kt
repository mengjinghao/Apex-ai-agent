package com.apex.agent.core.multiagent

import com.apex.agent.core.memory.unified.AgentMode
import com.apex.agent.core.memory.unified.ModeAwareMemoryConfig

class Agent(
    val id: String,
    val name: String,
    val role: String,
    val avatar: String? = null,
    val systemPrompt: String = "",
    val mode: AgentMode = AgentMode.MULTI_AGENT,
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
