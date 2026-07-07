// IEngineBridge.aidl
package com.apex.apk.engine;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * Engine APK 对外暴露的强类型接口。
 *
 * 主 APK / 狂暴 APK / 多 Agent APK 可以通过本接口调用引擎：
 *   - executeShell(cmd)        执行 shell 命令
 *   - executeTool(tool, args)  执行内置工具
 *   - accessibilityAction(...) 无障碍操作
 *   - shizukuInvoke(...)       Shizuku 高权限调用
 *
 * 当调用方与 Engine APK 同进程时，走 [InProcessRegistry] 直接 JVM 调用（零延迟）；
 * 否则走 AIDL Binder。
 */
interface IEngineBridge {
    /// 执行 shell 命令，返回 stdout
    String executeShell(String cmd);

    /// 执行内置工具
    BridgeParcel executeTool(in BridgeParcel request);

    /// 异步执行长任务
    void executeToolAsync(in BridgeParcel request, IBridgeCallback callback);

    /// 查询引擎状态
    String getStatus();

    /// 心跳
    long heartbeat();
}
