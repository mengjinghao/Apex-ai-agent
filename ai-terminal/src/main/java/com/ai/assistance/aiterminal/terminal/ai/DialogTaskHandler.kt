package com.ai.assistance.aiterminal.terminal.ai

import com.ai.assistance.aiterminal.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface DialogTaskHandler {
    suspend fun executeTask(
        task: String,
        steps: List<String>,
        session: TerminalSession,
        onStepProgress: (Int, Int, String) -> Unit
    ): TaskExecutionResult
}

sealed class TaskExecutionResult {
    abstract val task: String
    abstract val steps: List<String>
    
    data class Success(
        override val task: String,
        override val steps: List<String>,
        val stepResults: List<StepResult>
    ) : TaskExecutionResult()
    
    data class Failed(
        override val task: String,
        override val steps: List<String>,
        val failedStepIndex: Int,
        val reason: String
    ) : TaskExecutionResult()
    
    data class Cancelled(
        override val task: String,
        override val steps: List<String>,
        val completedSteps: Int
    ) : TaskExecutionResult()
}

sealed class StepResult {
    abstract val step: String
    
    data class Success(
        override val step: String,
        val output: String
    ) : StepResult()
    
    data class Failed(
        override val step: String,
        val reason: String
    ) : StepResult()
}

class DefaultDialogTaskHandler(
    private val commandHandler: DialogCommandHandler
) : DialogTaskHandler {
    
    override suspend fun executeTask(
        task: String,
        steps: List<String>,
        session: TerminalSession,
        onStepProgress: (Int, Int, String) -> Unit
    ): TaskExecutionResult = withContext(Dispatchers.IO) {
        val stepResults = mutableListOf<StepResult>()
        
        steps.forEachIndexed { index, step ->
            onStepProgress(index + 1, steps.size, step)
            
            val result = commandHandler.executeCommand(step, session)
            
            when (result) {
                is CommandExecutionResult.Success -> {
                    if (containsError(result.output)) {
                        return@withContext TaskExecutionResult.Failed(
                            task = task,
                            steps = steps,
                            failedStepIndex = index,
                            reason = result.output
                        )
                    }
                    stepResults.add(StepResult.Success(step, result.output))
                }
                is CommandExecutionResult.Error -> {
                    return@withContext TaskExecutionResult.Failed(
                        task = task,
                        steps = steps,
                        failedStepIndex = index,
                        reason = result.message
                    )
                }
            }
        }
        
        TaskExecutionResult.Success(task, steps, stepResults)
    }
    
    private fun containsError(output: String): Boolean {
        return output.contains("error", ignoreCase = true) || 
               output.contains("fail", ignoreCase = true)
    }
}
