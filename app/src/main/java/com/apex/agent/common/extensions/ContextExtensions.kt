package com.apex.agent.common.extensions

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * [Context] 的扩展函数集合，提供 Toast、界面跳转、资源获取、单位转换、输入法等便捷方法。
 */

/**
 * 显示短时长 Toast。
 *
 * @param message 要显示的消息文本
 * @param duration 显示时长，默认 [Toast.LENGTH_SHORT]
 */
fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

/**
 * 显示长时长 Toast。
 *
 * @param message 要显示的消息文本
 */
fun Context.toastLong(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/**
 * 安全启动 Activity，先检查是否存在可以处理该 Intent 的应用。
 *
 * @param intent 启动 Intent
 * @return true 表示成功启动，false 表示没有可用 Activity
 */
fun Context.safeStartActivity(intent: Intent): Boolean {
    return if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
        true
    } else {
        false
    }
}

/**
 * 兼容方式获取颜色值。
 * API 23+ 直接使用 [Context.getColor]，低版本使用 [Context.getResources.getColor]。
 *
 * @param id 颜色资源 ID
 * @return 颜色值（ARGB 格式）
 */
fun Context.getColorCompat(@ColorRes id: Int): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        getColor(id)
    } else {
        @Suppress("DEPRECATION")
        resources.getColor(id)
    }
}

/**
 * 兼容方式获取 Drawable 资源。
 * API 21+ 直接使用 [Context.getDrawable]，低版本使用 [Context.getResources.getDrawable]。
 *
 * @param id Drawable 资源 ID
 * @return Drawable 对象，可能为 null
 */
fun Context.getDrawableCompat(@DrawableRes id: Int): Drawable? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        getDrawable(id)
    } else {
        @Suppress("DEPRECATION")
        resources.getDrawable(id)
    }
}

/**
 * 获取字符串资源并支持格式化参数。
 *
 * @param id        字符串资源 ID
 * @param formatArgs 格式化参数（可选）
 * @return 格式化后的字符串
 */
fun Context.getString(@StringRes id: Int, vararg formatArgs: Any): String {
    return resources.getString(id, *formatArgs)
}

/**
 * 将 dp 单位转换为 px 单位。
 *
 * @param dp dp 值
 * @return px 值
 */
fun Context.dpToPx(dp: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        resources.displayMetrics
    )
}

/**
 * 将 px 单位转换为 dp 单位。
 *
 * @param px px 值
 * @return dp 值
 */
fun Context.pxToDp(px: Float): Float {
    return px / resources.displayMetrics.density
}

/**
 * 获取屏幕宽度（像素）。
 *
 * @return 屏幕宽度像素值
 */
fun Context.screenWidth(): Int {
    val metrics = resources.displayMetrics
    return metrics.widthPixels
}

/**
 * 获取屏幕高度（像素）。
 *
 * @return 屏幕高度像素值
 */
fun Context.screenHeight(): Int {
    val metrics = resources.displayMetrics
    return metrics.heightPixels
}

/**
 * 判断设备当前是否为横屏模式。
 *
 * @return 横屏返回 true，竖屏返回 false
 */
fun Context.isLandscape(): Boolean {
    return resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
}

/**
 * 震动设备指定时长。
 * API 26+ 使用 [VibrationEffect]，低版本使用弃用方法。
 *
 * @param millis 震动时长（毫秒），默认 50ms
 */
fun Context.vibrate(millis: Long = 50) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
            VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(millis)
    }
}

/**
 * 隐藏指定 View 的软键盘。
 *
 * @param view 当前获得焦点的 View
 */
fun Context.hideKeyboard(view: View) {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}

/**
 * 为指定 View 显示软键盘。
 *
 * @param view 需要输入焦点的 View
 */
fun Context.showKeyboard(view: View) {
    view.requestFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * 通过服务名获取系统服务。
 *
 * @param serviceName 系统服务名称（如 [Context.WIFI_SERVICE]）
 * @return 系统服务实例
 */
@Suppress("UNCHECKED_CAST")
fun <T> Context.systemService(serviceName: String): T {
    return getSystemService(serviceName) as T
}
