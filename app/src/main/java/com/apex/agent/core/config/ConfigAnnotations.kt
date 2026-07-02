package com.apex.agent.core.config

/**
 * 标记一个字段为可配置项
 *
 * @param key 配置项的路径
 * @param description 配置项的描述说明
 * @param type 配置值的数据类型
 * @param defaultValue 默认值
 * @param required 是否为必填项
 * @param secret 是否为敏感信息
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Configurable(
    val key: String,
    val description: String = "",
    val type: ConfigType = ConfigType.STRING,
    val defaultValue: String = "",
    val required: Boolean = false,
    val secret: Boolean = false
)

/**
 * 配置组注解，用于对相关配置进行分组
 *
 * @param prefix 配置组前缀
 * @param description 组的描述说明
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigGroup(
    val prefix: String,
    val description: String = ""
)

/**
 * 配置校验注解，为配置项附加校验规则
 *
 * @param validator 校验器类名（全限定名）
 * @param params 校验器参数列表
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigValidation(
    val validator: String,
    val params: Array<String> = []
)

/**
 * 配置注解处理器
 *
 * 扫描被 [Configurable] 标注的字段，自动注册到 [ConfigManager]，
 * 并执行关联的校验逻辑。
 */
class ConfigAnnotationProcessor(
    private val configManager: ConfigManager
) {

    private val registeredKeys = mutableMapOf<String, ConfigKey>()

    /**
     * 注册指定对象中被 [Configurable] 和 [ConfigGroup] 注解标记的字段
     *
     * @param obj 包含注解字段的对象实例
     * @param strict 严格模式：如果路径重复则抛出异常
     */
    fun register(obj: Any, strict: Boolean = false) {
        val cls = obj::class
        val groupPrefix = cls.annotations
            .filterIsInstance<ConfigGroup>()
            .firstOrNull()?.prefix ?: ""

        for (member in cls.members) {
            val configurable = member.annotations
                .filterIsInstance<Configurable>()
                .firstOrNull() ?: continue

            val fullPath = if (groupPrefix.isNotBlank()) {
                "$groupPrefix.${configurable.key}"
            } else {
                configurable.key
            }

            val configKey = ConfigKey(
                path = fullPath,
                defaultValue = configurable.defaultValue.ifBlank { null },
                description = configurable.description,
                type = configurable.type,
                required = configurable.required,
                secret = configurable.secret
            )

            if (strict && registeredKeys.containsKey(fullPath)) {
                throw IllegalStateException("配置键路径重复: $fullPath")
            }
            registeredKeys[fullPath] = configKey
            configManager.registerKey(configKey)
        }
    }

    /**
     * 自动校验所有已注册的配置项
     *
     * @return 校验结果映射，键为路径，值为校验结果
     */
    fun autoValidate(): Map<String, ValidationResult> {
        val results = mutableMapOf<String, ValidationResult>()
        for ((path, key) in registeredKeys) {
            val currentValue = try {
                configManager.getString(key)
            } catch (e: Exception) {
                key.defaultValue
            }
            val valueToValidate = currentValue ?: key.defaultValue ?: ""
            results[path] = configManager.validate(key)
        }
        return results
    }

    /**
     * 获取所有通过注解注册的配置键
     */
    fun getRegisteredKeys(): Map<String, ConfigKey> = registeredKeys.toMap()

    /**
     * 检查指定路径是否已被注解注册
     */
    fun isRegistered(path: String): Boolean = registeredKeys.containsKey(path)
}
