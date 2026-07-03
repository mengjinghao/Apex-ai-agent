package com.apex.sdk.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.apex.sdk.common.ApkDescriptors.byId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * APK 依赖管理器 — 检查安装状态、提示用户安装、监听安装/卸载事件。
 *
 * **核心能力**：
 *   1. 检查必须 APK 是否已安装（主 APK 启动时调用）
 *   2. 检查某能力对应的 APK 是否已安装（功能调用前调用）
 *   3. 启动 APK 安装（通过 Intent 跳转到 APK 安装器）
 *   4. 跳转到 GitHub Release 下载页
 *   5. 监听套件中 APK 的安装/卸载事件（基于 BroadcastReceiver）
 *
 * **使用方式**：
 *   ```kotlin
 *   // 主 APK 启动时
 *   val missing = ApkDependencyManager.checkRequiredApks(context)
 *   if (missing.isNotEmpty()) {
 *       showMissingApksDialog(missing)
 *   }
 *
 *   // 调用 Rage 前检查
 *   if (!ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.RAGE)) {
 *       promptInstallApk(ApkDescriptors.RAGE)
 *       return
 *   }
 *   ApexClient.rage.startSession(...)
 *   ```
 */
object ApkDependencyManager {

    private const val TAG_SUB = "ApkDeps"

    /** 套件安装状态快照（apkId → installed）。 */
    private val _installState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val installState: StateFlow<Map<String, Boolean>> = _installState.asStateFlow()

    /**
     * 检查指定 APK 是否已安装。
     * 通过 PackageManager 查询包名是否存在。
     */
    fun isApkInstalled(context: Context, apkId: String): Boolean {
        val desc = byId(apkId) ?: return false
        return try {
            context.packageManager.getPackageInfo(desc.packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 通过包名检查是否已安装。
     */
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 检查所有必须 APK 是否已安装。
     * @return 未安装的必须 APK 描述符列表
     */
    fun checkRequiredApks(context: Context): List<ApkDescriptor> {
        return ApkDescriptors.REQUIRED.filter { !isApkInstalled(context, it.apkId) }
    }

    /**
     * 检查某 APK 的所有依赖是否已安装（递归）。
     * @return 未安装的依赖 APK 描述符列表
     */
    fun checkDependencies(context: Context, apkId: String): List<ApkDescriptor> {
        return ApkDescriptors.dependencyTree(apkId).filter { !isApkInstalled(context, it.apkId) }
    }

    /**
     * 检查指定能力是否有 APK 提供。
     * @return 提供该能力的已安装 APK 描述符列表（可能为空）
     */
    fun findInstalledApksForCapability(context: Context, capability: String): List<ApkDescriptor> {
        return ApkDescriptors.byCapability(capability).filter { isApkInstalled(context, it.apkId) }
    }

    /**
     * 检查指定能力是否有 APK 提供（任意一个已安装即返回 true）。
     */
    fun hasCapability(context: Context, capability: String): Boolean {
        return findInstalledApksForCapability(context, capability).isNotEmpty()
    }

    /**
     * 刷新安装状态快照。应在 Application.onCreate 和 BroadcastReceiver.onReceive 中调用。
     */
    fun refreshInstallState(context: Context) {
        val snapshot = ApkDescriptors.ALL.associate { it.apkId to isApkInstalled(context, it.apkId) }
        _installState.value = snapshot
        ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] install state refreshed: ${snapshot.count { it.value }}/${snapshot.size} installed")
    }

    /**
     * 启动 APK 安装。
     *
     * @param context 上下文
     * @param apkId 要安装的 APK ID
     * @param apkFileUri APK 文件的 URI（如果已有下载好的文件）
     * @return 是否成功启动安装 Intent
     */
    fun startInstall(context: Context, apkId: String, apkFileUri: Uri? = null): Boolean {
        val desc = byId(apkId) ?: return false
        return if (apkFileUri != null) {
            startInstallFromUri(context, apkFileUri)
        } else {
            // 没有本地文件，跳转到下载页
            openDownloadPage(context, apkId)
        }
    }

    /**
     * 通过 URI 启动 APK 安装（用户已下载好 APK 文件）。
     */
    fun startInstallFromUri(context: Context, apkFileUri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkFileUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] install started: $apkFileUri")
            true
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] failed to start install: ${t.message}")
            false
        }
    }

    /**
     * 打开下载页面（GitHub Release 或 Play Store）。
     */
    fun openDownloadPage(context: Context, apkId: String): Boolean {
        val desc = byId(apkId) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(desc.downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] opened download page: ${desc.apkId}")
            true
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] failed to open download page: ${t.message}")
            false
        }
    }

    /**
     * 启动指定 APK（如果已安装）。
     */
    fun launchApk(context: Context, apkId: String): Boolean {
        val desc = byId(apkId) ?: return false
        if (!isApkInstalled(context, apkId)) return false
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(desc.packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] failed to launch ${desc.apkId}: ${t.message}")
            false
        }
    }

    /**
     * 获取已安装 APK 的版本名。
     */
    fun getInstalledVersion(context: Context, apkId: String): String? {
        val desc = byId(apkId) ?: return null
        return try {
            val info = context.packageManager.getPackageInfo(desc.packageName, 0)
            info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 生成缺失 APK 的友好提示文案。
     *
     * @param missingApks 未安装的 APK 描述符列表
     * @return 用户可读的提示文本
     */
    fun buildMissingApksMessage(missingApks: List<ApkDescriptor>): String {
        if (missingApks.isEmpty()) return "所有必要组件已安装"
        val required = missingApks.filter { it.necessity == ApkNecessity.REQUIRED }
        val optional = missingApks.filter { it.necessity == ApkNecessity.OPTIONAL }
        val debug = missingApks.filter { it.necessity == ApkNecessity.DEBUG }

        val sb = StringBuilder()
        if (required.isNotEmpty()) {
            sb.append("以下必要组件未安装，部分功能将不可用：\n")
            required.forEach { sb.append("  • ${it.displayName}（${it.description}，约 ${it.approxSizeMb}MB）\n") }
        }
        if (optional.isNotEmpty()) {
            sb.append("\n以下可选组件未安装（按需安装）：\n")
            optional.forEach { sb.append("  • ${it.displayName}（${it.description}，约 ${it.approxSizeMb}MB）\n") }
        }
        if (debug.isNotEmpty()) {
            sb.append("\n以下调试组件未安装（开发者使用）：\n")
            debug.forEach { sb.append("  • ${it.displayName}（${it.description}）\n") }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 生成单个 APK 缺失时的简短提示。
     */
    fun buildSingleMissingMessage(apkId: String): String {
        val desc = byId(apkId) ?: return "未知组件 $apkId 未安装"
        return when (desc.necessity) {
            ApkNecessity.REQUIRED -> "必要组件「${desc.displayName}」未安装，请先安装后再使用此功能"
            ApkNecessity.OPTIONAL -> "可选组件「${desc.displayName}」未安装，是否前往下载？"
            ApkNecessity.DEBUG -> "调试组件「${desc.displayName}」未安装"
        }
    }

    /**
     * 获取套件整体安装摘要。
     * @return "已安装 5/10（必须 5/5，可选 0/4，调试 0/1）"
     */
    fun getInstallSummary(context: Context): String {
        val required = ApkDescriptors.REQUIRED
        val optional = ApkDescriptors.OPTIONAL
        val debug = ApkDescriptors.DEBUG
        val reqInstalled = required.count { isApkInstalled(context, it.apkId) }
        val optInstalled = optional.count { isApkInstalled(context, it.apkId) }
        val dbgInstalled = debug.count { isApkInstalled(context, it.apkId) }
        val total = reqInstalled + optInstalled + dbgInstalled
        return "已安装 $total/${ApkDescriptors.ALL.size}（必须 $reqInstalled/${required.size}，可选 $optInstalled/${optional.size}，调试 $dbgInstalled/${debug.size}）"
    }
}
