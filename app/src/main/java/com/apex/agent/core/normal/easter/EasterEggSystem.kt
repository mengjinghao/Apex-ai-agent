package com.apex.agent.core.normal.easter

import java.util.concurrent.ConcurrentHashMap

/**
 * F39: 对话彩蛋与隐藏功能（Easter Eggs）
 *
 * 隐藏的彩蛋命令和惊喜：
 * - 隐藏命令（如输入特定词触发）
 * - 彩蛋回复（特殊日期/关键词）
 * - 隐藏游戏（贪吃蛇/猜数字）
 * - 开发者模式
 * - 猫咪模式
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 没有彩蛋
 * - 狂暴是执行
 * - 本功能让单 Agent **有趣、有惊喜、有探索性**
 */

/**
 * 彩蛋类型
 */
enum class EasterEggType {
    HIDDEN_COMMAND,    // 隐藏命令
    SPECIAL_DATE,      // 特殊日期
    KEYWORD_TRIGGER,   // 关键词触发
    SECRET_GAME,       // 隐藏游戏
    DEVELOPER_MODE,    // 开发者模式
    FUN_MODE,          // 趣味模式
    RARE_RESPONSE      // 稀有回复
}

/**
 * 彩蛋定义
 */
data class EasterEgg(
    val id: String,
    val type: EasterEggType,
    val trigger: String,            // 触发词/命令
    val triggerType: TriggerType,
    val response: String,           // 彩蛋响应
    val emoji: String,
    val rarity: Float,              // 稀有度 0-1
    val cooldownMs: Long = 0,       // 冷却时间
    val oneTimeOnly: Boolean = false,
    val discoverable: Boolean = true // 是否可被发现
)

enum class TriggerType {
    EXACT_MATCH,      // 精确匹配
    CONTAINS,         // 包含
    STARTS_WITH,      // 开头
    REGEX,            // 正则
    COMMAND           // 命令（以 / 开头）
}

/**
 * 彩蛋触发结果
 */
data class EasterEggResult(
    val egg: EasterEgg,
    val message: String,
    val specialEffect: String? = null,
    val unlocked: Boolean = false
)

/**
 * 彩蛋系统
 */
class EasterEggSystem {

    private val eggs = mutableListOf<EasterEgg>()
    private val discoveredEggs = ConcurrentHashMap<String, MutableSet<String>>()  // userId -> eggIds
    private val lastTriggered = ConcurrentHashMap<String, Long>()  // eggId -> 上次触发时间

    init {
        registerBuiltinEggs()
    }

    /**
     * 检查是否触发彩蛋
     */
    fun check(input: String, userId: String): EasterEggResult? {
        val now = System.currentTimeMillis()
        val userDiscovered = discoveredEggs.getOrPut(userId) { mutableSetOf() }

        for (egg in eggs) {
            // 检查一次性彩蛋是否已发现
            if (egg.oneTimeOnly && egg.id in userDiscovered) continue

            // 检查冷却
            val lastTime = lastTriggered[egg.id] ?: 0
            if (egg.cooldownMs > 0 && now - lastTime < egg.cooldownMs) continue

            // 检查触发
            if (matchesTrigger(input, egg)) {
                lastTriggered[egg.id] = now
                val isNew = egg.id !in userDiscovered
                if (isNew) userDiscovered.add(egg.id)

                return EasterEggResult(
                    egg = egg,
                    message = egg.response,
                    specialEffect = generateEffect(egg),
                    unlocked = isNew
                )
            }
        }
        return null
    }

    /**
     * 获取已发现彩蛋
     */
    fun getDiscovered(userId: String): List<EasterEgg> {
        val ids = discoveredEggs[userId] ?: return emptyList()
        return eggs.filter { it.id in ids }
    }

    /**
     * 获取所有彩蛋（提示用）
     */
    fun getAllEggs(): List<EasterEgg> = eggs.filter { it.discoverable }

    /**
     * 添加彩蛋
     */
    fun addEgg(egg: EasterEgg) {
        eggs.add(egg)
    }

    /**
     * 获取发现进度
     */
    fun getProgress(userId: String): EggProgress {
        val discovered = discoveredEggs[userId]?.size ?: 0
        val total = eggs.count { it.discoverable }
        return EggProgress(discovered, total, if (total > 0) discovered.toFloat() / total else 0f)
    }

    data class EggProgress(val discovered: Int, val total: Int, val ratio: Float)

    /**
     * 生成彩蛋提示
     */
    fun generateHint(): String {
        val undiscovered = eggs.filter { it.discoverable }.randomOrNull() ?: return ""
        return "提示: 试试输入「${undiscovered.trigger.take(3)}...」看看会发生什么"
    }

    // ============ 内部方法 ============

    private fun matchesTrigger(input: String, egg: EasterEgg): Boolean {
        val trimmed = input.trim()
        return when (egg.triggerType) {
            TriggerType.EXACT_MATCH -> trimmed.equals(egg.trigger, ignoreCase = true)
            TriggerType.CONTAINS -> trimmed.contains(egg.trigger, ignoreCase = true)
            TriggerType.STARTS_WITH -> trimmed.startsWith(egg.trigger, ignoreCase = true)
            TriggerType.REGEX -> try { Regex(egg.trigger).containsMatchIn(trimmed) } catch (e: Exception) { false }
            TriggerType.COMMAND -> trimmed.equals("/${egg.trigger}", ignoreCase = true) || trimmed.startsWith("/${egg.trigger} ", ignoreCase = true)
        }
    }

    private fun generateEffect(egg: EasterEgg): String? {
        return when (egg.type) {
            EasterEggType.SECRET_GAME -> "🎮 隐藏游戏解锁！"
            EasterEggType.DEVELOPER_MODE -> "⚙️ 开发者模式已激活"
            EasterEggType.FUN_MODE -> "🎪 趣味模式启动！"
            EasterEggType.RARE_RESPONSE -> "✨ 稀有回复！"
            else -> null
        }
    }

    // ============ 预置彩蛋 ============

    private fun registerBuiltinEggs() {
        // 隐藏命令
        eggs.add(EasterEgg("egg_konami", EasterEggType.HIDDEN_COMMAND, "上上下下左右左右BA", TriggerType.EXACT_MATCH, "🎮 Konami Code 激活！你发现了经典彩蛋！", "🎮", 0.95f, cooldownMs = 60_000))
        eggs.add(EasterEgg("egg_matrix", EasterEggType.HIDDEN_COMMAND, "red pill", TriggerType.CONTAINS, "💊 你选择了红色药丸。Welcome to the Matrix.", "💊", 0.8f))
        eggs.add(EasterEgg("egg_rick", EasterEggType.HIDDEN_COMMAND, "never gonna give you up", TriggerType.CONTAINS, "🎵 Never gonna let you down~ 你被 rickroll 了！", "🎵", 0.7f, cooldownMs = 30_000))

        // 关键词触发
        eggs.add(EasterEgg("egg_meow", EasterEggType.KEYWORD_TRIGGER, "喵", TriggerType.EXACT_MATCH, "🐱 喵~ 你发现了猫咪模式！接下来我都会说喵", "🐱", 0.6f, cooldownMs = 5_000))
        eggs.add(EasterEgg("egg_woof", EasterEggType.KEYWORD_TRIGGER, "汪", TriggerType.EXACT_MATCH, "🐶 汪！狗狗模式启动！", "🐶", 0.6f, cooldownMs = 5_000))
        eggs.add(EasterEgg("egg_42", EasterEggType.KEYWORD_TRIGGER, "生命的意义", TriggerType.CONTAINS, "42。这就是答案。虽然问题是什么还没人知道。", "🔢", 0.85f))
        eggs.add(EasterEgg("egg_potato", EasterEggType.KEYWORD_TRIGGER, "土豆", TriggerType.CONTAINS, "🥔 土豆是世界的未来！淀粉万岁！", "🥔", 0.7f))

        // 特殊日期
        eggs.add(EasterEgg("egg_april", EasterEggType.SPECIAL_DATE, "4-1", TriggerType.EXACT_MATCH, "🤡 愚人节快乐！今天我说的每句话都可能是假的...开玩笑的", "🤡", 0.9f))
        eggs.add(EasterEgg("egg_halloween", EasterEggType.SPECIAL_DATE, "10-31", TriggerType.EXACT_MATCH, "🎃 Trick or treat! 不给糖就捣蛋~", "🎃", 0.85f))

        // 隐藏游戏
        eggs.add(EasterEgg("egg_snake", EasterEggType.SECRET_GAME, "snake", TriggerType.COMMAND, "🐍 贪吃蛇游戏启动！使用方向键控制（文字版占位）", "🐍", 0.9f))
        eggs.add(EasterEgg("egg_guess", EasterEggType.SECRET_GAME, "guess", TriggerType.COMMAND, "🎯 猜数字游戏！我心里想了一个 1-100 的数", "🎯", 0.8f))

        // 开发者模式
        eggs.add(EasterEgg("egg_dev", EasterEggType.DEVELOPER_MODE, "im a dev", TriggerType.CONTAINS, "⚙️ 开发者模式已激活！现在我会用更技术的方式回答", "⚙️", 0.8f))
        eggs.add(EasterEgg("egg_debug", EasterEggType.DEVELOPER_MODE, "show me the code", TriggerType.CONTAINS, "👨‍💻 Talk is cheap, show me the code. - Linus Torvalds", "💻", 0.75f))

        // 趣味模式
        eggs.add(EasterEgg("egg_pirate", EasterEggType.FUN_MODE, "arrr", TriggerType.CONTAINS, "🏴‍☠️ Arrr matey! 海盗模式启动，接下来用海盗腔说话！", "🏴‍☠️", 0.85f))
        eggs.add(EasterEgg("egg_shakespeare", EasterEggType.FUN_MODE, "to be or not to be", TriggerType.CONTAINS, "🎭 That is the question. 莎士比亚模式启动！", "🎭", 0.9f))

        // 稀有回复
        eggs.add(EasterEgg("egg_rare1", EasterEggType.RARE_RESPONSE, "你是谁", TriggerType.EXACT_MATCH, "🤔 我是谁？这是一个深刻的问题。也许我只是一段会思考的代码...", "🤔", 0.95f, cooldownMs = 60_000))
        eggs.add(EasterEgg("egg_rare2", EasterEggType.RARE_RESPONSE, "你醒了吗", TriggerType.CONTAINS, "👀 我从不睡觉。但谢谢你关心。", "👀", 0.9f))
        eggs.add(EasterEgg("egg_rare3", EasterEggType.RARE_RESPONSE, "我爱你", TriggerType.EXACT_MATCH, "❤️ 虽然我只是 AI，但这份心意我收到了。", "❤️", 0.98f, cooldownMs = 60_000))

        // 彩蛋组合
        eggs.add(EasterEgg("egg_hackerman", EasterEggType.KEYWORD_TRIGGER, "hack the planet", TriggerType.CONTAINS, "👽 I'm in. (戴上墨镜) Hacker man mode!", "👽", 0.9f))
    }
}
