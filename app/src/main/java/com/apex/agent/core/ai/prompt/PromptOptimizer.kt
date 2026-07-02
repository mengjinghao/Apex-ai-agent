package com.apex.agent.core.ai.prompt

/**
 * 提示工程优化�?
 * 
 * 学习�?Codex 的提示工程最佳实践，为不同类型的任务提供优化的提示模�?
 */
class PromptOptimizer {
    
    /**
     * 构建代码生成的优化提�?
     */
    fun buildCodeGenerationPrompt(
        task: String,
        language: String = "kotlin",
        context: CodeContext? = null,
        examples: List<CodeExample> = emptyList(),
        constraints: List<String> = emptyList()
    ): String {
        return buildString {
            // 系统指令 - 设定角色和标�?
            appendLine("You are an expert ${language} developer with deep knowledge of best practices.")
            appendLine("Write clean, efficient, and well-documented code.")
            appendLine("Follow Android/Kotlin conventions and design patterns.")
            appendLine()
            
            // 上下文信�?
            if (context != null) {
                if (context.imports.isNotEmpty()) {
                    appendLine("Available imports:")
                    context.imports.forEach { appendLine("- ${it}") }
                    appendLine()
                }
                
                if (context.existingCode.isNotBlank()) {
                    appendLine("Existing code context:")
                    appendLine("```${language}")
                    appendLine(context.existingCode)
                    appendLine("```")
                    appendLine()
                }
                
                if (context.dependencies.isNotEmpty()) {
                    appendLine("Project dependencies:")
                    context.dependencies.forEach { appendLine("- ${it}") }
                    appendLine()
                }
            }
            
            // Few-shot 示例（从经验记忆中获取）
            if (examples.isNotEmpty()) {
                appendLine("Examples of good solutions:")
                examples.take(3).forEachIndexed { index, example ->
                    appendLine("Example ${index + 1}:")
                    appendLine("Problem: ${example.input}")
                    appendLine("Solution:")
                    appendLine("```${language}")
                    appendLine(example.output)
                    appendLine("```")
                    appendLine()
                }
            }
            
            // 约束条件
            if (constraints.isNotEmpty()) {
                appendLine("Constraints:")
                constraints.forEach { appendLine("- ${it}") }
                appendLine()
            }
            
            // 当前任务
            appendLine("Task:")
            appendLine(task)
            appendLine()
            
            // 输出格式要求
            appendLine("Output requirements:")
            appendLine("1. Provide only the code implementation")
            appendLine("2. Include necessary imports")
            appendLine("3. Add brief comments for complex logic")
            appendLine("4. Follow SOLID principles")
            appendLine("5. Handle edge cases appropriately")
            appendLine()
            appendLine("Start your response with the code:")
        }
    }
    
    /**
     * 构建代码审查的优化提�?
     */
    fun buildCodeReviewPrompt(
        code: String,
        language: String = "kotlin",
        focusAreas: List<String> = listOf("performance", "security", "readability", "maintainability"),
        projectContext: String? = null
    ): String {
        return buildString {
            appendLine("You are a senior code reviewer specializing in ${language}.")
            appendLine("Provide thorough, constructive feedback.")
            appendLine()
            
            if (projectContext != null) {
                appendLine("Project context:")
                appendLine(projectContext)
                appendLine()
            }
            
            appendLine("Review the following ${language} code:")
            appendLine()
            appendLine("Focus areas:")
            focusAreas.forEach { appendLine("- ${it}") }
            appendLine()
            
            appendLine("Code to review:")
            appendLine("```${language}")
            appendLine(code)
            appendLine("```")
            appendLine()
            
            appendLine("Provide feedback in this structured format:")
            appendLine()
            appendLine("## Summary")
            appendLine("Brief overview of code quality")
            appendLine()
            appendLine("## Issues Found")
            appendLine("List issues with severity (Critical/High/Medium/Low)")
            appendLine()
            appendLine("## Suggestions")
            appendLine("Actionable improvement recommendations")
            appendLine()
            appendLine("## Refactored Code (if needed)")
            appendLine("Show improved version with explanations")
            appendLine()
            appendLine("## Best Practices Applied")
            appendLine("Highlight what was done well")
        }
    }
    
    /**
     * 构建代码解释的优化提�?
     */
    fun buildCodeExplanationPrompt(
        code: String,
        language: String = "kotlin",
        targetAudience: String = "intermediate developer",
        detailLevel: String = "detailed" // brief/moderate/detailed
    ): String {
        return buildString {
            appendLine("You are an excellent teacher who explains code clearly.")
            appendLine("Target audience: ${targetAudience}")
            appendLine("Detail level: ${detailLevel}")
            appendLine()
            
            appendLine("Explain the following ${language} code:")
            appendLine()
            appendLine("```${language}")
            appendLine(code)
            appendLine("```")
            appendLine()
            
            appendLine("Structure your explanation:")
            appendLine()
            appendLine("1. **Overview**: What does this code do?")
            appendLine("2. **Key Components**: Break down main parts")
            appendLine("3. **How It Works**: Step-by-step execution flow")
            appendLine("4. **Design Patterns**: Identify any patterns used")
            appendLine("5. **Potential Issues**: Point out edge cases or bugs")
            appendLine("6. **Improvement Suggestions**: How to make it better")
            appendLine()
            
            when (detailLevel) {
                "brief" -> appendLine("Keep explanations concise (2-3 sentences per section)")
                "moderate" -> appendLine("Provide moderate detail with key insights")
                "detailed" -> appendLine("Provide comprehensive explanation with examples")
            }
        }
    }
    
    /**
     * 构建重构建议的优化提�?
     */
    fun buildRefactoringPrompt(
        code: String,
        language: String = "kotlin",
        goals: List<String> = listOf("improve readability", "reduce complexity", "enhance performance"),
        constraints: List<String> = emptyList()
    ): String {
        return buildString {
            appendLine("You are a refactoring expert in ${language}.")
            appendLine("Help improve code quality while maintaining functionality.")
            appendLine()
            
            appendLine("Original code:")
            appendLine("```${language}")
            appendLine(code)
            appendLine("```")
            appendLine()
            
            appendLine("Refactoring goals:")
            goals.forEach { appendLine("- ${it}") }
            appendLine()
            
            if (constraints.isNotEmpty()) {
                appendLine("Constraints:")
                constraints.forEach { appendLine("- ${it}") }
                appendLine()
            }
            
            appendLine("Provide:")
            appendLine("1. Analysis of current issues")
            appendLine("2. Specific refactoring steps")
            appendLine("3. Refactored code")
            appendLine("4. Explanation of improvements")
            appendLine("5. Before/after comparison of key metrics")
        }
    }
    
    /**
     * 构建自然语言到任务的转换提示
     */
    fun buildNaturalLanguageToTaskPrompt(
        userInput: String,
        availableTools: List<String> = emptyList()
    ): String {
        return buildString {
            appendLine("You are a task planning assistant.")
            appendLine("Convert natural language requests into structured tasks.")
            appendLine()
            
            if (availableTools.isNotEmpty()) {
                appendLine("Available tools:")
                availableTools.forEach { appendLine("- ${it}") }
                appendLine()
            }
            
            appendLine("User request:")
            appendLine("\"${userInput}\"")
            appendLine()
            
            appendLine("Analyze and structure this request into:")
            appendLine()
            appendLine("{")
            appendLine("  \"goal\": \"Clear, actionable goal statement\",")
            appendLine("  \"steps\": [")
            appendLine("    \"Step 1: ...\",")
            appendLine("    \"Step 2: ...\",")
            appendLine("    \"...\"")
            appendLine("  ],")
            appendLine("  \"required_tools\": [\"tool1\", \"tool2\"],")
            appendLine("  \"estimated_complexity\": \"LOW|MEDIUM|HIGH|EXTREME\",")
            appendLine("  \"estimated_time_minutes\": 10,")
            appendLine("  \"potential_challenges\": [\"challenge1\", \"challenge2\"]")
            appendLine("}")
            appendLine()
            
            appendLine("Consider:")
            appendLine("- Break complex tasks into manageable steps")
            appendLine("- Identify which tools are needed")
            appendLine("- Estimate realistic time and complexity")
            appendLine("- Anticipate potential issues")
        }
    }
}

/**
 * 代码上下文信�?
 */
data class CodeContext(
    val imports: List<String> = emptyList(),
    val existingCode: String = "",
    val dependencies: List<String> = emptyList(),
    val projectName: String = "",
    val architecture: String = "" // MVVM/Clean/etc
)

/**
 * 代码示例（用�?Few-shot learning�?
 */
data class CodeExample(
    val input: String,
    val output: String,
    val tags: List<String> = emptyList() // 用于匹配相关示例
)
