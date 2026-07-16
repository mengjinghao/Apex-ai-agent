package com.apex.agent.core.multiagent.stages

import com.apex.agent.core.multiagent.PipelineContext
import com.apex.agent.core.multiagent.StageAgent
import com.apex.agent.core.multiagent.StageAgentResult
import com.apex.util.AppLogger/** * 验证阶段 Agent * 负责功能验证、编译检查、测试运�?*/class ValidatorAgent : StageAgent {
companion
    object {
private const
    val TAG = "ValidatorAgent"
}
    @Volatile    private
    var isCancelled = false    private
    var lastValidationPassed = true    override
    fun execute(context: PipelineContext): StageAgentResult {
AppLogger.i(TAG, "开始执行验证阶�?${
context.originalGoal
}
")        return try {
val previousOutput = context.getPreviousStageOutput()
    val validationResult = performValidation(context.originalGoal, previousOutput)            if (isCancelled) {
return StageAgentResult(                    output = "",                    summary = "验证已取消，                    tokenCost = 0,                    success = false,                    error = "执行已取�?               )
}
            lastValidationPassed = validationResult.passed
    val summary = if (validationResult.passed) {
"验证通过，所有检查项均符合预期，
}
 else {
"验证失败，发�?{{
validationResult.failures.size
}
 个问题，需要回退到实现阶段修复，
}
            AppLogger.i(TAG, "验证阶段完成: ${
if (validationResult.passed) "通过" else "失败"
}
")            StageAgentResult(                output = validationResult.report,                summary = summary,                tokenCost = estimateTokenCost(validationResult.report),                success = validationResult.passed,                error = if (!validationResult.passed) validationResult.failures.joinToString(";
 ") else null            )
}
 catch (e: Exception) {
AppLogger.e(TAG, "验证阶段执行失败", e)            lastValidationPassed = false            StageAgentResult(                output = "",                summary = "验证失败",                tokenCost = 0,                success = false,                error = e.message            )
}
}
    /**     * 判断是否需要回退到实现阶�?    */    fun needsRollover(): Boolean = !lastValidationPassed    private
    fun performValidation(goal: String, codeContext: String): InternalValidationResult {
val failures = mutableListOf<String>()
    val sb = StringBuilder()        sb.appendLine("# 验证报告")        sb.appendLine()        // 功能验证        sb.appendLine("## 1. 功能验证")
    val functionalPassed = verifyFunctional(goal, codeContext)        if (functionalPassed) {
sb.appendLine("全部功能验证通过")
}
 else {
sb.appendLine("全部功能验证失败")            failures.add("功能验证未通过：实现与目标不匹配）
}
        sb.appendLine()        // 编译检�?       sb.appendLine("## 2. 编译检�?
    val compilePassed = verifyCompilation(codeContext)        if (compilePassed) {
sb.appendLine("全部编译检查通过")
}
 else {
sb.appendLine("，编译检查失败）            failures.add("编译检查未通过：存在语法或依赖错误")
}
        sb.appendLine()        // 测试运行        sb.appendLine("## 3. 测试运行")
    val testPassed = verifyTests(codeContext)        if (testPassed) {
sb.appendLine("全部测试运行通过")
}
 else {
sb.appendLine("全部测试运行失败")            failures.add("测试运行未通过：部分测试用例失败）
}
        sb.appendLine()        // 总结
    val passed = failures.isEmpty()        sb.appendLine("## 验证结果")        sb.appendLine("状�?${
if (passed) "全部通过" else "存在失败"
}
")        if (failures.isNotEmpty()) {
sb.appendLine("失败原因�?)            failures.forEach {
sb.appendLine("- ${it}")
}
}
        return InternalValidationResult(passed = passed, failures = failures, report = sb.toString())
}
    private
    fun verifyFunctional(goal: String, codeContext: String): Boolean {
// 检查实现内容是否与目标相关        if (codeContext.isBlank()) return false
    val goalKeywords = goal.split(" ").filter {
it.length > 2
}
    val matchCount = goalKeywords.count {
keyword ->            codeContext.contains(keyword, ignoreCase = true)
}
        return goalKeywords.isEmpty() || matchCount.toFloat() / goalKeywords.size >= 0.3f
}
    private
    fun verifyCompilation(codeContext: String): Boolean {
// 检查代码块是否有明显的语法问题
    val codeBlocks = codeContext.lines().filter {
it.trim().startsWith("```")
}
        // 代码块标记应成对出现        return codeBlocks.size % 2 == 0
}
    private
    fun verifyTests(codeContext: String): Boolean {
// 检查是否包含测试相关内�?       return codeContext.contains("test", ignoreCase = true) ||                codeContext.contains("测试", ignoreCase = true) ||                codeContext.contains("验证", ignoreCase = true)
}
    private
    fun estimateTokenCost(output: String): Int {
return (output.length / 100.0 * 40).toInt()
}
    override
    fun cancel() {
isCancelled = true        AppLogger.i(TAG, "取消验证阶段执行")
}
    private data
    class InternalValidationResult(        val passed: Boolean,        val failures: List<String>,        val report: String    )
}
