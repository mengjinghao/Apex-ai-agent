# AI Terminal 技能包完整指南

## 📋 概述

AI Terminal 现在拥有三个强大的 **Root 专属技能包**，提供普通 App 无法实现的功能。

| 技能包 | 目标用户 | 核心价值 |
|--------|----------|----------|
| 新手玩机配置 | 刚 Root 的新手用户、不想折腾的用户 | 一键配置完整玩机环境，包括核心模块、系统优化、Root 隐藏 |
| 性能/续航调优大师 | 所有 Root 玩机用户、续航焦虑用户、手游用户 | 不懂 CPU/内核，说需求就能调优 |
| 玩机/刷机助手 | Root 玩机爱好者、刷机用户 | 降低玩机门槛，自动备份、刷模块、诊断故障 |

---

## 🚀 技能包一：新手玩机配置

**新增文件**：
- `agent/skills/NewbieSetupSkill.kt` - 新手玩机配置技能包
- `SKILLS_GUIDE.md` - 已更新此文档

### 核心能力

1️⃣ **环境检测**
- 自动识别机型、系统版本
- 识别 Root 方案（Magisk/KernelSU/SuperSU）
- 检测内核版本
- 检查 Zygisk 状态
- 确认玩机环境基础状态

2️⃣ **核心模块自动安装**
- 根据用户需求（续航/性能/平衡）自动选择
- 自动安装适配的、口碑稳定的核心模块
  - Zygisk（核心）
  - LSPosed（强大的框架）
  - Universal SafetyNet Fix（绕过检测）
  - Shamiko（隐藏 Root）
  - Universal GMS Doze（优化续航）
  - App Systemizer（系统化 App）
- 自动处理依赖与兼容性

3️⃣ **系统基础优化**
- 自动执行系统精简
- 冻结预装垃圾 App
- 优化内核参数（IO 调度器、内存管理）
- 配置后台进程管控
- 调整动画速度
- 提升流畅度与续航

4️⃣ **Root 安全与隐藏配置**
- 自动配置 Root 权限管控
- 执行全链路 Root 隐藏
- 适配银行/支付 App
- 配置系统安全加固
- 调整 SELinux 状态

5️⃣ **配置报告生成**
- 完成后自动生成玩机环境配置报告
- 标注所有安装的模块
- 列出优化的内容
- 提供功能入口与注意事项
- 新手也能快速上手

### 快速使用

```kotlin
// 一键配置新手玩机环境（平衡模式）
val result = agent.newbieSetupSkill.setupNewbieEnvironment(
    preference = ConfigurationPreference.BALANCED,
    backupFirst = true
)

// 仅检测环境
val environment = agent.newbieSetupSkill.checkEnvironmentOnly()

// 仅安装核心模块
agent.newbieSetupSkill.installEssentialModulesOnly()

// 仅执行系统优化
agent.newbieSetupSkill.performSystemOptimizationsOnly()

// 仅配置 Root 安全
agent.newbieSetupSkill.configureRootSecurityOnly()
```

### 生成的报告示例

```
============================================
          新手玩机环境配置报告
============================================

📅 配置时间：2026-04-19 14:30:00

============================================
          设备信息
============================================
品牌：Xiaomi
型号：Mi 14 Pro
Android：14
内核：5.15.100-gabcdef123
Root 方案：Magisk
Magisk：27.0
SELinux：Enforcing

============================================
          已安装核心模块（6）
============================================
- Zygisk
  Zygisk 是 Magisk 的核心功能，用于注入系统进程

- LSPosed
  强大的框架模块，用于 Hook 系统和 App 行为

- Universal SafetyNet Fix
  绕过 SafetyNet 和 Play Integrity 检测

- Shamiko
  隐藏 Zygisk 和 Root 痕迹

- Universal GMS Doze
  强制 Google Play 服务进入 Doze 模式，省电

- App Systemizer
  将用户 App 系统化为系统应用

============================================
          已执行系统优化（5）
============================================
- 冻结预装垃圾应用
  冻结系统预装、不用的应用，减少后台占用

- 优化动画速度
  将系统动画调整为 0.75x，兼顾流畅与速度

- 限制后台进程
  限制后台进程数为 8，减少内存占用

- 优化 IO 调度器
  将 IO 调度器设为 deadline，提高读写响应

- 内存优化
  调整 VM 参数，优化内存管理

============================================
          Root 安全配置
============================================
Root 隐藏：✅
SELinux：✅
SafetyNet：✅
权限管理：✅

============================================
          配置完成！
============================================

💡 提示：请重启手机以让所有模块和优化生效
```

---

## 🔧 技能包二：性能/续航调优大师

### 核心能力

#### 1️⃣ 场景化调优 - 4种预设模式

```kotlin
// 极致续航 - 日常刷微信抖音
agent.performanceBatterySkill.applyExtremeBatteryMode()

// 平衡模式 - 日常使用
agent.performanceBatterySkill.applyBalancedMode()

// 游戏模式 - 全力释放性能，帧率拉满
agent.performanceBatterySkill.applyGamingMode()

// 性能模式 - 优先流畅
agent.performanceBatterySkill.applyPerformanceMode()
```

#### 2️⃣ 耗电溯源与修复

```kotlin
// 自动扫描耗电问题并修复
val result = agent.performanceBatterySkill.diagnoseAndFixBatteryDrain()

// 可能的修复项目
// - 冻结异常唤醒应用
// - 杀死高 CPU 占用进程
// - 禁用不必要的系统服务
```

#### 3️⃣ 内核级优化

```kotlin
// 高级内核调优
val result = agent.performanceBatterySkill.applyKernelOptimizations()

// 优化项包括
// - IO 调度器（noop/deadline/cfq）
// - 内存管理（VM 参数调整）
// - TCP 拥塞算法
// - 内核微调
```

#### 4️⃣ 自定义精确调优

```kotlin
val customConfig = TuningConfig(
    customCpuGovernor = "schedutil",
    customCpuMaxFreq = 2200000, // 2.2GHz
    customCpuMinFreq = 400000,  // 400MHz
    customIoScheduler = "cfq",
    customAnimationScale = 0.75f,
    customBackgroundProcessLimit = 12,
    freezeWakeLockApps = true,
    disableUnnecessaryServices = true
)

agent.performanceBatterySkill.applyTuningConfig(customConfig)
```

### 调优报告示例

```
📊 调优完成报告

✅ 已应用的优化项 (8 项):
  1. CPU 调度器: powersave
  2. CPU 最高频率: 1.4 GHz
  3. CPU 最低频率: 300 MHz
  4. IO 调度器: noop
  5. 动画缩放: 0.5x
  6. 后台进程限制: 4
  7. 冻结异常唤醒应用: 3 个
  8. 禁用不必要服务: 5 个

📈 预估提升:
  • 续航提升: +25%

💡 提示: 可以随时切换其他模式，或自定义调优配置
```

---

## 🚀 技能包二：玩机/刷机助手

### 核心能力

#### 1️⃣ 分区级备份/还原

```kotlin
// 列出所有可用分区
val partitions = agent.flashingHelperSkill.listPartitions()

// 备份单个分区（含校验）
val result = agent.flashingHelperSkill.backupPartition(
    partitionName = "boot",
    verifyAfterBackup = true
)

// 批量备份所有关键分区（boot、system、vendor、efs 等）
val result = agent.flashingHelperSkill.backupAllCriticalPartitions()

// 还原分区（操作前自动备份当前状态）
val result = agent.flashingHelperSkill.restorePartition(
    backupInfo = myBackup,
    autoBackupFirst = true,
    verifyBeforeRestore = true
)
```

#### 2️⃣ Magisk 模块全生命周期管理

```kotlin
// 列出已安装模块
val modules = agent.flashingHelperSkill.listMagiskModules()

// 检查模块兼容性（安装前）
val (compatible, reason) = agent.flashingHelperSkill.checkModuleCompatibility(
    "/sdcard/my_module.zip"
)

// 安装模块（自动备份，兼容性检查）
val result = agent.flashingHelperSkill.installMagiskModule(
    modulePath = "/sdcard/my_module.zip",
    autoBackup = true,
    checkCompatibility = true
)

// 启用/禁用模块
agent.flashingHelperSkill.toggleMagiskModule(moduleId, enable = true)

// 卸载模块
agent.flashingHelperSkill.uninstallMagiskModule(moduleId)
```

#### 3️⃣ 系统预装 App 批量管理

```kotlin
// 列出所有系统应用，智能识别核心组件
val apps = agent.flashingHelperSkill.listSystemApps()

// 批量冻结/卸载（自动跳过核心组件）
val result = agent.flashingHelperSkill.batchManageSystemApps(
    mode = AppManageMode.Uninstall,
    skipCritical = true
)

// 快捷方式：卸载所有预装垃圾 App
agent.flashingHelperSkill.uninstallBloatware()
```

#### 4️⃣ 刷机故障自动诊断与修复

```kotlin
// 手机卡米/无限重启时自动诊断
val diagnosis = agent.flashingHelperSkill.diagnoseFlashIssue()

// 自动修复
val result = agent.flashingHelperSkill.autoFixFlashIssue()

// 诊断包含
// - 内核日志分析
// - Recovery 日志分析
// - Magisk 日志分析
// - 问题类型识别
// - 根因分析
// - 修复建议
// - 自动修复命令
```

### 故障诊断报告示例

```
📋 诊断结果

问题类型: MAGISK_ISSUE
根因: Magisk 模块导致的卡米
建议: 进入安全模式禁用问题模块

自动修复命令: magisk --disable-modules

完整日志已保存，详情见: /sdcard/diagnosis.log
```

---

## 📁 完整文件结构

```
ai-terminal/src/main/java/com/ai/assistance/aiterminal/terminal/
├── agent/
│   ├── SystemProbeData.kt          # 系统探测数据模型
│   ├── SystemProbe.kt              # 系统探测
│   ├── AdvancedTerminalAgent.kt    # 高级 Agent 整合（含技能）
│   └── skills/
│       ├── SkillModels.kt          # 技能包通用数据模型
│       ├── PerformanceBatterySkill.kt  # 性能/续航调优
│       └── FlashingHelperSkill.kt    # 玩机/刷机助手
└── agent/task/
    ├── TaskModels.kt               # 任务模型
    ├── TaskPlanner.kt              # 任务规划
    ├── TaskExecutor.kt             # 任务执行
    ├── ErrorAnalyzer.kt            # 错误分析
    ├── TaskPersistence.kt          # 任务持久化
    └── ScheduledTaskManager.kt     # 定时任务
```

---

## 🎯 快速开始

### 初始化

```kotlin
// 初始化基础组件
val rootTerminalManager = RootTerminalManager()
val llmApi = LLMApiImpl()

// 创建高级 Agent
val agent = AdvancedTerminalAgent(
    context = context,
    rootTerminalManager = rootTerminalManager,
    llmApi = llmApi
)

// 或单独使用技能包
val batterySkill = PerformanceBatterySkill(rootTerminalManager)
val flashingSkill = FlashingHelperSkill(rootTerminalManager)
```

### 常用技能调用

```kotlin
// 场景1：用户说 "给我极致续航"
val result = batterySkill.applyExtremeBatteryMode()

// 场景2：用户说 "我要玩游戏，给我满帧"
val result = batterySkill.applyGamingMode()

// 场景3：用户说 "帮我备份所有关键分区，我要刷机"
val result = flashingSkill.backupAllCriticalPartitions()

// 场景4：用户说 "帮我刷这个 Magisk 模块"
val result = flashingSkill.installMagiskModule("/sdcard/module.zip")

// 场景5：用户说 "手机卡米了，帮我看看"
val diagnosis = flashingSkill.diagnoseFlashIssue()
val result = flashingSkill.autoFixFlashIssue()

// 场景6：用户说 "帮我卸载所有预装垃圾 App"
val result = flashingSkill.uninstallBloatware()
```

---

## ⚠️ 安全提示

1. **备份第一**：在进行任何修改前，系统会自动备份
2. **核心组件保护**：自动识别并跳过系统核心组件，防止误删变砖
3. **Root 权限**：所有技能包都需要 Root 权限才能工作
4. **谨慎操作**：刷机有风险，请确保备份后再操作

---

## 📖 更多文档

- [Advanced Agent Guide](./ADVANCED_AGENT_GUIDE.md) - 高级 Agent 完整指南
- [Completion Report](./COMPLETION_REPORT.md) - 完成报告
- [Root Mode Guide](./ROOT_MODE_GUIDE.md) - Root 模式指南
