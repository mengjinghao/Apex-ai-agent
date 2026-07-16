package com.apex.agent.core.extension

typealias CapabilityValidationResult = CoreNarrowWaistValidator.ValidationResult

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
