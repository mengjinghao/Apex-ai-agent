package com.apex.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * 屏幕工具类，提供屏幕尺寸、密度、单位转换、状态栏/导航栏高度等显示相关信息的获取
 *
 * 支持标准像素密度单位转换（dp、sp、px 互转），
 * 提供屏幕物理尺寸检测和刷新率查询功能。
 */
object ScreenUtils {

    /**
     * 获取屏幕宽度（像素）
     *
     * @param context 上下文
     * @return 屏幕宽度像素值
     */
    fun getScreenWidth(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        return displayMetrics.widthPixels
    }

    /**
     * 获取屏幕高度（像素）
     *
     * 返回包括状态栏在内的屏幕总高度，但不包括系统导航栏。
     *
     * @param context 上下文
     * @return 屏幕高度像素值
     */
    fun getScreenHeight(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        return displayMetrics.heightPixels
    }

    /**
     * 获取屏幕尺寸（宽高对，像素）
     *
     * @param context 上下文
     * @return Pair (宽度, 高度) 单位像素
     */
    fun getScreenSize(context: Context): Pair<Int, Int> {
        return Pair(getScreenWidth(context), getScreenHeight(context))
    }

    /**
     * 获取屏幕密度因子
     *
     * 该值表示屏幕物理像素与密度无关像素的比例：
     * - mdpi: 1.0
     * - hdpi: 1.5
     * - xhdpi: 2.0
     * - xxhdpi: 3.0
     * - xxxhdpi: 4.0
     *
     * @param context 上下文
     * @return 屏幕密度因子
     */
    fun getScreenDensity(context: Context): Float {
        return context.resources.displayMetrics.density
    }

    /**
     * 获取屏幕密度 DPI 常量
     *
     * 返回 DisplayMetrics 中的 densityDpi 值，
     * 如 DisplayMetrics.DENSITY_XHDPI (320) 等。
     *
     * @param context 上下文
     * @return DPI 常量值
     */
    fun getDensityDpi(context: Context): Int {
        return context.resources.displayMetrics.densityDpi
    }

    /**
     * 将 dp（密度无关像素）转换为 px（物理像素）
     *
     * @param context 上下文
     * @param dp dp 值
     * @return px 值
     */
    fun dpToPx(context: Context, dp: Float): Float {
        return dp * getScreenDensity(context)
    }

    /**
     * 将 px（物理像素）转换为 dp（密度无关像素）
     *
     * @param context 上下文
     * @param px px 值
     * @return dp 值
     */
    fun pxToDp(context: Context, px: Float): Float {
        return px / getScreenDensity(context)
    }

    /**
     * 将 sp（缩放无关像素）转换为 px（物理像素）
     *
     * sp 单位会根据用户系统字体大小设置自动缩放，
     * 推荐用于字体大小的设置。
     *
     * @param context 上下文
     * @param sp sp 值
     * @return px 值
     */
    fun spToPx(context: Context, sp: Float): Float {
        return sp * context.resources.displayMetrics.scaledDensity
    }

    /**
     * 将 px（物理像素）转换为 sp（缩放无关像素）
     *
     * @param context 上下文
     * @param px px 值
     * @return sp 值
     */
    fun pxToSp(context: Context, px: Float): Float {
        return px / context.resources.displayMetrics.scaledDensity
    }

    /**
     * 获取状态栏高度（像素）
     *
     * 通过资源 ID 或窗口插入区域获取系统状态栏高度。
     *
     * @param context 上下文
     * @return 状态栏高度像素值，获取失败时返回 0
     */
    fun getStatusBarHeight(context: Context): Int {
        return try {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId)
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取导航栏高度（像素）
     *
     * 通过资源 ID 获取系统导航栏（返回键、主页键、最近任务键所在区域）的高度。
     * 注意：手势导航模式下导航栏高度可能为 0。
     *
     * @param context 上下文
     * @return 导航栏高度像素值，获取失败时返回 0
     */
    fun getNavigationBarHeight(context: Context): Int {
        return try {
            val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId)
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取屏幕密度名称
     *
     * 返回对应的密度桶名称：
     * - ldpi: ~120dpi
     * - mdpi: ~160dpi
     * - hdpi: ~240dpi
     * - xhdpi: ~320dpi
     * - xxhdpi: ~480dpi
     * - xxxhdpi: ~640dpi
     *
     * @param context 上下文
     * @return 密度名称字符串（如 "xhdpi"、"xxhdpi" 等）
     */
    fun getScreenDensityName(context: Context): String {
        return when (context.resources.displayMetrics.densityDpi) {
            DisplayMetrics.DENSITY_LOW -> "ldpi"
            DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
            DisplayMetrics.DENSITY_HIGH -> "hdpi"
            DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
            DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
            DisplayMetrics.DENSITY_XXXHIGH -> "xxxhdpi"
            DisplayMetrics.DENSITY_TV -> "tvdpi"
            else -> "mdpi"
        }
    }

    /**
     * 判断当前设备是否为平板
     *
     * 通过屏幕尺寸的 sw（最小宽度）值判断，sw >= 600dp 视为平板。
     *
     * @param context 上下文
     * @return true 是平板设备，false 是手机设备
     */
    fun isTablet(context: Context): Boolean {
        val configuration = context.resources.configuration
        val screenWidthDp = configuration.screenWidthDp
        return screenWidthDp >= 600
    }

    /**
     * 获取屏幕物理对角线尺寸（英寸）
     *
     * 根据屏幕宽度和高度的像素值及密度 DPI 计算屏幕对角线长度。
     *
     * @param context 上下文
     * @return 屏幕对角线长度（英寸）
     */
    fun getScreenPhysicalSize(context: Context): Double {
        val displayMetrics = context.resources.displayMetrics
        val widthInches = displayMetrics.widthPixels.toDouble() / displayMetrics.xdpi
        val heightInches = displayMetrics.heightPixels.toDouble() / displayMetrics.ydpi
        return kotlin.math.sqrt(widthInches * widthInches + heightInches * heightInches)
    }

    /**
     * 获取屏幕刷新率（Hz）
     *
     * 在 Android 11 (API 30) 及以上通过 DisplayManager 获取实际刷新率，
     * 低版本使用 WindowManager 获取默认刷新率。
     *
     * @param context 上下文
     * @return 屏幕刷新率（帧/秒），获取失败返回 60f
     */
    fun getScreenRefreshRate(context: Context): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate
        }
    }
}
