package com.apex.agent.infrastructure.fallback

import javax.inject.Inject
import javax.inject.Singleton


    suspend fun <T> execute(action: suspend () -> T, fallback: suspend () -> T): T

    @Singleton
    class Default constructor() : FallbackHandler {

        override suspend fun <T> execute(action: suspend () -> T, fallback: suspend () -> T): T {
            return try {
                action()
            } catch (_: Throwable) {
                fallback()
            }
        }
    }
}
