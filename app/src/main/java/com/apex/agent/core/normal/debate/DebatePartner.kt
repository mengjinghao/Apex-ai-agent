package com.apex.agent.core.normal.debate

import java.util.concurrent.ConcurrentHashMap

/**
 * F41: 辩论练习伙伴（Debate Partner）
 *
 * 与 AI 进行辩论练习：
 * - 多种辩论模式（正反方/魔鬼代言人/苏格拉底式）
 * - 辩题库
 * - 辩论评分
 * - 论点分析
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 是 Agent 间协作
 * - 狂暴是执行
 * - 本功能是**用户与单 Agent 的思维对抗**
 */

enum class DebateMode {
    PRO_CON,            // 正反方辩论
    DEVILS_ADVOCATE,    // 魔鬼代言人（AI 反对用户）
    SOCRATIC,           // 苏格拉底式（追问）
    OXFORD_STYLE,       // 牛津式
    CROSS_EXAMINATION   // 交叉质询
}

enum class DebateSide { PROPOSITION, OPPOSITION, NEUTRAL }
enum class DebatePhase { OPENING, ARGUMENT, REBUTTAL, CROSS_EXAM, CLOSING, FINISHED }

data class DebateTopic(
    val id: String,
    val title: String,
    val description: String,
    val propositionSide: String,    // 正方立场
    val oppositionSide: String,     // 反方立场
    val category: String,
    val difficulty: Int
)

data class Argument(
    val id: String,
    val side: DebateSide,
    val claim: String,              // 主张
    val evidence: List<String>,     // 证据
    val reasoning: String,          // 推理
    val weaknesses: List<String> = emptyList()
)

data class DebateSession(
    val id: String,
    val topic: DebateTopic,
    val mode: DebateMode,
    val userSide: DebateSide,
    val aiSide: DebateSide,
    val phase: DebatePhase,
    val arguments: List<Argument>,
    val currentTurn: DebateSide,
    val round: Int,
    val maxRounds: Int,
    val score: DebateScore? = null,
    val startedAt: Long = System.currentTimeMillis()
)

data class DebateScore(
    val userScore: Int,
    val aiScore: Int,
    val criteria: Map<String, Pair<Int, Int>>,  // 维度 -> (用户分, AI分)
    val feedback: String,
    val winner: DebateSide?
)

class DebatePartner {

    private val sessions = ConcurrentHashMap<String, DebateSession>()
    private val topics = mutableListOf<DebateTopic>()

    init {
        loadBuiltinTopics()
    }

    fun startDebate(topicId: String, mode: DebateMode, userSide: DebateSide): DebateSession? {
        val topic = topics.find { it.id == topicId } ?: return null
        val aiSide = if (userSide == DebateSide.PROPOSITION) DebateSide.OPPOSITION else DebateSide.PROPOSITION
        val session = DebateSession(
            id = "debate_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            topic = topic, mode = mode, userSide = userSide, aiSide = aiSide,
            phase = DebatePhase.OPENING, arguments = emptyList(),
            currentTurn = userSide, round = 1, maxRounds = when (mode) {
                DebateMode.PRO_CON -> 4
                DebateMode.DEVILS_ADVOCATE -> 3
                DebateMode.SOCRATIC -> 5
                DebateMode.OXFORD_STYLE -> 6
                DebateMode.CROSS_EXAMINATION -> 4
            }
        )
        sessions[session.id] = session
        return session
    }

    fun submitArgument(sessionId: String, claim: String, evidence: List<String> = emptyList(), reasoning: String = ""): DebateSession? {
        val session = sessions[sessionId] ?: return null
        val arg = Argument(
            id = "arg_${session.arguments.size}",
            side = session.userSide,
            claim = claim, evidence = evidence, reasoning = reasoning
        )
        val updatedArgs = session.arguments + arg
        val newPhase = advancePhase(session.phase, session.round, session.maxRounds)
        val newRound = if (newPhase != session.phase && newPhase == DebatePhase.ARGUMENT) session.round + 1 else session.round
        val updated = session.copy(
            arguments = updatedArgs,
            phase = newPhase,
            currentTurn = session.aiSide,
            round = newRound
        )
        sessions[sessionId] = updated
        return updated
    }

    fun generateAIArgument(sessionId: String): Argument? {
        val session = sessions[sessionId] ?: return null
        val topic = session.topic
        val aiSide = session.aiSide
        val stance = if (aiSide == DebateSide.PROPOSITION) topic.propositionSide else topic.oppositionSide

        val templates = when (session.mode) {
            DebateMode.SOCRATIC -> listOf(
                "你提到$stance，但这是否意味着...？",
                "如果我们接受你的前提，那么...又如何解释？",
                "你认为...，但这是否考虑了...的情况？"
            )
            else -> listOf(
                "我方认为$stance，理由如下：...",
                "对方的论点存在一个漏洞：...",
                "从另一个角度看，..."
            )
        }

        val arg = Argument(
            id = "arg_${session.arguments.size}",
            side = aiSide,
            claim = templates.random(),
            evidence = listOf("逻辑推理", "反例"),
            reasoning = "基于对方论点的分析"
        )
        val updated = session.copy(
            arguments = session.arguments + arg,
            currentTurn = session.userSide
        )
        sessions[sessionId] = updated
        return arg
    }

    fun evaluateDebate(sessionId: String): DebateScore? {
        val session = sessions[sessionId] ?: return null
        val userArgs = session.arguments.filter { it.side == session.userSide }
        val aiArgs = session.arguments.filter { it.side == session.aiSide }

        val criteria = mapOf(
            "逻辑性" to (scoreLogic(userArgs) to scoreLogic(aiArgs)),
            "证据充分" to (scoreEvidence(userArgs) to scoreEvidence(aiArgs)),
            "说服力" to (scorePersuasion(userArgs) to scorePersuasion(aiArgs)),
            "反驳有效" to (scoreRebuttal(userArgs, aiArgs) to scoreRebuttal(aiArgs, userArgs))
        )

        val userTotal = criteria.values.sumOf { it.first }
        val aiTotal = criteria.values.sumOf { it.second }
        val winner = when {
            userTotal > aiTotal -> session.userSide
            aiTotal > userTotal -> session.aiSide
            else -> null
        }

        val feedback = buildString {
            appendLine("辩论评价:")
            criteria.forEach { (dim, scores) ->
                appendLine("  $dim: 你 ${scores.first} vs AI ${scores.second}")
            }
            appendLine()
            appendLine("总评: ${if (winner == session.userSide) "你赢了！" else if (winner == session.aiSide) "AI 获胜" else "平局"}")
        }

        val score = DebateScore(userTotal, aiTotal, criteria, feedback, winner)
        sessions[sessionId] = session.copy(phase = DebatePhase.FINISHED, score = score)
        return score
    }

    fun listTopics(category: String? = null): List<DebateTopic> {
        return if (category != null) topics.filter { it.category == category } else topics
    }

    fun addTopic(topic: DebateTopic) { topics.add(topic) }

    private fun advancePhase(phase: DebatePhase, round: Int, maxRounds: Int): DebatePhase {
        return when (phase) {
            DebatePhase.OPENING -> DebatePhase.ARGUMENT
            DebatePhase.ARGUMENT -> if (round >= maxRounds / 2) DebatePhase.REBUTTAL else DebatePhase.ARGUMENT
            DebatePhase.REBUTTAL -> DebatePhase.CLOSING
            DebatePhase.CLOSING -> DebatePhase.FINISHED
            DebatePhase.CROSS_EXAM -> DebatePhase.CLOSING
            DebatePhase.FINISHED -> DebatePhase.FINISHED
        }
    }

    private fun scoreLogic(args: List<Argument>): Int = args.size * 20 + args.count { it.reasoning.isNotBlank() } * 10
    private fun scoreEvidence(args: List<Argument>): Int = args.sumOf { it.evidence.size * 10 }
    private fun scorePersuasion(args: List<Argument>): Int = args.size * 15 + 30
    private fun scoreRebuttal(myArgs: List<Argument>, oppArgs: List<Argument>): Int {
        val rebuttalCount = myArgs.count { it.claim.contains("漏洞", "反驳", "但是", "然而") }
        return rebuttalCount * 25
    }

    private fun loadBuiltinTopics() {
        topics.addAll(listOf(
            DebateTopic("t1", "AI 是否会取代人类工作", "讨论人工智能对就业的影响", "AI 将创造更多新工作", "AI 将导致大规模失业", "科技", 3),
            DebateTopic("t2", "是否应该禁止短视频", "讨论短视频的利弊", "应该禁止（成瘾、浪费时间）", "不应禁止（娱乐自由、信息传播）", "社会", 2),
            DebateTopic("t3", "安乐死是否应该合法化", "讨论安乐死的伦理", "应该合法化（个人选择权）", "不应合法化（生命神圣）", "伦理", 5),
            DebateTopic("t4", "远程办公是否应成为常态", "讨论工作方式", "应成为常态（效率、自由）", "不应（沟通成本、孤独）", "职场", 2),
            DebateTopic("t5", "是否应该探索火星", "讨论太空探索", "应该（人类未来、科学进步）", "不应（资源浪费、地球优先）", "科学", 3),
            DebateTopic("t6", "游戏是否是艺术", "讨论游戏的艺术性", "是艺术（叙事、美学、情感）", "不是艺术（互动性、商业性）", "文化", 4),
            DebateTopic("t7", "是否应该实行四天工作制", "讨论工作制度", "应该（效率、生活质量）", "不应（产出、竞争力）", "社会", 3),
            DebateTopic("t8", "隐私与安全哪个更重要", "讨论隐私权", "隐私更重要（个人权利）", "安全更重要（公共利益）", "伦理", 4)
        ))
    }
}
