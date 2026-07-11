package com.ai.assistance.aiterminal.terminal.ai

interface DialogTaskHandler

class TaskExecutionResult

data class Success(val placeholder: String = "")

data class Failed(val placeholder: String = "")

data class Cancelled(val placeholder: String = "")

class StepResult

data class Success(val placeholder: String = "")

data class Failed(val placeholder: String = "")

class DefaultDialogTaskHandler
