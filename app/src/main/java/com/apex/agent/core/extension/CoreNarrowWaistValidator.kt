package com.apex.core.extension

import com.apex.util.AppLogger

/**
 * Core Narrow Waist Validator - Enforces core principles for capability expansion
 *
 * This validator ensures that:
 * 1. New capabilities match their declared footprint level
 * 2. Core-level additions are prevented when edge implementation is possible
 * 3. The "core is narrow waist" principle is maintained
 *
 * The core should remain minimal and focused, with expansion happening
 * at the outer layers of the footprint ladder.
 */
class CoreNarrowWaistValidator {

    companion object {
        private const val TAG = "CoreNarrowWaistValidator"

        /**
         * List of core tool names that represent the narrow waist
         * These are considered essential core capabilities
         */
        private val CORE_TOOLS = setOf(
            "execute_shell",
            "device_info",
            "file_operations",
            "browser_automation",
            "intent_execution",
            "notification_management",
            "workflow_execution",
            "skill_management"
        )

        /**
         * Capabilities that can be implemented via MCP Server (Level 5)
         */
        private val MCP_SUITABLE_CAPABILITIES = setOf(
            "code_generation",
            "document_processing",
            "data_analysis",
            "image_processing",
            "audio_processing",
            "video_processing",
            "natural_language_processing",
            "translation",
            "speech_synthesis",
            "speech_recognition"
        )

        /**
         * Capabilities that can be implemented via Plugin (Level 4)
         */
        private val PLUGIN_SUITABLE_CAPABILITIES = setOf(
            "custom_ui_components",
            "third_party_integrations",
            "proprietary_formats",
            "vendor_specific_features"
        )

        /**
         * Capabilities that should be implemented via Service Gate (Level 3)
         */
        private val SERVICE_GATE_SUITABLE_PATTERNS = setOf(
            "check_fn",
            "configuration_required",
            "runtime_condition"
        )
    }

    /**
     * Validate a capability declaration against core principles
     *
     * @param capability The capability to validate
     * @return ValidationResult indicating acceptance, rejection, or need for review
     */
    fun validate(capability: CapabilityDeclaration): ValidationResult {
        AppLogger.d(TAG, "Validating capability: ${capability.name} at level ${capability.level}")

        // Check 1: Verify level is appropriate for the capability type
        val levelValidation = validateLevelAppropriateness(capability)
        if (levelValidation !is ValidationResult.Accepted) {
            return levelValidation
        }

        // Check 2: Verify core tool additions are necessary
        if (capability.level == FootprintLevel.NEW_CORE_TOOL) {
            val coreCheck = validateCoreAddition(capability)
            if (coreCheck !is ValidationResult.Accepted) {
                return coreCheck
            }
        }

        // Check 3: Verify dependencies are appropriate
        val depCheck = validateDependencies(capability)
        if (depCheck !is ValidationResult.Accepted) {
            return depCheck
        }

        // Check 4: Verify check_fn is present for service-gated tools
        if (capability.level == FootprintLevel.SERVICE_GATED_TOOL && capability.checkFn == null) {
            return ValidationResult.Rejected(
                reason = "Service-gated tool must have a check_fn for conditional availability",
                suggestedLevel = FootprintLevel.CLI_COMMAND_SKILL,
                alternativeApproaches = listOf(
                    "Add a check_fn to gate tool availability based on service configuration",
                    "Consider implementing as CLI command + skill instead"
                )
            )
        }

        // Check 5: Review required for high-impact capabilities
        if (capability.level.coreImpact == FootprintLevel.CoreImpact.HIGH ||
            capability.level == FootprintLevel.NEW_CORE_TOOL) {
            return ValidationResult.NeedsReview(
                reasons = listOf(
                    "High core impact capability requires manual review",
                    "Consider if this can be implemented at a lower level",
                    "Verify this capability is not duplicating existing functionality"
                )
            )
        }

        return ValidationResult.Accepted(capability)
    }

    /**
     * Validate that the declared level is appropriate for the capability
     */
    private fun validateLevelAppropriateness(capability: CapabilityDeclaration): ValidationResult {
        val name = capability.name.lowercase()

        // Check if capability belongs to core tools and is trying to use higher level
        val isCoreTool = CORE_TOOLS.any { name.contains(it.lowercase()) }

        if (isCoreTool && capability.level.level > FootprintLevel.CLI_COMMAND_SKILL.level) {
            return ValidationResult.NeedsReview(
                reasons = listOf(
                    "Capability appears to be core functionality",
                    "Verify this is not a core tool that should be at Level 1-2"
                )
            )
        }

        // Check if capability is suitable for MCP Server level
        val isMcpSuitable = MCP_SUITABLE_CAPABILITIES.any { name.contains(it.lowercase()) }
        if (isMcpSuitable && capability.level.level < FootprintLevel.MCP_SERVER.level) {
            return ValidationResult.NeedsReview(
                reasons = listOf(
                    "This capability appears suitable for MCP Server implementation",
                    "Consider Level 5 (MCP Server) for better isolation"
                )
            )
        }

        // Check if capability is suitable for Plugin level
        val isPluginSuitable = PLUGIN_SUITABLE_CAPABILITIES.any { name.contains(it.lowercase()) }
        if (isPluginSuitable && capability.level.level < FootprintLevel.PLUGIN.level) {
            return ValidationResult.NeedsReview(
                reasons = listOf(
                    "This capability appears suitable for Plugin implementation",
                    "Consider Level 4 (Plugin) for third-party integration"
                )
            )
        }

        return ValidationResult.Accepted(capability)
    }

    /**
     * Validate that a core addition is truly necessary
     */
    private fun validateCoreAddition(capability: CapabilityDeclaration): ValidationResult {
        val name = capability.name.lowercase()

        // Check if similar capability exists in core
        val similarCoreTool = CORE_TOOLS.find { coreTool ->
            name.contains(coreTool.lowercase()) || coreTool.lowercase().contains(name)
        }

        if (similarCoreTool != null) {
            return ValidationResult.Rejected(
                reason = "Core already contains similar functionality: ${similarCoreTool}",
                suggestedLevel = FootprintLevel.EXTEND_EXISTING,
                alternativeApproaches = listOf(
                    "Extend existing ${similarCoreTool} capability",
                    "Implement as CLI command + skill (Level 2)",
                    "Consider if this belongs in a different layer"
                )
            )
        }

        // Check if this could be implemented via MCP
        val couldBeMcp = MCP_SUITABLE_CAPABILITIES.any { name.contains(it.lowercase()) }
        if (couldBeMcp) {
            return ValidationResult.Rejected(
                reason = "This capability should be implemented via MCP Server, not as core tool",
                suggestedLevel = FootprintLevel.MCP_SERVER,
                alternativeApproaches = listOf(
                    "Implement as MCP Server (Level 5) for better isolation",
                    "Use existing MCP infrastructure for this capability"
                )
            )
        }

        // Check if this could be a plugin
        val couldBePlugin = PLUGIN_SUITABLE_CAPABILITIES.any { name.contains(it.lowercase()) }
        if (couldBePlugin) {
            return ValidationResult.Rejected(
                reason = "This capability should be implemented as Plugin, not as core tool",
                suggestedLevel = FootprintLevel.PLUGIN,
                alternativeApproaches = listOf(
                    "Implement as Plugin (Level 4) for third-party integration",
                    "Use plugin architecture for this capability"
                )
            )
        }

        return ValidationResult.Accepted(capability)
    }

    /**
     * Validate capability dependencies are appropriate
     */
    private fun validateDependencies(capability: CapabilityDeclaration): ValidationResult {
        if (capability.dependencies.isEmpty()) {
            return ValidationResult.Accepted(capability)
        }

        // Check for circular dependencies
        if (hasCircularDependency(capability.name, capability.dependencies.toSet(), emptySet())) {
            return ValidationResult.Rejected(
                reason = "Circular dependency detected",
                suggestedLevel = capability.level,
                alternativeApproaches = listOf(
                    "Refactor to remove circular dependency",
                    "Consider using event-driven architecture instead"
                )
            )
        }

        // Check dependency levels are appropriate
        for (dep in capability.dependencies) {
            val depLevel = getDependencyLevel(dep)
            if (depLevel > capability.level.level) {
                return ValidationResult.Rejected(
                    reason = "Dependency ${dep} has higher footprint level than capability",
                    suggestedLevel = capability.level,
                    alternativeApproaches = listOf(
                        "Reduce dependency on high-level capabilities",
                        "Consider if this capability should be at a higher level"
                    )
                )
            }
        }

        return ValidationResult.Accepted(capability)
    }

    /**
     * Check if adding a dependency would create a circular reference
     */
    private fun hasCircularDependency(
        capabilityName: String,
        dependencies: Set<String>,
        visited: Set<String>
    ): Boolean {
        if (capabilityName in visited) {
            return true
        }

        val newVisited = visited + capabilityName

        for (dep in dependencies) {
            val registry = CapabilityRegistry.getInstance()
            val depCap = registry.getCapability(dep)

            if (depCap != null && hasCircularDependency(dep, depCap.dependencies.toSet(), newVisited)) {
                return true
            }
        }

        return false
    }

    /**
     * Get the footprint level of a dependency
     */
    private fun getDependencyLevel(dependencyName: String): Int {
        val registry = CapabilityRegistry.getInstance()
        val capability = registry.getCapability(dependencyName)
        return capability?.level?.level ?: 0
    }

    /**
     * Get alternative approaches for a rejected capability
     */
    fun getAlternativeApproaches(capability: CapabilityDeclaration): List<String> {
        val alternatives = mutableListOf<String>()

        when (capability.level) {
            FootprintLevel.NEW_CORE_TOOL -> {
                alternatives.add("Implement as MCP Server (Level 5) for better isolation")
                alternatives.add("Implement as Plugin (Level 4) for third-party integration")
                alternatives.add("Consider if this can extend existing functionality (Level 1)")
            }
            FootprintLevel.MCP_SERVER -> {
                alternatives.add("Implement as Plugin (Level 4) for simpler integration")
                alternatives.add("Consider if this can be a service-gated tool (Level 3)")
            }
            FootprintLevel.PLUGIN -> {
                alternatives.add("Consider implementing as service-gated tool (Level 3)")
                alternatives.add("Check if this can be a CLI command + skill (Level 2)")
            }
            FootprintLevel.SERVICE_GATED_TOOL -> {
                alternatives.add("Ensure check_fn is provided for conditional availability")
                alternatives.add("Consider CLI command + skill (Level 2) for simpler approach")
            }
            else -> {
                // Lower levels are generally acceptable
            }
        }

        return alternatives
    }

    /**
     * Validation result sealed class
     */
    sealed class ValidationResult {
        data class Accepted(val capability: CapabilityDeclaration) : ValidationResult()

        data class Rejected(
            val reason: String,
            val suggestedLevel: FootprintLevel?,
            val alternativeApproaches: List<String> = emptyList()
        ) : ValidationResult()

        data class NeedsReview(
            val capability: CapabilityDeclaration,
            val reasons: List<String>
        ) : ValidationResult()
    }
}
