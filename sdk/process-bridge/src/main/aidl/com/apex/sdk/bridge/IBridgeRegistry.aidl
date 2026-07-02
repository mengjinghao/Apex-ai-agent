// ApexBridge.aidl
package com.apex.sdk.bridge;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * 套件级服务发现 + 通用 RPC 入口。
 *
 * 每个 APK 在 Application.onCreate 中通过 [IBridgeRegistry.register] 注册自己对外暴露的服务，
 * 其他 APK 通过 [IBridgeRegistry.lookup] 拿到 [IApkBridge] 后调用 [IApkBridge.invoke]。
 *
 * 当两个 APK 共享同一个进程（SharedUserId + android:process）时，
 * 调用方可以直接通过进程内 ServiceRegistry 拿到对方 Service 的 Kotlin 实例，
 * 完全跳过 Binder —— 这是“零延迟”路径。
 *
 * 当两个 APK 处于不同进程（如 Terminal 独立进程）时，走标准 Binder AIDL。
 */
interface IBridgeRegistry {

    /**
     * 注册本 APK 暴露的服务。
     * @param serviceName 服务全名，例如 "com.apex.apk.engine.EngineService"
     * @param bridge      该服务的 Binder 入口
     * @return 注册成功返回 true
     */
    boolean register(String serviceName, in IApkBridge bridge);

    /**
     * 注销服务。
     */
    void unregister(String serviceName);

    /**
     * 查询某服务是否已注册。
     */
    boolean isRegistered(String serviceName);

    /**
     * 查找某服务的 Binder 入口。
     * @return 若存在则返回；若不存在返回 null
     */
    IApkBridge lookup(String serviceName);

    /**
     * 列出当前套件中所有已注册的服务名（用于诊断 UI）。
     */
    List<String> listServices();

    /**
     * 心跳探测：调用后立即返回当前 APK 的存活时间戳（毫秒）。
     * 用于 Watchdog 健康检查。
     */
    long heartbeat();
}
