// IMultiAgentBridge.aidl
package com.apex.apk.multiagent;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * Multi-Agent APK 对外接口。
 */
interface IMultiAgentBridge {

    /// 注册一个 Agent
    boolean registerAgent(in BridgeParcel agentSpec);

    /// 启动一次协作会话
    void runCollaborationAsync(in BridgeParcel config, IBridgeCallback callback);

    /// 列出所有注册的 Agent
    List<String> listAgents();

    /// 读取黑板数据
    BridgeParcel readBlackboard(String key);

    /// 心跳
    long heartbeat();
}
