package com.apex.ui.common.displays

import android.content.Context

/**
 * 虚拟显示覆盖层（com.apex.ui 包）— Stub。
 * 与 com.apex.agent.ui.common.displays.VirtualDisplayOverlay 同名不同包。
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
