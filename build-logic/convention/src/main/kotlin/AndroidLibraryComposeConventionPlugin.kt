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
                val libs = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
                    .named("libs")

                add("implementation", platform(libs.findLibrary("androidx-compose-bom").get()))
                add("implementation", libs.findLibrary("androidx-compose-ui").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("implementation", libs.findLibrary("androidx-compose-material3").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            }
        }
    }
}
