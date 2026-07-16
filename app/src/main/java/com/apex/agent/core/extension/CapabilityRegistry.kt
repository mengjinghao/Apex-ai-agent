package com.apex.core.extension

import com.apex.util.AppLogger

/**
 * Capability Registry - Validates and registers capabilities against Footprint Ladder
 *
 * This registry ensures all new capabilities are properly classified according to
 * the Footprint Ladder hierarchy and validates them against core principles.
 */
class CapabilityRegistry private constructor() {

    companion object {
        private const val TAG = "CapabilityRegistry"

        @Volatile
        private var INSTANCE: CapabilityRegistry? = null

        private val coreNarrowWaistValidator = CoreNarrowWaistValidator()

        fun getInstance(): CapabilityRegistry {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE ?: CapabilityRegistry().also { INSTANCE = it }
                }
        }
    }

    private val registeredCapabilities = mutableMapOf<String, CapabilityDeclaration>()
    private val capabilityListeners = mutableListOf<CapabilityChangeListener>()

    /**
     * Register a new capability with the registry
     *
     * @param capability The capability declaration to register
     * @return RegistrationResult indicating success, rejection, or need for review
     */
    fun register(capability: CapabilityDeclaration): RegistrationResult {
        AppLogger.d(TAG, "Registering capability: ${capability.name} at level ${capability.level.level}")

        // Validate the capability
        val validationResult = coreNarrowWaistValidator.validate(capability)

        return when (validationResult) {
            is CoreNarrowWaistValidator.ValidationResult.Accepted -> {
                // Check if capability already exists
                if (registeredCapabilities.containsKey(capability.name)) {
                    AppLogger.w(TAG, "Capability ${capability.name} already registered, updating")
                }

                registeredCapabilities[capability.name] = capability
                notifyListeners(capability, ChangeType.REGISTERED)

                AppLogger.d(TAG, "Successfully registered capability: ${capability.name}")
                RegistrationResult.Success(capability)
            }

            is CoreNarrowWaistValidator.ValidationResult.Rejected -> {
                AppLogger.w(TAG, "Capability ${capability.name} rejected: ${validationResult.reason}")
                RegistrationResult.Rejected(
                    reason = validationResult.reason,
                    suggestedLevel = validationResult.suggestedLevel,
                    alternativeApproaches = validationResult.alternativeApproaches
                )
            }

            is CoreNarrowWaistValidator.ValidationResult.NeedsReview -> {
                AppLogger.i(TAG, "Capability ${capability.name} needs review: ${validationResult.reasons}")
                RegistrationResult.NeedsReview(
                    capability = capability,
                    reviewReasons = validationResult.reasons
                )
            }
        }
    }

    /**
     * Unregister a capability from the registry
     *
     * @param capabilityName The name of the capability to unregister
     * @return true if the capability was found and removed, false otherwise
     */
    fun unregister(capabilityName: String): Boolean {
        val capability = registeredCapabilities.remove(capabilityName)
        if (capability != null) {
            notifyListeners(capability, ChangeType.UNREGISTERED)
            AppLogger.d(TAG, "Unregistered capability: ${capabilityName}")
            return true
        }
        AppLogger.w(TAG, "Attempted to unregister non-existent capability: ${capabilityName}")
        return false
    }

    /**
     * Get a registered capability by name
     *
     * @param name The capability name
     * @return The capability declaration or null if not found
     */
    fun getCapability(name: String): CapabilityDeclaration? {
        return registeredCapabilities[name]
    }

    /**
     * Get all capabilities at a specific footprint level
     *
     * @param level The footprint level to query
     * @return List of capabilities at the specified level
     */
    fun getCapabilitiesByLevel(level: FootprintLevel): List<CapabilityDeclaration> {
        return registeredCapabilities.values.filter { it.level == level }
    }

    /**
     * Get all registered capabilities
     *
     * @return Map of all registered capabilities by name
     */
    fun getAllCapabilities(): Map<String, CapabilityDeclaration> {
        return registeredCapabilities.toMap()
    }

    /**
     * Get the total count of registered capabilities
     *
     * @return Number of registered capabilities
     */
    fun getCapabilityCount(): Int {
        return registeredCapabilities.size
    }

    /**
     * Check if a capability is registered
     *
     * @param name The capability name to check
     * @return true if registered, false otherwise
     */
    fun isRegistered(name: String): Boolean {
        return registeredCapabilities.containsKey(name)
    }

    /**
     * Check if a capability meets its level requirements
     *
     * @param name The capability name to check
     * @return true if the capability's check_fn passes (if defined), false otherwise
     */
    fun meetsRequirements(name: String): Boolean {
        val capability = registeredCapabilities[name] ?: return false
        return capability.checkFn?.invoke() ?: true
    }

    /**
     * Add a listener for capability changes
     *
     * @param listener The listener to add
     */
    fun addListener(listener: CapabilityChangeListener) {
        capabilityListeners.add(listener)
    }

    /**
     * Remove a capability change listener
     *
     * @param listener The listener to remove
     */
    fun removeListener(listener: CapabilityChangeListener) {
        capabilityListeners.remove(listener)
    }

    /**
     * Validate a capability without registering it
     *
     * @param capability The capability to validate
     * @return Validation result without registration
     */
    fun validate(capability: CapabilityDeclaration): CoreNarrowWaistValidator.ValidationResult {
        return coreNarrowWaistValidator.validate(capability)
    }

    /**
     * Get statistics about registered capabilities
     *
     * @return Registry statistics
     */
    fun getStatistics(): RegistryStatistics {
        val levelCounts = FootprintLevel.entries.associate { level ->
            level to registeredCapabilities.values.count { it.level == level }
        }
        return RegistryStatistics(
            totalCapabilities = registeredCapabilities.size,
            levelDistribution = levelCounts,
            highestLevel = registeredCapabilities.values.maxOfOrNull { it.level.level } ?: 0
        )
    }

    /**
     * Clear all registered capabilities (for testing purposes)
     */
    fun clearForTesting() {
        registeredCapabilities.clear()
        AppLogger.w(TAG, "Registry cleared for testing")
    }

    private fun notifyListeners(capability: CapabilityDeclaration, changeType: ChangeType) {
        capabilityListeners.forEach { listener ->
            try {
                when (changeType) {
                    ChangeType.REGISTERED -> listener.onCapabilityRegistered(capability)
                    ChangeType.UNREGISTERED -> listener.onCapabilityUnregistered(capability)
                    ChangeType.UPDATED -> listener.onCapabilityUpdated(capability)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying listener", e)
            }
        }
    }


    interface CapabilityChangeListener {
        fun onCapabilityRegistered(capability: CapabilityDeclaration)
        fun onCapabilityUnregistered(capability: CapabilityDeclaration)
        fun onCapabilityUpdated(capability: CapabilityDeclaration)
    }

    data class RegistryStatistics(
        val totalCapabilities: Int,
        val levelDistribution: Map<FootprintLevel, Int>,
        val highestLevel: Int
    )
}
