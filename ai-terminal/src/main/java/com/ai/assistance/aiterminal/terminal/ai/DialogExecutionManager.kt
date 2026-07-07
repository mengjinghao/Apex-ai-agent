package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow

class DialogExecutionManager(private val context: Context, private val llmApi: LLMAPI? = null) {
    private val dialogManager by lazy {
        DialogManager.getInstance(context, llmApi)
    }
    
    private val messageBuilder by lazy {
        MessageBuilder()
    }

    val dialogState: StateFlow<DialogState>
        get() = dialogManager.dialogState

    suspend fun startExecution(
        userPrompt: String,
        session: TerminalSession?
    ) {
        dialogManager.startDialog(userPrompt, session)
    }

    suspend fun respondToDialog(
        userInput: String,
        session: TerminalSession?
    ) {
        dialogManager.processUserMessage(userInput, session)
    }

    fun getCurrentState(): DialogState {
        return dialogManager.getCurrentState()
    }

    fun hasActiveDialog(): Boolean {
        return dialogManager.hasActiveDialog()
    }

    fun isWaitingForUser(): Boolean {
        return dialogManager.isWaitingForUser()
    }

    fun isExecuting(): Boolean {
        return dialogManager.isExecuting()
    }

    fun resetDialog() {
        dialogManager.reset()
    }

    fun getDialogMessages(): List<DialogMessage> {
        return dialogManager.getCurrentState().messages
    }

    suspend fun generateTaskPlan(
        userIntent: String,
        context: TerminalContext? = null
    ): List<String> {
        val intelligentHelper = IntelligentTerminalHelper(
            context = this.context,
            contextCollector = TerminalContextCollector(this.context)
        )

        val request = CommandGenerationRequest(
            userIntent = userIntent,
            context = context
        )

        val result = intelligentHelper.generateCommand(request)
        return listOf(result.command) + result.alternativeCommands
    }

    suspend fun executeTaskPlan(
        task: String,
        steps: List<String>,
        session: TerminalSession?
    ) {
        val confirmMessage = messageBuilder.buildTaskConfirmation(task, steps)
        
        dialogManager.startDialog(task, session)
    }
}
