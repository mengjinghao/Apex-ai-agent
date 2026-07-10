// BridgeParcel.aidl
package com.apex.sdk.bridge;

/**
 * 跨 APK 调用的通用载体。
 *
 * 设计原则：
 *   - 所有 AIDL 方法签名只接受/返回 BridgeParcel，避免接口爆炸
 *   - method: 服务名 + 方法名（如 "engine/execute"）
 *   - args:   JSON 字节数组（kotlinx-serialization）
 *   - traceId: 端到端追踪 ID，串联多个 APK 间的调用链
 *
 * 当两个 APK 处于同一进程时，调用方根本不会经过 Parcel —— 会直接调用 Kotlin 实例。
 */
parcelable BridgeParcel {
    String method;
    String traceId;
    byte[] args;
    byte[] result;
    int errorCode;
    String errorMessage;
}
