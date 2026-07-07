import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Android Library + Compose 模块的 convention plugin。
 *
 * 在 [AndroidLibraryConventionPlugin] 基础上额外启用：
 * - Compose build feature
 * - Compose Compiler 插件
 * - Compose BOM 依赖管理
 * - 基础 Compose 依赖（ui, material3, foundation）
 *
 * 注意：本项目已删除所有 UI 文件，此插件保留供未来恢复 UI 时使用。
 *
 * 使用：在 <module>/build.gradle.kts 中
 *   plugins {
 *       id("apex.android.library")
 *       id("apex.android.library.compose")
 *   }
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("apex.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }
            }

            dependencies {
                add("implementation", platform("androidx.compose:compose-bom:2024.06.00"))
                add("implementation", "androidx.compose.ui:ui")
                add("implementation", "androidx.compose.ui:ui-graphics")
                add("implementation", "androidx.compose.ui:ui-tooling-preview")
                add("implementation", "androidx.compose.material3:material3")
                add("debugImplementation", "androidx.compose.ui:ui-tooling")
            }
        }
    }
}
