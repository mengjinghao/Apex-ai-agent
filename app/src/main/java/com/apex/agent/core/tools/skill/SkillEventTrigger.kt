package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SkillEventTrigger private constructor() {

    companion object {
        private const val TAG = "SkillEventTrigger"
        private const val MAX_TRIGGER_HISTORY = 1000
        private const val MAX_CONCURRENT_TRIGGERS = 10
        private const val DEFAULT_COOLDOWN_MS = 1000L

        @Volatile private var INSTANCE: SkillEventTrigger? = null

        fun getInstance(): SkillEventTrigger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillEventTrigger().also { INSTANCE = it }
            }
        }
    }

    @Serializable
    data class EventTrigger(
        val id: String = generateTriggerId(),
        val name: String,
        val description: String = "",
        val eventType: String,
        val targetWorkflowId: String? = null,
        val targetSkillName: String? = null,
        val condition: TriggerCondition? = null,
        val actions: List<TriggerAction> = emptyList(),
        val enabled: Boolean = true,
        val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        val maxExecutions: Long? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val lastTriggeredTime: Long = 0,
        val executionCount: Long = 0,
        val matchCount: Long = 0
    )

    @Serializable
    data class TriggerCondition(
        val type: ConditionType,
        val field: String? = null,
        val operator: String? = null,
        val value: String? = null,
        val pattern: String? = null,
        val regex: String? = null
    )

    enum class ConditionType {
        ALWAYS,
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        REGEX,
        GREATER_THAN,
        LESS_THAN,
        STARTS_WITH,
        ENDS_WITH
    }

    @Serializable
    data class TriggerAction(
        val type: ActionType,
        val config: Map<String, String> = emptyMap()
    )

    enum class ActionType {
        EXECUTE_WORKFLOW,
        EXECUTE_SKILL,
        NOTIFY,
        LOG,
        UPDATE_STATE
    }

    data class TriggerExecution(
        val triggerId: String,
        val triggerName: String,
        val workflowId: String?,
        val skillName: String?,
        val eventData: Map<String, Any>,
        val matchedCondition: TriggerCondition?,
        val executionId: String,
        val timestamp: Long,
        val success: Boolean,
        val executionTimeMs: Long,
        val error: String? = null
    )

    sealed class TriggerEvent {
        data class TriggerRegistered(val trigger: EventTrigger) : TriggerEvent()
        data class TriggerUnregistered(val triggerId: String) : TriggerEvent()
        data class TriggerEnabled(val triggerId: String) : TriggerEvent()
        data class TriggerDisabled(val triggerId: String) : TriggerEvent()
        data class TriggerMatched(val trigger: EventTrigger, val eventData: Map<String, Any>) : TriggerEvent()
        data class TriggerExecuted(val execution: TriggerExecution) : TriggerEvent()
        data class TriggerCooldown(val triggerId: String, val remainingMs: Long) : TriggerEvent()
    }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mutex = Mutex()
        private val triggers = ConcurrentHashMap<String, EventTrigger>()
        private val triggerCooldowns = ConcurrentHashMap<String, Long>()
        private val triggerHistory = ConcurrentHashMap<String, MutableList<TriggerExecution>>()
        private val activeExecutions = ConcurrentHashMap<String, Job>()
        private val _triggersFlow = MutableStateFlow<List<EventTrigger>>(emptyList())
        val triggersFlow: StateFlow<List<EventTrigger>> = _triggersFlow.asStateFlow()
        private val _triggerEvents = MutableSharedFlow<TriggerEvent>()
        val triggerEvents: SharedFlow<TriggerEvent> = _triggerEvents.asSharedFlow()
        private val _activeExecutionsCount = MutableStateFlow(0)
        val activeExecutionsCount: StateFlow<Int> = _activeExecutionsCount.asStateFlow()
        private val eventBus = SkillEventBus.getInstance()
        private val workflowEngine = WorkflowEngine.getInstance()
        private val json = Json { encodeDefaults = true }
        private val statsTotalTriggers = AtomicLong(0)
        private val statsTotalMatches = AtomicLong(0)
        private val statsTotalExecutions = AtomicLong(0)
        private val statsTotalSuccess = AtomicLong(0)
        private val statsTotalFailure = AtomicLong(0)

    init {
        setupEventBusSubscription()
    }
        private fun setupEventBusSubscription() {
        scope.launch {
            eventBus.subscribe(
                subscriber = this@SkillEventTrigger,
                eventType = "*",
                priority = 0
            ) { event ->
                handleEvent(event)
                true
            }
        }
    }
        private suspend fun handleEvent(event: SkillEventBus.SkillEvent): Boolean {
        val eventType = event.eventType
        val matchingTriggers = triggers.values.filter { it.enabled && it.eventType == eventType }
        if (matchingTriggers.isEmpty()) return true

        val eventData = extractEventData(event)
        for (trigger in matchingTriggers) {
            if (evaluateCondition(trigger.condition, eventData)) {
                statsTotalMatches.incrementAndGet()

                scope.launch {
                    _triggerEvents.emit(TriggerEvent.TriggerMatched(trigger, eventData))
                }

                executeTrigger(trigger, eventData)
            }
        }
        return true
    }
        private fun extractEventData(event: SkillEventBus.SkillEvent): Map<String, Any> {
        return when (event) {
            is SkillEventBus.SkillEvent.SkillLoaded -> mapOf(
                "skillName" to event.skillName,
                "loadDurationMs" to event.loadDurationMs,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.SkillInvoked -> mapOf(
                "skillName" to event.skillName,
                "toolName" to (event.toolName ?: ""),
                "executionTimeMs" to event.executionTimeMs,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.SkillCompleted -> mapOf(
                "skillName" to event.skillName,
                "success" to event.success,
                "executionTimeMs" to event.executionTimeMs,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.WorkflowTriggered -> mapOf(
                "workflowId" to event.workflowId,
                "workflowName" to event.workflowName,
                "triggerType" to event.triggerType,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.WorkflowCompleted -> mapOf(
                "workflowId" to event.workflowId,
                "success" to event.success,
                "totalExecutionTimeMs" to event.totalExecutionTimeMs,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.TaskScheduled -> mapOf(
                "taskId" to event.taskId,
                "taskName" to event.taskName,
                "scheduleType" to event.scheduleType,
                "nextExecutionTime" to event.nextExecutionTime,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.TaskExecuted -> mapOf(
                "taskId" to event.taskId,
                "taskName" to event.taskName,
                "success" to event.success,
                "executionTimeMs" to event.executionTimeMs,
                "source" to event.source
            )
            is SkillEventBus.SkillEvent.CustomEvent -> event.data + mapOf(
                "eventType" to event.eventType,
                "source" to event.source
            )
            else -> mapOf(
                "eventId" to event.eventId,
                "timestamp" to event.timestamp,
                "source" to event.source
            )
        }
    }
        private fun evaluateCondition(condition: TriggerCondition?, eventData: Map<String, Any>): Boolean {
        if (condition == null) return true

        return when (condition.type) {
            ConditionType.ALWAYS -> true

            ConditionType.EQUALS -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString() ?: ""
                fieldValue == (condition.value ?: "")
            }

            ConditionType.NOT_EQUALS -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString() ?: ""
                fieldValue != (condition.value ?: "")
            }

            ConditionType.CONTAINS -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString() ?: ""
                fieldValue.contains(condition.value ?: "")
            }

            ConditionType.REGEX -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString() ?: ""
        val pattern = condition.regex ?: condition.pattern ?: ""
                try {
                    Regex(pattern).matches(fieldValue)
                } catch (e: Exception) {
                    false
                }
            }

            ConditionType.GREATER_THAN -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString()?.toDoubleOrNull() ?: 0.0
                val compareValue = condition.value?.toDoubleOrNull() ?: 0.0
                fieldValue > compareValue
            }

            ConditionType.LESS_THAN -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString()?.toDoubleOrNull() ?: 0.0
                val compareValue = condition.value?.toDoubleOrNull() ?: 0.0
                fieldValue < compareValue
            }

            ConditionType.STARTS_WITH -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString() ?: ""
                fieldValue.startsWith(condition.value ?: "")
            }

            ConditionType.ENDS_WITH -> {
                val fieldValue = eventData[condition.field ?: ""]?.toString() ?: ""
                fieldValue.endsWith(condition.value ?: "")
            }
        }
    }
        fun registerTrigger(
        name: String,
        eventType: String,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null,
        condition: TriggerCondition? = null,
        actions: List<TriggerAction> = emptyList(),
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): EventTrigger? {
        if (triggers.size >= MAX_CONCURRENT_TRIGGERS) {
            AppLogger.w(TAG, "Max triggers limit reached")
        return null
        }
        val trigger = EventTrigger(
            name = name,
            eventType = eventType,
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            condition = condition,
            actions = actions,
            cooldownMs = cooldownMs
        )

        triggers[trigger.id] = trigger
        statsTotalTriggers.incrementAndGet()

        updateTriggersFlow()

        scope.launch {
            _triggerEvents.emit(TriggerEvent.TriggerRegistered(trigger))
        }

        AppLogger.i(TAG, "Trigger registered: ${trigger.name} [${trigger.id}] for event: ${eventType}")
        return trigger
    }
        fun unregisterTrigger(triggerId: String): Boolean {
        val trigger = triggers.remove(triggerId) ?: return false

        triggerCooldowns.remove(triggerId)

        updateTriggersFlow()

        scope.launch {
            _triggerEvents.emit(TriggerEvent.TriggerUnregistered(triggerId))
        }

        AppLogger.i(TAG, "Trigger unregistered: ${trigger.name} [${triggerId}]")
        return true
    }
        fun updateTrigger(triggerId: String, updates: (EventTrigger) -> EventTrigger): EventTrigger? {
        val trigger = triggers[triggerId] ?: return null

        val updatedTrigger = updates(trigger)
        triggers[triggerId] = updatedTrigger

        updateTriggersFlow()

        AppLogger.d(TAG, "Trigger updated: ${updatedTrigger.name} [${updatedTrigger.id}]")
        return updatedTrigger
    }
        fun enableTrigger(triggerId: String): Boolean {
        val trigger = triggers[triggerId] ?: return false
        if (trigger.enabled) return true

        val updatedTrigger = trigger.copy(enabled = true)
        triggers[triggerId] = updatedTrigger

        updateTriggersFlow()

        scope.launch {
            _triggerEvents.emit(TriggerEvent.TriggerEnabled(triggerId))
        }

        AppLogger.i(TAG, "Trigger enabled: ${trigger.name} [${triggerId}]")
        return true
    }
        fun disableTrigger(triggerId: String): Boolean {
        val trigger = triggers[triggerId] ?: return false
        if (!trigger.enabled) return true

        val updatedTrigger = trigger.copy(enabled = false)
        triggers[triggerId] = updatedTrigger

        updateTriggersFlow()

        scope.launch {
            _triggerEvents.emit(TriggerEvent.TriggerDisabled(triggerId))
        }

        AppLogger.i(TAG, "Trigger disabled: ${trigger.name} [${triggerId}]")
        return true
    }
        fun getTrigger(triggerId: String): EventTrigger? = triggers[triggerId]

    fun getAllTriggers(): List<EventTrigger> = triggers.values.toList()
        fun getTriggersForEvent(eventType: String): List<EventTrigger> =
        triggers.values.filter { it.eventType == eventType }
        fun getEnabledTriggers(): List<EventTrigger> = triggers.values.filter { it.enabled }
        fun getTriggerHistory(triggerId: String): List<TriggerExecution> =
        triggerHistory[triggerId]?.toList() ?: emptyList()
        private fun executeTrigger(trigger: EventTrigger, eventData: Map<String, Any>) {
        val now = System.currentTimeMillis()
        val lastTriggered = triggerCooldowns[trigger.id] ?: 0
        if (now - lastTriggered < trigger.cooldownMs) {
            val remainingMs = trigger.cooldownMs - (now - lastTriggered)
            scope.launch {
                _triggerEvents.emit(TriggerEvent.TriggerCooldown(trigger.id, remainingMs))
            }
            AppLogger.d(TAG, "Trigger ${trigger.id} is on cooldown, remaining: ${remainingMs}ms")
        return
        }
        if (activeExecutions.size >= MAX_CONCURRENT_TRIGGERS) {
            AppLogger.w(TAG, "Max concurrent trigger executions reached")
        return
        }
        val executionId = "trig_exec_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val startTime = System.currentTimeMillis()
        val job = scope.launch {
            var success = false
            var error: String? = null

            try {
                statsTotalExecutions.incrementAndGet()
        when {
                    trigger.targetWorkflowId != null -> {
                        val result = workflowEngine.executeWorkflow(
                            trigger.targetWorkflowId,
                            "event_trigger",
                            eventData
                        )
                        success = result?.success ?: false
                        if (!success) {
                            error = result?.errorMessage ?: "Workflow execution failed"
                        }
                    }

                    trigger.targetSkillName != null -> {
                        success = true
                    }

                    else -> {
                        success = true
                    }
                }
        for (action in trigger.actions) {
                    executeAction(action, eventData)
                }

            } catch (e: Exception) {
                error = e.message
                success = false
                AppLogger.e(TAG, "Trigger execution failed: ${trigger.id}", e)
            }
        val endTime = System.currentTimeMillis()
        val execution = TriggerExecution(
                triggerId = trigger.id,
                triggerName = trigger.name,
                workflowId = trigger.targetWorkflowId,
                skillName = trigger.targetSkillName,
                eventData = eventData,
                matchedCondition = trigger.condition,
                executionId = executionId,
                timestamp = startTime,
                success = success,
                executionTimeMs = endTime - startTime,
                error = error
            )
        val history = triggerHistory.getOrPut(trigger.id) { mutableListOf() }
            history.add(execution)
        if (history.size > MAX_TRIGGER_HISTORY) {
                history.removeAt(0)
            }
        val updatedTrigger = trigger.copy(
                lastTriggeredTime = now,
                executionCount = trigger.executionCount + 1,
                matchCount = trigger.matchCount + 1
            )
            triggers[trigger.id] = updatedTrigger

            triggerCooldowns[trigger.id] = now

            if (success) {
                statsTotalSuccess.incrementAndGet()
            } else {
                statsTotalFailure.incrementAndGet()
            }

            _triggerEvents.emit(TriggerEvent.TriggerExecuted(execution))

            AppLogger.d(TAG, "Trigger executed: ${trigger.name} [${trigger.id}], success: ${success}")
        }

        activeExecutions[executionId] = job
        _activeExecutionsCount.value = activeExecutions.size

        job.invokeOnCompletion {
            activeExecutions.remove(executionId)
            _activeExecutionsCount.value = activeExecutions.size
        }
    }
        private suspend fun executeAction(action: TriggerAction, eventData: Map<String, Any>) {
        when (action.type) {
            ActionType.LOG -> {
                val message = action.config["message"] ?: "Trigger action executed"
                AppLogger.d(TAG, "[Trigger Log] ${message}, eventData: ${eventData}")
            }

            ActionType.NOTIFY -> {
                val title = action.config["title"] ?: "Trigger Notification"
        val content = action.config["content"] ?: "Trigger was executed"
                AppLogger.d(TAG, "[Trigger Notify] ${title}: ${content}")
            }

            ActionType.UPDATE_STATE -> {
                val key = action.config["key"]
                val value = action.config["value"]
                if (key != null && value != null) {
                    AppLogger.d(TAG, "[Trigger State] Updated ${key} = ${value}")
                }
            }

            ActionType.EXECUTE_WORKFLOW -> {
                val workflowId = action.config["workflowId"]
                if (workflowId != null) {
                    workflowEngine.executeWorkflow(workflowId, "trigger_action", eventData)
                }
            }

            ActionType.EXECUTE_SKILL -> {
                val skillName = action.config["skillName"]
                if (skillName != null) {
                    AppLogger.d(TAG, "[Trigger Skill] Would execute skill: ${skillName}")
                }
            }
        }
    }
        fun emitCustomEvent(eventType: String, data: Map<String, Any> = emptyMap()) {
        scope.launch {
            eventBus.emit(SkillEventBus.SkillEvent.CustomEvent(
                source = TAG,
                eventType = eventType,
                data = data
            ))
        }
    }
        fun createAlwaysTrigger(
        name: String,
        eventType: String,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null
    ): EventTrigger? {
        return registerTrigger(
            name = name,
            eventType = eventType,
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            condition = TriggerCondition(type = ConditionType.ALWAYS)
        )
    }
        fun createRegexTrigger(
        name: String,
        eventType: String,
        field: String,
        pattern: String,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null
    ): EventTrigger? {
        return registerTrigger(
            name = name,
            eventType = eventType,
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            condition = TriggerCondition(
                type = ConditionType.REGEX,
                field = field,
                regex = pattern
            )
        )
    }
        fun createContainsTrigger(
        name: String,
        eventType: String,
        field: String,
        value: String,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null
    ): EventTrigger? {
        return registerTrigger(
            name = name,
            eventType = eventType,
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            condition = TriggerCondition(
                type = ConditionType.CONTAINS,
                field = field,
                value = value
            )
        )
    }
        private fun updateTriggersFlow() {
        _triggersFlow.value = triggers.values.toList()
    }
        fun cancelAllTriggers() {
        activeExecutions.values.forEach { it.cancel() }
        activeExecutions.clear()
        _activeExecutionsCount.value = 0
        AppLogger.i(TAG, "All trigger executions cancelled")
    }
        fun getStats(): TriggerStats {
        return TriggerStats(
            totalTriggers = triggers.size.toLong(),
            enabledTriggers = triggers.values.count { it.enabled },
            totalRegistrations = statsTotalTriggers.get(),
            totalMatches = statsTotalMatches.get(),
            totalExecutions = statsTotalExecutions.get(),
            totalSuccess = statsTotalSuccess.get(),
            totalFailure = statsTotalFailure.get(),
            activeExecutions = activeExecutions.size
        )
    }

    data class TriggerStats(
        val totalTriggers: Long,
        val enabledTriggers: Int,
        val totalRegistrations: Long,
        val totalMatches: Long,
        val totalExecutions: Long,
        val totalSuccess: Long,
        val totalFailure: Long,
        val activeExecutions: Int
    )
        private fun generateTriggerId(): String = "trig_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
}
