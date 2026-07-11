package com.apex.agent.plugins.burst.builtin

import kotlinx.coroutines.Dispatchers

import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class TreeOfThoughtsSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var context: BurstSkillContext
    // 修复 X3：isPaused 跨线程读写需 @Volatile
    @Volatile private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val maxDepth = 5
    private val branchingFactor = 3
    // 修复 T5：explorationPaths 原为实例字段，并发调用 execute() 时 clear()+addAll() 交叉
    // 改为局部变量（在 execute 内创建），这里仅保留作为类型声明占位

    // 修复 X3：统计字段改为原子
    private val totalExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    private val successfulExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalExecutionTimeMs = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        manifest = BurstSkillManifest(
            skillId = "reasoning.tree-of-thoughts",
            skillName = "Tree of Thoughts",
            version = "1.0.0",
            description = "Tree of Thoughts reasoning strategy that explores multiple reasoning paths and selects the optimal solution, suitable for creative tasks and complex problems",
            author = "Apex Agent",
            tags = listOf("reasoning", "tree-of-thoughts", "exploration", "creativity"),
            priority = 88,
            capabilities = listOf(
                "multi_path_exploration",
                "branching_reasoning",
                "path_evaluation",
                "best_path_selection"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val llm = context.llmService

        try {
            if (isPaused) {
                return@runBlocking BurstSkillResult(
                    success = false,
                    errorMessage = "Skill paused"
                )
            }

            val rootNode = ThoughtNode(
                id = "root",
                content = task.description,
                depth = 0,
                score = 1.0,
                parentId = null
            )

            val allNodes = mutableListOf<ThoughtNode>(rootNode)
            val leafNodes = mutableListOf<ThoughtNode>()

            buildTree(rootNode, task.description, allNodes, leafNodes, 0, llm)

            val paths = leafNodes.map { leaf ->
                val pathNodes = reconstructPath(leaf, allNodes)
                val score = evaluateNode(leaf, llm)
                ThoughtPath(nodes = pathNodes, totalScore = score)
            }

            // 修复 T13：如果所有 generateChildThoughts 都失败，paths 可能为空
            if (paths.isEmpty()) {
                val totalTime = System.currentTimeMillis() - startTime
                totalExecutions.incrementAndGet()
                return@runBlocking BurstSkillResult(
                    success = false,
                    errorMessage = "No reasoning paths generated (LLM may be unavailable)",
                    metrics = SkillMetrics(
                        executionTimeMs = totalTime,
                        stepsCompleted = allNodes.size
                    )
                )
            }

            // 修复 T5：explorationPaths 改为局部变量
            val explorationPaths = paths

            val bestPath = selectBestPath(explorationPaths, task.description, llm)
            // 修复 T1（critical）：旧版最终答案只取叶子节点 content，无任何综合。
            // ToT 算法的核心是“沿最优路径回溯并合成答案”。新版调用 synthesizeAnswer
            // 把整条 path 喂给 LLM 综合输出；LLM 不可用时用整条 path 的节点拼接作为兑底。
            val finalAnswer = synthesizeAnswer(bestPath, task.description, llm)

            val totalTime = System.currentTimeMillis() - startTime

            // 修复 T8：旧版无条件 successfulExecutions++，即使 LLM 不可用、答案完全是随机路径
            // 也算“成功”，使 evaluate() successRate 永远 1.0。新版只在 LLM 可用且 bestPath 有
            // 非空 content 时计成功。
            val llmAvailable = llm != null && llm.isAvailable()
            val actualSuccess = llmAvailable && finalAnswer.isNotBlank()

            totalExecutions.incrementAndGet()
            if (actualSuccess) successfulExecutions.incrementAndGet()
            totalExecutionTimeMs.addAndGet(totalTime)

            BurstSkillResult(
                success = actualSuccess,
                output = buildString {
                    appendLine("Tree of Thoughts reasoning complete:")
                    appendLine("- Paths explored: ${explorationPaths.size}")
                    appendLine("- Best path score: ${bestPath.totalScore}")
                    appendLine("- LLM available: $llmAvailable")
                    if (!llmAvailable) {
                        appendLine("- (random fallback; LLM unavailable)")
                    }
                    appendLine("- Final answer: $finalAnswer")
                },
                metrics = SkillMetrics(
                    executionTimeMs = totalTime,
                    stepsCompleted = allNodes.size,
                    tokensProcessed = estimateTokens(finalAnswer)
                )
            )

        } catch (e: Exception) {
            // 修复 X2：吞 CancellationException 会破坏结构化并发
            if (e is kotlinx.coroutines.CancellationException) throw e
            totalExecutions.incrementAndGet()
            BurstSkillResult(
                success = false,
                errorMessage = "Tree of Thoughts error: ${e.message}"
            )
        }
    }

    /**
     * 修复 T1：综合 bestPath 上所有节点的内容，输出最终答案。
     * - LLM 可用时：把整条 path 喂给 LLM，让它合成一个连贯的最终答案
     * - LLM 不可用时：用 path 节点的 content 用箭头拼接作为兑底
     */
    private suspend fun synthesizeAnswer(path: ThoughtPath, taskDescription: String, llm: ILLMService?): String {
        if (path.nodes.isEmpty()) return "No answer generated"

        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("You are concluding a Tree-of-Thoughts reasoning process.")
                appendLine("Task: $taskDescription")
                appendLine()
                appendLine("The best reasoning path is:")
                path.nodes.forEachIndexed { i, node ->
                    appendLine("${i + 1}. ${node.content}")
                }
                appendLine()
                appendLine("Synthesize a coherent final answer based on this reasoning path.")
            }
            return llm.generate(prompt, maxTokens = 512)
        }

        // 兑底：用箭头拼接 path 节点
        return path.nodes.joinToString(" -> ") { it.content }
    }

    /**
     * 修复 T2（major）：旧版 buildTree 为串行递归，depth=5/branching=3 时
     * 363 次串行 LLM 调用，耗时 10-30 分钟。新版同层子节点并行展开。
     */
    private suspend fun buildTree(
        parentNode: ThoughtNode,
        taskDescription: String,
        allNodes: MutableList<ThoughtNode>,
        leafNodes: MutableList<ThoughtNode>,
        currentDepth: Int,
        llm: ILLMService?
    ) {
        if (isPaused || currentDepth >= maxDepth) {
            leafNodes.add(parentNode)
            return
        }

        val childNodes = generateChildThoughts(parentNode, taskDescription, llm)

        // 同层并行展开：每个子节点的递归 buildTree 在独立 async 中启动
        coroutineScope {
            childNodes.forEach { child ->
                // 需同步访问 allNodes / leafNodes，但这里在 coroutineScope 内
                // 并发写入会有竞态。使用 Mutex 保护。
                synchronized(allNodes) { allNodes.add(child) }

                if (currentDepth + 1 >= maxDepth) {
                    synchronized(leafNodes) { leafNodes.add(child) }
                } else {
                    // 递归在 async 中并行
                    launch {
                        buildTree(child, taskDescription, allNodes, leafNodes, currentDepth + 1, llm)
                    }
                }
            }
        }
    }

    private suspend fun generateChildThoughts(node: ThoughtNode, taskDescription: String, llm: ILLMService?): List<ThoughtNode> {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("You are a Tree-of-Thoughts reasoning engine.")
                appendLine("Based on the current thought, generate $branchingFactor alternative next steps.")
                appendLine()
                appendLine("Task: $taskDescription")
                appendLine("Current thought: ${node.content}")
                appendLine()
                appendLine("Generate $branchingFactor distinct reasoning paths.")
            }
            val response = llm.generate(prompt, maxTokens = 512)
            val childContents = response.split("\n").filter { it.length > 10 }.take(branchingFactor)
            return childContents.mapIndexed { idx, content ->
                ThoughtNode(
                    id = "${node.id}.$idx",
                    content = content,
                    depth = node.depth + 1,
                    score = 1.0,
                    parentId = node.id
                )
            }
        }
        return (1..branchingFactor).map { index ->
            ThoughtNode(
                id = "${node.id}_$index",
                content = "Derived thought $index from '${node.content.take(20)}'",
                depth = node.depth + 1,
                score = node.score * (0.7 + Math.random() * 0.3),
                parentId = node.id
            )
        }
    }

    private suspend fun evaluateNode(node: ThoughtNode, llm: ILLMService?): Double {
        if (llm != null && llm.isAvailable()) {
            val prompt = "Rate the following reasoning from 0.0 to 1.0 on quality and relevance:\n${node.content}\n\nOnly output a number between 0 and 1."
            val response = llm.generate(prompt, maxTokens = 16)
            // 修复 T12：原版 response.trim().toIntOrNull() 对 "Path 2" / "2." 等返回 null
            // 改用正则提取首个数字
            val numStr = Regex("\\d+(?:\\.\\d+)?").find(response)?.value
            return numStr?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5
        }
        // 修复 T11：LLM 不可用时原版返回 0.5 + random*0.5，使 selectBestPath 退化为随机选路径
        // 但 output 仍声称 "Best path score: X"，给用户虚假置信。
        // 新版返回周定的 0.5，并在上游 output 中明确标注 "random fallback"。
        return 0.5
    }

    private suspend fun selectBestPath(paths: List<ThoughtPath>, taskDescription: String, llm: ILLMService?): ThoughtPath {
        if (llm != null && llm.isAvailable()) {
            val pathSummaries = paths.mapIndexed { i, p -> "Path $i: ${p.nodes.joinToString(" -> ") { it.content.take(100) }}" }
            val prompt = buildString {
                appendLine("Select the best reasoning path for: $taskDescription")
                appendLine()
                pathSummaries.forEach { appendLine(it) }
                appendLine()
                appendLine("Output the index of the best path (0-${paths.size - 1}).")
            }
            val response = llm.generate(prompt, maxTokens = 16)
            val idx = response.trim().toIntOrNull()
            if (idx != null && idx in paths.indices) return paths[idx]
        }
        return paths.maxByOrNull { it.totalScore } ?: paths.first()
    }

    private fun reconstructPath(leaf: ThoughtNode, allNodes: List<ThoughtNode>): List<ThoughtNode> {
        val path = mutableListOf<ThoughtNode>()
        var current: ThoughtNode? = leaf
        val nodeMap = allNodes.associateBy { it.id }

        while (current != null) {
            path.add(0, current)
            current = current.parentId?.let { nodeMap[it] }
        }

        return path
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it in '\u4e00'..'\u9fff' }
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val otherChars = text.length - chineseChars - englishChars
        return (chineseChars * 1.5 + englishChars * 0.25 + otherChars * 0.5).toInt()
    }

    override fun pause() {
        isPaused = true
    }

    override fun resume() {
        isPaused = false
    }

    override fun destroy() {
        scope.cancel()
    }

    override fun mutate(rate: Float): IBurstSkill {
        return this
    }

    override fun crossover(other: IBurstSkill): IBurstSkill {
        if (other is TreeOfThoughtsSkill) {
            return this
        }
        return this
    }

    override fun evaluate(): Float {
        val total = totalExecutions.get()
        if (total == 0) return 0.5f
        val successRate = successfulExecutions.get().toFloat() / total
        val avgTime = totalExecutionTimeMs.get().toFloat() / total
        val timeEfficiency = (15000f / (avgTime + 1)).coerceIn(0f, 1f)
        return successRate * 0.7f + timeEfficiency * 0.3f
    }

    data class ThoughtNode(
        val id: String,
        val content: String,
        val depth: Int,
        val score: Double,
        val parentId: String?
    )

    data class ThoughtPath(
        val nodes: List<ThoughtNode>,
        val totalScore: Double
    )
}
