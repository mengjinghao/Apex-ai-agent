package com.apex.agent.core.normal.game

import java.util.concurrent.ConcurrentHashMap

/**
 * F33: 对话游戏引擎（Conversation Game Engine）
 *
 * 在对话中玩游戏：
 * - 文字冒险（互动小说）
 * - 猜谜游戏（谜语/成语/知识问答）
 * - 角色扮演（DND 风格）
 * - 二十问
 * - 单词接龙
 * - 故事接龙
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不玩游戏
 * - 狂暴是执行
 * - 本功能让单 Agent **有趣、可玩、可互动**
 */

/**
 * 游戏类型
 */
enum class GameType {
    TEXT_ADVENTURE,   // 文字冒险
    RIDDLE,           // 猜谜
    QUIZ,             // 知识问答
    ROLE_PLAY,        // 角色扮演
    TWENTY_QUESTIONS, // 二十问
    WORD_CHAIN,       // 单词接龙
    STORY_CHAIN,      // 故事接龙
    WOULD_YOU_RATHER, // 二选一
    TRIVIA,           // 冷知识
    IMPROV            // 即兴表演
}

/**
 * 游戏状态
 */
enum class GameState {
    IDLE,             // 未开始
    PLAYING,          // 进行中
    WAITING_INPUT,    // 等待玩家输入
    PAUSED,           // 暂停
    ENDED             // 已结束
}

/**
 * 游戏会话
 */
data class GameSession(
    val id: String,
    val chatId: String,
    val gameType: GameType,
    val state: GameState,
    val currentPlayer: String,
    val players: List<String>,
    val score: Map<String, Int>,
    val round: Int,
    val maxRounds: Int,
    val gameData: Map<String, Any>,
    val history: List<GameMove>,
    val startedAt: Long,
    val lastActivityAt: Long,
    val winner: String? = null
)

/**
 * 游戏动作
 */
data class GameMove(
    val player: String,
    val action: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val result: MoveResult? = null
)

sealed class MoveResult {
    data class Correct(val message: String, val points: Int = 1) : MoveResult()
    data class Incorrect(val message: String, val hint: String? = null) : MoveResult()
    data class Progress(val message: String, val newState: Map<String, Any>) : MoveResult()
    data class Victory(val message: String, val winner: String) : MoveResult()
    data class Defeat(val message: String) : MoveResult()
    data class Continue(val message: String) : MoveResult()
}

/**
 * 游戏定义
 */
data class GameDefinition(
    val type: GameType,
    val name: String,
    val description: String,
    val icon: String,
    val minPlayers: Int,
    val maxPlayers: Int,
    val defaultRounds: Int,
    val rules: String
)

/**
 * 题库
 */
data class Question(
    val id: String,
    val category: String,
    val difficulty: Int,  // 1-5
    val question: String,
    val answer: String,
    val options: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
    val explanation: String? = null
)

/**
 * 游戏引擎
 */
class ConversationGameEngine {

    private val sessions = ConcurrentHashMap<String, GameSession>()
    private val questionBank = mutableListOf<Question>()
    private val gameDefinitions = mutableMapOf<GameType, GameDefinition>()

    init {
        registerBuiltinGames()
        loadBuiltinQuestions()
    }

    /**
     * 开始游戏
     */
    fun startGame(
        chatId: String,
        gameType: GameType,
        players: List<String> = listOf("player1"),
        maxRounds: Int? = null
    ): GameSession {
        val def = gameDefinitions[gameType]!!
        require(players.size in def.minPlayers..def.maxPlayers) {
            "玩家数需在 ${def.minPlayers}-${def.maxPlayers} 之间"
        }

        val sessionId = "game_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val session = GameSession(
            id = sessionId,
            chatId = chatId,
            gameType = gameType,
            state = GameState.PLAYING,
            currentPlayer = players.first(),
            players = players,
            score = players.associateWith { 0 },
            round = 1,
            maxRounds = maxRounds ?: def.defaultRounds,
            gameData = initializeGameData(gameType),
            history = emptyList(),
            startedAt = System.currentTimeMillis(),
            lastActivityAt = System.currentTimeMillis()
        )
        sessions[sessionId] = session
        return session
    }

    /**
     * 处理玩家输入
     */
    fun handleInput(sessionId: String, player: String, input: String): GameMove {
        val session = sessions[sessionId] ?: return GameMove(player, "error", "会话不存在")
        val move = GameMove(player, "input", input)
        val result = when (session.gameType) {
            GameType.RIDDLE -> handleRiddleInput(session, player, input)
            GameType.QUIZ -> handleQuizInput(session, player, input)
            GameType.TWENTY_QUESTIONS -> handleTwentyQuestions(session, player, input)
            GameType.WORD_CHAIN -> handleWordChain(session, player, input)
            GameType.STORY_CHAIN -> handleStoryChain(session, player, input)
            GameType.WOULD_YOU_RATHER -> handleWouldYouRather(session, player, input)
            GameType.TEXT_ADVENTURE -> handleTextAdventure(session, player, input)
            GameType.ROLE_PLAY -> handleRolePlay(session, player, input)
            GameType.TRIVIA -> handleTriviaInput(session, player, input)
            GameType.IMPROV -> handleImprov(session, player, input)
        }

        val updatedMove = move.copy(result = result)
        val updatedHistory = session.history + updatedMove

        // 更新分数
        val updatedScore = if (result is MoveResult.Correct) {
            session.score.toMutableMap().apply {
                this[player] = (this[player] ?: 0) + result.points
            }.toMap()
        } else session.score

        // 检查游戏结束
        val newRound = if (result is MoveResult.Correct || result is MoveResult.Victory) {
            session.round + 1
        } else session.round

        val newState = when {
            result is MoveResult.Victory -> GameState.ENDED
            result is MoveResult.Defeat -> GameState.ENDED
            newRound > session.maxRounds -> GameState.ENDED
            else -> GameState.WAITING_INPUT
        }

        // 切换玩家
        val nextPlayer = if (players > 1) {
            val idx = session.players.indexOf(player)
            session.players[(idx + 1) % session.players.size]
        } else player

        sessions[sessionId] = session.copy(
            state = newState,
            currentPlayer = nextPlayer,
            score = updatedScore,
            round = newRound,
            history = updatedHistory,
            lastActivityAt = System.currentTimeMillis(),
            winner = if (result is MoveResult.Victory) result.winner else null
        )

        return updatedMove
    }

    /**
     * 获取当前题目
     */
    fun getCurrentPrompt(sessionId: String): String? {
        val session = sessions[sessionId] ?: return null
        if (session.state == GameState.ENDED) return "游戏已结束"

        return when (session.gameType) {
            GameType.RIDDLE -> {
                val q = session.gameData["currentQuestion"] as? Question ?: return null
                "谜语（第${session.round}轮）: ${q.question}\n提示: 使用 /hint 获取提示"
            }
            GameType.QUIZ -> {
                val q = session.gameData["currentQuestion"] as? Question ?: return null
                buildString {
                    appendLine("知识问答（第${session.round}/${session.maxRounds}轮）:")
                    appendLine(q.question)
                    if (q.options.isNotEmpty()) {
                        q.options.forEachIndexed { i, opt -> appendLine("${'A' + i}. $opt") }
                    }
                }
            }
            GameType.TWENTY_QUESTIONS -> {
                val remaining = (20 - session.history.count { it.player != "system" })
                "二十问（剩余 $remaining 次）: 请提问是/否问题，猜出我在想什么"
            }
            GameType.WORD_CHAIN -> {
                val lastWord = session.gameData["lastWord"] as? String ?: "开始"
                "单词接龙: 上一个词是「$lastWord」，请用最后一个字开头接龙"
            }
            GameType.STORY_CHAIN -> {
                val lastSentence = session.gameData["lastSentence"] as? String ?: "从前有座山"
                "故事接龙: 上一句是「$lastSentence」，请续写下一句"
            }
            GameType.WOULD_YOU_RATHER -> {
                val options = session.gameData["options"] as? List<*> ?: listOf("选项A", "选项B")
                "二选一: 你选 ${options[0]} 还是 ${options[1]}？"
            }
            GameType.TEXT_ADVENTURE -> {
                val scene = session.gameData["currentScene"] as? String ?: "你站在十字路口"
                "文字冒险: $scene\n（输入你的行动）"
            }
            GameType.ROLE_PLAY -> "角色扮演: 请描述你的行动"
            GameType.TRIVIA -> {
                val q = session.gameData["currentQuestion"] as? Question ?: return null
                "冷知识（第${session.round}轮）: ${q.question}"
            }
            GameType.IMPROV -> "即兴表演: 请即兴接话"
        }
    }

    /**
     * 获取提示
     */
    fun getHint(sessionId: String): String? {
        val session = sessions[sessionId] ?: return null
        val q = session.gameData["currentQuestion"] as? Question ?: return "暂无提示"
        return q.hints.getOrNull(session.round - 1) ?: q.hints.firstOrNull() ?: "无更多提示"
    }

    /**
     * 获取游戏结果
     */
    fun getResults(sessionId: String): String? {
        val session = sessions[sessionId] ?: return null
        if (session.state != GameState.ENDED) return "游戏尚未结束"
        return buildString {
            appendLine("═══ 游戏结果 ═══")
            appendLine("游戏: ${session.gameType}")
            appendLine("轮次: ${session.round - 1}/${session.maxRounds}")
            appendLine("分数:")
            session.score.forEach { (player, score) ->
                val isWinner = player == session.winner || score == session.score.values.maxOrNull()
                appendLine("  ${if (isWinner) "🏆" else "  "} $player: $score 分")
            }
            appendLine("═══════════════")
        }
    }

    /**
     * 列出可用游戏
     */
    fun listGames(): List<GameDefinition> = gameDefinitions.values.toList()

    /**
     * 获取活跃会话
     */
    fun getActiveSession(chatId: String): GameSession? =
        sessions.values.find { it.chatId == chatId && it.state != GameState.ENDED }

    /**
     * 结束游戏
     */
    fun endGame(sessionId: String) {
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = session.copy(state = GameState.ENDED)
        }
    }

    // ============ 游戏处理器 ============

    private fun handleRiddleInput(session: GameSession, player: String, input: String): MoveResult {
        val q = session.gameData["currentQuestion"] as? Question ?: return MoveResult.Continue("无题目")
        return if (input.trim().equals(q.answer, ignoreCase = true)) {
            MoveResult.Correct("答对了！答案就是「${q.answer}」", 10)
        } else {
            MoveResult.Incorrect("不对哦，再想想", q.hints.firstOrNull())
        }
    }

    private fun handleQuizInput(session: GameSession, player: String, input: String): MoveResult {
        val q = session.gameData["currentQuestion"] as? Question ?: return MoveResult.Continue("无题目")
        val answerIndex = when {
            input.matches(Regex("[A-Da-d]")) -> input.uppercase().first() - 'A'
            input.matches(Regex("[1-4]")) -> input.toInt() - 1
            else -> -1
        }
        return if (answerIndex >= 0 && answerIndex < q.options.size && q.options[answerIndex] == q.answer) {
            MoveResult.Correct("正确！答案是 ${q.answer}", 10)
        } else {
            MoveResult.Incorrect("错误，正确答案是 ${q.answer}")
        }
    }

    private fun handleTwentyQuestions(session: GameSession, player: String, input: String): MoveResult {
        val target = session.gameData["target"] as? String ?: "苹果"
        return when {
            input.contains(target, ignoreCase = true) || input.equals(target, ignoreCase = true) -> {
                MoveResult.Victory("猜对了！答案就是「$target」", player)
            }
            input.contains("?") || input.contains("？") -> {
                // 是/否问题
                val answer = if (target.length > 3) "不，不是" else "是的"
                MoveResult.Continue(answer)
            }
            else -> MoveResult.Continue("请提问是/否问题")
        }
    }

    private fun handleWordChain(session: GameSession, player: String, input: String): MoveResult {
        val lastWord = session.gameData["lastWord"] as? String ?: ""
        val lastChar = lastWord.lastOrNull()?.lowercaseChar()
        val firstChar = input.firstOrNull()?.lowercaseChar()
        return if (lastChar == null || firstChar == lastChar) {
            MoveResult.Progress("好！「$input」接上了", mapOf("lastWord" to input))
        } else {
            MoveResult.Incorrect("接不上，「$lastWord」的最后一个字是「$lastChar」")
        }
    }

    private fun handleStoryChain(session: GameSession, player: String, input: String): MoveResult {
        return MoveResult.Progress("好的，故事继续: $input", mapOf("lastSentence" to input))
    }

    private fun handleWouldYouRather(session: GameSession, player: String, input: String): MoveResult {
        val options = session.gameData["options"] as? List<*> ?: listOf("A", "B")
        return when {
            input.contains("1") || input.contains("a", true) || input.contains(options.getOrNull(0) ?: "") -> {
                MoveResult.Continue("你选了 ${options[0]}，有意思的选择！")
            }
            input.contains("2") || input.contains("b", true) || input.contains(options.getOrNull(1) ?: "") -> {
                MoveResult.Continue("你选了 ${options[1]}，可以理解！")
            }
            else -> MoveResult.Incorrect("请选择 1 或 2")
        }
    }

    private fun handleTextAdventure(session: GameSession, player: String, input: String): MoveResult {
        // 简化的文字冒险
        val scenes = mapOf(
            "十字路口" to "你选择往${input}走，前方出现了...",
            "森林" to "你进入了森林，听到奇怪的声音...",
            "城堡" to "你来到一座古堡前，门虚掩着..."
        )
        val currentScene = session.gameData["currentScene"] as? String ?: "十字路口"
        val newScene = when {
            input.contains("左") || input.contains("west") -> "森林"
            input.contains("右") || input.contains("east") -> "城堡"
            input.contains("前") || input.contains("north") -> "森林"
            else -> currentScene
        }
        return MoveResult.Progress(scenes[newScene] ?: "你继续前行...", mapOf("currentScene" to newScene))
    }

    private fun handleRolePlay(session: GameSession, player: String, input: String): MoveResult {
        return MoveResult.Continue("(DM) $input 的行动成功了，接下来...")
    }

    private fun handleTriviaInput(session: GameSession, player: String, input: String): MoveResult {
        val q = session.gameData["currentQuestion"] as? Question ?: return MoveResult.Continue("无题目")
        return if (input.trim().equals(q.answer, ignoreCase = true)) {
            MoveResult.Correct("正确！${q.explanation ?: ""}", 5)
        } else {
            MoveResult.Incorrect("答案是「${q.answer}」")
        }
    }

    private fun handleImprov(session: GameSession, player: String, input: String): MoveResult {
        return MoveResult.Continue("（接戏）针对「$input」，我回应...")
    }

    // ============ 初始化 ============

    private fun initializeGameData(type: GameType): Map<String, Any> {
        return when (type) {
            GameType.RIDDLE -> mapOf("currentQuestion" to questionBank.filter { it.category == "riddle" }.randomOrNull() ?: Question("1", "riddle", 1, "?", "?"))
            GameType.QUIZ -> mapOf("currentQuestion" to questionBank.filter { it.category == "quiz" }.randomOrNull() ?: Question("1", "quiz", 1, "?", "?"))
            GameType.TRIVIA -> mapOf("currentQuestion" to questionBank.filter { it.category == "trivia" }.randomOrNull() ?: Question("1", "trivia", 1, "?", "?"))
            GameType.TWENTY_QUESTIONS -> mapOf("target" to listOf("苹果", "猫", "手机", "太阳", "书").random())
            GameType.WORD_CHAIN -> mapOf("lastWord" to "开始")
            GameType.STORY_CHAIN -> mapOf("lastSentence" to "从前有座山")
            GameType.WOULD_YOU_RATHER -> mapOf("options" to generateWouldYouRather())
            GameType.TEXT_ADVENTURE -> mapOf("currentScene" to "十字路口")
            GameType.ROLE_PLAY -> mapOf("character" to "冒险者")
            GameType.IMPROV -> mapOf("topic" to "即兴")
        }
    }

    private fun generateWouldYouRather(): List<String> {
        val options = listOf(
            listOf("永远不能说谎", "永远只能沉默"),
            listOf("拥有读心术", "拥有预知未来"),
            listOf("回到过去", "穿越未来"),
            listOf("无限财富", "无限时间"),
            listOf("会飞", "会隐身")
        )
        return options.random()
    }

    private fun registerBuiltinGames() {
        gameDefinitions[GameType.RIDDLE] = GameDefinition(GameType.RIDDLE, "猜谜", "传统谜语游戏", "🧩", 1, 4, 5, "答对得分，答错可要提示")
        gameDefinitions[GameType.QUIZ] = GameDefinition(GameType.QUIZ, "知识问答", "多选题问答", "📚", 1, 4, 10, "选 ABCD 或 1234")
        gameDefinitions[GameType.TWENTY_QUESTIONS] = GameDefinition(GameType.TWENTY_QUESTIONS, "二十问", "猜物游戏", "❓", 1, 4, 20, "只能问是/否问题")
        gameDefinitions[GameType.WORD_CHAIN] = GameDefinition(GameType.WORD_CHAIN, "单词接龙", "文字接龙", "🔗", 1, 4, 20, "用上一词最后一字开头")
        gameDefinitions[GameType.STORY_CHAIN] = GameDefinition(GameType.STORY_CHAIN, "故事接龙", "合作编故事", "📖", 2, 6, 10, "每人续写一句")
        gameDefinitions[GameType.WOULD_YOU_RATHER] = GameDefinition(GameType.WOULD_YOU_RATHER, "二选一", "艰难选择", "⚖️", 1, 1, 10, "选择并解释")
        gameDefinitions[GameType.TEXT_ADVENTURE] = GameDefinition(GameType.TEXT_ADVENTURE, "文字冒险", "互动小说", "🗺️", 1, 1, 20, "描述你的行动")
        gameDefinitions[GameType.ROLE_PLAY] = GameDefinition(GameType.ROLE_PLAY, "角色扮演", "DND 风格", "⚔️", 1, 4, 20, "扮演角色冒险")
        gameDefinitions[GameType.TRIVIA] = GameDefinition(GameType.TRIVIA, "冷知识", "趣味知识", "💡", 1, 4, 10, "答对得分")
        gameDefinitions[GameType.IMPROV] = GameDefinition(GameType.IMPROV, "即兴表演", "即兴接话", "🎭", 2, 4, 10, "即兴接话")
    }

    private fun loadBuiltinQuestions() {
        // 谜语
        questionBank.addAll(listOf(
            Question("r1", "riddle", 1, "千条线，万条线，落到水里看不见", "雨", hints = listOf("和天气有关", "从天上掉下来")),
            Question("r2", "riddle", 1, "麻屋子，红帐子，里面住着白胖子", "花生", hints = listOf("是一种食物", "有壳")),
            Question("r3", "riddle", 2, "有时圆，有时弯，白天看不见，晚上亮闪闪", "月亮", hints = listOf("在天上", "和夜晚有关")),
            Question("r4", "riddle", 2, "身穿绿衣裳，肚里水汪汪，生的子儿多，个个黑脸膛", "西瓜", hints = listOf("水果", "夏天常见")),
            Question("r5", "riddle", 3, "一物生得真奇怪，腰里长着一口袋，孩子袋里吃奶奶，奶奶带着孩子来", "袋鼠", hints = listOf("动物", "澳洲"))
        ))
        // 知识问答
        questionBank.addAll(listOf(
            Question("q1", "quiz", 1, "地球上最大的海洋是？", "太平洋", options = listOf("大西洋", "太平洋", "印度洋", "北冰洋")),
            Question("q2", "quiz", 2, "Python 语言的创始人是？", "Guido van Rossum", options = listOf("James Gosling", "Guido van Rossum", "Bjarne Stroustrup", "Brendan Eich")),
            Question("q3", "quiz", 1, "一年的平方根大约是？", "19", options = listOf("18", "19", "20", "21")),
            Question("q4", "quiz", 3, "相对论的提出者是？", "爱因斯坦", options = listOf("牛顿", "爱因斯坦", "霍金", "伽利略"))
        ))
        // 冷知识
        questionBank.addAll(listOf(
            Question("t1", "trivia", 1, "蜗牛有多少颗牙齿？", "25600", explanation = "蜗牛有约 25600 颗微小牙齿"),
            Question("t2", "trivia", 2, "章鱼有几个心脏？", "3", explanation = "章鱼有 3 个心脏"),
            Question("t3", "trivia", 1, "蜂鸟不能做什么？", "走路", explanation = "蜂鸟腿太短不能走路"),
            Question("t4", "trivia", 3, "宇宙中最常见的元素是？", "氢", explanation = "氢占宇宙可见物质约 75%")
        ))
    }
}
