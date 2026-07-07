// IApkBridge.aidl
package com.apex.sdk.bridge;

import com.apex.sdk.bridge.BridgeParcel;
import com.apex.sdk.bridge.IBridgeCallback;

/**
 * 单个 APK 对外暴露的 RPC 入口。
 *
 * 每个承载服务的 APK 都需要实现一个 IApkBridge，注册到 IBridgeRegistry。
 * 调用方通过 [IBridgeRegistry.lookup] 拿到 IApkBridge，再调用 invoke 发起请求。
 *
 * invoke 走通用 Parcel 通道：
 *   - 请求体 = method 名 + JSON/protobuf 字节数组参数
 *   - 响应体 = 同样格式的字节数组
 *
 * 对于流式场景（终端 PTY、文件 watch），请使用 [openStream] 走 LocalSocket，
 * 避免触发 Binder 1MB 事务缓冲区限制。
 */
interface IApkBridge {

    /**
     * 调用本 APK 暴露的同步方法。
     *
     * @param parcel 包含 method / args / traceId 的载体
     * @return 返回值载体；若方法不存在或抛异常，返回错误载体
     */
    BridgeParcel invoke(in BridgeParcel parcel);

    /**
     * 调用本 APK 暴露的异步方法，结果通过回调返回。
     * 适用于 LLM 推理、市场搜索等长耗时操作。
     */
    void invokeAsync(in BridgeParcel parcel, IBridgeCallback callback);

    /**
     * 打开一个 LocalSocket 流通道。
     *
     * @param channelName 通道名（如 "terminal.pty.123"）
     * @return 返回 LocalSocket 的 abstract namespace 名；调用方据此连接
     */
    String openStream(String channelName);

    /**
     * 关闭指定通道。
     */
    void closeStream(String channelName);

    /**
     * 当前 APK 的身份信息（apkId + version）。
     */
    String getApkIdentity();
}
