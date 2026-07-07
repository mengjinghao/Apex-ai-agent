package com.apex.agent.core.multiagent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.DefaultSubtaskDecompositionStrategy
import com.apex.agent.FileAgent
import com.apex.agent.GeneralAgent
import com.apex.agent.MainTask
import com.apex.agent.SubAgent
import com.apex.agent.SubTask
import com.apex.agent.TaskResult
import com.apex.agent.TaskScheduler
import com.apex.agent.TaskState
import com.apex.data.gepa.SkillDatabase
import com.apex.gepa.GepaConfig
import com.apex.gepa.MatchedSkill
import com.apex.gepa.MatchConfidence
import com.apex.gepa.SkillExtractor
import com.apex.gepa.SkillMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GepaIntegration(application: Application) : AndroidViewModel(application) {

    private val database = SkillDatabase.getDatabase(application)
    private val skillDao = database.skillDao()
    private val config = GepaConfig.getInstance(application)

    private val skillMatcher = SkillMatcher(skillDao)
    private val skillExtractor = SkillExtractor(skillDao)

    private val subAgents: List<SubAgent> = listOf(
        FileAgent(),
        GeneralAgent()
    )

    private val taskScheduler = TaskScheduler(subAgents)

    private val _gepaState = MutableStateFlow<GepaState>(GepaState.Idle)
    val gepaState: StateFlow<GepaState> = _gepaState.asStateFlow()

    private val _currentMatchedSkill = MutableStateFlow<MatchedSkill?>(null)
    val currentMatchedSkill: StateFlow<MatchedSkill?> = _currentMatchedSkill.asStateFlow()

    private val _executionResult = MutableStateFlow<ExecutionResult?>(null)
    val executionResult: StateFlow<ExecutionResult?> = _executionResult.asStateFlow()

    private val complexityQuantifier = TaskComplexityQuantifier()

    fun processTask(taskDescription: String) {
        viewModelScope.launch {
            _gepaState.value = GepaState.Analyzing

            val taskFeature = complexityQuantifier.quantifyTask(taskDescription)
            val mainTask = MainTask(
                taskId = "task_${System.currentTimeMillis()}",
                taskType = taskFeature.category,
                description = taskDescription,
                inputData = mapOf(
                    "difficulty" to taskFeature.difficulty,
                    "riskLevel" to taskFeature.riskLevel
                )
            )

            _gepaState.value = GepaState.Matching

            val matchedSkill = skillMatcher.getBestMatchingSkill(mainTask).first()

            if (matchedSkill != null && skillMatcher.shouldUseTemplate(
                    matchedSkill,
                    config.getMatchConfidenceForMinRate()
                )
            ) {
                _currentMatchedSkill.value = matchedSkill
                _gepaState.value = GepaState.ReadyToExecute(matchedSkill)
            } else {
                _currentMatchedSkill.value = null
                _gepaState.value = GepaState.UsingDefaultStrategy
            }
        }
    }

    fun executeWithMatchedSkill() {
        viewModelScope.launch {
            val matchedSkill = _currentMatchedSkill.value ?: return@launch

            _gepaState.value = GepaState.Executing

            val mainTask = MainTask(
                taskId = "task_${System.currentTimeMillis()}",
                taskType = matchedSkill.skill.taskType,
                description = matchedSkill.skill.taskDescription
            )

            val strategy = object : com.apex.agent.SubtaskDecompositionStrategy {
                override fun decompose(mainTask: MainTask): List<SubTask> {
                    return matchedSkill.suggestedSubtasks
                }
            }

            val result = taskScheduler.executeComplexTask(mainTask, strategy)

            handleExecutionResult(mainTask, matchedSkill.suggestedSubtasks, result)
        }
    }

    fun executeWithDefaultStrategy(taskDescription: String) {
        viewModelScope.launch {
            _gepaState.value = GepaState.Executing

            val taskFeature = complexityQuantifier.quantifyTask(taskDescription)
            val mainTask = MainTask(
                taskId = "task_${System.currentTimeMillis()}",
                taskType = taskFeature.category,
                description = taskDescription,
                inputData = mapOf(
                    "difficulty" to taskFeature.difficulty,
                    "riskLevel" to taskFeature.riskLevel
                )
            )

            val defaultStrategy = DefaultSubtaskDecompositionStrategy()

            val result = taskScheduler.executeComplexTask(mainTask, defaultStrategy)

            val subtasks = defaultStrategy.decompose(mainTask)
            handleExecutionResult(mainTask, subtasks, result)
        }
    }

    private suspend fun handleExecutionResult(
        mainTask: MainTask,
        subtasks: List<SubTask>,
        result: TaskResult
    ) {
        _executionResult.value = ExecutionResult(
            success = result.success,
            totalTime = result.totalExecutionTime,
            subtaskCount = result.subtaskResults.size,
            successCount = result.subtaskResults.count { it.success }
        )

        if (config.autoExtractOnSuccess && result.success) {
            val extractionResult = skillExtractor.extractAndMergeSkill(mainTask, subtasks, result)
            _gepaState.value = if (extractionResult.success) {
                GepaState.ExtractionComplete(extractionResult.skillId)
            } else {
                GepaState.Error("Failed to extract skill: ${extractionResult.message}")
            }
        } else {
            _gepaState.value = GepaState.Completed(result.success)
        }
    }

    fun getTopSkills(limit: Int = 10) = skillMatcher.getTopSkills(limit)

    fun getHighQualitySkills(minRate: Float = 0.8f) = skillMatcher.getHighQualitySkills(minRate)

    fun getAllTaskTypes() = skillMatcher.getAllTaskTypes()

    fun getSkillStats() = viewModelScope.launch {
        val totalSkills = skillDao.getTotalSkillCount().first()
        val avgSuccessRate = skillDao.getAverageSuccessRate().first() ?: 0f
        val taskTypes = skillDao.getAllTaskTypes().first()

        _executionResult.value = ExecutionResult(
            success = true,
            totalTime = 0,
            subtaskCount = totalSkills,
            successCount = taskTypes.size
        )
    }

    fun cleanupLowQualitySkills() {
        viewModelScope.launch {
            skillExtractor.cleanupLowQualitySkills(0.3f)
        }
    }

    fun reset() {
        _gepaState.value = GepaState.Idle
        _currentMatchedSkill.value = null
        _executionResult.value = null
        taskScheduler.resetState()
    }

    fun isEnabled(): Boolean = config.isEnabled

    fun setEnabled(enabled: Boolean) {
        config.isEnabled = enabled
    }
}

sealed class GepaState {
    object Idle : GepaState()
    object Analyzing : GepaState()
    object Matching : GepaState()
    object UsingDefaultStrategy : GepaState()
    data class ReadyToExecute(val matchedSkill: MatchedSkill) : GepaState()
    object Executing : GepaState()
    data class ExtractionComplete(val skillId: Int) : GepaState()
    data class Completed(val success: Boolean) : GepaState()
    data class Error(val message: String) : GepaState()
}

data class ExecutionResult(
    val success: Boolean,
    val totalTime: Long,
    val subtaskCount: Int,
    val successCount: Int
)
