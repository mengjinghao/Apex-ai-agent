package com.ai.assistance.aiterminal.terminal.ai

interface DialogInteractionHandler

class ConfirmationResult

object Confirmed

object Cancelled

data class InvalidInput(val placeholder: String = "")

class InputChoiceResult

data class Choice(val placeholder: String = "")

data class InvalidInput(val placeholder: String = "")

object Continue

object ViewExplanation

object EndDialog

class DefaultDialogInteractionHandler
