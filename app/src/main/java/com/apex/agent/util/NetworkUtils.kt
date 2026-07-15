package com.apex.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import com.apex.agent.R
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 网络连接工具类
 *
 * 提供网络状态检测、连接类型判断、WiFi 信息获取、
 * 移动网络制式识别、网络质量评估等功能。
 */
object NetworkUtils {

    /**
     * 网络速度等级枚举
     *
     * 根据网络类型和信号强度评估的网络速度等级。
     */
    enum class NetworkSpeed {
        /** 低速网络（2G、弱信号等） */
        LOW,
        /** 中速网络（3G、一般 WiFi 等） */
        MEDIUM,
        /** 高速网络（4G LTE、良好 WiFi 等） */
        HIGH,
        /** 极速网络（5G、千兆 WiFi 等） */
        VERY_HIGH
    }

    /**
     * 检查设备是否连接到网络
     *
     * @param context 上下文
     * @return true 已连接，false 未连接
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    /**
     * 获取网络连接类型
     *
     * @param context 上下文
     * @return 网络类型字符串（如 "WiFi"、"4G"、"未连接" 等）
     */
    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return context.getString(R.string.not_connected)
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return context.getString(R.string.not_connected)
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> context.getString(R.string.mobile_data)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> context.getString(R.string.ethernet)
            else -> context.getString(R.string.other_network)
        }
    }

    /**
     * 检查 WiFi 是否已连接
     *
     * @param context 上下文
     * @return true WiFi 已连接，false 未连接或非 WiFi 网络
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 检查移动数据是否已连接
     *
     * @param context 上下文
     * @return true 移动数据已连接，false 未连接或非蜂窝网络
     */
    fun isMobileDataConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 检查 VPN 是否已连接
     *
     * 通过检查活动的网络连接是否包含 VPN 传输层来判断。
     *
     * @param context 上下文
     * @return true VPN 已连接，false 未连接
     */
    fun isVpnConnected(context: Context): Boolean {
        // 方法一：通过 ConnectivityManager 检查 VPN 传输层
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        if (network != null) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        }

        // 方法二：通过检查系统网络接口中的 VPN 接口作为补充
    return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
        if (networkInterface.isUp) {
                    val name = networkInterface.displayName.lowercase()
        if (name.contains("tun") || name.contains("pptp") ||
                        name.contains("ppp") || name.contains("tap") ||
                        name.contains("vpn")
                    ) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取移动网络连接子类型（如 4G、5G 等）
     *
     * @param context 上下文
     * @return 网络子类型字符串（如 "5G"、"4G"、"3G"、"H+"、"H"、"E"、"GPRS"、"Unknown"）
     */
    fun getConnectionSubtype(context: Context): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_LTE_CA -> "4G+"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "H"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "H"
            TelephonyManager.NETWORK_TYPE_HSPA -> "H"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
            TelephonyManager.NETWORK_TYPE_EDGE -> "E"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            else -> "Unknown"
        }
    }

    /**
     * 获取当前连接的 WiFi SSID（网络名称）
     *
     * @param context 上下文
     * @return WiFi SSID，未连接或非 WiFi 时返回 null
     */
    fun getWifiSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val ssid = connectionInfo.ssid
            // 去除可能的引号
    if (ssid != null && ssid != WifiInfo.UNKNOWN_SSID) {
                ssid.removeSurrounding("\"")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取当前 WiFi 信号强度（RSSI，单位 dBm）
     *
     * @param context 上下文
     * @return RSSI 值（范围通常 -100 ~ -55），非 WiFi 环境返回 0
     */
    fun getWifiSignalStrength(context: Context): Int {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.connectionInfo.rssi
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取设备当前 IP 地址
     *
     * 优先返回 WiFi 接口的 IPv4 地址，若不可用则返回其他活跃接口的 IPv4 地址。
     *
     * @param context 上下文
     * @return IP 地址字符串，获取失败返回 null
     */
    fun getIpAddress(context: Context): String? {
        return try {
            // 优先通过 WiFiManager 获取
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xFF,
                    ipInt shr 8 and 0xFF,
                    ipInt shr 16 and 0xFF,
                    ipInt shr 24 and 0xFF
                )
            }

            // 备用：遍历网络接口获取 IPv4 地址
    val interfaces = NetworkInterface.getNetworkInterfaces()
        val addresses = mutableListOf<InetAddress>()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
        if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val interfaceAddresses = networkInterface.inetAddresses
                    while (interfaceAddresses.hasMoreElements()) {
                        val addr = interfaceAddresses.nextElement()
        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addresses.add(addr)
                        }
                    }
                }
            }
            // 优先返回非本地链接地址
            addresses.firstOrNull { !it.isLinkLocalAddress }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查当前网络是否为计费网络（如移动数据，热点等）
     *
     * @param context 上下文
     * @return true 计费网络，false 非计费网络
     */
    fun isNetworkMetered(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.isActiveNetworkMetered
    }

    /**
     * 评估当前网络的传输速度等级
     *
     * 根据连接类型和移动网络子类型综合判断。
     *
     * @param context 上下文
     * @return NetworkSpeed 枚举值
     */
    fun getNetworkSpeedGrade(context: Context): NetworkSpeed {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return NetworkSpeed.LOW
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkSpeed.LOW

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val rssi = getWifiSignalStrength(context)
        when {
                    rssi >= -50 -> NetworkSpeed.VERY_HIGH
                    rssi >= -65 -> NetworkSpeed.HIGH
                    rssi >= -80 -> NetworkSpeed.MEDIUM
                    else -> NetworkSpeed.LOW
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkSpeed.VERY_HIGH
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                when (getConnectionSubtype(context)) {
                    "5G" -> NetworkSpeed.VERY_HIGH
                    "4G+", "4G" -> NetworkSpeed.HIGH
                    "H+", "H", "3G" -> NetworkSpeed.MEDIUM
                    else -> NetworkSpeed.LOW
                }
            }
            else -> NetworkSpeed.LOW
        }
    }

    /**
     * 检查设备当前是否处于网络漫游状态
     *
     * @param context 上下文
     * @return true 漫游中，false 非漫游
     */
    fun isNetworkRoaming(context: Context): Boolean {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.isNetworkRoaming
        } catch (e: Exception) {
            false
        }
    }
}
