package com.apex.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import com.apex.util.CryptoUtils
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 应用包工具类，提供包管理、应用信息查询、签名验证等常用操作
 */
object PackageUtils {

    /**
     * 获取应用的版本名称
     *
     * @param context 上下文
     * @return 版本名称字符串，失败返回 null
     */
    fun getAppVersionName(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 获取应用的版本号
     *
     * @param context 上下文
     * @return 版本号（Long），失败返回 null
     */
    fun getAppVersionCode(context: Context): Long? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 获取应用名称
     *
     * @param context 上下文
     * @return 应用名称，失败返回 null
     */
    fun getAppName(context: Context): String? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 获取当前应用的包名
     *
     * @param context 上下文
     * @return 包名字符串
     */
    fun getPackageName(context: Context): String {
        return context.packageName
    }

    /**
     * 获取应用图标
     *
     * @param context 上下文
     * @return 应用图标 Drawable，失败返回 null
     */
    fun getApplicationIcon(context: Context): Drawable? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
            applicationInfo.loadIcon(packageManager)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * 检查指定包名的应用是否已安装
     *
     * @param context 上下文
     * @param packageName 待检查的包名
     * @return 如果已安装返回 true
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
     * 获取应用的首次安装时间
     *
     * @param context 上下文
     * @param packageName 包名，默认为当前应用
     * @return 首次安装时间戳（毫秒）
     */
    fun getAppInstallTime(context: Context, packageName: String? = null): Long {
        return try {
            val pkg = packageName ?: context.packageName
            val packageInfo = context.packageManager.getPackageInfo(pkg, 0)
            packageInfo.firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }
    }

    /**
     * 获取应用的最后更新时间
     *
     * @param context 上下文
     * @param packageName 包名，默认为当前应用
     * @return 最后更新时间戳（毫秒）
     */
    fun getAppUpdateTime(context: Context, packageName: String? = null): Long {
        return try {
            val pkg = packageName ?: context.packageName
            val packageInfo = context.packageManager.getPackageInfo(pkg, 0)
            packageInfo.lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }
    }

    /**
     * 获取所有已安装的应用列表
     *
     * @param context 上下文
     * @return ApplicationInfo 列表
     */
    fun getInstalledApps(context: Context): List<ApplicationInfo> {
        val packageManager = context.packageManager
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
    }

    /**
     * 检查指定应用是否为系统应用
     *
     * @param context 上下文
     * @param packageName 包名
     * @return 如果是系统应用返回 true
     */
    fun isSystemApp(context: Context, packageName: String): Boolean {
        return try {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取应用声明的权限列表
     *
     * @param context 上下文
     * @param packageName 包名
     * @return 权限名称数组
     */
    fun getAppPermissions(context: Context, packageName: String): Array<String> {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            packageInfo.requestedPermissions ?: emptyArray()
        } catch (e: PackageManager.NameNotFoundException) {
            emptyArray()
        }
    }

    /**
     * 获取应用的签名证书 SHA-1 哈希值
     *
     * @param context 上下文
     * @param packageName 包名，默认为当前应用
     * @return SHA-1 哈希字符串，失败返回 null
     */
    fun getSignatureHash(context: Context, packageName: String? = null): String? {
        return try {
            val pkg = packageName ?: context.packageName
            val packageInfo = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            val signatures = packageInfo.signatures
            if (signatures != null && signatures.isNotEmpty()) {
                val cert = signatures[0].toByteArray()
                val md = MessageDigest.getInstance("SHA-1")
                val digest = md.digest(cert)
                CryptoUtils.byteArrayToHex(digest)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 打开（启动）指定包名的应用
     *
     * @param context 上下文
     * @param packageName 待启动应用的包名
     */
    fun openApp(context: Context, packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // 静默处理启动失败
        }
    }

    /**
     * 卸载指定包名的应用（通过系统卸载 Intent）
     *
     * @param context 上下文
     * @param packageName 待卸载应用的包名
     */
    fun uninstallApp(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 静默处理卸载失败
        }
    }

    /**
     * 获取应用的目标 SDK 版本
     *
     * @param context 上下文
     * @param packageName 包名，默认为当前应用
     * @return 目标 SDK 版本号
     */
    fun getAppTargetSdk(context: Context, packageName: String? = null): Int {
        return try {
            val pkg = packageName ?: context.packageName
            val applicationInfo = context.packageManager.getApplicationInfo(pkg, 0)
            applicationInfo.targetSdkVersion
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }

    /**
     * 获取应用的最低支持 SDK 版本
     *
     * @param context 上下文
     * @param packageName 包名，默认为当前应用
     * @return 最低 SDK 版本号
     */
    fun getAppMinSdk(context: Context, packageName: String? = null): Int {
        return try {
            val pkg = packageName ?: context.packageName
            val applicationInfo = context.packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            applicationInfo.minSdkVersion
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }
}
