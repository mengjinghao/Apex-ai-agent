// ITerminalBridge.aidl
package com.apex.apk.terminal;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * Terminal APK 对外暴露的接口。
 *
 * **三块结构**：
 *   - createNormalSession()   → 普通 Agent 模式使用的终端
 *   - createMultiAgentSession() → 多 Agent 模式使用的终端
 *   - createBurstSession()    → 狂暴模式使用的终端
 *
 * 每个会话拿到 sessionId 后，通过 LocalSocket（openStream）传输 PTY 数据流，
 * 避免 Binder 1MB 事务限制。
 */
interface ITerminalBridge {

    /// 创建一个普通 Agent 模式的终端会话
    String createNormalSession(String workingDir);

    /// 创建一个多 Agent 模式的终端会话
    String createMultiAgentSession(String workingDir, String agentId);

    /// 创建一个狂暴模式的终端会话
    String createBurstSession(String workingDir, String burstProfile);

    /// 向指定会话写入输入（用于小数据量，大数据请走 LocalSocket）
    void writeInput(String sessionId, in byte[] data);

    /// 读取指定会话的输出（阻塞，可能为空）
    byte[] readOutput(String sessionId);

    /// 销毁会话
    void destroySession(String sessionId);

    /// 列出所有活跃会话
    List<String> listSessions();

    /// 获取某会话的 LocalSocket 通道名（用于流式传输）
    String getStreamChannel(String sessionId);

    /// 心跳
    long heartbeat();
}
