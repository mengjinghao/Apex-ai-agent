package com.apex.agent.ui.common.displays

import android.content.Context

/**
 * 虚拟显示覆盖层 — Stub。
 * 用于在虚拟显示器上展示 UI 自动化过程，原 UI 实现已移除。
 */
class VirtualDisplayOverlay private constructor(
    private val context: Context,
    private val agentId: String
) {

    companion object {
        @Volatile private val instances = mutableMapOf<String, VirtualDisplayOverlay>()

        @JvmStatic
        fun getInstance(context: Context, agentId: String): VirtualDisplayOverlay {
            return synchronized(instances) {
                instances.getOrPut(agentId) {
                    VirtualDisplayOverlay(context.applicationContext, agentId)
                }
            }
        }

        @JvmStatic
        fun hideAll() {
            synchronized(instances) {
                instances.values.forEach { it.hide() }
            }
        }
    }

    fun show(displayId: Int): VirtualDisplayOverlay = this
    fun hide(): VirtualDisplayOverlay = this
    fun release() {
        synchronized(instances) {
            instances.remove(agentId)
        }
    }
}
