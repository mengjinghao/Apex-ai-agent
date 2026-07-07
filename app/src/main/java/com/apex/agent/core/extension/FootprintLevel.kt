package com.apex.core.extension

/**
 * Footprint Ladder - 6-level extension hierarchy for Apex/Agent
 *
 * This enum defines the expansion levels for capabilities, ensuring
 * "core is narrow waist" principle is maintained.
 *
 * Level 1: Extend existing code (零新增表面积�? * Level 2: CLI command + skill (零模型工具足迹）
 * Level 3: Service-gated tool (check_fn, 仅配置后出现�? * Level 4: Plugin (第三方能力）
 * Level 5: MCP Server (工具化非核心能力�? * Level 6: New Core Tool (最后手段）
 */
enum class FootprintLevel(
    val level: Int,
    val description: String,
    val coreImpact: CoreImpact
) {
    /**
     * Level 1: Extend existing code
     * - Zero new surface area added to core
     * - Pure extension of existing capabilities
     * - No new tools, commands, or interfaces exposed
     */
    EXTEND_EXISTING(
        level = 1,
        description = "Extend existing code (零新增表面积�?,
        coreImpact = CoreImpact.NONE
    ),

    /**
     * Level 2: CLI command + skill
     * - Zero model tool footprint
     * - Adds command-line interface without increasing tool count
     * - Skill-based implementation
     */
    CLI_COMMAND_SKILL(
        level = 2,
        description = "CLI command + skill (零模型工具足迹）",
        coreImpact = CoreImpact.NONE
    ),

    /**
     * Level 3: Service-gated tool
     * - Tool appears only after configuration check_fn passes
     * - Conditional availability based on service configuration
     * - Gatekeeper pattern implementation
     */
    SERVICE_GATED_TOOL(
        level = 3,
        description = "Service-gated tool (check_fn, 仅配置后出现�?,
        coreImpact = CoreImpact.LOW
    ),

    /**
     * Level 4: Plugin
     * - Third-party capability integration
     * - Plugin architecture for external features
     * - Sandboxed execution environment
     */
    PLUGIN(
        level = 4,
        description = "Plugin (第三方能力）",
        coreImpact = CoreImpact.LOW
    ),

    /**
     * Level 5: MCP Server
     * - Tooling non-core capabilities via MCP protocol
     * - External server integration
     * - Standardized tool interface
     */
    MCP_SERVER(
        level = 5,
        description = "MCP Server (工具化非核心能力�?,
        coreImpact = CoreImpact.MEDIUM
    ),

    /**
     * Level 6: New Core Tool
     * - Last resort for adding capabilities
     * - Direct core implementation required
     * - Highest review threshold before acceptance
     */
    NEW_CORE_TOOL(
        level = 6,
        description = "New Core Tool (最后手段）",
        coreImpact = CoreImpact.HIGH
    );

    /**
     * Core impact levels for footprint assessment
     */
    enum class CoreImpact {
        /** No impact on core - pure extension */
        NONE,
        /** Low impact - minimal core changes */
        LOW,
        /** Medium impact - moderate core modifications */
        MEDIUM,
        /** High impact - significant core changes */
        HIGH
    }

    companion object {
        fun fromLevel(level: Int): FootprintLevel? {
            return entries.find { it.level == level }
        }

        fun isValidLevel(level: Int): Boolean {
            return level in 1..6
        }

        /**
         * Returns the minimum recommended review level for a given capability
         */
        fun getMinimumReviewLevel(): FootprintLevel {
            return EXTEND_EXISTING
        }

        /**
         * Returns the maximum expansion level that doesn't require core modification
         */
        fun getMaxNonCoreLevel(): FootprintLevel {
            return SERVICE_GATED_TOOL
        }
    }
}

/**
 * Capability declaration with footprint level tracking
 */
data class CapabilityDeclaration(
    val name: String,
    val level: FootprintLevel,
    val description: String,
    val checkFn: (() -> Boolean)? = null,
    val dependencies: List<String> = emptyList(),
    val isOptional: Boolean = true
)

/**
 * Registration result for capability validation
 */
sealed class RegistrationResult {
    data class Success(val capability: CapabilityDeclaration) : RegistrationResult()
    data class Rejected(
        val reason: String,
        val suggestedLevel: FootprintLevel?,
        val alternativeApproaches: List<String> = emptyList()
    ) : RegistrationResult()
    data class NeedsReview(val capability: CapabilityDeclaration, val reviewReasons: List<String>) : RegistrationResult()
}
