package com.apex.rage.nativelib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Coroutine ↔ C++-thread bridge.
 *
 * The C++ core (`librage_native.so`) is fully synchronous: [RageNative.nativeStartTask]
 * blocks the calling thread until the task completes (or is cancelled). It
 * calls back into Kotlin via [NativeCallbacks] from C++ worker threads to:
 *   - request LLM completions (synchronous — C++ blocks waiting for the response)
 *   - request search results (synchronous — best-effort)
 *   - emit observation events (asynchronous — fire-and-forget)
 *
 * This bridge:
 *   - Adapts a `suspend` LLM invoker to the synchronous JNI contract by
 *     `runBlocking`-ing the C++ calling thread until the coroutine completes.
 *   - Forwards observation events into a [SharedFlow] for UI consumption.
 *   - Routes the actual `nativeStartTask` call to [Dispatchers.Default] so the
 *     caller's coroutine is not blocked.
 *
 * Thread-safety: the bridge is safe to use from multiple coroutines
 * concurrently. Each task gets a unique [NativeTask.id]; the bridge tracks
 * per-task latches for LLM callback correlation.
 *
 * @param llmInvoker suspend LLM invoker — typically adapts :app's LLMProvider
 * @param scope       coroutine scope for fan-out event emission (NOT for
 *                    blocking native calls — those run on Dispatchers.Default)
 */
class RageNativeBridge(
    private val llmInvoker: suspend (String, String?) -> String,
    private val scope: CoroutineScope
) {
    /** Stream of observation events emitted by the C++ core. */
    private val _events = MutableSharedFlow<NativeEvent>(
        extraBufferCapacity = 256
    )
    val events: SharedFlow<NativeEvent> = _events.asSharedFlow()

    /** Non-null once [init] has succeeded. */
    private val initialized = AtomicReference<Boolean>(false)

    /** Per-task latches — currently unused for LLM correlation but kept for
     *  future per-task cancellation tokens. */
    private val taskLatches = ConcurrentHashMap<String, CountDownLatch>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** Callback instance handed to native code. Held as a strong ref for the
     *  lifetime of the bridge so the JNI global ref stays valid. */
    private val callbacks = object : NativeCallbacks {
        override fun onEvent(eventJson: String) {
            // Parse + emit on the bridge's scope to avoid blocking the C++ thread.
            runCatching {
                val ev = json.decodeFromString<NativeEvent>(eventJson)
                _events.tryEmit(ev)
            }
        }

        override fun onLlmRequest(prompt: String, systemPrompt: String?): String {
            // The C++ thread is attached to the JVM (see rage_jni.cpp).
            // runBlocking here is safe — it parks the (already-attached)
            // native thread on a JVM event loop. We explicitly switch to
            // Dispatchers.Default so the actual LLM call runs on a coroutine
            // dispatcher thread, not the C++ worker.
            return runBlocking {
                runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        llmInvoker(prompt, systemPrompt)
                    }
                }.getOrElse { "" }
            }
        }

        override fun onSearchRequest(query: String): String {
            // Search is best-effort; if the host doesn't override, return empty.
            // The bridge does NOT impose a search implementation — host APKs
            // that want search should wrap RageNativeBridge and inject a
            // search invoker via subclassing or a future constructor param.
            return ""
        }
    }

    /**
     * Initialize the C++ core. Idempotent.
     *
     * @return `true` if init succeeded (or was already initialized).
     */
    fun init(): Boolean {
        if (initialized.get()) return true
        if (!RageNative.isLoaded()) return false
        val ok = RageNative.nativeInit(callbacks)
        initialized.set(ok)
        return ok
    }

    /**
     * Start a task. Suspends until the C++ core returns a result.
     *
     * Implementation: dispatches the (synchronous) `nativeStartTask` call to
     * [Dispatchers.Default], so the calling coroutine is suspended (not
     * blocked) while the C++ thread executes the orchestrator loop.
     */
    suspend fun startTask(task: NativeTask, config: NativeRageConfig): NativeExecutionResult {
        if (!init()) {
            return NativeExecutionResult(
                success = false,
                errorMessage = "RageNative library not loaded or init failed",
                taskId = task.id
            )
        }
        val taskJson   = json.encodeToString(NativeTask.serializer(), task)
        val configJson = json.encodeToString(NativeRageConfig.serializer(), config)

        val raw = kotlinx.coroutines.withContext(Dispatchers.Default) {
            // Synchronous JNI call — runs on a Default dispatcher thread, not
            // the caller's. The C++ orchestrator will call back into our
            // NativeCallbacks (potentially on different C++ worker threads).
            RageNative.nativeStartTask(taskJson, configJson)
        }
        return runCatching {
            json.decodeFromString(NativeExecutionResult.serializer(), raw)
        }.getOrElse {
            NativeExecutionResult(
                success = false,
                errorMessage = "Failed to parse C++ result JSON: ${it.message}",
                taskId = task.id
            )
        }
    }

    /**
     * Request cancellation of an in-flight task. Non-blocking.
     */
    fun cancelTask(taskId: String): Boolean {
        if (!initialized.get()) return false
        return runCatching { RageNative.nativeCancelTask(taskId) }.getOrElse { false }
    }

    /**
     * Snapshot the global metrics counter. Non-blocking (C++ just reads atomics).
     */
    fun getMetrics(): NativeMetrics {
        if (!initialized.get()) return NativeMetrics()
        val raw = runCatching { RageNative.nativeGetMetrics() }.getOrElse { return NativeMetrics() }
        return runCatching {
            json.decodeFromString(NativeMetrics.serializer(), raw)
        }.getOrElse { NativeMetrics() }
    }

    /**
     * Tear down the C++ core. Safe to call multiple times.
     */
    fun destroy() {
        if (!initialized.compareAndSet(true, false)) return
        runCatching { RageNative.nativeDestroy() }
        taskLatches.clear()
    }
}
