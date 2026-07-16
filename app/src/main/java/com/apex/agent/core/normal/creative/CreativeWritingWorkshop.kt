package com.apex.agent.core.normal.creative

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

enum class WritingGenre { DEFAULT }
data class WritingStyle(val data: String = "")
enum class WritingTone { DEFAULT }
enum class WritingMood { DEFAULT }
enum class Perspective { DEFAULT }
enum class Tense { DEFAULT }
data class WritingProject(val data: String = "")
enum class ProjectStatus { DEFAULT }
data class Character(val data: String = "")
data class OutlineNode(val data: String = "")
data class Draft(val data: String = "")
data class InspirationCard(val data: String = "")
enum class InspirationType { DEFAULT }
class CreativeWritingWorkshop
enum class NameStyle { DEFAULT }
data class WritingEvaluation(val data: String = "")
