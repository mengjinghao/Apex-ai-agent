# AI输入框工具/技能选择功能

## 📝 功能概述

在AI对话输入框中增加指定工具或技能调用的功能，让用户可以在发送消息时指定使用特定工具或技能。

## 🎯 功能特点

### 1. 快速选择
- 工具快捷标签（文件搜索、代码助手、网页浏览、终端）
- 技能快捷标签（代码生成、文档撰写、数据分析等）
- 一键选择/取消选择

### 2. 灵活组合
- 可以同时选择工具和技能
- 可以单独使用工具或技能
- 支持清除选择

### 3. 增强提示词
- 自动将选择信息添加到消息中
- 使用 `【指定工具: xxx】` 和 `【指定技能: xxx】` 格式
- 保持原有提示词完整

## 📦 新增文件

### 核心组件
1. **ToolSkillQuickSelector.kt** - 快速选择UI组件
2. **ToolSkillSelectionManager.kt** - 选择状态管理
3. **ToolSkillIntegrationExample.kt** - 集成示例

## 🔧 使用方法

### 1. 基本集成

```kotlin
@Composable
fun MyChatInputWithTools() {
    var draftText by remember { mutableStateOf("") }
    val toolSkillManager = remember { ToolSkillSelectionManager() }

    Column {
        // 已选择的工具/技能显示条
        if (toolSkillManager.hasSelection()) {
            SelectedItemsBar(
                selectedTool = toolSkillManager.selectedTool,
                selectedSkill = toolSkillManager.selectedSkill,
                onToolRemove = { toolSkillManager.selectTool(null) },
                onSkillRemove = { toolSkillManager.selectSkill(null) }
            )
        }

        // 工具/技能快速选择器
        ToolSkillQuickSelector(
            selectedTool = toolSkillManager.selectedTool,
            selectedSkill = toolSkillManager.selectedSkill,
            onToolSelected = toolSkillManager::selectTool,
            onSkillSelected = toolSkillManager::selectSkill,
            onOpenToolSelector = toolSkillManager::openCombinedSelector,
            onOpenSkillSelector = toolSkillManager::openCombinedSelector
        )

        // 输入框
        OutlinedTextField(
            value = draftText,
            onValueChange = { draftText = it },
            modifier = Modifier.fillMaxWidth()
        )

        // 发送按钮
        Button(
            onClick = {
                val enhancedPrompt = toolSkillManager.buildEnhancedPrompt(draftText)
                sendMessage(enhancedPrompt)
                draftText = ""
                toolSkillManager.clearSelection()
            }
        ) {
            Text("发送")
        }
    }
}
```

### 2. 使用完整增强输入框

```kotlin
EnhancedToolSkillInputBar(
    draftText = draftText,
    onDraftTextChange = { draftText = it },
    selectedTool = toolSkillManager.selectedTool,
    selectedSkill = toolSkillManager.selectedSkill,
    onToolSelected = toolSkillManager::selectTool,
    onSkillSelected = toolSkillManager::selectSkill,
    onSendMessage = {
        val enhancedPrompt = toolSkillManager.buildEnhancedPrompt(draftText)
        sendMessage(enhancedPrompt)
        draftText = ""
        toolSkillManager.clearSelection()
    }
)
```

### 3. 使用弹窗选择器

```kotlin
if (toolSkillManager.showCombinedSelector) {
    ToolSkillSelectorPopup(
        onDismiss = toolSkillManager::closeCombinedSelector,
        onToolSelected = toolSkillManager::selectTool,
        onSkillSelected = toolSkillManager::selectSkill
    )
}
```

## 🎨 界面布局

### 工具/技能快速选择栏

```
[🔧 工具 ▾] [🧠 技能 ▾] [📁 文件搜索] [💻 代码助手] [🌐 网页浏览] [⌨️ 终端]
```

### 已选择状态显示

```
✨ 已选择: [🔧 文件搜索 ×] [💻 代码助手 ×]
```

### 输入框增强

```
┌─────────────────────────────────────────┐
│ [🔧 工具 ▾] [🧠 技能 ▾]                │
├─────────────────────────────────────────┤
│ ┌─────────────────────────────────────┐ │
│ │ 输入消息 [使用工具: 文件搜索]        │ │
│ └─────────────────────────────────────┘ │
│                                     [➤] │
└─────────────────────────────────────────┘
```

### 选择器弹窗

```
┌─────────────────────────────────────┐
│ 选择工具或技能                   [✕] │
├─────────────────────────────────────┤
│ [🔍 搜索...                      🗑️] │
├─────────────────────────────────────┤
│ [ 工具 ]  [ 技能 ]                   │
├─────────────────────────────────────┤
│ 可用工具 (42)                         │
│ [📁 文件搜索] [💻 代码助手] [🌐 网页] │
└─────────────────────────────────────┘
```

## 🔄 选择器状态管理

### ToolSkillSelectionManager

```kotlin
class ToolSkillSelectionManager {
    // 当前选择
    var selectedTool: String? = null
    var selectedSkill: String? = null

    // 选择器可见性
    var showCombinedSelector: Boolean = false

    // 选择方法
    fun selectTool(toolName: String?)
    fun selectSkill(skillName: String?)
    fun clearSelection()

    // 可见性控制
    fun openCombinedSelector()
    fun closeCombinedSelector()

    // 辅助方法
    fun hasSelection(): Boolean
    fun getSelectionSummary(): String
    fun buildEnhancedPrompt(originalPrompt: String): String
}
```

## 📊 支持的工具类别

| 类别 | 图标 | 说明 |
|------|------|------|
| FILE_OPERATION | 📁 | 文件操作 |
| CODE_GENERATION | 💻 | 代码生成 |
| COMPILATION | 🔨 | 编译构建 |
| SHELL_EXECUTION | ⌨️ | Shell执行 |
| WEB_REQUEST | 🌐 | 网络请求 |
| DATABASE | 🗄️ | 数据库 |
| SYSTEM_INFO | ℹ️ | 系统信息 |
| NETWORK | 🌐 | 网络 |
| SECURITY | 🔒 | 安全 |
| UTILITY | 🔧 | 工具 |

## 🎯 支持的技能

- **代码生成** - 使用AI生成高质量代码
- **文档撰写** - 帮助撰写各类文档
- **数据分析** - 分析数据并生成报告
- **翻译助手** - 多语言翻译
- **创意写作** - 创意内容和文案
- **问题解答** - 回答各类问题
- **学习辅导** - 学科知识辅导
- **项目管理** - 项目规划和管理

## 🔗 与现有系统集成

### 工具系统集成

该功能使用项目中现有的 `BurstToolRegistry` 获取可用工具列表:

```kotlin
val allTools = BurstToolRegistry.getAllToolDescriptors()
```

### 提示词增强

当用户选择工具或技能后，发送的消息会被增强:

**原始消息:**
```
帮我写一个排序算法
```

**增强后:**
```
【指定工具: 代码助手】
帮我写一个排序算法
```

## 💡 使用场景

### 场景1: 指定工具回答
用户想要使用特定工具获取信息:
```
选择工具: 文件搜索
发送: 查找所有PDF文件
```

### 场景2: 指定技能回答
用户想要特定技能处理:
```
选择技能: 翻译助手
发送: 翻译这段文字为英文
```

### 场景3: 同时指定
用户想要使用特定工具和技能:
```
选择工具: 代码助手
选择技能: 代码生成
发送: 写一个快速排序算法
```

## 🎉 优势

1. **直观易用** - 清晰的UI，一目了然
2. **灵活选择** - 支持多种组合方式
3. **无缝集成** - 与现有输入框完美融合
4. **状态管理** - 完善的状态管理和提示
5. **可扩展性** - 易于添加新的工具和技能类别

## 📝 注意事项

1. 选择工具或技能后，相关信息会自动添加到消息中
2. 可以随时通过点击 "×" 按钮清除选择
3. 发送消息后，选择会自动清除
4. 弹窗选择器提供更多工具和技能选项
5. 快捷标签提供最常用的工具快速访问

## 🚀 后续优化方向

- 添加最近使用的工具/技能记录
- 添加工具/技能使用统计
- 添加智能推荐功能（基于消息内容推荐工具）
- 支持自定义工具/技能快捷方式
- 添加拖拽排序功能
