package com.apex.agent.test

import android.content.Context
import com.apex.agent.core.hooks.HookRegistry
import com.apex.agent.core.hooks.PreCompactHook
import com.apex.agent.core.hooks.SessionContext
import com.apex.agent.core.hooks.SessionEndHook
import com.apex.agent.core.hooks.SessionStartHook
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 会话生命周期钩子集成测试
 * 验证钩子注册、触发、checkpoint 保存/恢复、会话摘要生成等功能
 */
object HookIntegrationTest {

    private const val TAG = "HookIntegrationTest"

    /**
     * 运行所有集成测�?     * @param context Android Context
     * @return 测试结果报告
     */
    suspend fun runAllTests(context: Context): TestReport = withContext(Dispatchers.IO) {
        val report = TestReport()

        try {
            AppLogger.i(TAG, "========== 开始会话生命周期钩子集成测�?==========")

            // 测试 1: 钩子注册
            report.addResult(testHookRegistration(context))

            // 测试 2: 会话启动钩子触发
            report.addResult(testSessionStartHook(context))

            // 测试 3: PreCompact 钩子触发�?checkpoint 保存
            report.addResult(testPreCompactHookAndCheckpoint(context))

            // 测试 4: Checkpoint 恢复
            report.addResult(testCheckpointRestore(context))

            // 测试 5: 会话结束钩子和摘要生�?            report.addResult(testSessionEndHookAndSummary(context))

            // 测试 6: 钩子注销
            report.addResult(testHookUnregistration(context))

            AppLogger.i(TAG, "========== 测试完成 ==========")
            AppLogger.i(TAG, "总计: ${report.totalTests}, 通过: ${report.passedTests}, 失败: ${report.failedTests}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "测试执行失败", e)
            report.addResult(TestResult("整体测试", false, "测试执行异常: ${e.message}"))
        }

        report
    }

    /**
     * 测试 1: 钩子注册
     */
    private suspend fun testHookRegistration(context: Context): TestResult {
        val testName = "钩子注册测试"
        return try {
            AppLogger.i(TAG, "[${testName}] 开始测�?)

            // 清空现有钩子
            HookRegistry.clearAll()

            // 注册三个钩子
            val sessionStartHook = SessionStartHook()
            val preCompactHook = PreCompactHook()
            val sessionEndHook = SessionEndHook()

            HookRegistry.register(sessionStartHook)
            HookRegistry.register(preCompactHook)
            HookRegistry.register(sessionEndHook)

            // 验证注册成功（通过触发钩子来间接验证）
            val testContext = SessionContext(
                sessionId = "test-registration-${UUID.randomUUID()}",
                startTime = System.currentTimeMillis(),
                lastActivity = System.currentTimeMillis(),
                messageCount = 0,
                tokenUsage = 0,
                environmentState = emptyMap()
            )

            // 触发钩子，如果没有异常说明注册成�?            HookRegistry.triggerSessionStart(context, testContext)
            HookRegistry.triggerPreCompact(context, testContext)
            HookRegistry.triggerSessionEnd(context, testContext)

            AppLogger.i(TAG, "[${testName}] �测试通过")
            TestResult(testName, true, "成功注册并触�?3 个钩�?)

        } catch (e: Exception) {
            AppLogger.e(TAG, "[${testName}] �测试失败", e)
            TestResult(testName, false, "注册失败: ${e.message}")
        }
    }

    /**
     * 测试 2: 会话启动钩子触发
     */
    private suspend fun testSessionStartHook(context: Context): TestResult {
        val testName = "会话启动钩子测试"
        return try {
            AppLogger.i(TAG, "[${testName}] 开始测�?)

            val sessionId = "test-start-${UUID.randomUUID()}"
            val sessionContext = SessionContext(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                lastActivity = System.currentTimeMillis(),
                messageCount = 0,
                tokenUsage = 0,
                environmentState = mapOf(
                    "testKey" to "testValue"
                )
            )

            // 触发会话启动钩子
            HookRegistry.triggerSessionStart(context, sessionContext)

            // SessionStartHook 会检测环境状态并加载上次会话摘要
            // 这里主要验证钩子能够正常触发且不抛出异常

            AppLogger.i(TAG, "[${testName}] �测试通过")
            TestResult(testName, true, "会话启动钩子成功触发，sessionId: ${sessionId}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "[${testName}] �测试失败", e)
            TestResult(testName, false, "触发失败: ${e.message}")
        }
    }

    /**
     * 测试 3: PreCompact 钩子触发�?checkpoint 保存
     */
    private suspend fun testPreCompactHookAndCheckpoint(context: Context): TestResult {
        val testName = "PreCompact 钩子�?Checkpoint 保存测试"
        return try {
            AppLogger.i(TAG, "[${testName}] 开始测�?)

            val sessionId = "test-precompact-${UUID.randomUUID()}"
            val sessionContext = SessionContext(
                sessionId = sessionId,
                startTime = System.currentTimeMillis() - 3600000, // 1小时�?                lastActivity = System.currentTimeMillis(),
                messageCount = 50,
                tokenUsage = 10000,
                environmentState = mapOf(
                    "pendingTasks" to "[\"task1\", \"task2\"]",
                    "importantDecisions" to "[{\"decision\":\"use Kotlin\",\"reason\":\"type safety\"}]",
                    "keyVariables" to "{\"userId\":\"12345\",\"sessionType\":\"test\"}"
                )
            )

            // 触发 PreCompact 钩子
            val checkpointData = HookRegistry.triggerPreCompact(context, sessionContext)

            // 验证返回�?checkpoint 数据
            if (checkpointData.isEmpty()) {
                throw Exception("Checkpoint 数据为空")
            }

            AppLogger.d(TAG, "[${testName}] Checkpoint 数据: ${checkpointData.keys}")

            // 验证 checkpoint 文件是否创建
            val checkpointFile = File(context.filesDir, "session_checkpoint_${sessionId}.json")
            if (!checkpointFile.exists()) {
                throw Exception("Checkpoint 文件未创�? ${checkpointFile.absolutePath}")
            }

            val fileContent = checkpointFile.readText()
            AppLogger.d(TAG, "[${testName}] Checkpoint 文件内容: ${fileContent}")

            // 验证文件内容包含关键字段
            if (!fileContent.contains("sessionId")) {
                throw Exception("Checkpoint 文件缺少 sessionId 字段")
            }

            AppLogger.i(TAG, "[${testName}] �测试通过")
            TestResult(testName, true, "成功保存 checkpoint，文�? ${checkpointFile.name}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "[${testName}] �测试失败", e)
            TestResult(testName, false, "测试失败: ${e.message}")
        }
    }

    /**
     * 测试 4: Checkpoint 恢复
     */
    private suspend fun testCheckpointRestore(context: Context): TestResult {
        val testName = "Checkpoint 恢复测试"
        return try {
            AppLogger.i(TAG, "[${testName}] 开始测�?)

            // 先创建一�?checkpoint
            val sessionId = "test-restore-${UUID.randomUUID()}"
            val preCompactHook = PreCompactHook()

            val originalData = mapOf(
                "sessionId" to sessionId,
                "messageCount" to 100,
                "tokenUsage" to 20000L,
                "environmentState" to mapOf("key1" to "value1", "key2" to "value2")
            )

            // 保存 checkpoint
            val checkpointFile = File(context.filesDir, "session_checkpoint_${sessionId}.json")
            val jsonContent = org.json.JSONObject(originalData).toString(2)
            checkpointFile.writeText(jsonContent)

            AppLogger.d(TAG, "[${testName}] 已创建测�?checkpoint: ${checkpointFile.name}")

            // 恢复 checkpoint
            val restoredData = preCompactHook.restoreFromCheckpoint(context, sessionId)

            if (restoredData == null) {
                throw Exception("恢复�?checkpoint 数据为空")
            }

            // 验证恢复的数�?            if (restoredData["sessionId"] != sessionId) {
                throw Exception("sessionId 不匹�? 期望 ${sessionId}, 实际 ${restoredData["sessionId"]}")
            }

            if (restoredData["messageCount"] != 100) {
                throw Exception("messageCount 不匹�?)
            }

            AppLogger.d(TAG, "[${testName}] 恢复的数�? ${restoredData.keys}")

            // 清理测试文件
            checkpointFile.delete()

            AppLogger.i(TAG, "[${testName}] �测试通过")
            TestResult(testName, true, "成功恢复 checkpoint，sessionId: ${sessionId}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "[${testName}] �测试失败", e)
            TestResult(testName, false, "恢复失败: ${e.message}")
        }
    }

    /**
     * 测试 5: 会话结束钩子和摘要生�?     */
    private suspend fun testSessionEndHookAndSummary(context: Context): TestResult {
        val testName = "会话结束钩子和摘要生成测�?
        return try {
            AppLogger.i(TAG, "[${testName}] 开始测�?)

            val sessionId = "test-end-${UUID.randomUUID()}"
            val startTime = System.currentTimeMillis() - 7200000 // 2小时�?
            val sessionContext = SessionContext(
                sessionId = sessionId,
                startTime = startTime,
                lastActivity = System.currentTimeMillis(),
                messageCount = 150,
                tokenUsage = 50000,
                environmentState = mapOf(
                    "keyDecisions" to "[{\"decision\":\"use MVVM\",\"reason\":\"separation of concerns\"}]",
                    "learnings" to "[\"Kotlin coroutines are powerful\", \"Room database is fast\"]",
                    "incompleteWork" to "[\"Implement caching\", \"Add unit tests\"]"
                )
            )

            // 触发会话结束钩子
            HookRegistry.triggerSessionEnd(context, sessionContext)

            // SessionEndHook 会生成摘要并保存到文�?            // 验证摘要文件是否创建
            val summaryFile = File(context.filesDir, "session_summary_${sessionId}.json")
            if (!summaryFile.exists()) {
                throw Exception("摘要文件未创�? ${summaryFile.absolutePath}")
            }

            val summaryContent = summaryFile.readText()
            AppLogger.d(TAG, "[${testName}] 摘要文件内容: ${summaryContent}")

            // 验证摘要内容包含关键字段
            val requiredFields = listOf("sessionId", "startTime", "endTime", "duration", "messageCount", "tokenUsage")
            for (field in requiredFields) {
                if (!summaryContent.contains(field)) {
                    throw Exception("摘要文件缺少字段: ${field}")
                }
            }

            // 验证时长计算
            val summaryJson = org.json.JSONObject(summaryContent)
            val duration = summaryJson.getLong("duration")
            if (duration < 7200000) {
                throw Exception("时长计算错误: 期望 >= 7200000ms, 实际 ${duration}ms")
            }

            AppLogger.i(TAG, "[${testName}] �测试通过")
            TestResult(testName, true, "成功生成会话摘要，文�? ${summaryFile.name}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "[${testName}] �测试失败", e)
            TestResult(testName, false, "测试失败: ${e.message}")
        }
    }

    /**
     * 测试 6: 钩子注销
     */
    private suspend fun testHookUnregistration(context: Context): TestResult {
        val testName = "钩子注销测试"
        return try {
            AppLogger.i(TAG, "[${testName}] 开始测�?)

            // 清空所有钩�?            HookRegistry.clearAll()

            // 注册一个钩�?            val hook = SessionStartHook()
            HookRegistry.register(hook)

            // 注销钩子
            HookRegistry.unregister(hook)

            // 触发钩子，应该不会有任何效果（因为没有注册的钩子�?            val testContext = SessionContext(
                sessionId = "test-unregister-${UUID.randomUUID()}",
                startTime = System.currentTimeMillis(),
                lastActivity = System.currentTimeMillis(),
                messageCount = 0,
                tokenUsage = 0,
                environmentState = emptyMap()
            )

            HookRegistry.triggerSessionStart(context, testContext)

            AppLogger.i(TAG, "[${testName}] �测试通过")
            TestResult(testName, true, "成功注销钩子")

        } catch (e: Exception) {
            AppLogger.e(TAG, "[${testName}] �测试失败", e)
            TestResult(testName, false, "注销失败: ${e.message}")
        }
    }

    /**
     * 测试结果数据�?     */
    data class TestResult(
        val testName: String,
        val passed: Boolean,
        val message: String
    )

    /**
     * 测试报告
     */
    class TestReport {
        private val results = mutableListOf<TestResult>()

        val totalTests: Int get() = results.size
        val passedTests: Int get() = results.count { it.passed }
        val failedTests: Int get() = results.count { !it.passed }

        fun addResult(result: TestResult) {
            results.add(result)
        }

        fun getSummary(): String {
            return buildString {
                appendLine("========== 测试报告 ==========")
                appendLine("总计测试: ${totalTests}")
                appendLine("通过: ${passedTests}")
                appendLine("失败: ${failedTests}")
                appendLine()
                appendLine("详细结果:")
                results.forEach { result ->
                    val status = if (result.passed) "�? else "�?
                    appendLine("  ${status} ${result.testName}: ${result.message}")
                }
            }
        }
    }
}
