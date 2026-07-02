package com.apex.agent.plugins.burst.base

class BurstSkillContext(
    val kernel: IBurstKernel,
    val skillId: String,
    val llmService: ILLMService? = null,
    val eventBus: SkillEventBus? = null,
    val configService: IPluginConfigService? = null,
    val utilityProcessor: UtilityProcessor? = null
) {
    val pluginLoader: IBurstPluginLoader
        get() = kernel.getPluginLoader()
    
    val stateManager: IBurstStateManager
        get() = kernel.getStateManager()
}
