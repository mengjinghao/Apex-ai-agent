# 贡献指南

感谢你对 Apex Auto Agent 项目的兴趣！本文档描述如何参与贡献。

## 📋 目录

- [代码规范](#代码规范)
- [开发环境](#开发环境)
- [分支策略](#分支策略)
- [提交规范](#提交规范)
- [PR 流程](#pr-流程)
- [模块结构](#模块结构)
- [测试要求](#测试要求)

## 代码规范

### Kotlin 代码风格

- 遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 4 空格缩进，不用 Tab
- 行宽不超过 140 字符
- 禁止通配符 import（`import x.*`）
- 公共 API 必须有 KDoc 注释

代码风格由根目录的 `.editorconfig` 强制约束，IDE 会自动应用。

### 架构原则

1. **单一职责**：每个类只做一件事
2. **依赖注入**：通过构造函数注入，不用 ServiceLocator
3. **接口优先**：对外暴露接口，内部实现可替换
4. **无 UI 依赖**：业务逻辑层（core/data/domain/engine/plugins）禁止依赖 Compose/Activity/Fragment
5. **扩展点暴露**：所有可替换组件通过接口暴露（见下方扩展点列表）

### 扩展点接口（业务侧注入实现）

| 接口 | 文件 | 用途 |
|------|------|------|
| `LlmService` | `IncrementalReasoningEngine.kt` | 自定义 LLM 后端 |
| `CheckpointStore` | `IncrementalReasoningEngine.kt` | 断点续传存储 |
| `TokenRefreshProvider` | `WebSessionManagerPersistent.kt` | Web Token 自动刷新 |
| `RemoteSyncAdapter` | `DataSyncManager.kt` | 远程数据同步 |
| `ARVRInteractionManager.Backend` | `ARVRInteractionManager.kt` | AR/VR 引擎后端 |
| `SkillDevServer.ToolExecutor` | `SkillDevServer.kt` | Skill 工具执行 |
| `IBurstSkill` | `IBurstSkill.kt` | 自定义狂暴模式技能 |

## 开发环境

### 必需工具

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | 必须，不支持 8/11/21 |
| Android SDK | compileSdk 35 | API 35 (Android 15) |
| Min SDK | 26 | Android 8.0 |
| Android Studio | Hedgehog+ | 推荐 Iguana+ |
| NDK | 26+ | 仅编译 C++ 部分需要 |
| Git LFS | 任意 | 处理大文件 |

### 初始化

```bash
# 克隆仓库
git clone https://github.com/mengjinghao/Apex-auto-agent.git
cd Apex-auto-agent

# （可选）添加第三方 AI 引擎子模块
git submodule add https://github.com/ggml-org/llama.cpp llama/third_party/llama.cpp
git submodule update --init --recursive

# 构建
./gradlew :app:assembleDebug
```

## 分支策略

采用 Git Flow 简化版：

| 分支 | 用途 |
|------|------|
| `main` | 稳定发布分支 |
| `develop` | 开发集成分支 |
| `feature/*` | 功能开发分支 |
| `fix/*` | Bug 修复分支 |
| `release/*` | 发布准备分支 |

### 命名约定

```
feature/add-streaming-engine
fix/memory-leak-in-thumbnail-cache
release/v2.0.0
```

## 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Type 取值

| Type | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档变更 |
| `style` | 代码格式（不影响功能） |
| `refactor` | 重构（不新增功能、不修复 Bug） |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `build` | 构建系统或依赖变更 |
| `ci` | CI 配置变更 |
| `chore` | 杂项（不修改 src 或 test） |

### 示例

```
feat(core): 增量推理引擎支持 LLM 注入

通过 LlmService 接口让业务侧注入 LLM 实现，
默认使用 NoopLlmService 保证引擎可独立测试。

Closes #123
```

## PR 流程

1. **Fork & Clone**：Fork 仓库到个人空间
2. **创建分支**：`git checkout -b feature/your-feature`
3. **开发 & 测试**：确保 `./gradlew testDebugUnitTest` 通过
4. **代码审计**：运行 `bash scripts/code_audit/run_audit.sh`
5. **提交 PR**：目标分支为 `develop`（或 `main`）
6. **CI 检查**：PR 提交后会自动触发 CI，必须全部通过：
   - **Compile Check** — `compileDebugKotlin` 编译通过
   - **Unit Tests** — `testDebugUnitTest` 全部通过
   - **Lint** — `:app:lintDebug` 无 fatal 错误
   - **Code Quality** — `!!`/`runBlocking`/catch-all 统计（仅报告不阻断）
   - **Security Scan** — 依赖漏洞/权限/密钥泄露扫描
7. **Review**：至少 1 位 reviewer 批准
8. **合并**：Squash merge

### CI/CD 流水线说明

| Workflow | 文件 | 触发 | 作用 |
|----------|------|------|------|
| **CI** | `.github/workflows/ci.yml` | push/PR 到 main/develop | 编译 + 单测 + lint，**阻断合并** |
| **Code Quality** | `.github/workflows/code-quality.yml` | push/PR + 每周一 | 扫描 `!!`/`runBlocking`/catch-all/明文URL，**仅报告** |
| **Security Scan** | `.github/workflows/security.yml` | push/PR + 每周一 | 依赖漏洞 + 权限审计 + 密钥泄露，**high/critical 阻断** |
| **Build & Release APK** | `.github/workflows/release-apk.yml` | tag `v*` 或手动 | 构建 9 个 APK 并发布 Release |
| **Docs** | `.github/workflows/docs.yml` | push 到 main | 生成 API 文档 |

### 本地预检

提交前建议在本地跑：

```bash
# 快速编译检查（最快反馈）
./gradlew compileDebugKotlin

# 单元测试
./gradlew testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# 风险模式扫描（与 CI code-quality 一致）
grep -rn '!!' --include='*.kt' app/ sdk/ core/ lib/ | wc -l
grep -rn 'runBlocking' --include='*.kt' app/ sdk/ core/ lib/ | wc -l
```

### PR 标题

与提交规范一致：`<type>(<scope>): <subject>`

### PR 描述模板

```markdown
## 变更说明
<!-- 简述这个 PR 做了什么 -->

## 变更类型
- [ ] feat: 新功能
- [ ] fix: Bug 修复
- [ ] refactor: 重构
- [ ] docs: 文档
- [ ] test: 测试
- [ ] build: 构建

## 测试
- [ ] 单元测试通过
- [ ] 仪器化测试通过（如适用）
- [ ] 手动验证

## 影响范围
<!-- 列出受影响的模块 -->
```

## 模块结构

详见 [README.md](./README.md) 的架构部分。

关键约束：
- `:app` → 仅依赖 `:core:*`、`:engine`、`:plugins:*`、`:data:*`
- `:core:*` → 不依赖任何 Android UI 框架
- `:data:*` → 仅依赖 `:core:domain`
- `:engine` → 仅依赖 `:core:*`
- `:plugins:*` → 仅依赖 `:core:burst-kernel`

禁止的依赖方向：
- `:core` ← `:app`（core 不能依赖 app）
- `:data` ← `:engine`（data 不能依赖 engine）
- 任何模块 ← `:app`（app 是最外层）

## 测试要求

### 单元测试

- 业务逻辑必须有单元测试
- 测试文件放在 `src/test/java/` 下，与源码同包
- 命名：`<ClassName>Test.kt`
- 使用 JUnit 5 + MockK

### 仪器化测试

- 涉及 Android 框架的代码用仪器化测试
- 放在 `src/androidTest/java/` 下
- 命名：`<ClassName>AndroidTest.kt`

### 代码审计

```bash
# 扫描空壳/TODO/逻辑错误
bash scripts/code_audit/run_audit.sh

# 扫描结果在 stdout
```

新增代码不应引入新的：
- `NotImplementedError` / `TODO()` 调用
- 空 `catch` 块（除资源清理外）
- 空 `if` body（除明确注释意图外）

## 问题反馈

- **Bug 报告**：[GitHub Issues](https://github.com/mengjinghao/Apex-auto-agent/issues)
- **功能建议**：[GitHub Discussions](https://github.com/mengjinghao/Apex-auto-agent/discussions)
- **安全漏洞**：邮件 mjh4117222@gmail.com（请勿公开 Issue）

## 行为准则

请保持尊重和包容。攻击性、歧视性或不专业行为将不被容忍。
