package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 自修正循环技能
 * 实现迭代优化、质量评估、收敛检测
 */
class SelfCorrectionSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _correctionState = MutableStateFlow(CorrectionState())
    val correctionState: StateFlow<CorrectionState> = _correctionState.asStateFlow()
    
    private val correctionHistory = ConcurrentHashMap<String, MutableList<CorrectionRecord>>()
    
    private val maxIterations: Int = 5
    private val convergenceThreshold: Double = 0.95
    
    init {
        manifest = BurstSkillManifest(
            skillId = "self_correction",
            skillName = "自修正循环",
            version = "1.0.0",
            description = "迭代自修正循环，支持质量评估、收敛检测和优化建议",
            author = "Apex Agent",
            tags = listOf("correction", "iteration", "optimization"),
            priority = 88,
            capabilities = listOf(
                "iterative_correction",
                "quality_assessment",
                "convergence_detection",
                "optimization_suggestions"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val input = task.input.text ?: task.description
            val taskId = task.id
            
            var currentInput = input
            var iteration = 0
            var bestQuality = 0.0
            var bestResult: ProcessingResult? = null
            
            _correctionState.value = CorrectionState(
                iteration = iteration,
                status = CorrectionStatus.RUNNING
            )
            
            val history = mutableListOf<CorrectionRecord>()
            
            while (iteration < maxIterations) {
                // 处理当前输入
                val result = processInput(currentInput)
                
                // 验证结果
                val validation = validateResult(result)
                
                // 计算质量分数
                val quality = calculateQuality(result, validation)
                
                // 记录历史
                history.add(CorrectionRecord(
                    iteration = iteration,
                    inputQuality = quality,
                    outputQuality = quality,
                    corrections = validation.suggestedCorrections
                ))
                
                // 更新最佳结果
                if (quality > bestQuality) {
                    bestQuality = quality
                    bestResult = result
                }
                
                _correctionState.value = _correctionState.value.copy(
                    iteration = iteration + 1,
                    currentQuality = quality,
                    bestQuality = bestQuality,
                    correctionsApplied = validation.suggestedCorrections
                )
                
                // 检查收敛
                if (quality >= convergenceThreshold) {
                    _correctionState.value = _correctionState.value.copy(
                        status = CorrectionStatus.CONVERGED
                    )
                    break
                }
                
                // 应用修正
                if (!validation.isValid) {
                    currentInput = applyCorrections(currentInput, validation.suggestedCorrections)
                    iteration++
                } else {
                    break
                }
            }
            
            if (iteration >= maxIterations) {
                _correctionState.value = _correctionState.value.copy(
                    status = CorrectionStatus.MAX_ITERATIONS
                )
            }
            
            correctionHistory[taskId] = history
            
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = """
                    |Self-correction loop completed:
                    |- Iterations: $iteration
                    |- Best quality: $bestQuality
                    |- Status: ${_correctionState.value.status}
                    |- Corrections applied: ${history.size}
                    ${if (bestResult != null) "- Final output: ${bestResult!!.output.take(100)}..." else ""}
                """.trimMargin(),
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = iteration
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    private fun processInput(input: String): ProcessingResult {
        // 模拟处理：实际应该调用处理器
        return ProcessingResult(
            output = "Processed: $input",
            quality = 0.5 + (input.length / 1000.0).coerceAtMost(0.4)
        )
    }
    
    private fun validateResult(result: ProcessingResult): ValidationResult {
        val issues = mutableListOf<String>()
        val corrections = mutableListOf<String>()
        
        // 检查输出长度
        if (result.output.length < 50) {
            issues.add("Output too short")
            corrections.add("Expand the output with more details")
        }
        
        // 检查质量分数
        if (result.quality < 0.7) {
            issues.add("Quality below threshold")
            corrections.add("Improve the processing quality")
        }
        
        // 检查内容完整性
        if (!result.output.contains("processed")) {
            issues.add("Missing key content")
            corrections.add("Ensure all key content is included")
        }
        
        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            suggestedCorrections = corrections,
            qualityScore = result.quality
        )
    }
    
    private fun calculateQuality(result: ProcessingResult, validation: ValidationResult): Double {
        var quality = result.quality
        
        // 根据验证结果调整质量分数
        if (validation.isValid) {
            quality += 0.1
        } else {
            quality -= validation.issues.size * 0.05
        }
        
        return quality.coerceIn(0.0, 1.0)
    }
    
    private fun applyCorrections(input: String, corrections: List<String>): String {
        // 模拟应用修正：实际应该根据修正建议修改输入
        return "$input [Corrected: ${corrections.joinToString(", ")}]"
    }
    
    fun getCorrectionHistory(taskId: String): List<CorrectionRecord> {
        return correctionHistory[taskId] ?: emptyList()
    }
    
    fun clearHistory(taskId: String) {
        correctionHistory.remove(taskId)
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        correctionHistory.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.87f
    
    data class CorrectionState(
        val iteration: Int = 0,
        val status: CorrectionStatus = CorrectionStatus.IDLE,
        val currentQuality: Double = 0.0,
        val bestQuality: Double = 0.0,
        val correctionsApplied: List<String> = emptyList()
    )
    
    enum class CorrectionStatus {
        IDLE, RUNNING, CONVERGED, MAX_ITERATIONS, FAILED
    }
    
    data class CorrectionRecord(
        val iteration: Int,
        val inputQuality: Double,
        val outputQuality: Double,
        val corrections: List<String>
    )
    
    data class ProcessingResult(
        val output: String,
        val quality: Double
    )
    
    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>,
        val suggestedCorrections: List<String>,
        val qualityScore: Double
    )
}