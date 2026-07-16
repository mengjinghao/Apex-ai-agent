package com.apex.agent.core.normal.game

// Minimal implementation (original had 6 errors)
// TODO: Restore full implementation from original code

enum class GameType { DEFAULT }
enum class GameState { DEFAULT }
data class GameSession(val data: String = "")
data class GameMove(val data: String = "")
sealed class MoveResult
data class Correct(val data: String = "")
data class Incorrect(val data: String = "")
data class Victory(val data: String = "")
data class Defeat(val data: String = "")
data class Continue(val data: String = "")
data class GameDefinition(val data: String = "")
data class Question(val data: String = "")
class ConversationGameEngine
