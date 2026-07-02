package com.apex.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * 剪贴板工具类，提供文本复制、粘贴、清空及剪贴板状态查询等功能
 *
 * 封装系统 ClipboardManager 的常用操作，支持一键复制加 Toast 提示，
 * 适用于应用中常见的剪贴板交互场景。
 */
object ClipboardUtils {

    /**
     * 获取剪贴板管理器实例
     *
     * @param context 上下文
     * @return ClipboardManager 系统服务实例
     */
    fun getClipboardManager(context: Context): ClipboardManager {
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /**
     * 复制文本到系统剪贴板
     *
     * 将指定文本内容以给定的标签标识复制到剪贴板。
     *
     * @param context 上下文
     * @param label 剪贴板条目标签（用于标识内容来源，如 "label"、"text" 等）
     * @param text 要复制的文本内容
     */
    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipData = ClipData.newPlainText(label, text)
        getClipboardManager(context).setPrimaryClip(clipData)
    }

    /**
     * 从系统剪贴板获取文本内容
     *
     * 读取剪贴板中的第一条文本内容，如果剪贴板为空或无文本类型数据则返回 null。
     *
     * @param context 上下文
     * @return 剪贴板中的文本内容，无内容时返回 null
     */
    fun pasteFromClipboard(context: Context): String? {
        val clipData = getClipboardManager(context).primaryClip ?: return null
        return if (clipData.itemCount > 0) {
            clipData.getItemAt(0).text?.toString()
        } else {
            null
        }
    }

    /**
     * 检查系统剪贴板是否包含文本内容
     *
     * @param context 上下文
     * @return true 剪贴板中包含文本内容，false 剪贴板为空或无文本数据
     */
    fun hasClipboardContent(context: Context): Boolean {
        val clipData = getClipboardManager(context).primaryClip ?: return false
        return clipData.itemCount > 0 && clipData.getItemAt(0).text != null
    }

    /**
     * 清空系统剪贴板中的所有内容
     *
     * 移除剪贴板中的当前主条目。
     *
     * @param context 上下文
     */
    fun clearClipboard(context: Context) {
        val clipData = ClipData.newPlainText("", "")
        getClipboardManager(context).setPrimaryClip(clipData)
    }

    /**
     * 复制文本到剪贴板并显示 Toast 提示
     *
     * 便捷方法，执行复制操作后自动弹出 Toast 反馈。
     * 如果未提供自定义提示信息，则使用默认文案"已复制到剪贴板"。
     *
     * @param context 上下文
     * @param text 要复制的文本内容
     * @param toastMessage 复制成功后显示的 Toast 文案，为空时使用默认文案
     */
    fun copyToClipboardAndToast(context: Context, text: String, toastMessage: String? = null) {
        copyToClipboard(context, "text", text)
        Toast.makeText(context, toastMessage ?: "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
