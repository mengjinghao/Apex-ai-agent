# 模型切换 & 增强输入系统

## 📝 功能概述

在AI输入框中增加**模型切换**、**工具选择**、**技能选择**功能，支持快速切换和组合使用。

## 🎯 功能特点

### 1. 模型快速切换
- 在线模型（GPT-4、Claude、Gemini等）
- 本地模型（Llama、MNN等）
- 快速选择标签
- 模型详情弹窗

### 2. 工具/技能选择（之前已实现）
- 快速工具标签
- 技能选择弹窗
- 组合使用

### 3. 整合增强系统
- 三者整合在一个输入栏
- 智能占位符提示
- 一键清除所有选择

## 📦 新增文件

### 核心组件
1. **ModelQuickSelector.kt** - 模型快速选择UI
   - 模型切换按钮
   - 快捷模型标签
   - 模型选择弹窗
   - 在线/本地模型列表

2. **IntegratedEnhancementBar.kt** - 整合增强栏
   - 整合模型+工具+技能
   - 选择摘要显示
   - 完整输入栏

## 🔧 使用方法

### 1. 简单模型选择器

```kotlin
@Composable
fun SimpleModelExample() {
    var currentModel by remember { mutableStateOf<String?>(null) }
    var draftText by remember { mutableStateOf("") }

    Column {
        // 模型快速选择
        ModelQuickSelector(
            currentModel = currentModel,
            onModelSelected = { model -> currentModel = model },
            onOpenModelSelector = { /* show selector */ }
        )

        // 输入框
        OutlinedTextField(
            value = draftText,
            onValueChange = { draftText = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

### 2. 完整整合输入栏

```kotlin
@Composable
fun CompleteInputExample() {
    var draftText by remember { mutableStateOf("") }
    var currentModel by remember { mutableStateOf<String?>(null) }
    var selectedTool by remember { mutableStateOf<String?>(null) }
    var selectedSkill by remember { mutableStateOf<String?>(null) }

    CompleteIntegratedInputBar(
        draftText = draftText,
        onDraftTextChange = { draftText = it },
        currentModel = currentModel,
        selectedTool = selectedTool,
        selectedSkill = selectedSkill,
        onModelSelected = { currentModel = it },
        onToolSelected = { selectedTool = it },
        onSkillSelected = { selectedSkill = it },
        onSendMessage = {
            val prompt = buildEnhancedPrompt(draftText, currentModel, selectedTool, selectedSkill)
            sendMessage(prompt)
            draftText = ""
            currentModel = null
            selectedTool = null
            selectedSkill = null
        }
    )
}
```

### 3. 仅模型选择弹窗

```kotlin
@Composable
fun ModelSelectorExample() {
    var showSelector by remember { mutableStateOf(false) }
    var currentModel by remember { mutableStateOf<String?>(null) }

    // 显示弹窗
    if (showSelector) {
        ModelSelectorPopup(
            onDismiss = { showSelector = false },
            onModelSelected = { model ->
                currentModel = model
                showSelector = false
            },
            currentModel = currentModel
        )
    }
}
```

## 🎨 界面布局

### 整合增强栏

```
┌────────────────────────────────────────────────────────────┐
│ [✨ GPT-4 ▾] [🔧 工具 ▾] [🧠 技能 ▾]              [🗑️ 清除] │
├────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐   │
│ │ 输入消息 · GPT-4, 搜索, 代码生成                   ➤ │   │
│ └──────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 选择摘要显示

```
[✨ GPT-4 ▾] [🔧 搜索 ▾] [🧠 代码 ▾]                    [🗑️]
```

### 模型选择弹窗

```
┌────────────────────────────────────────────────────────┐
│ 切换模型                                          [✕]   │
├────────────────────────────────────────────────────────┤
│ [🔍 搜索模型...                                  🗑️]   │
├────────────────────────────────────────────────────────┤
│ [ 在线 ]  [ 本地 ]                                   │
├────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────┐   │
│ │ [☁️]  GPT-4o                                   [✓] │   │
│ │      OpenAI • 在线                               │   │
│ └──────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────┐   │
│ │ [☁️]  Claude 3.5 Sonnet                           │   │
│ │      Anthropic • 在线                             │   │
│ └──────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────┐   │
│ │ [💾]  Llama-3-8B-Q4                            │   │
│ │      Q4_K_M • 4.5 GB                            │   │
│ └──────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

## 📊 支持的模型

### 在线模型
- **OpenAI**: GPT-4o, GPT-4o Mini, GPT-4 Turbo
- **Anthropic**: Claude 3.5 Sonnet, Claude 3 Opus, Claude 3 Haiku
- **Google**: Gemini 1.5 Pro, Gemini 1.5 Flash
- **DeepSeek**: DeepSeek Chat, DeepSeek Coder
- **Alibaba**: Qwen 2.5, Qwen Coder
- **Moonshot**: Kimi Chat
- **01.AI**: Yi Large
- **Zhipu**: GLM-4

### 本地模型
- 从 `ModelRegistry` 自动获取
- 支持 Llama、MNN 等格式
- 显示模型大小和量化信息

## 🔗 整合的系统

### ToolSkillSelectionManager
```kotlin
class ToolSkillSelectionManager {
    var selectedTool: String? = null
    var selectedSkill: String? = null

    fun selectTool(toolName: String?)
    fun selectSkill(skillName: String?)
    fun clearSelection()
    fun hasSelection(): Boolean
    fun buildEnhancedPrompt(originalPrompt: String): String
}
```

### Model Integration
```kotlin
// 模型选择状态
var currentModel: String? = null

// 选择模型
onModelSelected("GPT-4o")

// 构建增强提示词
val enhancedPrompt = buildString {
    append("【模型: GPT-4o】")
    append("【工具: 文件搜索】")
    append("【技能: 代码生成】")
    append(originalPrompt)
}
```

## 🎯 使用场景

### 场景1: 快速切换模型回答
```
选择模型: GPT-4
发送: 解释量子计算
```

### 场景2: 指定工具获取信息
```
选择模型: Claude
选择工具: 文件搜索
发送: 查找项目文档
```

### 场景3: 完整增强回答
```
选择模型: GPT-4o
选择工具: 代码助手
选择技能: 代码生成
发送: 写一个排序算法
```

### 场景4: 本地模型离线使用
```
选择模型: Llama-3-8B-Q4
发送: 用本地模型回答
```

## 💡 优势

1. **一目了然** - 所有增强选项在一行显示
2. **快速切换** - 点击即可切换模型/工具/技能
3. **灵活组合** - 支持任意组合使用
4. **清晰反馈** - 已选择项清晰显示
5. **便捷清除** - 一键清除所有选择

## 🚀 后续优化

- 添加模型使用统计
- 智能推荐模型（基于问题类型）
- 最近使用模型快速访问
- 模型性能对比显示
- 自定义模型快捷方式
