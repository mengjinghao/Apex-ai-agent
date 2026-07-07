import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * 纯 Kotlin JVM 模块的 convention plugin。
 *
 * 适用于不含 Android 依赖的纯 Kotlin 模块（如 code-analyzer、code-generator）。
 *
 * 使用：在 <module>/build.gradle.kts 中
 *   plugins { id("apex.kotlin.jvm") }
 */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
