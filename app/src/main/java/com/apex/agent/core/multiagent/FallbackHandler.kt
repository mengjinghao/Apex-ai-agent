
package com.apex.agent.core.multiagent

class FallbackHandler {

    data class FallbackConfig(
        val maxRetries: Int = 3,
        val retryDelayMs: Long = 1000,
        val exponentialBackoff: Boolean = true
    )

    private var config = FallbackConfig()

    fun configure(config: FallbackConfig) {
        this.config = config
    }

    suspend fun <T> executeWithFallback(
        action: suspend () -> T,
        fallback: suspend () -> T
    ): T {
        var lastError: Exception? = null
        repeat(config.maxRetries) { attempt ->
            try {
                return action()
            } catch (e: Exception) {
                lastError = e
                if (config.exponentialBackoff) {
                    kotlinx.coroutines.delay(config.retryDelayMs * (1L shl attempt))
                }
            }
        }
        return fallback()
    }
}
