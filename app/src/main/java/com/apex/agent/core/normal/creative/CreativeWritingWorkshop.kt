package com.apex.agent.core.normal.creative

import java.util.concurrent.ConcurrentHashMap

/**
 * F34: 创意写作工坊（Creative Writing Workshop）
 *
 * 多体裁创意写作辅助：
 * - 诗歌（古体/现代/俳句）
 * - 小说（短篇/微小说/连载）
 * - 剧本（对话/独白）
 * - 散文（随笔/杂文）
 * - 文案（广告/标语）
 * - 故事接龙
 * - 灵感激发器
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不专注创作
 * - 狂暴是执行
 * - 本功能让单 Agent 成为**创意伙伴**
 */

/**
 * 写作体裁
 */
enum class WritingGenre {
    ANCIENT_POEM,    // 古体诗
    MODERN_POEM,     // 现代诗
    HAIKU,           // 俳句
    SHORT_STORY,     // 短篇小说
    MICRO_FICTION,   // 微小说
    SERIAL,          // 连载
    SCRIPT,          // 剧本
    MONOLOGUE,       // 独白
    ESSAY,           // 散文
    AD_COPY,         // 广告文案
    SLOGAN,          // 标语
    LETTER,          // 书信
    DIARY,           // 日记
    REVIEW,          // 评论
    FANFIC          // 同人
}

/**
 * 写作风格
 */
data class WritingStyle(
    val tone: WritingTone,
    val mood: WritingMood,
    val perspective: Perspective,
    val tense: Tense,
    val languageStyle: LanguageStyle,
    val targetLength: Int,
    val targetAudience: String = "general"
)

enum class WritingTone { SERIOUS, HUMOROUS, SATIRICAL, LYRICAL, EPIC, INTIMATE }
enum class WritingMood { JOYFUL, MELANCHOLY, TENSE, PEACEFUL, MYSTERIOUS, ROMANTIC, DARK }
enum class Perspective { FIRST_PERSON, SECOND_PERSON, THIRD_PERSON_LIMITED, THIRD_PERSON_OMNISCIENT }
enum class Tense { PAST, PRESENT, FUTURE }
enum class LanguageStyle { CLASSICAL, LITERARY, COLLOQUIAL, MINIMALIST, ORNATE }

/**
 * 写作项目
 */
data class WritingProject(
    val id: String,
    val title: String,
    val genre: WritingGenre,
    val style: WritingStyle,
    val premise: String,            // 核心设定/主题
    val characters: List<Character>,
    val outline: List<OutlineNode>,
    val drafts: List<Draft>,
    val currentDraft: Draft? = null,
    val wordCount: Int = 0,
    val status: ProjectStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ProjectStatus { BRAINSTORMING, OUTLINING, DRAFTING, REVISING, COMPLETED, ABANDONED }

data class Character(
    val id: String,
    val name: String,
    val role: String,              // 主角/配角/反派
    val description: String,
    val personality: String,
    val motivation: String,
    val relationships: Map<String, String> = emptyMap()  // 其他角色ID -> 关系
)

data class OutlineNode(
    val id: String,
    val title: String,
    val description: String,
    val order: Int,
    val children: List<OutlineNode> = emptyList()
)

data class Draft(
    val id: String,
    val version: Int,
    val content: String,
    val wordCount: Int,
    val feedback: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 灵感卡片
 */
data class InspirationCard(
    val id: String,
    val type: InspirationType,
    val content: String,
    val prompt: String
)

enum class InspirationType {
    OPENING_LINE,    // 开头句
    PLOT_TWIST,      // 剧情反转
    CHARACTER_TRAIT, // 角色特征
    SETTING,         // 场景
    CONFLICT,        // 冲突
    THEME,           // 主题
    TITLE,           // 标题
    ENDING,          // 结尾
    METAPHOR         // 比喻
}

/**
 * 创意写作工坊
 */
class CreativeWritingWorkshop {

    private val projects = ConcurrentHashMap<String, WritingProject>()
    private val inspirationBank = mutableListOf<InspirationCard>()

    init {
        loadBuiltinInspirations()
    }

    /**
     * 创建写作项目
     */
    fun createProject(
        title: String,
        genre: WritingGenre,
        premise: String,
        style: WritingStyle = defaultStyle(genre)
    ): WritingProject {
        val project = WritingProject(
            id = "proj_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            title = title,
            genre = genre,
            style = style,
            premise = premise,
            characters = emptyList(),
            outline = emptyList(),
            drafts = emptyList(),
            status = ProjectStatus.BRAINSTORMING
        )
        projects[project.id] = project
        return project
    }

    /**
     * 添加角色
     */
    fun addCharacter(projectId: String, character: Character): WritingProject? {
        val project = projects[projectId] ?: return null
        val updated = project.copy(
            characters = project.characters + character,
            updatedAt = System.currentTimeMillis()
        )
        projects[projectId] = updated
        return updated
    }

    /**
     * 设置大纲
     */
    fun setOutline(projectId: String, outline: List<OutlineNode>): WritingProject? {
        val project = projects[projectId] ?: return null
        val updated = project.copy(
            outline = outline,
            status = ProjectStatus.OUTLINING,
            updatedAt = System.currentTimeMillis()
        )
        projects[projectId] = updated
        return updated
    }

    /**
     * 保存草稿
     */
    fun saveDraft(projectId: String, content: String): WritingProject? {
        val project = projects[projectId] ?: return null
        val version = project.drafts.size + 1
        val draft = Draft(
            id = "draft_${System.currentTimeMillis()}",
            version = version,
            content = content,
            wordCount = countWords(content)
        )
        val updated = project.copy(
            drafts = project.drafts + draft,
            currentDraft = draft,
            wordCount = draft.wordCount,
            status = ProjectStatus.DRAFTING,
            updatedAt = System.currentTimeMillis()
        )
        projects[projectId] = updated
        return updated
    }

    /**
     * 生成写作 prompt
     */
    fun generateWritingPrompt(projectId: String): String? {
        val project = projects[projectId] ?: return null
        val sb = StringBuilder()

        sb.appendLine("[创意写作工坊]")
        sb.appendLine("项目: ${project.title}")
        sb.appendLine("体裁: ${genreName(project.genre)}")
        sb.appendLine("主题: ${project.premise}")
        sb.appendLine()

        // 风格指导
        sb.appendLine("风格指导:")
        sb.appendLine("- 语气: ${project.style.tone}")
        sb.appendLine("- 情调: ${project.style.mood}")
        sb.appendLine("- 视角: ${project.style.perspective}")
        sb.appendLine("- 时态: ${project.style.tense}")
        sb.appendLine("- 语言风格: ${project.style.languageStyle}")
        sb.appendLine("- 目标字数: ${project.style.targetLength}")
        sb.appendLine()

        // 体裁要求
        sb.appendLine("体裁要求:")
        sb.appendLine(genreRequirements(project.genre))
        sb.appendLine()

        // 角色
        if (project.characters.isNotEmpty()) {
            sb.appendLine("角色:")
            project.characters.forEach { c ->
                sb.appendLine("- ${c.name} (${c.role}): ${c.description}")
                sb.appendLine("  性格: ${c.personality}")
                sb.appendLine("  动机: ${c.motivation}")
            }
            sb.appendLine()
        }

        // 大纲
        if (project.outline.isNotEmpty()) {
            sb.appendLine("大纲:")
            project.outline.sortedBy { it.order }.forEach { node ->
                sb.appendLine("${node.order}. ${node.title}: ${node.description}")
            }
            sb.appendLine()
        }

        // 当前草稿（如有）
        project.currentDraft?.let { draft ->
            sb.appendLine("当前草稿 (v${draft.version}, ${draft.wordCount}字):")
            sb.appendLine(draft.content.take(500) + if (draft.content.length > 500) "..." else "")
        }

        return sb.toString()
    }

    /**
     * 获取灵感
     */
    fun getInspiration(type: InspirationType? = null, count: Int = 3): List<InspirationCard> {
        val pool = if (type != null) inspirationBank.filter { it.type == type } else inspirationBank
        return pool.shuffled().take(count)
    }

    /**
     * 生成故事开头建议
     */
    fun generateOpeningSuggestions(theme: String, count: Int = 5): List<String> {
        val templates = listOf(
            "那是一个${moodWord()}的早晨，$theme 从未像此刻这般清晰。",
            "「你确定要这么做吗？」$theme 的疑问打破了沉默。",
            "关于 $theme，我得从头说起——那已经是十年前的事了。",
            "$theme 的尽头，是一扇从未打开过的门。",
            "如果那天我没有回头，$theme 就不会发生。",
            "传闻说，$theme 始于一封没有署名的信。",
            "$theme 发生时，我正站在命运的十字路口。",
            "所有人都说 $theme 不可能，除了她。"
        )
        return templates.shuffled().take(count)
    }

    /**
     * 生成剧情反转建议
     */
    fun generatePlotTwists(context: String, count: Int = 5): List<String> {
        val twists = listOf(
            "看似反派的角色其实是被陷害的，真正的幕后黑手是主角最信任的人",
            "主角苦寻的目标其实一直就在自己身边，只是从未发现",
            "回忆中的"事件"其实是被植入的虚假记忆",
            "时间线是循环的，主角其实已经经历过这一切无数次",
            "看似无关的支线其实是主线的关键",
            "主角的死敌其实是另一个时空的自己",
            "拯救世界的代价是失去最爱的人",
            "看似胜利的结局其实只是更大阴谋的开始"
        )
        return twists.shuffled().take(count)
    }

    /**
     * 生成角色名字
     */
    fun generateCharacterNames(style: NameStyle, count: Int = 5): List<String> {
        return when (style) {
            NameStyle.CHINESE_CLASSIC -> listOf("云溪", "墨白", "清和", "言书", "知微", "映雪", "怀瑾", "若虚").shuffled().take(count)
            NameStyle.CHINESE_MODERN -> listOf("林深", "苏晚", "陈默", "周安", "顾念", "沈知", "许言", "何遇").shuffled().take(count)
            NameStyle.ENGLISH -> listOf("Elena", "Marcus", "Vivian", "Theodore", "Cassandra", "Julian", "Seraphina", "Atticus").shuffled().take(count)
            NameStyle.FANTASY -> listOf("Aelindra", "Theron", "Lyra", "Darian", "Sylphie", "Kael", "Nymeria", "Eldrin").shuffled().take(count)
        }
    }

    enum class NameStyle { CHINESE_CLASSIC, CHINESE_MODERN, ENGLISH, FANTASY }

    /**
     * 评估写作
     */
    fun evaluateWriting(content: String): WritingEvaluation {
        val wordCount = countWords(content)
        val sentenceCount = content.split(Regex("[。.！!？?]+")).filter { it.isNotBlank() }.size
        val avgSentenceLength = if (sentenceCount > 0) wordCount / sentenceCount else 0

        // 检查文学手法
        val metaphors = Regex("(像|如同|仿佛|宛如|好似).+").findAll(content).count()
        val dialogues = content.count { it == '"' || it == '"' || it == '「' }
        val descriptions = Regex("(色|香|味|声|光|影)").findAll(content).count()

        return WritingEvaluation(
            wordCount = wordCount,
            sentenceCount = sentenceCount,
            avgSentenceLength = avgSentenceLength,
            metaphorCount = metaphors,
            dialogueCount = dialogues,
            descriptionCount = descriptions,
            readabilityScore = computeReadability(avgSentenceLength),
            suggestions = generateWritingSuggestions(content, wordCount, metaphors, dialogues)
        )
    }

    data class WritingEvaluation(
        val wordCount: Int,
        val sentenceCount: Int,
        val avgSentenceLength: Int,
        val metaphorCount: Int,
        val dialogueCount: Int,
        val descriptionCount: Int,
        val readabilityScore: Int,
        val suggestions: List<String>
    )

    // ============ 内部方法 ============

    private fun defaultStyle(genre: WritingGenre): WritingStyle {
        return when (genre) {
            WritingGenre.ANCIENT_POEM -> WritingStyle(WritingTone.LYRICAL, WritingMood.PEACEFUL, Perspective.FIRST_PERSON, Tense.PAST, LanguageStyle.CLASSICAL, 56)
            WritingGenre.MODERN_POEM -> WritingStyle(WritingTone.LYRICAL, WritingMood.MELANCHOLY, Perspective.FIRST_PERSON, Tense.PRESENT, LanguageStyle.LITERARY, 100)
            WritingGenre.HAIKU -> WritingStyle(WritingTone.LYRICAL, WritingMood.PEACEFUL, Perspective.FIRST_PERSON, Tense.PRESENT, LanguageStyle.MINIMALIST, 17)
            WritingGenre.SHORT_STORY -> WritingStyle(WritingTone.SERIOUS, WritingMood.TENSE, Perspective.THIRD_PERSON_LIMITED, Tense.PAST, LanguageStyle.LITERARY, 3000)
            WritingGenre.MICRO_FICTION -> WritingStyle(WritingTone.SERIOUS, WritingMood.MYSTERIOUS, Perspective.FIRST_PERSON, Tense.PAST, LanguageStyle.MINIMALIST, 300)
            WritingGenre.SERIAL -> WritingStyle(WritingTone.EPIC, WritingMood.TENSE, Perspective.THIRD_PERSON_OMNISCIENT, Tense.PAST, LanguageStyle.LITERARY, 10000)
            WritingGenre.SCRIPT -> WritingStyle(WritingTone.SERIOUS, WritingMood.TENSE, Perspective.THIRD_PERSON_LIMITED, Tense.PRESENT, LanguageStyle.COLLOQUIAL, 5000)
            WritingGenre.MONOLOGUE -> WritingStyle(WritingTone.INTIMATE, WritingMood.MELANCHOLY, Perspective.FIRST_PERSON, Tense.PRESENT, LanguageStyle.COLLOQUIAL, 800)
            WritingGenre.ESSAY -> WritingStyle(WritingTone.LYRICAL, WritingMood.PEACEFUL, Perspective.FIRST_PERSON, Tense.PRESENT, LanguageStyle.LITERARY, 1500)
            WritingGenre.AD_COPY -> WritingStyle(WritingTone.HUMOROUS, WritingMood.JOYFUL, Perspective.SECOND_PERSON, Tense.PRESENT, LanguageStyle.COLLOQUIAL, 100)
            WritingGenre.SLOGAN -> WritingStyle(WritingTone.HUMOROUS, WritingMood.JOYFUL, Perspective.SECOND_PERSON, Tense.PRESENT, LanguageStyle.MINIMALIST, 20)
            WritingGenre.LETTER -> WritingStyle(WritingTone.INTIMATE, WritingMood.PEACEFUL, Perspective.FIRST_PERSON, Tense.PAST, LanguageStyle.LITERARY, 500)
            WritingGenre.DIARY -> WritingStyle(WritingTone.INTIMATE, WritingMood.MELANCHOLY, Perspective.FIRST_PERSON, Tense.PAST, LanguageStyle.COLLOQUIAL, 300)
            WritingGenre.REVIEW -> WritingStyle(WritingTone.SERIOUS, WritingMood.PEACEFUL, Perspective.FIRST_PERSON, Tense.PRESENT, LanguageStyle.LITERARY, 1000)
            WritingGenre.FANFIC -> WritingStyle(WritingTone.LYRICAL, WritingMood.ROMANTIC, Perspective.THIRD_PERSON_LIMITED, Tense.PAST, LanguageStyle.LITERARY, 5000)
        }
    }

    private fun genreName(genre: WritingGenre): String = when (genre) {
        WritingGenre.ANCIENT_POEM -> "古体诗"
        WritingGenre.MODERN_POEM -> "现代诗"
        WritingGenre.HAIKU -> "俳句"
        WritingGenre.SHORT_STORY -> "短篇小说"
        WritingGenre.MICRO_FICTION -> "微小说"
        WritingGenre.SERIAL -> "连载小说"
        WritingGenre.SCRIPT -> "剧本"
        WritingGenre.MONOLOGUE -> "独白"
        WritingGenre.ESSAY -> "散文"
        WritingGenre.AD_COPY -> "广告文案"
        WritingGenre.SLOGAN -> "标语"
        WritingGenre.LETTER -> "书信"
        WritingGenre.DIARY -> "日记"
        WritingGenre.REVIEW -> "评论"
        WritingGenre.FANFIC -> "同人"
    }

    private fun genreRequirements(genre: WritingGenre): String = when (genre) {
        WritingGenre.ANCIENT_POEM -> "五言或七言，押韵，意境深远"
        WritingGenre.MODERN_POEM -> "自由体，注重意象与节奏"
        WritingGenre.HAIKU -> "5-7-5 音节，含季节词"
        WritingGenre.SHORT_STORY -> "完整起承转合，单一视角"
        WritingGenre.MICRO_FICTION -> "结尾反转，字数严控"
        WritingGenre.SERIAL -> "每章有悬念钩子"
        WritingGenre.SCRIPT -> "场景描述+对话，时态现在"
        WritingGenre.MONOLOGUE -> "内心独白，情感推进"
        WritingGenre.ESSAY -> "形散神不散，有核心意象"
        WritingGenre.AD_COPY -> "简洁有力，有记忆点"
        WritingGenre.SLOGAN -> "一句话，朗朗上口"
        WritingGenre.LETTER -> "称呼+正文+落款，真诚"
        WritingGenre.DIARY -> "日期+天气，内心记录"
        WritingGenre.REVIEW -> "观点+论据+结论"
        WritingGenre.FANFIC -> "尊重原作设定"
    }

    private fun moodWord(): String = listOf("宁静", "喧闹", "阴郁", "明媚", "神秘", "温暖").random()

    private fun countWords(text: String): Int {
        val chinese = text.count { it.code in 0x4e00..0x9fff }
        val english = text.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotEmpty() && it.all { c -> c.code !in 0x4e00..0x9fff } }
            .size
        return chinese + english
    }

    private fun computeReadability(avgLen: Int): Int {
        return when {
            avgLen < 10 -> 95
            avgLen < 20 -> 85
            avgLen < 30 -> 70
            avgLen < 40 -> 55
            else -> 40
        }
    }

    private fun generateWritingSuggestions(content: String, words: Int, metaphors: Int, dialogues: Int): List<String> {
        val suggestions = mutableListOf<String>()
        if (words < 100) suggestions.add("内容较短，可适当展开")
        if (metaphors == 0) suggestions.add("缺少比喻，可增加文学性")
        if (dialogues == 0 && words > 300) suggestions.add("缺少对话，可增加生动性")
        if (content.count { it == '\n' } < 3 && words > 200) suggestions.add("段落较少，可适当分段")
        if (content.contains(Regex("非常|十分|特别")) && content.count { Regex("非常|十分|特别").containsMatchIn(it.toString()) } > 3) {
            suggestions.add("程度副词较多，可替换为具体描写")
        }
        return suggestions
    }

    private fun loadBuiltinInspirations() {
        inspirationBank.addAll(listOf(
            InspirationCard("i1", InspirationType.OPENING_LINE, "「时间是一列没有返程的火车」", "用这个开头写一段"),
            InspirationCard("i2", InspirationType.OPENING_LINE, "「她数到三，世界就变了」", "用这个开头写一段"),
            InspirationCard("i3", InspirationType.PLOT_TWIST, "主角发现自己在重复同一天", "围绕这个反转构思"),
            InspirationCard("i4", InspirationType.PLOT_TWIST, "看似平凡的物品是通往异世界的钥匙", "围绕这个反转构思"),
            InspirationCard("i5", InspirationType.CHARACTER_TRAIT, "永远说真话但没人相信的人", "塑造这个角色"),
            InspirationCard("i6", InspirationType.CHARACTER_TRAIT, "能听见别人内心独白的人", "塑造这个角色"),
            InspirationCard("i7", InspirationType.SETTING, "一座永远在下雨的城市", "描绘这个场景"),
            InspirationCard("i8", InspirationType.SETTING, "时间走得比现实慢十倍的图书馆", "描绘这个场景"),
            InspirationCard("i9", InspirationType.CONFLICT, "使命与良知的抉择", "展开这个冲突"),
            InspirationCard("i10", InspirationType.THEME, "孤独与连接", "围绕这个主题创作"),
            InspirationCard("i11", InspirationType.TITLE, "《最后一位守夜人》", "为这个标题构思故事"),
            InspirationCard("i12", InspirationType.ENDING, "主角终于笑了，却是苦笑", "用这个结尾收束"),
            InspirationCard("i13", InspirationType.METAPHOR, "记忆是会过期的罐头", "用这个比喻写一段")
        ))
    }
}
