# Normal Agent Mode（普通 Agent 模式）

> 普通 Agent 模式的全面升级 —— **45 项**与多 Agent 模式、狂暴模式完全不同的独有功能，
> 聚焦**单 Agent + 单 stream + 用户为中心**的深度对话体验，包含**玩梗模式**等趣味功能。

## 📋 目录

- [概述](#概述)
- [与多 Agent / 狂暴模式的区别](#与多-agent--狂暴模式的区别)
- [45 项独有功能](#45-项独有功能)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [玩梗模式详解](#玩梗模式详解)
- [功能详解](#功能详解)

---

## 概述

普通 Agent 模式（NORMAL）是 Apex-auto-agent 三种运行模式之一。之前它只是 `enum class AgentMode { NORMAL, MULTI_AGENT, BERSERK }` 的占位枚举，缺少独立实现。

本次升级为普通 Agent 模式实现了 **45 项独有功能**（v1 F1-F15 + v2 F16-F30 + v3 F31-F45），全部围绕"单 Agent + 单 stream + 用户为中心"的设计哲学，与多 Agent 模式（Agent 间协作）和狂暴模式（暴力执行）**完全不重叠**。v3 特别加入了**玩梗模式**等趣味功能，让 AI 有趣、有梗、有灵魂。

## 与多 Agent / 狂暴模式的区别

| 维度 | **NORMAL（普通）** | **MULTI_AGENT** | **BERSERK** |
|------|---------------|-------------|---------|
| 核心抽象 | 单 Agent + 单 stream + 用户为中心 | 多 Agent + 消息总线 + 协作 | 单 Agent + 推理策略链 + 暴力执行 |
| 工具调用 | 串行 + 权限确认 + 预估 | 多 Agent 各自调用 + 冲突解决 | 并行 racing + tool fusion + 无限重试 |
| 权限 | 三级 ALLOW/ASK/FORBID + 悬浮确认 | Agent 权限矩阵 | 跳过所有权限检查 |
| 记忆 | 用户偏好 + 跨会话 RAG + 遗忘曲线 | Agent 间协作记忆 | 任务级策略记忆 |
| 上下文 | 智能分层压缩 + 意图追踪 | 各 Agent 独立 | 无限上下文技能 |
| 用户控制 | 高（每步可干预） | 中（监督协作） | 低（启动后放任） |

## 15 项独有功能

| # | 功能 | 包路径 | 核心能力 |
|---|------|--------|----------|
| 1 | 对话意图状态机 | `intent/` | 跨轮次追踪用户意图（追问/纠正/切换话题） |
| 2 | 回答深度自适应 | `depth/` | 根据问题类型自动调节回答深度 |
| 3 | 智能上下文压缩器 | `context/` | 分层压缩（原文/摘要/关键事实） |
| 4 | 用户偏好画像 | `profile/` | 长期学习用户风格、技术栈、禁忌 |
| 5 | 跨会话记忆检索 | `memory/` | 向量检索历史对话注入当前上下文 |
| 6 | 流式 Markdown 渲染 | `rendering/` | 增量渲染无抖动，结构预测 |
| 7 | 工具调用预估与确认 | `toolpreview/` | 执行前展示预览，用户确认 |
| 8 | 工具调用宏 | `mac/` | 用户自定义工具序列一键执行 |
| 9 | 对话分支与回溯 | `branching/` | Git 式对话分支，回到历史重新提问 |
| 10 | 思考链注解 | `thinking/` | 提取推理步骤，支持追问 |
| 11 | 主动澄清机制 | `clarification/` | 检测模糊主动反问 |
| 12 | 个人化工具集 | `tools/` | 笔记/代码片段/收藏/待办 |
| 13 | 场景化对话模板 | `scene/` | 编程/写作/翻译/学习等预置 |
| 14 | 敏感信息脱敏 | `redactor/` | 自动识别 API key/密码/身份证 |
| 15 | 对话健康度仪表盘 | `health/` | 实时体验指标与建议 |

## 架构设计

```
normal/
├── NormalAgentMode.kt              # 核心定义（配置、上下文、结果）
├── NormalAgentOrchestrator.kt      # 🔥 编排器，整合 15 项功能
├── intent/                         # F1 对话意图状态机
├── depth/                          # F2 回答深度自适应
├── context/                        # F3 智能上下文压缩器
├── profile/                        # F4 用户偏好画像
├── memory/                         # F5 跨会话记忆检索
├── rendering/                      # F6 流式 Markdown 渲染
├── toolpreview/                    # F7 工具调用预估与确认
├── mac/                            # F8 工具调用宏
├── branching/                      # F9 对话分支与回溯
├── thinking/                       # F10 思考链注解
├── clarification/                  # F11 主动澄清机制
├── tools/                          # F12 个人化工具集
├── scene/                          # F13 场景化对话模板
├── redactor/                       # F14 敏感信息脱敏
└── health/                         # F15 对话健康度仪表盘
```

## 快速开始

### 1. 创建编排器

```kotlin
val orchestrator = NormalAgentOrchestrator(
    config = NormalAgentConfig(
        enableIntentTracking = true,
        enableAdaptiveDepth = true,
        enableSmartCompression = true,
        // ... 15 个开关
    )
)
```

### 2. 处理用户输入

```kotlin
val context = NormalAgentContext(
    chatId = "chat_001",
    userId = "user_001",
    sessionId = "session_001"
)

val inputResult = orchestrator.processInput("帮我写一个 Python 单例", context)

// inputResult 包含：
// - redactedMessage（脱敏后）
// - injections（prompt 注入列表：用户画像、场景、记忆、意图、深度等）
// - actions（检测到的动作：意图、深度、澄清需求等）

val fullPrompt = inputResult.generateInjectionsText()
```

### 3. 处理 AI 输出

```kotlin
val outputResult = orchestrator.processOutput(
    response = aiResponse,
    context = context,
    latencyMs = 1500,
    inputTokens = 500,
    outputTokens = 200
)

// outputResult 包含：
// - restoredResponse（还原脱敏后）
// - renderTree（Markdown 渲染树）
// - thinkingChain（思考链）
```

### 4. 工具调用预估

```kotlin
val toolResult = orchestrator.processToolCall(
    toolCallId = "call_001",
    toolName = "delete_file",
    arguments = mapOf("path" to "/tmp/test.txt"),
    context = context
)

if (!toolResult.approved) {
    println("用户拒绝了工具调用")
}
```

### 5. 查看健康度

```kotlin
val report = orchestrator.getHealthReport(context)
println(report)
// 输出：
// ═══ 对话健康度 ═══
// 总评分: 85/100 (GOOD)
// 维度评分:
//   context:  ████████░░ 80
//   tools:    ██████████ 100
//   ...
```

## 功能详解

### F1 对话意图状态机

跨轮次维护用户意图状态，识别 8 种意图：
- `INITIAL_QUERY` 首次提问
- `FOLLOW_UP` 追问
- `CORRECTION` 纠正
- `TOPIC_SWITCH` 切换话题
- `SUPPLEMENT` 补充信息
- `CONFIRMATION` 确认
- `CLARIFICATION` 请求澄清
- `CLOSURE` 结束

### F4 用户偏好画像

长期学习用户偏好并注入 prompt：
- 语言风格（正式度、语气、冗长度）
- 技术栈（自动检测 Python/Kotlin/Java 等）
- 回答偏好（深度、风格、emoji、示例）
- 禁忌话题
- 兴趣领域

### F9 对话分支

类似 Git 的对话分支：
```kotlin
// 从某消息分叉
orchestrator.forkConversation(context, "msg_123", "尝试不同方案")

// 回到历史重新提问
orchestrator.conversationBranching.rewindAndAsk(
    context.chatId, "msg_100", "换个角度回答"
)

// 可视化分支树
println(orchestrator.conversationBranching.visualizeTree(context.chatId))
```

### F13 场景模板

8 个预置场景，一键切换：
- 💻 编程助手
- ✍️ 写作助手
- 🌐 翻译专家
- 📚 学习导师
- 💡 头脑风暴
- 📊 数据分析
- ⚡ 效率助手
- 🤝 个人助理

```kotlin
orchestrator.sceneTemplateRegistry.apply(context.chatId, "scene_programming")
```

## 设计原则

1. **用户为中心**：所有功能围绕提升单 Agent 对话体验
2. **不与多 Agent 重叠**：不引入 Agent 间协作、委派、消息总线
3. **不与狂暴重叠**：不引入并行 racing、tool fusion、无限重试、推测执行
4. **渐进式控制**：用户每步可干预（工具确认、分支、澄清）
5. **长期记忆**：用户偏好跨会话保留，越用越懂你
6. **透明可解释**：思考链、健康度、意图都可见

## v3 新增功能 (F31-F45)

### 🎭 趣味与个性化

| # | 功能 | 包路径 | 核心能力 |
|---|------|--------|----------|
| **31** | **玩梗模式** ⭐ | `meme/` | 梗识别/生成/场景适配，20+ 预置梗库 |
| 32 | 人格化角色 | `persona/` | 8 种预置角色（学者/朋友/教练/诗人/侦探/管家/极客/哲学家） |
| 33 | 对话游戏 | `game/` | 10 种游戏（文字冒险/猜谜/问答/二十问/接龙等） |
| 34 | 创意写作 | `creative/` | 15 种体裁 + 灵感激发 + 角色管理 |
| 35 | 成就系统 | `achievement/` | 徽章/等级/连击/挑战，游戏化体验 |
| 36 | 每日问候 | `greeting/` | 时段问候/每日一言/天气/心情/节日 |
| 37 | 冷知识百科 | `trivia/` | 30+ 冷知识 + 趣味问答 |
| 38 | 表情包建议 | `sticker/` | 情感匹配/语境匹配，emoji+颜文字+ASCII |
| 39 | 彩蛋系统 | `easter/` | 隐藏命令/关键词/游戏/稀有回复 |
| 40 | 语气模仿 | `impersonation/` | 12 种模仿对象（鲁迅/乔布斯/福尔摩斯等） |
| 41 | 辩论练习 | `debate/` | 5 种辩论模式 + 评分 + 论点分析 |
| 42 | 速读模式 | `skimming/` | 5 级精简（标题/TLDR/要点/摘要/全文） |
| 43 | 节日感知 | `festival/` | 22 个节日 + 24 节气 + 习俗养生 |
| 44 | 对战模式 | `battle/` | AI vs AI 辩论/说唱/诗词/讲故事 |
| 45 | 昵称关系 | `nickname/` | 昵称管理 + 关系记忆 + 共同回忆 |

## 玩梗模式详解 ⭐

玩梗模式是 v3 的核心亮点，让 AI 有趣、有梗、有灵魂。

### 梗库分类

| 类型 | 示例 |
|------|------|
| 中文网络梗 | yyds、绝绝子、破防了、打工人、乐/典/绷 |
| 中文经典梗 | 孔乙己、阿Q精神 |
| 影视梗 | 真香、臣妾做不到 |
| 程序员梗 | hello world、404、小黄鸭调试、Stack Overflow、it works |
| 英文网络梗 | lol、brain moment |
| 游戏梗 | gg、afk |
| 谐音梗 | 冲鸭、好鸭 |
| 流行文化 | 各种热门梗 |

### 玩梗强度

```kotlin
// 5 级强度可调
MemeIntensity.OFF          // 关闭玩梗
MemeIntensity.SUBTLE       // 微妙（偶尔一梗）
MemeIntensity.BALANCED     // 平衡（适度玩梗）⭐ 推荐
MemeIntensity.ENTHUSIASTIC // 热情（经常玩梗）
MemeIntensity.MAXIMUM      // 最大化（句句有梗）
```

### 使用示例

```kotlin
val orchestrator = NormalAgentOrchestrator()

// 调整玩梗强度
orchestrator.memeEngine.updateConfig(
    MemeModeConfig(intensity = MemeIntensity.ENTHUSIASTIC)
)

// 识别用户消息中的梗
val detection = orchestrator.memeEngine.detect("这个功能 yyds，绝绝子！")
// detection.totalMemes = 2

// 生成玩梗回复
val result = orchestrator.memeEngine.generateResponse(
    userMessage = "太累了",
    baseResponse = "记得休息",
    scene = "casual"
)
// result.content = "记得休息 打工人打工魂"

// 解释梗
val explanation = orchestrator.memeEngine.explainMeme("yyds")
// "【永远的神】含义：「永远的神」的缩写..."
```

### 编排器集成

```kotlin
// v3 增强输入处理（含玩梗识别）
val inputResult = orchestrator.processInputV3(userMessage, context)

// v3 增强输出处理（含玩梗注入）
val outputResult = orchestrator.processOutputV3(response, context, latency, inputTokens, outputTokens)

// v3 全面状态报告（含玩梗统计）
val report = orchestrator.generateV3FullStatusReport(context)

// ★ 网络实时搜梗（suspend）
val webResult = orchestrator.processInputV3WithWebSearch(userMessage, context)

// 搜索梗的含义
val searchResult = orchestrator.searchMemeOnline("绝绝子")

// 梗百科查询
val wiki = orchestrator.lookupMemeOnline("yyds")

// 获取当前流行梗
val trending = orchestrator.getTrendingMemes()

// 增强版梗解释（本地 + 网络）
val explanation = orchestrator.explainMemeEnhanced("蚌埠住了")
```

## 🌐 网络实时搜梗

玩梗模式支持**网络实时搜索**，不仅限于本地预置梗库：

### 核心能力

| 能力 | 说明 |
|------|------|
| **多引擎搜索** | Bing + 百度并发搜索，自动故障转移 |
| **梗百科查询** | 小鸡词典 + 百度百科，获取详细解释 |
| **实时热搜** | 微博/百度/知乎热搜，追踪流行梗 |
| **自动识别** | 检测用户消息中的未知梗，自动搜索解释 |
| **搜索建议** | 输入梗前缀时自动补全 |
| **智能缓存** | TTL + LRU 缓存，减少重复请求 |

### 架构设计

```
meme/web/
├── WebSearchProvider.kt          # 搜索引擎抽象层 + 注册表
├── BingSearchProvider.kt         # Bing 搜索（建议 API + 网页解析）
├── BaiduSearchProvider.kt        # 百度搜索（建议 API + 网页解析）
├── HotSearchProvider.kt          # 热搜聚合（微博/百度/知乎）
├── MemeWikiProvider.kt           # 梗百科（小鸡词典/百度百科）
├── MemeCacheManager.kt           # 缓存管理（TTL + LRU）
└── MemeWebSearchEngine.kt        # 核心引擎（多引擎并发 + 合并去重）
```

### 使用示例

```kotlin
val orchestrator = NormalAgentOrchestrator()

// 1. 网络搜索梗的含义
val result = orchestrator.searchMemeOnline("绝绝子")
// result.items = [{title:"绝绝子是什么梗", snippet:"...", source:"Bing"}, ...]

// 2. 梗百科查询（详细解释）
val wiki = orchestrator.lookupMemeOnline("yyds")
// wiki.definition = "永远的神的缩写..."

// 3. 获取当前流行梗（来自热搜）
val trending = orchestrator.getTrendingMemes(10)
// trending = [{title:"...", source:"weibo", hotScore:12345}, ...]

// 4. 自动识别未知梗
val inputResult = orchestrator.processInputV3WithWebSearch(
    "这个功能蚌埠住了，太绝了",
    context
)
// 自动搜索"蚌埠住了"的解释并注入 prompt

// 5. 增强版梗解释（本地优先，网络兜底）
val explanation = orchestrator.explainMemeEnhanced("yyds")
// 先查本地梗库，没有则网络搜索

// 6. 搜索建议
val suggestions = orchestrator.suggestMemes("绝")
// ["绝绝子", "绝了", "绝对...", ...]
```

### 故障转移机制

```
用户搜索 "xxx梗"
    │
    ├─→ 检查缓存 ──→ 命中 ──→ 返回（标记 fromCache=true）
    │
    └─→ 缓存未命中
            │
            ├─→ Bing 搜索（并发）
            ├─→ 百度搜索（并发）
            │
            ├─→ 成功 ──→ 合并去重 ──→ 排序 ──→ 缓存 ──→ 返回
            │
            └─→ 全部失败 ──→ 记录失败计数
                    │
                    ├─→ 失败 < 3 次 ──→ 标记 DEGRADED
                    └─→ 失败 >= 3 次 ──→ 标记 COOLING_DOWN（5分钟）
```

### 缓存策略

| 数据类型 | TTL | 最大条数 |
|----------|-----|----------|
| 搜索结果 | 1 小时 | 500 |
| 搜索建议 | 5 分钟 | 500 |
| 热搜榜 | 30 分钟 | 500 |
| 梗百科 | 24 小时 | 500 |

## License

Apache License 2.0
