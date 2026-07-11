package com.ai.assistance.aiterminal.terminal.ai

interface DialogCommandHandler

class CommandExecutionResult

data class Success(val placeholder: String = "")

data class Error(val placeholder: String = "")

class DefaultDialogCommandHandler
