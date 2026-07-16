package com.apex.core.config

import com.apex.data.model.SystemToolPromptCategory
import com.apex.data.model.ToolPrompt

object SystemToolPromptsUtils {

    fun getToolByName(categories: List<SystemToolPromptCategory>, toolName: String): ToolPrompt? {
        for (category in categories) {
            val tool = category.tools.find { it.name == toolName }
            if (tool != null) {
                return tool
            }
        }
        return null
    }

    fun getAllTools(categories: List<SystemToolPromptCategory>): List<ToolPrompt> {
        return categories.flatMap { it.tools }
    }

    fun getToolsByCategory(categories: List<SystemToolPromptCategory>, categoryName: String): List<ToolPrompt> {
        val category = categories.find { it.categoryName == categoryName }
        return category?.tools ?: emptyList()
    }

    fun getCategories(categories: List<SystemToolPromptCategory>): List<String> {
        return categories.map { it.categoryName }
    }

    fun searchTools(categories: List<SystemToolPromptCategory>, query: String): List<ToolPrompt> {
        val lowerQuery = query.lowercase()
        return categories.flatMap { category ->
            category.tools.filter { tool ->
                tool.name.lowercase().contains(lowerQuery) ||
                tool.description.lowercase().contains(lowerQuery)
            }
        }
    }
}
