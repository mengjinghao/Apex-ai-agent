package com.apex.api.chat.llmprovider

import com.apex.data.model.ApiProviderType
import java.net.URL

/**
 * ç¨äºèªå¨è¡¥å¨APIç«¯ç¹URLçå·¥å·ç±»å?*/
object EndpointCompleter {

    /**
     * ä¸ºç±»ä¼¼OpenAIçæå¡èªå¨è¡¥å¨APIç«¯ç¹URLï¼?    * - å¦æç«¯ç¹æ¯ä¸ä¸ªåºç¡URLï¼ä¾ï¼https://api.example.comï¼ï¼å®ä¼èªå¨éå éç¨çè·¯å¾`/v1/chat/completions`ï¼?    * - å¦æç«¯ç¹è·¯å¾ï¼`/v1` ç»å°¾ï¼ä¾ï¼https://my-proxy/custom/v1ï¼ï¼åä¼èªå¨éå  `/chat/completions`ï¼?    * ç¨æ·å¯ä»¥å¨URLæ«å°¾æ·»å  '#' æ¥ç¦ç¨æ­¤åè½ç?    *
     * @param endpoint ç¨æ·æä¾çç«¯ç¹URLï¼?    * @return è¡¥å¨åçæåå§çç«¯ç¹URLï¼?    */
    fun completeEndpoint(endpoint: String): String {
        val trimmedEndpoint = endpoint.trim()
        if (trimmedEndpoint.endsWith("#")) {
            return trimmedEndpoint.removeSuffix("#")
        }
        val endpointWithoutSlash = trimmedEndpoint.removeSuffix("/")

        // å°è¯è§£æURLå¹¶å¤æ­å®æ¯å¦ä¸ºä¸ä¸ªéè¦è¡¥å¨çURL
    try {
            // ä½¿ç¨åå«å°¾é¨ææ çç«¯ç¹è¿è¡è§£æï¼ä»¥æ­£ç¡®è¯å«è·¯å¾?
    val url = URL(trimmedEndpoint)
        val path = url.path.removeSuffix("/")

            // 1. å¦æè·¯å¾ä¸ºç©º (e.g., https://api.example.com)ï¼åè¡¥å¨ä¸ºæ åè·¯å¾?
    if (path.isNullOrEmpty()) {
                return "${endpointWithoutSlash}/v1/chat/completions"
            }

            // 2. å¦æè·¯å¾ï¼?v1 ç»å°¾ (e.g., https://api.example.com/custom/v1)ï¼åä»è¡¥å¨åç»­é¨ï¼?
    if (path.endsWith("/v1", ignoreCase = true)) {
                return "${endpointWithoutSlash}/chat/completions"
            }
        } catch (e: Exception) {
            // å¦æä¸æ¯ä¸ä¸ªææçURLï¼åä¸è¿è¡ä»»ä½æä½?       }
        
        // å¦æä¸ç¬¦åè¡¥å¨ç¹å¾ï¼åè¿ååå§è¾å?
    return endpoint
    }
        private fun completeResponsesEndpoint(endpoint: String): String {
        val trimmedEndpoint = endpoint.trim()
        if (trimmedEndpoint.endsWith("#")) {
            return trimmedEndpoint.removeSuffix("#")
        }
        val endpointWithoutSlash = trimmedEndpoint.removeSuffix("/")

        try {
            val url = URL(trimmedEndpoint)
        val path = url.path.removeSuffix("/")
        if (path.isEmpty()) {
                return "${endpointWithoutSlash}/v1/responses"
            }
        if (path.endsWith("/v1", ignoreCase = true)) {
                return "${endpointWithoutSlash}/responses"
            }
        } catch (_: Exception) {
        }
        return endpoint
    }
        fun completeEndpoint(endpoint: String, providerType: ApiProviderType): String {
        val trimmedEndpoint = endpoint.trim()
        if (trimmedEndpoint.endsWith("#")) {
            return trimmedEndpoint.removeSuffix("#")
        }
        val endpointWithoutSlash = trimmedEndpoint.removeSuffix("/")
        when (providerType) {
            ApiProviderType.OPENAI_RESPONSES,
            ApiProviderType.OPENAI_RESPONSES_GENERIC -> {
                return completeResponsesEndpoint(endpoint)
            }

            ApiProviderType.ANTHROPIC,
            ApiProviderType.ANTHROPIC_GENERIC -> {
                try {
                    val url = URL(trimmedEndpoint)
        val path = url.path.removeSuffix("/")
        if (path.isEmpty()) {
                        return "${endpointWithoutSlash}/v1/messages"
                    }
        if (path.endsWith("/anthropic", ignoreCase = true)) {
                        return "${endpointWithoutSlash}/v1/messages"
                    }
        if (path.endsWith("/v1", ignoreCase = true)) {
                        return "${endpointWithoutSlash}/messages"
                    }
                } catch (e: Exception) {
                    // å¦æä¸æ¯ä¸ä¸ªææçURLï¼åä¸è¿è¡ä»»ä½æä½?               }
        return endpoint
            }

            ApiProviderType.GOOGLE,
            ApiProviderType.GEMINI_GENERIC -> {
                return endpoint
            }

            else -> {
                return completeEndpoint(endpoint)
            }
        }
    }
}
