package com.apex.sdk.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

/**
 * 自动初始化器 — 利用 ContentProvider 在 Application.onCreate 之前
 * 被系统调用的特性，自动完成 Bridge 注册。
 *
 * **机制**：
 *   Android 启动 APK 时，会先初始化所有 ContentProvider，再调用 Application.onCreate。
 *   Firebase、WorkManager 等都用这个机制做“零侵入”初始化。
 *
 * **本 Provider 做的事**：
 *   1. 注册本 APK 的 [ApkIdentity]
 *   2. 启动 [HeartbeatReporter]
 *   3. 启动 [Watchdog]
 *   4. bindService 到主 APK 的 [BridgeRegistryService]
 *
 * 各 APK 无需修改 Application 类，只需在 AndroidManifest 中声明本 Provider。
 * 但 SDK 通过 consumer-rules 已自动声明，业务侧无需任何配置。
 *
 * **主 APK 自身的初始化**：
 *   主 APK 也用本 Provider，但因为它的包名就是 com.apex.agent，
 *   bindService 会指向自己内部的 BridgeRegistryService，
 *   走进程内调用，零延迟。
 */
class ApexBridgeInitializer : ContentProvider() {

    private var heartbeat: HeartbeatReporter? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val apkId = detectApkId(ctx)

        // 注册身份
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = apkId,
                packageName = ctx.packageName,
                displayName = apkId,
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(apkId, "[BridgeInitializer] onCreate (pid=${android.os.Process.myPid()}, pkg=${ctx.packageName})")

        // 启动心跳
        heartbeat = HeartbeatReporter(apkId).also { it.start() }

        // 启动看门狗
        Watchdog.start()

        // 主 APK 自身：启动 BridgeRegistryService（其他 APK bindService 到此）
        // 非 主 APK：bindService 到主 APK 的 Registry
        if (apkId == ApexSuite.ApkId.MAIN) {
            BridgeRegistryService.startInMainApp(ctx)
            // 同时也 bindToRegistry（bind 到自己）
            BridgeConnection.bindToRegistry(ctx) { connected ->
                ApexLog.i(apkId, "[BridgeInitializer] self-bridge bound = $connected")
            }
        } else {
            BridgeConnection.bindToRegistry(ctx) { connected ->
                ApexLog.i(apkId, "[BridgeInitializer] bridge bound = $connected")
            }
        }

        return true
    }

    private fun detectApkId(ctx: Context): String {
        // 根据包名识别当前是哪个 APK
        return when (ctx.packageName) {
            "com.apex.agent" -> ApexSuite.ApkId.MAIN
            "com.apex.apk.engine" -> ApexSuite.ApkId.ENGINE
            "com.apex.apk.rage" -> ApexSuite.ApkId.RAGE
            "com.apex.apk.multiagent" -> ApexSuite.ApkId.MULTI_AGENT
            "com.apex.apk.workflow" -> ApexSuite.ApkId.WORKFLOW
            "com.apex.apk.market" -> ApexSuite.ApkId.MARKET
            "com.apex.apk.terminal" -> ApexSuite.ApkId.TERMINAL
            "com.apex.apk.workingfiles" -> ApexSuite.ApkId.WORKING_FILES
            "com.apex.apk.diagnostics" -> ApexSuite.ApkId.DIAGNOSTICS
            "com.apex.apk.voice" -> ApexSuite.ApkId.VOICE
            else -> "unknown:${ctx.packageName}"
        }
    }

    // ContentProvider 必须实现的方法，本 Provider 不实际提供内容，全部 no-op
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<String>?): Int = 0
}
