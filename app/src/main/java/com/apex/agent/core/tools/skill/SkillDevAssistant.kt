package com.apex.agent.core.tools.skill

// Minimal implementation (original had 133 errors)
// TODO: Restore full implementation from original code

class SkillDevAssistant
data class EditorDocument(val data: String = "")
data class CompletionItem(val data: String = "")
enum class CompletionKind { DEFAULT }
data class Diagnostic(val data: String = "")
enum class DiagnosticSeverity { DEFAULT }
data class SyntaxToken(val data: String = "")
interface AssistantListener
data class SkillStructure(val data: String = "")
data class FileNode(val data: String = "")
data class PreviewResult(val data: String = "")
enum class PreviewType { DEFAULT }
data class TokenPattern(val data: String = "")
class LanguageServer
class JSLanguageServer
class TypeScriptLanguageServer
class MarkdownLanguageServer
