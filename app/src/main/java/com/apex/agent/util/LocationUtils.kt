package com.apex.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.apex.util.AppLogger
import java.util.Locale
import java.util.TimeZone
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 位置服务工具类
 *
 * 提供设备定位、中国大陆检测、位置权限检查、定位服务状态查询等功能。
 * 使用 Android 原生 API（非 Google Play Services），适用于国内设备。
 */
object LocationUtils {

    private const val TAG = "LocationUtils"

    /**
     * 使用 Android 原生 API，检测设备是否在中国大陆
     *
     * 此方法不依赖任何 Google Play Services，按以下顺序判断：
     * 1. 通过 TelephonyManager 获取运营商国家代码
     * 2. 检查系统时区是否为 Asia/Shanghai 或 Asia/Urumqi
     * 3. 如有位置权限，获取最后已知位置进行反地理编码
     * 4. 获取当前位置进行反地理编码
     * 5. 回退到坐标范围判断（中国大陆粗略矩形边界）
     *
     * @param context 上下文
     * @return true 设备在中国大陆，false 不在或无法判断
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun isDeviceInMainlandChina(context: Context): Boolean {
        // 快速、无需权限的启发式检测
        getCountryIsoByTelephony(context)?.let { iso ->
            if (iso.equals("CN", true)) return true
        }
        if (isChinaTimezone()) return true

        if (!hasLocationPermission(context)) {
            AppLogger.w(TAG, "No location permission; returning result from heuristics only.")
        return false
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (lastKnownLocation != null) {
                AppLogger.d(TAG, "Got last known location from native API.")
        return getCountryFromLocation(context, lastKnownLocation)
            }
            AppLogger.d(TAG, "No last known location, requesting current location update.")
        val currentLocation = getCurrentLocationNative(context, locationManager)
        return getCountryFromLocation(context, currentLocation)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get location using native API", e)
        return false
        }
    }

    /**
     * 封装原生 API 的回调，以协程方式获取当前位置
     *
     * @param context 上下文
     * @param locationManager LocationManager 实例
     * @return 获取到的 Location 对象
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
        private suspend fun getCurrentLocationNative(context: Context, locationManager: LocationManager): Location {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                locationManager.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER,
                    cancellationSignal,
                    context.mainExecutor,
                    Consumer { location ->
                        if (location != null) {
                            continuation.resume(location)
                        } else {
                            continuation.resumeWithException(RuntimeException("Failed to get location from Network provider."))
                        }
                    }
                )
            }
        } else {
            @Suppress("DEPRECATION")
        return suspendCancellableCoroutine { continuation ->
                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        continuation.resume(location)
                    }

                    override fun onProviderDisabled(provider: String) {
                        locationManager.removeUpdates(this)
                        continuation.resumeWithException(RuntimeException("Provider ${provider} disabled"))
                    }
                }
                continuation.invokeOnCancellation { locationManager.removeUpdates(locationListener) }
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, context.mainLooper)
            }
        }
    }

    /**
     * 将 Location 对象通过 Geocoder 转换为国家代码并进行判断
     *
     * @param context 上下文
     * @param location 位置对象
     * @return true 在中国大陆，false 不在或无法判断
     */
    private suspend fun getCountryFromLocation(context: Context, location: Location): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        try {
                            Geocoder(context, Locale.getDefault()).getFromLocation(
                                location.latitude,
                                location.longitude,
                                1,
                                object : Geocoder.GeocodeListener {
                                    override fun onGeocode(results: MutableList<Address>) {
                                        cont.resume(results)
                                    }

                                    override fun onError(errorMessage: String) {
                                        AppLogger.w(TAG, "Geocoder error: ${errorMessage}")
                                        cont.resume(emptyList())
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocation(
                        location.latitude,
                        location.longitude,
                        1
                    )
                }
        if (!addresses.isNullOrEmpty()) {
                    val countryCode = addresses[0].countryCode
                    AppLogger.d(TAG, "Detected country code: ${countryCode}")
                    return@withContext "CN".equals(countryCode, ignoreCase = true)
                }
        val inBounds = isWithinMainlandChinaBounds(location.latitude, location.longitude)
        if (!inBounds) AppLogger.w(TAG, "Coordinates outside CN bounds; lat=${location.latitude}, lon=${location.longitude}")
                inBounds
            } catch (e: Exception) {
                AppLogger.e(TAG, "Geocoder failed", e)
                false
            }
        }
    }

    /**
     * 判断坐标是否在中国大陆粗略边界内（排除台湾、香港、澳门）
     *
     * @param lat 纬度
     * @param lon 经度
     * @return true 在大陆边界内，false 在外
     */
    private fun isWithinMainlandChinaBounds(lat: Double, lon: Double): Boolean {
        val inRoughBounds = lat in 18.0..54.0 && lon in 73.0..135.0
        if (!inRoughBounds) return false
        val inTaiwan = lat in 20.5..25.6 && lon in 119.3..122.0
        val inHongKong = lat in 22.1..22.6 && lon in 113.8..114.4
        val inMacau = lat in 22.08..22.23 && lon in 113.52..113.65
        return !(inTaiwan || inHongKong || inMacau)
    }

    /**
     * 通过 TelephonyManager 获取运营商国家代码
     *
     * @param context 上下文
     * @return 国家 ISO 代码（如 "CN"），获取失败返回 null
     */
    private fun getCountryIsoByTelephony(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val networkIso = tm.networkCountryIso?.trim()
        val simIso = tm.simCountryIso?.trim()
        val iso = (networkIso?.ifBlank { null } ?: simIso?.ifBlank { null })
            iso?.uppercase(Locale.ROOT)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Telephony country ISO unavailable", e)
            null
        }
    }

    /**
     * 检查当前系统时区是否为中国时区
     *
     * @return true 为 Asia/Shanghai 或 Asia/Urumqi，false 不是
     */
    private fun isChinaTimezone(): Boolean {
        return try {
            val tz = TimeZone.getDefault()
            tz.id.equals("Asia/Shanghai", ignoreCase = true) || tz.id.equals("Asia/Urumqi", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查 GPS 定位服务是否已开启
     *
     * @param context 上下文
     * @return true GPS 已开启，false 已关闭
     */
    fun isGpsEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check GPS status", e)
            false
        }
    }

    /**
     * 检查网络定位服务是否已开启
     *
     * @param context 上下文
     * @return true 网络定位已开启，false 已关闭
     */
    fun isNetworkLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check network location status", e)
            false
        }
    }

    /**
     * 获取当前所有可用的位置提供者列表
     *
     * @param context 上下文
     * @return 位置提供者名称列表（如 ["gps", "network", "passive"]）
     */
    fun getLocationProviders(context: Context): List<String> {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.getProviders(true).toList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get location providers", e)
            emptyList()
        }
    }

    /**
     * 检查是否拥有精细位置权限（ACCESS_FINE_LOCATION）
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否拥有粗略位置权限（ACCESS_COARSE_LOCATION）
     *
     * @param context 上下文
     * @return true 已授权，false 未授权
     */
    fun hasCoarseLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查应用是否具有粗略或精细位置权限（内部使用）
     *
     * @param context 上下文
     * @return true 至少拥有一种位置权限，false 均未授权
     */
    private fun hasLocationPermission(context: Context): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            AppLogger.w(TAG, "Location permission not granted.")
        }
        return hasPermission
    }
}
