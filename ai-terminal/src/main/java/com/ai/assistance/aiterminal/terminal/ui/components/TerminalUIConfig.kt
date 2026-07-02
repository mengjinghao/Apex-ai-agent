package com.ai.assistance.aiterminal.terminal.ui.components

import com.ai.assistance.aiterminal.terminal.theme.ApexTerminalTheme
import com.ai.assistance.aiterminal.terminal.ui.*

/**
 * 终端 UI 组件配置。
 *
 * 定义终端各 UI 组件的样式、间距、字体等配置。
 * UI 层（Compose/View）读取这些配置来统一渲染风格。
 *
 * # 配置项
 *
 * - **字体配置**: 字号、行高、字族
 * - **间距配置**: 内边距、外边距、消息间距
 * - **颜色配置**: 从主题映射到 UI 组件
 * - **圆角配置**: 卡片、按钮、输入框圆角
 * - **动画配置**: 持续时间、缓动曲线
 * - **尺寸配置**: 工具栏高度、侧边栏宽度、状态栏高度
 */
object TerminalUIConfig {

    // ===== 字体 =====

    object Font {
        const val SIZE_TINY = 10
        const val SIZE_SMALL = 12
        const val SIZE_NORMAL = 14
        const val SIZE_LARGE = 16
        const val SIZE_HUGE = 20

        const val LINE_HEIGHT_NORMAL = 1.4f
        const val LINE_HEIGHT_COMPACT = 1.2f
        const val LINE_HEIGHT_COMFORTABLE = 1.6f

        const val FAMILY_MONO = "JetBrains Mono"
        const val FAMILY_SANS = "Inter"
        const val FAMILY_FALLBACK = "monospace"
    }

    // ===== 间距 =====

    object Spacing {
        const val XS = 2      // 2dp
        const val SM = 4      // 4dp
        const val MD = 8      // 8dp
        const val LG = 12     // 12dp
        const val XL = 16     // 16dp
        const val XXL = 24    // 24dp
        const val XXXL = 32   // 32dp

        const val MESSAGE_SPACING = 2     // 消息间距
        const val SECTION_SPACING = 8     // 分区间距
        const val INPUT_PADDING_H = 12    // 输入框水平内边距
        const val INPUT_PADDING_V = 8     // 输入框垂直内边距
    }

    // ===== 颜色映射（从主题到组件）=====

    object Colors {
        // 背景
        val terminalBackground = ApexTerminalTheme.terminalBg
        val panelBackground = ApexTerminalTheme.surface
        val surfaceVariant = ApexTerminalTheme.surfaceVariant

        // 前景
        val primaryText = ApexTerminalTheme.foreground
        val secondaryText = ApexTerminalTheme.foregroundMuted
        val disabledText = ApexTerminalTheme.foregroundDim

        // 交互
        val primary = ApexTerminalTheme.primary
        val accent = ApexTerminalTheme.accent

        // 语义
        val success = ApexTerminalTheme.success
        val warning = ApexTerminalTheme.warning
        val error = ApexTerminalTheme.error
        val info = ApexTerminalTheme.info

        // 消息类型映射
        val commandColor = ApexTerminalTheme.primary       // 电光青
        val outputColor = ApexTerminalTheme.foreground      // 柔和白
        val errorColor = ApexTerminalTheme.error            // 玫瑰红
        val systemColor = ApexTerminalTheme.foregroundMuted  // 灰色
        val agentColor = ApexTerminalTheme.accent            // 珊瑚粉
        val burstColor = ApexTerminalTheme.accent            // 珊瑚粉
        val successColor = ApexTerminalTheme.success         // 薄荷绿
        val warningColor = ApexTerminalTheme.warning         // 琥珀金
        val infoColor = ApexTerminalTheme.info               // 天空蓝
        val dividerColor = ApexTerminalTheme.foregroundDim   // 暗灰

        // 选中
        val selectionBackground = ApexTerminalTheme.terminalSelection
        val cursorColor = ApexTerminalTheme.terminalCursor
    }

    // ===== 圆角 =====

    object CornerRadius {
        const val NONE = 0
        const val SM = 4
        const val MD = 8
        const val LG = 12
        const val XL = 16
        const val PILL = 999

        const val INPUT_FIELD = 8
        const val MESSAGE_CARD = 4
        const val PANEL = 12
        const val BUTTON = 8
        const val BADGE = 999
    }

    // ===== 动画 =====

    object Animation {
        const val FAST = 100     // ms
        const val NORMAL = 200   // ms
        const val SLOW = 400     // ms

        const val MESSAGE_APPEAR = 150
        const val SIDEBAR_SLIDE = 250
        const val MODE_SWITCH = 200
        const val COMPLETION_DROPDOWN = 100
        const val MASCOT_TRANSITION = 300

        const val CURSOR_BLINK = 530  // 标准光标闪烁频率
    }

    // ===== 尺寸 =====

    object Dimensions {
        const val TOOLBAR_HEIGHT = 48     // dp
        const val STATUS_BAR_HEIGHT = 28  // dp
        const val SIDEBAR_WIDTH = 280     // dp
        const val SIDEBAR_WIDTH_COMPACT = 200
        const val INPUT_BAR_HEIGHT = 56   // dp
        const val COMPLETION_MAX_HEIGHT = 200
        const val MASCOT_SIZE_SMALL = 120
        const val MASCOT_SIZE_MEDIUM = 180
        const val MASCOT_SIZE_LARGE = 240
        const val MIN_PANEL_WIDTH = 200
        const val MIN_PANEL_HEIGHT = 150
    }

    // ===== 边框 =====

    object Border {
        const val WIDTH_THIN = 1
        const val WIDTH_NORMAL = 2
        const val WIDTH_THICK = 3

        val panelBorderColor = ApexTerminalTheme.surfaceVariant
        val dividerColor = ApexTerminalTheme.foregroundDim
        val focusBorderColor = ApexTerminalTheme.primary
    }
}

/**
 * 终端消息渲染配置。
 *
 * 控制消息如何渲染的细节配置。
 */
data class MessageRenderConfig(
    val showTimestamps: Boolean = true,
    val showAgentLabels: Boolean = true,
    val showIcons: Boolean = true,
    val useAnsiColors: Boolean = true,
    val maxLineLength: Int = 200,
    val wrapLongLines: Boolean = true,
    val collapseLongOutput: Boolean = true,
    val collapsedLineThreshold: Int = 20,
    val collapsedCharThreshold: Int = 1000
) {
    companion object {
        val DEFAULT = MessageRenderConfig()
        val COMPACT = MessageRenderConfig(
            showTimestamps = false,
            showAgentLabels = false,
            showIcons = false,
            collapsedLineThreshold = 10,
            collapsedCharThreshold = 500
        )
        val VERBOSE = MessageRenderConfig(
            showTimestamps = true,
            showAgentLabels = true,
            showIcons = true,
            collapseLongOutput = false
        )
    }
}

/**
 * 输入栏配置。
 */
data class InputBarConfig(
    val placeholder: String = "输入命令...",
    val showModeIndicator: Boolean = true,
    val showMacroRecordButton: Boolean = true,
    val showModeSwitchButton: Boolean = true,
    val autoComplete: Boolean = true,
    val autoIndent: Boolean = false,
    val multilineSupport: Boolean = true,
    val maxInputLength: Int = 10000,
    val historySize: Int = 500
) {
    companion object {
        val DEFAULT = InputBarConfig()
        val MINIMAL = InputBarConfig(
            showModeIndicator = false,
            showMacroRecordButton = false,
            showModeSwitchButton = false,
            autoComplete = false,
            multilineSupport = false
        )
    }
}

/**
 * 状态栏配置。
 */
data class StatusBarConfig(
    val showSession: Boolean = true,
    val showDirectory: Boolean = true,
    val showMode: Boolean = true,
    val showAgentCount: Boolean = true,
    val showBurstState: Boolean = true,
    val showCpuUsage: Boolean = false,
    val showMemUsage: Boolean = false,
    val showTime: Boolean = false,
    val updateIntervalMs: Long = 1000
) {
    companion object {
        val DEFAULT = StatusBarConfig()
        val DETAILED = StatusBarConfig(
            showCpuUsage = true,
            showMemUsage = true,
            showTime = true
        )
        val MINIMAL = StatusBarConfig(
            showMode = true,
            showSession = false,
            showDirectory = false,
            showAgentCount = false,
            showBurstState = false
        )
    }
}

/**
 * 侧边栏配置。
 */
data class SidebarConfig(
    val width: Int = TerminalUIConfig.Dimensions.SIDEBAR_WIDTH,
    val compactWidth: Int = TerminalUIConfig.Dimensions.SIDEBAR_WIDTH_COMPACT,
    val showTabs: Boolean = true,
    val defaultTab: String = "sessions",
    val enableDragToResize: Boolean = true,
    val minDragWidth: Int = 150,
    val maxDragWidth: Int = 400
) {
    companion object {
        val DEFAULT = SidebarConfig()
    }
}
