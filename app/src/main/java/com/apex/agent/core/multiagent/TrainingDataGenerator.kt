package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class TrainingDataGenerator {

    data class TrainingSample(
        val taskId: String,
        val taskFeatures: Map<String, Double>,
        val agentFeatures: Map<String, Double>,
        val label: Double, // 0-1之间的匹配得�?       val metadata: Map<String, Any>
    )

    data class DataPipelineConfig(
        val sampleCount: Int = 10000,
        val validationSplit: Double = 0.2,
        val testSplit: Double = 0.1,
        val featureNormalization: Boolean = true,
        val balanceClasses: Boolean = true
    )

    data class Dataset(
        val trainSamples: List<TrainingSample>,
        val validationSamples: List<TrainingSample>,
        val testSamples: List<TrainingSample>
    )

    private val profileManager = AgentCapabilityProfile()
    private val quantifier = TaskComplexityQuantifier()
    private val samples = ConcurrentHashMap<String, TrainingSample>()
    private val sampleIdCounter = AtomicInteger(0)

    fun generateTrainingData(config: DataPipelineConfig): Dataset {
        val totalSamples = config.sampleCount
        val validationCount = (totalSamples * config.validationSplit).toInt()
        val testCount = (totalSamples * config.testSplit).toInt()
        val trainCount = totalSamples - validationCount - testCount

        val allSamples = generateSamples(totalSamples)
        val processedSamples = if (config.featureNormalization) {
            normalizeFeatures(allSamples)
        } else {
            allSamples
        }

        val balancedSamples = if (config.balanceClasses) {
            balanceClasses(processedSamples)
        } else {
            processedSamples
        }

        val shuffledSamples = balancedSamples.shuffled()
        val trainSamples = shuffledSamples.take(trainCount)
        val validationSamples = shuffledSamples.drop(trainCount).take(validationCount)
        val testSamples = shuffledSamples.drop(trainCount + validationCount).take(testCount)

        return Dataset(
            trainSamples = trainSamples,
            validationSamples = validationSamples,
            testSamples = testSamples
        )
    }

    private fun generateSamples(count: Int): List<TrainingSample> {
        val samples = mutableListOf<TrainingSample>()
        val tasks = generateTasks(count)
        val agents = profileManager.getAllProfiles()

        tasks.forEach { task ->
            agents.forEach { agent ->
                val sample = createSample(task, agent)
                samples.add(sample)
                samples[sample.taskId] = sample
            }
        }

        return samples
    }

    private fun generateTasks(count: Int): List<TaskComplexityQuantifier.TaskFeature> {
        val tasks = mutableListOf<TaskComplexityQuantifier.TaskFeature>()
        val taskDescriptions = listOf(
            "开发一个Android应用",
            "撰写产品文档",
            "进行用户测试",
            "数据分析与可视化",
            "设计用户界面",
            "编写API接口",
            "优化数据库查�?
            "实现安全认证",
            "部署应用到服务器",
            "编写测试用例"
        )

        repeat(count) {
            val description = taskDescriptions.random()
            val feature = quantifier.quantifyTask(description)
            tasks.add(feature)
        }

        return tasks
    }

    private fun createSample(task: TaskComplexityQuantifier.TaskFeature, agent: AgentCapabilityProfile.CapabilityProfile): TrainingSample {
        val taskId = "task_${sampleIdCounter.incrementAndGet()}"
        val taskFeatures = extractTaskFeatures(task)
        val agentFeatures = extractAgentFeatures(agent, task.category)
        val label = calculateMatchScore(taskFeatures, agentFeatures)

        val metadata = mapOf(
            "taskDescription" to task.category,
            "agentName" to agent.agentName,
            "agentRole" to agent.role,
            "difficulty" to task.difficulty,
            "riskLevel" to task.riskLevel
        )

        return TrainingSample(
            taskId = taskId,
            taskFeatures = taskFeatures,
            agentFeatures = agentFeatures,
            label = label,
            metadata = metadata
        )
    }

    private fun extractTaskFeatures(task: TaskComplexityQuantifier.TaskFeature): Map<String, Double> {
        val features = mutableMapOf<String, Double>()
        features["difficulty"] = task.difficulty.toDouble() / 10.0
        features["riskLevel"] = task.riskLevel.toDouble() / 5.0
        features["estimatedTime"] = task.estimatedTime.toDouble() / 3600.0 // 转换为小�?       features["memoryRequirement"] = task.resourceRequirement.memory.toDouble() / 1024.0 // 转换为GB
        features["cpuRequirement"] = task.resourceRequirement.cpu.toDouble() / 100.0
        features["networkRequirement"] = task.resourceRequirement.network.toDouble() / 100.0
        features["storageRequirement"] = task.resourceRequirement.storage.toDouble() / 1024.0 // 转换为GB
        
        // 任务类别独热编码
        val categories = listOf("coding", "writing", "research", "design", "data", "communication", "planning", "testing", "documentation", "other")
        categories.forEach { category ->
            features["category_${category}"] = if (task.category == category) 1.0 else 0.0
        }

        return features
    }

    private fun extractAgentFeatures(agent: AgentCapabilityProfile.CapabilityProfile, taskCategory: String): Map<String, Double> {
        val features = mutableMapOf<String, Double>()
        features["successRate"] = agent.performanceMetrics.successRate
        features["averageResponseTime"] = agent.performanceMetrics.averageResponseTime.toDouble() / 60000.0 // 转换为分�?       features["averageQualityScore"] = agent.performanceMetrics.averageQualityScore
        features["totalTasks"] = agent.performanceMetrics.totalTasks.toDouble() / 100.0
        
        // Agent能力评分
        val capabilityScore = agent.capabilityScores.getOrDefault(taskCategory, 1.0)
        features["capabilityScore"] = capabilityScore / 2.0 // 归一化到0-1
        
        // 技能匹配度
        val requiredSkills = getSkillsForCategory(taskCategory)
        val skillMatch = requiredSkills.count { agent.skillTags.contains(it) }.toDouble() / requiredSkills.size
        features["skillMatch"] = skillMatch

        return features
    }

    private fun calculateMatchScore(taskFeatures: Map<String, Double>, agentFeatures: Map<String, Double>): Double {
        var score = 0.0
        
        // 能力匹配�?       score += agentFeatures.getOrDefault("capabilityScore", 0.0) * 0.4
        
        // 技能匹配度
        score += agentFeatures.getOrDefault("skillMatch", 0.0) * 0.3
        
        // 成功�?       score += agentFeatures.getOrDefault("successRate", 0.0) * 0.2
        
        // 响应时间（越低越好）
        val responseTimeScore = 1.0 - minOf(agentFeatures.getOrDefault("averageResponseTime", 0.0), 1.0)
        score += responseTimeScore * 0.1
        
        // 添加一些噪�?       score += (Random.nextDouble() - 0.5) * 0.1
        
        return score.coerceIn(0.0, 1.0)
    }

    private fun normalizeFeatures(samples: List<TrainingSample>): List<TrainingSample> {
        // 计算特征均值和标准�?       val taskFeatureStats = calculateFeatureStats(samples.map { it.taskFeatures })
        val agentFeatureStats = calculateFeatureStats(samples.map { it.agentFeatures })

        return samples.map { sample ->
            val normalizedTaskFeatures = normalizeFeatures(sample.taskFeatures, taskFeatureStats)
            val normalizedAgentFeatures = normalizeFeatures(sample.agentFeatures, agentFeatureStats)
            sample.copy(
                taskFeatures = normalizedTaskFeatures,
                agentFeatures = normalizedAgentFeatures
            )
        }
    }

    private fun calculateFeatureStats(featureMaps: List<Map<String, Double>>): Map<String, Pair<Double, Double>> {
        val stats = mutableMapOf<String, MutableList<Double>>()
        
        featureMaps.forEach { features ->
            features.forEach { (key, value) ->
                stats.computeIfAbsent(key) { mutableListOf() }.add(value)
            }
        }
        
        return stats.mapValues { (_, values) ->
            val mean = values.average()
            val variance = values.map { (it - mean) * (it - mean) }.average()
            val stdDev = Math.sqrt(variance)
            Pair(mean, stdDev)
        }
    }

    private fun normalizeFeatures(features: Map<String, Double>, stats: Map<String, Pair<Double, Double>>): Map<String, Double> {
        return features.mapValues { (key, value) ->
            val (mean, stdDev) = stats.getOrDefault(key, Pair(0.0, 1.0))
            if (stdDev == 0.0) 0.0 else (value - mean) / stdDev
        }
    }

    private fun balanceClasses(samples: List<TrainingSample>): List<TrainingSample> {
        // 按标签分�?       val lowScoreSamples = samples.filter { it.label < 0.3 }
        val mediumScoreSamples = samples.filter { it.label >= 0.3 && it.label < 0.7 }
        val highScoreSamples = samples.filter { it.label >= 0.7 }
        
        // 找到最小的组大�?       val minSize = minOf(lowScoreSamples.size, mediumScoreSamples.size, highScoreSamples.size)
        
        // 平衡样本
        return lowScoreSamples.take(minSize) + mediumScoreSamples.take(minSize) + highScoreSamples.take(minSize)
    }

    private fun getSkillsForCategory(category: String): List<String> {
        return when (category) {
            "coding" -> listOf("编程", "算法", "调试")
            "writing" -> listOf("写作", "文案", "编辑")
            "research" -> listOf("研究", "分析", "调查")
            "design" -> listOf("设计", "创意", "用户体验")
            "data" -> listOf("数据分析", "统计", "数据可视�?
            "communication" -> listOf("沟？, "协调", "表达")
            "planning" -> listOf("计划", "组织", "项目管理")
            "testing" -> listOf("测试", "质量保证", "问题定位")
            "documentation" -> listOf("文档编写", "技术写�?
            else -> emptyList()
        }
    }

    fun exportDataset(dataset: Dataset, path: String) {
        // 这里可以实现数据集导出到文件的功�?       // 例如导出为CSV或TFRecord格式
    }

    fun importDataset(path: String): Dataset {
        // 这里可以实现从文件导入数据集的功�?       // 例如从CSV或TFRecord格式导入
        return Dataset(emptyList(), emptyList(), emptyList())
    }

    fun clearSamples() {
        samples.clear()
    }

    fun getSampleCount(): Int {
        return samples.size
    }
}
