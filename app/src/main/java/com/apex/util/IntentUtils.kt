package com.apex.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Intent 工具类，提供创建和启动常用系统 Intent 的便捷方法
 *
 * 覆盖浏览器、拨号、短信、邮件、设置页面、应用市场、分享、相机、图库等
 * 常见系统功能的 Intent 创建与启动。
 */
object IntentUtils {

    /**
     * 检查 Intent 是否可以被系统中某个应用处理
     *
     * 在启动隐式 Intent 前调用以避免 ActivityNotFoundException。
     *
     * @param context 上下文
     * @param intent 待检查的 Intent
     * @return true 存在可处理该 Intent 的应用，false 无可用应用
     */
    fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return intent.resolveActivity(context.packageManager) != null
    }

    /**
     * 创建系统选择器（Chooser）Intent
     *
     * 包装传入的 Intent，弹出应用选择器让用户选择处理应用。
     *
     * @param intent 原始 Intent
     * @param title 选择器标题
     * @return 包装后的选择器 Intent
     */
    fun createChooser(intent: Intent, title: String): Intent {
        return Intent.createChooser(intent, title)
    }

    /**
     * 在浏览器中打开指定 URL
     *
     * 使用 ACTION_VIEW 启动浏览器应用。
     *
     * @param context 上下文
     * @param url 要打开的网页地址（如 "https://www.example.com"）
     */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isIntentAvailable(context, intent)) {
            context.startActivity(intent)
        }
    }

    /**
     * 打开拨号界面并填入电话号码
     *
     * 使用 ACTION_DIAL 进入拨号界面，需要 CALL_PHONE 权限。
     *
     * @param context 上下文
     * @param phoneNumber 电话号码字符串
     */
    fun openDial(context: Context, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 打开短信发送界面
     *
     * 使用 ACTION_SENDTO 跳转到短信应用，可附带默认消息内容。
     *
     * @param context 上下文
     * @param phoneNumber 收件人电话号码
     * @param message 预设的短信内容（可选）
     */
    fun sendSms(context: Context, phoneNumber: String, message: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (message != null) {
                putExtra("sms_body", message)
            }
        }
        context.startActivity(intent)
    }

    /**
     * 打开邮件发送界面
     *
     * 使用 ACTION_SENDTO 跳转到邮件应用，可预设收件人、主题和正文。
     *
     * @param context 上下文
     * @param to 收件人邮箱地址
     * @param subject 邮件主题（可选）
     * @param body 邮件正文（可选）
     */
    fun sendEmail(context: Context, to: String, subject: String? = null, body: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body != null) putExtra(Intent.EXTRA_TEXT, body)
        }
        if (isIntentAvailable(context, intent)) {
            context.startActivity(intent)
        }
    }

    /**
     * 打开当前应用的系统设置页面
     *
     * 跳转到应用详情设置页，用户可在此授权权限、清除数据等。
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
     * 打开 WiFi 设置页面
     *
     * 跳转到系统 WiFi 列表和连接管理页面。
     *
     * @param context 上下文
     */
    fun openWifiSettings(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 打开蓝牙设置页面
     *
     * 跳转到系统蓝牙设备管理页面。
     *
     * @param context 上下文
     */
    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 打开位置信息设置页面
     *
     * 跳转到系统位置服务设置页面。
     *
     * @param context 上下文
     */
    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 在 Google Play 应用商店中打开应用页面
     *
     * 未指定包名时默认打开当前应用在商店的页面。
     *
     * @param context 上下文
     * @param packageName 目标应用包名，为空时使用当前应用包名
     */
    fun openAppInMarket(context: Context, packageName: String? = null) {
        val pkg = packageName ?: context.packageName
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isIntentAvailable(context, intent)) {
            context.startActivity(intent)
        } else {
            openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    /**
     * 分享文本内容
     *
     * 使用 ACTION_SEND 将文本分享给其他应用，如微信、QQ、短信等。
     *
     * @param context 上下文
     * @param text 要分享的文本内容
     * @param title 选择器标题（可选）
     */
    fun shareText(context: Context, text: String, title: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(createChooser(intent, title ?: "分享"))
    }

    /**
     * 分享文件
     *
     * 使用 FileProvider 生成内容 URI，将文件分享给其他应用。
     * 注意需要在 AndroidManifest.xml 中配置对应的 FileProvider。
     *
     * @param context 上下文
     * @param uri 文件的 content URI（通过 FileProvider 获取）
     * @param mimeType 文件的 MIME 类型，默认为 "*/*"
     */
    fun shareFile(context: Context, uri: Uri, mimeType: String = "*/*") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(createChooser(intent, "分享文件"))
    }

    /**
     * 打开相机拍照
     *
     * 使用 ACTION_IMAGE_CAPTURE 启动系统相机应用。
     * 若提供了输出 URI，照片将保存到指定位置；否则返回缩略图。
     *
     * @param context 上下文
     * @param uri 照片输出路径的 content URI（可选，通过 FileProvider 获取）
     */
    fun openCamera(context: Context, uri: Uri? = null) {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uri != null) {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        if (isIntentAvailable(context, intent)) {
            context.startActivity(intent)
        }
    }

    /**
     * 打开图库选择图片
     *
     * 使用 ACTION_PICK 或 ACTION_GET_CONTENT 启动系统图库。
     *
     * @param context 上下文
     * @param mimeType 要选择的文件 MIME 类型，默认为 "image/*"
     */
    fun openGallery(context: Context, mimeType: String = "image/*") {
        val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = mimeType
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isIntentAvailable(context, intent)) {
            context.startActivity(intent)
        }
    }

    /**
     * 拨打电话
     *
     * 根据 directCall 参数决定是直接拨号还是打开拨号界面：
     * - directCall = true：直接拨号，需要 CALL_PHONE 权限
     * - directCall = false：打开拨号界面并填入号码，无需特殊权限
     *
     * @param context 上下文
     * @param phoneNumber 电话号码
     * @param directCall true 直接拨号，false 仅打开拨号界面，默认为 false
     */
    fun makeCall(context: Context, phoneNumber: String, directCall: Boolean = false) {
        val action = if (directCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isIntentAvailable(context, intent)) {
            context.startActivity(intent)
        }
    }
}
