// IWorkflowBridge.aidl
package com.apex.apk.workflow;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * Workflow APK 对外接口。
 */
interface IWorkflowBridge {

    /// 注册一个工作流定义
    boolean registerWorkflow(BridgeParcel workflowDef);

    /// 异步执行工作流
    void executeAsync(String workflowId, BridgeParcel inputs, IBridgeCallback callback);

    /// 列出所有已注册的工作流
    List<String> listWorkflows();

    /// 心跳
    long heartbeat();
}
