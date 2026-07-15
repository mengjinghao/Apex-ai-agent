
package com.apex.agent.core.application

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.apex.util.AppLogger
import com.apex.core.tools.AIToolHandler
import com.apex.core.workflow.WorkflowSchedulerInitializer
import com.apex.util.ImagePoolManager
import com.apex.util.MediaPoolManager
import com.apex.util.TextSegmenter

class AppInitializer(private val context: Context) {
    
    companion object {
        private const val TAG = "AppInitializer"
        private const val DELAY_CRITICAL = 100L
        private const val DELAY_NORMAL = 500L
        private const val DELAY_LOW = 1000L
    }
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // [优化2] 用于跟踪顺序执行 vs 并行执行的耗时对比
    private val taskDurations = java.util.concurrent.atomic.AtomicLong(0L)

    sealed class InitializationPhase(val priority: Int) {
        data object Critical : InitializationPhase(0)
        data object Normal : InitializationPhase(1)
        data object Low : InitializationPhase(2)
    }
        private val initializationTasks = mutableListOf<InitializationTask>()
    
    data class InitializationTask(
        val name: String,
        val phase: InitializationPhase,
        val isBlocking: Boolean = false,
        val task: suspend () -> Unit
    )
    
    init {
        registerTasks()
    }
        private fun registerTasks() {
        // Critical phase - needs to be done early but can be async
        initializationTasks.add(
            InitializationTask(
                name = "CustomEmojiRepository",
                phase = InitializationPhase.Critical,
                isBlocking = false
            ) {
                com.apex.agent.data.repository.CustomEmojiRepository
                    .getInstance(context)
                    .initializeBuiltinEmojis()
                AppLogger.d(TAG, "CustomEmojiRepository initialized")
            }
        )
        
        // Normal phase - can wait a bit
        initializationTasks.add(
            InitializationTask(
                name = "TextSegmenter",
                phase = InitializationPhase.Normal,
                isBlocking = false
            ) {
                com.apex.agent.util.TextSegmenter.initialize(context)
                AppLogger.d(TAG, "TextSegmenter initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "ImagePoolPreload",
                phase = InitializationPhase.Normal,
                isBlocking = false
            ) {
                withContext(Dispatchers.IO) {
                    com.apex.agent.util.ImagePoolManager.preloadFromDisk()
                }
                AppLogger.d(TAG, "ImagePool preloaded")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "MediaPoolPreload",
                phase = InitializationPhase.Normal,
                isBlocking = false
            ) {
                withContext(Dispatchers.IO) {
                    com.apex.agent.util.MediaPoolManager.preloadFromDisk()
                }
                AppLogger.d(TAG, "MediaPool preloaded")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "AIToolHandler",
                phase = InitializationPhase.Normal,
                isBlocking = false
            ) {
                val toolHandler = com.apex.agent.core.tools.AIToolHandler
                    .getInstance(context)
                toolHandler.registerDefaultTools()
                AppLogger.d(TAG, "AIToolHandler initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "WorkflowScheduler",
                phase = InitializationPhase.Normal,
                isBlocking = false
            ) {
                com.apex.agent.core.workflow.WorkflowSchedulerInitializer
                    .initialize(context)
                AppLogger.d(TAG, "WorkflowScheduler initialized")
            }
        )
        
        // Low phase - can wait longer
        initializationTasks.add(
            InitializationTask(
                name = "UserProfileManager",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                val memoryRepository = com.apex.agent.data.repository
                    .MemoryRepository.getInstance(context)
                com.apex.agent.core.userprofile.UserProfileManager
                    .getInstance(context, memoryRepository)
                AppLogger.d(TAG, "UserProfileManager initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "BehaviorAnalysisManager",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                com.apex.agent.core.behavior.BehaviorAnalysisManager
                    .getInstance(context)
                AppLogger.d(TAG, "BehaviorAnalysisManager initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "EmotionAnalysisManager",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                com.apex.agent.core.emotion.EmotionAnalysisManager
                    .getInstance(context)
                AppLogger.d(TAG, "EmotionAnalysisManager initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "InterestManagementManager",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                com.apex.agent.core.interest.InterestManagementManager
                    .getInstance(context)
                AppLogger.d(TAG, "InterestManagementManager initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "ProfileEvolutionManager",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                val memoryRepository = com.apex.agent.data.repository
                    .MemoryRepository.getInstance(context)
                com.apex.agent.core.profileevolution.ProfileEvolutionManager
                    .getInstance(context, memoryRepository)
                AppLogger.d(TAG, "ProfileEvolutionManager initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "RoomBackupScheduler",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                val prefs = com.apex.agent.data.backup
                    .RoomDatabaseBackupPreferences.getInstance(context)
        if (prefs.isDailyBackupEnabled()) {
                    com.apex.agent.data.backup.RoomDatabaseBackupScheduler
                        .ensureScheduled(context)
                } else {
                    com.apex.agent.data.backup.RoomDatabaseBackupScheduler
                        .cancelScheduled(context)
                }
                AppLogger.d(TAG, "RoomBackupScheduler initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "HermesAgentIntegration",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                com.apex.agent.core.HermesIntegration.integrate(context)
                AppLogger.d(TAG, "HermesAgentIntegration initialized")
            }
        )
        
        initializationTasks.add(
            InitializationTask(
                name = "UIHierarchyManager",
                phase = InitializationPhase.Low,
                isBlocking = false
            ) {
                com.apex.agent.ui.main.ApexApp.uiHierarchyManager.initialize()
                AppLogger.d(TAG, "UIHierarchyManager initialized")
            }
        )

    // [优化] 各阶段仍保持顺序（Critical→Normal→Low）以保证依赖关系
    // 但每个阶段内部的任务改为并发执行 (async + awaitAll)
        fun startInitialization() {
        AppLogger.d(TAG, "Starting phased initialization [并发模式]")
        
        applicationScope.launch {
            executePhase(InitializationPhase.Critical, DELAY_CRITICAL)
            delay(DELAY_NORMAL)
            executePhase(InitializationPhase.Normal, 0)
            delay(DELAY_LOW)
            executePhase(InitializationPhase.Low, 0)
        }
    }
    
    // [优化] 同阶段任务并发执行，而非顺序等待
    // 旧方式 forEach { task.task() } — 总耗时 = 各任务耗时之和
    // 新方式 async + awaitAll — 总耗时 ≈ 最慢单个任务耗时
    private suspend fun executePhase(phase: InitializationPhase, delayMs: Long) {
        if (delayMs > 0) {
            delay(delayMs)
        }
        val tasks = initializationTasks.filter { it.phase == phase }
        AppLogger.d(TAG, "Executing ${tasks.size} tasks in ${phase::class.simpleName} phase [并行]")
        val phaseStart = System.currentTimeMillis()

        tasks.map { task ->
            async(Dispatchers.Default) {
                val taskStart = System.currentTimeMillis()
                try {
                    task.task()
        val taskDuration = System.currentTimeMillis() - taskStart
                    AppLogger.d(
                        TAG,
                        "Task '${task.name}' completed in ${taskDuration}ms"
                    )
                    // [优化2] 累积单任务耗时，用于计算"顺序执行预计总耗时"
                    taskDurations.addAndGet(taskDuration)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Task '${task.name}' failed", e)
                }
            }
        }.awaitAll()
        val phaseDuration = System.currentTimeMillis() - phaseStart
        val sequentialTotal = taskDurations.getAndSet(0)
        healthCheck.recordPhaseExecution(
            context = context,
            phaseName = phase::class.simpleName ?: "Unknown",
            sequentialTotalMs = sequentialTotal,
            actualParallelMs = phaseDuration
        )
        AppLogger.d(TAG, "${phase::class.simpleName} phase total: ${phaseDuration}ms (并行执行, 加速比=${if (phaseDuration > 0) "%.2f".format(sequentialTotal.toDouble() / phaseDuration) else "N/A"}x)")
    }

    // 健康检查：轻量级反射调用（避免直接耦合）
    private object HealthCheckBridge {
        fun recordPhaseExecution(context: Context, phaseName: String, sequentialTotalMs: Long, actualParallelMs: Long) {
            try {
                val healthClass = Class.forName("com.apex.agent.core.application.ArchitectureHealthCheck")
        val healthInstance = healthClass.getMethod("getInstance", Context::class.java)
                    .invoke(null, context.applicationContext)
        val method = healthClass.getMethod(
                    "recordPhaseExecution",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                )
                method.invoke(healthInstance, phaseName, sequentialTotalMs, actualParallelMs)
            } catch (_: Throwable) { /* 静默失败 */ }
        }
    }
        private val healthCheck = HealthCheckBridge
}
