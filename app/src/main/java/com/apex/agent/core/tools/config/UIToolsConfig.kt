package com.apex.agent.core.tools.config

import android.content.Context
import com.apex.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/**
 * UI工具类配置管�?
 * 
 * 集中管理所有UI工具的配置参数，支持从外部文件加载配�?
 */
object UIToolsConfig {
    private const val TAG = "UIToolsConfig"
    
    // ==================== 重试配置 ====================
    /** 最大重试次�?*/
    const val MAX_RETRY_COUNT = 3
    
    /** 重试延迟（毫秒） */
    const val RETRY_DELAY_MS = 300L
    
    // ==================== 超时配置 ====================
    /** 操作超时时间（毫秒） */
    const val OPERATION_TIMEOUT_MS = 5000L
    
    /** 截图超时时间（毫秒） */
    const val SCREENSHOT_TIMEOUT_MS = 3000L
    
    // ==================== 截图配置 ====================
    /** 截图质量�?-100�?*/
    const val SCREENSHOT_QUALITY = 85
    
    /** 截图格式 */
    const val SCREENSHOT_FORMAT = "png"
    
    /** 截图文件名前缀 */
    const val SCREENSHOT_PREFIX = "screenshot_"
    
    // ==================== UI查询配置 ====================
    /** UI层次结构获取超时（毫秒） */
    const val UI_HIERARCHY_TIMEOUT_MS = 2000L
    
    /** 元素查找最大深�?*/
    const val ELEMENT_SEARCH_MAX_DEPTH = 10
    
    // ==================== 手势配置 ====================
    /** 默认滑动持续时间（毫秒） */
    const val DEFAULT_SWIPE_DURATION = 300
    
    /** 长按最短持续时间（毫秒�?*/
    const val MIN_LONG_PRESS_DURATION = 500
    
    /** 点击反馈显示时长（毫秒） */
    const val TAP_FEEDBACK_DURATION = 300L
    
    // ==================== 日志配置 ====================
    /** 是否启用详细日志 */
    var ENABLE_DETAILED_LOGS = false
    
    /** 最大日志条目数 */
    const val MAX_LOG_ENTRIES = 1000
    
    // ==================== 应用包名配置 ====================
    /** 内置应用包名映射�?*/
    private val BUILTIN_APP_PACKAGES = mapOf(
        // 社交与通讯
        "微信" to "com.tencent.mm",
        "WeChat" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        
        // 电商
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多�? to "com.xunmeng.pinduoduo",
        
        // 生活与社�?
        "小红�? to "com.xingin.xhs",
        "豆瓣" to "com.douban.frodo",
        "知乎" to "com.zhihu.android",
        
        // 地图与导�?
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        
        // 美食与服�?
        "美团" to "com.sankuai.meituan",
        "大众点评" to "com.dianping.v1",
        "饿了�? to "me.ele",
        
        // 旅行
        "携程" to "ctrip.android.view",
        "铁路12306" to "com.MobileTicket",
        "滴滴出行" to "com.sdu.did.psnger",
        
        // 视频与娱�?
        "bilibili" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "腾讯视频" to "com.tencent.qqlive",
        
        // 音乐与音�?
        "网易云音�? to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "喜马拉雅" to "com.ximalaya.ting.android",
        
        // 阅读
        "番茄小说" to "com.dragon.read",
        "七猫免费小说" to "com.kmxs.reader",
        
        // 生产�?
        "飞书" to "com.ss.android.lark",
        "QQ邮箱" to "com.tencent.androidqqmail",
        
        // AI与工�?
        "豆包" to "com.larus.nova",
        
        // 系统应用
        "Settings" to "com.android.settings",
        "Chrome" to "com.android.chrome",
        "Gmail" to "com.google.android.gm",
        "Google Maps" to "com.google.android.apps.maps"
    )
    
    /** 动态加载的应用包名（从系统扫描�?*/
    private var dynamicAppPackages = mutableMapOf<String, String>()
    
    /** 是否已扫描系统应�?*/
    private var appsScanned = false
    
    /**
     * 获取完整的应用包名映射表
     * 
     * @param context Android上下�?
     * @return 应用名称到包名的映射
     */
    fun getAppPackages(context: Context): Map<String, String> {
        if (!appsScanned) {
            scanInstalledApps(context)
        }
        return BUILTIN_APP_PACKAGES + dynamicAppPackages
    }
    
    /**
     * 扫描已安装的应用并添加到映射�?
     * 
     * @param context Android上下�?
     */
    fun scanInstalledApps(context: Context) {
        if (appsScanned) return
        
        synchronized(this) {
            if (appsScanned) return
            
            AppLogger.d(TAG, "开始扫描已安装的应�?..")
            
            try {
                val packageManager = context.packageManager
                val installedApps = packageManager.getInstalledApplications(
                    android.content.pm.PackageManager.GET_META_DATA
                )
        val newPackages = mutableMapOf<String, String>()
        for (app in installedApps) {
                    try {
                        val appName = packageManager.getApplicationLabel(app).toString()
        val packageName = app.packageName
                        
                        if (appName.isNotBlank() && packageName.isNotBlank()) {
                            // 只添加不在内置列表中的应�?
    if (!BUILTIN_APP_PACKAGES.containsKey(appName)) {
                                newPackages[appName] = packageName
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "获取应用信息失败: ${app.packageName}", e)
                    }
                }
        if (newPackages.isNotEmpty()) {
                    dynamicAppPackages.putAll(newPackages)
                    AppLogger.d(TAG, "扫描完成，新�?${newPackages.size} 个应�?)
                }
                
            } catch (e: Exception) {
                AppLogger.e(TAG, "扫描已安装应用失�?, e)
            } finally {
                appsScanned = true
            }
        }
    }
    
    /**
     * 从JSON配置文件加载应用包名
     * 
     * @param context Android上下�?
     * @param fileName JSON文件名（位于assets目录�?
     * @return 加载的应用包名映�?
     */
    fun loadAppPackagesFromJson(context: Context, fileName: String = "app_packages.json"): Map<String, String> {
        return try {
            val inputStream = context.assets.open(fileName)
        val reader = InputStreamReader(inputStream, "UTF-8")
        val json = reader.use { it.readText() }
        val gson = Gson()
        val type = object : TypeToken<Map<String, String>>() {}.type
            val loadedPackages: Map<String, String> = gson.fromJson(json, type)
            
            AppLogger.d(TAG, "�?${fileName} 加载�?${loadedPackages.size} 个应用包�?)
            loadedPackages
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载应用包名配置文件失败: ${fileName}", e)
            emptyMap()
        }
    }
    
    /**
     * 根据应用名称查找包名
     * 
     * @param context Android上下�?
     * @param appName 应用名称
     * @return 包名，未找到返回null
     */
    fun findPackageByName(context: Context, appName: String): String? {
        val packages = getAppPackages(context)
        return packages[appName]
    }
    
    /**
     * 根据包名查找应用名称
     * 
     * @param context Android上下�?
     * @param packageName 包名
     * @return 应用名称，未找到返回null
     */
    fun findNameByPackage(context: Context, packageName: String): String? {
        val packages = getAppPackages(context)
        return packages.entries.find { it.value == packageName }?.key
    }
    
    /**
     * 重置扫描状态（用于测试�?
     */
    fun resetScanState() {
        appsScanned = false
        dynamicAppPackages.clear()
    }
}
