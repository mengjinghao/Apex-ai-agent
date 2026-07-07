package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

data class DialogMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val sender: MessageSender,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val metadata: Map<String, Any> = emptyMap()
)

enum class MessageSender {
    USER,
    AI,
    SYSTEM,
    ERROR
}

enum class MessageType {
    TEXT,
    COMMAND,
    CONFIRMATION,
    ERROR,
    PROGRESS,
    RESULT
}

data class DialogState(
    val messages: List<DialogMessage>,
    val currentState: State,
    val pendingAction: PendingAction? = null,
    val taskProgress: TaskProgress? = null,
    val context: TerminalContext? = null
)

enum class State {
    INITIAL,
    ACTIVE,
    WAITING_FOR_USER,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

sealed class PendingAction {
    data class ExecuteCommand(val command: String, val explanation: String) : PendingAction()
    data class ConfirmTask(val task: String, val steps: List<String>) : PendingAction()
    data class ProvideInput(val prompt: String, val defaultValue: String? = null) : PendingAction()
}

data class TaskProgress(
    val currentStep: Int,
    val totalSteps: Int,
    val currentAction: String,
    val progress: Float
)

class DialogManager private constructor(
    private val context: Context,
    private val commandHandler: DialogCommandHandler,
    private val taskHandler: DialogTaskHandler,
    private val interactionHandler: DialogInteractionHandler,
    private val messageBuilder: MessageBuilder,
    private val llmApi: LLMAPI? = null
) {
    private val _dialogState = MutableStateFlow<DialogState>(
        DialogState(
            messages = emptyList(),
            currentState = State.INITIAL
        )
    )
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val contextCollector = TerminalContextCollector(context)
    private val aiHelper by lazy {
        if (llmApi != null) {
            IntelligentTerminalHelper(context, contextCollector, llmApi)
        } else {
            IntelligentTerminalHelper(context, contextCollector)
        }
    }

    companion object {
        @Volatile
        private var instance: DialogManager? = null

        fun getInstance(context: Context, llmApi: LLMAPI? = null): DialogManager {
            return instance ?: synchronized(this) {
                instance ?: createInstance(context, llmApi).also { instance = it }
            }
        }

        fun getInstance(
            context: Context,
            commandHandler: DialogCommandHandler,
            taskHandler: DialogTaskHandler,
            interactionHandler: DialogInteractionHandler,
            messageBuilder: MessageBuilder,
            llmApi: LLMAPI? = null
        ): DialogManager {
            return instance ?: synchronized(this) {
                instance ?: DialogManager(
                    context,
                    commandHandler,
                    taskHandler,
                    interactionHandler,
                    messageBuilder,
                    llmApi
                ).also { instance = it }
            }
        }

        private fun createInstance(context: Context, llmApi: LLMAPI? = null): DialogManager {
            val messageBuilder = MessageBuilder()
            val commandHandler = DefaultDialogCommandHandler(context)
            val taskHandler = DefaultDialogTaskHandler(commandHandler)
            val interactionHandler = DefaultDialogInteractionHandler()
            return DialogManager(
                context,
                commandHandler,
                taskHandler,
                interactionHandler,
                messageBuilder,
                llmApi
            )
        }
    }

    suspend fun startDialog(userPrompt: String, session: TerminalSession? = null) = withContext(Dispatchers.IO) {
        val initialMessage = messageBuilder.buildUserMessage(userPrompt)
        val terminalContext = contextCollector.collectContext()

        _dialogState.value = DialogState(
            messages = listOf(initialMessage),
            currentState = State.ACTIVE,
            context = terminalContext
        )

        processUserMessage(userPrompt, session)
    }

    suspend fun processUserMessage(message: String, session: TerminalSession? = null) = withContext(Dispatchers.IO) {
        val currentState = _dialogState.value
        val newMessages = currentState.messages + messageBuilder.buildUserMessage(message)

        when (currentState.pendingAction) {
            is PendingAction.ConfirmTask -> handleTaskConfirmation(message, session, newMessages)
            is PendingAction.ExecuteCommand -> handleCommandConfirmation(message, session, newMessages)
            is PendingAction.ProvideInput -> handleUserInput(message, session, newMessages)
            null -> handleNewIntent(message, session, newMessages)
        }
    }

    private suspend fun handleNewIntent(
        userPrompt: String,
        session: TerminalSession?,
        messages: List<DialogMessage>
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value
        val context = state.context ?: contextCollector.collectContext()

        val request = CommandGenerationRequest(
            userIntent = userPrompt,
            context = context
        )

        _dialogState.value = state.copy(
            messages = messages + messageBuilder.buildAnalysisProgress(),
            currentState = State.EXECUTING
        )

        try {
            val result = aiHelper.generateCommand(request)
            processGeneratedCommand(result, session)
        } catch (e: Exception) {
            _dialogState.value = state.copy(
                messages = messages + messageBuilder.buildErrorMessage("分析任务失败: ${e.message}"),
                currentState = State.FAILED
            )
        }
    }

    private suspend fun processGeneratedCommand(
        result: CommandGenerationResult,
        session: TerminalSession?
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value
        val messages = state.messages

        val confirmationMessage = messageBuilder.buildCommandConfirmation(result)

        _dialogState.value = state.copy(
            messages = messages + confirmationMessage,
            currentState = State.WAITING_FOR_USER,
            pendingAction = PendingAction.ExecuteCommand(
                command = result.command,
                explanation = result.explanation
            )
        )
    }

    private suspend fun handleCommandConfirmation(
        userInput: String,
        session: TerminalSession?,
        messages: List<DialogMessage>
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value
        val pendingAction = state.pendingAction as? PendingAction.ExecuteCommand ?: return@withContext

        when (interactionHandler.handleConfirmation(userInput)) {
            is ConfirmationResult.Confirmed -> {
                executeCommand(pendingAction.command, session, messages)
            }
            is ConfirmationResult.Cancelled -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildSystemMessage("命令执行已取消"),
                    currentState = State.CANCELLED,
                    pendingAction = null
                )
            }
            is ConfirmationResult.InvalidInput -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildAIMessage("请输入 y (执行) 或 n (取消)"),
                    currentState = State.WAITING_FOR_USER
                )
            }
        }
    }

    private suspend fun executeCommand(
        command: String,
        session: TerminalSession?,
        messages: List<DialogMessage>
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value

        _dialogState.value = state.copy(
            messages = messages + messageBuilder.buildProgressMessage("正在执行命令..."),
            currentState = State.EXECUTING
        )

        if (session == null) {
            _dialogState.value = state.copy(
                messages = messages + messageBuilder.buildErrorMessage("Session not available"),
                currentState = State.FAILED,
                pendingAction = null
            )
            return@withContext
        }

        val result = commandHandler.executeCommand(command, session)

        when (result) {
            is CommandExecutionResult.Success -> {
                val newMessages = messages + messageBuilder.buildProgressMessage("执行完成")
                handleCommandOutput(result.output, newMessages)
            }
            is CommandExecutionResult.Error -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildErrorMessage("执行失败: ${result.message}"),
                    currentState = State.FAILED,
                    pendingAction = null
                )
            }
        }
    }

    private suspend fun handleCommandOutput(output: String, messages: List<DialogMessage>) = withContext(Dispatchers.IO) {
        val state = _dialogState.value
        val resultMessage = messageBuilder.buildCommandOutput(output)

        _dialogState.value = state.copy(
            messages = messages + resultMessage,
            currentState = State.WAITING_FOR_USER,
            pendingAction = PendingAction.ProvideInput("请选择后续操作: 1-继续, 2-查看说明, 3-结束")
        )
    }

    private suspend fun handleUserInput(
        userInput: String,
        session: TerminalSession?,
        messages: List<DialogMessage>
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value
        state.pendingAction as? PendingAction.ProvideInput ?: return@withContext

        when (interactionHandler.handleInputChoice(userInput)) {
            is InputChoiceResult.Continue -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildNextCommandPrompt(),
                    currentState = State.ACTIVE,
                    pendingAction = null
                )
            }
            is InputChoiceResult.ViewExplanation -> {
                val lastCommand = state.messages.findLast { it.metadata.containsKey("command") }
                val command = lastCommand?.metadata?.get("command") as? String

                if (command != null) {
                    showCommandExplanation(command, messages)
                } else {
                    _dialogState.value = state.copy(
                        messages = messages + messageBuilder.buildCommandNotFoundMessage(),
                        currentState = State.ACTIVE,
                        pendingAction = null
                    )
                }
            }
            is InputChoiceResult.EndDialog -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildDialogEndMessage(),
                    currentState = State.COMPLETED,
                    pendingAction = null
                )
            }
            is InputChoiceResult.InvalidInput -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildInvalidInputMessage("无效输入，请重新选择：1-继续, 2-查看说明, 3-结束"),
                    currentState = State.WAITING_FOR_USER
                )
            }
            is InputChoiceResult.Choice -> {}
        }
    }

    private suspend fun showCommandExplanation(command: String, messages: List<DialogMessage>) = withContext(Dispatchers.IO) {
        val state = _dialogState.value

        _dialogState.value = state.copy(
            messages = messages + messageBuilder.buildExplanationProgress(),
            currentState = State.EXECUTING
        )

        try {
            val explanation = aiHelper.explainCommand(command, state.context)
            val explanationMessage = messageBuilder.buildCommandExplanation(command, explanation)

            _dialogState.value = state.copy(
                messages = messages + explanationMessage,
                currentState = State.WAITING_FOR_USER,
                pendingAction = PendingAction.ProvideInput("是否继续？ (y/n)")
            )
        } catch (e: Exception) {
            _dialogState.value = state.copy(
                messages = messages + messageBuilder.buildErrorMessage("生成说明失败: ${e.message}"),
                currentState = State.ACTIVE,
                pendingAction = null
            )
        }
    }

    private suspend fun handleTaskConfirmation(
        userInput: String,
        session: TerminalSession?,
        messages: List<DialogMessage>
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value
        val pendingAction = state.pendingAction as? PendingAction.ConfirmTask ?: return@withContext

        when (interactionHandler.handleConfirmation(userInput)) {
            is ConfirmationResult.Confirmed -> {
                executeTask(pendingAction.task, pendingAction.steps, session, messages)
            }
            is ConfirmationResult.Cancelled -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildSystemMessage("任务执行已取消"),
                    currentState = State.CANCELLED,
                    pendingAction = null
                )
            }
            is ConfirmationResult.InvalidInput -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildAIMessage("请输入 y (执行) 或 n (取消)"),
                    currentState = State.WAITING_FOR_USER
                )
            }
        }
    }

    private suspend fun executeTask(
        task: String,
        steps: List<String>,
        session: TerminalSession?,
        messages: List<DialogMessage>
    ) = withContext(Dispatchers.IO) {
        val state = _dialogState.value

        if (session == null) {
            _dialogState.value = state.copy(
                messages = messages + messageBuilder.buildErrorMessage("Session not available"),
                currentState = State.FAILED,
                pendingAction = null
            )
            return@withContext
        }

        val result = taskHandler.executeTask(
            task = task,
            steps = steps,
            session = session,
            onStepProgress = { currentStep, totalSteps, currentAction ->
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildTaskProgress(currentStep, totalSteps, currentAction),
                    currentState = State.EXECUTING,
                    taskProgress = TaskProgress(
                        currentStep = currentStep,
                        totalSteps = totalSteps,
                        currentAction = currentAction,
                        progress = currentStep.toFloat() / totalSteps.toFloat()
                    )
                )
            }
        )

        when (result) {
            is TaskExecutionResult.Success -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildTaskCompleteMessage(),
                    currentState = State.COMPLETED,
                    pendingAction = null,
                    taskProgress = null
                )
            }
            is TaskExecutionResult.Failed -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildTaskStepFailedMessage(result.failedStepIndex, result.reason),
                    currentState = State.FAILED,
                    pendingAction = null,
                    taskProgress = null
                )
            }
            is TaskExecutionResult.Cancelled -> {
                _dialogState.value = state.copy(
                    messages = messages + messageBuilder.buildSystemMessage("任务执行已取消"),
                    currentState = State.CANCELLED,
                    pendingAction = null,
                    taskProgress = null
                )
            }
        }
    }

    fun reset() {
        _dialogState.value = DialogState(
            messages = emptyList(),
            currentState = State.INITIAL
        )
    }

    fun getCurrentState(): DialogState {
        return _dialogState.value
    }

    fun hasActiveDialog(): Boolean {
        val state = _dialogState.value
        return state.currentState !in listOf(State.INITIAL, State.COMPLETED, State.FAILED, State.CANCELLED)
    }

    fun isWaitingForUser(): Boolean {
        return _dialogState.value.currentState == State.WAITING_FOR_USER
    }

    fun isExecuting(): Boolean {
        return _dialogState.value.currentState == State.EXECUTING
    }
}
