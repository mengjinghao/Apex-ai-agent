package com.ai.assistance.aiterminal.terminal.ai

data class DialogMessage(val placeholder: String = "")

enum class MessageSender { DEFAULT }

enum class MessageType { DEFAULT }

data class DialogState(val placeholder: String = "")

enum class State { DEFAULT }

class PendingAction

data class ExecuteCommand(val placeholder: String = "")

data class ConfirmTask(val placeholder: String = "")

data class ProvideInput(val placeholder: String = "")

data class TaskProgress(val placeholder: String = "")

class DialogManager
