package com.apex.agent.core.patterns

/**
 * 工厂方法模式 - 统一的客户端和存储工厂
 * 通过抽象 Creator 类定义创建接口，子类决定实例化哪个具体类
 */

/** 抽象创建者 */
abstract class Creator<T> {
    private val cache = mutableMapOf<String, T>()

    /** 工厂方法 - 子类实现 */
    abstract fun factoryMethod(type: String): T

    /** 获取实例（带缓存） */
    fun create(type: String): T {
        return cache.getOrPut(type) { factoryMethod(type) }
    }

    /** 获取缓存中的所有实例 */
    fun getCachedInstances(): Map<String, T> = cache.toMap()

    /** 清空缓存 */
    fun clearCache() = cache.clear()
}

/** 模型客户端枚举 */
enum class ModelType { OPENAI, GEMINI, CLAUDE, DEEPSEEK }

/** 模型客户端 */
open class ModelClient(val modelType: ModelType, val apiKey: String = "")

class OpenAIClient : ModelClient(ModelType.OPENAI)
class GeminiClient : ModelClient(ModelType.GEMINI)
class ClaudeClient : ModelClient(ModelType.CLAUDE)
class DeepseekClient : ModelClient(ModelType.DEEPSEEK)

/** 模型客户端工厂 */
class ModelClientFactory : Creator<ModelClient>() {
    override fun factoryMethod(type: String): ModelClient {
        return when (type.uppercase()) {
            "OPENAI" -> OpenAIClient()
            "GEMINI" -> GeminiClient()
            "CLAUDE" -> ClaudeClient()
            "DEEPSEEK" -> DeepseekClient()
            else -> throw IllegalArgumentException("Unknown model type: $type")
        }
    }
}

/** 存储提供者接口 */
interface StorageProvider {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String): Boolean
}

class LocalStorageProvider : StorageProvider {
    private val store = mutableMapOf<String, String>()
    override fun read(key: String): String? = store[key]
    override fun write(key: String, value: String) { store[key] = value }
    override fun delete(key: String): Boolean = store.remove(key) != null
}

class DatabaseStorageProvider : StorageProvider {
    private val store = mutableMapOf<String, String>()
    override fun read(key: String): String? = store[key]
    override fun write(key: String, value: String) { store[key] = value }
    override fun delete(key: String): Boolean = store.remove(key) != null
}

class SupabaseStorageProvider : StorageProvider {
    private val store = mutableMapOf<String, String>()
    override fun read(key: String): String? = store[key]
    override fun write(key: String, value: String) { store[key] = value }
    override fun delete(key: String): Boolean = store.remove(key) != null
}

/** 存储提供者工厂 */
class StorageProviderFactory : Creator<StorageProvider>() {
    override fun factoryMethod(type: String): StorageProvider {
        return when (type.uppercase()) {
            "LOCAL" -> LocalStorageProvider()
            "DATABASE" -> DatabaseStorageProvider()
            "SUPABASE" -> SupabaseStorageProvider()
            else -> throw IllegalArgumentException("Unknown storage type: $type")
        }
    }
}

/** 通知类型 */
sealed class Notification {
    abstract val title: String
    abstract val message: String
}

data class InfoNotification(override val title: String, override val message: String) : Notification()
data class WarningNotification(override val title: String, override val message: String) : Notification()
data class ErrorNotification(override val title: String, override val message: String) : Notification()

/** 通知工厂 */
class NotificationFactory : Creator<Notification>() {
    override fun factoryMethod(type: String): Notification {
        return when (type.uppercase()) {
            "INFO" -> InfoNotification("Info", "General information")
            "WARNING" -> WarningNotification("Warning", "Proceed with caution")
            "ERROR" -> ErrorNotification("Error", "An error occurred")
            else -> throw IllegalArgumentException("Unknown notification type: $type")
        }
    }
}
