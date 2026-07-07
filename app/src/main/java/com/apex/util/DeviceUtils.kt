package com.apex.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Locale
import java.util.TimeZone

/**
 * 设备信息工具类，提供设备品牌、型号、系统版本、硬件功能、内存/存储容量等信息的查询
 *
 * 覆盖 Android 设备的各项软硬件信息，帮助开发者了解运行环境并进行兼容性判断。
 */
object DeviceUtils {

    /**
     * 获取设备品牌
     *
     * 如 "Samsung"、"Xiaomi"、"HUAWEI"、"Google" 等。
     *
     * @return 设备品牌字符串
     */
    fun getDeviceBrand(): String {
        return Build.BRAND
    }

    /**
     * 获取设备型号
     *
     * 如 "SM-G998B"、"Mi 11"、"Pixel 7" 等。
     *
     * @return 设备型号字符串
     */
    fun getDeviceModel(): String {
        return Build.MODEL
    }

    /**
     * 获取设备名称（品牌 + 型号组合）
     *
     * 如 "Samsung SM-G998B"、"Xiaomi Mi 11"。
     *
     * @return 品牌与型号拼接字符串
     */
    fun getDeviceName(): String {
        return "${getDeviceBrand()} ${getDeviceModel()}"
    }

    /**
     * 获取 Android 系统 API 版本号（SDK Int）
     *
     * @return SDK 版本号（如 Android 13 返回 33）
     */
    fun getAndroidVersion(): Int {
        return Build.VERSION.SDK_INT
    }

    /**
     * 获取 Android 系统版本名称
     *
     * 如 "13"、"14"、"15" 等。
     *
     * @return Android 版本名称字符串
     */
    fun getAndroidVersionName(): String {
        return Build.VERSION.RELEASE
    }

    /**
     * 获取设备制造商
     *
     * 如 "samsung"、"xiaomi"、"HUAWEI"、"Google" 等。
     *
     * @return 制造商字符串
     */
    fun getManufacturer(): String {
        return Build.MANUFACTURER
    }

    /**
     * 获取系统编译时间
     *
     * 返回系统固件的编译时间戳字符串。
     *
     * @return 编译时间字符串
     */
    fun getBuildTime(): String {
        return Build.TIME.toString()
    }

    /**
     * 获取系统构建指纹
     *
     * 唯一标识系统固件版本的指纹字符串。
     *
     * @return 构建指纹字符串
     */
    fun getFingerprint(): String {
        return Build.FINGERPRINT
    }

    /**
     * 获取硬件名称
     *
     * 如 "qcom"（高通平台）、"exynos"（三星平台）等。
     *
     * @return 硬件名称字符串
     */
    fun getHardware(): String {
        return Build.HARDWARE
    }

    /**
     * 获取主板名称
     *
     * @return 主板名称字符串
     */
    fun getBoard(): String {
        return Build.BOARD
    }

    /**
     * 获取设备序列号
     *
     * 在 Android 10 (API 29) 及以上版本中，序列号访问受到限制，
     * 仅系统应用或具有特定权限的应用可获取，否则可能返回空字符串。
     *
     * @return 序列号字符串，可能为空
     */
    fun getSerial(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (e: SecurityException) {
            ""
        }
    }

    /**
     * 获取设备当前语言
     *
     * 返回 IETF 语言标签，如 "zh"、"en"、"ja" 等。
     *
     * @return 语言代码字符串
     */
    fun getLanguage(): String {
        return Locale.getDefault().language
    }

    /**
     * 获取设备当前国家/地区代码
     *
     * 如 "CN"、"US"、"JP" 等。
     *
     * @return 国家/地区代码字符串
     */
    fun getCountry(): String {
        return Locale.getDefault().country
    }

    /**
     * 获取设备当前时区 ID
     *
     * 如 "Asia/Shanghai"、"America/New_York" 等。
     *
     * @return 时区 ID 字符串
     */
    fun getTimezone(): String {
        return TimeZone.getDefault().id
    }

    /**
     * 判断设备是否运行在模拟器中
     *
     * 通过检查 Build 属性中的模拟器特征进行判断。
     *
     * @return true 为模拟器，false 为真机
     */
    fun isEmulator(): Boolean {
        val buildProps = listOf(
            Build.FINGERPRINT,
            Build.HARDWARE,
            Build.MODEL,
            Build.MANUFACTURER,
            Build.PRODUCT,
            Build.DEVICE
        )
        return buildProps.any { prop ->
            prop.contains("sdk") ||
                    prop.contains("google_sdk") ||
                    prop.contains("Emulator") ||
                    prop.contains("Android SDK built for x86") ||
                    prop.contains("generic_x86") ||
                    prop.contains("generic_arm64") ||
                    prop.contains("vsoc")
        }
    }

    /**
     * 检查设备是否拥有摄像头
     *
     * 通过 CameraManager 查询设备上的摄像头数量。
     *
     * @param context 上下文
     * @return true 存在至少一个摄像头，false 无摄像头
     */
    fun hasCamera(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查设备是否拥有闪光灯
     *
     * 通过 PackageManager 查询系统是否具有闪光灯特征。
     *
     * @param context 上下文
     * @return true 有闪光灯，false 无闪光灯
     */
    fun hasFlash(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    /**
     * 检查设备是否拥有指纹传感器
     *
     * @param context 上下文
     * @return true 有指纹传感器，false 无
     */
    fun hasFingerprintSensor(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    }

    /**
     * 检查设备是否支持 NFC
     *
     * @param context 上下文
     * @return true 支持 NFC，false 不支持
     */
    fun hasNfc(context: Context): Boolean {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        return nfcAdapter != null
    }

    /**
     * 检查设备是否拥有 GPS 定位功能
     *
     * @param context 上下文
     * @return true 有 GPS，false 无
     */
    fun hasGps(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    }

    /**
     * 获取设备总内存（字节）
     *
     * 通过读取 /proc/meminfo 文件获取总内存大小。
     *
     * @return 总内存字节数，读取失败返回 0
     */
    fun getTotalRam(): Long {
        return try {
            val reader = BufferedReader(FileReader("/proc/meminfo"))
            val firstLine = reader.readLine()
            reader.close()
            val memInfo = firstLine.replace(Regex("[^0-9]"), "")
            memInfo.toLongOrNull()?.times(1024) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取设备可用内存（字节）
     *
     * 通过 ActivityManager.MemoryInfo 查询当前可用内存大小。
     *
     * @param context 上下文
     * @return 可用内存字节数
     */
    fun getAvailableRam(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取内部存储总空间（字节）
     *
     * 获取数据分区的总容量，即设备内部存储的总大小。
     *
     * @return 内部存储总字节数
     */
    fun getTotalStorage(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取内部存储可用空间（字节）
     *
     * 获取数据分区当前可用的存储容量。
     *
     * @return 内部存储可用字节数
     */
    fun getAvailableStorage(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取设备 CPU 架构列表
     *
     * 返回设备支持的 CPU ABI 架构列表，如 ["arm64-v8a", "armeabi-v7a"]。
     *
     * @return CPU 架构字符串列表
     */
    fun getCpuAbi(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            @Suppress("DEPRECATION")
            listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotBlank() }
        }
    }
}
