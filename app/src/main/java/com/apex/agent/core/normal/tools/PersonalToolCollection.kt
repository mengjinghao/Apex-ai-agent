package com.apex.agent.core.normal.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * F12: 个人化工具集（Personal Tool Collection）
 *
 * 用户专属工具集合——"我的笔记"/"我的代码片段"/"我的收藏"/"我的待办"。
 * 这些工具的数据存储在用户私有空间，单 Agent 独占。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的工具是 Agent 共享
 * - 狂暴的工具是任务级
 * - 本功能是**用户私有工具**，体现单 Agent 的个人化
 */

/**
 * 个人工具基类
 */
abstract class PersonalTool {
    abstract val id: String
    abstract val name: String
    abstract val displayName: String
    abstract val description: String
    abstract val icon: String

    /**
     * 执行工具
     */
    abstract suspend fun execute(arguments: Map<String, Any>): PersonalToolResult

    /**
     * 获取工具说明（用于 LLM）
     */
    open fun toToolSpec(): String {
        return buildString {
            appendLine("工具: $displayName")
            appendLine("说明: $description")
        }
    }
}

/**
 * 工具执行结果
 */
sealed class PersonalToolResult {

/**
 * 个人笔记工具
 */
class PersonalNotesTool(
    private val storage: PersonalStorage
) : PersonalTool() {

    override val id = "personal_notes"
    override val name = "my_notes"
    override val displayName = "我的笔记"
    override val description = "管理用户的个人笔记，支持增删改查"
    override val icon = "📝"

    override suspend fun execute(arguments: Map<String, Any>): PersonalToolResult {
        val action = arguments["action"]?.toString() ?: "list"
        return when (action) {
            "list" -> {
                val notes = storage.listNotes()
                PersonalToolResult.Success(notes.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(50)}" })
            }
            "add" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                val content = arguments["content"]?.toString() ?: ""
                val note = storage.addNote(title, content)
                PersonalToolResult.Success("已添加笔记: ${note.title}", note)
            }
            "get" -> {
                val id = arguments["id"]?.toString() ?: return PersonalToolResult.Failure("缺少 id")
                val note = storage.getNote(id) ?: return PersonalToolResult.Failure("笔记不存在")
                PersonalToolResult.Success("标题: ${note.title}\n内容: ${note.content}", note)
            }
            "delete" -> {
                val id = arguments["id"]?.toString() ?: return PersonalToolResult.Failure("缺少 id")
                storage.deleteNote(id)
                PersonalToolResult.Success("已删除笔记 $id")
            }
            "search" -> {
                val query = arguments["query"]?.toString() ?: ""
                val results = storage.searchNotes(query)
                PersonalToolResult.Success(results.joinToString("\n") { "- [${it.id}] ${it.title}" })
            }
            else -> PersonalToolResult.Failure("未知操作: $action")
        }
    }
}

/**
 * 代码片段工具
 */
class PersonalSnippetsTool(
    private val storage: PersonalStorage
) : PersonalTool() {

    override val id = "personal_snippets"
    override val name = "my_snippets"
    override val displayName = "我的代码片段"
    override val description = "管理用户的常用代码片段"
    override val icon = "💾"

    override suspend fun execute(arguments: Map<String, Any>): PersonalToolResult {
        val action = arguments["action"]?.toString() ?: "list"
        return when (action) {
            "list" -> {
                val snippets = storage.listSnippets()
                PersonalToolResult.Success(snippets.joinToString("\n") { "- [${it.language}] ${it.title}" })
            }
            "add" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                val code = arguments["code"]?.toString() ?: return PersonalToolResult.Failure("缺少 code")
                val language = arguments["language"]?.toString() ?: "text"
                val snippet = storage.addSnippet(title, code, language)
                PersonalToolResult.Success("已保存代码片段: ${snippet.title}", snippet)
            }
            "get" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                val snippet = storage.getSnippetByTitle(title) ?: return PersonalToolResult.Failure("片段不存在")
                PersonalToolResult.Success("```${snippet.language}\n${snippet.code}\n```", snippet)
            }
            else -> PersonalToolResult.Failure("未知操作: $action")
        }
    }
}

/**
 * 收藏夹工具
 */
class PersonalFavoritesTool(
    private val storage: PersonalStorage
) : PersonalTool() {

    override val id = "personal_favorites"
    override val name = "my_favorites"
    override val displayName = "我的收藏"
    override val description = "管理用户收藏的链接/文件/文本"
    override val icon = "⭐"

    override suspend fun execute(arguments: Map<String, Any>): PersonalToolResult {
        val action = arguments["action"]?.toString() ?: "list"
        return when (action) {
            "list" -> {
                val category = arguments["category"]?.toString()
                val favs = if (category != null) storage.listFavoritesByCategory(category)
                          else storage.listFavorites()
                PersonalToolResult.Success(favs.joinToString("\n") { "- [${it.category}] ${it.title}: ${it.content.take(50)}" })
            }
            "add" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                val content = arguments["content"]?.toString() ?: ""
                val category = arguments["category"]?.toString() ?: "general"
                val fav = storage.addFavorite(title, content, category)
                PersonalToolResult.Success("已添加收藏: ${fav.title}", fav)
            }
            "remove" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                storage.removeFavorite(title)
                PersonalToolResult.Success("已移除收藏: $title")
            }
            else -> PersonalToolResult.Failure("未知操作: $action")
        }
    }
}

/**
 * 待办事项工具
 */
class PersonalTodoTool(
    private val storage: PersonalStorage
) : PersonalTool() {

    override val id = "personal_todo"
    override val name = "my_todo"
    override val displayName = "我的待办"
    override val description = "管理用户的待办事项"
    override val icon = "✅"

    override suspend fun execute(arguments: Map<String, Any>): PersonalToolResult {
        val action = arguments["action"]?.toString() ?: "list"
        return when (action) {
            "list" -> {
                val todos = storage.listTodos()
                val pending = todos.filter { !it.done }
                val completed = todos.filter { it.done }
                val sb = StringBuilder()
                if (pending.isNotEmpty()) {
                    sb.appendLine("待办:")
                    pending.forEach { sb.appendLine("  ☐ ${it.title} (优先级: ${it.priority})")
                        it.dueDate?.let { d -> sb.appendLine("    截止: $d") }
                    }
                }
                if (completed.isNotEmpty()) {
                    sb.appendLine("已完成:")
                    completed.forEach { sb.appendLine("  ☑ ${it.title}") }
                }
                PersonalToolResult.Success(sb.toString())
            }
            "add" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                val priority = (arguments["priority"] as? Number)?.toInt() ?: 3
                val dueDate = arguments["due_date"]?.toString()
                val todo = storage.addTodo(title, priority, dueDate)
                PersonalToolResult.Success("已添加待办: ${todo.title}", todo)
            }
            "complete" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                storage.completeTodo(title)
                PersonalToolResult.Success("已完成待办: $title")
            }
            "remove" -> {
                val title = arguments["title"]?.toString() ?: return PersonalToolResult.Failure("缺少 title")
                storage.removeTodo(title)
                PersonalToolResult.Success("已移除待办: $title")
            }
            else -> PersonalToolResult.Failure("未知操作: $action")
        }
    }
}

/**
 * 个人存储数据结构
 */
data class PersonalNote(val id: String, val title: String, val content: String, val createdAt: Long, val updatedAt: Long)
data class PersonalSnippet(val id: String, val title: String, val code: String, val language: String, val createdAt: Long)
data class PersonalFavorite(val id: String, val title: String, val content: String, val category: String, val createdAt: Long)
data class PersonalTodo(val id: String, val title: String, val priority: Int, val done: Boolean, val dueDate: String?, val createdAt: Long)

/**
 * 个人存储接口（业务侧实现持久化）
 */
interface PersonalStorage {
    fun listNotes(): List<PersonalNote>
    fun addNote(title: String, content: String): PersonalNote
    fun getNote(id: String): PersonalNote?
    fun deleteNote(id: String)
    fun searchNotes(query: String): List<PersonalNote>

    fun listSnippets(): List<PersonalSnippet>
    fun addSnippet(title: String, code: String, language: String): PersonalSnippet
    fun getSnippetByTitle(title: String): PersonalSnippet?

    fun listFavorites(): List<PersonalFavorite>
    fun listFavoritesByCategory(category: String): List<PersonalFavorite>
    fun addFavorite(title: String, content: String, category: String): PersonalFavorite
    fun removeFavorite(title: String)

    fun listTodos(): List<PersonalTodo>
    fun addTodo(title: String, priority: Int, dueDate: String?): PersonalTodo
    fun completeTodo(title: String)
    fun removeTodo(title: String)
}

/**
 * 内存存储实现
 */
class InMemoryPersonalStorage : PersonalStorage {
    private val notes = ConcurrentHashMap<String, PersonalNote>()
    private val snippets = ConcurrentHashMap<String, PersonalSnippet>()
    private val favorites = ConcurrentHashMap<String, PersonalFavorite>()
    private val todos = ConcurrentHashMap<String, PersonalTodo>()

    override fun listNotes() = notes.values.sortedByDescending { it.updatedAt }.toList()
    override fun addNote(title: String, content: String): PersonalNote {
        val now = System.currentTimeMillis()
        val note = PersonalNote("note_$now", title, content, now, now)
        notes[note.id] = note
        return note
    }
    override fun getNote(id: String) = notes[id]
    override fun deleteNote(id: String) { notes.remove(id) }
    override fun searchNotes(query: String) = notes.values.filter {
        it.title.contains(query, true) || it.content.contains(query, true)
    }.toList()

    override fun listSnippets() = snippets.values.sortedByDescending { it.createdAt }.toList()
    override fun addSnippet(title: String, code: String, language: String): PersonalSnippet {
        val snippet = PersonalSnippet("snippet_${System.currentTimeMillis()}", title, code, language, System.currentTimeMillis())
        snippets[snippet.id] = snippet
        return snippet
    }
    override fun getSnippetByTitle(title: String) = snippets.values.find { it.title == title }

    override fun listFavorites() = favorites.values.sortedByDescending { it.createdAt }.toList()
    override fun listFavoritesByCategory(category: String) = favorites.values.filter { it.category == category }.toList()
    override fun addFavorite(title: String, content: String, category: String): PersonalFavorite {
        val fav = PersonalFavorite("fav_${System.currentTimeMillis()}", title, content, category, System.currentTimeMillis())
        favorites[fav.id] = fav
        return fav
    }
    override fun removeFavorite(title: String) {
        favorites.values.find { it.title == title }?.let { favorites.remove(it.id) }
    }

    override fun listTodos() = todos.values.sortedBy { it.priority }.toList()
    override fun addTodo(title: String, priority: Int, dueDate: String?): PersonalTodo {
        val todo = PersonalTodo("todo_${System.currentTimeMillis()}", title, priority, false, dueDate, System.currentTimeMillis())
        todos[todo.id] = todo
        return todo
    }
    override fun completeTodo(title: String) {
        todos.values.find { it.title == title }?.let { todo ->
            todos[todo.id] = todo.copy(done = true)
        }
    }
    override fun removeTodo(title: String) {
        todos.values.find { it.title == title }?.let { todos.remove(it.id) }
    }
}

/**
 * 个人工具集注册表
 */
class PersonalToolRegistry(
    private val storage: PersonalStorage = InMemoryPersonalStorage()
) {
    private val tools = ConcurrentHashMap<String, PersonalTool>()

    init {
        registerBuiltinTools()
    }

    fun register(tool: PersonalTool) {
        tools[tool.id] = tool
    }

    fun get(id: String): PersonalTool? = tools[id]
    fun list(): List<PersonalTool> = tools.values.toList()

    /**
     * 生成所有个人工具的说明（注入 LLM prompt）
     */
    fun generateToolsPrompt(): String {
        if (tools.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[个人工具集]")
        tools.values.forEach { tool ->
            sb.appendLine("- ${tool.toToolSpec()}")
        }
        return sb.toString()
    }

    /**
     * 执行工具
     */
    suspend fun execute(toolId: String, arguments: Map<String, Any>): PersonalToolResult {
        val tool = tools[toolId] ?: return PersonalToolResult.Failure("未找到工具: $toolId")
        return tool.execute(arguments)
    }

    private fun registerBuiltinTools() {
        register(PersonalNotesTool(storage))
        register(PersonalSnippetsTool(storage))
        register(PersonalFavoritesTool(storage))
        register(PersonalTodoTool(storage))
    }
}
