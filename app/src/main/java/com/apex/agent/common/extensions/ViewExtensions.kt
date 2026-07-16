package com.apex.agent.common.extensions

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * [View] 的扩展函数集合，提供可见性控制、键盘操作、点击防抖、单位转换等便捷方法。
 */

/**
 * 设置 View 为可见状态（[View.VISIBLE]）。
 */
fun View.visible() {
    visibility = View.VISIBLE
}

/**
 * 设置 View 为不可见但仍占位状态（[View.INVISIBLE]）。
 */
fun View.invisible() {
    visibility = View.INVISIBLE
}

/**
 * 设置 View 为消失状态（[View.GONE]）。
 */
fun View.gone() {
    visibility = View.GONE
}

/**
 * 判断 View 当前是否可见（[View.VISIBLE]）。
 */
val View.isVisible: Boolean
    get() = visibility == View.VISIBLE

/**
 * 为 View 请求焦点并显示软键盘。
 */
fun View.showKeyboard() {
    requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * 清除 View 焦点并隐藏软键盘。
 */
fun View.hideKeyboard() {
    clearFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

/**
 * 设置带防抖的点击监听器。
 * 在 [debounceMs] 毫秒内重复点击仅首次生效。
 *
 * @param debounceMs 防抖间隔（毫秒），默认 500ms
 * @param action     点击回调
 */
fun View.onClick(debounceMs: Long = 500, action: (View) -> Unit) {
    var lastClickTime = 0L
    setOnClickListener { v ->
        val now = System.currentTimeMillis()
        if (now - lastClickTime >= debounceMs) {
            lastClickTime = now
            action(v)
        }
    }
}

/**
 * 将 dp 值转换为当前 View 上下文下的 px 值。
 *
 * @param dp dp 值
 * @return px 值
 */
fun View.dpToPx(dp: Float): Float {
    return dp * resources.displayMetrics.density
}

/**
 * 将 px 值转换为当前 View 上下文下的 dp 值。
 *
 * @param px px 值
 * @return dp 值
 */
fun View.pxToDp(px: Float): Float {
    return px / resources.displayMetrics.density
}
