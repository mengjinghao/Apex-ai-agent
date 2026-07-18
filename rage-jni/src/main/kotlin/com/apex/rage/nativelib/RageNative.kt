package com.apex.rage.nativelib

/**
 * Low-level JNI surface to the rage-native C++ core (`librage_native.so`).
 *
 * All functions are SYNCHRONOUS and may block for long durations
 * (LLM round-trips, parallel subtask execution, etc.). Callers MUST dispatch
 * them off the main thread — see [RageNativeBridge] for a coroutine-safe
 * wrapper.
 *
 * JNI symbol mangling:
 *   package `com.apex.rage.nativelib` → class `RageNative` →
 *   `Java_com_apex_rage_nativelib_RageNative_<methodName>`.
 *
 * Note: the package was renamed to `nativelib` because AGP rejects the
 * Java reserved word "native" as a namespace segment.
 */
object RageNative {

    @Volatile
    private var loaded: Boolean = false

    init {
        try {
            System.loadLibrary("rage_native")
            loaded = true
        } catch (t: Throwable) {
            // Native library unavailable — calls will throw UnsatisfiedLinkError.
            // We swallow here and let callers handle the per-method error.
            loaded = false
        }
    }

    /** True iff `librage_native.so` was successfully loaded. */
    fun isLoaded(): Boolean = loaded

    /**
     * Initialize the C++ core with a [NativeCallbacks] instance. Must be called
     * once before any other native method. Idempotent: re-init replaces the
     * callback registry and core singletons.
     *
     * @return `true` on success, `false` if the callback method lookup failed.
     */
    external fun nativeInit(callback: NativeCallbacks): Boolean

    /**
     * Execute a task synchronously.
     *
     * @param taskJson   JSON-serialized [NativeTask]
     * @param configJson JSON-serialized [NativeRageConfig]
     * @return JSON-serialized [NativeExecutionResult]
     */
    external fun nativeStartTask(taskJson: String, configJson: String): String

    /**
     * Request cancellation of an in-flight task. The orchestrator checks the
     * cancellation flag at safe points (between agents / between subtasks) and
     * returns a CANCELLED result.
     *
     * @return `true` if the cancellation flag was set.
     */
    external fun nativeCancelTask(taskId: String): Boolean

    /**
     * Snapshot the global metrics counter.
     *
     * @return JSON-serialized [NativeMetrics]
     */
    external fun nativeGetMetrics(): String

    /**
     * Tear down the C++ core: stop the scheduler, free singletons, release the
     * global ref to the callback. After this, [nativeInit] must be called again
     * before any other native method.
     */
    external fun nativeDestroy()
}
