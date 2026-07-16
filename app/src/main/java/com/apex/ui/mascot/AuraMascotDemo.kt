package com.apex.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.aiterminal.terminal.mascot.MascotKernelState
import com.ai.assistance.aiterminal.terminal.mascot.MascotStateSources
import com.ai.assistance.aiterminal.terminal.mascot.MascotTerminalIntegration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aura 水母完整集成示例。
 *
 * 演示如何在 app 层:
 * 1. 创建 MascotTerminalIntegration
 * 2. 收集各软件模块的真实 StateFlow
 * 3. 映射为 MascotStateSources
 * 4. bindRealStateSources 启动自动形态切换
 * 5. 在 Compose UI 里显示水母
 *
 * # 真实使用
 *
 * 实际 app 中,把这里的 mock 状态替换成真实模块的 StateFlow:
 * - burstKernel.state → kernelState
 * - shizukuManager.hasShizukuPermission → isRootActive
 * - aiService.thinkingState → isThinking
 * - 等等(见 MascotStateSources 文档表)
 *
 * # 示例
 *
 * ```
 * // 在 Activity/Fragment/Composable 里
 * AuraMascotDemo(integration = mascotIntegration)
 * ```
 */
@Composable
fun AuraMascotDemo(
    integration: MascotTerminalIntegration,
    sessionId: String = "demo",
    modifier: Modifier = Modifier,
) {
    // 1. 模拟真实状态源(实际使用时替换为真实模块 StateFlow)
    val mockKernelState = remember { MutableStateFlow(MascotKernelState.RUNNING) }
    val mockIsThinking = remember { MutableStateFlow(false) }
    val mockIsExecuting = remember { MutableStateFlow(true) }
    val mockIsRootActive = remember { MutableStateFlow(true) }
    val mockIsRemembering = remember { MutableStateFlow(false) }
    val mockIsTooling = remember { MutableStateFlow(false) }
    val mockIsSkilling = remember { MutableStateFlow(false) }
    val mockIsMcping = remember { MutableStateFlow(false) }

    // 2. 绑定真实状态源(首次进入时)
    LaunchedEffect(sessionId) {
        integration.bindRealStateSources(
            sessionId = sessionId,
            sources = MascotStateSources(
                kernelState = mockKernelState.asStateFlow(),
                isThinking = mockIsThinking.asStateFlow(),
                isExecuting = mockIsExecuting.asStateFlow(),
                isRootActive = mockIsRootActive.asStateFlow(),
                isRemembering = mockIsRemembering.asStateFlow(),
                isTooling = mockIsTooling.asStateFlow(),
                isSkilling = mockIsSkilling.asStateFlow(),
                isMcping = mockIsMcping.asStateFlow(),
            )
        )
        integration.showWelcome(sessionId)
    }

    // 3. 获取当前形态
    val controller = remember(sessionId) { integration.getController(sessionId) }
    val state by controller.state.collectAsState()

    // 4. 渲染水母
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AuraMascotView(
            form = state.form,
            modifier = Modifier.size(200.dp),
            transitionEnabled = true
        )
    }
}

/**
 * 真实状态源映射辅助函数。
 *
 * 把各软件模块的真实 StateFlow 映射成 MascotStateSources 需要的类型。
 * app 层调用这个函数收集真实状态后传给 bindRealStateSources。
 *
 * # 示例
 *
 * ```
 * val sources = collectRealStateSources(
 *     kernelStateFlow = burstKernel.state.map { it.toMascot() }.stateIn(...),
 *     isRootActiveFlow = shizukuManager.permissionState,
 *     isThinkingFlow = aiService.thinkingState,
 *     // ...
 * )
 * integration.bindRealStateSources(sessionId, sources)
 * ```
 */
fun collectRealStateSources(
    kernelStateFlow: StateFlow<MascotKernelState>? = null,
    isBerserkFlow: StateFlow<Boolean>? = null,
    isThinkingFlow: StateFlow<Boolean>? = null,
    isExecutingFlow: StateFlow<Boolean>? = null,
    hasErrorFlow: StateFlow<Boolean>? = null,
    isIdleFlow: StateFlow<Boolean>? = null,
    isSleepingFlow: StateFlow<Boolean>? = null,
    isRememberingFlow: StateFlow<Boolean>? = null,
    isAnalyzingFlow: StateFlow<Boolean>? = null,
    isLearningFlow: StateFlow<Boolean>? = null,
    isNetworkingFlow: StateFlow<Boolean>? = null,
    isRootActiveFlow: StateFlow<Boolean>? = null,
    isPlanningFlow: StateFlow<Boolean>? = null,
    isCompilingFlow: StateFlow<Boolean>? = null,
    isConnectingFlow: StateFlow<Boolean>? = null,
    isToolingFlow: StateFlow<Boolean>? = null,
    isSkillingFlow: StateFlow<Boolean>? = null,
    isMcpingFlow: StateFlow<Boolean>? = null,
    isCollaboratingFlow: StateFlow<Boolean>? = null,
): MascotStateSources = MascotStateSources(
    kernelState = kernelStateFlow,
    isBerserk = isBerserkFlow,
    isThinking = isThinkingFlow,
    isExecuting = isExecutingFlow,
    hasError = hasErrorFlow,
    isIdle = isIdleFlow,
    isSleeping = isSleepingFlow,
    isRemembering = isRememberingFlow,
    isAnalyzing = isAnalyzingFlow,
    isLearning = isLearningFlow,
    isNetworking = isNetworkingFlow,
    isRootActive = isRootActiveFlow,
    isPlanning = isPlanningFlow,
    isCompiling = isCompilingFlow,
    isConnecting = isConnectingFlow,
    isTooling = isToolingFlow,
    isSkilling = isSkillingFlow,
    isMcping = isMcpingFlow,
    isCollaborating = isCollaboratingFlow,
)
