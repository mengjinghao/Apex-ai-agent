package com.apex.ui.error

import android.app.Activity

/**
 * 崩溃报告 Activity — Stub。
 * 原 UI 用于展示未捕获异常的堆栈跟踪，UI 移除后保留为空 Activity 占位。
 * GlobalExceptionHandler 通过 Intent 启动此 Activity 来展示崩溃信息。
 *
 * 恢复 UI 时，在此处添加 Compose 内容展示 stackTrace。
 */
class CrashReportActivity : Activity() {

    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    override fun onResume() {
        super.onResume()
        // Stub: 原 UI 展示崩溃详情并允许复制/分享，已移除
        // 直接 finish() 让用户回到上一个非崩溃状态
        finish()
    }
}
