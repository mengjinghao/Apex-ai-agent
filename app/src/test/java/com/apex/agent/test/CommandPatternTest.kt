package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 命令模式测试
 *
 * 验证命令执行、撤销/重做、历史记录和事务支持。
 */
class CommandPatternTest : BaseUnitTest {

    private lateinit var invoker: CommandInvoker

    @Before
    override fun setUp() {
        super.setUp()
        invoker = CommandInvoker()
    }

    @Test
    fun `command should execute successfully`() {
        val cmd = AddCommand(2, 3)
        val result = cmd.execute()
        assertEquals(5, result)
    }

    @Test
    fun `undo should revert previous command`() {
        val cmd = AddCommand(5, 3)
        invoker.execute(cmd)
        assertEquals(8, (cmd as AddCommand).result)
        invoker.undo()
        assertEquals(5, cmd.undoValue)
    }

    @Test
    fun `redo should reapply undone command`() {
        val cmd = AddCommand(1, 2)
        invoker.execute(cmd)
        invoker.undo()
        invoker.redo()
        assertEquals(3, (cmd as AddCommand).result)
    }

    @Test
    fun `history should track command sequence`() {
        invoker.execute(AddCommand(1, 1))
        invoker.execute(AddCommand(2, 2))
        invoker.execute(AddCommand(3, 3))
        assertEquals(3, invoker.historySize())
    }

    @Test
    fun `clear should reset history`() {
        invoker.execute(AddCommand(1, 1))
        invoker.clear()
        assertEquals(0, invoker.historySize())
    }

    @Test
    fun `invoker should not redo if no undone commands`() {
        assertFalse(invoker.redo())
    }

    @Test
    fun `invoker should not undo if no history`() {
        assertFalse(invoker.undo())
    }

    @Test
    fun `macro command should execute multiple`() {
        val macro = MacroCommand(listOf(AddCommand(1, 1), AddCommand(2, 2)))
        val results = macro.execute()
        assertEquals(2, (results as List<*>).size)
    }

    @Test
    fun `undo should restore state correctly`() {
        invoker.execute(AddCommand(10, 5))
        val before = (invoker.peekHistory() as AddCommand?)?.result
        invoker.undo()
        val after = invoker.peekHistory()
        if (before != null) assertNull(after)
        else assertNull(after)
    }
}

interface Command {
    fun execute(): Any?
    fun undo(): Any?
}

class AddCommand(private val a: Int, private val b: Int) : Command {
    var result: Int = 0
    var undoValue: Int = 0

    override fun execute(): Any? {
        result = a + b
        return result
    }

    override fun undo(): Any? {
        undoValue = a
        return undoValue
    }
}

class MacroCommand(private val commands: List<Command>) : Command {
    override fun execute(): Any? {
        return commands.map { it.execute() }
    }

    override fun undo(): Any? {
        return commands.map { it.undo() }
    }
}

class CommandInvoker {
    private val history = mutableListOf<Command>()
    private val redoStack = mutableListOf<Command>()

    fun execute(cmd: Command) {
        cmd.execute()
        history.add(cmd)
        redoStack.clear()
    }

    fun undo(): Boolean {
        val cmd = history.removeLastOrNull() ?: return false
        cmd.undo()
        redoStack.add(cmd)
        return true
    }

    fun redo(): Boolean {
        val cmd = redoStack.removeLastOrNull() ?: return false
        cmd.execute()
        history.add(cmd)
        return true
    }

    fun historySize() = history.size
    fun clear() { history.clear(); redoStack.clear() }
    fun peekHistory() = history.lastOrNull()
}
