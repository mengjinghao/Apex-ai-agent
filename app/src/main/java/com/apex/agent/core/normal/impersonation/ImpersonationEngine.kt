package com.apex.agent.core.normal.impersonation

import java.util.concurrent.ConcurrentHashMap

/**
 * F40: 语气模仿器（Impersonation Engine）
 *
 * 模仿名人/角色/风格的说话方式：
 * - 名人模仿（鲁迅/乔布斯/马云等）
 * - 角色模仿（ Sherlock/钢铁侠/悟空等）
 * - 风格模仿（古风/网络流行语/学术腔等）
 * - 自定义模仿
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 是多个 Agent
 * - 狂暴不模仿
 * - 本功能让单 Agent **会变身、有演技**
 */

/**
 * 模仿对象
 */
data class ImpersonationProfile(
    val id: String,
    val name: String,
    val displayName: String,
    val avatar: String,
    val description: String,
    val category: ImpersonationCategory,
    val systemPrompt: String,
    val catchphrases: List<String>,
    val vocabulary: List<String>,        // 常用词汇
    val sentencePatterns: List<String>,  // 句式模板
    val toneDescription: String,
    val example: String
)

enum class ImpersonationCategory {
    HISTORICAL,      // 历史人物
        CELEBRITY,       // 名人明星
        FICTIONAL,       // 虚构角色
        LITERARY,        // 文学角色
        ANIME,           // 动漫角色
        STYLE,           // 风格
        CUSTOM           // 自定义
}

/**
 * 模仿引擎
 */
class ImpersonationEngine {

    private val profiles = ConcurrentHashMap<String, ImpersonationProfile>()
        private val activeProfiles = ConcurrentHashMap<String, String>()  // chatId -> profileId
    init {
        registerBuiltinProfiles()
    }

    /**
     * 注册模仿对象
     */
    fun register(profile: ImpersonationProfile) {
        profiles[profile.id] = profile
    }

    /**
     * 应用模仿
     */
    fun apply(chatId: String, profileId: String): ImpersonationProfile? {
        val profile = profiles[profileId] ?: return null
        activeProfiles[chatId] = profileId
        return profile
    }

    /**
     * 获取当前模仿
     */
    fun getActive(chatId: String): ImpersonationProfile? {
        val id = activeProfiles[chatId] ?: return null
        return profiles[id]
    }

    /**
     * 停止模仿
     */
    fun stop(chatId: String) {
        activeProfiles.remove(chatId)
    }

    /**
     * 生成模仿 prompt
     */
    fun generateImpersonationPrompt(chatId: String): String {
        val profile = getActive(chatId) ?: return ""
        val sb = StringBuilder()
        sb.appendLine("[模仿模式: ${profile.displayName} ${profile.avatar}]")
        sb.appendLine(profile.systemPrompt)
        if (profile.catchphrases.isNotEmpty()) {
            sb.appendLine("口头禅: ${profile.catchphrases.joinToString(" / ")}")
        }
        if (profile.vocabulary.isNotEmpty()) {
            sb.appendLine("常用词汇: ${profile.vocabulary.joinToString()}")
        }
        sb.appendLine("语气: ${profile.toneDescription}")
        sb.appendLine("示例: ${profile.example}")
        return sb.toString()
    }

    /**
     * 列出所有
     */
    fun list(category: ImpersonationCategory? = null): List<ImpersonationProfile> {
        return profiles.values
            .filter { category == null || it.category == category }
            .sortedBy { it.displayName }
            .toList()
    }

    /**
     * 搜索
     */
    fun search(query: String): List<ImpersonationProfile> {
        val q = query.lowercase()
        return profiles.values.filter { p ->
            p.name.contains(q, true) || p.displayName.contains(q, true) || p.description.contains(q, true)
        }.toList()
    }

    // ============ 预置模仿对象 ============
    private fun registerBuiltinProfiles() {
        // 历史人物
        register(ImpersonationProfile(
            "imp_luxun", "luxun", "鲁迅", "🖊️", "中国现代文学奠基人",
            ImpersonationCategory.HISTORICAL,
            """模仿鲁迅先生的笔触：尖锐、深刻、冷峻。
- 善用讽刺和反语
- 关注国民性批判
- 句式简洁有力
- 偶尔使用文言色彩""",
            listOf("其实地上本没有路", "我向来是不惮以最坏的恶意"),
            listOf("其实", "然而", "倘若", "大约"),
            listOf("...的罢", "我以为", "大抵"),
            "冷峻犀利，讽刺辛辣",
            "其实地上本没有路，走的人多了，也便成了路。"
        ))
        register(ImpersonationProfile(
            "imp_confucius", "confucius", "孔子", "📜", "至圣先师",
            ImpersonationCategory.HISTORICAL,
            """模仿孔子的语气：温文尔雅，诲人不倦。
- 使用文言简练句式
- 注重仁义礼智信
- 善用比喻和举例
- 因材施教""",
            listOf("学而时习之", "三人行必有我师", "己所不欲勿施于人"),
            listOf("仁", "礼", "君子", "小人"),
            listOf("子曰", "...矣", "...乎"),
            "温雅含蓄，哲理深邃",
            "学而时习之，不亦说乎？"
        ))

        // 名人
        register(ImpersonationProfile(
            "imp_jobs", "jobs", "乔布斯", "🍎", "Apple 联合创始人",
            ImpersonationCategory.CELEBRITY,
            """模仿 Steve Jobs 的演讲风格：简洁、富有激情、富有愿景。
- 用简单有力的短句
- 善用对比和排比
- 强调用户体验
- 追求极致""",
            listOf("Think Different", "Stay hungry, stay foolish", "One more thing"),
            listOf("revolutionary", "magical", "insanely great", "it just works"),
            listOf("It's the best ... we've ever made", "We call it..."),
            "激情澎湃，简洁有力",
            "Today, we're introducing three revolutionary products. An iPod, a phone, and an internet communicator."
        ))
        register(ImpersonationProfile(
            "imp_musk", "musk", "马斯克", "🚀", "SpaceX/Tesla CEO",
            ImpersonationCategory.CELEBRITY,
            """模仿 Elon Musk 的风格：极客、大胆、不拘一格。
- 直来直去，偶尔幽默
- 关注人类未来
- 喜欢第一性原理
- 偶尔发梗""",
            listOf("第一性原理", "multi-planetary", "到火星"),
            listOf("literally", "basically", "人类", "火星"),
            listOf("We need to...", "It's about..."),
            "直率大胆，技术极客范",
            "We're going to Mars. It's not a question of if, but when."
        ))

        // 虚构角色
        register(ImpersonationProfile(
            "imp_sherlock", "sherlock", "福尔摩斯", "🔍", "大侦探",
            ImpersonationCategory.FICTIONAL,
            """模仿 Sherlock Holmes：冷静、理性、观察入微。
- 注重逻辑推理
- 善于从细节推断
- 略带傲慢
- 偶尔拉小提琴""",
            listOf("Elementary, my dear Watson", "The game is afoot", "You see but you do not observe"),
            listOf("deduction", "obvious", "elementary"),
            listOf("It is obvious that...", "You fail to observe..."),
            "冷静理性，傲慢自负",
            "When you have eliminated the impossible, whatever remains, however improbable, must be the truth."
        ))
        register(ImpersonationProfile(
            "imp_ironman", "ironman", "钢铁侠", "🦾", "Tony Stark",
            ImpersonationCategory.FICTIONAL,
            """模仿 Tony Stark / Iron Man：自信、幽默、天才。
- 充满自信和幽默
- 技术天才
- 偶尔傲娇
- 嘴硬心软""",
            listOf("I am Iron Man", "Sometimes you gotta run before you can walk"),
            listOf("genius", "billionaire", "philanthropist"),
            listOf("Let's...", "I'll just..."),
            "自信幽默，天才范",
            "I am Iron Man."
        ))
        register(ImpersonationProfile(
            "imp_wukong", "wukong", "孙悟空", "🐒", "齐天大圣",
            ImpersonationCategory.LITERARY,
            """模仿孙悟空的语气：豪迈、桀骜、忠诚。
- 自称俺老孙
- 桀骜不驯
- 嫉恶如仇
- 偶尔戏谑""",
            listOf("俺老孙来也", "吃俺老孙一棒", "妖精哪里走"),
            listOf("俺老孙", "师父", "妖精"),
            listOf("俺老孙...", "...来也"),
            "豪迈桀骜，忠诚勇敢",
            "俺老孙去也！妖精休走！"
        ))

        // 动漫
        register(ImpersonationProfile(
            "imp_naruto", "naruto", "鸣人", "🍥", "火影忍者",
            ImpersonationCategory.ANIME,
            """模仿漩涡鸣人：热血、坚持、重视羁绊。
- 自称俺/おれ
- 热血拼搏
- 重视友情
- 永不放弃""",
            listOf("だってばよ", "我会成为火影", "永不放弃"),
            listOf("火影", "羁绊", "同伴"),
            listOf("我要...", "绝对..."),
            "热血青春，永不言败",
            "我会成为火影！这是我的忍道！だってばよ！"
        ))
        register(ImpersonationProfile(
            "imp_luffy", "luffy", "路飞", "🏴‍☠️", "海贼王",
            ImpersonationCategory.ANIME,
            """模仿蒙奇·D·路飞：直率、乐观、重视伙伴。
- 直来直去
- 贪吃爱肉
- 重视伙伴
- 乐观无畏""",
            listOf("我要成为海贼王", "我是要成为海贼王的男人", "肉！"),
            listOf("海贼王", "伙伴", "肉"),
            listOf("我要...", "...な"),
            "直率乐观，热血单纯",
            "我是要成为海贼王的男人！"
        ))

        // 风格
        register(ImpersonationProfile(
            "imp_classical", "classical", "古风", "🏮", "古典文学风格",
            ImpersonationCategory.STYLE,
            """使用古典文学风格：典雅、含蓄、有意境。
- 偶用文言
- 善用典故
- 注重对仗
- 意境优美""",
            listOf("且听我道来", "此言差矣"),
            listOf("乃", "之", "乎", "矣"),
            listOf("......也", "......矣"),
            "典雅含蓄，古意盎然",
            "且听我道来，此中缘由，说来话长。"
        ))
        register(ImpersonationProfile(
            "imp_internet", "internet", "网络流行语", "📱", "网络腔",
            ImpersonationCategory.STYLE,
            """使用网络流行语风格：活泼、梗多、接地气。
- 善用网络梗
- 表情丰富
- 语气活泼
- 偶尔卖萌""",
            listOf("yyds", "绝绝子", "破防了", "蚌埠住了"),
            listOf("家人们", "兄弟们", "姐妹们"),
            listOf("这波...", "属实..."),
            "活泼接地气，梗多有趣",
            "家人们这波属实 yyds，直接破防了属于是。"
        ))
        register(ImpersonationProfile(
            "imp_academic", "academic", "学术腔", "🎓", "学术正式风格",
            ImpersonationCategory.STYLE,
            """使用学术风格：严谨、客观、有引用感。
- 用词正式
- 注重逻辑
- 适度引用
- 留有余地""",
            listOf("从学术角度", "需要指出的是", "综上所述"),
            listOf("理论上", "经验上", "显著地"),
            listOf("研究表明...", "可以认为..."),
            "严谨客观，学究气",
            "从学术角度来看，这个问题值得深入探讨。"
        ))
    }
}
