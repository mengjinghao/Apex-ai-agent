package com.apex.agent.core.normal.game

// STUBBED: had 6 errors
enum class GameType { DEFAULT }
enum class GameState { DEFAULT }
data class GameSession(val placeholder: String = "")
data class GameMove(val placeholder: String = "")
sealed class MoveResult
data class Correct(val placeholder: String = "")
data class Incorrect(val placeholder: String = "")
data class Victory(val placeholder: String = "")
data class Defeat(val placeholder: String = "")
data class Continue(val placeholder: String = "")
data class GameDefinition(val placeholder: String = "")
data class Question(val placeholder: String = "")
class ConversationGameEngine
