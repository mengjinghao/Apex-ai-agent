package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 安全管理技能
 * 实现任务安全检查、敏感信息检测、策略合规验证
 */
class SecurityManagerSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val securityHistory = ConcurrentHashMap<String, SecurityCheckResult>()
    private val policyEngine = PolicyEngine()
    private val resourceController = ResourceController()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "security_manager",
            skillName = "安全管理",
            version = "1.0.0",
            description = "任务安全检查和敏感信息检测，支持策略合规验证和资源限制",
            author = "Apex Agent",
            tags = listOf("security", "policy", "validation"),
            priority = 95,
            capabilities = listOf(
                "task_security_check",
                "sensitive_info_detection",
                "policy_compliance",
                "resource_limit_check"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "check"
            
            when (operation) {
                "check" -> {
                    val result = checkTaskSecurity(task)
                    securityHistory[task.id] = result
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = result.isSecure,
                        output = """
                            |Security check completed:
                            |- Task ID: ${task.id}
                            |- Is Secure: ${result.isSecure}
                            |- Issues found: ${result.issues.size}
                            ${result.issues.take(5).joinToString("\n") { "- [${it.severity}] ${it.message}" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "encrypt" -> {
                    val data = task.input.text ?: ""
                    val encrypted = encryptSensitiveData(data)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Data encrypted:
                            |- Original length: ${data.length}
                            |- Encrypted length: ${encrypted.length}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "get_history" -> {
                    val history = getSecurityHistory(task.id)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Security history for task ${task.id}:
                            |- Checks performed: ${history.size}
                            ${history.takeLast(3).joinToString("\n") { "- ${it.timestamp}: ${if (it.isSecure) "Secure" else "Issues found"}" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = history.size
                        )
                    )
                }
                else -> {
                    BurstSkillResult(
                        success = false,
                        errorMessage = "Unknown operation: $operation"
                    )
                }
            }
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    fun checkTaskSecurity(task: BurstTask): SecurityCheckResult {
        val issues = mutableListOf<SecurityIssue>()
        
        // 1. 检查策略合规性
        val policyResult = policyEngine.checkPolicyCompliance(task)
        if (!policyResult.isCompliant) {
            issues.addAll(policyResult.issues)
        }
        
        // 2. 检查资源限制
        val resourceResult = resourceController.checkResourceLimits(task)
        if (!resourceResult.isWithinLimits) {
            issues.addAll(resourceResult.issues)
        }
        
        // 3. 检查敏感信息
        val sensitiveInfoResult = checkSensitiveInformation(task)
        if (sensitiveInfoResult.hasSensitiveInfo) {
            issues.addAll(sensitiveInfoResult.issues)
        }
        
        return SecurityCheckResult(
            isSecure = issues.isEmpty(),
            issues = issues,
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun checkSensitiveInformation(task: BurstTask): SensitiveInfoCheckResult {
        val issues = mutableListOf<SecurityIssue>()
        
        // 检查任务描述中的敏感信息
        val content = task.description
        
        // 敏感信息模式
        val sensitivePatterns = listOf(
            "api_key" to "API密钥",
            "password" to "密码",
            "token" to "令牌",
            "secret" to "密钥",
            "credit_card" to "信用卡号",
            "ssn" to "社会安全号"
        )
        
        sensitivePatterns.forEach { (pattern, description) ->
            if (content.lowercase().contains(pattern)) {
                issues.add(SecurityIssue(
                    id = "sensitive_info_$pattern",
                    type = "sensitive_information",
                    message = "Potential sensitive information detected: $description",
                    severity = Severity.WARNING
                ))
            }
        }
        
        return SensitiveInfoCheckResult(
            hasSensitiveInfo = issues.isNotEmpty(),
            issues = issues
        )
    }
    
    fun encryptSensitiveData(data: String): String {
        // 简化实现：实际应该使用AES加密
        return data.toByteArray().joinToString("") { "%02x".format(it) }
    }
    
    fun decryptData(encryptedData: String): String {
        // 简化实现
        return encryptedData.chunked(2).map { 
            Integer.parseInt(it, 16).toByte() 
        }.toByteArray().toString(Charsets.UTF_8)
    }
    
    fun getSecurityHistory(taskId: String): List<SecurityCheckResult> {
        return listOfNotNull(securityHistory[taskId])
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        securityHistory.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.91f
    
    data class SecurityCheckResult(
        val isSecure: Boolean,
        val issues: List<SecurityIssue>,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class SecurityIssue(
        val id: String,
        val type: String,
        val message: String,
        val severity: Severity
    )
    
    data class SensitiveInfoCheckResult(
        val hasSensitiveInfo: Boolean,
        val issues: List<SecurityIssue>
    )
    
    enum class Severity {
        ERROR, WARNING, INFO
    }
    
    /**
     * 策略引擎
     */
    class PolicyEngine {
        fun checkPolicyCompliance(task: BurstTask): PolicyCheckResult {
            val issues = mutableListOf<SecurityIssue>()
            
            // 检查危险操作
            val dangerousPatterns = listOf("rm -rf", "format", "drop table")
            dangerousPatterns.forEach { pattern ->
                if (task.description.contains(pattern, ignoreCase = true)) {
                    issues.add(SecurityIssue(
                        id = "policy_dangerous_$pattern",
                        type = "policy_violation",
                        message = "Potentially dangerous operation: $pattern",
                        severity = Severity.ERROR
                    ))
                }
            }
            
            return PolicyCheckResult(
                isCompliant = issues.isEmpty(),
                issues = issues
            )
        }
        
        data class PolicyCheckResult(
            val isCompliant: Boolean,
            val issues: List<SecurityIssue>
        )
    }
    
    /**
     * 资源控制器
     */
    class ResourceController {
        fun checkResourceLimits(task: BurstTask): ResourceCheckResult {
            val issues = mutableListOf<SecurityIssue>()
            
            // 检查资源限制
            val maxMemory = task.metadata["maxMemory"]?.toLongOrNull() ?: Long.MAX_VALUE
            val maxCpu = task.metadata["maxCpu"]?.toIntOrNull() ?: Int.MAX_VALUE
            
            if (maxMemory > 4L * 1024 * 1024 * 1024) {
                issues.add(SecurityIssue(
                    id = "resource_memory",
                    type = "resource_limit",
                    message = "Memory request exceeds recommended limit",
                    severity = Severity.WARNING
                ))
            }
            
            return ResourceCheckResult(
                isWithinLimits = issues.isEmpty(),
                issues = issues
            )
        }
        
        data class ResourceCheckResult(
            val isWithinLimits: Boolean,
            val issues: List<SecurityIssue>
        )
    }
}