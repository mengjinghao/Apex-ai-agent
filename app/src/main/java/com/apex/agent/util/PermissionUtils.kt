package com.apex.agent.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限工具类，提供 Android 运行时权限的检查、申请和状态查询功能
 *
 * 支持 Android 6.0 (API 23) 及以上的运行时权限模型，根据 API 级别自动适配权限行为
 * 包含常见权限（相机、存储、位置、蓝牙、通知等）的一站式处理方法
 */
object PermissionUtils {

    // ========== 权限请求码常量 ==========

    /** 录音权限请求码 */
    const val REQUEST_RECORD_AUDIO_PERMISSION = 1001

    /** 通知权限请求码 */
    const val REQUEST_NOTIFICATION_PERMISSION = 1002

    /** 相机权限请求码 */
    const val REQUEST_CAMERA_PERMISSION = 1003

    /** 存储权限请求码 */
    const val REQUEST_STORAGE_PERMISSION = 1004

    /** 位置权限请求码 */
    const val REQUEST_LOCATION_PERMISSION = 1005

    /** 蓝牙权限请求码 */
    const val REQUEST_BLUETOOTH_PERMISSION = 1006

    /** 短信权限请求码 */
    const val REQUEST_SMS_PERMISSION = 1007

    /** 通讯录权限请求码 */
    const val REQUEST_CONTACTS_PERMISSION = 1008

    /** 拨打电话权限请求码 */
    const val REQUEST_CALL_PHONE_PERMISSION = 1009

    /** 身体传感器权限请求码 */
    const val REQUEST_BODY_SENSORS_PERMISSION = 1010

    /** 精确闹钟权限请求码 */
    const val REQUEST_SCHEDULE_EXACT_ALARM_PERMISSION = 1011

    /** 管理外部存储权限请求码 */
    const val REQUEST_MANAGE_EXTERNAL_STORAGE_PERMISSION = 1012

    /**
     * 检查是否拥有录音权限
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有悬浮窗权限
     *
     * 在 Android 6.0 (API 23) 及以上需要跳转系统设置页面授予，
     * 低于 Android 6.0 的设备默认拥有悬浮窗权限。
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 检查是否拥有通知权限
     *
     * 在 Android 13 (API 33) 及以上需要运行时权限，
     * 低于 Android 13 的设备默认拥有通知权限。
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 检查是否拥有相机权限
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有存储权限
     *
     * 根据 Android 版本自动适配检查的权限：
     * - Android 13+：检查细粒度媒体权限 READ_MEDIA_IMAGES
     * - Android 10-12：通过分区存储 API 访问，此处检查 READ_EXTERNAL_STORAGE
     * - Android 9 及以下：同时检查 READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查是否拥有位置权限（同时检查粗略和精细位置权限）
     *
     * @param context 上下文
     * @return true 至少拥有一种位置权限，false 均未授权
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有蓝牙权限
     *
     * 根据 Android 版本自动适配：
     * - Android 12 (API 31)+：检查 BLUETOOTH_SCAN + BLUETOOTH_CONNECT
     * - Android 12 以下：检查 BLUETOOTH + BLUETOOTH_ADMIN
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADMIN
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查是否拥有短信权限（发送和接收）
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有通讯录权限（读取和写入）
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有拨打电话权限
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasCallPhonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有身体传感器权限
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasBodySensorsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有精确闹钟权限
     *
     * 仅在 Android 12 (API 31) 及以上需要运行时检查，
     * 低版本设备默认拥有此权限。
     *
     * @param context 上下文
     * @return true 已授权或无需检查，false 未授权
     */
    fun hasScheduleExactAlarmPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 检查是否拥有管理所有文件权限
     *
     * 仅在 Android 11 (API 30) 及以上需要运行时检查，
     * 低版本设备默认拥有完整的文件访问权限。
     *
     * @param context 上下文
     * @return true 已授权或无需检查，false 未授权
     */
    fun hasManageExternalStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * 通用权限检查方法
     *
     * @param context 上下文
     * @param permission 要检查的权限字符串（如 Manifest.permission.CAMERA）
     * @return true 已授权，false 未授权
     */
    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查所有必需的运行时权限并返回详细状态映射
     *
     * 返回的 Map 包含所有关键权限的授权状态，key 为权限名称，value 为是否已授权。
     * 部分权限会根据 API 级别自动判断（如低版本无需检查精确闹钟权限）。
     *
     * @param context 上下文
     * @return 权限名称到授权状态的映射
     */
    fun hasAllRequiredPermissions(context: Context): Map<String, Boolean> {
        val permissions = mutableMapOf<String, Boolean>()
        permissions["RECORD_AUDIO"] = hasRecordAudioPermission(context)
        permissions["CAMERA"] = hasCameraPermission(context)
        permissions["LOCATION"] = hasLocationPermission(context)
        permissions["NOTIFICATION"] = hasNotificationPermission(context)
        permissions["STORAGE"] = hasStoragePermission(context)
        permissions["BLUETOOTH"] = hasBluetoothPermission(context)
        permissions["SMS"] = hasSmsPermission(context)
        permissions["CONTACTS"] = hasContactsPermission(context)
        permissions["CALL_PHONE"] = hasCallPhonePermission(context)
        permissions["BODY_SENSORS"] = hasBodySensorsPermission(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions["SCHEDULE_EXACT_ALARM"] = hasScheduleExactAlarmPermission(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions["MANAGE_EXTERNAL_STORAGE"] = hasManageExternalStoragePermission(context)
        }
        return permissions
    }

    /**
     * 请求悬浮窗权限（跳转至系统设置页面）
     *
     * 通过 Intent 跳转到系统设置 - 悬浮窗管理页面。
     *
     * @param context 上下文
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * 请求通知权限
     *
     * 仅在 Android 13 (API 33) 及以上会弹出系统权限对话框。
     *
     * @param context 上下文
     */
    fun requestNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.requestPermissions(
                context,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    /**
     * 请求相机权限
     *
     * @param context 上下文，需为 Activity 实例
     */
    fun requestCameraPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activity = context as? Activity
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSION
                )
            }
        }
    }

    /**
     * 请求存储权限
     *
     * 根据 Android 版本自动请求对应的权限列表：
     * - Android 13+：请求 READ_MEDIA_IMAGES、READ_MEDIA_VIDEO、READ_MEDIA_AUDIO
     * - Android 10-12：请求 READ_EXTERNAL_STORAGE
     * - Android 9 及以下：请求 READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
     *
     * @param context 上下文，需为 Activity 实例
     */
    fun requestStoragePermission(context: Context) {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                ),
                REQUEST_STORAGE_PERMISSION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }

    /**
     * 请求位置权限（同时请求粗略和精细位置权限）
     *
     * @param context 上下文，需为 Activity 实例
     */
    fun requestLocationPermission(context: Context) {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
        }
    }

    /**
     * 请求蓝牙权限
     *
     * 根据 Android 版本自动请求对应的权限：
     * - Android 12+：请求 BLUETOOTH_SCAN + BLUETOOTH_CONNECT
     * - Android 12 以下：请求 BLUETOOTH + BLUETOOTH_ADMIN
     *
     * @param context 上下文，需为 Activity 实例
     */
    fun requestBluetoothPermission(context: Context) {
        val activity = context as? Activity ?: return
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_BLUETOOTH_PERMISSION)
    }

    /**
     * 显示权限被拒绝的 Toast 提示
     *
     * @param context 上下文
     * @param permission 被拒绝的权限名称
     */
    fun showPermissionDeniedToast(context: Context, permission: String) {
        Toast.makeText(
            context,
            "Permission denied: ${permission}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * 应用工具类，提供应用信息查询和系统工具方法
 */
object AppUtils {

    /**
     * 检查指定包名的应用是否已安装
     *
     * @param context 上下文
     * @param packageName 应用包名
     * @return true 已安装，false 未安装
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 打开应用详情设置页面
     *
     * @param context 上下文
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 获取应用版本名称
     *
     * @param context 上下文
     * @return 版本名称字符串，获取失败则返回 "Unknown"
     */
    fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * 检查设备是否为低 RAM 设备
     *
     * 低 RAM 设备通常内存小于 1GB，系统会针对此类设备优化资源使用。
     *
     * @param context 上下文
     * @return true 低 RAM 设备，false 正常设备
     */
    fun isLowRamDevice(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return activityManager.isLowRamDevice
    }

    /**
     * 获取设备屏幕密度
     *
     * @param context 上下文
     * @return 屏幕密度值（如 1.0 为 mdpi，2.0 为 xhdpi）
     */
    fun getDeviceDensity(context: Context): Float {
        return context.resources.displayMetrics.density
    }

    /**
     * 将 dp（密度无关像素）转换为 px（物理像素）
     *
     * @param context 上下文
     * @param dp dp 值
     * @return px 值（四舍五入为整数）
     */
    fun dpToPx(context: Context, dp: Float): Int {
        return (dp * getDeviceDensity(context)).toInt()
    }

    /**
     * 将 px（物理像素）转换为 dp（密度无关像素）
     *
     * @param context 上下文
     * @param px px 值
     * @return dp 值
     */
    fun pxToDp(context: Context, px: Int): Float {
        return px / getDeviceDensity(context)
    }
}
