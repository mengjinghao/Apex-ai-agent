package com.apex.ai.data

data class ApiConfig(
    val endpoint: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val systemPrompt: String = "你是 Apex AI Agent，一个智能助手。请用中文回答。",
    val temperature: Double = 0.7
)
