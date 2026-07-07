package com.apex.agent.infrastructure.fallback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackHandlerTest {

    private val fallbackHandler: FallbackHandler = FallbackHandler.Default()

    @Test
    fun `execute should return action result when action succeeds`() = runTest {
        val result = fallbackHandler.execute(
            action = { "success" },
            fallback = { "fallback" }
        )

        assertEquals("success", result)
    }

    @Test
    fun `execute should return fallback result when action fails`() = runTest {
        val result = fallbackHandler.execute(
            action = { throw RuntimeException("action failed") },
            fallback = { "fallback" }
        )

        assertEquals("fallback", result)
    }
}
