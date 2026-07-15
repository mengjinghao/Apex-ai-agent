package com.apex.util

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 软键盘工具类，提供键盘的显示、隐藏、状态检测及高度获取等功能
 *
 * 支持 Activity 和 View 两种操作方式，提供全局的键盘可见性监听能力，
 * 自动适配不同 Android 版本的键盘行为差异。
 */
object KeyboardUtils {

    /**
     * 弹出软键盘
     *
     * 请求焦点并显示输入法，适用于 EditText 或其他可聚焦的 View。
     *
     * @param context 上下文
     * @param view 要接收输入的 View（通常为 EditText）
     */
    fun showKeyboard(context: Context, view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        imm.showSoftInput(view, SHOW_IMPLICIT)
    }

    /**
     * 隐藏软键盘
     *
     * 通过 View 的窗口令牌关闭输入法。
     *
     * @param context 上下文
     * @param view 当前获得焦点的 View
     */
    fun hideKeyboard(context: Context, view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * 从 Activity 中隐藏软键盘
     *
     * 获取当前页面的焦点 View 并关闭输入法，
     * 无需手动传入 View 参数。
     *
     * @param activity 当前的 Activity
     */
    fun hideKeyboard(activity: Activity) {
        val view = activity.currentFocus
        if (view != null) {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * 切换软键盘的显示/隐藏状态
     *
     * 如果当前键盘已显示则隐藏，反之则显示。
     *
     * @param context 上下文
     * @param view 要接收输入的 View
     */
    fun toggleKeyboard(context: Context, view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(0, 0)
    }

    /**
     * 检查软键盘当前是否可见
     *
     * 通过 Activity 窗口的可见区域高度与屏幕高度的差值判断键盘是否弹出。
     *
     * @param activity 当前的 Activity
     * @return true 键盘可见，false 键盘隐藏
     */
    fun isKeyboardVisible(activity: Activity): Boolean {
        val rootView = activity.window.decorView
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }

    /**
     * 注册软键盘可见性变化监听器
     *
     * 通过监听 Activity 窗口布局变化来检测键盘的弹出和收起。
     * 在 Activity 销毁时自动移除监听器以防止内存泄露。
     *
     * @param activity 当前的 Activity（需继承自 AppCompatActivity）
     * @param onVisibilityChanged 可见性变化回调，参数为 true 表示键盘显示，false 表示键盘隐藏
     */
    fun registerKeyboardVisibilityListener(activity: Activity, onVisibilityChanged: (Boolean) -> Unit) {
        val rootView = activity.window.decorView
        var lastVisible = isKeyboardVisible(activity)

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val currentVisible = isKeyboardVisible(activity)
        if (currentVisible != lastVisible) {
                lastVisible = currentVisible
                onVisibilityChanged(currentVisible)
            }
        }
        if (activity is AppCompatActivity) {
            activity.lifecycle.addObserver(LifecycleEventObserver { source: LifecycleOwner, event: Lifecycle.Event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    source.lifecycle.removeObserver(this)
                }
            })
        }
    }

    /**
     * 获取软键盘的高度（像素）
     *
     * 通过计算屏幕高度与可见显示区域底部的差值得到键盘高度。
     * 如果键盘当前未显示则返回 0。
     *
     * @param activity 当前的 Activity
     * @return 键盘高度（像素），键盘隐藏时返回 0
     */
    fun getKeyboardHeight(activity: Activity): Int {
        val rootView = activity.window.decorView
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height
        val keypadHeight = screenHeight - rect.bottom
        return if (keypadHeight > screenHeight * 0.15) keypadHeight else 0
    }
}
