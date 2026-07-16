package com.apex.agent.core.multiagent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 下一?Agent 系统 - 整合所有功能！
 * 参? OpenClaw + AgentX + UnisonAI
 */
class NextGenAgentSystem private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NextGenAgentSystem"

        @Volatile
        private var instance: NextGenAgentSystem? = null

        fun getInstance(context: Context): NextGenAgentSystem {
            return instance ?: synchronized(this) {
                instance ?: NextGenAgentSystem(context).also { instance = it }
            }
        }
    }

    val superAgentManager: SuperAgentSystemManager = SuperAgentSystemManager.getInstance(context)
    val pluginSystem: PluginSystem = PluginSystem(context)
    val multiChannel: MultiChannelSystem = MultiChannelSystem(context)
    val modelOrchestrator: MultiModelOrchestrator = MultiModelOrchestrator(context)
    val reasoningFramework: ReasoningFramework = ReasoningFramework(context)
    val intentClassifier: IntentClassifier = IntentClassifier(context)
    val reflectionSystem: ReflectionSystem = ReflectionSystem(context)
    val fileToolSystem: FileToolSystem = FileToolSystem(context)
    val taskPlanner: TaskPlanner = TaskPlanner(context)

    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize() {
        pluginSystem.registerPlugin(WebSearchPlugin())
        pluginSystem.registerPlugin(CodeGeneratorPlugin())

        multiChannel.registerAdapter(TextChannelAdapter())
        multiChannel.registerAdapter(VoiceChannelAdapter())

        ModelProvider.values().forEach { provider ->
            modelOrchestrator.configureProvider(
                ModelConfig(
                    provider = provider,
                    modelName = provider.displayName,
                    isEnabled = true
                )
            )
        }

        scope.launch {
            superAgentManager.initialize()
        }
    }

    fun processIntelligentRequest(
        userInput: String,
        reasoningType: ReasoningType? = null,
        onProgress: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        scope.launch {
            onProgress("?? 分析请求...")

            val reasoningResult = if (reasoningType != null) {
                when (reasoningType) {
                    ReasoningType.CHAIN_OF_THOUGHT -> reasoningFramework.chainOfThoughtReasoning(userInput)
                    ReasoningType.TREE_OF_THOUGHT -> reasoningFramework.treeOfThoughtReasoning(userInput)
                    ReasoningType.REACT -> reasoningFramework.reactReasoning(userInput)
                    ReasoningType.REFLECTION -> reasoningFramework.reflectionReasoning(userInput)
                }
            } else {
                reasoningFramework.autoReasoning(userInput)
            }

            onProgress("?? 完成推理...")

            val skills = pluginSystem.getAvailableSkills()
            val relevantSkill = skills.firstOrNull { skill ->
                userInput.contains(skill.name, ignoreCase = true)
            }

            if (relevantSkill != null) {
                onProgress("?? 调用 ${relevantSkill.name}...")
                val input = PluginInput(userInput)
                pluginSystem.executeSkill(relevantSkill.id, input)
            }

            val request = ModelRequest(
                query = userInput,
                context = reasoningResult.steps.map { it.thought }
            )
            val bestProvider = modelOrchestrator.selectOptimalProvider(request)
            modelOrchestrator.switchProvider(bestProvider)

            onProgress("? 使用 ${bestProvider.displayName} 处理...")

            val response = "? 处理完成！\n\n" + reasoningFramework.formatReasoning(reasoningResult)

            multiChannel.sendMessage(response)

            onComplete(response)
        }
    }

    fun getCapabilities(): Map<String, Any> {
        return mapOf(
            "plugins" to pluginSystem.getAllPlugins().map { it.name },
            "skills" to pluginSystem.getAvailableSkills().map { it.name },
            "channels" to multiChannel.getAvailableChannels(),
            "models" to modelOrchestrator.getAvailableProviders(),
            "reasoning" to ReasoningType.values().map { it.name },
            "system_health" to superAgentManager.systemState.value
        )
    }
}
