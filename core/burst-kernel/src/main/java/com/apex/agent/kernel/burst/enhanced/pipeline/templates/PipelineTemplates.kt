package com.apex.agent.kernel.burst.enhanced.pipeline.templates

import com.apex.agent.kernel.burst.enhanced.pipeline.ExecutionPipelineEngine
import com.apex.agent.kernel.burst.enhanced.pipeline.orchestrator.PipelineOrchestrator

/**
 * B35: 流水线模板库
 *
 * 预置常用流水线模板，开箱即用：
 * - 代码分析流水线
 * - 文档生成流水线
 * - 问题解决流水线
 * - 数据处理流水线
 * - 研究调查流水线
 * - 自动化测试流水线
 * - 代码审查流水线
 * - 翻译流水线
 * - 摘要流水线
 * - 调试流水线
 */
class PipelineTemplates {

    /**
     * 代码分析流水线
     * 分析 → 质量检查 → 安全扫描 → 优化建议
     */
    fun codeAnalysis(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "代码分析流水线",
            description = "分析代码质量、安全性和优化建议",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("analyze", "code_quality_analyzer"),
                ExecutionPipelineEngine.PipelineStep.Sequential("security", "security_manager"),
                ExecutionPipelineEngine.PipelineStep.Sequential("optimize", "reasoning.react")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 2, timeoutMs = 120_000L, failFast = false
            ),
            tags = listOf("code", "analysis", "quality")
        )
    }

    /**
     * 问题解决流水线
     * 理解 → 规划 → 执行 → 验证
     */
    fun problemSolving(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "问题解决流水线",
            description = "理解问题 → 规划方案 → 执行 → 验证结果",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("understand", "reasoning.react"),
                ExecutionPipelineEngine.PipelineStep.Sequential("plan", "thinking_agent"),
                ExecutionPipelineEngine.PipelineStep.Sequential("execute", "berserk_execution"),
                ExecutionPipelineEngine.PipelineStep.Sequential("verify", "self_correction")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 3, timeoutMs = 300_000L, enableCheckpointing = true
            ),
            tags = listOf("problem-solving", "reasoning")
        )
    }

    /**
     * 研究调查流水线
     * 搜索 → 检索 → 多跳推理 → 总结
     */
    fun research(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "研究调查流水线",
            description = "搜索信息 → RAG 检索 → 多跳推理 → 总结",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("search", "file_search"),
                ExecutionPipelineEngine.PipelineStep.Sequential("retrieve", "rag_pipeline"),
                ExecutionPipelineEngine.PipelineStep.Sequential("reason", "reasoning.multi-hop"),
                ExecutionPipelineEngine.PipelineStep.Sequential("summarize", "reasoning.chain-of-thought")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 2, timeoutMs = 180_000L
            ),
            tags = listOf("research", "rag", "reasoning")
        )
    }

    /**
     * 创意写作流水线
     * 头脑风暴 → 起草 → 红蓝对抗 → 精炼
     */
    fun creativeWriting(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "创意写作流水线",
            description = "头脑风暴 → 起草 → 对抗审查 → 精炼",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("brainstorm", "reasoning.tree-of-thoughts"),
                ExecutionPipelineEngine.PipelineStep.Sequential("draft", "reasoning.react"),
                ExecutionPipelineEngine.PipelineStep.Sequential("review", "red_blue_adversarial"),
                ExecutionPipelineEngine.PipelineStep.Sequential("refine", "self_correction")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 1, timeoutMs = 240_000L, failFast = false
            ),
            tags = listOf("creative", "writing")
        )
    }

    /**
     * 调试流水线
     * 复现 → 分析 → 定位 → 修复 → 验证
     */
    fun debugging(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "调试流水线",
            description = "复现 → 分析 → 定位 → 修复 → 验证",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("reproduce", "reasoning.react"),
                ExecutionPipelineEngine.PipelineStep.Sequential("analyze", "reasoning.multi-hop"),
                ExecutionPipelineEngine.PipelineStep.Sequential("locate", "file_search"),
                ExecutionPipelineEngine.PipelineStep.Sequential("fix", "recovery_chain"),
                ExecutionPipelineEngine.PipelineStep.Sequential("verify", "self_correction")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 3, timeoutMs = 300_000L, enableCheckpointing = true
            ),
            tags = listOf("debugging", "recovery")
        )
    }

    /**
     * 文档生成流水线
     * 分析代码 → 生成文档 → 审查 → 格式化
     */
    fun docGeneration(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "文档生成流水线",
            description = "分析代码 → 生成文档 → 审查 → 格式化",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("analyze", "code_quality_analyzer"),
                ExecutionPipelineEngine.PipelineStep.Sequential("generate", "reasoning.chain-of-thought"),
                ExecutionPipelineEngine.PipelineStep.Sequential("review", "self_correction"),
                ExecutionPipelineEngine.PipelineStep.Sequential("format", "reasoning.react")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 2, timeoutMs = 120_000L
            ),
            tags = listOf("documentation", "generation")
        )
    }

    /**
     * 翻译流水线
     * 初翻 → 校对 → 润色 → 一致性检查
     */
    fun translation(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "翻译流水线",
            description = "初翻 → 校对 → 润色 → 一致性检查",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("translate", "reasoning.chain-of-thought"),
                ExecutionPipelineEngine.PipelineStep.Sequential("proofread", "reasoning.self-consistency"),
                ExecutionPipelineEngine.PipelineStep.Sequential("polish", "self_correction"),
                ExecutionPipelineEngine.PipelineStep.Sequential("consistency", "reasoning.react")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 1, timeoutMs = 90_000L
            ),
            tags = listOf("translation", "language")
        )
    }

    /**
     * 安全审计流水线
     * 静态分析 → 权限检查 → 漏洞扫描 → 报告
     */
    fun securityAudit(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "安全审计流水线",
            description = "静态分析 → 权限检查 → 漏洞扫描 → 报告",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("static", "code_quality_analyzer"),
                ExecutionPipelineEngine.PipelineStep.Sequential("permission", "security_manager"),
                ExecutionPipelineEngine.PipelineStep.Sequential("vulnerability", "red_blue_adversarial"),
                ExecutionPipelineEngine.PipelineStep.Sequential("report", "reasoning.chain-of-thought")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 2, timeoutMs = 180_000L, failFast = false
            ),
            tags = listOf("security", "audit")
        )
    }

    /**
     * 数据处理流水线
     * 提取 → 清洗 → 转换 → 分析 → 报告
     */
    fun dataProcessing(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "数据处理流水线",
            description = "提取 → 清洗 → 转换 → 分析 → 报告",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("extract", "stream_processor"),
                ExecutionPipelineEngine.PipelineStep.Sequential("clean", "reasoning.react"),
                ExecutionPipelineEngine.PipelineStep.Sequential("transform", "reasoning.chain-of-thought"),
                ExecutionPipelineEngine.PipelineStep.Sequential("analyze", "reasoning.multi-hop"),
                ExecutionPipelineEngine.PipelineStep.Sequential("report", "reasoning.self-consistency")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 2, timeoutMs = 240_000L, parallelism = 2
            ),
            tags = listOf("data", "processing")
        )
    }

    /**
     * 自动化测试流水线
     * 分析 → 生成测试 → 执行 → 验证覆盖率
     */
    fun autoTesting(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = "自动化测试流水线",
            description = "分析 → 生成测试 → 执行 → 验证覆盖率",
            steps = listOf(
                ExecutionPipelineEngine.PipelineStep.Sequential("analyze", "code_quality_analyzer"),
                ExecutionPipelineEngine.PipelineStep.Sequential("generate", "reasoning.tree-of-thoughts"),
                ExecutionPipelineEngine.PipelineStep.Sequential("execute", "berserk_execution"),
                ExecutionPipelineEngine.PipelineStep.Sequential("coverage", "self_correction")
            ),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = 3, timeoutMs = 300_000L, enableCheckpointing = true
            ),
            tags = listOf("testing", "automation")
        )
    }

    /**
     * 获取所有模板
     */
    fun all(): List<PipelineOrchestrator.PipelineDefinition> {
        return listOf(
            codeAnalysis(), problemSolving(), research(), creativeWriting(),
            debugging(), docGeneration(), translation(), securityAudit(),
            dataProcessing(), autoTesting()
        )
    }

    /**
     * 按标签搜索
     */
    fun search(query: String): List<PipelineOrchestrator.PipelineDefinition> {
        val q = query.lowercase()
        return all().filter { template ->
            template.name.contains(q, true) ||
            template.description.contains(q, true) ||
            template.tags.any { it.contains(q, true) }
        }
    }
}
