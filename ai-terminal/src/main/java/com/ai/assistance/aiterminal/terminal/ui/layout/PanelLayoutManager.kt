package com.ai.assistance.aiterminal.terminal.ui.layout

import com.ai.assistance.aiterminal.terminal.ui.viewmodel.PanelLayout

/**
 * 终端面板布局模型。
 *
 * 描述终端 UI 的面板分割方式，支持：
 * - 单面板（全屏终端）
 * - 水平分屏（上下两个面板）
 * - 垂直分屏（左右两个面板）
 * - 标签页（多标签切换）
 *
 * 每个面板可以承载独立的终端会话。
 *
 * # 使用示例
 *
 * ```
 * val layout = PanelLayoutManager()
 * layout.setLayout(PanelLayout.HORIZONTAL_SPLIT)
 *
 * // 左面板会话
 * layout.setPanelSession(0, "session-1")
 *
 * // 右面板会话
 * layout.setPanelSession(1, "session-2")
 *
 * // 切换活跃面板
 * layout.setActivePanel(1)
 *
 * // 调整分屏比例
 * layout.setSplitRatio(0.6f)  // 左 60%，右 40%
 * ```
 */
class PanelLayoutManager {

    /** 面板列表（每个面板持有一个会话 ID）。 */
    private val panels = mutableListOf<PanelInfo>()

    /** 活跃面板索引。 */
    private var activeIndex = 0

    /** 分屏比例（0..1，左/上面板占比）。 */
    private var splitRatio = 0.5f

    /** 当前布局。 */
    private var layout = PanelLayout.SINGLE

    init {
        // 初始化为单面板
        panels.add(PanelInfo(index = 0, sessionId = null, title = "Panel 1"))
    }

    /**
     * 面板信息。
     */
    data class PanelInfo(
        val index: Int,
        var sessionId: String?,
        var title: String,
        var isActive: Boolean = false
    )

    /**
     * 设置布局。
     */
    fun setLayout(newLayout: PanelLayout) {
        layout = newLayout
        when (newLayout) {
            PanelLayout.SINGLE -> {
                // 只保留第一个面板
                while (panels.size > 1) panels.removeAt(panels.size - 1)
                panels[0].isActive = true
            }
            PanelLayout.HORIZONTAL_SPLIT, PanelLayout.VERTICAL_SPLIT -> {
                // 确保有 2 个面板
                while (panels.size < 2) {
                    panels.add(PanelInfo(index = panels.size, sessionId = null, title = "Panel " + (panels.size + 1)))
                }
                while (panels.size > 2) panels.removeAt(panels.size - 1)
            }
            PanelLayout.TABS -> {
                // 标签页模式，保留所有面板
                // 至少 2 个
                if (panels.size < 2) {
                    panels.add(PanelInfo(index = panels.size, sessionId = null, title = "Panel " + (panels.size + 1)))
                }
            }
        }
        activeIndex = activeIndex.coerceIn(0, panels.size - 1)
        updateActive()
    }

    /**
     * 获取当前布局。
     */
    fun getLayout(): PanelLayout = layout

    /**
     * 获取所有面板。
     */
    fun getPanels(): List<PanelInfo> = panels.toList()

    /**
     * 获取面板数。
     */
    fun getPanelCount(): Int = panels.size

    /**
     * 设置面板的会话。
     */
    fun setPanelSession(panelIndex: Int, sessionId: String) {
        val panel = panels.getOrNull(panelIndex) ?: return
        panel.sessionId = sessionId
        panel.title = "Session: " + sessionId.take(8)
    }

    /**
     * 设置活跃面板。
     */
    fun setActivePanel(index: Int) {
        activeIndex = index.coerceIn(0, panels.size - 1)
        updateActive()
    }

    /**
     * 获取活跃面板索引。
     */
    fun getActivePanelIndex(): Int = activeIndex

    /**
     * 获取活跃面板。
     */
    fun getActivePanel(): PanelInfo? = panels.getOrNull(activeIndex)

    /**
     * 切换到下一个面板。
     */
    fun cyclePanel() {
        activeIndex = (activeIndex + 1) % panels.size
        updateActive()
    }

    /**
     * 设置分屏比例。
     */
    fun setSplitRatio(ratio: Float) {
        splitRatio = ratio.coerceIn(0.2f, 0.8f)
    }

    /**
     * 获取分屏比例。
     */
    fun getSplitRatio(): Float = splitRatio

    /**
     * 添加新标签页。
     */
    fun addTab(sessionId: String? = null): Int {
        if (layout != PanelLayout.TABS) return -1
        val index = panels.size
        panels.add(PanelInfo(index = index, sessionId = sessionId, title = "Panel " + (index + 1)))
        activeIndex = index
        updateActive()
        return index
    }

    /**
     * 关闭标签页。
     */
    fun closeTab(index: Int): Boolean {
        if (layout != PanelLayout.TABS || panels.size <= 1) return false
        if (index !in panels.indices) return false
        panels.removeAt(index)
        // 重新编号
        panels.forEachIndexed { i, p -> }  // index assignment removed
        activeIndex = activeIndex.coerceIn(0, panels.size - 1)
        updateActive()
        return true
    }

    /**
     * 获取布局配置摘要（供 UI 渲染参考）。
     */
    fun getLayoutConfig(): LayoutConfig {
        return LayoutConfig(
            layout = layout,
            panelCount = panels.size,
            activeIndex = activeIndex,
            splitRatio = splitRatio,
            panels = panels.map { PanelSummary(it.index, it.sessionId, it.title, it.isActive) }
        )
    }

    private fun updateActive() {
        panels.forEachIndexed { i, p -> p.isActive = (i == activeIndex) }
    }
}

/**
 * 布局配置摘要。
 */
data class LayoutConfig(
    val layout: PanelLayout,
    val panelCount: Int,
    val activeIndex: Int,
    val splitRatio: Float,
    val panels: List<PanelSummary>
)

data class PanelSummary(
    val index: Int,
    val sessionId: String?,
    val title: String,
    val isActive: Boolean
)
