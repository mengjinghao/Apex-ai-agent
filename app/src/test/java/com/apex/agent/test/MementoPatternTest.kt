package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 备忘录模式测试
 *
 * 验证状态快照、撤销/重做和历史管理。
 */
class MementoPatternTest : BaseUnitTest {

    private lateinit var originator: TextEditor
    private lateinit var caretaker: Caretaker

    @Before
    override fun setUp() {
        super.setUp()
        originator = TextEditor()
        caretaker = Caretaker()
    }

    @Test
    fun `save should create memento snapshot`() {
        originator.setText("version 1")
        val memento = originator.save()
        assertNotNull(memento)
        assertTrue(memento.state.contains("version 1"))
    }

    @Test
    fun `restore should revert to previous state`() {
        originator.setText("v1")
        caretaker.save(originator.save())
        originator.setText("v2")
        originator.restore(caretaker.undo())
        assertEquals("v1", originator.getText())
    }

    @Test
    fun `caretaker should manage history`() {
        originator.setText("state1")
        caretaker.save(originator.save())
        originator.setText("state2")
        caretaker.save(originator.save())
        assertEquals(2, caretaker.historySize())
    }

    @Test
    fun `memento should preserve state independently`() {
        originator.setText("original")
        val memento = originator.save()
        originator.setText("modified")
        assertEquals("modified", originator.getText())
        originator.restore(memento)
        assertEquals("original", originator.getText())
    }

    @Test
    fun `undo should return previous snapshot`() {
        originator.setText("a")
        caretaker.save(originator.save())
        originator.setText("b")
        caretaker.save(originator.save())
        originator.setText("c")
        val prev = caretaker.undo()
        originator.restore(prev!!)
        assertEquals("b", originator.getText())
    }

    @Test
    fun `undo should return null when no history`() {
        assertNull(caretaker.undo())
    }

    @Test
    fun `multiple undos should step through history`() {
        originator.setText("first")
        caretaker.save(originator.save())
        originator.setText("second")
        caretaker.save(originator.save())
        originator.setText("third")
        caretaker.save(originator.save())
        originator.restore(caretaker.undo()!!)
        assertEquals("second", originator.getText())
        originator.restore(caretaker.undo()!!)
        assertEquals("first", originator.getText())
    }
}

data class Memento(val state: String)

class TextEditor {
    private var text = ""

    fun setText(t: String) { text = t }
    fun getText() = text
    fun save() = Memento(text)
    fun restore(memento: Memento) { text = memento.state }
}

class Caretaker {
    private val history = mutableListOf<Memento>()

    fun save(memento: Memento) { history.add(memento) }
    fun undo(): Memento? = if (history.isEmpty()) null else history.removeLast()
    fun historySize() = history.size
}
