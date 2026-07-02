# build-logic

此目录包含 Gradle **convention plugins**（构建约定插件），用于统一管理所有模块的通用构建配置。

## 设计理念

采用 Now in Android (NIA) 项目的最佳实践：
- 将通用的 Android/Kotlin/Compose 配置抽取为可复用的 convention plugin
- 每个模块只需 `plugins { id("apex.android.library") }` 即可应用完整配置
- 版本统一在 `gradle/libs.versions.toml` 中管理
- 避免每个模块的 `build.gradle.kts` 重复配置

## 可用的 convention plugins

| Plugin ID | 用途 |
|-----------|------|
| `apex.android.application` | Android Application 模块（产出 APK） |
| `apex.android.library` | Android Library 模块 |
| `apex.android.library.compose` | 含 Compose 的 Library 模块 |
| `apex.kotlin.jvm` | 纯 Kotlin JVM 模块 |

## 使用示例

```kotlin
// app/build.gradle.kts
plugins {
    id("apex.android.application")
    id("apex.android.application.compose")
    // ...
}

// core/burst-kernel/build.gradle.kts
plugins {
    id("apex.android.library")
    // ...
}
```
