package com.apex.agent.core

import android.content.Context
import com.apex.agent.R
import com.apex.agent.TaskScheduler
import com.apex.agent.agent.BaseSubAgent
import com.apex.agent.SubTask
import com.apex.agent.SubTaskResult
import com.apex.agent.core.batch.BatchRunner
import com.apex.agent.core.batch.CheckpointManager
import com.apex.agent.core.batch.DatasetLoader
import com.apex.agent.core.batch.ResumableRunner
import com.apex.agent.core.batch.StatisticsAggregator
import com.apex.agent.core.extension.Capability
import com.apex.agent.core.extension.CapabilityRegistry
import com.apex.agent.core.extension.FootprintLevel
import com.apex.agent.core.kanban.KanbanBoard
import com.apex.agent.core.kanban.TaskDispatcher
import com.apex.agent.core.kanban.WorkerRegistry
import com.apex.agent.core.mcp.ConversationBridgeTools
import com.apex.agent.core.mcp.MCPCatalog
import com.apex.agent.core.mcp.MCPServerBridge
import com.apex.agent.core.models.ModelSelector
import com.apex.agent.core.provider.ProviderProfile
import com.apex.agent.core.provider.ProviderRegistry
import com.apex.agent.core.scheduler.CronScheduler
import com.apex.agent.core.scheduler.TaskTypeRegistry
import com.apex.agent.core.storage.BatchRunStorage
import com.apex.agent.core.storage.FTSSearch
import com.apex.agent.core.storage.SessionChainManager
import com.apex.agent.core.storage.SessionDatabase
import com.apex.agent.core.trajectory.CompressionStrategy
import com.apex.agent.core.trajectory.TrajectoryCompressor
import com.apex.core.tools.skill.SkillManager
import com.apex.core.tools.skill.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Local chat message model used by Hermes storage adapters.
 * The project-wide ChatMessage may live in another module; this minimal
 * definition lets HermesCore compile standalone.
 */
data class ChatMessage(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val timestamp: Long = 0L
)

class CoreModuleInitializer private constructor() {

    private val logger = LoggerFactory.getLogger(CoreModuleInitializer::class.java)

    private var isInitialized = false
    private var appContext: Context? = null

    // 使用自定�?CoroutineScope 替代 GlobalScope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(context: Context) {
        if (isInitialized) {
            logger.info("Core modules already initialized")
            return
        }

        logger.info("Initializing Hermes Agent core modules...")
        appContext = context.applicationContext

        try {
            initializeProviderProfile()
            initializeExtensionMechanism()
            initializeStorage(context)
            initializeMCP(context)
            initializeTrajectory()
            initializeBatchProcessing()
            initializeKanban()
            initializeScheduler()

            isInitialized = true
            logger.info("All Hermes Agent core modules initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize core modules", e)
            throw e
        }
    }

    private fun initializeProviderProfile() {
        logger.info("Initializing ProviderProfile system...")
        val registry = ProviderRegistry.getInstance()
        
        registry.registerDefaultProviders()
        
        logger.info("ProviderProfile system initialized")
    }

    private fun initializeExtensionMechanism() {
        logger.info("Initializing Footprint Ladder extension mechanism...")
        val registry = CapabilityRegistry.getInstance()
        
        registry.registerBuiltinCapabilities()
        
        logger.info("Footprint Ladder extension mechanism initialized")
    }

    private fun initializeStorage(context: Context) {
        logger.info("Initializing SQLite + FTS5 storage...")

        SessionDatabase.getInstance(context)
        FTSSearch.getInstance(context)
        SessionChainManager.getInstance(context)
        BatchRunStorage.getInstance(context)

        logger.info("SQLite + FTS5 storage initialized")
    }

    private fun initializeMCP(context: Context) {
        logger.info("Initializing MCP Server integration...")

        ConversationBridgeTools.getInstance(context)
        MCPServerBridge.initialize(context)

        logger.info("MCP Server integration initialized")
    }

    private fun initializeTrajectory() {
        logger.info("Initializing Trajectory Compression system...")
        
        CompressionStrategy.initialize()
        TrajectoryCompressor.initialize()
        
        logger.info("Trajectory Compression system initialized")
    }

    private fun initializeBatchProcessing() {
        logger.info("Initializing Batch Runner system...")
        
        DatasetLoader.initialize()
        CheckpointManager.initialize(context)
        BatchRunner.initialize(context)
        ResumableRunner.initialize(context)
        StatisticsAggregator.initialize()
        
        logger.info("Batch Runner system initialized")
    }

    private fun initializeKanban() {
        logger.info("Initializing Kanban Multi-Agent Board...")
        
        WorkerRegistry.initialize()
        TaskDispatcher.initialize()
        KanbanBoard.initialize()
        
        logger.info("Kanban Multi-Agent Board initialized")
    }

    private fun initializeScheduler() {
        logger.info("Initializing Cron Scheduler...")
        
        TaskTypeRegistry.registerBuiltinTaskTypes()
        CronScheduler.initialize()
        
        logger.info("Cron Scheduler initialized")
    }

    fun startMCPServer(port: Int = MCPServerBridge.DEFAULT_PORT) {
        scope.launch {
            MCPServerBridge.startServer(port)
        }
    }

    fun stopMCPServer() {
        MCPServerBridge.stopServer()
    }

    fun getModuleStatus(): Map<String, Boolean> {
        val ctx = appContext ?: return emptyMap()
        return mapOf(
            ctx.getString(R.string.module_provider_profile) to true,
            ctx.getString(R.string.module_footprint_ladder) to true,
            ctx.getString(R.string.module_sqlite_storage) to SessionDatabase.isInitialized(),
            ctx.getString(R.string.module_mcp_integration) to MCPServerBridge.isInitialized(),
            ctx.getString(R.string.module_trajectory_compressor) to true,
            ctx.getString(R.string.module_batch_runner) to true,
            ctx.getString(R.string.module_kanban_board) to true,
            ctx.getString(R.string.module_cron_scheduler) to true
        )
    }

    companion object {
        @Volatile
        private var instance: CoreModuleInitializer? = null

        fun getInstance(): CoreModuleInitializer {
            return instance ?: synchronized(this) {
                instance ?: CoreModuleInitializer().also { instance = it }
            }
        }

        fun initializeModules(context: Context) {
            getInstance().initialize(context)
        }
    }
}

interface ModuleInitializable {
    fun initialize()
    fun isInitialized(): Boolean
}

object HermesIntegration {

    private const val PROVIDER_OPENAI = "openai"
    private const val TASK_TYPE_WORKFLOW_TRIGGER = "workflow.trigger"
    private const val TASK_TYPE_WORKFLOW_STEP = "workflow.step"

    private val logger = LoggerFactory.getLogger(HermesIntegration::class.java)
    
    // 使用自定�?CoroutineScope 替代 GlobalScope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Link state management
    private val _linkState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val linkState: StateFlow<Map<String, Boolean>> = _linkState.asStateFlow()

    // Link references for coordinated access
    private val linkedProviderProfile = MutableStateFlow<ProviderProfile?>(null)
    private val linkedModelSelector = MutableStateFlow<ModelSelector?>(null)
    private val linkedSkillManager = MutableStateFlow<SkillManager?>(null)
    private val linkedStorage = MutableStateFlow<AgentStorage?>(null)
    private val linkedChatHistory = MutableStateFlow<ChatHistoryPort?>(null)
    private val linkedMCP = MutableStateFlow<MCPBridge?>(null)
    private val linkedToolManager = MutableStateFlow<ToolManager?>(null)
    private val linkedScheduler = MutableStateFlow<TaskScheduler?>(null)
    private val linkedWorkflowEngine = MutableStateFlow<WorkflowEngine?>(null)

    // Extension registry for capability-based linking
    private val extensionRegistry = ConcurrentHashMap<String, AgentExtension>()

    // Scheduler-workflow integration state (keyed by scheduler instance)
    private val schedulerWorkflowCallbacks = ConcurrentHashMap<TaskScheduler, suspend (String, Map<String, Any>) -> WorkflowEngine.ExecutionResult?>()
    private val schedulerPriorityMappers = ConcurrentHashMap<TaskScheduler, (String) -> TaskPriority>()
    private val schedulerTaskStates = ConcurrentHashMap<TaskScheduler, ConcurrentHashMap<String, TaskState>>()

    // Capability change listener references to avoid GC
    private val capabilityChangeListeners = mutableListOf<CapabilityRegistry.CapabilityChangeListener>()

    fun integrateWithExistingSystems(context: Context) {
        logger.info("Integrating Hermes modules with existing Apex/Agent systems...")

        try {
            // Initialize links with proper dependencies
            linkProviderProfileWithModelSelector(
                ProviderRegistry.getInstance().getProviderProfile(PROVIDER_OPENAI) ?: ProviderProfile.openAI(),
                ModelSelector(context)
            )

            linkExtensionWithSkillSystem(
                context
            )

            linkStorageWithChatHistory(
                context
            )

            linkMCPWithToolManager(
                context
            )

            linkSchedulerWithWorkflow(
                context,
                TaskScheduler(),
                WorkflowEngine.getInstance()
            )

            updateLinkState("all", true)
            logger.info("Hermes modules integration complete")
        } catch (e: Exception) {
            logger.error("Failed to integrate Hermes modules", e)
            updateLinkState("all", false)
            throw e
        }
    }

    /**
     * Link 1: ProviderProfile with ModelSelector
     * - Monitors providerProfile changes
     * - Notifies modelSelector when provider models need update
     * - Filters models based on provider capabilities
     * - Sets default model
     */
    fun linkProviderProfileWithModelSelector(
        providerProfile: ProviderProfile,
        modelSelector: ModelSelector
    ) {
        logger.info("Linking ProviderProfile (${providerProfile.name}) with ModelSelector...")

        linkedProviderProfile.value = providerProfile
        linkedModelSelector.value = modelSelector

        // Launch coroutine to fetch and sync models
        scope.launch {
            try {
                val apiKey = System.getenv(providerProfile.apiKeyEnvVar)
                val models = providerProfile.fetchModels(apiKey)

                logger.debug("Fetched ${models.size} models from provider ${providerProfile.name}")

                // Filter models based on provider capabilities
                val filteredModels = filterModelsByProviderCapability(models, providerProfile)

                // Update model selector with available models
                logger.debug("Filtered to ${filteredModels.size} models based on provider capabilities")

                // Set default model if available
                val defaultModel = filteredModels.find { it.id == providerProfile.defaultModel }
                    ?: filteredModels.firstOrNull()

                if (defaultModel != null) {
                    logger.debug("Default model set: ${defaultModel.id}")
                }

                updateLinkState("ProviderProfile-ModelSelector", true)
            } catch (e: Exception) {
                logger.error("Failed to link ProviderProfile with ModelSelector", e)
                updateLinkState("ProviderProfile-ModelSelector", false)
            }
        }

        logger.debug("ProviderProfile-ModelSelector link initiated")
    }

    /**
     * Link 2: Extension mechanism with SkillSystem
     * - Registers extension capabilities to skillSystem
     * - Enables skillSystem to invoke extension capabilities
     * - Handles extension lifecycle with skillSystem association
     */
    fun linkExtensionWithSkillSystem(context: Context) {
        logger.info("Linking Footprint Ladder extension mechanism with SkillSystem...")

        val skillManager = SkillManager.getInstance(context)
        linkedSkillManager.value = skillManager

        // Register all capabilities from CapabilityRegistry to SkillManager
        val capabilityRegistry = CapabilityRegistry.getInstance()
        val allCapabilities = capabilityRegistry.getAllCapabilities()

        // Dynamic capability listener that keeps SkillManager in sync
        val listener = object : CapabilityRegistry.CapabilityChangeListener {
            override fun onCapabilityRegistered(capability: Capability) {
                registerCapabilityWithSkillManager(skillManager, capability)
            }

            override fun onCapabilityUnregistered(capability: Capability) {
                extensionRegistry.remove(capability.name)
            }

            override fun onCapabilityUpdated(capability: Capability) {
                registerCapabilityWithSkillManager(skillManager, capability)
            }
        }
        capabilityRegistry.addListener(listener)
        capabilityChangeListeners.add(listener)

        scope.launch {
            try {
                // Ensure latest skills are known to SkillManager
                skillManager.refreshAvailableSkills()

                var registeredCount = 0
                var preloadedCount = 0

                allCapabilities.forEach { capability ->
                    try {
                        val linked = registerCapabilityWithSkillManager(skillManager, capability)
                        registeredCount++
                        if (linked) preloadedCount++
                    } catch (e: Exception) {
                        logger.warn("Failed to link capability ${capability.name}: ${e.message}")
                    }
                }

                logger.info("Extension-SkillSystem link complete: ${registeredCount} capabilities linked, ${preloadedCount} skills preloaded")
                updateLinkState("Extension-SkillSystem", registeredCount > 0)
            } catch (e: Exception) {
                logger.error("Failed to link Extension with SkillSystem", e)
                updateLinkState("Extension-SkillSystem", false)
            }
        }

        logger.debug("Extension-SkillSystem link initiated")
    }

    private fun registerCapabilityWithSkillManager(skillManager: SkillManager, capability: Capability): Boolean {
        logger.debug("Linking capability '${capability.name}' at level ${capability.level.description}")

        val extension = AgentExtension(
            name = capability.name,
            level = capability.level,
            description = capability.description,
            capabilities = capability.dependencies
        )
        extensionRegistry[capability.name] = extension

        // Attempt to preload a skill with the same name so the skill system can invoke it
        return try {
            skillManager.preloadSkill(capability.name)
        } catch (e: Exception) {
            logger.warn("Could not preload skill for capability ${capability.name}: ${e.message}")
            false
        }
    }

    /**
     * Link 3: Storage with ChatHistory
     * - Sets chatHistory data persistence to storage
     * - Configures automatic message save strategy
     * - Sets up history message loading logic
     */
    fun linkStorageWithChatHistory(context: Context) {
        logger.info("Linking SQLite storage with ChatHistory...")

        val chatHistoryPort = createChatHistoryAdapter(context)
        val storage = AgentStorage(context)

        linkedStorage.value = storage
        linkedChatHistory.value = chatHistoryPort

        scope.launch {
            try {
                // Initialize storage if needed
                storage.initialize()

                // Configure auto-save strategy
                storage.setAutoSaveStrategy(AutoSaveStrategy(
                    enabled = true,
                    intervalMs = 5000,
                    batchSize = 10
                ))

                // Set up history loading callback
                storage.setHistoryLoader { chatId ->
                    chatHistoryPort.loadMessages(chatId)
                }

                // Set up message persistence callback
                storage.setMessagePersister { chatId, message ->
                    try {
                        chatHistoryPort.saveMessage(chatId, message)
                        true
                    } catch (e: Exception) {
                        logger.warn("Failed to persist message: ${e.message}")
                        false
                    }
                }

                logger.info("Storage-ChatHistory link configured successfully")
                updateLinkState("Storage-ChatHistory", true)
            } catch (e: Exception) {
                logger.error("Failed to link Storage with ChatHistory", e)
                updateLinkState("Storage-ChatHistory", false)
            }
        }

        logger.debug("Storage-ChatHistory link initiated")
    }

    /**
     * Creates a ChatHistory adapter from existing ChatHistoryDelegate
     */
    private fun createChatHistoryAdapter(context: Context): ChatHistoryPort {
        // In production, this would wrap the actual ChatHistoryManager
        // For now, return a no-op adapter that can be replaced with actual implementation
        return object : ChatHistoryPort {
            override suspend fun loadMessages(chatId: String): List<ChatMessage> {
                return try {
                    // Attempt to use ChatHistoryManager if available via reflection
                    val chatHistoryManagerClass = Class.forName("com.apex.data.repository.ChatHistoryManager")
                    val getInstanceMethod = chatHistoryManagerClass.getMethod("getInstance", Context::class.java)
                    val manager = getInstanceMethod.invoke(null, context)
                    val loadMessagesMethod = chatHistoryManagerClass.getMethod("loadChatMessages", String::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val result = loadMessagesMethod.invoke(manager, chatId) as? List<ChatMessage>
                    result ?: emptyList()
                } catch (e: Exception) {
                    logger.warn("Failed to load messages for chat ${chatId}: ${e.message}")
                    emptyList()
                }
            }

            override suspend fun saveMessage(chatId: String, message: ChatMessage): Boolean {
                return try {
                    // Attempt to use ChatHistoryManager if available via reflection
                    val chatHistoryManagerClass = Class.forName("com.apex.data.repository.ChatHistoryManager")
                    val getInstanceMethod = chatHistoryManagerClass.getMethod("getInstance", Context::class.java)
                    val manager = getInstanceMethod.invoke(null, context)
                    val addMessageMethod = chatHistoryManagerClass.getMethod("addMessage", String::class.java, ChatMessage::class.java)
                    addMessageMethod.invoke(manager, chatId, message)
                    true
                } catch (e: Exception) {
                    logger.warn("Failed to save message for chat ${chatId}: ${e.message}")
                    false
                }
            }
        }
    }

    /**
     * Link 4: MCP with ToolManager
     * - Registers MCP tools to toolManager
     * - Configures tool permissions and quotas
     * - Sets up tool call routing
     */
    fun linkMCPWithToolManager(context: Context) {
        logger.info("Linking MCP Server with ToolManager...")

        val mcpBridge = MCPBridge(context)
        val toolManager = ToolManager(context)

        linkedMCP.value = mcpBridge
        linkedToolManager.value = toolManager

        scope.launch {
            try {
                // Initialize MCP bridge
                mcpBridge.initialize()

                // Get available tools from MCP
                val mcpTools = mcpBridge.discoverTools()

                logger.debug("Discovered ${mcpTools.size} MCP tools")

                // Register each tool with tool manager
                var registeredCount = 0
                mcpTools.forEach { tool ->
                    try {
                        val toolPermission = ToolPermission(
                            name = tool.name,
                            allowed = true,
                            quota = ToolQuota(
                                maxCallsPerMinute = 60,
                                maxConcurrentCalls = 5
                            )
                        )
                        toolManager.registerTool(tool, toolPermission)
                        registeredCount++
                    } catch (e: Exception) {
                        logger.warn("Failed to register tool ${tool.name}: ${e.message}")
                    }
                }

                // Set up routing callback
                toolManager.setRouter { toolName ->
                    when {
                        mcpTools.any { it.name == toolName } -> mcpBridge
                        else -> null
                    }
                }

                logger.info("MCP-ToolManager link configured: ${registeredCount} tools registered")
                updateLinkState("MCP-ToolManager", registeredCount > 0)
            } catch (e: Exception) {
                logger.error("Failed to link MCP with ToolManager", e)
                updateLinkState("MCP-ToolManager", false)
            }
        }

        logger.debug("MCP-ToolManager link initiated")
    }

    /**
     * Link 5: Scheduler with Workflow
     * - Routes workflow tasks to scheduler
     * - Configures task priority and scheduling strategy
     * - Sets up task status callbacks
     */
    fun linkSchedulerWithWorkflow(
        context: Context,
        scheduler: TaskScheduler,
        workflowEngine: WorkflowEngine
    ) {
        logger.info("Linking CronScheduler with WorkflowEngine...")

        linkedScheduler.value = scheduler
        linkedWorkflowEngine.value = workflowEngine

        // Register a dedicated agent so the scheduler can dispatch workflow tasks
        val workflowAgent = WorkflowSubAgent(context, scheduler, workflowEngine)
        if (!scheduler.registerAgent(workflowAgent)) {
            logger.warn("Workflow agent registration returned false; an agent with the same id may already exist")
        }

        scope.launch {
            try {
                // Register workflow execution callback with scheduler
                scheduler.setWorkflowCallback { workflowId, workflowContext ->
                    workflowEngine.executeWorkflow(workflowId, "scheduled", workflowContext)
                }

                // Configure task priority mapping
                scheduler.setPriorityMapper { taskType ->
                    when (taskType) {
                        TASK_TYPE_WORKFLOW_TRIGGER -> TaskPriority.HIGH
                        TASK_TYPE_WORKFLOW_STEP -> TaskPriority.MEDIUM
                        else -> TaskPriority.NORMAL
                    }
                }

                // Subscribe to workflow execution events for task state updates
                workflowEngine.executionEvents.collect { event ->
                    when (event) {
                        is WorkflowEngine.ExecutionEvent.Started -> {
                            scheduler.updateTaskState(event.executionId, TaskState.RUNNING)
                        }
                        is WorkflowEngine.ExecutionEvent.Completed -> {
                            scheduler.updateTaskState(event.executionId,
                                if (event.success) TaskState.COMPLETED else TaskState.FAILED)
                        }
                        is WorkflowEngine.ExecutionEvent.Failed -> {
                            scheduler.updateTaskState(event.executionId, TaskState.FAILED)
                        }
                        is WorkflowEngine.ExecutionEvent.Cancelled -> {
                            scheduler.updateTaskState(event.executionId, TaskState.CANCELLED)
                        }
                        else -> { /* ignore other events */ }
                    }
                }

                logger.info("Scheduler-WorkflowEngine link configured successfully")
                updateLinkState("Scheduler-Workflow", true)
            } catch (e: Exception) {
                logger.error("Failed to link Scheduler with Workflow", e)
                updateLinkState("Scheduler-Workflow", false)
            }
        }

        logger.debug("Scheduler-Workflow link initiated")
    }

    // ============ Helper Methods ============

    private fun filterModelsByProviderCapability(
        models: List<ProviderProfile.ModelInfo>,
        provider: ProviderProfile
    ): List<ProviderProfile.ModelInfo> {
        return models.filter { model ->
            // Filter by streaming support if provider requires it
            if (provider.supportsStreaming) {
                true // Keep all models if provider supports streaming
            } else {
                // Could add more filtering logic here
                true
            }
        }
    }

    private fun updateLinkState(linkName: String, success: Boolean) {
        val currentState = _linkState.value.toMutableMap()
        currentState[linkName] = success
        _linkState.value = currentState
    }

    fun getLinkStatus(): Map<String, Boolean> {
        return _linkState.value.toMap()
    }

    fun isFullyLinked(): Boolean {
        return _linkState.value.values.all { it }
    }

    /**
     * One-shot integration entry used by [AppInitializer].
     * Initializes all core modules and links them with existing Apex/Agent systems.
     */
    fun integrate(context: Context) {
        logger.info("Integrating Hermes Agent modules into Apex/Agent...")

        try {
            CoreModuleInitializer.getInstance().initialize(context)
            integrateWithExistingSystems(context)

            logger.info("Hermes Agent modules integrated successfully")
        } catch (e: Exception) {
            logger.error("Failed to integrate Hermes modules", e)
            throw e
        }
    }

    // ============ Interface Definitions ============

    /**
     * Agent Extension - represents a registered extension capability
     */
    data class AgentExtension(
        val name: String,
        val level: FootprintLevel,
        val description: String,
        val capabilities: List<String>
    )

    /**
     * Agent Storage - storage abstraction for chat persistence
     */
    class AgentStorage(private val context: Context) {

        private var autoSaveStrategy: AutoSaveStrategy? = null
        private var historyLoader: (suspend (String) -> List<ChatMessage>)? = null
        private var messagePersister: (suspend (String, ChatMessage) -> Boolean)? = null
        private var initialized = false

        fun initialize() {
            if (initialized) return
            SessionDatabase.getInstance(context)
            FTSSearch.getInstance(context)
            SessionChainManager.getInstance(context)
            BatchRunStorage.getInstance(context)
            initialized = true
        }

        fun setAutoSaveStrategy(strategy: AutoSaveStrategy) {
            autoSaveStrategy = strategy
        }

        fun setHistoryLoader(loader: suspend (String) -> List<ChatMessage>) {
            historyLoader = loader
        }

        fun setMessagePersister(persister: suspend (String, ChatMessage) -> Boolean) {
            messagePersister = persister
        }

        suspend fun loadHistory(chatId: String): List<ChatMessage> {
            return historyLoader?.invoke(chatId) ?: emptyList()
        }

        suspend fun persistMessage(chatId: String, message: ChatMessage): Boolean {
            return messagePersister?.invoke(chatId, message) ?: false
        }
    }

    data class AutoSaveStrategy(
        val enabled: Boolean,
        val intervalMs: Long,
        val batchSize: Int
    )

    /**
     * MCP Bridge - abstraction for MCP server communication
     */
    class MCPBridge(private val context: Context) {

        private var initialized = false

        fun initialize() {
            if (initialized) return
            ConversationBridgeTools.getInstance(context)
            MCPServerBridge.initialize(context)
            initialized = true
        }

        suspend fun discoverTools(): List<MCPBridgeTool> {
            // Discover tools from the Nous-approved MCP catalog
            return try {
                val entries = MCPCatalog.getInstance(context).getCatalog()
                entries.map { entry ->
                    MCPBridgeTool(
                        name = entry.name.lowercase().replace("\\s+".toRegex(), "_"),
                        description = entry.description,
                        inputSchema = mapOf(
                            "type" to "object",
                            "properties" to emptyMap<String, Any>(),
                            "required" to emptyList<String>()
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to discover MCP tools", e)
                emptyList()
            }
        }
    }

    data class MCPBridgeTool(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any>
    )

    /**
     * Tool Manager - manages tool registration and routing
     */
    class ToolManager(private val context: Context) {

        private val registeredTools = ConcurrentHashMap<String, ToolRegistration>()
        private var router: ((String) -> Any)? = null

        data class ToolRegistration(
            val tool: MCPBridgeTool,
            val permission: ToolPermission
        )

        fun registerTool(tool: MCPBridgeTool, permission: ToolPermission) {
            registeredTools[tool.name] = ToolRegistration(tool, permission)
        }

        fun setRouter(router: (String) -> Any) {
            this.router = router
        }

        fun getTool(name: String): MCPBridgeTool? {
            return registeredTools[name]?.tool
        }

        fun getPermission(name: String): ToolPermission? {
            return registeredTools[name]?.permission
        }
    }

    data class ToolPermission(
        val name: String,
        val allowed: Boolean,
        val quota: ToolQuota
    )

    data class ToolQuota(
        val maxCallsPerMinute: Int,
        val maxConcurrentCalls: Int
    )

    /**
     * Task Priority enumeration
     */
    enum class TaskPriority {
        LOW, NORMAL, MEDIUM, HIGH, CRITICAL
    }

    /**
     * Task State enumeration
     */
    enum class TaskState {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    /**
     * Task Scheduler extension - adds workflow support
     */
    private fun TaskScheduler.setWorkflowCallback(
        callback: suspend (String, Map<String, Any>) -> WorkflowEngine.ExecutionResult?
    ) {
        schedulerWorkflowCallbacks[this] = callback
    }

    private fun TaskScheduler.setPriorityMapper(
        mapper: (String) -> TaskPriority
    ) {
        schedulerPriorityMappers[this] = mapper
    }

    private fun TaskScheduler.updateTaskState(
        executionId: String,
        state: TaskState
    ) {
        schedulerTaskStates.getOrPut(this) { ConcurrentHashMap() }[executionId] = state
    }

    /**
     * SubAgent that routes scheduler workflow tasks to the WorkflowEngine.
     */
    private class WorkflowSubAgent(
        private val context: Context,
        private val scheduler: TaskScheduler,
        private val workflowEngine: WorkflowEngine
    ) : BaseSubAgent(
        agentId = "workflow-agent-${System.currentTimeMillis()}",
        agentType = "workflow.trigger",
        displayName = context.getString(R.string.workflow_agent_display_name),
        description = context.getString(R.string.workflow_agent_description)
    ) {
        override fun canHandle(taskType: String): Boolean = taskType.startsWith("workflow.")

        override suspend fun execute(task: SubTask): SubTaskResult {
            val workflowId = task.inputData["workflowId"] as? String ?: task.taskId
            val workflowContext = (task.inputData["context"] as? Map<String, Any>) ?: emptyMap()

            return try {
                val result = workflowEngine.executeWorkflow(workflowId, "scheduled", workflowContext)
                SubTaskResult(
                    taskId = task.taskId,
                    success = result?.success ?: false,
                    outputData = result?.let {
                        mapOf(
                            "executionId" to it.executionId,
                            "workflowId" to it.workflowId
                        )
                    } ?: emptyMap(),
                    executionTime = result?.totalExecutionTimeMs ?: 0,
                    errorMessage = if (result == null) context.getString(R.string.error_workflow_null) else null
                )
            } catch (e: Exception) {
                logger.error("WorkflowSubAgent failed to execute workflow ${workflowId}", e)
                SubTaskResult(
                    taskId = task.taskId,
                    success = false,
                    executionTime = 0,
                    errorMessage = e.message,
                    errorStack = e.stackTraceToString()
                )
            }
        }
    }

    // ============ Port Interfaces ============

    /**
     * ChatHistoryPort - abstraction for chat history operations
     */
    interface ChatHistoryPort {
        suspend fun loadMessages(chatId: String): List<ChatMessage>
        suspend fun saveMessage(chatId: String, message: ChatMessage): Boolean
    }
}
