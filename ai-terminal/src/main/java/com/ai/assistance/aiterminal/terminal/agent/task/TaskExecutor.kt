package com.ai.assistance.aiterminal.terminal.agent.task

class TaskExecutor

data class TaskExecutionContext(val placeholder: String = "")

class TaskExecutionState

object IDLE

object RUNNING

object PAUSED

object COMPLETED

object ROLLED_BACK

object CANCELLED

data class StepRunning(val placeholder: String = "")

data class FAILED(val placeholder: String = "")

data class AWAITING_CONFIRMATION(val placeholder: String = "")
