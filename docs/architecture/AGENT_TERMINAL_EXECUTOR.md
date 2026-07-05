# Agent 终端工具 (AgentTerminalExecutor)

让 Agent 高效调用终端的核心执行层 — 终端是 Agent 的工具,不是给人敲的。

## 🎯 设计理念

之前的 `TerminalToolExecutor` 有 4 大效率瓶颈:

| 瓶颈 | 旧实现 | 新实现 |
|------|--------|--------|
| **命令输出** | `executeCommand` 只返回 boolean | `exec()` 返回 stdout + stderr + exitCode |
| **批量执行** | 每条命令独立调用,工作目录丢失 | `execBatch()` 共享工作目录,cd 自动跟随 |
| **流式输出** | 长命令阻塞,Agent 等不到 | `execStream()` 返回 Flow,实时推送 |
| **会话保持** | 每次创建新 session | `createSession()` + `sessionExec()` 持久会话 |

## 📂 文件

```
ai-terminal/src/main/java/com/ai/assistance/aiterminal/terminal/ai/
├── AgentTerminalExecutor.kt       # 核心执行器(11 个工具)
├── AgentTerminalToolProvider.kt   # LLM Tool Call 适配器
└── TerminalToolExecutor.kt        # 旧实现(保留兼容)

app/src/main/java/com/apex/agent/presentation/enhancedterminal/di/
└── AgentTerminalModule.kt         # Hilt 依赖注入
```

## 🛠️ 11 个 Agent 工具

### 命令执行
| 工具 | 说明 | 返回 |
|------|------|------|
| `agent_exec` | 执行单条命令 | `{stdout, stderr, exitCode, success, durationMs}` |
| `agent_exec_batch` | 批量执行(共享工作目录) | `{results: [...], allSuccess, totalDurationMs}` |
| `agent_pipeline` | 管道 cmd1\|cmd2\|cmd3 | 同 exec |

### 持久会话
| 工具 | 说明 |
|------|------|
| `agent_session_create` | 创建会话,返回 sessionId |
| `agent_session_exec` | 在会话中执行(cd/export 保持) |
| `agent_session_close` | 关闭会话 |

### 文件操作
| 工具 | 说明 |
|------|------|
| `agent_file_tree` | 目录树 JSON(含大小/子节点) |
| `agent_grep` | 高级搜索(正则+文件过滤+上下文) |

### 后台执行
| 工具 | 说明 |
|------|------|
| `agent_bg_exec` | 后台执行,立即返回 taskId |
| `agent_bg_status` | 轮询状态和已输出 |
| `agent_bg_cancel` | 取消后台任务 |

## 🔌 集成方式

### 1. Hilt 注入(推荐)

```kotlin
@HiltViewModel
class MyAgentViewModel @Inject constructor(
    private val toolProvider: AgentTerminalToolProvider,
) : ViewModel() {

    // 获取所有工具 schema(传给 LLM)
    val tools: List<ToolPrompt> = toolProvider.getAllToolPrompts()

    // LLM 返回 tool_call 时执行
    suspend fun handleToolCall(toolName: String, args: String): String {
        return toolProvider.executeToolCall(toolName, args)
    }
}
```

### 2. 直接使用 Executor

```kotlin
val executor = AgentTerminalExecutor(context)

// 单条命令
val result = executor.exec("ls -la /data")
println(result.stdout)  // 完整输出
println(result.exitCode) // 退出码

// 批量执行
val batch = executor.execBatch(listOf(
    "cd /project",
    "git pull",
    "./gradlew build",
    "echo BUILD_DONE"
))

// 流式
executor.execStream("npm install").collect { line ->
    // 实时处理每行输出
}

// 持久会话
val sid = executor.createSession("~")
executor.sessionExec(sid, "cd /project")
executor.sessionExec(sid, "git status")  // 在 /project 下
executor.closeSession(sid)

// 后台执行
val taskId = executor.execBackground("gradle build --no-daemon")
// ... 做其他事 ...
val status = executor.getBgTaskStatus(taskId)
println(status?.status)  // RUNNING / COMPLETED / FAILED
```

## 📊 LLM Function Calling 示例

LLM 收到工具列表后,可能这样调用:

```json
// LLM 请求
{"name": "agent_session_create", "arguments": {"working_dir": "/home/user/project"}}

// 系统返回
{"sessionId": "agent_session_1234567890", "workingDir": "/home/user/project"}

// LLM 在会话中执行
{"name": "agent_session_exec", "arguments": {"session_id": "agent_session_1234567890", "command": "git status -sb"}}

// 系统返回
{"stdout": "## main...origin/main\n M src/Main.kt\n", "stderr": "", "exitCode": 0, "success": true}

// LLM 继续搜索
{"name": "agent_grep", "arguments": {"pattern": "TODO", "file_pattern": "*.kt", "context_lines": 2}}

// 系统返回结构化结果
{"results": [{"file": "/home/user/project/Main.kt", "line": 42, "content": "// TODO: fix this"}]}

// LLM 关闭会话
{"name": "agent_session_close", "arguments": {"session_id": "agent_session_1234567890"}}
```

## ✨ 关键优势

1. **输出完整返回** — Agent 能看到命令的 stdout/stderr/exitCode,做智能决策
2. **会话保持** — cd/export 状态跨多次调用保持,不用每次重新定位
3. **流式输出** — 长命令(build/install)实时返回,Agent 可超时取消
4. **后台执行** — 不阻塞 Agent,可轮询状态
5. **结构化搜索** — grep 返回 JSON(file/line/content/context),比纯文本更易解析
6. **Hilt 注入** — 全局单例,Agent 直接 @Inject 获取
