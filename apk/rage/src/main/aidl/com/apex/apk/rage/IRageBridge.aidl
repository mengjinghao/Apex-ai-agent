// IRageBridge.aidl
package com.apex.apk.rage;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * Rage Mode APK（狂暴模式）对外接口。
 *
 * 狂暴模式微内核 + 插件式技能系统（ReAct / ToT / CoT / Racing 等）。
 */
interface IRageBridge {

    /// 启动狂暴模式会话
    String startSession(in BridgeParcel config);

    /// 异步执行一个任务
    void executeAsync(String sessionId, in BridgeParcel request, IBridgeCallback callback);

    /// 暂停 / 恢复 / 终止
    boolean pauseSession(String sessionId);
    boolean resumeSession(String sessionId);
    boolean stopSession(String sessionId);

    /// 列出可用技能
    List<String> listSkills();

    /// 加载自定义技能
    boolean loadSkill(in BridgeParcel manifest);

    /// 心跳
    long heartbeat();
}
