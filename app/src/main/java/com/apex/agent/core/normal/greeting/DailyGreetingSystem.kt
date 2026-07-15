package com.apex.agent.core.normal.greeting

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * F36: 每日一言与问候系统（Daily Greeting）
 *
 * 根据时间/天气/心情/节日提供个性化问候：
 * - 时段问候（早/中/晚/深夜）
 * - 每日一言（名言/诗词/鸡汤/段子）
 * - 天气问候（占位，需接入天气）
 * - 心情问候（基于情感追踪）
 * - 个性化称呼
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不问候用户
 * - 狂暴不关心情感
 * - 本功能让单 Agent **有温度、有人情味**
 */

/**
 * 问候类型
 */
enum class GreetingType {
    TIME_BASED,      // 基于时段
    DAILY_QUOTE,     // 每日一言
    WEATHER_BASED,   // 基于天气
    MOOD_BASED,      // 基于心情
    FESTIVAL_BASED,  // 基于节日
    RANDOM           // 随机问候
}

/**
 * 时段
 */
enum class TimeOfDay {
    EARLY_MORNING,   // 5-7
    MORNING,         // 7-11
    NOON,            // 11-13
    AFTERNOON,       // 13-17
    EVENING,         // 17-19
    NIGHT,           // 19-23
    LATE_NIGHT       // 23-5
}

/**
 * 问候内容
 */
data class Greeting(
    val type: GreetingType,
    val title: String,
    val message: String,
    val quote: String? = null,
    val quoteAuthor: String? = null,
    val emoji: String,
    val timeOfDay: TimeOfDay? = null,
    val personalization: String? = null
)

/**
 * 每日一言数据库
 */
data class DailyQuote(
    val id: String,
    val text: String,
    val author: String,
    val category: QuoteCategory,
    val origin: String? = null  // 出处
)

enum class QuoteCategory {
    PHILOSOPHY,    // 哲理
    LITERATURE,    // 文学
    SCIENCE,       // 科学
    MOTIVATIONAL,  // 励志
    HUMOROUS,      // 幽默
    CLASSIC_POEM,  // 古诗
    PROVERB,       // 谚语
    MOVIE,         // 电影台词
    ANIME          // 动漫台词
}

/**
 * 问候系统
 */
class DailyGreetingSystem {

    private val quotes = mutableListOf<DailyQuote>()
        private val lastGreeting = ConcurrentHashMap<String, Long>()  // userId -> 上次问候时间
    private val userNicknames = ConcurrentHashMap<String, String>()

    init {
        loadBuiltinQuotes()
    }

    /**
     * 生成问候
     */
    fun generateGreeting(
        userId: String,
        type: GreetingType = GreetingType.TIME_BASED,
        mood: String? = null,
        weather: String? = null
    ): Greeting {
        val now = System.currentTimeMillis()
        val timeOfDay = getTimeOfDay(now)
        val nickname = userNicknames[userId]
        val personalization = nickname?.let { "，$it" } ?: ""
        return when (type) {
            GreetingType.TIME_BASED -> generateTimeGreeting(timeOfDay, personalization)
            GreetingType.DAILY_QUOTE -> generateDailyQuote(personalization)
            GreetingType.WEATHER_BASED -> generateWeatherGreeting(weather, personalization)
            GreetingType.MOOD_BASED -> generateMoodGreeting(mood, personalization)
            GreetingType.FESTIVAL_BASED -> generateFestivalGreeting(personalization)
            GreetingType.RANDOM -> generateRandomGreeting(personalization)
        }.also {
            lastGreeting[userId] = now
        }
    }

    /**
     * 设置昵称
     */
    fun setNickname(userId: String, nickname: String) {
        userNicknames[userId] = nickname
    }

    /**
     * 获取每日推荐
     */
    fun getDailyRecommendation(userId: String): Greeting {
        // 根据上次问候时间决定类型
    val last = lastGreeting[userId] ?: 0
        val hoursSince = (System.currentTimeMillis() - last) / (60 * 60_000)
        return when {
            hoursSince > 24 -> generateGreeting(userId, GreetingType.TIME_BASED)
            hoursSince > 6 -> generateGreeting(userId, GreetingType.DAILY_QUOTE)
            else -> generateGreeting(userId, GreetingType.RANDOM)
        }
    }

    /**
     * 生成问候 prompt
     */
    fun generateGreetingPrompt(userId: String): String {
        val greeting = generateGreeting(userId)
        val sb = StringBuilder()
        sb.append("[问候: ${greeting.emoji} ${greeting.title}]")
        sb.append(" ${greeting.message}")
        greeting.quote?.let {
            sb.append("\n[每日一言] $it")
            greeting.quoteAuthor?.let { a -> sb.append(" —— $a") }
        }
        return sb.toString()
    }

    // ============ 生成方法 ============
    private fun generateTimeGreeting(timeOfDay: TimeOfDay, personalization: String): Greeting {
        return when (timeOfDay) {
            TimeOfDay.EARLY_MORNING -> Greeting(
                GreetingType.TIME_BASED, "清晨问候",
                "早安$personalization！一日之计在于晨，新的一天开始了",
                quote = "黎明前总是最黑暗的", quoteAuthor = "托马斯·富勒",
                emoji = "🌅", timeOfDay = timeOfDay
            )
            TimeOfDay.MORNING -> Greeting(
                GreetingType.TIME_BASED, "早安",
                "早上好$personalization！今天也要元气满满",
                quote = "每一个不曾起舞的日子，都是对生命的辜负", quoteAuthor = "尼采",
                emoji = "☀️", timeOfDay = timeOfDay
            )
            TimeOfDay.NOON -> Greeting(
                GreetingType.TIME_BASED, "午安",
                "中午好$personalistic$personalization，记得吃午饭哦",
                quote = "民以食为天", quoteAuthor = "汉书",
                emoji = "🍜", timeOfDay = timeOfDay
            )
            TimeOfDay.AFTERNOON -> Greeting(
                GreetingType.TIME_BASED, "下午好",
                "下午好$personalization！来杯下午茶歇会儿",
                quote = "生活不止眼前的苟且，还有诗和远方", quoteAuthor = "高晓松",
                emoji = "☕", timeOfDay = timeOfDay
            )
            TimeOfDay.EVENING -> Greeting(
                GreetingType.TIME_BASED, "傍晚好",
                "傍晚好$personalization！夕阳无限好",
                quote = "夕阳无限好，只是近黄昏", quoteAuthor = "李商隐",
                emoji = "🌇", timeOfDay = timeOfDay
            )
            TimeOfDay.NIGHT -> Greeting(
                GreetingType.TIME_BASED, "晚上好",
                "晚上好$personalization！今天辛苦了",
                quote = "黑夜给了我黑色的眼睛，我却用它寻找光明", quoteAuthor = "顾城",
                emoji = "🌙", timeOfDay = timeOfDay
            )
            TimeOfDay.LATE_NIGHT -> Greeting(
                GreetingType.TIME_BASED, "夜深了",
                "都这么晚了还没睡$personalization？注意休息哦",
                quote = "睡眠是上帝的馈赠，可惜很多人拒绝了", quoteAuthor = "佚名",
                emoji = "🦉", timeOfDay = timeOfDay
            )
        }
    }
        private fun generateDailyQuote(personalization: String): Greeting {
        val quote = quotes.random()
        return Greeting(
            GreetingType.DAILY_QUOTE, "每日一言",
            "今天送你一句话$personalization",
            quote = quote.text, quoteAuthor = quote.author,
            emoji = "💬"
        )
    }
        private fun generateWeatherGreeting(weather: String?, personalization: String): Greeting {
        val w = weather?.lowercase() ?: "晴"
        return when {
            w.containsAny("晴", "sunny") -> Greeting(GreetingType.WEATHER_BASED, "晴天", "阳光明媚$personalization，适合出门", emoji = "☀️")
            w.containsAny("雨", "rain") -> Greeting(GreetingType.WEATHER_BASED, "雨天", "下雨了$personalization，记得带伞", quote = "雨打梨花深闭门", quoteAuthor = "唐寅", emoji = "🌧️")
            w.containsAny("雪", "snow") -> Greeting(GreetingType.WEATHER_BASED, "雪天", "下雪了$personalization，注意保暖", quote = "晚来天欲雪，能饮一杯无", quoteAuthor = "白居易", emoji = "❄️")
            w.containsAny("阴", "cloudy") -> Greeting(GreetingType.WEATHER_BASED, "阴天", "天阴沉沉的$personalization，心情要明亮哦", emoji = "☁️")
            w.containsAny("雾", "fog") -> Greeting(GreetingType.WEATHER_BASED, "雾天", "雾蒙蒙的$personalization，出行注意安全", emoji = "🌫️")
            else -> Greeting(GreetingType.WEATHER_BASED, "天气", "今天天气$w$personalization", emoji = "🌤️")
        }
    }
        private fun generateMoodGreeting(mood: String?, personalization: String): Greeting {
        val m = mood?.lowercase() ?: "neutral"
        return when {
            m.containsAny("happy", "开心", "快乐") -> Greeting(GreetingType.MOOD_BASED, "心情不错", "看你心情不错$personalization！", emoji = "😄")
            m.containsAny("sad", "难过", "伤心") -> Greeting(GreetingType.MOOD_BASED, "给你温暖", "不开心也没关系$personalization，我在这里", quote = "黑夜终将过去", quoteAuthor = "佚名", emoji = "🤗")
            m.containsAny("angry", "生气", "愤怒") -> Greeting(GreetingType.MOOD_BASED, "冷静一下", "深呼吸$personalization，生气伤身", quote = "愤怒以愚蠢开始，以后悔告终", quoteAuthor = "毕达哥拉斯", emoji = "😌")
            m.containsAny("tired", "累", "疲惫") -> Greeting(GreetingType.MOOD_BASED, "辛苦了", "累了就歇歇$personalization", quote = "休息是为了走更长的路", quoteAuthor = "佚名", emoji = "🛋️")
            else -> Greeting(GreetingType.MOOD_BASED, "你好", "你好$personalization", emoji = "👋")
        }
    }
        private fun generateFestivalGreeting(personalization: String): Greeting {
        val festival = detectFestival()
        return if (festival != null) {
            Greeting(GreetingType.FESTIVAL_BASED, festival.name, "${festival.greeting}$personalization", emoji = festival.emoji)
        } else {
            generateTimeGreeting(getTimeOfDay(System.currentTimeMillis()), personalization)
        }
    }
        private fun generateRandomGreeting(personalization: String): Greeting {
        val types = GreetingType.values().filter { it != GreetingType.RANDOM }
        return generateGreeting("user", types.random())
    }
        private fun detectFestival(): Festival? {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return when {
            month == 1 && day == 1 -> Festival("元旦", "新年快乐", "🎉")
            month == 2 && day == 14 -> Festival("情人节", "情人节快乐", "💝")
            month == 3 && day == 8 -> Festival("妇女节", "节日快乐", "🌸")
            month == 5 && day == 1 -> Festival("劳动节", "劳动节快乐", "⚒️")
            month == 5 && day == 4 -> Festival("青年节", "青年节快乐", "🔥")
            month == 6 && day == 1 -> Festival("儿童节", "永葆童心", "🎈")
            month == 10 && day == 1 -> Festival("国庆节", "国庆快乐", "🇨🇳")
            month == 12 && day == 25 -> Festival("圣诞节", "圣诞快乐", "🎄")
            month == 12 && day == 31 -> Festival("除夕夜", "辞旧迎新", "🎆")
            else -> null
        }
    }
        private fun getTimeOfDay(timestamp: Long): TimeOfDay {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..6 -> TimeOfDay.EARLY_MORNING
            in 7..10 -> TimeOfDay.MORNING
            in 11..12 -> TimeOfDay.NOON
            in 13..16 -> TimeOfDay.AFTERNOON
            in 17..18 -> TimeOfDay.EVENING
            in 19..22 -> TimeOfDay.NIGHT
            else -> TimeOfDay.LATE_NIGHT
        }
    }
        private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }

    data class Festival(val name: String, val greeting: String, val emoji: String)
        private fun loadBuiltinQuotes() {
        quotes.addAll(listOf(
            // 哲理
            DailyQuote("q1", "我思故我在", "笛卡尔", QuoteCategory.PHILOSOPHY, "《第一哲学沉思集》"),
            DailyQuote("q2", "存在先于本质", "萨特", QuoteCategory.PHILOSOPHY, "《存在与虚无》"),
            DailyQuote("q3", "人是万物的尺度", "普罗泰戈拉", QuoteCategory.PHILOSOPHY),
            // 文学
            DailyQuote("q4", "生活不止眼前的苟且，还有诗和远方", "高晓松", QuoteCategory.LITERATURE),
            DailyQuote("q5", "愿你出走半生，归来仍是少年", "佚名", QuoteCategory.LITERATURE),
            // 励志
            DailyQuote("q6", "千里之行，始于足下", "老子", QuoteCategory.MOTIVATIONAL, "《道德经》"),
            DailyQuote("q7", "天行健，君子以自强不息", "《周易》", QuoteCategory.MOTIVATIONAL),
            DailyQuote("q8", "不积跬步，无以至千里", "荀子", QuoteCategory.MOTIVATIONAL, "《劝学》"),
            // 幽默
            DailyQuote("q9", "我不是胖，我是可爱到膨胀", "佚名", QuoteCategory.HUMOROUS),
            DailyQuote("q10", "我不是在发呆，我是在下载灵感", "佚名", QuoteCategory.HUMOROUS),
            // 古诗
            DailyQuote("q11", "床前明月光，疑是地上霜", "李白", QuoteCategory.CLASSIC_POEM, "《静夜思》"),
            DailyQuote("q12", "海内存知己，天涯若比邻", "王勃", QuoteCategory.CLASSIC_POEM, "《送杜少府之任蜀州》"),
            DailyQuote("q13", "落霞与孤鹜齐飞，秋水共长天一色", "王勃", QuoteCategory.CLASSIC_POEM, "《滕王阁序》"),
            // 谚语
            DailyQuote("q14", "失败是成功之母", "佚名", QuoteCategory.PROVERB),
            DailyQuote("q15", "条条大路通罗马", "西方谚语", QuoteCategory.PROVERB),
            // 电影
            DailyQuote("q16", "希望是美好的，也许是人间至善", "《肖申克的救赎》", QuoteCategory.MOVIE),
            DailyQuote("q17", "生活就像一盒巧克力", "《阿甘正传》", QuoteCategory.MOVIE),
            // 动漫
            DailyQuote("q18", "只要你相信，就有可能", "《海贼王》", QuoteCategory.ANIME),
            // 科学
            DailyQuote("q19", "想象力比知识更重要", "爱因斯坦", QuoteCategory.SCIENCE),
            DailyQuote("q20", "我们都生活在阴沟里，但仍有人仰望星空", "王尔德", QuoteCategory.PHILOSOPHY)
        ))
    }
}
