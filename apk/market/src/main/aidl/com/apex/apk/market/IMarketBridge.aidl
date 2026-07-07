// IMarketBridge.aidl
package com.apex.apk.market;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * Market APK 对外暴露的接口。
 *
 * 提供：
 *   - 搜索技能 / 插件 / MCP / 模型 / Agent 角色
 *   - 安装 / 卸载 / 启用 / 禁用
 *   - 列出已安装的资产
 *   - 调用云端模型（OpenAI / Claude / Gemini / 本地 llama）
 *
 * 其他 APK（主 / 狂暴 / 多 Agent / 工作流）调用本接口获取能力。
 *
 * **技能 / MCP / 插件在本地** → 零延迟（同进程直调）
 * **模型在云端** → 延迟取决于网络，与多 APK 无关
 * **集成平台（GitHub 等）** → 通过本接口统一暴露，任意 APK 可用
 */
interface IMarketBridge {

    /// 搜索市场
    BridgeParcel search(in BridgeParcel request);

    /// 列出已安装的资产
    BridgeParcel listInstalled(String category);

    /// 安装一个资产
    void installAsync(in BridgeParcel request, IBridgeCallback callback);

    /// 卸载
    boolean uninstall(String assetId);

    /// 启用 / 禁用
    boolean setEnabled(String assetId, boolean enabled);

    /// 调用云端模型（统一入口）
    void invokeModelAsync(in BridgeParcel request, IBridgeCallback callback);

    /// 调用本地技能 / 插件 / MCP
    BridgeParcel invokeLocalSkill(in BridgeParcel request);

    /// 心跳
    long heartbeat();
}
