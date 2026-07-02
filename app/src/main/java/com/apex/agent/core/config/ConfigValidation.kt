package com.apex.agent.core.config

import java.net.URL

/**
 * 配置校验结果密封类
 */
sealed class ValidationResult {
    /** 校验通过 */
    data object Valid : ValidationResult()

    /** 校验不通过，包含错误列表 */
    data class Invalid(val errors: List<String>) : ValidationResult() {
        constructor(error: String) : this(listOf(error))
    }

    /** 校验通过但有警告，包含警告列表 */
    data class Warning(val messages: List<String>) : ValidationResult() {
        constructor(message: String) : this(listOf(message))
    }

    val isValid: Boolean get() = this is Valid || this is Warning
    val isInvalid: Boolean get() = this is Invalid
}

/**
 * 配置校验器接口，用于校验配置值的合法性
 */
fun interface ConfigValidator {
    fun validate(key: ConfigKey, value: String): ValidationResult
}

/**
 * 必填项校验器 - 验证值不能为空
 */
object RequiredValidator : ConfigValidator {
    override fun validate(key: ConfigKey, value: String): ValidationResult {
        return if (value.isBlank()) {
            ValidationResult.Invalid("配置项 [${key.path}] 为必填项，但值为空")
        } else {
            ValidationResult.Valid
        }
    }
}

/**
 * 数值范围校验器 - 验证数值在指定范围内
 */
class RangeValidator<T : Comparable<T>>(
    private val min: T? = null,
    private val max: T? = null,
    private val parser: (String) -> T?,
    private val typeName: String = "数值"
) : ConfigValidator {
    override fun validate(key: ConfigKey, value: String): ValidationResult {
        val parsed = parser(value)
        if (parsed == null) {
            return ValidationResult.Invalid("配置项 [${key.path}] 的值 \"$value\" 不是有效的 $typeName")
        }
        val errors = mutableListOf<String>()
        if (min != null && parsed < min) {
            errors.add("配置项 [${key.path}] 的值 $parsed 小于最小值 $min")
        }
        if (max != null && parsed > max) {
            errors.add("配置项 [${key.path}] 的值 $parsed 大于最大值 $max")
        }
        return if (errors.isEmpty()) ValidationResult.Valid
        else ValidationResult.Invalid(errors)
    }
}

/**
 * 正则表达式模式校验器
 */
class PatternValidator(
    private val pattern: Regex,
    private val message: String? = null
) : ConfigValidator {
    constructor(pattern: String, message: String? = null) : this(pattern.toRegex(), message)

    override fun validate(key: ConfigKey, value: String): ValidationResult {
        return if (pattern.matches(value)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                message ?: "配置项 [${key.path}] 的值 \"$value\" 不匹配模式 ${pattern.pattern}"
            )
        }
    }
}

/**
 * 枚举值校验器 - 验证值必须在给定的列表中
 */
class EnumValidator(
    private val allowedValues: Collection<String>,
    private val caseSensitive: Boolean = true
) : ConfigValidator {
    override fun validate(key: ConfigKey, value: String): ValidationResult {
        val match = if (caseSensitive) {
            allowedValues.contains(value)
        } else {
            allowedValues.any { it.equals(value, ignoreCase = true) }
        }
        return if (match) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "配置项 [${key.path}] 的值 \"$value\" 不在允许值列表中: ${allowedValues.joinToString(", ")}"
            )
        }
    }
}

/**
 * URL 格式校验器
 */
object UrlValidator : ConfigValidator {
    override fun validate(key: ConfigKey, value: String): ValidationResult {
        return try {
            URL(value).toURI()
            ValidationResult.Valid
        } catch (e: Exception) {
            ValidationResult.Invalid("配置项 [${key.path}] 的值 \"$value\" 不是有效的 URL 格式")
        }
    }
}

/**
 * 电子邮箱格式校验器
 */
object EmailValidator : ConfigValidator {
    private val emailRegex = Regex(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    )

    override fun validate(key: ConfigKey, value: String): ValidationResult {
        return if (emailRegex.matches(value)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("配置项 [${key.path}] 的值 \"$value\" 不是有效的邮箱格式")
        }
    }
}

/**
 * 端口号校验器 - 验证端口在 0-65535 范围内
 */
object PortValidator : ConfigValidator {
    override fun validate(key: ConfigKey, value: String): ValidationResult {
        val port = value.toIntOrNull()
        return when {
            port == null -> ValidationResult.Invalid("配置项 [${key.path}] 的值 \"$value\" 不是有效的数字")
            port < 0 || port > 65535 -> ValidationResult.Invalid("配置项 [${key.path}] 的值 $port 超出端口范围 (0-65535)")
            else -> ValidationResult.Valid
        }
    }
}

/**
 * 持续时间格式校验器 - 验证格式如 "30s", "5m", "2h", "1d"
 */
object DurationValidator : ConfigValidator {
    private val durationRegex = Regex("^(\\d+)(ns|us|ms|s|m|h|d)$")

    override fun validate(key: ConfigKey, value: String): ValidationResult {
        return if (durationRegex.matches(value)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "配置项 [${key.path}] 的值 \"$value\" 不是有效的持续时间格式 (如 30s, 5m, 2h, 1d)"
            )
        }
    }
}

/**
 * 字节大小格式校验器 - 验证格式如 "10MB", "1GB", "512KB"
 */
object SizeValidator : ConfigValidator {
    private val sizeRegex = Regex("^(\\d+)(B|KB|MB|GB|TB)$", RegexOption.IGNORE_CASE)

    override fun validate(key: ConfigKey, value: String): ValidationResult {
        return if (sizeRegex.matches(value)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "配置项 [${key.path}] 的值 \"$value\" 不是有效的字节大小格式 (如 10MB, 1GB, 512KB)"
            )
        }
    }
}

/**
 * 组合校验器 - 支持 AND/OR 逻辑组合多个校验器
 */
class CompositeValidator(
    private val validators: List<ConfigValidator>,
    private val mode: CombineMode = CombineMode.AND
) : ConfigValidator {
    enum class CombineMode { AND, OR }

    override fun validate(key: ConfigKey, value: String): ValidationResult {
        val results = validators.map { it.validate(key, value) }
        return when (mode) {
            CombineMode.AND -> {
                val errors = results.filterIsInstance<ValidationResult.Invalid>()
                    .flatMap { it.errors }
                if (errors.isEmpty()) ValidationResult.Valid
                else ValidationResult.Invalid(errors)
            }
            CombineMode.OR -> {
                if (results.any { it.isValid }) ValidationResult.Valid
                else {
                    val errors = results.filterIsInstance<ValidationResult.Invalid>()
                        .flatMap { it.errors }
                    ValidationResult.Invalid(errors)
                }
            }
        }
    }
}

/**
 * 校验引擎 - 为给定的配置键和值运行所有适用的校验器
 */
class ValidationEngine(
    private vararg val validators: ConfigValidator
) {
    constructor(validators: List<ConfigValidator>) : this(*validators.toTypedArray())

    /**
     * 对单个配置项执行所有校验
     */
    fun validate(key: ConfigKey, value: String): ValidationResult {
        if (key.required && value.isBlank()) {
            return RequiredValidator.validate(key, value)
        }
        val results = mutableListOf<ValidationResult>()
        if (key.required) {
            results.add(RequiredValidator.validate(key, value))
        }
        key.validator?.let { custom ->
            val passed = try {
                custom.invoke(value)
            } catch (e: Exception) {
                false
            }
            if (!passed) {
                results.add(ValidationResult.Invalid("配置项 [${key.path}] 自定义校验失败"))
            }
        }
        for (validator in validators) {
            results.add(validator.validate(key, value))
        }
        val errors = results.filterIsInstance<ValidationResult.Invalid>().flatMap { it.errors }
        val warnings = results.filterIsInstance<ValidationResult.Warning>().flatMap { it.messages }
        return when {
            errors.isNotEmpty() -> ValidationResult.Invalid(errors)
            warnings.isNotEmpty() -> ValidationResult.Warning(warnings)
            else -> ValidationResult.Valid
        }
    }

    /**
     * 批量校验多个配置项
     */
    fun validateAll(configs: Map<ConfigKey, String>): Map<String, ValidationResult> {
        val results = mutableMapOf<String, ValidationResult>()
        for ((key, value) in configs) {
            results[key.path] = validate(key, value)
        }
        return results
    }

    /**
     * 获取所有校验失败的结果
     */
    fun getErrors(results: Map<String, ValidationResult>): Map<String, List<String>> {
        return results.filterValues { it is ValidationResult.Invalid }
            .mapValues { (it.value as ValidationResult.Invalid).errors }
    }
}
