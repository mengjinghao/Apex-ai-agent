package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class BruteForceUISkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var ctx: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val approachCache = ConcurrentHashMap<String, List<UIApproach>>()

    data class UIApproach(
        val name: String,
        val toolName: String,
        val approachType: ApproachType,
        val priority: Int
    )

    enum class ApproachType {
        COORDINATE,     // 坐标点击
        TEXT_MATCH,     // 文本匹配
        ACCESSIBILITY,  // 无障碍
        SWIPE,          // 滑动
        KEYBOARD,       // 键盘
        INTENT,         // Intent
        ADB_RAW,        // ADB 原始命令
        OCR,            // 图像识别
        GESTURE         // 手势
    }

    init {
        manifest = BurstSkillManifest(
            skillId = "brute_force_ui",
            skillName = "暴力UI引擎",
            version = "1.0.0",
            description = "尝试所有可能的UI交互方式 — 坐标、文本、无障碍、Intent、ADB原始命令等",
            author = "Apex Agent",
            tags = listOf("berserk", "ui", "brute-force", "accessibility"),
            priority = 85,
            capabilities = listOf(
                "multi_approach_ui",
                "coordinate_interaction",
                "text_match_interaction",
                "accessibility_interaction",
                "adb_raw_command",
                "intent_injection",
                "gesture_emulation"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.ctx = context
        buildApproaches()
    }

    private fun buildApproaches() {
        approachCache["click"] = listOf(
            UIApproach("accessibility_click", "accessibility_click", ApproachType.ACCESSIBILITY, 10),
            UIApproach("click_element", "click_element", ApproachType.COORDINATE, 20),
            UIApproach("tap", "tap", ApproachType.COORDINATE, 30),
            UIApproach("long_press", "long_press", ApproachType.COORDINATE, 40),
            UIApproach("text_click", "text_click", ApproachType.TEXT_MATCH, 50),
            UIApproach("press_key", "press_key", ApproachType.KEYBOARD, 60),
            UIApproach("adb_tap", "adb_tap", ApproachType.ADB_RAW, 70),
            UIApproach("intent_click", "intent_click", ApproachType.INTENT, 80),
            UIApproach("ocr_click", "ocr_click", ApproachType.OCR, 90)
        )
        approachCache["input"] = listOf(
            UIApproach("set_text", "set_text", ApproachType.ACCESSIBILITY, 10),
            UIApproach("input_text", "input_text", ApproachType.TEXT_MATCH, 20),
            UIApproach("type_text", "type_text", ApproachType.KEYBOARD, 30),
            UIApproach("paste_text", "paste_text", ApproachType.KEYBOARD, 40),
            UIApproach("adb_input", "adb_input", ApproachType.ADB_RAW, 50),
            UIApproach("keyboard_type", "keyboard_type", ApproachType.KEYBOARD, 60)
        )
        approachCache["scroll"] = listOf(
            UIApproach("scroll_to", "scroll_to", ApproachType.ACCESSIBILITY, 10),
            UIApproach("swipe", "swipe", ApproachType.SWIPE, 20),
            UIApproach("fling", "fling", ApproachType.GESTURE, 30),
            UIApproach("adb_swipe", "adb_swipe", ApproachType.ADB_RAW, 40)
        )
        approachCache["navigate"] = listOf(
            UIApproach("navigate", "navigate", ApproachType.INTENT, 10),
            UIApproach("open_app", "open_app", ApproachType.INTENT, 20),
            UIApproach("adb_am_start", "adb_am_start", ApproachType.ADB_RAW, 30),
            UIApproach("deep_link", "deep_link", ApproachType.INTENT, 40)
        )
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        try {
            val operation = task.metadata["operation"] ?: "brute_force"

            when (operation) {
                "brute_force" -> bruteForceUI(task, startTime)
                "search_element" -> searchElement(task, startTime)
                else -> BurstSkillResult(
                    success = false,
                    errorMessage = "Unknown UI operation: $operation",
                    metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = "BruteForceUI failed: ${e.message}",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }
    }

    private suspend fun bruteForceUI(task: BurstTask, startTime: Long): BurstSkillResult {
        val interaction = task.metadata["interaction"] ?: "click"
        val approaches = approachCache[interaction] ?: return BurstSkillResult(
            success = false,
            errorMessage = "No approaches for interaction type: $interaction",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )

        val results = ConcurrentHashMap<String, BurstSkillResult>()

        for (approach in approaches.sortedBy { it.priority }) {
            if (isPaused) break

            val approachTask = task.copy(
                id = "${task.id}_ui_${approach.name}",
                metadata = task.metadata + mapOf(
                    "toolName" to approach.toolName,
                    "approachType" to approach.approachType.name,
                    "approachName" to approach.name
                )
            )
            val result = ctx.kernel.executeSkill("tool_${approach.toolName}", approachTask)
            results[approach.name] = result

            if (result.success) {
                return BurstSkillResult(
                    success = true,
                    output = "[UI WINNER: ${approach.name} (${approach.approachType})] ${result.output}",
                    metrics = SkillMetrics(
                        executionTimeMs = System.currentTimeMillis() - startTime,
                        stepsCompleted = results.size
                    )
                )
            }
        }

        val summary = results.entries.joinToString("\n") { (name, r) ->
            "  $name: ${r.errorMessage ?: "unknown error"}"
        }
        return BurstSkillResult(
            success = false,
            output = "All ${approaches.size} UI approaches failed for $interaction",
            errorMessage = summary,
            metrics = SkillMetrics(
                executionTimeMs = System.currentTimeMillis() - startTime,
                stepsCompleted = results.size
            )
        )
    }

    private suspend fun searchElement(task: BurstTask, startTime: Long): BurstSkillResult {
        val queries = listOf(
            mapOf("by" to "text", "value" to (task.metadata["text"] ?: "")),
            mapOf("by" to "content_desc", "value" to (task.metadata["contentDesc"] ?: "")),
            mapOf("by" to "resource_id", "value" to (task.metadata["resourceId"] ?: "")),
            mapOf("by" to "class_name", "value" to (task.metadata["className"] ?: "")),
            mapOf("by" to "xpath", "value" to (task.metadata["xpath"] ?: "")),
            mapOf("by" to "bounds", "value" to (task.metadata["bounds"] ?: ""))
        )

        val results = queries.map { query ->
            val searchTask = task.copy(
                id = "${task.id}_search_${query["by"]}",
                metadata = task.metadata + query
            )
            val result = ctx.kernel.executeSkill("tool_search_element", searchTask)
            Pair(query["by"], result)
        }

        val firstFound = results.firstOrNull { (_, r) -> r.success }
        if (firstFound != null) {
            return BurstSkillResult(
                success = true,
                output = "[Found by ${firstFound.first}] ${firstFound.second.output}",
                metrics = SkillMetrics(
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    stepsCompleted = results.size
                )
            )
        }

        return BurstSkillResult(
            success = false,
            errorMessage = "Element not found by any query method",
            metrics = SkillMetrics(
                executionTimeMs = System.currentTimeMillis() - startTime,
                stepsCompleted = results.size
            )
        )
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel(); approachCache.clear() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.83f
}
