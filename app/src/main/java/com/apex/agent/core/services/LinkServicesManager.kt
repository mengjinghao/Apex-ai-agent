package com.apex.core.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.apex.data.preferences.UserPreferencesManager
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class LinkServicesManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "LinkServicesManager"
        private const val CONNECTION_TIMEOUT = 10000
        private const val READ_TIMEOUT = 15000
        private const val PING_INTERVAL = 30000L

        @Volatile
        private var INSTANCE: LinkServicesManager? = null

        fun getInstance(context: Context): LinkServicesManager {
            return INSTANCE ?: synchronized(this) {
                val instance = LinkServicesManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mainHandler = Handler(Looper.getMainLooper())
        private var monitoringJob: Job? = null
    private var pingRunnable: Runnable? = null

    private var isWechatClawbotEnabled = false
    private var wechatClawbotServerUrl = ""
        private var isLinkServicesEnabled = false
    private var linkServicesServerUrl = ""
        private var isConnected = false
    private var lastPingTime = 0L

    sealed class LinkServiceStatus {
        object Disconnected : LinkServiceStatus()
        object Connecting : LinkServiceStatus()
        data class Connected(val serviceType: String) : LinkServiceStatus()
        data class Error(val message: String) : LinkServiceStatus()
    }

    interface LinkServiceCallback {
        fun onStatusChanged(status: LinkServiceStatus)
        fun onMessageReceived(message: String)
        fun onCommandReceived(command: String)
    }
        private var callback: LinkServiceCallback? = null

    fun setCallback(callback: LinkServiceCallback) {
        this.callback = callback
    }
        fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = serviceScope.launch {
            val preferences = UserPreferencesManager.getInstance(context)

            launch {
                preferences.wechatClawbotEnabled.collectLatest { enabled ->
                    isWechatClawbotEnabled = enabled
                    AppLogger.d(TAG, "WeChat clawbot enabled changed: ${enabled}")
                    updateServiceState()
                }
            }

            launch {
                preferences.wechatClawbotServerUrl.collectLatest { url ->
                    wechatClawbotServerUrl = url
                    AppLogger.d(TAG, "WeChat clawbot server URL changed: ${url}")
                    updateServiceState()
                }
            }

            launch {
                preferences.linkServicesEnabled.collectLatest { enabled ->
                    isLinkServicesEnabled = enabled
                    AppLogger.d(TAG, "Link services enabled changed: ${enabled}")
                    updateServiceState()
                }
            }

            launch {
                preferences.linkServicesServerUrl.collectLatest { url ->
                    linkServicesServerUrl = url
                    AppLogger.d(TAG, "Link services server URL changed: ${url}")
                    updateServiceState()
                }
            }
        }
    }
        fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        stopPing()
        disconnect()
    }
        private fun updateServiceState() {
        val shouldConnect = (isWechatClawbotEnabled && wechatClawbotServerUrl.isNotBlank()) ||
                (isLinkServicesEnabled && linkServicesServerUrl.isNotBlank())
        if (shouldConnect && !isConnected) {
            connect()
        } else if (!shouldConnect && isConnected) {
            disconnect()
        }
    }
        private fun connect() {
        if (isConnected) return

        val serverUrl = when {
            isWechatClawbotEnabled && wechatClawbotServerUrl.isNotBlank() -> wechatClawbotServerUrl
            isLinkServicesEnabled && linkServicesServerUrl.isNotBlank() -> linkServicesServerUrl
            else -> return
        }

        callback?.onStatusChanged(LinkServiceStatus.Connecting)

        thread {
            try {
                AppLogger.d(TAG, "Connecting to link service: ${serverUrl}")
        val result = testConnection(serverUrl)
        if (result) {
                    isConnected = true
                    lastPingTime = System.currentTimeMillis()
                    mainHandler.post {
                        val serviceType = if (isWechatClawbotEnabled) "WeChat Clawbot" else "Link Service"
                        callback?.onStatusChanged(LinkServiceStatus.Connected(serviceType))
                    }
                    startPing()
                } else {
                    mainHandler.post {
                        callback?.onStatusChanged(LinkServiceStatus.Error("Connection test failed"))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to connect to link service", e)
                mainHandler.post {
                    callback?.onStatusChanged(LinkServiceStatus.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }
        private fun disconnect() {
        stopPing()
        isConnected = false
        callback?.onStatusChanged(LinkServiceStatus.Disconnected)
        AppLogger.d(TAG, "Disconnected from link service")
    }
        private fun startPing() {
        stopPing()

        pingRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) return
                performPing()
                mainHandler.postDelayed(this, PING_INTERVAL)
            }
        }
        mainHandler.postDelayed(pingRunnable!!, PING_INTERVAL)
    }
        private fun stopPing() {
        pingRunnable?.let { mainHandler.removeCallbacks(it) }
        pingRunnable = null
    }
        private fun performPing() {
        val serverUrl = when {
            isWechatClawbotEnabled && wechatClawbotServerUrl.isNotBlank() -> wechatClawbotServerUrl
            isLinkServicesEnabled && linkServicesServerUrl.isNotBlank() -> linkServicesServerUrl
            else -> return
        }

        thread {
            try {
                val response = sendRequest(serverUrl, "ping", mapOf("timestamp" to System.currentTimeMillis().toString()))
        if (response != null) {
                    lastPingTime = System.currentTimeMillis()
                    AppLogger.v(TAG, "Ping successful: ${response}")
        if (!isConnected) {
                        isConnected = true
                        mainHandler.post {
                            val serviceType = if (isWechatClawbotEnabled) "WeChat Clawbot" else "Link Service"
                            callback?.onStatusChanged(LinkServiceStatus.Connected(serviceType))
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Ping failed", e)
                isConnected = false
                mainHandler.post {
                    callback?.onStatusChanged(LinkServiceStatus.Error("Connection lost: ${e.message}"))
                }
            }
        }
    }
        private fun testConnection(serverUrl: String): Boolean {
        return try {
            val response = sendRequest(serverUrl, "test", emptyMap())
            response != null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Connection test failed", e)
            false
        }
    }
        fun sendCommand(command: String, params: Map<String, String> = emptyMap()): String? {
        val serverUrl = when {
            isWechatClawbotEnabled && wechatClawbotServerUrl.isNotBlank() -> wechatClawbotServerUrl
            isLinkServicesEnabled && linkServicesServerUrl.isNotBlank() -> linkServicesServerUrl
            else -> return null
        }
        return try {
            val allParams = params.toMutableMap()
            allParams["command"] = command
            sendRequest(serverUrl, "command", allParams)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to send command", e)
            null
        }
    }
        fun requestWechatAction(action: String, params: Map<String, String> = emptyMap()): String? {
        if (!isWechatClawbotEnabled || wechatClawbotServerUrl.isBlank()) {
            return null
        }
        val allParams = params.toMutableMap()
        allParams["action"] = action
        return sendRequest(wechatClawbotServerUrl, "wechat/${action}", allParams)
    }
        private fun sendRequest(baseUrl: String, endpoint: String, params: Map<String, String>): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("${baseUrl}/${endpoint.removePrefix("/")}")
        val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val postData = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(postData)
                    writer.flush()
                }
        val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                } else {
                    AppLogger.w(TAG, "HTTP response code: ${responseCode}")
                    null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Request failed: ${endpoint}", e)
                null
            }
        }
    }
        fun isServiceConnected(): Boolean = isConnected

    fun getLastPingTime(): Long = lastPingTime
}