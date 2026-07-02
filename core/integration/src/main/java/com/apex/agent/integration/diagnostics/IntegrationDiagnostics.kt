package com.apex.agent.integration.diagnostics

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.MarketRegistry

/**
 * 集成诊断报告。
 *
 * 全面诊断集成系统的状态，帮助用户了解：
 * - 市场可用性（哪些市场在线/离线）
 * - 已安装项统计（按分类/按市场/按状态）
 * - 配置问题（缺失 API Key / 无效端点等）
 * - 更新检查（哪些有新版本）
 * - 健康度评分
 *
 * # 使用示例
 *
 * ```
 * val report = IntegrationDiagnostics(center.installedManager).diagnose()
 *
 * println("健康度: ${report.healthScore}/100")
 * println("已安装: ${report.installedCount}")
 * println("问题: ${report.issues.size}")
 * report.issues.forEach { println("  - $it") }
 * ```
 */
class IntegrationDiagnostics(
    private val installedManager: InstalledManager
) {

    /**
     * 诊断报告。
     */
    data class DiagnosticsReport(
        val timestamp: Long,
        val healthScore: Int,                    // 0..100
        val marketCount: Int,
        val availableMarkets: Int,
        val unavailableMarkets: Int,
        val installedCount: Int,
        val installedByCategory: Map<IntegrationCategory, Int>,
        val updatableCount: Int,
        val enabledCount: Int,
        val disabledCount: Int,
        val issues: List<DiagnosticsIssue>,
        val recommendations: List<String>
    )

    /**
     * 诊断问题。
     */
    data class DiagnosticsIssue(
        val severity: IssueSeverity,
        val category: IntegrationCategory?,
        val itemId: String?,
        val title: String,
        val description: String
    )

    enum class IssueSeverity { ERROR, WARNING, INFO }

    /**
     * 执行诊断。
     */
    suspend fun diagnose(): DiagnosticsReport {
        val timestamp = System.currentTimeMillis()
        val issues = mutableListOf<DiagnosticsIssue>()
        val recommendations = mutableListOf<String>()

        // 1. 市场统计
        val allMarkets = MarketRegistry.getAll()
        val marketCount = allMarkets.size
        var availableMarkets = 0
        var unavailableMarkets = 0

        for (market in allMarkets) {
            try {
                if (market.isAvailable()) {
                    availableMarkets++
                } else {
                    unavailableMarkets++
                    issues.add(DiagnosticsIssue(
                        severity = IssueSeverity.WARNING,
                        category = market.category,
                        itemId = null,
                        title = "市场不可用: ${market.displayName}",
                        description = "市场 '${market.displayName}' (${market.marketId}) 当前不可用"
                    ))
                }
            } catch (e: Exception) {
                unavailableMarkets++
                issues.add(DiagnosticsIssue(
                    severity = IssueSeverity.ERROR,
                    category = market.category,
                    itemId = null,
                    title = "市场检查失败: ${market.displayName}",
                    description = "检查市场 '${market.displayName}' 时出错: ${e.message}"
                ))
            }
        }

        // 2. 已安装项统计
        val allInstalled = installedManager.getAll()
        val installedCount = allInstalled.size
        val installedByCategory = allInstalled.groupingBy { it.category }.eachCount()
        val updatableCount = installedManager.getUpdatable().size
        val enabledCount = installedManager.getEnabled().size
        val disabledCount = installedManager.getDisabled().size

        // 3. 检查已安装项的问题
        for (item in allInstalled) {
            // MCP 缺少 command
            if (item.category == IntegrationCategory.MCP) {
                val command = item.metadata["command"]
                val transport = item.metadata["transport"]
                if (command.isNullOrBlank() && transport == "stdio") {
                    issues.add(DiagnosticsIssue(
                        severity = IssueSeverity.ERROR,
                        category = item.category,
                        itemId = item.id,
                        title = "MCP 缺少启动命令",
                        description = "已安装的 MCP '${item.name}' 缺少 command 配置"
                    ))
                }
            }

            // 模型平台缺少 API Key
            if (item.category == IntegrationCategory.MODEL_PLATFORMS) {
                val apiKey = item.metadata["apiKey"]
                if (apiKey.isNullOrBlank()) {
                    issues.add(DiagnosticsIssue(
                        severity = IssueSeverity.WARNING,
                        category = item.category,
                        itemId = item.id,
                        title = "模型平台缺少 API Key",
                        description = "已安装的模型平台 '${item.name}' 尚未配置 API Key"
                    ))
                }
            }

            // 已禁用
            if (!item.enabled) {
                issues.add(DiagnosticsIssue(
                    severity = IssueSeverity.INFO,
                    category = item.category,
                    itemId = item.id,
                    title = "已禁用: ${item.name}",
                    description = "集成项 '${item.name}' 当前已禁用"
                ))
            }
        }

        // 4. 更新检查
        if (updatableCount > 0) {
            issues.add(DiagnosticsIssue(
                severity = IssueSeverity.INFO,
                category = null,
                itemId = null,
                title = "$updatableCount 个项有可用更新",
                description = "有 $updatableCount 个已安装项可以更新到新版本"
            ))
            recommendations.add("检查并更新 $updatableCount 个集成项")
        }

        // 5. 推荐
        if (installedCount == 0) {
            recommendations.add("尚未安装任何集成项，浏览市场开始集成")
        }
        if (marketCount > 0 && availableMarkets == 0) {
            recommendations.add("所有市场不可用，请检查网络连接")
        }
        if (disabledCount > enabledCount) {
            recommendations.add("大量集成项已禁用，考虑清理或重新启用")
        }
        val errorCount = issues.count { it.severity == IssueSeverity.ERROR }
        if (errorCount > 0) {
            recommendations.add("有 $errorCount 个错误需要处理")
        }

        // 6. 健康度评分
        val healthScore = calculateHealthScore(
            marketCount = marketCount,
            availableMarkets = availableMarkets,
            installedCount = installedCount,
            errorCount = issues.count { it.severity == IssueSeverity.ERROR },
            warningCount = issues.count { it.severity == IssueSeverity.WARNING },
            updatableCount = updatableCount
        )

        return DiagnosticsReport(
            timestamp = timestamp,
            healthScore = healthScore,
            marketCount = marketCount,
            availableMarkets = availableMarkets,
            unavailableMarkets = unavailableMarkets,
            installedCount = installedCount,
            installedByCategory = installedByCategory,
            updatableCount = updatableCount,
            enabledCount = enabledCount,
            disabledCount = disabledCount,
            issues = issues,
            recommendations = recommendations
        )
    }

    /**
     * 计算健康度评分（0..100）。
     */
    private fun calculateHealthScore(
        marketCount: Int,
        availableMarkets: Int,
        installedCount: Int,
        errorCount: Int,
        warningCount: Int,
        updatableCount: Int
    ): Int {
        var score = 100

        // 市场可用性（30 分）
        if (marketCount > 0) {
            val marketScore = (availableMarkets.toDouble() / marketCount * 30).toInt()
            score -= (30 - marketScore)
        }

        // 错误扣分（每个 -10，最多 -30）
        score -= (errorCount * 10).coerceAtMost(30)

        // 警告扣分（每个 -3，最多 -15）
        score -= (warningCount * 3).coerceAtMost(15)

        // 更新扣分（每个 -1，最多 -10）
        score -= updatableCount.coerceAtMost(10)

        // 空安装扣分（-10）
        if (installedCount == 0) score -= 10

        return score.coerceIn(0, 100)
    }

    /**
     * 生成人类可读的报告摘要。
     */
    fun formatReport(report: DiagnosticsReport): String {
        return buildString {
            appendLine("=== 集成诊断报告 ===")
            appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(report.timestamp))}")
            appendLine("健康度: ${report.healthScore}/100")
            appendLine()
            appendLine("市场统计:")
            appendLine("  总计: ${report.marketCount}")
            appendLine("  可用: ${report.availableMarkets}")
            appendLine("  不可用: ${report.unavailableMarkets}")
            appendLine()
            appendLine("已安装统计:")
            appendLine("  总计: ${report.installedCount}")
            appendLine("  已启用: ${report.enabledCount}")
            appendLine("  已禁用: ${report.disabledCount}")
            appendLine("  可更新: ${report.updatableCount}")
            appendLine("  按分类:")
            report.installedByCategory.forEach { (cat, count) ->
                appendLine("    ${cat.displayName}: $count")
            }
            appendLine()
            if (report.issues.isNotEmpty()) {
                appendLine("问题 (${report.issues.size}):")
                report.issues.forEach { issue ->
                    val icon = when (issue.severity) {
                        IssueSeverity.ERROR -> "[ERROR]"
                        IssueSeverity.WARNING -> "[WARN]"
                        IssueSeverity.INFO -> "[INFO]"
                    }
                    appendLine("  $icon ${issue.title}")
                    if (issue.description.isNotBlank()) {
                        appendLine("        ${issue.description}")
                    }
                }
            }
            if (report.recommendations.isNotEmpty()) {
                appendLine()
                appendLine("建议:")
                report.recommendations.forEach { rec ->
                    appendLine("  - $rec")
                }
            }
        }
    }
}
