# Apex Working Files — 独有增强功能

> 不止"抄袭" VSCode/Cline/Aider，针对 Apex 多 APK 套件 + Android 移动端 + 多 Agent 协作场景做的**独有创新**。

---

## 0. 与顶级项目的对比

| 特性 | Apex 独有 | VSCode Timeline | Cline | Aider | JetBrains LH |
|------|:----:|:----:|:----:|:----:|:----:|
| 虚拟分支系统 | ✅ | ❌ | ❌ | git | ❌ |
| 按 Agent 步骤回退 | ✅ | ❌ | 仅 checkpoint | ❌ | ❌ |
| 语义 Diff（变更类型+风险） | ✅ | ❌ | ❌ | ❌ | ❌ |
| 时间机器（连续滑动） | ✅ | 仅点击 | ❌ | ❌ | 仅点击 |
| 多 Agent 冲突检测 | ✅ | N/A | N/A | N/A | N/A |
| 变更回放（视频式） | ✅ | ❌ | ❌ | ❌ | ❌ |
| 双指缩放代码字号 | ✅ | Ctrl+滚轮 | ❌ | ❌ | Ctrl+滚轮 |
| 跨 APK 共享状态 | ✅ | ❌ | ❌ | ❌ | ❌ |

---

## 1. 虚拟分支系统（Apex 独有）

### 创新点
- **移动端友好**：不依赖 git（移动端 git 难用）
- **轻量**：不真正复制文件，基于快照指针
- **细粒度**：单文件级别（git 是仓库级别）
- **无切换成本**：通过快照指针，无需 working directory 切换

### 使用场景
```
用户对当前代码不满意，但想"试试"另一种实现：
1. 创建虚拟分支 "experiment-1"
2. Agent 在分支上修改（不影响 main）
3. 切回 main，对比效果
4. 满意 → 合并到 main
5. 不满意 → 丢弃分支
```

### API
```kotlin
// 创建分支
val branch = ApexClient.workingFiles.createBranch(
    name = "experiment-1",
    filePath = "/sdcard/proj/Main.kt",
    description = "尝试用协程重写"
).getOrNull()

// 切换到分支
ApexClient.workingFiles.switchToBranch(filePath, branchId)

// 切回 main
ApexClient.workingFiles.switchToMain(filePath)

// 合并分支（支持冲突检测）
val mergeResult = ApexClient.workingFiles.mergeBranch(branchId, strategy = "MERGE_MANUAL").getOrNull()

// 丢弃分支
ApexClient.workingFiles.discardBranch(branchId)

// 查看分支 diff
val diff = ApexClient.workingFiles.getBranchDiff(branchId).getOrNull()
```

### 合并策略
- `MERGE_KEEP_BRANCH`: 保留分支版本（覆盖 main）
- `MERGE_KEEP_MAIN`: 保留 main 版本（丢弃分支变更）
- `MERGE_MANUAL`: 手动解决（生成 `<<<<<<<` 冲突标记）

---

## 2. 智能回退（Apex 独有）

### 创新点
- **按 Agent 步骤回退**（不只是单个快照）
- **保留无关成果**：回退第 2 步不影响第 4 步的成果
- **影响范围分析**：列出受影响的文件 + 后续步骤

### 对比传统做法

Agent 在一次会话中执行 5 步：
1. 读文件 A
2. 改文件 A（添加函数） ← 用户想回退
3. 读文件 B
4. 改文件 B（调用 A 的函数）
5. 改文件 B（添加日志）

| 做法 | 结果 |
|------|------|
| **VSCode/Cline** | 找到第 2 步前的快照，回退文件 A → 文件 B 第 4-5 步成果**全部丢失** |
| **Apex 智能回退** | 文件 A 回退到第 2 步前 → 文件 B **保留**第 4-5 步成果 → 警告"代码可能引用已删除的函数" |

### API
```kotlin
// 分析回退影响
val analysis = ApexClient.workingFiles.analyzeRevert(sessionId, stepId).getOrNull()
// analysis.summary = "回退 #2 添加函数：回退 1 个文件，保留 1 个文件，2 个后续步骤可能受影响"

// 执行回退
val result = ApexClient.workingFiles.executeSmartRevert(sessionId, stepId).getOrNull()
// result.revertedFiles = ["/sdcard/proj/Main.kt"]
// result.keptFiles = ["/sdcard/proj/B.kt"]
// result.warnings = ["文件未回退，可能引用已变更的代码：B.kt"]
```

---

## 3. 语义 Diff（Apex 独有）

### 创新点
- **AI 增强差异分析**：不只显示行级 diff，还显示"这个变更做了什么"
- **变更类型识别**：✨ 新功能 / 🐛 修复 / ♻️ 重构 / ⚡ 性能 / 💥 破坏性 / ...
- **风险评估**：无 / 低 / 中 / 高 / 严重
- **影响符号列表**：哪些函数/类被修改
- **破坏性变更检测**：删除 public 函数 / 修改签名
- **建议操作**：根据变更类型给建议

### 当前实现：基于规则（不依赖 LLM）
未来可加 LLM 增强版（通过 Market APK 的 `invokeModel`）。

### API
```kotlin
val semantic = ApexClient.workingFiles.analyzeSemanticDiff(
    oldContent = oldCode,
    newContent = newCode
).getOrNull()

// semantic.summary = "✨ 新功能：+12 -3，影响：fun add()、class Calculator"
// semantic.changeType = FEATURE
// semantic.riskLevel = MEDIUM
// semantic.affectedSymbols = ["fun add()", "class Calculator", "import: kotlin.math"]
// semantic.breakingChanges = []  // 无破坏性变更
// semantic.suggestions = ["🟡 中等风险，注意测试覆盖", "建议添加回归测试"]
```

### 变更类型识别规则
| 类型 | 识别条件 |
|------|----------|
| FEATURE | 新增函数/类 |
| BUGFIX | 含 fix/bug/null/crash 关键词 |
| REFACTOR | 函数签名变化但行数变化小 |
| PERFORMANCE | 含 perf/cache/async 关键词 |
| DOCS | 仅注释变化 |
| TEST | 含 test/assert 关键词 |
| BREAKING | 删除 public 函数 |

### 风险等级
| 等级 | 触发条件 |
|------|----------|
| CRITICAL | 删除 public 函数 |
| HIGH | 修改函数签名 / 删除 > 20 行 |
| MEDIUM | 变更 > 10 行 |
| LOW | 少量变更 |
| NONE | 无变更 |

---

## 4. 时间机器（Apex 独有）

### 创新点
- **连续滑动预览**：手指滑动时间轴，文件内容**实时**变化
- **无需点击切换**（VSCode Timeline 必须点击）
- **自动播放**：按时间间隔推进
- **速度调节**：0.5x / 1x / 2x / 5x / 10x

### 用户体验
```
手指在时间轴上滑动
  → 实时显示该时刻的文件内容
  → 显示"如果在这个时刻停止"的状态
  → 与最新版本的差异实时更新
```

类似 iOS 照片应用的"时刻滑块"，但是用于代码版本。

### API
```kotlin
// 加载文件到时间机器
ApexClient.workingFiles.loadTimeMachine(filePath)

// 跳到指定索引
ApexClient.workingFiles.timeMachineJumpTo(index)

// 跳到指定时间戳
ApexClient.workingFiles.timeMachineJumpToTimestamp(timestamp)

// 前进/后退
ApexClient.workingFiles.timeMachineNext()
ApexClient.workingFiles.timeMachinePrevious()
```

### UI 组件
- 水平滑块（手势拖动）
- 当前快照信息（时间 + 描述）
- 播放/暂停/上一步/下一步按钮
- 速度选择器（5 档）

---

## 5. 多 Agent 冲突检测（Apex 独有）

### 创新点
- VSCode/Cline/Aider 是单 Agent，不需要冲突检测
- Apex 多 Agent 模式下，Supervisor 分派多个 Worker 同时改一个文件
- 狂暴模式并行路径也可能冲突

### 文件锁机制
- **READ_LOCK**: 多 Agent 可同时读
- **WRITE_LOCK**: 独占写，其他 Agent 必须等待
- **EXCLUSIVE_LOCK**: 完全独占

### 锁特性
- TTL（默认 30 秒），避免 Agent 崩溃后死锁
- 自动清理过期锁
- 锁升级：READ → WRITE

### API
```kotlin
// Agent 写入前获取锁
val token = ApexClient.workingFiles.acquireFileLock(
    filePath = "/sdcard/proj/Main.kt",
    agentId = "rage-agent-1",
    type = "WRITE_LOCK",
    ttlMs = 30000
).getOrNull()

if (token == null) {
    // 锁获取失败，等待或返回错误
    val conflict = ApexClient.workingFiles.detectConflict(filePath, agentId).getOrNull()
    // conflict.message = "文件被其他 Agent 写锁定，强行修改会导致数据丢失"
    return
}

try {
    // 执行写入...
} finally {
    ApexClient.workingFiles.releaseFileLock(token)
}
```

---

## 6. 变更回放（Apex 独有）

### 创新点
- 像看视频一样回放 Agent 的所有变更
- 可调速度（0.5x ~ 10x）
- 跳到任意步骤
- 暂停 / 继续 / 重置
- 回放到任意步骤时显示该步骤的文件状态

### 用户体验
```
点击播放 → 文件内容按 Agent 操作顺序变化
  → 看到第 2 步：函数被添加
  → 看到第 4 步：函数被调用
  → 看到第 5 步：日志被添加
点击暂停 → 停在当前步骤
点击上一步 → 回到上一个变更
```

### API
```kotlin
// 加载会话
ApexClient.workingFiles.loadReplayer(sessionId)

// 播放
ApexClient.workingFiles.playReplay(speed = 2.0f)

// 暂停
ApexClient.workingFiles.pauseReplay()

// 跳到指定步骤
ApexClient.workingFiles.jumpReplayTo(stepIndex)

// 上一步/下一步
ApexClient.workingFiles.replayPreviousStep()
ApexClient.workingFiles.replayNextStep()

// 进度
val progress = ApexClient.workingFiles.replayProgress().getOrNull()  // 0.0 - 1.0
```

---

## 7. 移动端手势增强（Apex 独有）

### 创新点
- 桌面 VSCode 用 Ctrl+滚轮缩放，移动端没有滚轮
- Apex 用双指缩放（pinch-to-zoom）调整字号

### 实现的
- ✅ 双指缩放调整字号（8sp - 28sp）
- ✅ 时间机器水平滑动

### 预留的（v2）
- 双击切换主题
- 长按行号多选
- 左滑/右滑文件切换
- 摇一摇撤销最近操作

---

## 8. 跨 APK 共享（Apex 独有）

### 创新点
- 所有 APK 同进程（SharedUserId + android:process）
- 工作文件状态跨 APK 实时共享
- Agent 在 Rage APK 写文件，Working Files APK 立即看到快照

### 场景
```
1. Rage APK 中的 Agent 写入 Main.kt
   → writeWithSnapshot 自动建立快照
2. 用户切换到 Working Files APK
   → 立即看到时间线新增一个快照（🤖 Agent 来源）
   → 点击查看 diff
3. 用户切换到 Multi-Agent APK
   → Supervisor Agent 看到该文件被锁
   → 等待 Rage Agent 释放锁
```

---

## 9. 业务侧完整使用示例

```kotlin
// 在 Rage APK 的 Agent 中
class RageAgent {
    suspend fun execute(task: String) {
        // 1. 启动 Agent 会话
        val session = ApexClient.workingFiles.startAgentSession(
            agentId = "rage-1", agentName = "狂暴 Agent",
            taskDescription = task, mode = "BURST"
        ).getOrNull() ?: return

        // 2. 获取文件锁（防止其他 Agent 冲突）
        val lockToken = ApexClient.workingFiles.acquireFileLock(
            filePath = "/sdcard/proj/Main.kt",
            agentId = "rage-1", type = "WRITE_LOCK"
        ).getOrNull()

        // 3. 创建虚拟分支（试验性修改）
        val branch = ApexClient.workingFiles.createBranch(
            name = "rage-experiment",
            filePath = "/sdcard/proj/Main.kt",
            description = "狂暴模式尝试新实现"
        ).getOrNull()

        ApexClient.workingFiles.switchToBranch("/sdcard/proj/Main.kt", branch!!.branchId)

        // 4. 写入文件（自动快照）
        ApexClient.workingFiles.writeWithSnapshot(
            filePath = "/sdcard/proj/Main.kt",
            rootPath = "/sdcard/proj",
            content = newCode,
            agentId = "rage-1", agentName = "狂暴 Agent",
            sessionId = session.sessionId,
            description = "用协程重写"
        )

        // 5. 分析语义 diff
        val semantic = ApexClient.workingFiles.analyzeSemanticDiff(
            oldContent = oldCode, newContent = newCode
        ).getOrNull()
        // semantic.riskLevel = HIGH → 通知用户

        // 6. 切回 main 对比
        ApexClient.workingFiles.switchToMain("/sdcard/proj/Main.kt")

        // 7. 满意 → 合并分支
        ApexClient.workingFiles.mergeBranch(branch.branchId, "MERGE_KEEP_BRANCH")

        // 8. 释放锁
        ApexClient.workingFiles.releaseFileLock(lockToken!!)

        // 9. 结束会话
        ApexClient.workingFiles.finishAgentSession(session.sessionId, "完成")
    }
}

// 在 Working Files APK 中查看
class WorkingFilesUI {
    fun showFileHistory(filePath: String) {
        // 时间机器
        ApexClient.workingFiles.loadTimeMachine(filePath)
        // 滑动浏览历史...

        // 变更回放
        ApexClient.workingFiles.loadReplayer(sessionId)
        ApexClient.workingFiles.playReplay(speed = 2.0f)
        // 像视频一样看 Agent 的所有变更...

        // 智能回退某个步骤
        val analysis = ApexClient.workingFiles.analyzeRevert(sessionId, stepId).getOrNull()
        if (analysis != null && !analysis.hasRisk) {
            ApexClient.workingFiles.executeSmartRevert(sessionId, stepId)
        }
    }
}
```

---

## 10. 完整 API 速查（Apex 独有增强）

### 虚拟分支（10 个方法）
```kotlin
createBranch / switchToBranch / switchToMain / mergeBranch / discardBranch
listBranches / listActiveBranches / getActiveBranch / getBranchDiff / deleteBranch
```

### 智能回退（2 个方法）
```kotlin
analyzeRevert / executeSmartRevert
```

### 语义 Diff（2 个方法）
```kotlin
analyzeSemanticDiff / semanticDiffSnapshots
```

### 时间机器（5 个方法）
```kotlin
loadTimeMachine / timeMachineJumpTo / timeMachineJumpToTimestamp
timeMachineNext / timeMachinePrevious
```

### 冲突检测（7 个方法）
```kotlin
acquireFileLock / releaseFileLock / releaseAllLocksForAgent
isFileLocked / getFileLockStatus / detectConflict / listLockedFiles
```

### 变更回放（9 个方法）
```kotlin
loadReplayer / playReplay / pauseReplay / resetReplay
jumpReplayTo / replayNextStep / replayPreviousStep
setReplaySpeed / replayProgress
```

**总计 35 个 Apex 独有方法**，全部通过 `ApexClient.workingFiles.*` 调用。
