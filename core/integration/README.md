# integration

> 集成大目录模块 — 统一管理 Skills / MCP / 插件 / 模型平台的外部集成

## 📋 模块定位

`integration` 是集成大目录的**核心业务层**，提供：

- **4 大模块**：Skills / MCP / Plugins / Model Platforms
- **多市场支持**：每个大模块下可注册多个市场（小模块）
- **已安装管理**：统一的安装/卸载/启用/禁用/更新
- **导入功能**：从文件/URL/Git/剪贴板导入集成项
- **高扩展性**：通过 `MarketRegistry` + `ServiceLoader` 支持插件化市场

## 🏗️ 架构

```
IntegrationCenter（门面）
  ├── skillModule: SkillModule              → 技能市场
  │     └── MarketRegistry.getByCategory(SKILLS)
  │           ├── ApexSkillMarket
  │           ├── CommunitySkillMarket
  │           └── ...（可扩展）
  ├── mcpModule: McpModule                  → MCP 市场
  │     └── MarketRegistry.getByCategory(MCP)
  │           ├── McpSoMarket
  │           ├── OfficialMcpMarket
  │           └── ...（可扩展）
  ├── pluginModule: PluginModule            → 插件市场
  │     └── MarketRegistry.getByCategory(PLUGINS)
  ├── modelPlatformModule: ModelPlatformModule → 模型平台市场
  │     └── MarketRegistry.getByCategory(MODEL_PLATFORMS)
  │           ├── DeepSeekMarket
  │           ├── ClaudeMarket
  │           ├── OpenAIMarket
  │           └── ...（可扩展）
  ├── installedManager: InstalledManager    → 已安装管理
  └── importer: IntegrationImporter         → 导入功能
```

## 🚀 快速开始

### 创建集成中心

```kotlin
val center = IntegrationCenter.create()

// 或自动发现市场（通过 ServiceLoader）
val center = IntegrationCenter.createWithAutoDiscovery()
```

### 注册自定义市场

```kotlin
// 方式 1：DSL 风格
integrationCenter {
    registerMarket {
        marketId = "my_skill_market"
        displayName = "我的技能市场"
        category = IntegrationCategory.SKILLS
        description = "自定义技能市场"

        onSearch { filter ->
            // 搜索逻辑
            MarketSearchResult(items = myItems, totalCount = myItems.size)
        }

        onGetItem { id ->
            myItems.find { it.id == id }
        }
    }
}

// 方式 2：实现接口
class MyMarket : IntegrationMarket {
    override val marketId = "my_market"
    override val displayName = "我的市场"
    override val category = IntegrationCategory.SKILLS
    override val description = "..."
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter) = ...
    override suspend fun getItem(itemId: String) = ...
    override suspend fun getCategories() = ...
}
MarketRegistry.register(MyMarket())
```

### 搜索

```kotlin
// 跨所有技能市场搜索
val results = center.skillModule.searchAcrossMarkets(
    MarketSearchFilter(query = "代码分析", sortBy = SortBy.RATING)
)

// 在指定市场搜索
val results = center.skillModule.searchInMarket("apex_skill_market", filter)
```

### 安装/卸载

```kotlin
// 安装
center.skillModule.install(marketItem)

// 卸载
center.skillModule.uninstall("item_id")

// 启用/禁用
center.skillModule.setEnabled("item_id", false)
```

### 查看已安装

```kotlin
// 观察已安装变化
center.installedSnapshot.collect { snapshot ->
    println("总计: ${snapshot.totalCount}")
    snapshot.byCategory.forEach { (cat, count) ->
        println("  ${cat.displayName}: $count")
    }
}

// 按分类查询
val installedSkills = center.getInstalledByCategory(IntegrationCategory.SKILLS)

// 获取可更新的项
val updatable = center.getUpdatable()
```

### 导入

```kotlin
// 从文件导入
val result = center.import(
    source = ImportSource(
        type = ImportSourceType.LOCAL_FILE,
        path = "/sdcard/skill.json"
    ),
    category = IntegrationCategory.SKILLS
)

// 从 URL 导入
val result = center.import(
    source = ImportSource(
        type = ImportSourceType.URL,
        url = "https://example.com/skill.json"
    ),
    category = IntegrationCategory.SKILLS,
    autoInstall = true
)

// 从直接内容导入
val result = center.import(
    source = ImportSource(
        type = ImportSourceType.DIRECT_INPUT,
        content = jsonString
    ),
    category = IntegrationCategory.MCP
)
```

### 模型平台特殊用法

```kotlin
// 安装模型平台（配置 API Key）
center.modelPlatformModule.install(
    item = marketItem,
    apiKey = "sk-xxx",
    endpoint = "https://api.deepseek.com"  // 可选
)

// 获取 API Key
val apiKey = center.modelPlatformModule.getApiKey("deepseek")

// 更新 API Key
center.modelPlatformModule.updateApiKey("deepseek", "sk-new-key")
```

## 📦 模块结构

```
integration/
├── api/                        # 对外 API
│   ├── IntegrationCenter.kt    # 顶层门面
│   ├── IntegrationCategory.kt  # 4 大分类枚举
│   └── IntegrationDsl.kt      # DSL 扩展
├── market/                     # 市场抽象
│   ├── IntegrationMarket.kt    # 市场接口
│   ├── MarketItem.kt           # 市场项 + 搜索结果
│   └── MarketRegistry.kt       # 市场注册表（单例）
├── category/                   # 4 大模块
│   ├── skills/SkillModule.kt
│   ├── mcp/McpModule.kt
│   ├── plugins/PluginModule.kt
│   └── models/ModelPlatformModule.kt
├── installed/                  # 已安装管理
│   └── InstalledManager.kt
├── importer/                   # 导入功能
│   └── IntegrationImporter.kt
└── exception/                  # 异常
    └── IntegrationException.kt
```

## 🔌 扩展点

| 扩展点 | 接口 | 用途 |
|--------|------|------|
| 自定义市场 | `IntegrationMarket` | 实现新市场 |
| 市场注册 | `MarketRegistry.register()` | 动态注册 |
| 自动发现 | `ServiceLoader` | META-INF/services |
| 导入来源 | `ImportSource` | 自定义导入来源 |

## 📄 License

Apache 2.0
