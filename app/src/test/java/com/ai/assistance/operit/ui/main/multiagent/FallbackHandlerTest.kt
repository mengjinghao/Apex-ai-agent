package com.ai.assistance.`Apex agent`.ui.main.multiagent

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class FallbackHandlerTest {

    private val fallbackHandler = FallbackHandler()

    @Test
    fun `test executeWithFallback should execute task successfully`() {
        val sessionId = "test_session_1"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        val result = fallbackHandler.executeWithFallback(sessionId) {
            "Success"
        }

        assertEquals("Success", result)
    }

    @Test
    fun `test executeWithFallback should handle timeout`() {
        val sessionId = "test_session_2"
        fallbackHandler.createSessionConfig(sessionId, 100, 3) // 100ms超时

        var fallbackTriggered = false
        var fallbackReason = ""

        val listenerId = fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {
                fallbackTriggered = true
                fallbackReason = reason
            }

            override fun onGlobalShutdown() {}

            override fun onSessionShutdown(sessionId: String) {}
        })

        val result = fallbackHandler.executeWithFallback(sessionId) {
            Thread.sleep(200) // 超过超时时间
            "Success"
        }

        assertNull(result)
        assertTrue(fallbackTriggered)
        assertTrue(fallbackReason.contains("任务执行超时"))

        fallbackHandler.unregisterListener(listenerId)
    }

    @Test
    fun `test executeWithFallback should handle exception`() {
        val sessionId = "test_session_3"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        var fallbackTriggered = false
        var fallbackReason = ""

        val listenerId = fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {
                fallbackTriggered = true
                fallbackReason = reason
            }

            override fun onGlobalShutdown() {}

            override fun onSessionShutdown(sessionId: String) {}
        })

        val result = fallbackHandler.executeWithFallback(sessionId) {
            throw Exception("Test exception")
        }

        assertNull(result)
        assertTrue(fallbackTriggered)
        assertTrue(fallbackReason.contains("任务执行异常"))

        fallbackHandler.unregisterListener(listenerId)
    }

    @Test
    fun `test executeWithRetry should retry on failure`() {
        val sessionId = "test_session_4"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        var retryCount = 0

        val result = fallbackHandler.executeWithRetry(sessionId, {
            retryCount++
            if (retryCount < 2) {
                throw Exception("Test exception")
            }
            "Success"
        }, 3)

        assertEquals("Success", result)
        assertEquals(2, retryCount)
    }

    @Test
    fun `test executeWithRetry should fail after max retries`() {
        val sessionId = "test_session_5"
        fallbackHandler.createSessionConfig(sessionId, 1000, 2)

        var retryCount = 0
        var fallbackTriggered = false

        val listenerId = fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {
                fallbackTriggered = true
            }

            override fun onGlobalShutdown() {}

            override fun onSessionShutdown(sessionId: String) {}
        })

        val result = fallbackHandler.executeWithRetry(sessionId, {
            retryCount++
            throw Exception("Test exception")
        }, 2)

        assertNull(result)
        assertEquals(3, retryCount) // 初始执行 + 2次重试
        assertTrue(fallbackTriggered)

        fallbackHandler.unregisterListener(listenerId)
    }

    @Test
    fun `test setGlobalEnabled should disable all sessions`() {
        val sessionId = "test_session_6"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        var globalShutdownTriggered = false

        val listenerId = fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {}

            override fun onGlobalShutdown() {
                globalShutdownTriggered = true
            }

            override fun onSessionShutdown(sessionId: String) {}
        })

        fallbackHandler.setGlobalEnabled(false)

        assertTrue(globalShutdownTriggered)
        assertFalse(fallbackHandler.isGlobalEnabled())

        // 测试全局关闭后任务执行
        val result = fallbackHandler.executeWithFallback(sessionId) {
            "Success"
        }

        assertNull(result)

        fallbackHandler.unregisterListener(listenerId)
    }

    @Test
    fun `test setSessionEnabled should disable specific session`() {
        val sessionId = "test_session_7"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        var sessionShutdownTriggered = false

        val listenerId = fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {}

            override fun onGlobalShutdown() {}

            override fun onSessionShutdown(sessionId: String) {
                sessionShutdownTriggered = true
            }
        })

        fallbackHandler.setSessionEnabled(sessionId, false)

        assertTrue(sessionShutdownTriggered)

        // 测试会话关闭后任务执行
        val result = fallbackHandler.executeWithFallback(sessionId) {
            "Success"
        }

        assertNull(result)

        fallbackHandler.unregisterListener(listenerId)
    }

    @Test
    fun `test shutdownAll should close all resources`() {
        val sessionId = "test_session_8"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        var globalShutdownTriggered = false

        val listenerId = fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {}

            override fun onGlobalShutdown() {
                globalShutdownTriggered = true
            }

            override fun onSessionShutdown(sessionId: String) {}
        })

        fallbackHandler.shutdownAll()

        assertTrue(globalShutdownTriggered)
        assertFalse(fallbackHandler.isGlobalEnabled())

        fallbackHandler.unregisterListener(listenerId)
    }

    @Test
    fun `test cleanup should clear all resources`() {
        val sessionId = "test_session_9"
        fallbackHandler.createSessionConfig(sessionId, 1000, 3)

        fallbackHandler.registerListener(object : FallbackHandler.FallbackListener {
            override fun onFallbackTriggered(sessionId: String, reason: String, fallbackType: FallbackHandler.FallbackType) {}
            override fun onGlobalShutdown() {}
            override fun onSessionShutdown(sessionId: String) {}
        })

        fallbackHandler.cleanup()

        // 测试清理后任务执行
        val result = fallbackHandler.executeWithFallback(sessionId) {
            "Success"
        }

        assertNull(result)
    }
}

