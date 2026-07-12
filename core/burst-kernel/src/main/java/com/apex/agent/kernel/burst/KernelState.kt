package com.apex.agent.kernel.burst

/**
 * Typealias to the canonical [com.apex.agent.domain.model.KernelState].
 *
 * Historically this file declared its own `enum class KernelState` duplicating the one
 * in the `domain` module. Because [BurstKernel] implements [IBurstKernel] (whose
 * `getState()` returns the domain `KernelState`) while also living in the same package
 * as this local enum, the duplicate produced conflicting declarations and incompatible
 * enum comparisons across packages. Alias to the domain enum so all call sites agree.
 */
typealias KernelState = com.apex.agent.domain.model.KernelState
