package com.apex.agent.kernel.burst.enhanced.pipeline.dataflow

import java.util.concurrent.ConcurrentHashMap

/**
 * B32: 数据流管理器
 *
 * 管理流水线步骤间的数据传递与转换：
 * - 变量作用域（全局/步骤级/临时）
 * - 数据转换函数链
 * - 数据验证
 * - 数据溯源
 */
class DataFlowManager {

    /**
     * 变量作用域
     */
    enum class Scope { GLOBAL, STEP, TEMPORARY }

    /**
     * 数据变量
     */
    data class DataVariable(
        val name: String,
        val value: Any?,
        val type: DataType,
        val scope: Scope,
        val sourceStepId: String?,
        val createdAt: Long = System.currentTimeMillis(),
        val metadata: Map<String, String> = emptyMap()
    )

    enum class DataType {
        STRING, INTEGER, FLOAT, BOOLEAN, LIST, MAP, JSON, BINARY, NULL
    }

    /**
     * 数据转换函数
     */
    fun interface Transform {
        fun transform(input: Any?): Any?
    }

    /**
     * 数据验证规则
     */
    data class ValidationRule(
        val name: String,
        val validator: (Any?) -> Boolean,
        val errorMessage: String
    )

    /**
     * 数据流转记录
     */
    data class DataFlowRecord(
        val fromStep: String,
        val toStep: String,
        val variableName: String,
        val value: Any?,
        val transformApplied: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    // ============ 存储 ============

    private val globalVariables = ConcurrentHashMap<String, DataVariable>()
    private val stepVariables = ConcurrentHashMap<String, ConcurrentHashMap<String, DataVariable>>()
    private val tempVariables = ConcurrentHashMap<String, DataVariable>()
    private val transforms = ConcurrentHashMap<String, Transform>()
    private val validationRules = ConcurrentHashMap<String, ValidationRule>()
    private val flowHistory = mutableListOf<DataFlowRecord>()

    // ============ 公共 API ============

    /**
     * 设置变量
     */
    fun setVariable(name: String, value: Any?, scope: Scope = Scope.GLOBAL, sourceStepId: String? = null) {
        val type = inferType(value)
        val variable = DataVariable(name, value, type, scope, sourceStepId)
        when (scope) {
            Scope.GLOBAL -> globalVariables[name] = variable
            Scope.TEMPORARY -> tempVariables[name] = variable
            Scope.STEP -> {
                if (sourceStepId != null) {
                    stepVariables.computeIfAbsent(sourceStepId) { ConcurrentHashMap() }[name] = variable
                }
            }
        }
    }

    /**
     * 获取变量（按作用域查找：TEMPORARY → STEP → GLOBAL）
     */
    fun getVariable(name: String, stepId: String? = null): DataVariable? {
        tempVariables[name]?.let { return it }
        if (stepId != null) {
            stepVariables[stepId]?.get(name)?.let { return it }
        }
        return globalVariables[name]
    }

    /**
     * 获取变量值
     */
    fun getValue(name: String, stepId: String? = null, default: Any? = null): Any? {
        return getVariable(name, stepId)?.value ?: default
    }

    /**
     * 获取字符串值
     */
    fun getString(name: String, stepId: String? = null, default: String = ""): String {
        return getValue(name, stepId, default)?.toString() ?: default
    }

    /**
     * 获取步骤输出（约定：step_output_<stepId>）
     */
    fun getStepOutput(stepId: String): String {
        return getString("step_output_$stepId")
    }

    /**
     * 设置步骤输出
     */
    fun setStepOutput(stepId: String, output: String) {
        setVariable("step_output_$stepId", output, Scope.STEP, stepId)
    }

    /**
     * 注册转换函数
     */
    fun registerTransform(name: String, transform: Transform) {
        transforms[name] = transform
    }

    /**
     * 应用转换
     */
    fun applyTransform(value: Any?, transformName: String): Any? {
        val transform = transforms[transformName] ?: return value
        return transform.transform(value)
    }

    /**
     * 应用转换链
     */
    fun applyTransformChain(value: Any?, transformNames: List<String>): Any? {
        var current = value
        for (name in transformNames) {
            current = applyTransform(current, name)
        }
        return current
    }

    /**
     * 注册验证规则
     */
    fun registerValidation(rule: ValidationRule) {
        validationRules[rule.name] = rule
    }

    /**
     * 验证变量
     */
    fun validate(name: String, ruleName: String): ValidationResult {
        val rule = validationRules[ruleName] ?: return ValidationResult(false, "规则不存在: $ruleName")
        val value = getVariable(name)?.value
        return if (rule.validator(value)) {
            ValidationResult(true, null)
        } else {
            ValidationResult(false, rule.errorMessage)
        }
    }

    data class ValidationResult(val valid: Boolean, val error: String?)

    /**
     * 记录数据流转
     */
    fun recordFlow(fromStep: String, toStep: String, variableName: String, value: Any?, transforms: List<String> = emptyList()) {
        flowHistory.add(DataFlowRecord(fromStep, toStep, variableName, value, transforms))
        while (flowHistory.size > 500) flowHistory.removeAt(0)
    }

    /**
     * 获取数据流历史
     */
    fun getFlowHistory(): List<DataFlowRecord> = flowHistory.toList()

    /**
     * 获取所有变量
     */
    fun getAllVariables(scope: Scope? = null): List<DataVariable> {
        val all = mutableListOf<DataVariable>()
        if (scope == null || scope == Scope.GLOBAL) all.addAll(globalVariables.values)
        if (scope == null || scope == Scope.TEMPORARY) all.addAll(tempVariables.values)
        if (scope == null || scope == Scope.STEP) all.addAll(stepVariables.values.flatMap { it.values })
        return all
    }

    /**
     * 清理步骤变量
     */
    fun clearStepVariables(stepId: String) {
        stepVariables.remove(stepId)
    }

    /**
     * 清理临时变量
     */
    fun clearTempVariables() {
        tempVariables.clear()
    }

    /**
     * 清空所有
     */
    fun clearAll() {
        globalVariables.clear()
        stepVariables.clear()
        tempVariables.clear()
        flowHistory.clear()
    }

    /**
     * 生成数据流报告
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 数据流报告 ═══")
        sb.appendLine("全局变量: ${globalVariables.size}")
        sb.appendLine("步骤变量: ${stepVariables.values.sumOf { it.size }}")
        sb.appendLine("临时变量: ${tempVariables.size}")
        sb.appendLine("转换函数: ${transforms.size}")
        sb.appendLine("验证规则: ${validationRules.size}")
        sb.appendLine("流转记录: ${flowHistory.size}")
        sb.appendLine()
        sb.appendLine("全局变量:")
        globalVariables.forEach { (name, var_) ->
            val valueStr = var_.value?.toString()?.take(50) ?: "null"
            sb.appendLine("  $name (${var_.type}): $valueStr")
        }
        sb.appendLine()
        sb.appendLine("最近流转:")
        flowHistory.takeLast(10).forEach { record ->
            sb.appendLine("  ${record.fromStep} → ${record.toStep}: ${record.variableName}")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    // ============ 内置转换 ============

    init {
        // 注册常用转换
        registerTransform("toString") { it?.toString() ?: "" }
        registerTransform("toInt") { (it?.toString()?.toIntOrNull() ?: 0) }
        registerTransform("trim") { it?.toString()?.trim() ?: "" }
        registerTransform("lowercase") { it?.toString()?.lowercase() ?: "" }
        registerTransform("uppercase") { it?.toString()?.uppercase() ?: "" }
        registerTransform("json_pretty") {
            val str = it?.toString() ?: return@registerTransform ""
            try { org.json.JSONObject(str).toString(2) } catch (e: Exception) { str }
        }
        registerTransform("truncate_100") { it?.toString()?.take(100) ?: "" }
        registerTransform("truncate_500") { it?.toString()?.take(500) ?: "" }
    }

    private fun inferType(value: Any?): DataType {
        return when (value) {
            null -> DataType.NULL
            is String -> DataType.STRING
            is Int, is Long -> DataType.INTEGER
            is Float, is Double -> DataType.FLOAT
            is Boolean -> DataType.BOOLEAN
            is List<*> -> DataType.LIST
            is Map<*, *> -> DataType.MAP
            is ByteArray -> DataType.BINARY
            else -> DataType.STRING
        }
    }
}
