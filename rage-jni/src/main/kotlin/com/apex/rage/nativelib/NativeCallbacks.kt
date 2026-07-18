package com.apex.rage.nativelib

/**
 * C++ → Kotlin callback interface.
 *
 * The C++ core (rage-native) calls back into Kotlin via these methods through
 * JNI (`AttachCurrentThread` + `CallObjectMethod`). Implementations MUST be
 * thread-safe — the callbacks fire from C++ worker threads, not the calling
 * thread.
 *
 * The LLM and search callbacks are SYNCHRONOUS from C++'s perspective: the C++
 * thread blocks until the Kotlin method returns. Implementations should
 * therefore dispatch the actual work to a coroutine dispatcher (see
 * [RageNativeBridge]) and block the calling thread via `runBlocking` /
 * `CountDownLatch` until the result is ready.
 *
 * All callbacks must handle their own exceptions — throwing across the JNI
 * boundary is logged-and-cleared by the C++ side and the callback returns an
 * empty result (treated as "call failed — continue best-effort").
 */
interface NativeCallbacks {

    /**
     * Observation event from the C++ orchestrator: task lifecycle, agent step,
     * blackboard update, or the request half of an LLM/search call. Implement
     * by feeding into a [kotlinx.coroutines.flow.SharedFlow] for UI consumption.
     *
     * @param eventJson JSON-serialized [NativeEvent]
     */
    fun onEvent(eventJson: String)

    /**
     * Synchronous LLM invocation. The C++ thread blocks until this returns.
     *
     * @param prompt       user prompt
     * @param systemPrompt optional system prompt (may be null)
     * @return LLM completion text (empty string on failure)
     */
    fun onLlmRequest(prompt: String, systemPrompt: String?): String

    /**
     * Synchronous search invocation. The C++ thread blocks until this returns.
     *
     * @param query search query
     * @return search results text (empty string on failure — non-fatal)
     */
    fun onSearchRequest(query: String): String
}
