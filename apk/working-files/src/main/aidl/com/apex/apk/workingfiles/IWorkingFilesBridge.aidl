// IWorkingFilesBridge.aidl
package com.apex.apk.workingfiles;

import com.apex.sdk.bridge.BridgeParcel;

/**
 * Working Files APK 对外接口。
 *
 * 管理三种 Agent 模式共享的工作文件夹。
 */
interface IWorkingFilesBridge {

    /// 绑定一个工作文件夹
    boolean bindFolder(in BridgeParcel folderSpec);

    /// 解绑
    boolean unbindFolder(String folderId);

    /// 列出绑定的文件夹
    BridgeParcel listFolders();

    /// 列出某文件夹下的文件树
    BridgeParcel listFiles(String folderId, String relativePath);

    /// 读取文件内容
    String readFile(String folderId, String relativePath);

    /// 写入文件
    boolean writeFile(String folderId, String relativePath, String content);

    /// 订阅文件变更（通过 LocalSocket 推送）
    String subscribeChanges(String folderId);

    /// 心跳
    long heartbeat();
}
