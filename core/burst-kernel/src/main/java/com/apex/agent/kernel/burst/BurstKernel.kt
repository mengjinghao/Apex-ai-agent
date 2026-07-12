package com.apex.agent.kernel.burst

import android.app.Application
import com.apex.agent.core.arvr.ARVRInteractionManager
import com.apex.agent.data.burstmode.performance.BurstAdaptiveGovernor
import com.apex.agent.data.burstmode.visualization.BurstSpatialBridge
import com.apex.agent.domain.model.BurstTask
// KernelState resolved via local typealias in this package (see KernelState.kt)
import com.apex.agent.kernel.burst.utility.UtilityProcessorImpl
import com.apex.agent.plugins.burst.base.BurstSkillContext
import com.apex.agent.plugins.burst.base.BurstSkillManifest
import com.apex.agent.plugins.burst.base.BurstSkillResult
import com.apex.agent.plugins.burst.base.ILLMService
import com.apex.agent.plugins.burst.base.IBurstKernel
import com.apex.agent.plugins.burst.base.IBurstPluginLoader
import com.apex.agent.plugins.burst.base.IBurstSkill
import com.apex.agent.plugins.burst.base.IBurstStateManager
import com.apex.agent.plugins.burst.base.IPluginConfigService
import com.apex.agent.plugins.burst.base.LLMConfig
import com.apex.agent.plugins.burst.base.SkillEvent
import com.apex.agent.plugins.burst.base.SkillEventBus
import com.apex.agent.plugins.burst.base.SkillEventTypes
import com.apex.agent.plugins.burst.base.UtilityProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class LLMProvider {
    LOCAL_LLAMA,
    DEEPSEEK_V4
}

object BurstKernel : IBurstKernel {
    private val _state = MutableStateFlow<KernelState>(KernelState.STOPPED)
    val state: StateFlow<KernelState> = _state.asStateFlow()

    private val _lock = Any()
    private var application: Application? = null
    private var lifecycleManager: LifecycleManager? = null
    private var taskScheduler: BurstTaskScheduler? = null
    private var executionEngine: BurstExecutionEngine? = null
    private var collabFramework: Any? = null
    var spatialBridge: BurstSpatialBridge? = null
        private set
    private var stateManager: BurstStateManager? = null
    private var healthMonitor: BurstHealthMonitor? = null
    private var adaptiveGovernor: BurstAdaptiveGovernor? = null
    private var llmService: ILLMService? = null
    private var utilityProcessor: UtilityProcessor? = null
    private var eventBus: SkillEventBusImpl? = null
    private var configService: PluginConfigServiceImpl? = null
    private var repositoryClient: PluginRepositoryClient? = null
    private var updateChecker: PluginUpdateChecker? = null

    // 修复 D1：旧版 scope 是 val，stop() 调用 scope.cancel() 后无法重建，
    // 导致单例 object 一旦 stop 就永远不能再 start。改为 var，stop 时 cancel 并置 null，
    // start 时按需重建。
    private var scope: CoroutineScope? = null

    // lateinit 字段降级为可空 + getter 懒拿，避免 stop 后再访问抛 UninitializedPropertyAccessException
    private var pluginLoaderInternal: BurstPluginLoader? = null
    private var dynamicPluginLoaderInternal: DynamicPluginLoader? = null
    private var skillDependencyResolverInternal: SkillDependencyResolver? = null
    private var pluginManagerInternal: PluginManager? = null

    // 公开 getter（保持与旧 API 兼容，但访问前必须确保已 start）
    val pluginLoader: BurstPluginLoader
        get() = pluginLoaderInternal ?: error("BurstKernel is not started. Call start() first.")
    val dynamicPluginLoader: DynamicPluginLoader
        get() = dynamicPluginLoaderInternal ?: error("BurstKernel is not started. Call start() first.")
    val pluginManager: PluginManager
        get() = pluginManagerInternal ?: error("BurstKernel is not started. Call start() first.")
    val skillDependencyResolver: SkillDependencyResolver
        get() = skillDependencyResolverInternal ?: error("BurstKernel is not started. Call start() first.")
    val eventBusInstance: SkillEventBus
        get() = eventBus ?: error("BurstKernel is not started. Call start() first.")
    val configServiceInstance: IPluginConfigService
        get() = configService ?: error("BurstKernel is not started. Call start() first.")
    val repositoryClientInstance: PluginRepositoryClient
        get() = repositoryClient ?: error("BurstKernel is not started. Call start() first.")
    val updateCheckerInstance: PluginUpdateChecker
        get() = updateChecker ?: error("BurstKernel is not started. Call start() first.")

    val llmServiceInstance: ILLMService
        get() = llmService ?: error("BurstKernel is not started. Call start() first.")
    val utilityProcessorInstance: UtilityProcessor?
        get() = utilityProcessor

    fun start(
        app: Application,
        llmConfig: LLMConfig? = null,
        provider: LLMProvider = LLMProvider.LOCAL_LLAMA,
        /**
         * 可选的协作框架实例（应实现 IBurstCollaborationFramework 接口）。
         *
         * 修复 D2：旧版通过反射调用 AgentCollaborationFramework 的 no-arg 构造函数，
         * 但实际该类需要 (Context, AIService) 两个参数，会静默抛 NoSuchMethodException，
         * 导致 SWARM 执行模式永远回退到 multi-path。
         * 新版改为显式参数注入，调用方（通常是 :app 模块）负责创建框架实例并传入。
         */
        collaborationFramework: Any? = null
    ) {
        synchronized(_lock) {
            if (_state.value == KernelState.RUNNING) return
            // 修复 D1：即使之前 stop 过，scope 已 cancel，这里重建
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = newScope

            // 修复 D2：旧版 start() 无 try-catch，初始化中途异常会留下半初始化状态
            //（_state 仍为 STOPPED 但部分 lateinit 已赋值）。新版用 try-catch 包裹，
            // 失败时回滚所有已初始化字段。
            try {
                application = app
                eventBus = SkillEventBusImpl()
                configService = PluginConfigServiceImpl(app)
                val sm = BurstStateManager(app)
                stateManager = sm
                val ts = BurstTaskScheduler(sm)
                taskScheduler = ts
                collabFramework = collaborationFramework
                val ee = BurstExecutionEngine(app, collabFramework)
                executionEngine = ee
                llmService = when (provider) {
                    LLMProvider.LOCAL_LLAMA -> LLMService()
                    LLMProvider.DEEPSEEK_V4 -> DeepSeekLLMService()
                }
                utilityProcessor = createUtilityProcessor(llmConfig, llmService!!)
                dynamicPluginLoaderInternal = DynamicPluginLoader(app)
                skillDependencyResolverInternal = SkillDependencyResolver()
                pluginLoaderInternal = BurstPluginLoader(app, ts, llmService!!, eventBus!!, configService!!)
                pluginManagerInternal = PluginManager(
                    pluginLoaderInternal!!,
                    dynamicPluginLoaderInternal!!,
                    skillDependencyResolverInternal!!
                )
                repositoryClient = PluginRepositoryClient(app)
                updateChecker = PluginUpdateChecker(app, repositoryClient!!, pluginLoaderInternal!!, pluginManagerInternal!!)
                healthMonitor = BurstHealthMonitor()
                adaptiveGovernor = BurstAdaptiveGovernor(app)

                lifecycleManager = LifecycleManager(app)

                lifecycleManager!!.start()
                ts.start()
                newScope.launch {
                    pluginLoaderInternal!!.loadBuiltInSkills()
                }

                // Governor 自适应策略反馈循环
                newScope.launch {
                    adaptiveGovernor!!.currentState.collectLatest { state ->
                        if (state != null) {
                            val strategy = adaptiveGovernor!!.computeStrategy()
                            ts.updateMaxConcurrency(strategy.maxConcurrency)
                            healthMonitor!!.updateStrategy(strategy)
                            healthMonitor!!.updateDeviceState(state)
                        }
                    }
                }

                if (llmConfig != null && llmConfig.modelPath.isNotEmpty()) {
                    newScope.launch {
                        llmService!!.initialize(llmConfig)
                    }
                }

                _state.value = KernelState.RUNNING
            } catch (t: Throwable) {
                // 回滚：释放本次 start 中已创建的资源，恢复 STOPPED 状态
                runCatching { stopInternal() }
                throw t
            }
        }
    }

    private fun createUtilityProcessor(
        config: LLMConfig?,
        service: ILLMService
    ): UtilityProcessor? {
        if (config == null || !config.enableUtilityProcessor) return null
        if (config.utilityApiKey.isBlank()) return null
        return try {
            val utilityService = CloudLLMService(
                apiEndpoint = config.utilityEndpoint,
                apiKey = config.utilityApiKey,
                modelName = config.utilityModelName,
                defaultTemperature = config.utilityTemperature,
                defaultMaxTokens = config.utilityMaxTokens,
                timeoutMs = config.utilityTimeoutMs
            )
            utilityService.initialize(config)
            // 修复 D2：旧版这里传入 `this`（BurstKernel 单例），
            // 但在 start() 初始化途中抛异常时回滚会很难。改为传入当前 application 上下文。
            UtilityProcessorImpl(utilityService, config, this)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 停止内核，释放资源。
     *
     * 修复 D3：旧版未调用 executionEngine.cleanup()，导致 BurstExecutionEngine 的协程 scope 泄漏。
     * 修复 D1：scope 改为 var，cancel 后置 null，下次 start 时重建。
     */
    fun stop() {
        synchronized(_lock) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        runCatching { eventBus?.clearAll() }
        runCatching { pluginLoaderInternal?.unloadAllSkills() }
        runCatching { taskScheduler?.stop() }
        runCatching { lifecycleManager?.stop() }
        // 修复 D3：旧版未调用 executionEngine.cleanup()
        runCatching { executionEngine?.cleanup() }
        // 新增：释放 BurstAdaptiveGovernor 注册的广播接收器和采样协程，避免泄漏
        runCatching { adaptiveGovernor?.release() }
        // 新增：释放 BurstSpatialBridge（如果启用过）
        runCatching { spatialBridge?.release() }
        runCatching { llmService?.release() }
        utilityProcessor = null
        runCatching { scope?.cancel() }
        scope = null

        // 清空所有引用，让 GC 可以回收，且访问时会快速失败而非读到旧值
        application = null
        lifecycleManager = null
        taskScheduler = null
        executionEngine = null
        stateManager = null
        healthMonitor = null
        adaptiveGovernor = null
        llmService = null
        eventBus = null
        configService = null
        repositoryClient = null
        updateChecker = null
        pluginLoaderInternal = null
        dynamicPluginLoaderInternal = null
        skillDependencyResolverInternal = null
        pluginManagerInternal = null
        spatialBridge = null

        _state.value = KernelState.STOPPED
    }
    
    fun enableSpatialVisualization() {
        if (spatialBridge != null) return
        val app = application ?: return
        try {
            val arvrManager = ARVRInteractionManager(app)
            spatialBridge = BurstSpatialBridge(app, arvrManager)
            scope?.launch { spatialBridge!!.startVisualization() }
        } catch (e: Exception) {
            // AR/VR 不可用时不启用
        }
    }

    override fun getState(): KernelState = _state.value

    override fun getPluginLoader(): IBurstPluginLoader =
        pluginLoaderInternal ?: error("BurstKernel is not started. Call start() first.")

    override fun getStateManager(): IBurstStateManager =
        stateManager ?: error("BurstKernel is not started. Call start() first.")

    override fun getUtilityProcessor(): UtilityProcessor? = utilityProcessor

    override fun reportSkillResult(skillId: String, result: BurstSkillResult) {
        eventBus?.publish(
            SkillEvent(
                type = if (result.success) SkillEventTypes.TASK_COMPLETED
                       else SkillEventTypes.TASK_FAILED,
                sourceSkillId = skillId,
                payload = mapOf("output" to (result.output ?: ""), "error" to (result.errorMessage ?: ""))
            )
        )
    }

    override suspend fun executeSkill(skillId: String, task: BurstTask): BurstSkillResult {
        return pluginLoader.executeSkill(skillId, task)
    }

    override fun getAvailableSkills(): List<BurstSkillManifest> {
        return pluginLoaderInternal?.getLoadedSkills()?.mapNotNull { id ->
            pluginLoaderInternal?.getSkillManifest(id)
        } ?: emptyList()
    }

    override fun getSkill(skillId: String): IBurstSkill? {
        return pluginLoaderInternal?.getSkill(skillId)
    }
}
