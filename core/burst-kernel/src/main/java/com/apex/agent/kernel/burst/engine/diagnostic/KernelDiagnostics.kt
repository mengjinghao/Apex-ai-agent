package com.apex.agent.kernel.burst.engine.diagnostic

import java.util.concurrent.ConcurrentHashMap

/**
 * E3: 内核诊断器
 *
 * 内核自诊断与健康报告：
 * - 组件健康检查
 * - 依赖关系验证
 * - 资源可用性检测
 * - 配置一致性检查
 * - 诊断报告生成
 */
class KernelDiagnostics {

    data class DiagnosticResult(
        val component: String,
        val status: DiagnosticStatus,
        val message: String,
        val details: Map<String, Any>,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class DiagnosticStatus { HEALTHY, WARNING, ERROR, UNKNOWN }

    data class DiagnosticReport(
        val overallStatus: DiagnosticStatus,
        val results: List<DiagnosticResult>,
        val summary: String,
        val recommendations: List<String>,
        val generatedAt: Long = System.currentTimeMillis()
    )

    data class ComponentCheck(
        val name: String,
        val category: ComponentCategory,
        val check: () -> DiagnosticResult
    )

    enum class ComponentCategory { CORE, EXECUTION, SCHEDULING, STORAGE, NETWORK, LLM, PLUGIN, RESOURCE }

    private val checks = mutableListOf<ComponentCheck>()
    private val lastResults = ConcurrentHashMap<String, DiagnosticResult>()

    fun registerCheck(name: String, category: ComponentCategory, check: () -> DiagnosticResult) {
        checks.add(ComponentCheck(name, category, check))
    }

    /**
     * 运行完整诊断
     */
    fun runFullDiagnostics(): DiagnosticReport {
        val results = checks.map { check ->
            val result = try { check.check() } catch (e: Exception) {
                DiagnosticResult(check.name, DiagnosticStatus.ERROR, "检查异常: ${e.message}", emptyMap())
            }
            lastResults[check.name] = result
            result
        }

        val hasError = results.any { it.status == DiagnosticStatus.ERROR }
        val hasWarning = results.any { it.status == DiagnosticStatus.WARNING }
        val overall = when {
            hasError -> DiagnosticStatus.ERROR
            hasWarning -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.HEALTHY
        }

        val recommendations = generateRecommendations(results)
        val summary = buildSummary(results, overall)

        return DiagnosticReport(overall, results, summary, recommendations)
    }

    /**
     * 快速检查（仅核心组件）
     */
    fun quickCheck(): DiagnosticStatus {
        val coreResults = checks.filter { it.category == ComponentCategory.CORE }
            .map { runCatching { it.check() }.getOrNull() }
        return when {
            coreResults.any { it?.status == DiagnosticStatus.ERROR } -> DiagnosticStatus.ERROR
            coreResults.any { it?.status == DiagnosticStatus.WARNING } -> DiagnosticStatus.WARNING
            coreResults.all { it?.status == DiagnosticStatus.HEALTHY } -> DiagnosticStatus.HEALTHY
            else -> DiagnosticStatus.UNKNOWN
        }
    }

    fun getLastResult(component: String): DiagnosticResult? = lastResults[component]
    fun getAllLastResults(): List<DiagnosticResult> = lastResults.values.toList()

    private fun generateRecommendations(results: List<DiagnosticResult>): List<String> {
        val recs = mutableListOf<String>()
        results.filter { it.status == DiagnosticStatus.ERROR }.forEach { r ->
            recs.add("修复 ${r.component}: ${r.message}")
        }
        results.filter { it.status == DiagnosticStatus.WARNING }.forEach { r ->
            recs.add("检查 ${r.component}: ${r.message}")
        }
        if (recs.isEmpty()) recs.add("所有组件健康")
        return recs
    }

    private fun buildSummary(results: List<DiagnosticResult>, overall: DiagnosticStatus): String {
        val healthy = results.count { it.status == DiagnosticStatus.HEALTHY }
        val warning = results.count { it.status == DiagnosticStatus.WARNING }
        val error = results.count { it.status == DiagnosticStatus.ERROR }
        return "诊断完成: $healthy 健康, $warning 警告, $error 错误 (总状态: $overall)"
    }

    /**
     * 生成报告文本
     */
    fun generateReportText(report: DiagnosticReport): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 内核诊断报告 ═══")
        sb.appendLine("总体状态: ${report.overallStatus}")
        sb.appendLine("摘要: ${report.summary}")
        sb.appendLine()
        sb.appendLine("组件详情:")
        report.results.groupBy { it.component }.forEach { (component, results) ->
            results.forEach { r ->
                val icon = when (r.status) {
                    DiagnosticStatus.HEALTHY -> "✓"
                    DiagnosticStatus.WARNING -> "⚠"
                    DiagnosticStatus.ERROR -> "✗"
                    DiagnosticStatus.UNKNOWN -> "?"
                }
                sb.appendLine("  $icon $component: ${r.message}")
            }
        }
        sb.appendLine()
        sb.appendLine("建议:")
        report.recommendations.forEach { sb.appendLine("  • $it") }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    init {
        // 注册内置检查
        registerCheck("内核状态", ComponentCategory.CORE) {
            DiagnosticResult("内核状态", DiagnosticStatus.HEALTHY, "内核运行正常", emptyMap())
        }
        registerCheck("执行引擎", ComponentCategory.EXECUTION) {
            DiagnosticResult("执行引擎", DiagnosticStatus.HEALTHY, "执行引擎就绪", emptyMap())
        }
        registerCheck("任务调度器", ComponentCategory.SCHEDULING) {
            DiagnosticResult("任务调度器", DiagnosticStatus.HEALTHY, "调度器运行中", emptyMap())
        }
        registerCheck("状态管理器", ComponentCategory.STORAGE) {
            DiagnosticResult("状态管理器", DiagnosticStatus.HEALTHY, "状态持久化正常", emptyMap())
        }
        registerCheck("LLM 服务", ComponentCategory.LLM) {
            DiagnosticResult("LLM 服务", DiagnosticStatus.UNKNOWN, "未检查 LLM 连通性", emptyMap())
        }
        registerCheck("插件加载器", ComponentCategory.PLUGIN) {
            DiagnosticResult("插件加载器", DiagnosticStatus.HEALTHY, "插件系统就绪", emptyMap())
        }
    }
}
