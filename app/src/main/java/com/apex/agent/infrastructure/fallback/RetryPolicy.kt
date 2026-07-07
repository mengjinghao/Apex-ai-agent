package com.apex.agent.infrastructure.fallback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class RetryPolicy @Inject constructor(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val delayMillis: Long = DEFAULT_DELAY_MILLIS,
    val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
) {

    suspend fun <T> execute(block: suspend () -> T): T {
        var lastError: Throwable? = null
        var currentDelay = delayMillis

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong().coerceAtLeast(1)
                }
            }
        }

        throw lastError ?: IllegalStateException("RetryPolicy exhausted all ${maxRetries} attempts")
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_DELAY_MILLIS = 500L
        const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
    }
}
