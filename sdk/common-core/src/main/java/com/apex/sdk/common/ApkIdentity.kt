package com.apex.sdk.common

import android.content.Context
import android.content.pm.PackageManager

/**
 * 标识一个 APK 在套件中的身份。
 *
 * 每个 APK 在 Application.onCreate 中调用 [ApkIdentityRegistry.register] 注册自己，
 * 其他 APK 即可通过 [ApkIdentityRegistry.isInstalled] 检测某个 APK 是否已安装，
 * 决定是直接进程内调用，还是走 AIDL 跨进程，还是提示用户去市场安装。
 *
 * 因为所有 APK 共享 [ApexSuite.SHARED_USER_ID]， PackageManager 查询同 UID 下的
 * 包名列表即可枚举出“套件内已安装的 APK 集合”，无需额外权限。
 */
data class ApkIdentity(
    val id: String,
    val packageName: String,
    val displayName: String,
    val defaultProcess: String,
    /** 该 APK 是否承载了一个 long-running 的核心 Service。 */
    val hostsForegroundService: Boolean = false
)

object ApkIdentityRegistry {

    private val registry = linkedMapOf<String, ApkIdentity>()

    fun register(identity: ApkIdentity) {
        registry[identity.id] = identity
    }

    fun all(): List<ApkIdentity> = registry.values.toList()

    fun byId(id: String): ApkIdentity? = registry[id]

    /**
     * 通过 PackageManager 查询当前 UID 下安装了哪些 APK。
     * 因为共享 [ApexSuite.SHARED_USER_ID]，所有同签名同 UID 的 APK 都会被列出。
     */
    fun installedApkPackages(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            val myUid = android.os.Process.myUid()
            pm.getPackagesForUid(myUid).orEmpty().toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 判断指定 [apkId] 是否已安装（基于 [ApexIdentity] 注册 + 包名匹配）。
     */
    fun isInstalled(context: Context, apkId: String): Boolean {
        val identity = byId(apkId) ?: return false
        return installedApkPackages(context).any { it == identity.packageName }
    }

    /**
     * 通过 Intent action 启动另一个 APK 的入口 Activity（如果已安装）。
     * 返回是否启动成功。
     */
    fun launchApk(context: Context, apkId: String): Boolean {
        val identity = byId(apkId) ?: return false
        val pkg = identity.packageName
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
