package com.apex.agent.plugins.burst.base

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigField(
    val key: String,
    val label: String,
    val description: String = "",
    val type: ConfigType = ConfigType.STRING,
    val defaultValue: String = "",
    val section: String = "General",
    val required: Boolean = false,
    val placeholder: String = "",
    val options: Array<String> = [],
    val min: Double = Double.MIN_VALUE,
    val max: Double = Double.MAX_VALUE,
    val secret: Boolean = false
)

enum class ConfigType {
    STRING, INT, FLOAT, BOOLEAN, PASSWORD, ENUM, MULTILINE_TEXT,
    FILE_PATH, DIRECTORY_PATH, COLOR, SLIDER, LIST
}

data class ConfigSchema(
    val pluginId: String,
    val sections: List<ConfigSection>
)

data class ConfigSection(
    val name: String,
    val label: String,
    val description: String = "",
    val fields: List<ConfigFieldDef>
)

data class ConfigFieldDef(
    val key: String,
    val label: String,
    val description: String,
    val type: ConfigType,
    val defaultValue: String,
    val required: Boolean,
    val placeholder: String,
    val options: List<String>,
    val min: Double,
    val max: Double,
    val secret: Boolean
)

data class ConfigValue(
    val key: String,
    val value: String
)

data class ConfigValidation(
    val valid: Boolean,
    val errors: Map<String, String> = emptyMap()
)
