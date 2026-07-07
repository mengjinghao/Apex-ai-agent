# Market APK — 完整能力清单

> 27 个市场 + LLM 调用 + 本地技能调用 + 缓存 + 收藏夹 + 使用统计 + 批量操作

---

## 0. 与原项目（Apex-auto-agent）的对比

| 能力 | 原项目 | Apex Market APK |
|------|--------|----------------|
| 市场数 | 27 个（同） | 27 个（同） |
| invokeModel | ❌ stub | ✅ 真实调用 11 个 Provider |
| invokeLocalSkill | ❌ stub | ✅ 真实调用 MCP/Plugin/Skill |
| 市场缓存 | ❌ | ✅ 5 分钟 TTL + 持久化 |
| 收藏夹 | ❌ | ✅ 持久化 + 备注 |
| 使用统计 | ❌ | ✅ "最近使用"/"最常使用" |
| 批量安装/卸载 | ❌ | ✅ |
| 更新检查 | ❌ | ✅ checkForUpdates + updateAll |
| 市场诊断 | ❌ | ✅ |
| 套件 APK 商店 | ❌ | ✅ 9 APK 统一管理 |

---

## 1. LLM 调用（11 个 Provider）

### 支持的 Provider

| Provider | baseUrl | 协议 | 默认模型 |
|----------|---------|------|----------|
| DeepSeek | api.deepseek.com/v1 | OpenAI 兼容 | deepseek-chat |
| Claude | api.anthropic.com/v1 | Anthropic | claude-sonnet-4-20250514 |
| OpenAI | api.openai.com/v1 | OpenAI 兼容 | gpt-4o |
| 通义千问 | dashscope.aliyuncs.com/api/v1 | OpenAI 兼容 | qwen-max |
| 智谱 GLM | open.bigmodel.cn/api/paas/v4 | OpenAI 兼容 | glm-4 |
| Moonshot | api.moonshot.cn/v1 | OpenAI 兼容 | moonshot-v1-128k |
| MiniMax | api.minimax.chat/v1 | OpenAI 兼容 | abab6.5-chat |
| Baichuan | api.baichuan-ai.com/v1 | OpenAI 兼容 | Baichuan2-Turbo |
| Ollama | localhost:11434/v1 | OpenAI 兼容 | llama3.2 |
| Agnes | api.agnes.ai/v1 | OpenAI 兼容 | agnes-text-pro |
| ... | ... | ... | ... |

### API Key 来源
1. InstalledManager 中已安装的 model platform 的 `metadata["apiKey"]`
2. 环境变量（Android 通常无效）

### 使用示例
```kotlin
// 调用 DeepSeek
val result = ApexClient.market.invokeModel(
    provider = "deepseek",
    modelName = "deepseek-chat",
    prompt = "解释 Kotlin 协程",
    maxTokens = 2048,
    systemPrompt = "你是 Kotlin 专家",
    temperature = 0.7f
)

// 列出所有 Provider（含 apiKey 状态）
val providers = ApexClient.market.listAvailableProviders()
// [✓] DeepSeek (deepseek) - deepseek-chat
// [✗] Claude (claude) - claude-sonnet-4-20250514
// [✓] OpenAI (openai) - gpt-4o
// ...

// 检查某 Provider 是否可用
val available = ApexClient.market.isProviderAvailable("deepseek")
```

---

## 2. 本地技能调用（MCP/Plugin/Skill）

### 支持的方法

| 分类 | 方法 |
|------|------|
| MCP | list_tools / call_tool / list_resources / read_resource |
| Plugin | execute / info / config |
| Skill | execute / describe / validate |
| ModelPlatform | chat / complete / embed |

### 使用示例
```kotlin
// 列出 MCP 工具
val tools = ApexClient.market.invokeLocalSkill(
    itemId = "filesystem-mcp",
    method = "list_tools"
)

// 调用 MCP 工具
val result = ApexClient.market.invokeLocalSkill(
    itemId = "filesystem-mcp",
    method = "call_tool",
    argsJson = """{"tool":"read_file","path":"/sdcard/test.txt"}"""
)

// 列出某项支持的方法
val methods = ApexClient.market.listLocalSkillMethods("filesystem-mcp")
// ["list_tools", "call_tool", "list_resources", "read_resource"]

// 获取元数据
val meta = ApexClient.market.getInstalledItemMetadata("filesystem-mcp")
```

---

## 3. 市场缓存

**策略**：
- 按 (marketId, query, category) 维度缓存
- 默认 TTL = 5 分钟
- 持久化到本地 JSON（重启后仍可用）

```kotlin
// 搜索（默认启用缓存）
val results = ApexClient.market.search("SKILLS", "react", useCache = true)

// 清除指定市场缓存
ApexClient.market.clearCacheForMarket("smithery")

// 清除所有缓存
ApexClient.market.clearAllCache()

// 清除过期缓存
ApexClient.market.cleanExpiredCache()

// 缓存统计
val stats = ApexClient.market.getCacheStats()
// {entryCount: 24, totalSizeMb: 0.15}
```

---

## 4. 收藏夹

```kotlin
// 添加收藏
ApexClient.market.addFavorite(
    itemId = "react-skill",
    category = "SKILLS",
    name = "ReAct 推理",
    description = "ReAct 推理技能",
    note = "常用"
)

// 切换收藏状态
ApexClient.market.toggleFavorite("react-skill", "SKILLS", "ReAct 推理")

// 列出所有收藏
val favs = ApexClient.market.listFavorites()

// 按分类列出
val skillFavs = ApexClient.market.listFavorites("SKILLS")

// 搜索收藏
val results = ApexClient.market.searchFavorites("react")

// 更新备注
ApexClient.market.updateFavoriteNote("react-skill", "重要技能")

// 收藏总数
val count = ApexClient.market.favoritesCount()
```

---

## 5. 使用统计

**跟踪的事件**：SEARCH / VIEW / INSTALL / UNINSTALL / INVOKE / FAVORITE / UNFAVORITE

```kotlin
// "最近使用"列表
val recent = ApexClient.market.getRecentlyUsed(limit = 20)

// "最常使用"排行榜（invokeCount + viewCount*2 + searchCount）
val mostUsed = ApexClient.market.getMostUsed(limit = 20)

// 某项的统计
val stats = ApexClient.market.getItemStats("react-skill")
// {searchCount: 5, viewCount: 3, invokeCount: 2, isInstalled: true}

// 总统计
val total = ApexClient.market.getTotalUsageStats()
// {totalItems: 50, totalSearches: 120, totalInstalls: 8, currentlyInstalled: 5}

// 按分类统计
val byCat = ApexClient.market.getUsageByCategory()
// {SKILLS: 80, MCP: 30, PLUGINS: 10, MODEL_PLATFORMS: 5}

// 最近事件流
val events = ApexClient.market.getRecentEvents(limit = 100)

// 手动记录查看事件
ApexClient.market.recordView("react-skill", "ReAct 推理", "SKILLS")

// 清除统计
ApexClient.market.clearUsageStats()
```

---

## 6. 批量操作 + 更新检查

```kotlin
// 批量安装
val results = ApexClient.market.batchInstall(listOf(
    Triple("skill-1", "SKILLS", null),
    Triple("mcp-1", "MCP", "/sdcard/mcp"),
    Triple("plugin-1", "PLUGINS", null)
))

// 批量卸载
val results = ApexClient.market.batchUninstall(listOf("skill-1", "mcp-1"))

// 检查更新（基于已知 latestVersion）
val updatable = ApexClient.market.checkForUpdates()
// [{id: "react-skill", installedVersion: "1.0", latestVersion: "1.1"}, ...]

// 一键更新所有
val updateResults = ApexClient.market.updateAll()

// 刷新所有市场（清缓存 + 重新加载）
ApexClient.market.refreshAllMarkets()

// 刷新指定市场
ApexClient.market.refreshMarket("smithery")

// 市场诊断
val report = ApexClient.market.diagnose()
```

---

## 7. 搜索增强

```kotlin
// 跨所有市场搜索（带缓存）
val results = ApexClient.market.search("SKILLS", "react", limit = 50)

// 在指定市场内搜索
val smitheryResults = ApexClient.market.searchInMarket(
    marketId = "smithery",
    category = "MCP",
    query = "filesystem",
    limit = 20
)
```

---

## 8. 套件 APK 商店（已有功能）

```kotlin
// 列出所有 APK 的安装状态
val apks = ApexClient.market.listSuiteApks()

// 安装套件 APK
ApexClient.market.installSuiteApk(ApexSuite.ApkId.RAGE)

// 启动 APK
ApexClient.market.launchSuiteApk(ApexSuite.ApkId.RAGE)

// 安装摘要
val summary = ApexClient.market.getSuiteInstallSummary()
// "已安装 5/10（必须 5/5，可选 0/4，调试 0/1）"

// 检查必须 APK 缺失
val missing = ApexClient.market.checkRequiredApks()
```

---

## 9. 完整 API 速查（Apex 独有增强）

### LLM 调用（3 个方法）
```kotlin
invokeModel / listAvailableProviders / isProviderAvailable
```

### 本地技能调用（3 个方法）
```kotlin
invokeLocalSkill / listLocalSkillMethods / getInstalledItemMetadata
```

### 搜索增强（1 个方法）
```kotlin
searchInMarket
```

### 收藏夹（9 个方法）
```kotlin
addFavorite / removeFavorite / toggleFavorite / isFavorite
listFavorites / searchFavorites / updateFavoriteNote / clearFavorites / favoritesCount
```

### 使用统计（8 个方法）
```kotlin
getItemStats / getRecentlyUsed / getMostUsed / getTotalUsageStats
getUsageByCategory / getRecentEvents / recordView / clearUsageStats
```

### 缓存管理（4 个方法）
```kotlin
clearCacheForMarket / clearAllCache / cleanExpiredCache / getCacheStats
```

### 批量操作 + 更新检查（7 个方法）
```kotlin
batchInstall / batchUninstall / checkForUpdates / updateAll
refreshAllMarkets / refreshMarket / diagnose
```

**总计 35 个 Apex 独有增强方法**，全部通过 `ApexClient.market.*` 调用。
