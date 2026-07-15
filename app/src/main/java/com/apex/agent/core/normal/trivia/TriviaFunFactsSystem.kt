package com.apex.agent.core.normal.trivia

import java.util.concurrent.ConcurrentHashMap

/**
 * F37: 冷知识百科与趣味问答（Trivia & Fun Facts）
 *
 * 提供趣味冷知识、每日一题、知识竞赛：
 * - 冷知识库（动物/科学/历史/地理/文化等）
 * - 每日一题
 * - 趣味问答
 * - 知识挑战
 * - "你知道吗"随机推送
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不提供趣味知识
 * - 狂暴是执行
 * - 本功能让单 Agent **有趣、涨知识**
 */

/**
 * 冷知识
 */
data class FunFact(
    val id: String,
    val category: FactCategory,
    val fact: String,
    val explanation: String? = null,
    val source: String? = null,
    val surprising: Int = 3  // 惊讶度 1-5
)

enum class FactCategory {
    ANIMAL,        // 动物
        SCIENCE,       // 科学
        HISTORY,       // 历史
        GEOGRAPHY,     // 地理
        CULTURE,       // 文化
        FOOD,          // 食物
        HUMAN_BODY,    // 人体
        SPACE,         // 太空
        TECHNOLOGY,    // 技术
        LANGUAGE,      // 语言
        SPORTS,        // 体育
        ART,           // 艺术
        NATURE,        // 自然
        WEIRD          // 奇葩
}

/**
 * 趣味问答
 */
data class TriviaQuestion(
    val id: String,
    val category: FactCategory,
    val difficulty: Int,  // 1-5
    val question: String,
    val answer: String,
    val options: List<String> = emptyList(),
    val funFact: String? = null  // 答题后揭晓的冷知识
)

/**
 * 冷知识系统
 */
class TriviaFunFactsSystem {

    private val facts = mutableListOf<FunFact>()
        private val questions = mutableListOf<TriviaQuestion>()
        private val userStats = ConcurrentHashMap<String, UserTriviaStats>()
        private val dailyFact = ConcurrentHashMap<String, FunFact>()  // 日期 -> 每日冷知识
    init {
        loadBuiltinFacts()
        loadBuiltinQuestions()
    }

    /**
     * 获取随机冷知识
     */
    fun getRandomFact(category: FactCategory? = null): FunFact {
        val pool = if (category != null) facts.filter { it.category == category } else facts
        return pool.randomOrNull() ?: facts.first()
    }

    /**
     * 获取每日冷知识
     */
    fun getDailyFact(): FunFact {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
        return dailyFact.getOrPut(today) { facts.random() }
    }

    /**
     * 搜索冷知识
     */
    fun searchFacts(query: String): List<FunFact> {
        val q = query.lowercase()
        return facts.filter { it.fact.contains(q, true) || it.explanation?.contains(q, true) == true }
    }

    /**
     * 获取随机问答题
     */
    fun getRandomQuestion(category: FactCategory? = null, difficulty: Int? = null): TriviaQuestion {
        val pool = questions.filter { q ->
            (category == null || q.category == category) &&
            (difficulty == null || q.difficulty == difficulty)
        }
        return pool.randomOrNull() ?: questions.first()
    }

    /**
     * 检查答案
     */
    fun checkAnswer(userId: String, questionId: String, answer: String): AnswerResult {
        val q = questions.find { it.id == questionId } ?: return AnswerResult(false, "题目不存在")
        val correct = answer.trim().equals(q.answer, ignoreCase = true) ||
            q.options.indexOf(answer.trim()).let { it >= 0 && q.options[it] == q.answer }

        // 更新统计
    val stats = userStats.computeIfAbsent(userId) { UserTriviaStats(userId) }
        val updated = stats.copy(
            totalAnswered = stats.totalAnswered + 1,
            correctAnswers = if (correct) stats.correctAnswers + 1 else stats.correctAnswers,
            byCategory = stats.byCategory.toMutableMap().apply {
                val cat = q.category.name
                this[cat] = (this[cat] ?: 0) + if (correct) 1 else 0
            }.toMap()
        )
        userStats[userId] = updated

        return AnswerResult(
            correct = correct,
            feedback = if (correct) "答对了！" else "答案是「${q.answer}」",
            funFact = q.funFact
        )
    }

    /**
     * 生成"你知道吗"prompt
     */
    fun generateFunFactPrompt(): String {
        val fact = getRandomFact()
        return buildString {
            append("[你知道吗] ")
        append(fact.fact)
        fact.explanation?.let { append("\n$it") }
        }
    }

    /**
     * 获取用户统计
     */
    fun getUserStats(userId: String): UserTriviaStats = userStats[userId] ?: UserTriviaStats(userId)
        data class UserTriviaStats(
        val userId: String,
        val totalAnswered: Int = 0,
        val correctAnswers: Int = 0,
        val byCategory: Map<String, Int> = emptyMap()
    ) {
        val accuracy: Float get() = if (totalAnswered > 0) correctAnswers.toFloat() / totalAnswered else 0f
    }
        data class AnswerResult(
        val correct: Boolean,
        val feedback: String,
        val funFact: String? = null
    )
        private fun loadBuiltinFacts() {
        facts.addAll(listOf(
            // 动物
        FunFact("f1", FactCategory.ANIMAL, "章鱼有 3 个心脏", "两个鳃心一个体心，游泳时体心会停跳"),
            FunFact("f2", FactCategory.ANIMAL, "蜗牛有约 25600 颗牙齿", "牙齿长在舌头上，称为齿舌"),
            FunFact("f3", FactCategory.ANIMAL, "蜂鸟不能走路", "腿太短，只能飞或栖息"),
            FunFact("f4", FactCategory.ANIMAL, "海獭睡觉时会手牵手", "防止漂散"),
            FunFact("f5", FactCategory.ANIMAL, "猫每天睡 16-20 小时"),
            FunFact("f6", FactCategory.ANIMAL, "袋熊的便便是方形的"),
            // 科学
        FunFact("f7", FactCategory.SCIENCE, "宇宙中最常见的元素是氢", "占可见物质约 75%"),
            FunFact("f8", FactCategory.SCIENCE, "光从太阳到地球需要约 8 分钟"),
            FunFact("f9", FactCategory.SCIENCE, "DNA 链拉直约 2 米长"),
            FunFact("f10", FactCategory.SCIENCE, "一茶匙中子星物质重约 60 亿吨"),
            // 历史
        FunFact("f11", FactCategory.HISTORY, "牛津大学比阿兹特克帝国还古老", "牛津 1096 年开始教学，阿兹特克 1428 年建国"),
            FunFact("f12", FactCategory.HISTORY, "埃及艳后生活的时代离 iPhone 比离金字塔更近"),
            // 地理
        FunFact("f13", FactCategory.GEOGRAPHY, "俄罗斯横跨 11 个时区"),
            FunFact("f14", FactCategory.GEOGRAPHY, "撒哈拉沙漠曾经是热带雨林"),
            FunFact("f15", FactCategory.GEOGRAPHY, "加拿大湖泊比世界其他地方加起来还多"),
            // 食物
        FunFact("f16", FactCategory.FOOD, "蜂蜜永远不会过期", "考古发现 3000 年前的蜂蜜仍可食用"),
            FunFact("f17", FactCategory.FOOD, "草莓不是浆果，但香蕉是"),
            FunFact("f18", FactCategory.FOOD, "番茄在 19 世纪被美国最高法院判定为蔬菜"),
            // 人体
        FunFact("f19", FactCategory.HUMAN_BODY, "人一生产生约 2.5 万升唾液"),
            FunFact("f20", FactCategory.HUMAN_BODY, "你的胃黏膜每 3-4 天更新一次"),
            FunFact("f21", FactCategory.HUMAN_BODY, "打喷嚏时所有身体机能都会停止，包括心跳（瞬间）"),
            // 太空
        FunFact("f22", FactCategory.SPACE, "金星上一天比一年长", "金星自转 243 地球日，公转 225 地球日"),
            FunFact("f23", FactCategory.SPACE, "太空是绝对寂静的", "没有空气传播声音"),
            FunFact("f24", FactCategory.SPACE, "月球每年远离地球约 3.8 厘米"),
            // 技术
        FunFact("f25", FactCategory.TECHNOLOGY, "第一个网页至今仍在线", "1991 年由 Tim Berners-Lee 创建"),
            FunFact("f26", FactCategory.TECHNOLOGY, "Python 是以 Monty Python 命名的，不是蛇"),
            // 语言
        FunFact("f27", FactCategory.LANGUAGE, "中文是最多人使用的母语", "约 12 亿人"),
            FunFact("f28", FactCategory.LANGUAGE, "冰岛语保留了大量古诺尔斯语，现代冰岛人能读懂千年前的萨迦"),
            // 奇葩
        FunFact("f29", FactCategory.WEIRD, "云的平均重量约 500 吨", "相当于 100 头大象"),
            FunFact("f30", FactCategory.WEIRD, "你出生时约有 300 块骨头，成年后只有 206 块", "因为骨头会融合")
        ))
    }
        private fun loadBuiltinQuestions() {
        questions.addAll(listOf(
            TriviaQuestion("q1", FactCategory.ANIMAL, 1, "章鱼有几个心脏？", "3", listOf("1", "2", "3", "4"), "两个鳃心一个体心"),
            TriviaQuestion("q2", FactCategory.SCIENCE, 2, "光从太阳到地球需要多久？", "8分钟", listOf("8秒", "8分钟", "8小时", "8天")),
            TriviaQuestion("q3", FactCategory.GEOGRAPHY, 1, "地球上最大的海洋是？", "太平洋", listOf("大西洋", "太平洋", "印度洋", "北冰洋")),
            TriviaQuestion("q4", FactCategory.HISTORY, 3, "埃及艳后生活的时代离什么更近？", "iPhone", listOf("金字塔", "iPhone", "罗马帝国", "希腊文明")),
            TriviaQuestion("q5", FactCategory.FOOD, 2, "以下哪个是真正的浆果？", "香蕉", listOf("草莓", "香蕉", "樱桃", "桃子")),
            TriviaQuestion("q6", FactCategory.SPACE, 3, "金星上哪个更长？", "一天", listOf("一天", "一年", "一样长", "都不存在")),
            TriviaQuestion("q7", FactCategory.LANGUAGE, 2, "Python 语言以什么命名？", "Monty Python", listOf("蟒蛇", "Monty Python", "创始人宠物", "希腊神话")),
            TriviaQuestion("q8", FactCategory.HUMAN_BODY, 1, "成年人有多少块骨头？", "206", listOf("201", "206", "211", "216")),
            TriviaQuestion("q9", FactCategory.TECHNOLOGY, 4, "第一个网页创建于哪一年？", "1991"),
            TriviaQuestion("q10", FactCategory.WEIRD, 3, "云的平均重量约等于？", "100头大象", listOf("1头大象", "10头大象", "100头大象", "1000头大象"))
        ))
    }
}
