// IBridgeCallback.aidl
package com.apex.sdk.bridge;

import com.apex.sdk.bridge.BridgeParcel;

/**
 * 异步调用回调。
 */
interface IBridgeCallback {
    void onSuccess(in BridgeParcel result);
    void onFailure(int errorCode, String message);
    void onProgress(int percent, String message);
}
