package com.ai.assistance.aiterminal.terminal.ai

import com.ai.assistance.aiterminal.terminal.ai.CommandGenerationResult

class MessageBuilder {
    
    fun buildUserMessage(content: String): DialogMessage {
        return DialogMessage(
            content = content,
            sender = MessageSender.USER,
            type = MessageType.TEXT
        )
    }
    
    fun buildSystemMessage(content: String): DialogMessage {
        return DialogMessage(
            content = content,
            sender = MessageSender.SYSTEM,
            type = MessageType.TEXT
        )
    }
    
    fun buildAIMessage(content: String): DialogMessage {
        return DialogMessage(
            content = content,
            sender = MessageSender.AI,
            type = MessageType.TEXT
        )
    }
    
    fun buildErrorMessage(content: String): DialogMessage {
        return DialogMessage(
            content = content,
            sender = MessageSender.ERROR,
            type = MessageType.ERROR
        )
    }
    
    fun buildProgressMessage(content: String): DialogMessage {
        return DialogMessage(
            content = content,
            sender = MessageSender.SYSTEM,
            type = MessageType.PROGRESS
        )
    }
    
    fun buildCommandConfirmation(
        result: CommandGenerationResult
    ): DialogMessage {
        return DialogMessage(
            content = buildString {
                appendLine("## 任务分析")
                appendLine()
                appendLine("**执行命令:**")
                appendLine("```")
                appendLine(result.command)
                appendLine("```")
                appendLine()
                appendLine("**命令说明:**")
                appendLine(result.explanation)
                appendLine()
                if (result.warnings.isNotEmpty()) {
                    appendLine("**警告:**")
                    result.warnings.forEach { appendLine("- $it") }
                    appendLine()
                }
                if (result.alternativeCommands.isNotEmpty()) {
                    appendLine("**备选命令:**")
                    result.alternativeCommands.forEachIndexed { index, cmd ->
                        appendLine("${index + 1}. $cmd")
                    }
                    appendLine()
                }
                appendLine("**执行风险:** ${result.matchedTemplate?.riskLevel?.displayName ?: "未知"}")
                appendLine()
                appendLine("是否执行这条命令？ (y/n)")
            },
            sender = MessageSender.AI,
            type = MessageType.CONFIRMATION,
            metadata = mapOf(
                "command" to result.command,
                "confidence" to result.confidence,
                "templateId" to (result.matchedTemplate?.id ?: "")
            )
        )
    }
    
    fun buildTaskConfirmation(
        task: String,
        steps: List<String>
    ): DialogMessage {
        return DialogMessage(
            content = buildString {
                appendLine("## 任务执行计划")
                appendLine()
                appendLine("**任务:** $task")
                appendLine()
                appendLine("**执行步骤:**")
                steps.forEachIndexed { index, step ->
                    appendLine("${index + 1}. $step")
                }
                appendLine()
                appendLine("**预计执行时间:** ${steps.size * 2} 秒")
                appendLine()
                appendLine("是否开始执行？ (y/n)")
            },
            sender = MessageSender.AI,
            type = MessageType.CONFIRMATION
        )
    }
    
    fun buildCommandOutput(output: String): DialogMessage {
        return DialogMessage(
            content = buildString {
                appendLine("## 执行结果")
                appendLine()
                appendLine("```")
                appendLine(output.take(3000))
                if (output.length > 3000) {
                    appendLine("... (output truncated)")
                }
                appendLine("```")
                appendLine()
                appendLine("**是否需要进一步操作？**")
                appendLine("1. 继续执行其他命令")
                appendLine("2. 查看命令说明")
                appendLine("3. 结束对话")
                appendLine()
                appendLine("请输入选项编号：")
            },
            sender = MessageSender.AI,
            type = MessageType.RESULT,
            metadata = mapOf("output" to output)
        )
    }
    
    fun buildInputPrompt(prompt: String): DialogMessage {
        return DialogMessage(
            content = prompt,
            sender = MessageSender.AI,
            type = MessageType.TEXT
        )
    }
    
    fun buildCommandExplanation(
        command: String,
        explanation: CommandExplanation
    ): DialogMessage {
        return DialogMessage(
            content = buildString {
                appendLine("## 命令说明")
                appendLine()
                appendLine("**命令:** $command")
                appendLine()
                appendLine("**功能说明:**")
                appendLine(explanation.explanation)
                appendLine()
                if (explanation.detailedSteps.isNotEmpty()) {
                    appendLine("**执行步骤:**")
                    explanation.detailedSteps.forEachIndexed { index, step ->
                        appendLine("${index + 1}. $step")
                    }
                    appendLine()
                }
                appendLine("**风险评估:** ${explanation.riskAssessment.level.displayName}")
                if (explanation.riskAssessment.warnings.isNotEmpty()) {
                    appendLine()
                    appendLine("**警告:**")
                    explanation.riskAssessment.warnings.forEach { appendLine("- $it") }
                    appendLine()
                }
                if (explanation.riskAssessment.precautions.isNotEmpty()) {
                    appendLine("**注意事项:**")
                    explanation.riskAssessment.precautions.forEach { appendLine("- $it") }
                    appendLine()
                }
                if (explanation.relatedCommands.isNotEmpty()) {
                    appendLine("**相关命令:**")
                    explanation.relatedCommands.forEach { appendLine("- $it") }
                    appendLine()
                }
                appendLine("**是否继续执行其他命令？** (y/n)")
            },
            sender = MessageSender.AI,
            type = MessageType.TEXT
        )
    }
    
    fun buildTaskProgress(
        currentStep: Int,
        totalSteps: Int,
        currentAction: String
    ): DialogMessage {
        return DialogMessage(
            content = "执行步骤 ${currentStep}/${totalSteps}: $currentAction",
            sender = MessageSender.SYSTEM,
            type = MessageType.PROGRESS,
            metadata = mapOf(
                "currentStep" to currentStep,
                "totalSteps" to totalSteps,
                "currentAction" to currentAction,
                "progress" to currentStep.toFloat() / totalSteps.toFloat()
            )
        )
    }
    
    fun buildTaskCompleteMessage(): DialogMessage {
        return DialogMessage(
            content = "任务执行完成！",
            sender = MessageSender.SYSTEM,
            type = MessageType.TEXT
        )
    }
    
    fun buildTaskStepFailedMessage(
        stepIndex: Int,
        reason: String
    ): DialogMessage {
        return DialogMessage(
            content = "步骤 ${stepIndex + 1} 执行失败: $reason",
            sender = MessageSender.ERROR,
            type = MessageType.ERROR
        )
    }
    
    fun buildTaskStepExceptionMessage(
        stepIndex: Int,
        exception: String
    ): DialogMessage {
        return DialogMessage(
            content = "步骤 ${stepIndex + 1} 执行异常: $exception",
            sender = MessageSender.ERROR,
            type = MessageType.ERROR
        )
    }
    
    fun buildDialogEndMessage(): DialogMessage {
        return DialogMessage(
            content = "对话已结束，感谢使用！",
            sender = MessageSender.SYSTEM,
            type = MessageType.TEXT
        )
    }
    
    fun buildInvalidInputMessage(expectedInput: String): DialogMessage {
        return DialogMessage(
            content = expectedInput,
            sender = MessageSender.AI,
            type = MessageType.TEXT
        )
    }
    
    fun buildNextCommandPrompt(): DialogMessage {
        return DialogMessage(
            content = "请输入下一条命令或任务：",
            sender = MessageSender.AI,
            type = MessageType.TEXT
        )
    }
    
    fun buildCommandNotFoundMessage(): DialogMessage {
        return DialogMessage(
            content = "没有找到命令信息",
            sender = MessageSender.ERROR,
            type = MessageType.ERROR
        )
    }
    
    fun buildAnalysisProgress(): DialogMessage {
        return DialogMessage(
            content = "正在分析任务...",
            sender = MessageSender.SYSTEM,
            type = MessageType.PROGRESS
        )
    }
    
    fun buildExplanationProgress(): DialogMessage {
        return DialogMessage(
            content = "正在生成命令说明...",
            sender = MessageSender.SYSTEM,
            type = MessageType.PROGRESS
        )
    }
}
