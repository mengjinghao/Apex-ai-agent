package com.apex.agent.infrastructure.fallback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `execute should return immediately on first success`() = runTest {
        val retryPolicy = RetryPolicy(maxRetries = 3, delayMillis = 0L)

        var callCount = 0
        val result = retryPolicy.execute {
            callCount++
            "success"
        }

        assertEquals("success", result)
        assertEquals(1, callCount)
    }

    @Test
    fun `execute should retry until success`() = runTest {
        val retryPolicy = RetryPolicy(maxRetries = 3, delayMillis = 0L)

        var callCount = 0
        val result = retryPolicy.execute {
            callCount++
            if (callCount < 3) {
                throw RuntimeException("failure $callCount")
            }
            "success"
        }

        assertEquals("success", result)
        assertEquals(3, callCount)
    }
}
