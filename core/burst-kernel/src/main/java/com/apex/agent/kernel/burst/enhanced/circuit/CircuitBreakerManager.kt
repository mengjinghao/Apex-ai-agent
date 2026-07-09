package com.apex.agent.kernel.burst.enhanced.circuit

class CircuitBreakerManager

enum class CircuitState { DEFAULT }

data class CircuitInfo(val placeholder: String = "")

data class Diagnosis(val placeholder: String = "")

enum class HealAction { DEFAULT }

data class HealResult(val placeholder: String = "")

class CircuitOpenException
