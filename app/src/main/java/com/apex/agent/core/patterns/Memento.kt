package com.apex.agent.core.patterns

/**
 * 备忘录模式 - Agent 状态快照与恢复
 * 在不暴露内部结构的前提下捕获和外部化对象状态，支持撤销/重做
 */

/** 不可变备忘录 */
data class Memento(val state: Map<String, Any>, val timestamp: Long = System.currentTimeMillis())

/** 发起人接口 */
interface Originator<T> {
    fun createMemento(): Memento
    fun restore(memento: Memento): Boolean
}

/** 看护人 - 管理撤销/重做栈 */
class Caretaker<T>(private val maxDepth: Int = 20) {

    private val undoStack = ArrayDeque<Memento>()
    private val redoStack = ArrayDeque<Memento>()
    private var currentMemento: Memento? = null

    fun save(originator: Originator<T>) {
        val memento = originator.createMemento()
        if (currentMemento != null) undoStack.addLast(currentMemento!!)
        if (undoStack.size > maxDepth) undoStack.removeFirst()
        currentMemento = memento
        redoStack.clear()
    }

    fun undo(originator: Originator<T>): Boolean {
        val memento = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(currentMemento!!)
        if (redoStack.size > maxDepth) redoStack.removeFirst()
        currentMemento = memento
        return originator.restore(memento)
    }

    fun redo(originator: Originator<T>): Boolean {
        val memento = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(currentMemento!!)
        currentMemento = memento
        return originator.restore(memento)
    }

    fun getCurrent(): Memento? = currentMemento

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        currentMemento = null
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}

/** Agent 状态 */
data class AgentState(
    val status: String = "idle",
    val activeTasks: List<String> = emptyList(),
    val memoryUsage: Long = 0,
    val config: Map<String, String> = emptyMap()
)

/** Agent 状态发起人 */
class AgentStateOriginator : Originator<AgentState> {

    var state: AgentState = AgentState()
        private set

    fun updateState(updater: (AgentState) -> AgentState) {
        state = updater(state)
    }

    override fun createMemento(): Memento {
        return Memento(
            state = mapOf(
                "status" to state.status,
                "activeTasks" to state.activeTasks.joinToString(","),
                "memoryUsage" to state.memoryUsage,
                "config" to state.config.entries.joinToString(";") { "${it.key}=${it.value}" }
            )
        )
    }

    override fun restore(memento: Memento): Boolean {
        val stateMap = memento.state
        state = AgentState(
            status = stateMap["status"] as? String ?: return false,
            activeTasks = (stateMap["activeTasks"] as? String)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            memoryUsage = (stateMap["memoryUsage"] as? Number)?.toLong() ?: return false,
            config = (stateMap["config"] as? String)
                ?.split(";")?.filter { it.isNotBlank() }
                ?.associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (parts.getOrElse(1) { "" })
                } ?: emptyMap()
        )
        return true
    }
}

/** 配置发起人 */
class ConfigurationOriginator : Originator<Map<String, String>> {

    private var config: Map<String, String> = emptyMap()

    fun setConfig(key: String, value: String) {
        config = config + (key to value)
    }

    fun getConfig(key: String): String? = config[key]

    fun getAllConfig(): Map<String, String> = config.toMap()

    override fun createMemento(): Memento {
        return Memento(state = config)
    }

    override fun restore(memento: Memento): Boolean {
        @Suppress("UNCHECKED_CAST")
        config = (memento.state as? Map<String, String>) ?: return false
        return true
    }
}
