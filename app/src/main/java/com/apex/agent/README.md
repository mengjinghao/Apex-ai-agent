# Apex Agent 增强功能

## 已实现的功能

### 1. 知识图谱系统
- **轻量级图数据库集成**：基于SQLite实现的图数据库
- **实体-关系-事件模型**：完整的知识表示
- **自然语言到图查询转换**：支持自然语言查询
- **知识图谱可视化界面**：直观的图形化展示

### 2. Docker沙箱执行环境
- **容器隔离**：使用Docker容器隔离执行环境
- **资源限制**：CPU、内存限制
- **网络隔离**：可选的网络隔离
- **安全执行**：沙箱保护

### 3. 多Agent协作框架
- **CrewAI风格**：支持多Agent协作
- **顺序执行**：任务按顺序执行
- **层级执行**：Manager协调的层级执行
- **角色定义**：明确的Agent角色和目标

### 4. Skills自进化系统
- **自动技能创建**：从成功任务自动创建技能
- **技能自我改进**：基于执行结果持续优化
- **技能评估**：自动评估技能效果
- **技能推荐**：基于任务描述推荐技能

### 5. 4层记忆架构
- **L1 会话上下文**：当前对话窗口
- **L2 持久化事实**：分层记忆系统
- **L3 FTS5全文搜索**：10ms级快速检索
- **L4 知识图谱**：结构化知识存储

### 6. FTS5全文搜索
- **快速检索**：10ms级搜索速度
- **高亮显示**：搜索结果高亮
- **优化索引**：自动索引优化
- **支持复杂查询**：FTS5语法支持

## 技术架构

```
┌─────────────────────────────────────────────────────┐
│                UnifiedMemoryManager                  │
│            (统一记忆管理器)                           │
├─────────────────────────────────────────────────────┤
│  ┌───────────────────┐  ┌──────────────────────┐   │
│  │  KnowledgeGraph   │  │  HierarchicalMemory  │   │
│  │   Memory          │  │   (原有分层记忆)       │   │
│  │   (知识图谱记忆)   │  │                      │   │
│  └─────────┬─────────┘  └──────────────────────┘   │
│            │                                        │
│  ┌─────────▼─────────┐  ┌──────────────────────┐   │
│  │ GraphDatabaseManager│ │   FTS5SearchManager  │   │
│  │  (SQLite图数据库)   │ │  (全文搜索索引)       │   │
│  └─────────────────────┘  └──────────────────────┘   │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│               MultiAgentManager                     │
│            (多Agent协作管理)                         │
├─────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │    Agent    │  │    Agent    │  │    Agent    │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  │
│  ┌───────────────────────────────────────────────┐  │
│  │                    Crew                      │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│              SkillEvolutionManager                 │
│            (技能自进化管理)                         │
├─────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │ SkillStore  │  │SkillEvaluator│  │SkillExecutor│  │
│  └─────────────┘  └─────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│                  DockerManager                     │
│            (Docker沙箱管理)                          │
├─────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │ Container   │  │ Container   │  │ Container   │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────┘
```

## 使用方式

### 1. 知识图谱

```kotlin
// 初始化统一记忆管理器
val unifiedMemory = UnifiedMemoryManager(context)

// 存储记忆
unifiedMemory.storeMemory(
    taskId = "task_123",
    chunkId = "chunk_456",
    content = "John met Mary at the park yesterday"
)

// 检索记忆
val results = unifiedMemory.retrieveMemory(
    taskId = "task_123",
    query = "Where did John meet Mary?"
)

// 启动可视化界面
val intent = Intent(context, GraphVisualizerActivity::class.java)
startActivity(intent)
```

### 2. 多Agent协作

```kotlin
// 初始化多Agent管理器
val multiAgentManager = MultiAgentManager(context)

// 创建Agent
val researcher = multiAgentManager.createAgent(
    name = "Researcher",
    role = "Researcher",
    goal = "Find information about AI agents",
    backstory = "Expert in AI research",
    tools = listOf("web_search")
)

val writer = multiAgentManager.createAgent(
    name = "Writer",
    role = "Writer",
    goal = "Write a report about AI agents",
    backstory = "Professional technical writer",
    tools = listOf("text_editor")
)

// 创建任务
val task1 = Task(
    description = "Research latest AI agent technologies",
    agentId = researcher.id
)

val task2 = Task(
    description = "Write a comprehensive report based on research",
    agentId = writer.id
)

// 创建Crew
val crew = multiAgentManager.createCrew(
    name = "AI Research Team",
    agents = listOf(researcher.id, writer.id),
    tasks = listOf(task1, task2),
    process = ProcessType.SEQUENTIAL
)

// 执行Crew
val result = multiAgentManager.executeCrew(crew.id)
```

### 3. Docker沙箱

```kotlin
// 初始化Docker管理器
val dockerManager = DockerManager(context)

// 检查Docker是否可用
val isAvailable = dockerManager.isDockerAvailable()

if (isAvailable) {
    // 创建沙箱容器
    val containerName = dockerManager.createSandbox()
    
    // 在容器中执行命令
    val result = dockerManager.executeInContainer(
        containerName,
        "echo 'Hello from sandbox'"
    )
    
    // 清理容器
    dockerManager.removeContainer(containerName)
}
```

### 4. Skills自进化

```kotlin
// 初始化技能进化管理器
val skillManager = SkillEvolutionManager(context)

// 从成功任务创建技能
val skill = skillManager.createSkillFromTask(
    taskDescription = "Search for latest AI news",
    taskResult = "Found 5 latest AI news articles",
    toolsUsed = listOf("web_search")
)

// 执行技能
val executionResult = skillManager.executeSkill(
    skillId = skill.id,
    inputs = mapOf("query" to "AI agent latest developments")
)

// 搜索技能
val skills = skillManager.searchSkills("AI")

// 获取推荐技能
val recommendedSkills = skillManager.getRecommendedSkills(
    "Find information about machine learning"
)
```

## 性能优化

- **异步操作**：所有数据库和网络操作都在协程中执行
- **索引优化**：使用SQLite索引加速查询
- **内存缓存**：减少数据库操作
- **批量处理**：支持批量操作提高效率
- **资源限制**：Docker容器资源限制保护系统

## 安全特性

- **沙箱隔离**：Docker容器隔离执行环境
- **网络隔离**：可选的网络隔离
- **资源限制**：防止资源滥用
- **本地存储**：所有数据存储在本地，保护隐私

## 未来扩展

- **NLP增强**：集成更强大的NLP模型
- **多模态支持**：支持图像、语音等多模态输入
- **云同步**：可选的云同步功能
- **社区技能**：技能分享和社区贡献
- **企业集成**：企业级集成和管理
