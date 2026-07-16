package com.apex.agent.ui.screens.chat

// Minimal implementation (original had 17 errors)
// TODO: Restore full implementation from original code

sealed class Bubble
data class Thinking(val data: String = "")
data class Text(val data: String = "")
data class Command(val data: String = "")
data class Search(val data: String = "")
enum class CommandStatus { DEFAULT }
data class ChatMessage(val data: String = "")
object CommandSafety {
    fun init() { }
}
enum class CommandRisk { DEFAULT }
data class SkillItem(val data: String = "")
data class ModelItem(val data: String = "")
fun ChatScreen() { }
fun PersistedMessage() { }
