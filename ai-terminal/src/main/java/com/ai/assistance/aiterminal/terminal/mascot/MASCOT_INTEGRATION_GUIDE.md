# Aura 水母吉祥物 — Android 集成指南

Apex Auto Agent 终端的深海极光水母吉祥物,25 种形态,由软件真实功能状态自动驱动。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│ app 模块 (有 Compose 依赖)                              │
│  ┌─────────────────────┐  ┌──────────────────────────┐  │
│  │ AuraMascotView      │  │ AuraMascotDemo           │  │
│  │ (Compose 渲染)      │  │ (集成示例)               │  │
│  │ AnimationDrawable   │  │ collectRealStateSources  │  │
│  │ 帧动画 + 变身特效   │  │                          │  │
│  └──────────┬──────────┘  └───────────┬──────────────┘  │
│             │                          │                 │
│             ↓                          ↓                 │
└─────────────┼──────────────────────────┼─────────────────┘
              │                          │
┌─────────────┼──────────────────────────┼─────────────────┐
│ ai-terminal 模块 (纯业务逻辑,无 UI 依赖)               │
│  ┌──────────▼──────────────────────────▼──────────────┐  │
│  │ MascotTerminalIntegration                           │  │
│  │ - getController(sessionId)                          │  │
│  │ - bindRealStateSources(sessionId, MascotStateSources)│  │
│  │ - showWelcome / playSuccess / playError             │  │
│  └──────────┬──────────────────────────┬──────────────┘  │
│             │                          │                 │
│  ┌──────────▼──────────┐  ┌───────────▼──────────────┐  │
│  │ MascotStateBinder   │  │ MascotAnimationController │  │
│  │ 订阅真实 StateFlow   │  │ 帧动画播放 + 形态切换     │  │
│  │ 合并 MascotFunctionalState │ MascotStateMapper     │  │
│  │ 自动驱动 setForm    │  │ 优先级映射                │  │
│  └──────────┬──────────┘  └──────────────────────────┘  │
│             │                                            │
│  ┌──────────▼──────────┐                                │
│  │ AuraMascot (枚举)   │                                │
│  │ 25 形态定义          │                                │
│  │ getDrawableName     │                                │
│  │ getAnimationDrawableName │                          │
│  └─────────────────────┘                                │
└─────────────────────────────────────────────────────────┘
```

## 25 形态及触发条件

| 形态 | 触发条件 | 真实数据来源 |
|------|---------|-------------|
| ERROR | 执行出错 | TaskExecutor / ErrorAnalyzer |
| BERSERK | 狂暴模式 | BurstStateManager phase="berserk" |
| COMPILING | 代码生成中 | CodeGenerator |
| ANALYZING | 代码分析中 | CodeAnalyzer |
| LEARNING | 进化系统迭代 | EvolutionSystem |
| REMEMBERING | 记忆读写 | MemoryStorageSkill |
| PLANNING | 任务分解 | TaskPlanner |
| NETWORKING | 网络请求 | NetworkTool |
| CONNECTING | MCP/插件连接 | PluginManager |
| TOOLING | 调用工具 | ToolExecutor / ToolRegistry |
| SKILLING | Skills 加载 | SkillManager |
| MCPING | MCP 服务接入 | McpModule |
| ROOT | Shizuku/Root 激活 | ShizukuManager |
| COLLABORATING | 多 Agent 模式 | MultiAgentTerminalCoordinator |
| EXECUTING | 命令执行中 | TerminalSessionManager |
| THINKING | LLM 推理中 | AITerminalHelper |
| SLEEPING | 无活动超时 | KernelState=PAUSED |
| IDLE | 全部空闲 | 默认 |
| TYPING | 终端输入模式 | fromInputMode |
| SUCCESS | 任务完成 | notifySuccess() |
| EVOLVING | GA 演化 | fromBurstPhase |
| LOADING | 初始化 | fromBurstPhase |
| CELEBRATING | 里程碑 | 手动 |
| CURIOUS | 等待输入 | 手动 |
| SHIELDING | 安全模式 | 手动 |

## 集成步骤

### 1. 创建 Integration 实例

```kotlin
// 在 app 的 DI 或 Activity 里
val sessionManager = TerminalSessionManager(...)
val mascotIntegration = MascotTerminalIntegration(sessionManager)
```

### 2. 收集真实状态源

```kotlin
// app 层负责把各模块真实 StateFlow 映射成 MascotStateSources
val sources = collectRealStateSources(
    kernelStateFlow = burstKernel.state.map { it.toMascotKernelState() }
        .stateIn(scope, SharingStarted.Eagerly, MascotKernelState.STOPPED),
    isRootActiveFlow = shizukuManager.hasShizukuPermissionState,
    isThinkingFlow = aiService.thinkingState,
    isExecutingFlow = terminalSessionManager.executingState,
    isRememberingFlow = memoryStore.writingState,
    isToolingFlow = toolExecutor.executingState,
    isSkillingFlow = skillManager.loadingState,
    isMcpingFlow = mcpModule.connectingState,
    // ... 其他按需接入
)
```

### 3. 绑定状态源(启动自动形态切换)

```kotlin
mascotIntegration.bindRealStateSources(
    sessionId = "main",
    sources = sources
)
```

### 4. 在 Compose UI 显示水母

```kotlin
@Composable
fun TerminalScreen(mascotIntegration: MascotTerminalIntegration) {
    val controller = mascotIntegration.getController("main")
    val state by controller.state.collectAsState()

    AuraMascotView(
        form = state.form,
        modifier = Modifier.size(200.dp),
        transitionEnabled = true  // 启用变身特效
    )
}
```

### 5. 手动触发一次性动画

```kotlin
// 任务完成时
mascotIntegration.playSuccess("main")

// 出错时
mascotIntegration.playError("main")
```

## 资源文件

- `app/src/main/res/drawable/aura_<form>.png` — 25 张主形态图
- `app/src/main/res/drawable/aura_<form>_f1..f4.png` — 88 张帧动画图
- `app/src/main/res/drawable/aura_anim_<form>.xml` — 22 个 AnimationDrawable XML

## 模块独立性

- `ai-terminal/mascot/` 只依赖 kotlinx.coroutines + 自身,**可独立编译**
- `app/ui/mascot/` 依赖 androidx.compose + `:ai-terminal`(访问 AuraMascot)
- 真实状态映射在 app 层完成,mascot 模块不依赖任何外部业务模块
