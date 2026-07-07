import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.GradleException

/**
 * 模块归属校验插件 — 编译时检查 lib:* 模块只被授权的 APK 引用。
 *
 * **解决的问题**：
 *   `:lib:working-files` 应该只打包进 `:apk:working-files`，
 *   但 Gradle 不会阻止其他模块（如 `:app`）误加 `implementation(project(":lib:working-files"))`。
 *   本插件在 preBuild 阶段检查依赖图，发现违规即让构建失败。
 *
 * **归属规则**（见 docs/architecture/MODULE_OWNERSHIP.md）：
 *   :lib:multi-agent   → 只允许 :apk:multi-agent 引用
 *   :lib:workflow      → 只允许 :apk:workflow 引用
 *   :lib:working-files → 只允许 :apk:working-files 引用
 *   :sdk:*             → 所有 APK 都可引用（共享 SDK）
 *   :core:* / :engine / :plugins:* / :ai-terminal / :domain / :database / :background / :file
 *                     → 按需引用（不限制）
 *
 * **使用方式**：
 *   build.gradle.kts 顶部：
 *     plugins { id("apex.module.ownership") }
 *
 *   或在 settings.gradle.kts 中对所有 :app 和 :apk:* 模块自动应用。
 */
class ModuleOwnershipPlugin : Plugin<Project> {

    /** lib 模块 → 允许引用它的 APK/模块 白名单。 */
    private val ownershipRules: Map<String, Set<String>> = mapOf(
        ":lib:multi-agent" to setOf(":apk:multi-agent"),
        ":lib:workflow" to setOf(":apk:workflow"),
        ":lib:working-files" to setOf(":apk:working-files"),
        ":lib:engine" to setOf(":apk:engine"),
        ":lib:rage" to setOf(":apk:rage"),
        ":lib:market" to setOf(":apk:market"),
        ":lib:terminal" to setOf(":apk:terminal"),
        ":lib:voice" to setOf(":apk:voice")
    )

    override fun apply(target: Project) {
        // 只在 :app 和 :apk:* 模块上生效
        val path = target.path
        val isAppModule = path == ":app"
        val isApkModule = path.startsWith(":apk:")
        if (!isAppModule && !isApkModule) {
            return
        }

        // 注册检查任务，在 preBuild 之前执行
        val checkTask = target.tasks.register("checkModuleOwnership") {
            doLast {
                val violations = mutableListOf<String>()

                // 遍历所有 configurations 的项目依赖
                target.configurations.configureEach {
                    // 只检查可以解析的 configuration
                    if (!isCanBeResolved) return@configureEach

                    dependencies.forEach { dep ->
                        if (dep is ProjectDependency) {
                            val depPath = dep.dependencyProject.path
                            val allowedOwners = ownershipRules[depPath]
                            if (allowedOwners != null && path !in allowedOwners) {
                                violations.add(
                                    "  ❌ $path 通过 ${this.name} 引用了 $depPath\n" +
                                    "     但 $depPath 只允许以下模块引用：${allowedOwners.joinToString(", ")}\n" +
                                    "     请改用 ApexClient 跨 APK 调用，或检查 docs/architecture/MODULE_OWNERSHIP.md"
                                )
                            }
                        }
                    }
                }

                if (violations.isNotEmpty()) {
                    val msg = buildString {
                        appendLine("========================================")
                        appendLine("❌ 模块归属校验失败")
                        appendLine("========================================")
                        appendLine("以下依赖违反了模块归属规则：")
                        appendLine()
                        violations.forEach { appendLine(it); appendLine() }
                        appendLine("如需在主 APK 中使用这些功能，请通过 ApexClient 跨 APK 调用：")
                        appendLine("  - 多 Agent：ApexClient.multiAgent.*")
                        appendLine("  - 工作流：ApexClient.workflow.*")
                        appendLine("  - 工作文件：ApexClient.workingFiles.*")
                        appendLine("  - 引擎：ApexClient.engine.*")
                        appendLine("  - 狂暴模式：ApexClient.rage.*")
                        appendLine("  - 市场：ApexClient.market.*")
                        appendLine("  - 终端：ApexClient.terminal.*")
                        appendLine("  - 语音：ApexClient.voice.*")
                        appendLine("========================================")
                    }
                    throw GradleException(msg)
                }
            }
        }

        // 让 preBuild 依赖检查任务
        target.tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(checkTask)
        }
    }
}
