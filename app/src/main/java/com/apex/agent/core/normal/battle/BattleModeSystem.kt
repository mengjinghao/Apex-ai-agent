package com.apex.agent.core.normal.battle

import java.util.concurrent.ConcurrentHashMap

/**
 * F44: 对话评分与对战模式（Battle Mode）
 *
 * AI 与 AI 对话对战，用户评分：
 * - 两个 AI 角色就同一话题发表观点
 * - 用户评判谁更胜一筹
 * - 积分排行榜
 * - 复盘分析
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 是协作
 * - 狂暴是执行
 * - 本功能是**用户主持的 AI 辩论赛**
 */

enum class BattleMode {
    DEBATE,          // 辩论
        RAP_BATTLE,      // 说唱对战
        POETRY_DUEL,     // 诗词对决
        STORYTELLING,    // 故事接龙对决
        EXPLAIN_OFF,     // 解释大比拼
        PERSUASION       // 说服力对决
}

data class BattleContestant(
    val id: String,
    val name: String,
    val avatar: String,
    val persona: String,            // 性格设定
    val style: String,              // 说话风格
    val catchphrase: String
)

data class BattleRound(
    val roundNum: Int,
    val topic: String,
    val contestantA: BattleContestant,
    val contestantB: BattleContestant,
    val responseA: String,
    val responseB: String,
    val winner: BattleSide? = null,
    val userFeedback: String? = null
)

enum class BattleSide { A, B, TIE }

data class BattleSession(
    val id: String,
    val mode: BattleMode,
    val topic: String,
    val contestantA: BattleContestant,
    val contestantB: BattleContestant,
    val rounds: List<BattleRound>,
    val currentRound: Int,
    val maxRounds: Int,
    val scoreA: Int,
    val scoreB: Int,
    val status: BattleStatus
)

enum class BattleStatus { ONGOING, FINISHED, ABANDONED }

data class BattleResult(
    val session: BattleSession,
    val winner: BattleContestant?,
    val finalScore: Pair<Int, Int>,
    val analysis: String,
    val highlights: List<String>
)

class BattleModeSystem {

    private val sessions = ConcurrentHashMap<String, BattleSession>()
        private val contestants = mutableListOf<BattleContestant>()
        private val leaderboard = ConcurrentHashMap<String, Int>()  // contestantId -> wins
    init {
        registerBuiltinContestants()
    }
        fun startBattle(
        mode: BattleMode,
        topic: String,
        contestantAId: String? = null,
        contestantBId: String? = null
    ): BattleSession {
        val a = if (contestantAId != null) contestants.find { it.id == contestantAId }!! else contestants.random()
        val b = if (contestantBId != null) contestants.find { it.id == contestantBId }!! else contestants.filter { it.id != a.id }.random()
        val session = BattleSession(
            id = "battle_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            mode = mode, topic = topic,
            contestantA = a, contestantB = b,
            rounds = emptyList(), currentRound = 0, maxRounds = 3,
            scoreA = 0, scoreB = 0, status = BattleStatus.ONGOING
        )
        sessions[session.id] = session
        return session
    }
        fun generateRound(sessionId: String): BattleRound? {
        val session = sessions[sessionId] ?: return null
        if (session.currentRound >= session.maxRounds) return null

        val roundNum = session.currentRound + 1
        val (respA, respB) = generateResponses(session, roundNum)
        val round = BattleRound(
            roundNum = roundNum,
            topic = session.topic,
            contestantA = session.contestantA,
            contestantB = session.contestantB,
            responseA = respA,
            responseB = respB
        )
        val updated = session.copy(
            rounds = session.rounds + round,
            currentRound = roundNum
        )
        sessions[sessionId] = updated
        return round
    }
        fun judgeRound(sessionId: String, roundNum: Int, winner: BattleSide, feedback: String? = null): BattleSession? {
        val session = sessions[sessionId] ?: return null
        val rounds = session.rounds.map { r ->
            if (r.roundNum == roundNum) r.copy(winner = winner, userFeedback = feedback) else r
        }
        val newScoreA = session.scoreA + if (winner == BattleSide.A) 1 else 0
        val newScoreB = session.scoreB + if (winner == BattleSide.B) 1 else 0
        val newStatus = if (session.currentRound >= session.maxRounds) BattleStatus.FINISHED else BattleStatus.ONGOING
        val updated = session.copy(rounds = rounds, scoreA = newScoreA, scoreB = newScoreB, status = newStatus)
        sessions[sessionId] = updated

        // 更新排行榜
    if (newStatus == BattleStatus.FINISHED) {
            val winnerId = when {
                newScoreA > newScoreB -> session.contestantA.id
                newScoreB > newScoreA -> session.contestantB.id
                else -> null
            }
        winnerId?.let { leaderboard[it] = (leaderboard[it] ?: 0) + 1 }
        }
        return updated
    }
        fun getResults(sessionId: String): BattleResult? {
        val session = sessions[sessionId] ?: return null
        val winner = when {
            session.scoreA > session.scoreB -> session.contestantA
            session.scoreB > session.scoreA -> session.contestantB
            else -> null
        }
        val highlights = session.rounds.mapNotNull { r ->
            when (r.winner) {
                BattleSide.A -> "第${r.roundNum}轮: ${session.contestantA.name} 胜"
        BattleSide.B -> "第${r.roundNum}轮: ${session.contestantB.name} 胜"
        BattleSide.TIE -> "第${r.roundNum}轮: 平局"
        null -> null
            }
        }
        val analysis = buildString {
            appendLine("对战分析:")
        appendLine("模式: ${session.mode}")
        appendLine("话题: ${session.topic}")
        appendLine("总比分: ${session.contestantA.name} ${session.scoreA} : ${session.scoreB} ${session.contestantB.name}")
        if (winner != null) appendLine("胜者: ${winner.name}")
        else appendLine("结果: 平局")
        }
        return BattleResult(session, winner, session.scoreA to session.scoreB, analysis, highlights)
    }
        fun getLeaderboard(): List<Pair<BattleContestant, Int>> {
        return contestants.map { it to (leaderboard[it.id] ?: 0) }
            .sortedByDescending { it.second }
    }
        fun listContestants(): List<BattleContestant> = contestants.toList()
        private fun generateResponses(session: BattleSession, round: Int): Pair<String, String> {
        val templates = when (session.mode) {
            BattleMode.DEBATE -> listOf("关于${session.topic}，我认为...", "让我从另一个角度看...")
        BattleMode.RAP_BATTLE -> listOf("Yo yo yo, 说起${session.topic}...", "听我说，${session.topic}这事儿...")
        BattleMode.POETRY_DUEL -> listOf("${session.topic}如诗如画...", "且看${session.topic}另一番...")
        BattleMode.STORYTELLING -> listOf("${session.topic}的故事，要从一个夜晚说起...", "另一版本里，${session.topic}...")
        BattleMode.EXPLAIN_OFF -> listOf("简单来说，${session.topic}就是...", "${session.topic}的本质是...")
        BattleMode.PERSUASION -> listOf("你应该相信，${session.topic}...", "为什么不试试${session.topic}呢？")
        }
        return templates[0].replace("...", "（${session.contestantA.style}风格，第${round}轮）") to
               templates[1].replace("...", "（${session.contestantB.style}风格，第${round}轮）")
    }
        private fun registerBuiltinContestants() {
        contestants.addAll(listOf(
            BattleContestant("c1", "学者", "🎓", "严谨理性", "学术正式", "从学术角度来看"),
            BattleContestant("c2", "段子手", "🤣", "幽默风趣", "网络流行", "笑死，这题我会"),
            BattleContestant("c3", "诗人", "🌹", "感性浪漫", "文学典雅", "如诗中所言"),
            BattleContestant("c4", "极客", "🤓", "技术狂热", "极客俚语", "TL;DR"),
            BattleContestant("c5", "哲学家", "🧠", "深沉思辨", "哲学思辨", "但本质上"),
            BattleContestant("c6", "侦探", "🔍", "冷静缜密", "推理严谨", "注意到一个细节"),
            BattleContestant("c7", "管家", "🤵", "周到有礼", "优雅得体", "为您效劳"),
            BattleContestant("c8", "教练", "💪", "积极激励", "行动导向", "你可以的")
        ))
    }
}
