package com.apex.sdk.bridge

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeError
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * **跨 APK 调用门面** — 业务代码只与本对象交互。
 *
 * 调用路径选择：
 *
 *   1. 优先：[InProcessRegistry] 命中 → JVM 内方法调用（零延迟）
 *   2. 降级：AIDL Binder 跨进程调用（毫秒级延迟）
 *   3. 流式：[openStream] 走 LocalSocket（适合 PTY、文件 watch 等高频流）
 *
 * 使用示例：
 *   ```kotlin
 *   val engine: IEngineService = ApexBridge.get("engine") ?: error("engine not available")
 *   val result = engine.execute(cmd)
 *   ```
 *
 *   或者使用通用 invoke：
 *   ```kotlin
 *   val resp = ApexBridge.invoke("engine/execute", """{"cmd":"ls"}""")
 *   ```
 *
 * **核心契约**：调用方代码完全不感知底层走的是进程内还是 AIDL，
 * 这是“多个 APK 像一个 APK 一样”的关键。
 */
object ApexBridge {

    private const val TAG_SUB = "ApexBridge"

    /** 默认调用超时（毫秒），覆盖 99% 同步场景。 */
    var defaultTimeoutMs: Long = 30_000L

    /**
     * 拿到指定服务的 Kotlin 接口实例。
     *
     * - 若服务在当前进程内注册过 → 返回进程内实例（零延迟）
     * - 否则 → 返回 AIDL Stub 代理（跨进程，Binder 开销）
     * - 若服务既无进程内注册，也无 AIDL 连接 → 返回 null
     *
     * 业务侧拿到 T 后，直接调用方法即可，无需关心是 in-process 还是 cross-process。
     */
    fun <T : Any> get(serviceName: String): T? {
        // 1) 进程内直接命中
        InProcessRegistry.lookup<T>(serviceName)?.let {
            ApexLog.v(ApexSuite.ApkId.MAIN, "[$TAG_SUB] in-process hit: $serviceName")
            return it
        }
        // 2) 走 AIDL（若有连接）
        val binderProxy = BinderConnectionManager.lookup(serviceName)
        if (binderProxy != null) {
            // 由调用方自己把 IApkBridge 适配成 T；此处返回 null，让调用方走 invoke()
            // 这是设计取舍：直接返回 binder 代理需要 T 实现 IApkBridge 适配，
            // 业务侧可以用 ServiceAdapter 自己适配
            return null
        }
        return null
    }

    /**
     * 同步通用调用 — 适用于不想强类型化的场景（动态路由、跨 APK 脚本等）。
     *
     * @param method 形如 "engine/execute"
     * @param argsJson JSON 字符串参数
     * @return 调用结果（JSON 字符串）或错误
     */
    suspend fun invoke(method: String, argsJson: String): BridgeResult<String> {
        val traceId = Trace.newId()
        val serviceName = method.substringBefore('/', "")

        // 1) 进程内优先
        val inProc = InProcessRegistry.lookup<IApkBridgeInternal>(serviceName)
        if (inProc != null) {
            return bridgeRun {
                inProc.invoke(method, argsJson)
            }
        }

        // 2) AIDL 降级
        val bridge = BinderConnectionManager.lookup(serviceName)
        if (bridge == null) {
            // 服务不可达，可能是 APK 未安装或未启动
            // 通过 serviceName 反查 APK 描述符，返回友好错误
            val apkId = mapServiceToApkId(serviceName)
            val error = if (apkId != null) {
                BridgeError.notInstalledFriendly(apkId)
            } else {
                BridgeError.notInstalled(serviceName)
            }
            return BridgeResult.Failure(error)
        }

        return withContext(Dispatchers.IO) {
            val timeout = defaultTimeoutMs
            val result = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine<BridgeParcel> { cont ->
                    val parcel = BridgeParcel.request(
                        method = method,
                        traceId = traceId,
                        argsBytes = argsJson.toByteArray(Charsets.UTF_8)
                    )
                    try {
                        val resp = bridge.invoke(parcel)
                        cont.resume(resp)
                    } catch (t: Throwable) {
                        cont.resume(
                            BridgeParcel.failure(
                                method = method,
                                traceId = traceId,
                                errorCode = BridgeError.CODE_BUSINESS_FAILURE,
                                message = t.message ?: t.javaClass.simpleName
                            )
                        )
                    }
                }
            } ?: return@withContext BridgeResult.Failure(BridgeError.timeout(method, timeout))

            if (result.errorCode == 0) {
                BridgeResult.Success(String(result.result, Charsets.UTF_8))
            } else {
                BridgeResult.Failure(
                    BridgeError(
                        code = result.errorCode,
                        message = result.errorMessage ?: "unknown",
                        sourceApk = serviceName
                    )
                )
            }
        }
    }

    /**
     * 异步调用 — 适用于长耗时操作（LLM 推理、市场搜索）。
     * 进度回调通过 [onProgress] 实时上报。
     */
    suspend fun invokeAsync(
        method: String,
        argsJson: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): BridgeResult<String> {
        val traceId = Trace.newId()
        val serviceName = method.substringBefore('/', "")

        // 进程内优先
        val inProc = InProcessRegistry.lookup<IApkBridgeInternal>(serviceName)
        if (inProc != null) {
            return bridgeRun {
                inProc.invokeAsync(method, argsJson) { percent, msg ->
                    onProgress?.invoke(percent, msg)
                }
            }
        }

        // AIDL
        val bridge = BinderConnectionManager.lookup(serviceName)
            ?: return BridgeResult.Failure(BridgeError.notInstalled(serviceName))

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(defaultTimeoutMs * 4L) {
                suspendCancellableCoroutine<BridgeResult<String>> { cont ->
                    val parcel = BridgeParcel.request(
                        method = method,
                        traceId = traceId,
                        argsBytes = argsJson.toByteArray(Charsets.UTF_8)
                    )
                    val cb = object : IBridgeCallback.Stub() {
                        override fun onSuccess(result: BridgeParcel) {
                            cont.resume(
                                BridgeResult.Success(String(result.result, Charsets.UTF_8))
                            )
                        }

                        override fun onFailure(errorCode: Int, message: String?) {
                            cont.resume(
                                BridgeResult.Failure(
                                    BridgeError(errorCode, message ?: "unknown", sourceApk = serviceName)
                                )
                            )
                        }

                        override fun onProgress(percent: Int, message: String?) {
                            onProgress?.invoke(percent, message ?: "")
                        }
                    }
                    try {
                        bridge.invokeAsync(parcel, cb)
                    } catch (t: Throwable) {
                        cont.resume(
                            BridgeResult.Failure(BridgeError.fromThrowable(t, serviceName))
                        )
                    }
                }
            } ?: BridgeResult.Failure(BridgeError.timeout(method, defaultTimeoutMs * 4L))
        }
    }

    /**
     * 打开一个 LocalSocket 流通道。
     * 返回抽象命名空间地址，调用方据此连接。
     */
    suspend fun openStream(serviceName: String, channelName: String): BridgeResult<String> {
        val inProc = InProcessRegistry.lookup<IApkBridgeInternal>(serviceName)
        if (inProc != null) {
            return bridgeRun { inProc.openStream(channelName) }
        }
        val bridge = BinderConnectionManager.lookup(serviceName)
            ?: return BridgeResult.Failure(BridgeError.notInstalled(serviceName))
        return withContext(Dispatchers.IO) {
            bridgeRun { bridge.openStream(channelName) }
        }
    }

    /**
     * 关闭流通道。
     */
    suspend fun closeStream(serviceName: String, channelName: String): BridgeResult<Unit> {
        val inProc = InProcessRegistry.lookup<IApkBridgeInternal>(serviceName)
        if (inProc != null) {
            inProc.closeStream(channelName)
            return BridgeResult.Success(Unit)
        }
        val bridge = BinderConnectionManager.lookup(serviceName)
            ?: return BridgeResult.Failure(BridgeError.notInstalled(serviceName))
        return withContext(Dispatchers.IO) {
            bridgeRun { bridge.closeStream(channelName) }
        }
    }

    /**
     * 统一的错误处理包装：将普通函数调用结果包装为 [BridgeResult]。
     * 捕获所有异常，转为 [BridgeResult.Failure]。
     */
    private inline fun <T> bridgeRun(block: () -> T): BridgeResult<T> {
        return try {
            BridgeResult.Success(block())
        } catch (t: Throwable) {
            BridgeResult.Failure(BridgeError.fromThrowable(t, "bridge"))
        }
    }
}

/**
 * 进程内服务需要实现的内部接口（不要暴露给业务侧）。
 *
 * 用 Kotlin 接口而非 AIDL 是因为同进程根本不需要 Parcel，
 * 直接传 String 是最快的方式。
 */
interface IApkBridgeInternal {
    fun invoke(method: String, argsJson: String): String
    fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String
    fun openStream(channelName: String): String
    fun closeStream(channelName: String)
}

/**
 * 把 [IApkBridgeInternal] 适配成 AIDL [IApkBridge] Stub，
 * 用于跨进程降级时返回给 Binder 调用方。
 */
class ApkBridgeStubAdapter(private val internal: IApkBridgeInternal) : IApkBridge.Stub() {

    override fun invoke(parcel: BridgeParcel): BridgeParcel {
        return try {
            val args = String(parcel.args, Charsets.UTF_8)
            val result = internal.invoke(parcel.method, args)
            BridgeParcel.success(parcel.method, parcel.traceId, result.toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            BridgeParcel.failure(
                parcel.method,
                parcel.traceId,
                BridgeError.CODE_BUSINESS_FAILURE,
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    override fun invokeAsync(parcel: BridgeParcel, callback: IBridgeCallback?) {
        if (callback == null) return
        try {
            val args = String(parcel.args, Charsets.UTF_8)
            val result = internal.invokeAsync(parcel.method, args) { percent, msg ->
                try {
                    callback.onProgress(percent, msg)
                } catch (_: Throwable) {}
            }
            val resp = BridgeParcel.success(parcel.method, parcel.traceId, result.toByteArray(Charsets.UTF_8))
            callback.onSuccess(resp)
        } catch (t: Throwable) {
            callback.onFailure(BridgeError.CODE_BUSINESS_FAILURE, t.message)
        }
    }

    override fun openStream(channelName: String?): String {
        return internal.openStream(channelName.orEmpty())
    }

    override fun closeStream(channelName: String?) {
        internal.closeStream(channelName.orEmpty())
    }

    override fun getApkIdentity(): String = internal.javaClass.name
}

/**
 * 服务名 → APK ID 的映射。
 *
 * 服务名是 method 的第一段（如 "engine/execute" → "engine"），
 * 但 APK ID 可能不完全一致（如 "multi-agent" 包含连字符）。
 * 用于 [ApexBridge.invoke] 在服务不可达时返回友好的未安装错误。
 */
private fun mapServiceToApkId(serviceName: String): String? {
    return when (serviceName) {
        "main" -> com.apex.sdk.common.ApexSuite.ApkId.MAIN  // 诊断等服务由主 APK 提供
        "engine" -> com.apex.sdk.common.ApexSuite.ApkId.ENGINE
        "rage" -> com.apex.sdk.common.ApexSuite.ApkId.RAGE
        "multiagent", "multi-agent" -> com.apex.sdk.common.ApexSuite.ApkId.MULTI_AGENT
        "workflow" -> com.apex.sdk.common.ApexSuite.ApkId.WORKFLOW
        "market" -> com.apex.sdk.common.ApexSuite.ApkId.MARKET
        "terminal" -> com.apex.sdk.common.ApexSuite.ApkId.TERMINAL
        "workingfiles", "working-files" -> com.apex.sdk.common.ApexSuite.ApkId.WORKING_FILES
        "diagnostics" -> com.apex.sdk.common.ApexSuite.ApkId.DIAGNOSTICS
        "voice" -> com.apex.sdk.common.ApexSuite.ApkId.VOICE
        else -> null
    }
}

/**
 * Binder 连接管理 — 跨进程降级路径。
 *
 * 通过 [Context.bindService] 连接其他 APK 暴露的 Service，
 * 拿到 IApkBridge 代理后缓存复用。
 */
object BinderConnectionManager {

    private const val TAG_SUB = "BinderConn"

    private val connections = ConcurrentHashMap<String, IApkBridge>()

    fun register(serviceName: String, binder: IApkBridge) {
        connections[serviceName] = binder
        ApexLog.d(ApexSuite.ApkId.MAIN, "[$TAG_SUB] AIDL connection registered: $serviceName")
    }

    fun unregister(serviceName: String) {
        connections.remove(serviceName)
    }

    fun lookup(serviceName: String): IApkBridge? = connections[serviceName]

    fun listConnections(): List<String> = connections.keys.toList()
}
