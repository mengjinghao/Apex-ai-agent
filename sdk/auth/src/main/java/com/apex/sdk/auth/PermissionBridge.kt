package com.apex.sdk.auth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite

/**
 * 跨 APK 权限桥。
 *
 * **核心机制**：所有 APK 共享 [ApexSuite.SHARED_USER_ID]（需相同签名），
 * Android 系统会把它们归并到同一个 Linux UID。
 * 因此只要**任意一个 APK** 申请并获得了某权限，同 UID 下所有 APK 自动继承该权限。
 *
 * **业务收益**：
 *   - 用户只需授权一次（在主 APK 中），其他 APK 直接可用
 *   - 不会出现“终端 APK 要存储权限、主 APK 也要存储权限”这种割裂体验
 *   - 对用户而言，“多个 APK 像一个 APK 一样”
 *
 * **使用方式**：
 *   ```kotlin
 *   if (PermissionBridge.isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
 *       // 任意 APK 都可以读外部存储
 *   } else {
 *       // 在主 APK 中发起请求
 *       PermissionBridge.requestFromMainApk(activity, listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
 *   }
 *   ```
 */
object PermissionBridge {

    private const val TAG_SUB = "PermissionBridge"

    /**
     * 检查某权限是否已授予。
     * 由于 SharedUserId 共享 UID，任意 APK 检查结果都一致。
     */
    fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查一组权限是否全部已授予。
     */
    fun allGranted(context: Context, permissions: Collection<String>): Boolean {
        return permissions.all { isGranted(context, it) }
    }

    /**
     * 检查一组权限中哪些未授予。
     */
    fun missing(context: Context, permissions: Collection<String>): List<String> {
        return permissions.filterNot { isGranted(context, it) }
    }

    /**
     * 判断是否拥有“所有文件访问”权限（Android 11+）。
     */
    fun hasManageExternalStorage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return android.os.Environment.isExternalStorageManager()
    }

    /**
     * 套件常用权限清单 — 用于主 APK 启动时批量请求。
     */
    object Common {
        val STORAGE = listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val LOCATION = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val PHONE_SMS = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )

        val MEDIA = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        val NOTIFICATION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyList()

        /** 主 APK 在首次启动时应请求的所有权限。 */
        val ALL: List<String> = STORAGE + LOCATION + PHONE_SMS + MEDIA + NOTIFICATION
    }

    /**
     * 把权限申请请求路由到主 APK。
     *
     * 因为权限对话框依赖 Activity，而其他 APK 可能没有 UI，
     * 通过本方法启动主 APK 的 PermissionRequestActivity，
     * 让用户在统一的 UI 中完成所有授权。
     */
    fun requestFromMainApk(
        context: Context,
        permissions: List<String>,
        requestCode: Int = 0x1001
    ): Boolean {
        return try {
            val intent = android.content.Intent().apply {
                setClassName("com.apex.agent", "com.apex.agent.ui.permission.PermissionRequestActivity")
                action = ACTION_REQUEST_PERMISSIONS
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                putStringArrayListExtra(EXTRA_PERMISSIONS, ArrayList(permissions))
                putExtra(EXTRA_REQUEST_CODE, requestCode)
            }
            context.startActivity(intent)
            ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] routed permission request to main APK")
            true
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] failed to route permission request", t)
            false
        }
    }

    const val ACTION_REQUEST_PERMISSIONS = "com.apex.sdk.auth.REQUEST_PERMISSIONS"
    const val EXTRA_PERMISSIONS = "extra.permissions"
    const val EXTRA_REQUEST_CODE = "extra.request_code"
}
