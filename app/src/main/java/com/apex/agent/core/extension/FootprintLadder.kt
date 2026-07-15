package com.apex.agent.core.extension

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import com.apex.core.extension.ValidationResult

enum class FootprintLevel(val level: Int, val description: String, val surfaceArea: String) {
    EXTEND_EXISTING(1, "扩展现有代码", "零新增表面积"),
    CLI_COMMAND_SKILL(2, "CLI 命令 + Skill", "零模型工具足�?),
    SERVICE_GATED_TOOL(3, "Service-gated Tool", "仅配置后出现"),
    PLUGIN(4, "Plugin", "第三方能�?),
    MCP_SERVER(5, "MCP Server", "工具化非核心能力"),
    NEW_CORE_TOOL(6, "New Core Tool", "最后手�?);
        fun isLowerThan(other: FootprintLevel): Boolean {
        return this.level < other.level
    }
        fun isHigherThan(other: FootprintLevel): Boolean {
        return this.level > other.level
    }
        fun canPromoteTo(target: FootprintLevel): Boolean {
        return target.level > this.level
    }

    companion object {
        fun fromLevel(level: Int): FootprintLevel? {
            return values().firstOrNull { it.level == level }
        }
        fun fromDescription(description: String): FootprintLevel? {
            return values().firstOrNull { it.description == description }
        }
    }
}

data class Capability(
    val name: String,
    val level: FootprintLevel,
    val description: String = "",
    val dependencies: List<String> = emptyList(),
    val requiresConfig: Boolean = false,
    val checkFn: (() -> Boolean)? = null,
    val implementation: Any? = null
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (name.isBlank()) {
            errors.add("Capability name cannot be blank")
        }
        if (level == FootprintLevel.NEW_CORE_TOOL && checkFn == null) {
            errors.add("Core tools must have a check function")
        }
        return errors
    }
}

class CapabilityRegistry private constructor() {

    private val logger = LoggerFactory.getLogger(CapabilityRegistry::class.java)
        private val capabilities = ConcurrentHashMap<String, Capability>()
        private val capabilityListeners = mutableListOf<CapabilityChangeListener>()
        fun addListener(listener: CapabilityChangeListener) {
        capabilityListeners.add(listener)
    }
        fun removeListener(listener: CapabilityChangeListener) {
        capabilityListeners.remove(listener)
    }

    interface CapabilityChangeListener {
        fun onCapabilityRegistered(capability: Capability)
        fun onCapabilityUnregistered(capability: Capability)
        fun onCapabilityUpdated(capability: Capability)
    }
        private fun notifyListeners(capability: Capability, changeType: ChangeType) {
        capabilityListeners.forEach { listener ->
            try {
                when (changeType) {
                    ChangeType.REGISTERED -> listener.onCapabilityRegistered(capability)
                    ChangeType.UNREGISTERED -> listener.onCapabilityUnregistered(capability)
                    ChangeType.UPDATED -> listener.onCapabilityUpdated(capability)
                }
            } catch (e: Exception) {
                logger.warn("Error notifying capability listener", e)
            }
        }
    }
        private enum class ChangeType {
        REGISTERED,
        UNREGISTERED,
        UPDATED
    }
        fun register(capability: Capability): Boolean {
        val validationErrors = capability.validate()
        if (validationErrors.isNotEmpty()) {
            logger.warn("Capability validation failed for ${capability.name}: ${validationErrors.joinToString(", ")}")
        return false
        }
        val validator = CoreNarrowWaistValidator()
        val validationResult = validator.validate(capability)
        if (!validationResult.isValid) {
            logger.warn("Core narrow waist validation failed for ${capability.name}: ${validationResult.reason}")
        return false
        }
        val isUpdate = capabilities.containsKey(capability.name)
        capabilities[capability.name] = capability
        logger.info("Registered capability: ${capability.name} at level ${capability.level.description}")
        notifyListeners(capability, if (isUpdate) ChangeType.UPDATED else ChangeType.REGISTERED)
        return true
    }
        fun unregister(name: String): Boolean {
        val removed = capabilities.remove(name)
        if (removed != null) {
            notifyListeners(removed, ChangeType.UNREGISTERED)
        }
        return removed != null
    }
        fun getCapability(name: String): Capability? {
        return capabilities[name]
    }
        fun getCapabilitiesByLevel(level: FootprintLevel): List<Capability> {
        return capabilities.values.filter { it.level == level }
    }
        fun getAllCapabilities(): List<Capability> {
        return capabilities.values.toList()
    }
        fun validate(capability: Capability): ValidationResult {
        return CoreNarrowWaistValidator().validate(capability)
    }
        fun getStatistics(): Statistics {
        val byLevel = FootprintLevel.values().associateWith { level ->
            capabilities.values.count { it.level == level }
        }
        return Statistics(
            total = capabilities.size,
            byLevel = byLevel,
            requiresConfigCount = capabilities.values.count { it.requiresConfig }
        )
    }
        fun registerBuiltinCapabilities() {
        register(Capability(
            name = "system.info",
            level = FootprintLevel.EXTEND_EXISTING,
            description = "系统信息查询"
        ))

        register(Capability(
            name = "file.read",
            level = FootprintLevel.SERVICE_GATED_TOOL,
            description = "文件读取",
            requiresConfig = true
        ))

        register(Capability(
            name = "file.write",
            level = FootprintLevel.SERVICE_GATED_TOOL,
            description = "文件写入",
            requiresConfig = true
        ))

        register(Capability(
            name = "shell.exec",
            level = FootprintLevel.SERVICE_GATED_TOOL,
            description = "Shell 命令执行",
            requiresConfig = true
        ))

        register(Capability(
            name = "web.search",
            level = FootprintLevel.PLUGIN,
            description = "网络搜索"
        ))

        register(Capability(
            name = "code.generate",
            level = FootprintLevel.PLUGIN,
            description = "代码生成"
        ))
    }

    data class Statistics(
        val total: Int,
        val byLevel: Map<FootprintLevel, Int>,
        val requiresConfigCount: Int
    )

    companion object {
        @Volatile
        private var instance: CapabilityRegistry? = null

        fun getInstance(): CapabilityRegistry {
            return instance ?: synchronized(this) {
                instance ?: CapabilityRegistry().also { instance = it }
            }
        }
    }
}

class CoreNarrowWaistValidator {

    private val logger = LoggerFactory.getLogger(CoreNarrowWaistValidator::class.java)
        fun validate(capability: Capability): ValidationResult {
        if (capability.level == FootprintLevel.NEW_CORE_TOOL) {
            return validateCoreTool(capability)
        }
        if (capability.level.isLowerThan(FootprintLevel.PLUGIN)) {
            return validateNonPlugin(capability)
        }
        return ValidationResult(true)
    }
        private fun validateCoreTool(capability: Capability): ValidationResult {
        val alternatives = findAlternativeImplementations(capability)
        if (alternatives.isNotEmpty()) {
            return ValidationResult(
                isValid = false,
                reason = "Core tool ${capability.name} has alternative implementations at lower levels: ${alternatives.joinToString()}",
                alternatives = alternatives
            )
        }
        if (!isFundamentalCapability(capability)) {
            return ValidationResult(
                isValid = false,
                reason = "Core tool ${capability.name} is not a fundamental capability that requires core-level implementation"
            )
        }
        return ValidationResult(true)
    }
        private fun validateNonPlugin(capability: Capability): ValidationResult {
        if (capability.level == FootprintLevel.EXTEND_EXISTING) {
            return ValidationResult(true)
        }
        if (capability.requiresConfig && capability.checkFn == null) {
            return ValidationResult(
                isValid = false,
                reason = "Service-gated tool ${capability.name} requires config but has no check function"
            )
        }
        return ValidationResult(true)
    }
        private fun findAlternativeImplementations(capability: Capability): List<String> {
        val alternatives = mutableListOf<String>()
        if (canBePlugin(capability)) {
            alternatives.add("Plugin")
        }
        if (canBeServiceGated(capability)) {
            alternatives.add("Service-gated tool")
        }
        if (canBeCLI(capability)) {
            alternatives.add("CLI command")
        }
        return alternatives
    }
        private fun canBePlugin(capability: Capability): Boolean {
        return !capability.name.startsWith("core.")
    }
        private fun canBeServiceGated(capability: Capability): Boolean {
        return capability.requiresConfig
    }
        private fun canBeCLI(capability: Capability): Boolean {
        return capability.description.contains("查询") || 
               capability.description.contains("配置") ||
               capability.description.contains("状�?)
    }
        private fun isFundamentalCapability(capability: Capability): Boolean {
        val fundamentalNames = setOf(
            "core.message",
            "core.tool_call",
            "core.context",
            "core.memory",
            "core.storage"
        )
        return fundamentalNames.contains(capability.name)
    }

    data class ValidationResult(
        val isValid: Boolean,
        val reason: String = "",
        val alternatives: List<String> = emptyList()
    )
}

fun createCapability(
    name: String,
    level: FootprintLevel,
    description: String = "",
    requiresConfig: Boolean = false,
    checkFn: (() -> Boolean)? = null
): Capability {
    return Capability(
        name = name,
        level = level,
        description = description,
        requiresConfig = requiresConfig,
        checkFn = checkFn
    )
}

fun getCapabilityRegistry(): CapabilityRegistry {
    return CapabilityRegistry.getInstance()
}
