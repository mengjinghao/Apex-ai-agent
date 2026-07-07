package com.ai.assistance.aiterminal.terminal.ai

interface DialogInteractionHandler {
    fun handleConfirmation(userInput: String): ConfirmationResult
    fun handleInputChoice(userInput: String): InputChoiceResult
    fun isConfirmationPositive(input: String): Boolean
    fun isConfirmationNegative(input: String): Boolean
}

sealed class ConfirmationResult {
    object Confirmed : ConfirmationResult()
    object Cancelled : ConfirmationResult()
    data class InvalidInput(val message: String) : ConfirmationResult()
}

sealed class InputChoiceResult {
    data class Choice(val option: String, val value: String) : InputChoiceResult()
    data class InvalidInput(val message: String) : InputChoiceResult()
    object Continue : InputChoiceResult()
    object ViewExplanation : InputChoiceResult()
    object EndDialog : InputChoiceResult()
}

class DefaultDialogInteractionHandler : DialogInteractionHandler {
    
    private val positiveInputs = setOf("y", "yes", "确认", "是", "同意")
    private val negativeInputs = setOf("n", "no", "取消", "否", "拒绝")
    
    override fun handleConfirmation(userInput: String): ConfirmationResult {
        val normalized = userInput.trim().lowercase()
        
        return when {
            normalized in positiveInputs -> ConfirmationResult.Confirmed
            normalized in negativeInputs -> ConfirmationResult.Cancelled
            else -> ConfirmationResult.InvalidInput("请输入 y (执行) 或 n (取消)")
        }
    }
    
    override fun handleInputChoice(userInput: String): InputChoiceResult {
        return when (userInput.trim()) {
            "1", "继续" -> InputChoiceResult.Continue
            "2", "查看说明" -> InputChoiceResult.ViewExplanation
            "3", "结束" -> InputChoiceResult.EndDialog
            else -> InputChoiceResult.InvalidInput("无效输入，请重新选择：1-继续, 2-查看说明, 3-结束")
        }
    }
    
    override fun isConfirmationPositive(input: String): Boolean {
        return input.trim().lowercase() in positiveInputs
    }
    
    override fun isConfirmationNegative(input: String): Boolean {
        return input.trim().lowercase() in negativeInputs
    }
}
