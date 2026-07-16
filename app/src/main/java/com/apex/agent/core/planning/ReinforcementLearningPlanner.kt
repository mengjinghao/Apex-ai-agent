package com.apex.agent.core.planning

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ReinforcementLearningPlanner(private val context: Context) {

    private val TAG = "RLPlanner"

    enum class GoalType {
        SHORT_TERM,
        MEDIUM_TERM,
        LONG_TERM,
        ONE_TIME,
        RECURRING
    }

    enum class PlanStatus {
        DRAFT,
        PLANNED,
        IN_PROGRESS,
        COMPLETED,
        PAUSED,
        CANCELLED,
        FAILED
    }

    enum class Priority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        OPTIONAL
    }

    data class Goal(
        val id: String,
        val name: String,
        val description: String,
        val type: GoalType,
        val targetMetric: String,
        val targetValue: Float,
        val currentValue: Float = 0f,
        val deadline: Long?,
        val priority: Priority,
        val dependencies: List<String>,
        val tags: List<String>
    )

    data class Plan(
        val id: String,
        val name: String,
        val goalId: String,
        val description: String,
        val status: PlanStatus,
        val priority: Priority,
        val steps: List<PlanStep>,
        val estimatedDurationHours: Float,
        val actualDurationHours: Float = 0f,
        val createdAt: Long,
        val startedAt: Long?,
        val completedAt: Long?,
        val successRate: Float = 0f,
        val feedback: String? = null
    )

    data class PlanStep(
        val id: String,
        val name: String,
        val description: String,
        val order: Int,
        val estimatedMinutes: Float,
        val status: StepStatus = StepStatus.PENDING,
        val completedAt: Long? = null,
        val requiredTools: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(),
        val successCriteria: String? = null
    )

    enum class StepStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        SKIPPED,
        FAILED,
        BLOCKED
    }

    data class State(
        val id: String,
        val timestamp: Long,
        val stateData: Map<String, Float>,
        val planProgress: Float,
        val activePlans: Int,
        val goalsProgress: Map<String, Float>
    )

    data class Action(
        val id: String,
        val name: String,
        val description: String,
        val parameters: Map<String, Any>,
        val executionPolicy: ExecutionPolicy,
        val expectedRewards: Map<String, Float>
    )

    enum class ExecutionPolicy {
        GREEDY,
        E_GREEDY,
        SOFTMAX,
        UCB,
        THOMPSON_SAMPLING,
        CUSTOM
    }

    data class Experience(
        val id: String,
        val stateId: String,
        val actionId: String,
        val reward: Float,
        val nextStateId: String,
        val timestamp: Long,
        val success: Boolean,
        val details: String? = null
    )

    private val goalsDir: File
        get() = File(context.filesDir, "rl_goals").also {
            if (!it.exists()) it.mkdirs()
        }

    private val plansDir: File
        get() = File(context.filesDir, "rl_plans").also {
            if (!it.exists()) it.mkdirs()
        }

    private val statesDir: File
        get() = File(context.filesDir, "rl_states").also {
            if (!it.exists()) it.mkdirs()
        }

    private val experiencesDir: File
        get() = File(context.filesDir, "rl_experiences").also {
            if (!it.exists()) it.mkdirs()
        }

    private val activeGoals = mutableMapOf<String, Goal>()
    private val activePlans = mutableMapOf<String, Plan>()
    private val stateHistory = mutableListOf<State>()
    private val experienceBuffer = mutableListOf<Experience>()

    private var epsilon: Float = 0.1f
    private var learningRate: Float = 0.01f
    private var discountFactor: Float = 0.95f

    suspend fun createGoal(
        name: String,
        description: String,
        type: GoalType,
        targetMetric: String,
        targetValue: Float,
        priority: Priority,
        deadlineHours: Int? = null
    ): Goal = withContext(Dispatchers.IO) {
        val goal = Goal(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            type = type,
            targetMetric = targetMetric,
            targetValue = targetValue,
            deadline = deadlineHours?.let { System.currentTimeMillis() + (it * 60 * 60 * 1000L) },
            priority = priority,
            dependencies = emptyList(),
            tags = emptyList()
        )

        saveGoal(goal)
        activeGoals[goal.id] = goal
        goal
    }

    private suspend fun saveGoal(goal: Goal) = withContext(Dispatchers.IO) {
        val goalFile = File(goalsDir, "${goal.id}.json")
        val json = JSONObject().apply {
            put("id", goal.id)
            put("name", goal.name)
            put("description", goal.description)
            put("type", goal.type.name)
            put("targetMetric", goal.targetMetric)
            put("targetValue", goal.targetValue.toDouble())
            put("currentValue", goal.currentValue.toDouble())
            put("deadline", goal.deadline ?: JSONObject.NULL)
            put("priority", goal.priority.name)
            put("dependencies", JSONArray(goal.dependencies))
            put("tags", JSONArray(goal.tags))
        }

        goalFile.writeText(json.toString(2))
    }

    suspend fun getGoals(filter: GoalType? = null): List<Goal> = withContext(Dispatchers.IO) {
        val goals = mutableListOf<Goal>()

        goalsDir.listFiles { _, name -> name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val goal = Goal(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        description = json.getString("description"),
                        type = GoalType.valueOf(json.getString("type")),
                        targetMetric = json.getString("targetMetric"),
                        targetValue = json.getDouble("targetValue").toFloat(),
                        currentValue = json.getDouble("currentValue").toFloat(),
                        deadline = if (json.isNull("deadline")) null else json.getLong("deadline"),
                        priority = Priority.valueOf(json.getString("priority")),
                        dependencies = (0 until json.getJSONArray("dependencies").length())
                            .map { json.getJSONArray("dependencies").getString(it) },
                        tags = (0 until json.getJSONArray("tags").length())
                            .map { json.getJSONArray("tags").getString(it) }
                    )

                    goals.add(goal)
                    activeGoals[goal.id] = goal
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析目标配置失败: ${file.name}", e)
                }
            }

        if (filter != null) {
            goals.filter { it.type == filter }
        } else {
            goals
        }
    }

    suspend fun generatePlan(
        goalId: String,
        name: String,
        priority: Priority = Priority.MEDIUM
    ): Plan = withContext(Dispatchers.IO) {
        val goal = activeGoals[goalId] ?: throw IllegalArgumentException("目标不存�?)

        val steps = generatePlanSteps(goal)

        val plan = Plan(
            id = UUID.randomUUID().toString(),
            name = name,
            goalId = goalId,
            description = "为目标生成的计划: ${goal.name}",
            status = PlanStatus.PLANNED,
            priority = priority,
            steps = steps,
            estimatedDurationHours = steps.sumOf { it.estimatedMinutes } / 60f,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            completedAt = null
        )

        savePlan(plan)
        activePlans[plan.id] = plan
        plan
    }

    private fun generatePlanSteps(goal: Goal): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()

        val stepNames = listOf(
            "问题分析",
            "收集信息",
            "制定策略",
            "执行策略",
            "评估结果",
            "调整优化"
        )

        stepNames.forEachIndexed { index, stepName ->
            steps.add(
                PlanStep(
                    id = UUID.randomUUID().toString(),
                    name = stepName,
                    description = "${stepName}阶段: ${goal.description}",
                    order = index + 1,
                    estimatedMinutes = 30f
                )
            )
        }

        return steps
    }

    private suspend fun savePlan(plan: Plan) = withContext(Dispatchers.IO) {
        val planFile = File(plansDir, "${plan.id}.json")
        val stepsJson = JSONArray()
        plan.steps.forEach { step ->
            stepsJson.put(
                JSONObject().apply {
                    put("id", step.id)
                    put("name", step.name)
                    put("description", step.description)
                    put("order", step.order)
                    put("estimatedMinutes", step.estimatedMinutes.toDouble())
                    put("status", step.status.name)
                    put("completedAt", step.completedAt ?: JSONObject.NULL)
                    put("requiredTools", JSONArray(step.requiredTools))
                    put("dependencies", JSONArray(step.dependencies))
                    put("successCriteria", step.successCriteria ?: JSONObject.NULL)
                }
            )
        }

        val json = JSONObject().apply {
            put("id", plan.id)
            put("name", plan.name)
            put("goalId", plan.goalId)
            put("description", plan.description)
            put("status", plan.status.name)
            put("priority", plan.priority.name)
            put("steps", stepsJson)
            put("estimatedDurationHours", plan.estimatedDurationHours.toDouble())
            put("actualDurationHours", plan.actualDurationHours.toDouble())
            put("createdAt", plan.createdAt)
            put("startedAt", plan.startedAt ?: JSONObject.NULL)
            put("completedAt", plan.completedAt ?: JSONObject.NULL)
            put("successRate", plan.successRate.toDouble())
            put("feedback", plan.feedback ?: JSONObject.NULL)
        }

        planFile.writeText(json.toString(2))
    }

    suspend fun getPlans(status: PlanStatus? = null): List<Plan> = withContext(Dispatchers.IO) {
        val plans = mutableListOf<Plan>()

        plansDir.listFiles { _, name -> name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val stepsJson = json.getJSONArray("steps")
                    val steps = mutableListOf<PlanStep>()

                    for (i in 0 until stepsJson.length()) {
                        val stepJson = stepsJson.getJSONObject(i)
                        steps.add(
                            PlanStep(
                                id = stepJson.getString("id"),
                                name = stepJson.getString("name"),
                                description = stepJson.getString("description"),
                                order = stepJson.getInt("order"),
                                estimatedMinutes = stepJson.getDouble("estimatedMinutes").toFloat(),
                                status = StepStatus.valueOf(stepJson.getString("status")),
                                completedAt = if (stepJson.isNull("completedAt")) null else stepJson.getLong("completedAt"),
                                requiredTools = (0 until stepJson.getJSONArray("requiredTools").length())
                                    .map { stepJson.getJSONArray("requiredTools").getString(it) },
                                dependencies = (0 until stepJson.getJSONArray("dependencies").length())
                                    .map { stepJson.getJSONArray("dependencies").getString(it) },
                                successCriteria = if (stepJson.isNull("successCriteria")) null else stepJson.getString("successCriteria")
                            )
                        )
                    }

                    val plan = Plan(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        goalId = json.getString("goalId"),
                        description = json.getString("description"),
                        status = PlanStatus.valueOf(json.getString("status")),
                        priority = Priority.valueOf(json.getString("priority")),
                        steps = steps,
                        estimatedDurationHours = json.getDouble("estimatedDurationHours").toFloat(),
                        actualDurationHours = json.getDouble("actualDurationHours").toFloat(),
                        createdAt = json.getLong("createdAt"),
                        startedAt = if (json.isNull("startedAt")) null else json.getLong("startedAt"),
                        completedAt = if (json.isNull("completedAt")) null else json.getLong("completedAt"),
                        successRate = json.getDouble("successRate").toFloat(),
                        feedback = if (json.isNull("feedback")) null else json.getString("feedback")
                    )

                    plans.add(plan)
                    activePlans[plan.id] = plan
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析计划配置失败: ${file.name}", e)
                }
            }

        if (status != null) {
            plans.filter { it.status == status }
        } else {
            plans
        }
    }

    suspend fun startPlan(planId: String): Boolean = withContext(Dispatchers.IO) {
        val plan = activePlans[planId] ?: return@withContext false

        val updatedPlan = plan.copy(
            status = PlanStatus.IN_PROGRESS,
            startedAt = System.currentTimeMillis()
        )

        savePlan(updatedPlan)
        activePlans[planId] = updatedPlan
        true
    }

    suspend fun completePlan(planId: String, success: Boolean, feedback: String? = null): Float = withContext(Dispatchers.IO) {
        val plan = activePlans[planId] ?: return@withContext -1f

        val completedSteps = plan.steps.count { it.status == StepStatus.COMPLETED }
        val totalSteps = plan.steps.size
        val successRate = if (totalSteps > 0) completedSteps.toFloat() / totalSteps.toFloat() else 0f

        val updatedPlan = plan.copy(
            status = if (success) PlanStatus.COMPLETED else PlanStatus.FAILED,
            completedAt = System.currentTimeMillis(),
            actualDurationHours = (System.currentTimeMillis() - (plan.startedAt ?: plan.createdAt)) / (60 * 60 * 1000f),
            successRate = successRate,
            feedback = feedback
        )

        savePlan(updatedPlan)
        activePlans[planId] = updatedPlan

        val reward = calculateReward(updatedPlan)
        recordExperience(plan.id, reward, success)

        successRate
    }

    private fun calculateReward(plan: Plan): Float {
        var reward = 0f

        if (plan.status == PlanStatus.COMPLETED) {
            reward += plan.successRate * 10f
        } else {
            reward -= 5f
        }

        val targetDuration = plan.estimatedDurationHours
        val actualDuration = plan.actualDurationHours

        if (actualDuration < targetDuration) {
            reward += (targetDuration - actualDuration)
        } else if (actualDuration > targetDuration * 1.5f) {
            reward -= (actualDuration - targetDuration) / 2f
        }

        return reward
    }

    private suspend fun recordExperience(planId: String, reward: Float, success: Boolean) = withContext(Dispatchers.IO) {
        val currentState = captureCurrentState()
        saveState(currentState)

        val nextState = captureCurrentState()
        saveState(nextState)

        val experience = Experience(
            id = UUID.randomUUID().toString(),
            stateId = currentState.id,
            actionId = planId,
            reward = reward,
            nextStateId = nextState.id,
            timestamp = System.currentTimeMillis(),
            success = success
        )

        experienceBuffer.add(experience)
        saveExperience(experience)

        if (experienceBuffer.size > 1000) {
            experienceBuffer.removeAt(0)
        }
    }

    private fun captureCurrentState(): State {
        val progressData = mutableMapOf<String, Float>()

        activeGoals.values.forEach { goal ->
            val progress = if (goal.targetValue > 0) goal.currentValue / goal.targetValue else 0f
            progressData[goal.id] = progress.coerceAtMost(1f)
        }

        val planProgress = if (activePlans.isNotEmpty()) {
            activePlans.values.filter { it.status == PlanStatus.IN_PROGRESS }
                .map { plan ->
                    val completedSteps = plan.steps.count { it.status == StepStatus.COMPLETED }
                    if (plan.steps.isNotEmpty()) completedSteps.toFloat() / plan.steps.size.toFloat() else 0f
                }.average().toFloat()
        } else 0f

        return State(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            stateData = progressData,
            planProgress = planProgress,
            activePlans = activePlans.size,
            goalsProgress = activeGoals.values.associate { goal ->
                val progress = if (goal.targetValue > 0) goal.currentValue / goal.targetValue else 0f
                goal.id to progress.coerceAtMost(1f)
            }
        )
    }

    private suspend fun saveState(state: State) = withContext(Dispatchers.IO) {
        val stateFile = File(statesDir, "${state.id}.json")
        val stateDataJson = JSONObject()
        state.stateData.forEach { (key, value) ->
            stateDataJson.put(key, value.toDouble())
        }

        val goalsProgressJson = JSONObject()
        state.goalsProgress.forEach { (key, value) ->
            goalsProgressJson.put(key, value.toDouble())
        }

        val json = JSONObject().apply {
            put("id", state.id)
            put("timestamp", state.timestamp)
            put("stateData", stateDataJson)
            put("planProgress", state.planProgress.toDouble())
            put("activePlans", state.activePlans)
            put("goalsProgress", goalsProgressJson)
        }

        stateFile.writeText(json.toString(2))
        stateHistory.add(state)

        if (stateHistory.size > 100) {
            stateHistory.removeAt(0)
        }
    }

    private suspend fun saveExperience(experience: Experience) = withContext(Dispatchers.IO) {
        val expFile = File(experiencesDir, "${experience.id}.json")
        val json = JSONObject().apply {
            put("id", experience.id)
            put("stateId", experience.stateId)
            put("actionId", experience.actionId)
            put("reward", experience.reward.toDouble())
            put("nextStateId", experience.nextStateId)
            put("timestamp", experience.timestamp)
            put("success", experience.success)
            put("details", experience.details ?: JSONObject.NULL)
        }

        expFile.writeText(json.toString(2))
    }

    suspend fun updateGoalProgress(goalId: String, newValue: Float): Boolean = withContext(Dispatchers.IO) {
        val goal = activeGoals[goalId] ?: return@withContext false

        val updatedGoal = goal.copy(
            currentValue = newValue
        )

        saveGoal(updatedGoal)
        activeGoals[goalId] = updatedGoal
        true
    }

    suspend fun learnFromExperience() = withContext(Dispatchers.IO) {
        if (experienceBuffer.size < 10) return@withContext

        val recentExperiences = experienceBuffer.takeLast(50)
        val avgReward = recentExperiences.map { it.reward }.average()

        if (avgReward < 0f) {
            epsilon = (epsilon + 0.05f).coerceAtMost(0.3f)
        } else {
            epsilon = (epsilon - 0.01f).coerceAtLeast(0.05f)
        }

        AppLogger.d(TAG, "学习完成: 平均奖励 = ${String.format("%.2f", avgReward)}, epsilon = ${String.format("%.2f", epsilon)}")
    }

    suspend fun generateReport(): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("=== 强化学习规划系统报告 ===")
            appendLine()
            appendLine("【目标统计�?)
            appendLine("活跃目标: ${activeGoals.size}")
            appendLine("长期目标: ${activeGoals.values.count { it.type == GoalType.LONG_TERM }}")
            appendLine("中期目标: ${activeGoals.values.count { it.type == GoalType.MEDIUM_TERM }}")
            appendLine("短期目标: ${activeGoals.values.count { it.type == GoalType.SHORT_TERM }}")
            appendLine()
            appendLine("【计划统计�?)
            appendLine("总计划数: ${activePlans.size}")
            appendLine("进行�? ${activePlans.values.count { it.status == PlanStatus.IN_PROGRESS }}")
            appendLine("已完�? ${activePlans.values.count { it.status == PlanStatus.COMPLETED }}")
            appendLine()
            appendLine("【学习状态�?)
            appendLine("经验数量: ${experienceBuffer.size}")
            appendLine("Epsilon: ${String.format("%.2f", epsilon)}")
            appendLine("学习�? ${String.format("%.3f", learningRate)}")
        }
    }

    suspend fun cleanupOldData(daysToKeep: Int = 60) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        statesDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }

        experiencesDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }

        stateHistory.removeIf { it.timestamp < cutoffTime }
    }
}