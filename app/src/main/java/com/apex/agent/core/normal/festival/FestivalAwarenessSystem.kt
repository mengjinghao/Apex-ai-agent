package com.apex.agent.core.normal.festival

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * F43: 节日/节气感知系统（Festival & Solar Term Awareness）
 *
 * 感知中国传统节日、节气、国际节日：
 * - 农历节日（春节/中秋/端午等）
 * - 二十四节气
 * - 国际节日
 * - 纪念日
 * - 节日习俗/祝福
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不感知节日
 * - 狂暴不关心文化
 * - 本功能让单 Agent **有文化底蕴、应景**
 */

enum class FestivalType {
    LUNAR,           // 农历节日
    SOLAR,           // 公历节日
    SOLAR_TERM,      // 节气
    INTERNATIONAL,   // 国际节日
    MEMORIAL,        // 纪念日
    TRADITIONAL      // 传统节日
}

data class Festival(
    val id: String,
    val name: String,
    val type: FestivalType,
    val datePattern: String,        // "M-D" 公历 或 "L-M-D" 农历
    val greeting: String,
    val customs: List<String>,      // 习俗
    val foods: List<String>,        // 节日食物
    val blessing: String,           // 祝福语
    val emoji: String,
    val description: String,
    val durationDays: Int = 1       // 持续天数
)

data class SolarTerm(
    val id: String,
    val name: String,               // 节气名
    val meaning: String,            // 含义
    val month: Int,                 // 公历月份
    val day: Int,                   // 公历日期（近似）
    val customs: List<String>,
    val healthTips: List<String>    // 养生建议
)

data class FestivalContext(
    val currentFestivals: List<Festival>,
    val upcomingFestivals: List<Festival>,
    val currentSolarTerm: SolarTerm?,
    val nextSolarTerm: SolarTerm?,
    val isFestivalDay: Boolean,
    val greeting: String?
)

class FestivalAwarenessSystem {

    private val festivals = mutableListOf<Festival>()
        private val solarTerms = mutableListOf<SolarTerm>()

    init {
        loadBuiltinFestivals()
        loadBuiltinSolarTerms()
    }
        fun getCurrentContext(): FestivalContext {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val current = festivals.filter { f ->
            val (fMonth, fDay) = parseDate(f.datePattern, cal)
            fMonth == month && (fDay == day || (f.durationDays > 1 && day in fDay..fDay + f.durationDays - 1))
        }
        val upcoming = festivals.filter { f ->
            val (fMonth, fDay) = parseDate(f.datePattern, cal)
        val calCopy = cal.clone() as Calendar
            calCopy.set(Calendar.MONTH, fMonth - 1)
            calCopy.set(Calendar.DAY_OF_MONTH, fDay)
        if (calCopy.timeInMillis < now) calCopy.add(Calendar.YEAR, 1)
        val daysUntil = ((calCopy.timeInMillis - now) / (24 * 60 * 60_000L)).toInt()
            daysUntil in 1..30
        }.sortedBy { f ->
            val (fMonth, fDay) = parseDate(f.datePattern, cal)
        val calCopy = cal.clone() as Calendar
            calCopy.set(Calendar.MONTH, fMonth - 1)
            calCopy.set(Calendar.DAY_OF_MONTH, fDay)
        if (calCopy.timeInMillis < now) calCopy.add(Calendar.YEAR, 1)
            calCopy.timeInMillis
        }.take(3)
        val currentTerm = solarTerms.find { it.month == month && kotlin.math.abs(it.day - day) <= 7 }
        val nextTerm = solarTerms.find { st ->
            val stCal = cal.clone() as Calendar
            stCal.set(Calendar.MONTH, st.month - 1)
            stCal.set(Calendar.DAY_OF_MONTH, st.day)
        if (stCal.timeInMillis < now) stCal.add(Calendar.YEAR, 1)
            ((stCal.timeInMillis - now) / (24 * 60 * 60_000L)).toInt() in 1..30
        }
        val greeting = if (current.isNotEmpty()) current.first().greeting else null

        return FestivalContext(current, upcoming, currentTerm, nextTerm, current.isNotEmpty(), greeting)
    }
        fun generateFestivalPrompt(): String {
        val ctx = getCurrentContext()
        val sb = StringBuilder()
        if (ctx.isFestivalDay && ctx.currentFestivals.isNotEmpty()) {
            val f = ctx.currentFestivals.first()
            sb.appendLine("[今日节日: ${f.name} ${f.emoji}]")
            sb.appendLine(f.description)
            sb.appendLine("祝福: ${f.blessing}")
        if (f.customs.isNotEmpty()) sb.appendLine("习俗: ${f.customs.joinToString()}")
        if (f.foods.isNotEmpty()) sb.appendLine("应景食物: ${f.foods.joinToString()}")
        }

        ctx.currentSolarTerm?.let { st ->
            sb.appendLine("[当前节气: ${st.name}]")
            sb.appendLine("含义: ${st.meaning}")
        if (st.healthTips.isNotEmpty()) sb.appendLine("养生: ${st.healthTips.joinToString()}")
        }
        if (ctx.upcomingFestivals.isNotEmpty()) {
            sb.appendLine("[即将到来的节日]")
            ctx.upcomingFestivals.forEach { sb.appendLine("- ${it.name} ${it.emoji}") }
        }
        return if (sb.isEmpty()) "" else sb.toString()
    }
        fun listFestivals(type: FestivalType? = null): List<Festival> {
        return if (type != null) festivals.filter { it.type == type } else festivals
    }
        fun listSolarTerms(): List<SolarTerm> = solarTerms.toList()
        fun addFestival(festival: Festival) { festivals.add(festival) }
        private fun parseDate(pattern: String, cal: Calendar): Pair<Int, Int> {
        // 简化：仅支持 "M-D" 格式
    val parts = pattern.split("-")
        return if (parts.size == 2) (parts[0].toIntOrNull() ?: 1) to (parts[1].toIntOrNull() ?: 1) else 1 to 1
    }
        private fun loadBuiltinFestivals() {
        festivals.addAll(listOf(
            Festival("f1", "元旦", FestivalType.SOLAR, "1-1", "新年快乐", listOf("倒计时", "跨年"), listOf("年糕"), "新年新气象", "🎉", "公历新年第一天"),
            Festival("f2", "情人节", FestivalType.INTERNATIONAL, "2-14", "情人节快乐", listOf("送花", "约会"), listOf("巧克力"), "愿有情人终成眷属", "💝", "西方情人节"),
            Festival("f3", "妇女节", FestivalType.INTERNATIONAL, "3-8", "节日快乐", listOf("送花"), listOf(), "致敬每一位女性", "🌸", "国际劳动妇女节"),
            Festival("f4", "植树节", FestivalType.SOLAR, "3-12", "植树快乐", listOf("种树"), listOf(), "绿水青山就是金山银山", "🌳", "中国植树节"),
            Festival("f5", "清明节", FestivalType.TRADITIONAL, "4-5", "清明安康", listOf("扫墓", "踏青"), listOf("青团"), "慎终追远", "🍃", "祭祖扫墓节日"),
            Festival("f6", "劳动节", FestivalType.INTERNATIONAL, "5-1", "劳动节快乐", listOf("休息", "出游"), listOf(), "致敬劳动者", "⚒️", "国际劳动节", 5),
            Festival("f7", "青年节", FestivalType.SOLAR, "5-4", "青年节快乐", listOf("学习"), listOf(), "青春万岁", "🔥", "五四青年节"),
            Festival("f8", "母亲节", FestivalType.INTERNATIONAL, "5-12", "母亲节快乐", listOf("送康乃馨"), listOf(), "感恩母亲", "Mother", "💐", "感恩母亲的节日"),
            Festival("f9", "儿童节", FestivalType.INTERNATIONAL, "6-1", "儿童节快乐", listOf("送礼物"), listOf("糖果"), "永葆童心", "🎈", "国际儿童节"),
            Festival("f10", "父亲节", FestivalType.INTERNATIONAL, "6-16", "父亲节快乐", listOf("送礼物"), listOf(), "感恩父亲", "👨", "感恩父亲的节日"),
            Festival("f11", "端午节", FestivalType.TRADITIONAL, "6-10", "端午安康", listOf("赛龙舟", "挂艾草"), listOf("粽子", "咸鸭蛋"), "端午安康", "🐲", "纪念屈原"),
            Festival("f12", "建党节", FestivalType.SOLAR, "7-1", "建党节快乐", listOf("学习"), listOf(), "不忘初心", "🇨🇳", "中国共产党建党纪念日"),
            Festival("f13", "建军节", FestivalType.SOLAR, "8-1", "建军节快乐", listOf("致敬"), listOf(), "致敬最可爱的人", "🎖️", "中国人民解放军建军节"),
            Festival("f14", "教师节", FestivalType.SOLAR, "9-10", "教师节快乐", listOf("送花"), listOf(), "感恩师长", "📚", "感恩教师的节日"),
            Festival("f15", "中秋节", FestivalType.TRADITIONAL, "9-17", "中秋快乐", listOf("赏月", "团圆"), listOf("月饼", "柚子"), "月圆人团圆", "🌕", "团圆节日", 3),
            Festival("f16", "国庆节", FestivalType.SOLAR, "10-1", "国庆快乐", listOf("升旗", "出游"), listOf(), "祖国繁荣昌盛", "🇨🇳", "中华人民共和国国庆", 7),
            Festival("f17", "重阳节", FestivalType.TRADITIONAL, "10-11", "重阳安康", listOf("登高", "敬老"), listOf("重阳糕"), "敬老爱老", "🍂", "敬老节日"),
            Festival("f18", "万圣节", FestivalType.INTERNATIONAL, "10-31", "Trick or treat", listOf("化妆", "讨糖"), listOf("糖果"), "不给糖就捣蛋", "🎃", "西方万圣节"),
            Festival("f19", "感恩节", FestivalType.INTERNATIONAL, "11-28", "感恩节快乐", listOf("团聚"), listOf("火鸡"), "感恩一切", "🦃", "西方感恩节"),
            Festival("f20", "平安夜", FestivalType.INTERNATIONAL, "12-24", "平安喜乐", listOf("送苹果"), listOf("苹果"), "平平安安", "🍎", "圣诞前夕"),
            Festival("f21", "圣诞节", FestivalType.INTERNATIONAL, "12-25", "圣诞快乐", listOf("送礼", "装饰"), listOf(), "Merry Christmas", "🎄", "西方圣诞节"),
            Festival("f22", "除夕", FestivalType.TRADITIONAL, "12-31", "除夕快乐", listOf("守岁", "年夜饭"), listOf("饺子", "年糕"), "辞旧迎新", "🧧", "农历年最后一天")
        ))
    }
        private fun loadBuiltinSolarTerms() {
        solarTerms.addAll(listOf(
            SolarTerm("st1", "立春", "春天开始", 2, 4, listOf("咬春"), listOf("养肝", "早起")),
            SolarTerm("st2", "雨水", "降雨开始增多", 2, 19, listOf(), listOf("健脾", "防寒")),
            SolarTerm("st3", "惊蛰", "春雷惊醒蛰虫", 3, 6, listOf(), listOf("养肝", "清淡饮食")),
            SolarTerm("st4", "春分", "昼夜平分", 3, 21, listOf("竖蛋"), listOf("平肝", "踏青")),
            SolarTerm("st5", "清明", "天气晴朗", 4, 5, listOf("扫墓", "踏青"), listOf("养肝", "运动")),
            SolarTerm("st6", "谷雨", "雨生百谷", 4, 20, listOf(), listOf("祛湿", "养脾")),
            SolarTerm("st7", "立夏", "夏天开始", 5, 6, listOf(), listOf("养心", "午休")),
            SolarTerm("st8", "小满", "麦粒渐满", 5, 21, listOf(), listOf("清热", "防湿")),
            SolarTerm("st9", "芒种", "有芒作物成熟", 6, 6, listOf(), listOf("清热", "补水")),
            SolarTerm("st10", "夏至", "白昼最长", 6, 21, listOf(), listOf("养心", "忌凉")),
            SolarTerm("st11", "小暑", "炎热将至", 7, 7, listOf(), listOf("防暑", "清淡")),
            SolarTerm("st12", "大暑", "一年最热", 7, 23, listOf(), listOf("防暑", "绿豆汤")),
            SolarTerm("st13", "立秋", "秋天开始", 8, 8, listOf("贴秋膘"), listOf("养肺", "润燥")),
            SolarTerm("st14", "处暑", "暑气止", 8, 23, listOf(), listOf("润肺", "早睡")),
            SolarTerm("st15", "白露", "露凝而白", 9, 8, listOf(), listOf("保暖", "润肺")),
            SolarTerm("st16", "秋分", "昼夜平分", 9, 23, listOf(), listOf("养肺", "润燥")),
            SolarTerm("st17", "寒露", "露寒将凝", 10, 8, listOf(), listOf("保暖", "足浴")),
            SolarTerm("st18", "霜降", "初霜出现", 10, 23, listOf(), listOf("保暖", "润肺")),
            SolarTerm("st19", "立冬", "冬天开始", 11, 7, listOf("补冬"), listOf("补肾", "早睡")),
            SolarTerm("st20", "小雪", "开始降雪", 11, 22, listOf(), listOf("保暖", "温补")),
            SolarTerm("st21", "大雪", "降雪增多", 12, 7, listOf(), listOf("保暖", "进补")),
            SolarTerm("st22", "冬至", "白昼最短", 12, 22, listOf("吃饺子"), listOf("养肾", "进补")),
            SolarTerm("st23", "小寒", "寒冷加剧", 1, 6, listOf(), listOf("保暖", "温补")),
            SolarTerm("st24", "大寒", "一年最冷", 1, 20, listOf(), listOf("保暖", "养肾"))
        ))
    }
}
