package com.apex.agent.core.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonParser
import kotlinx.serialization.modules.SerializersModule

/**
 * 缓存序列化工具，基于 kotlinx.serialization 实现缓存的 JSON 序列化与反序列化。
 *
 * 提供：
 * - [CacheEntry] 与 JSON 字符串之间的双向转换
 * - 类型适配器注册机制，支持自定义类型的序列化
 * - 向后兼容的序列化格式（添加 version 字段）
 */
object CacheSerialization {

    /** 序列化格式版本，用于向后兼容检测 */
    private const val FORMAT_VERSION = 1

    /** 全局 JSON 实例，配置忽略未知键以支持向后兼容 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
        serializersModule = SerializersModule {
            contextual(String::class, StringSerializer)
        }
    }

    /**
     * 将缓存条目序列化为 JSON 字符串。
     * 内部添加 version 字段确保格式可演进。
     */
    inline fun <reified T> serialize(entry: CacheEntry<T>): String {
        val wrapper = CacheEntryWrapper(
            version = FORMAT_VERSION,
            key = entry.key,
            value = json.encodeToString(entry.value),
            createdAt = entry.createdAt,
            lastAccessedAt = entry.lastAccessedAt,
            ttl = entry.ttl,
            hitCount = entry.hitCount,
            sizeBytes = entry.sizeBytes,
            serialized = true
        )
        return json.encodeToString(wrapper)
    }

    /**
     * 将 JSON 字符串反序列化为缓存条目。
     * 使用 [deserializer] 将 JSON 字符串转换为目标类型 [T]。
     */
    inline fun <reified T> deserialize(
        jsonString: String,
        crossinline deserializer: (String) -> T
    ): CacheEntry<T> {
        val wrapper = json.decodeFromString<CacheEntryWrapper>(jsonString)
        val value = deserializer(wrapper.value)
        return CacheEntry(
            key = wrapper.key,
            value = value,
            createdAt = wrapper.createdAt,
            lastAccessedAt = wrapper.lastAccessedAt,
            ttl = wrapper.ttl,
            hitCount = wrapper.hitCount,
            sizeBytes = wrapper.sizeBytes,
            serialized = wrapper.serialized
        )
    }

    /**
     * 序列化包装类，在原始 [CacheEntry] 基础上添加 format version 字段。
     */
    @Serializable
    private data class CacheEntryWrapper(
        val version: Int = FORMAT_VERSION,
        val key: String,
        val value: String,
        val createdAt: Long,
        val lastAccessedAt: Long,
        val ttl: Long = -1L,
        val hitCount: Long = 0L,
        val sizeBytes: Long = -1L,
        val serialized: Boolean = false
    )
}

/**
 * 默认的 String 类型序列化器，将字符串原样序列化/反序列化。
 */
private object StringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CacheString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return decoder.decodeString()
    }
}
