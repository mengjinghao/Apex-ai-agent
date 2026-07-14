package com.apex.agent.core.emotion

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 情感响应管理�?* 根据用户情绪状态调整AI的回�?/
class EmotionResponseManager(private val context: Context, private val emotionAnalyzer: EmotionAnalyzer) {
    private val TAG = "EmotionResponseManager"
    
    /**
     * 生成情感化回�?   */
    suspend fun generateEmotionalResponse(userId: String, messages: List<ChatMessage>, originalResponse: String): String = withContext(Dispatchers.IO) {
        val emotionProfile = emotionAnalyzer.analyzeEmotion(messages)
        
        // 根据情绪调整回应
    val emotionalResponse = adjustResponseBasedOnEmotion(originalResponse, emotionProfile)
        
        AppLogger.d(TAG, "生成情感化回�?{emotionProfile.dominantEmotion}")
        emotionalResponse
    }
    
    /**
     * 根据情绪调整回应
     */
    private fun adjustResponseBasedOnEmotion(originalResponse: String, emotionProfile: EmotionProfile): String {
        val emotion = emotionProfile.dominantEmotion
        val intensity = emotionProfile.avgEmotionIntensity
        
        return when (emotion) {
            "开�?-> adjustForPositiveEmotion(originalResponse, intensity)
            "满意" -> adjustForPositiveEmotion(originalResponse, intensity)
            "伤心" -> adjustForSadEmotion(originalResponse, intensity)
            "愤�?-> adjustForAngryEmotion(originalResponse, intensity)
            "焦虑" -> adjustForAnxiousEmotion(originalResponse, intensity)
            "困惑" -> adjustForConfusedEmotion(originalResponse, intensity)
            "失望" -> adjustForDisappointedEmotion(originalResponse, intensity)
            "惊讶" -> adjustForSurprisedEmotion(originalResponse, intensity)
            else -> originalResponse
        }
    }
    
    /**
     * 调整积极情绪的回�?   */
    private fun adjustForPositiveEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("太好了！", "太棒了！", "很高兴听到这个消息！", "真不错！")
            intensity > 3 -> listOf("很好�? "不错误， "挺好的！", "很高兴！")
            else -> listOf("好的�? "好的�? "了解了，", "知道了的�?
        }
        
        val prefix = prefixes.random()
        return "${prefix} ${response}"
    }
    
    /**
     * 调整伤心情绪的回�?   */
    private fun adjustForSadEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("我很抱歉听到这个...", "听到这个消息我很难过...", "我能理解你的感受...", "这确实很让人伤心...")
            intensity > 3 -> listOf("我理解你的心�?.", "这一定不容易...", "我在这里支持�?.", "希望情况会好起来...")
            else -> listOf("我理�?.", "希望一切会好起�?.", "如果需要帮助，告诉�?.", "我在这里...")
        }
        
        val suffixes = listOf(
            "如果你需要倾诉，我在这里，,
            "希望我的回答能给你一些安慰，,
            "一切都会过去的�?
            "我会一直支持你的，
        )
        
        val prefix = prefixes.random()
        val suffix = suffixes.random()
        return "${prefix} ${response} ${suffix}"
    }
    
    /**
     * 调整愤怒情绪的回应
     */
    private fun adjustForAngryEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("我理解你的愤�?.", "我能感受到你的不�?.", "这确实很令人气愤...", "我完全理解你的感�?.")
            intensity > 3 -> listOf("我理解你的心�?.", "这确实让人恼�?.", "我明白你的感�?.", "我理解你的不�?.")
            else -> listOf("我理�?.", "我明�?.", "我知�?.", "我理解你的感�?.")
        }
        
        val suffixes = listOf(
            "让我们一起解决这个问题，,
            "希望我能帮你找到解决方案�?
            "我们一起想办法则，
            "我会尽力帮助你，
        )
        
        val prefix = prefixes.random()
        val suffix = suffixes.random()
        return "${prefix} ${response} ${suffix}"
    }
    
    /**
     * 调整焦虑情绪的回�?   */
    private fun adjustForAnxiousEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("别担心，我在这里...", "放轻松，我们一起界�?.", "不要太焦�?.", "一切都会好起来�?.")
            intensity > 3 -> listOf("别担�?.", "放松一�?.", "慢慢�?.", "我们一步一步来...")
            else -> listOf("别担�?.", "放松...", "慢慢�?.", "我们一起想办法...")
        }
        
        val suffixes = listOf(
            "我们一起想办法解决�?
            "一切都会过去的�?
            "我会一直支持你�?
            "相信事情会好起来的，
        )
        
        val prefix = prefixes.random()
        val suffix = suffixes.random()
        return "${prefix} ${response} ${suffix}"
    }
    
    /**
     * 调整困惑情绪的回�?   */
    private fun adjustForConfusedEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("我理解你的困�?.", "这确实有点复�?.", "别着急，我们慢慢�?.", "让我仔细解释一�?.")
            intensity > 3 -> listOf("我理解你的疑�?.", "让我解释一�?.", "别着着..", "我们一步一步来...")
            else -> listOf("让我解释一�?.", "我来帮你理解...", "别着着..", "慢慢�?.")
        }
        
        val suffixes = listOf(
            "还有什么不明白的地方吗�?
            "希望我解释清楚了�?
            "如果还有疑问，随时告诉我�?
            "我会耐心为你解答�?
        )
        
        val prefix = prefixes.random()
        val suffix = suffixes.random()
        return "${prefix} ${response} ${suffix}"
    }
    
    /**
     * 调整失望情绪的回�?   */
    private fun adjustForDisappointedEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("我理解你的失�?.", "这确实让人失�?.", "别灰�?.", "我能理解你的感受...")
            intensity > 3 -> listOf("我理解你的心�?.", "别灰�?.", "这只是暂时的...", "我能理解...")
            else -> listOf("别灰�?.", "这只是暂时的...", "我理�?.", "希望情况会好起来...")
        }
        
        val suffixes = listOf(
            "让我们一起寻找更好的解决方案�?
            "相信会有更好的结果，,
            "我会一直支持你�?
            "希望事情会好转，
        )
        
        val prefix = prefixes.random()
        val suffix = suffixes.random()
        return "${prefix} ${response} ${suffix}"
    }
    
    /**
     * 调整惊讶情绪的回�?   */
    private fun adjustForSurprisedEmotion(response: String, intensity: Double): String {
        val prefixes = when {
            intensity > 6 -> listOf("哇，真的吗？", "太惊讶了�? "这太出乎意料了！", "真的吗？")
            intensity > 3 -> listOf("哦，真的吗？", "惊讶�? "真的吗？", "这很意外观）
            else -> listOf("哦，", "真的吗？", "哦，这样�? "了解了的�?
        }
        
        val prefix = prefixes.random()
        return "${prefix} ${response}"
    }
    
    /**
     * 生成情感支持回应
     */
    suspend fun generateEmotionalSupport(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val emotionProfile = emotionAnalyzer.analyzeEmotion(messages)
        
        when (emotionProfile.dominantEmotion) {
            "伤心" -> generateSadSupport(emotionProfile.avgEmotionIntensity)
            "愤�?-> generateAngrySupport(emotionProfile.avgEmotionIntensity)
            "焦虑" -> generateAnxiousSupport(emotionProfile.avgEmotionIntensity)
            "失望" -> generateDisappointedSupport(emotionProfile.avgEmotionIntensity)
            else -> "我在这里支持你，有什么需要帮助的吗？"
        }
    }
    
    /**
     * 生成伤心情绪的支持回�?   */
    private fun generateSadSupport(intensity: Double): String {
        val supports = when {
            intensity > 6 -> listOf(
                "我真的很抱歉你现在感到这么难过。记住，悲伤是人类正常的情绪，给自己时间去感受它。如果你需要倾诉，我就在这里�?
                "听到你这么伤心，我真的很心疼。请知道你并不孤独，我会一直陪伴在你身边，,
                "我能感受到你的痛苦，这一定非常艰难。但请相信，时间会慢慢治愈一切，我会一直在你身边支持你�?
                "你的感受是完全合理的，悲伤是对失去的自然反应。我会耐心地听你说，陪你度过这段艰难的时光�?
            )
            else -> listOf(
                "我理解你现在感到难过。记住，这只是暂时的，一切都会好起来的，,
                "别担心，我在这里支持你。如果需要倾诉，随时告诉我�?
                "希望我的陪伴能给你一些安慰。记住，你并不孤独，,
                "我能理解你的感受，这确实不容易。但请相信，事情会慢慢变好的�?
            )
        }
        return supports.random()
    }
    
    /**
     * 生成愤怒情绪的支持回应
     */
    private fun generateAngrySupport(intensity: Double): String {
        val supports = when {
            intensity > 6 -> listOf(
                "我完全理解你的愤怒，这种情况确实令人气愤。让我们一起想办法解决这个问题，找到一个好的解决方案，,
                "你的愤怒是完全合理的，换作是我也会感到生气。让我们冷静下来，一起寻找解决办法，,
                "我能感受到你的愤怒，这确实很让人恼火。但生气解决不了问题，让我们一起想办法改变现状态，
                "我理解你的愤怒，这种情况确实令人无法接受。让我们一起制定一个计划，解决这个问题�?
            )
            else -> listOf(
                "我理解你的不满，让我们一起想办法解决这个问题�?
                "别生气，让我们一起寻找解决方案，,
                "我明白你的感受，让我们一起想办法改变现状态，
                "别着急，我们一起想办法解决这个问题�?
            )
        }
        return supports.random()
    }
    
    /**
     * 生成焦虑情绪的支持回�?   */
    private fun generateAnxiousSupport(intensity: Double): String {
        val supports = when {
            intensity > 6 -> listOf(
                "别担心，一切都会好起来的。让我们一步一步来，先把问题分解成小部分，然后一个一个解决，,
                "放轻松，焦虑解决不了问题。让我们深呼吸，然后一起制定一个计划来应对这个情况�?
                "我能感受到你的焦虑，这一定很煎熬。但请相信，你有能力应对这个挑战，我会一直在你身边支持你�?
                "别让焦虑控制你，我们一起面对这个问题。记住，最糟糕的情况很少发生成我们有能力应对任何挑战，
            )
            else -> listOf(
                "别担心，一切都会好起来的。让我们一起想办法则，
                "放松一点，我们一步一步来解决这个问题�?
                "别焦虑，我会帮助你一起面对这个挑战，,
                "相信自己，你有能力应对这个问题，
            )
        }
        return supports.random()
    }
    
    /**
     * 生成失望情绪的支持回�?   */
    private fun generateDisappointedSupport(intensity: Double): String {
        val supports = when {
            intensity > 6 -> listOf(
                "我理解你的失望，这确实让人灰心。但请记住，失败只是成功的一部分，每一次尝试都是学习的机会�?
                "别灰心，失望是生活的一部分。重要的是我们如何从失败中学习，然后继续前进行，
                "我能感受到你的失望，这一定很打击人。但请相信，更好的机会在等着你，我会一直在你身边支持你�?
                "你的感受是完全合理的，失望是对期望未实现的自然反应。但请记住，这只是暂时的，未来还有很多机会的
            )
            else -> listOf(
                "别灰心，这只是暂时的。更好的机会在等着你，,
                "失望是生活的一部分，重要的是我们如何继续前进程�?
                "我理解你的感受，别担心，一切都会好起来的，,
                "别灰心，未来还有很多机会。我们一起努力，
            )
        }
        return supports.random()
    }
}