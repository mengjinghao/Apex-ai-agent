package com.apex.agent.core.tools.skill.config

import android.content.Context
import com.apex.agent.core.tools.skill.SkillPackage
import com.apex.agent.data.skill.config.ConfigItem
import com.apex.agent.data.skill.config.ConfigItemType
import com.apex.agent.data.skill.config.ConfigRepository
import com.apex.agent.data.skill.config.ConfigValidationResult
import com.apex.agent.data.skill.config.ConfigValidationError
import com.apex.agent.data.skill.config.SkillConfig
import com.apex.agent.data.skill.config.SkillConfigExport
import com.apex.agent.data.skill.config.SkillConfigPreset
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SkillConfigManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillConfigManager"
        private const val CONFIG_VERSION = "1.0"

        @Volatile
        private var INSTANCE: SkillConfigManager? = null

        fun getInstance(context: Context): SkillConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val repository = ConfigRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _activeSkillConfig = MutableStateFlow<SkillConfig?>(null)
    val activeSkillConfig: StateFlow<SkillConfig?> = _activeSkillConfig.asStateFlow()

    private val _configTemplates = MutableStateFlow<Map<String, List<ConfigItem>>>(emptyMap())
    val configTemplates: StateFlow<Map<String, List<ConfigItem>>> = _configTemplates.asStateFlow()

    private val _pendingChanges = MutableStateFlow<Map<String, String>>(emptyMap())
    val pendingChanges: StateFlow<Map<String, String>> = _pendingChanges.asStateFlow()

    suspend fun initialize() {
        if (_isInitialized.value) return

        try {
            repository.initialize()
            _isInitialized.value = true
            AppLogger.d(TAG, "SkillConfigManager initialized")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize SkillConfigManager", e)
        }
    }

    suspend fun getConfig(skillName: String): SkillConfig? {
        return repository.getConfig(skillName)
    }

    suspend fun getOrCreateConfig(skillPackage: SkillPackage): SkillConfig {
        return repository.getConfig(skillPackage.name) ?: run {
            repository.createDefaultConfigForSkill(skillPackage)
        }
    }

    fun setActiveConfig(config: SkillConfig) {
        _activeSkillConfig.value = config
    }

    suspend fun saveConfig(config: SkillConfig): Boolean {
        val result = repository.saveConfig(config)
        if (result && _activeSkillConfig.value?.skillName == config.skillName) {
            _activeSkillConfig.value = config
        }
        return result
    }

    suspend fun deleteConfig(skillName: String): Boolean {
        return repository.deleteConfig(skillName)
    }

    suspend fun updateConfigItem(skillName: String, key: String, value: String): Boolean {
        return repository.updateConfigItem(skillName, key, value)
    }

    suspend fun addConfigItem(skillName: String, item: ConfigItem): Boolean {
        return repository.addConfigItem(skillName, item)
    }

    suspend fun removeConfigItem(skillName: String, key: String): Boolean {
        return repository.removeConfigItem(skillName, key)
    }

    suspend fun resetToDefaults(skillName: String): Boolean {
        return repository.resetConfigToDefaults(skillName)
    }

    fun validateConfigItem(item: ConfigItem, value: String): ConfigValidationResult {
        if (item.required && value.isBlank()) {
            return ConfigValidationResult.INVALID_REQUIRED
        }

        if (value.isBlank()) {
            return ConfigValidationResult.VALID
        }

        when (item.type) {
            ConfigItemType.INTEGER -> {
                if (value.toIntOrNull() == null) {
                    return ConfigValidationResult.INVALID_FORMAT
                }
                item.validation?.let { validation ->
                    val intValue = value.toInt()
                    validation.min?.let { if (intValue < it.toInt()) return ConfigValidationResult.INVALID_RANGE }
                    validation.max?.let { if (intValue > it.toInt()) return ConfigValidationResult.INVALID_RANGE }
                }
            }

            ConfigItemType.FLOAT -> {
                if (value.toDoubleOrNull() == null) {
                    return ConfigValidationResult.INVALID_FORMAT
                }
                item.validation?.let { validation ->
                    val doubleValue = value.toDouble()
                    validation.min?.let { if (doubleValue < it) return ConfigValidationResult.INVALID_RANGE }
                    validation.max?.let { if (doubleValue > it) return ConfigValidationResult.INVALID_RANGE }
                }
            }

            ConfigItemType.BOOLEAN -> {
                if (value.toBooleanStrictOrNull() == null) {
                    return ConfigValidationResult.INVALID_FORMAT
                }
            }

            ConfigItemType.SELECT -> {
                if (item.options.isNotEmpty() && !item.options.contains(value)) {
                    return ConfigValidationResult.INVALID_FORMAT
                }
            }

            ConfigItemType.MULTI_SELECT -> {
                val values = value.split(",").map { it.trim() }
                if (item.options.isNotEmpty()) {
                    val invalidValues = values.filterNot { item.options.contains(it) }
                    if (invalidValues.isNotEmpty()) {
                        return ConfigValidationResult.INVALID_FORMAT
                    }
                }
            }

            ConfigItemType.JSON -> {
                if (!isValidJson(value)) {
                    return ConfigValidationResult.INVALID_FORMAT
                }
            }

            ConfigItemType.SECRET, ConfigItemType.STRING, ConfigItemType.FILE_PATH -> {
                item.validation?.let { validation ->
                    if (validation.minLength != null && value.length < validation.minLength) {
                        return ConfigValidationResult.INVALID_RANGE
                    }
                    if (validation.maxLength != null && value.length > validation.maxLength) {
                        return ConfigValidationResult.INVALID_RANGE
                    }
                    if (validation.pattern != null) {
                        val pattern = Regex(validation.pattern)
                        if (!pattern.matches(value)) {
                            return ConfigValidationResult.INVALID_PATTERN
                        }
                    }
                }
            }
        }

        return ConfigValidationResult.VALID
    }

    fun validateConfig(config: SkillConfig): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()

        for (item in config.items) {
            val result = validateConfigItem(item, item.value)
            if (result != ConfigValidationResult.VALID) {
                val message = when (result) {
                    ConfigValidationResult.INVALID_REQUIRED -> "此字段为必填?
                    ConfigValidationResult.INVALID_FORMAT -> "格式不正?
                    ConfigValidationResult.INVALID_RANGE -> "值超出允许范?
                    ConfigValidationResult.INVALID_PATTERN -> item.validation?.patternMessage ?: "不符合要求的格式"
                    else -> "验证失败"
                }
                errors.add(ConfigValidationError(item.key, message, result))
            }
        }

        return errors
    }

    private fun isValidJson(value: String): Boolean {
        return try {
            val json = kotlinx.serialization.json.Json
            json.Json.parseToJsonElement(value)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun exportConfigs(): File? {
        return repository.exportAllConfigs()
    }

    suspend fun importConfigs(file: File): Pair<Int, Int>? {
        val result = repository.importConfigs(file)
        if (result != null) {
            scope.launch {
                repository.loadAllConfigs()
                repository.loadAllPresets()
            }
        }
        return result
    }

    suspend fun savePreset(preset: SkillConfigPreset): Boolean {
        return repository.savePreset(preset)
    }

    suspend fun loadPreset(presetId: String): SkillConfigPreset? {
        return repository.loadPreset(presetId)
    }

    suspend fun deletePreset(presetId: String): Boolean {
        return repository.deletePreset(presetId)
    }

    suspend fun getPresetsForSkill(skillName: String): List<SkillConfigPreset> {
        return repository.getPresetsForSkill(skillName)
    }

    suspend fun applyPreset(presetId: String): Boolean {
        return repository.applyPreset(presetId)
    }

    suspend fun createPresetFromCurrentConfigs(presetName: String, description: String, skillName: String): SkillConfigPreset? {
        return repository.createPresetFromCurrentConfigs(presetName, description, skillName)
    }

    fun addPendingChange(key: String, value: String) {
        _pendingChanges.value = _pendingChanges.value.toMutableMap().apply {
            put(key, value)
        }
    }

    fun clearPendingChanges() {
        _pendingChanges.value = emptyMap()
    }

    fun getPendingChanges(): Map<String, String> {
        return _pendingChanges.value
    }

    suspend fun applyPendingChanges(skillName: String): Boolean {
        val changes = getPendingChanges()
        if (changes.isEmpty()) return true

        for ((key, value) in changes) {
            val result = updateConfigItem(skillName, key, value)
            if (!result) {
                AppLogger.e(TAG, "Failed to apply pending change: ${key} = ${value}")
                return false
            }
        }

        clearPendingChanges()
        return true
    }

    fun registerConfigTemplate(skillType: String, items: List<ConfigItem>) {
        _configTemplates.value = _configTemplates.value.toMutableMap().apply {
            put(skillType, items)
        }
    }

    fun getConfigTemplate(skillType: String): List<ConfigItem>? {
        return _configTemplates.value[skillType]
    }

    suspend fun createConfigFromTemplate(skillName: String, templateItems: List<ConfigItem>): SkillConfig {
        val config = SkillConfig(
            skillName = skillName,
            items = templateItems.map { it.copy(value = it.defaultValue) }
        )
        repository.saveConfig(config)
        return config
    }

    fun hasConfig(skillName: String): Boolean {
        return repository.hasConfig(skillName)
    }

    fun getConfigChangeHistory(): List<com.apex.agent.data.skill.config.ConfigChange> {
        return repository.getRecentChanges()
    }

    fun getConfigChangesForSkill(skillName: String): List<com.apex.agent.data.skill.config.ConfigChange> {
        return repository.getChangesForSkill(skillName)
    }
}
