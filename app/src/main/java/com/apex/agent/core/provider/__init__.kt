package com.apex.agent.core.provider

typealias ProviderAuthType = ProviderProfile.AuthType
typealias ProviderType = ProviderProfile.ProviderType
typealias ProviderMessage = ProviderProfile.Message
typealias ModelInfo = ProviderProfile.ModelInfo
typealias Pricing = ProviderProfile.Pricing

fun openAIProvider(): ProviderProfile = ProviderProfile.openAI()
fun anthropicProvider(): ProviderProfile = ProviderProfile.anthropic()
fun googleProvider(): ProviderProfile = ProviderProfile.google()
fun openRouterProvider(): ProviderProfile = ProviderProfile.openRouter()
fun ollamaProvider(): ProviderProfile = ProviderProfile.ollama()
fun kimiProvider(): ProviderProfile = ProviderProfile.kimi()
fun minimaxProvider(): ProviderProfile = ProviderProfile.minimax()
fun zaiProvider(): ProviderProfile = ProviderProfile.zai()
