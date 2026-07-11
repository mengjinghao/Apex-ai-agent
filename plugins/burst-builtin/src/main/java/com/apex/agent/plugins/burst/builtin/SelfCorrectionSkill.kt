package com.apex.agent.plugins.burst.builtin

class SelfCorrectionSkill

data class CorrectionState(val placeholder: String = "")

enum class CorrectionStatus { DEFAULT }

data class CorrectionRecord(val placeholder: String = "")

data class ProcessingResult(val placeholder: String = "")

data class ValidationResult(val placeholder: String = "")
