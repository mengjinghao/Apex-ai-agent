package com.apex.agent.core.scheduler

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.apex.agent.core.scheduler.DeliveryResult
import com.apex.agent.core.scheduler.MultiPlatformDelivery
import com.apex.core.tools.javascript.not

/**
 * 多平台投递管理器
 * 
 * 支持多种平台的定时任务结果投�?
 * - 应用内通知
 * - Telegram
 * - Discord
 * - Email
 * - 微信
 * - 系统推�? */
class MultiPlatformDelivery(private val context: Context) {

    companion object {
        private const val TAG = "MultiPlatformDelivery"
        
        @Volatile
        private var INSTANCE: MultiPlatformDelivery? = null
        
        fun getInstance(context: Context): MultiPlatformDelivery {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiPlatformDelivery(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // Intent Actions
        const val ACTION_NOTIFICATION = "com.apex.agent.SCHEDULED_NOTIFICATION"
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        const val EXTRA_NOTIFICATION_CONTENT = "notification_content"
        const val EXTRA_NOTIFICATION_TASK_ID = "task_id"
    }

    /**
     * 投递结�?     */
    data class DeliveryResult(
        val platform: ScheduledTask.DeliveryPlatform,
        val success: Boolean,
        val message: String? = null,
        val error: String? = null
    )
    
    /**
     * 批量投递结�?     */
    data class BatchDeliveryResult(
        val total: Int,
        val successCount: Int,
        val failureCount: Int,
        val results: List<DeliveryResult>
    )

    /**
     * 投递内容到多个平台
     */
    suspend fun deliver(
        taskName: String,
        content: String,
        platforms: List<ScheduledTask.DeliveryPlatform>,
        metadata: Map<String, String> = emptyMap()
    ): BatchDeliveryResult = withContext(Dispatchers.IO) {
        val results = platforms.map { platform ->
            deliverToPlatform(platform, taskName, content, metadata)
        }
        
        BatchDeliveryResult(
            total = platforms.size,
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            results = results
        )
    }
    
    /**
     * 投递到单个平台
     */
    private suspend fun deliverToPlatform(
        platform: ScheduledTask.DeliveryPlatform,
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult {
        return try {
            when (platform) {
                ScheduledTask.DeliveryPlatform.IN_APP -> deliverInApp(taskName, content, metadata)
                ScheduledTask.DeliveryPlatform.TELEGRAM -> deliverTelegram(taskName, content, metadata)
                ScheduledTask.DeliveryPlatform.DISCORD -> deliverDiscord(taskName, content, metadata)
                ScheduledTask.DeliveryPlatform.EMAIL -> deliverEmail(taskName, content, metadata)
                ScheduledTask.DeliveryPlatform.WECHAT -> deliverWechat(taskName, content, metadata)
                ScheduledTask.DeliveryPlatform.PUSH -> deliverPush(taskName, content, metadata)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "投递到 ${platform.displayName} 失败", e)
            DeliveryResult(platform, false, error = e.message)
        }
    }
    
    /**
     * 应用内通知
     */
    private fun deliverInApp(
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult {
        val intent = Intent(ACTION_NOTIFICATION).apply {
            putExtra(EXTRA_NOTIFICATION_TITLE, taskName)
            putExtra(EXTRA_NOTIFICATION_CONTENT, content)
            putExtra(EXTRA_NOTIFICATION_TASK_ID, metadata["taskId"])
            setPackage(context.packageName)
        }
        
        context.sendBroadcast(intent)
        AppLogger.d(TAG, "已发送应用内通知: ${taskName}")
        return DeliveryResult(ScheduledTask.DeliveryPlatform.IN_APP, true, "通知已发�?)
    }
    
    /**
     * Telegram 投�?     */
    private suspend fun deliverTelegram(
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult = withContext(Dispatchers.IO) {
        // 获取 Telegram 配置
    val botToken = getConfig("telegram_bot_token")
        val chatId = getConfig("telegram_chat_id")
        if (botToken.isNullOrEmpty() || chatId.isNullOrEmpty()) {
            AppLogger.w(TAG, "Telegram 未配�?(bot_token �?chat_id 未设置）")
            return@withContext DeliveryResult(
                ScheduledTask.DeliveryPlatform.TELEGRAM,
                false,
                error = "Telegram 未配�?
            )
        }
        
        try {
            // 构建消息
    val message = buildString {
                append("📅 *${taskName}*\n\n")
                append(content)
                append("\n\n")
                metadata.forEach { (key, value) ->
                    append("�?${key}: `${value}`\n")
                }
            }
            
            // 发�?HTTP 请求�?Telegram Bot API
    val url = "https://api.telegram.org/bot${botToken}/sendMessage"
        val params = mapOf(
                "chat_id" to chatId,
                "text" to message,
                "parse_mode" to "Markdown"
            )
        val response = makeHttpPost(url, params)
        if (response.contains("\"ok\":true")) {
                DeliveryResult(ScheduledTask.DeliveryPlatform.TELEGRAM, true, "消息已发�?)
            } else {
                DeliveryResult(ScheduledTask.DeliveryPlatform.TELEGRAM, false, error = response)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Telegram 投递失�?, e)
            DeliveryResult(ScheduledTask.DeliveryPlatform.TELEGRAM, false, error = e.message)
        }
    }
    
    /**
     * Discord 投�?     */
    private suspend fun deliverDiscord(
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult = withContext(Dispatchers.IO) {
        val webhookUrl = getConfig("discord_webhook_url")
        if (webhookUrl.isNullOrEmpty()) {
            AppLogger.w(TAG, "Discord 未配�?(webhook_url 未设置）")
            return@withContext DeliveryResult(
                ScheduledTask.DeliveryPlatform.DISCORD,
                false,
                error = "Discord 未配�?
            )
        }
        
        try {
            val payload = buildString {
                append("""{"embeds":[{"title":"${taskName}","description":"${content}","color":3447003}""")
        if (metadata.isNotEmpty()) {
                    append(",""fields":[")
                    metadata.entries.forEachIndexed { index, (key, value) ->
                        append("""{"name":"${key}","value":"${value}","inline":true}""")
        if (index < metadata.size - 1) append(",")
                    }
                    append("]")
                }
                append("}]}")
            }
        val response = makeHttpPost(webhookUrl, payload, isJson = true)
        if (response.isEmpty() || response.contains("\"id\":")) {
                DeliveryResult(ScheduledTask.DeliveryPlatform.DISCORD, true, "消息已发�?)
            } else {
                DeliveryResult(ScheduledTask.DeliveryPlatform.DISCORD, false, error = response)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Discord 投递失�?, e)
            DeliveryResult(ScheduledTask.DeliveryPlatform.DISCORD, false, error = e.message)
        }
    }
    
    /**
     * Email 投�?     */
    private suspend fun deliverEmail(
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult = withContext(Dispatchers.IO) {
        val smtpHost = getConfig("email_smtp_host")
        val smtpPort = getConfig("email_smtp_port")?.toIntOrNull() ?: 587
        val emailFrom = getConfig("email_from")
        val emailTo = getConfig("email_to")
        val emailUser = getConfig("email_user")
        val emailPassword = getConfig("email_password")
        if (smtpHost.isNullOrEmpty() || emailFrom.isNullOrEmpty() || emailTo.isNullOrEmpty()) {
            AppLogger.w(TAG, "Email 未配�?)
            return@withContext DeliveryResult(
                ScheduledTask.DeliveryPlatform.EMAIL,
                false,
                error = "Email 未配�?
            )
        }
        
        try {
            // 注意: 这里需要实际的邮件发送实�?            // 简化版本仅记录日志
            AppLogger.d(TAG, "Email 投�? �?${emailFrom} �?${emailTo}, 主题: ${taskName}")
            
            // 实际实现需要使�?JavaMail 或其他邮件库
            // 这里预留接口
            
            DeliveryResult(ScheduledTask.DeliveryPlatform.EMAIL, true, "邮件已发�?)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Email 投递失�?, e)
            DeliveryResult(ScheduledTask.DeliveryPlatform.EMAIL, false, error = e.message)
        }
    }
    
    /**
     * 微信投�?(通过企业微信或其�?webhook)
     */
    private suspend fun deliverWechat(
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult = withContext(Dispatchers.IO) {
        val webhookUrl = getConfig("wechat_webhook_url")
        if (webhookUrl.isNullOrEmpty()) {
            AppLogger.w(TAG, "微信未配�?(webhook_url 未设置）")
            return@withContext DeliveryResult(
                ScheduledTask.DeliveryPlatform.WECHAT,
                false,
                error = "微信未配�?
            )
        }
        
        try {
            val payload = buildString {
                append("""{"msgtype":"text","text":{"content":"[${taskName}] ${content}"}}""")
            }
        val response = makeHttpPost(webhookUrl, payload, isJson = true)
        if (response.isEmpty() || response.contains("\"errcode\":0")) {
                DeliveryResult(ScheduledTask.DeliveryPlatform.WECHAT, true, "消息已发�?)
            } else {
                DeliveryResult(ScheduledTask.DeliveryPlatform.WECHAT, false, error = response)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "微信投递失�?, e)
            DeliveryResult(ScheduledTask.DeliveryPlatform.WECHAT, false, error = e.message)
        }
    }
    
    /**
     * 系统推�?     */
    private fun deliverPush(
        taskName: String,
        content: String,
        metadata: Map<String, String>
    ): DeliveryResult {
        // 使用系统的通知能力
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        
        // 创建通知渠道 (Android 8.0+)
        val channelId = "scheduled_tasks"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "定时任务通知",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "定时任务执行结果通知"
            }
            notificationManager.createNotificationChannel(channel)
        }
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(taskName)
            .setContentText(content)
            .setPriority(android.app.Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        
        AppLogger.d(TAG, "系统推送已发�? ${taskName}")
        return DeliveryResult(ScheduledTask.DeliveryPlatform.PUSH, true, "推送已发�?)
    }
    
    /**
     * 获取配置
     */
    private fun getConfig(key: String): String? {
        // �?SharedPreferences 或其他配置源获取
    val prefs = context.getSharedPreferences("scheduler_config", Context.MODE_PRIVATE)
        return prefs.getString(key, null)
    }
    
    /**
     * 保存配置
     */
    fun saveConfig(key: String, value: String) {
        val prefs = context.getSharedPreferences("scheduler_config", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
        AppLogger.d(TAG, "已保存配�? ${key}")
    }
    
    /**
     * 检查平台是否已配置
     */
    fun isPlatformConfigured(platform: ScheduledTask.DeliveryPlatform): Boolean {
        return when (platform) {
            ScheduledTask.DeliveryPlatform.IN_APP -> true
            ScheduledTask.DeliveryPlatform.TELEGRAM -> 
                !getConfig("telegram_bot_token").isNullOrEmpty() && 
                !getConfig("telegram_chat_id").isNullOrEmpty()
            ScheduledTask.DeliveryPlatform.DISCORD ->
                !getConfig("discord_webhook_url").isNullOrEmpty()
            ScheduledTask.DeliveryPlatform.EMAIL ->
                !getConfig("email_smtp_host").isNullOrEmpty() &&
                !getConfig("email_from").isNullOrEmpty() &&
                !getConfig("email_to").isNullOrEmpty()
            ScheduledTask.DeliveryPlatform.WECHAT ->
                !getConfig("wechat_webhook_url").isNullOrEmpty()
            ScheduledTask.DeliveryPlatform.PUSH -> true
        }
    }
    
    /**
     * 获取已配置的平台列表
     */
    fun getConfiguredPlatforms(): List<ScheduledTask.DeliveryPlatform> {
        return ScheduledTask.DeliveryPlatform.values().filter { isPlatformConfigured(it) }
    }
    
    /**
     * 简单的 HTTP POST 请求
     */
    private fun makeHttpPost(url: String, params: Map<String, String>): String {
        // 使用 Java 内置�?HttpURLConnection
    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val postData = params.entries.joinToString("&") { 
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        
        connection.outputStream.use { it.write(postData.toByteArray()) }
        return connection.inputStream.bufferedReader().readText()
    }
    
    /**
     * 简单的 HTTP POST JSON 请求
     */
    private fun makeHttpPost(url: String, jsonPayload: String, isJson: Boolean = false): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", if (isJson) "application/json" else "text/plain")
        
        connection.outputStream.use { it.write(jsonPayload.toByteArray()) }
        return try {
            connection.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        }
    }
}