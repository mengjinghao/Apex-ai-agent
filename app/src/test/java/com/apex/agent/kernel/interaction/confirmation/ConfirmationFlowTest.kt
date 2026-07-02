package com.apex.agent.kernel.interaction.confirmation

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfirmationFlowTest {

    private lateinit var riskAssessor: RiskAssessor
    private lateinit var confirmationManager: ConfirmationManager
    private lateinit var consequenceSimulator: ConsequenceSimulator
    private lateinit var undoManager: UndoManager

    @Before
    fun setup() {
        riskAssessor = RiskAssessor()
        confirmationManager = ConfirmationManager(riskAssessor)
        consequenceSimulator = ConsequenceSimulator()
        undoManager = UndoManager()
    }

    // --- Full confirmation flow: request -> assess risk -> confirm/deny ---

    @Test
    fun `requestConfirmation creates request with risk assessment`() = runTest {
        val request = confirmationManager.requestConfirmation(
            "delete file important.txt",
            mapOf("files" to listOf("important.txt"))
        )

        assertNotNull(request.id)
        assertEquals("Confirm: delete file important.txt", request.title)
        assertNotNull(request.riskAssessment)
        assertTrue(request.riskAssessment.consequences.isNotEmpty())
    }

    @Test
    fun `requestConfirmation emits Requested event`() = runTest {
        val request = confirmationManager.requestConfirmation(
            "run system command",
            mapOf("command" to "rm -rf /")
        )

        val event = confirmationManager.events.first()
        assertTrue(event is ConfirmationEvent.Requested)
        assertEquals(request.id, (event as ConfirmationEvent.Requested).request.id)
    }

    @Test
    fun `resolveConfirmation with confirmed=true returns successful result`() = runTest {
        val request = confirmationManager.requestConfirmation("delete file.txt", emptyMap())

        val result = confirmationManager.resolveConfirmation(request.id, true)

        assertNotNull(result)
        assertTrue(result!!.confirmed)
        assertEquals(request.id, result.requestId)
    }

    @Test
    fun `resolveConfirmation with confirmed=false returns denied result`() = runTest {
        val request = confirmationManager.requestConfirmation("delete file.txt", emptyMap())

        val result = confirmationManager.resolveConfirmation(request.id, false, "Not now")

        assertNotNull(result)
        assertFalse(result!!.confirmed)
        assertEquals("Not now", result.notes)
    }

    @Test
    fun `resolveConfirmation emits Resolved event`() = runTest {
        val request = confirmationManager.requestConfirmation("delete file.txt")
        confirmationManager.resolveConfirmation(request.id, true)

        val event = confirmationManager.events.first()
        assertTrue(event is ConfirmationEvent.Resolved)
        assertEquals(request.id, (event as ConfirmationEvent.Resolved).requestId)
    }

    @Test
    fun `resolveConfirmation returns null for unknown requestId`() = runTest {
        val result = confirmationManager.resolveConfirmation("non-existent", true)

        assertNull(result)
    }

    @Test
    fun `getPendingConfirmations returns active requests`() = runTest {
        confirmationManager.requestConfirmation("delete file.txt")
        confirmationManager.requestConfirmation("run command.sh")

        val pending = confirmationManager.getPendingConfirmations()
        assertEquals(2, pending.size)
    }

    @Test
    fun `getPendingConfirmations excludes resolved requests`() = runTest {
        val request = confirmationManager.requestConfirmation("delete file.txt")
        confirmationManager.resolveConfirmation(request.id, true)

        val pending = confirmationManager.getPendingConfirmations()
        assertEquals(0, pending.size)
    }

    @Test
    fun `cancelConfirmation removes request from pending`() = runTest {
        val request = confirmationManager.requestConfirmation("delete file.txt")

        confirmationManager.cancelConfirmation(request.id)

        val pending = confirmationManager.getPendingConfirmations()
        assertEquals(0, pending.size)
    }

    // --- Risk assessment with high/medium/low risk actions ---

    @Test
    fun `riskAssessor returns HIGH risk for delete operations`() {
        val assessment = riskAssessor.assess("delete all user data")

        assertEquals(RiskLevel.HIGH, assessment.riskLevel)
        assertTrue(assessment.consequences.contains("Files will be permanently removed"))
    }

    @Test
    fun `riskAssessor returns MEDIUM risk for system modifications`() {
        val assessment = riskAssessor.assess("modify system config")

        assertEquals(RiskLevel.MEDIUM, assessment.riskLevel)
    }

    @Test
    fun `riskAssessor returns MEDIUM risk for executing code`() {
        val assessment = riskAssessor.assess("execute bash script")

        assertEquals(RiskLevel.MEDIUM, assessment.riskLevel)
    }

    @Test
    fun `riskAssessor returns LOW risk for network requests`() {
        val assessment = riskAssessor.assess("send data to http://example.com")

        assertEquals(RiskLevel.LOW, assessment.riskLevel)
    }

    @Test
    fun `riskAssessor returns CRITICAL risk for disk format operations`() {
        val assessment = riskAssessor.assess("format /dev/sda1")

        assertEquals(RiskLevel.CRITICAL, assessment.riskLevel)
    }

    @Test
    fun `riskAssessor returns NONE for unrecognized operations`() {
        val assessment = riskAssessor.assess("read documentation")

        assertEquals(RiskLevel.NONE, assessment.riskLevel)
        assertEquals(0f, assessment.score, 0.001f)
    }

    @Test
    fun `riskAssessor computes reversibility based on risk level`() {
        val lowRisk = riskAssessor.assess("send email to user")
        assertEquals(Reversibility.REVERSIBLE, lowRisk.reversibility)

        val highRisk = riskAssessor.assess("delete database records")
        assertEquals(Reversibility.PARTIALLY_REVERSIBLE, highRisk.reversibility)

        val criticalRisk = riskAssessor.assess("format disk")
        assertEquals(Reversibility.IRREVERSIBLE, criticalRisk.reversibility)
    }

    @Test
    fun `riskAssessor registerRule adds new rule`() {
        val rule = RiskRule("test_rule", "Test", "A test rule", keywords = listOf("test"))
        riskAssessor.registerRule(rule)

        val assessment = riskAssessor.assess("run test operation")
        assertEquals(RiskLevel.MEDIUM, assessment.riskLevel) // default
    }

    @Test
    fun `riskAssessor unregisterRule removes a rule`() {
        riskAssessor.unregisterRule("delete_files")

        val assessment = riskAssessor.assess("delete everything")
        assertEquals(RiskLevel.NONE, assessment.riskLevel)
    }

    @Test
    fun `riskAssessor getRules returns built-in rules`() {
        val rules = riskAssessor.getRules()
        assertTrue(rules.size >= 7)
        assertTrue(rules.any { it.id == "delete_files" })
        assertTrue(rules.any { it.id == "execute_code" })
    }

    // --- Consequence simulation ---

    @Test
    fun `simulate delete operation returns effects and warnings`() = runTest {
        val outcome = consequenceSimulator.simulate(
            "delete files",
            mapOf("targets" to listOf("file1.txt", "file2.txt"))
        )

        assertTrue(outcome.success)
        assertTrue(outcome.effects.any { it.contains("permanently removed") })
        assertEquals(2, outcome.affectedItems.size)
        assertTrue(outcome.warnings.any { it.contains("backup") })
    }

    @Test
    fun `simulate write operation returns modification effects`() = runTest {
        val outcome = consequenceSimulator.simulate(
            "write config",
            mapOf("targets" to listOf("config.json"), "content" to "new settings")
        )

        assertTrue(outcome.effects.any { it.contains("modified") })
        assertTrue(outcome.affectedItems.contains("config.json"))
    }

    @Test
    fun `simulate exec operation returns command effects`() = runTest {
        val outcome = consequenceSimulator.simulate(
            "run build",
            mapOf("command" to "./gradlew assembleRelease")
        )

        assertTrue(outcome.effects.any { it.contains("executed") })
        assertTrue(outcome.warnings.any { it.contains("side effects") })
    }

    @Test
    fun `simulate network operation returns transmission effects`() = runTest {
        val outcome = consequenceSimulator.simulate(
            "upload file",
            mapOf("url" to "https://example.com/upload")
        )

        assertTrue(outcome.effects.any { it.contains("transmitted") })
        assertTrue(outcome.warnings.any { it.contains("trustworthy") })
    }

    @Test
    fun `generateRollbackPlan includes backup restoration when applicable`() = runTest {
        val outcome = SimulatedOutcome(
            operation = "delete files",
            effects = listOf("permanently removed"),
            warnings = listOf("cannot be undone unless you have a backup")
        )

        val plan = consequenceSimulator.generateRollbackPlan(outcome)

        assertTrue(plan.any { it.contains("backup") })
    }

    @Test
    fun `estimateImpact returns formatted analysis`() = runTest {
        val impact = consequenceSimulator.estimateImpact(
            "delete files",
            mapOf("targets" to listOf("file.txt"))
        )

        assertTrue(impact.contains("Impact Analysis"))
        assertTrue(impact.contains("direct effects"))
        assertTrue(impact.contains("items affected"))
    }

    // --- Undo / Redo with different action types ---

    @Test
    fun `recordAction adds action to undo history`() {
        val action = UndoableAction(
            type = "DELETE_FILE",
            description = "Deleted config.json",
            undoData = mapOf("path" to "/etc/config.json", "backup" to "backup.json")
        )

        undoManager.recordAction(action)

        assertTrue(undoManager.canUndo())
        val history = undoManager.getUndoHistory()
        assertEquals(1, history.size)
        assertEquals("DELETE_FILE", history[0].type)
    }

    @Test
    fun `undo marks action as undone and moves to redo stack`() {
        val action = UndoableAction(
            type = "MODIFY_FILE",
            description = "Modified settings.txt",
            undoData = mapOf("original" to "old content")
        )
        undoManager.recordAction(action)

        val result = undoManager.undo(action.id)

        assertNotNull(result)
        assertTrue(result!!.success)
        assertTrue(result.message.startsWith("Undo:"))
        assertTrue(undoManager.canRedo())
        assertFalse(undoManager.canUndo())

        val history = undoManager.getUndoHistory()
        assertEquals(0, history.size)
    }

    @Test
    fun `redo restores action from redo stack`() {
        val action = UndoableAction(
            type = "CREATE_FILE",
            description = "Created newfile.txt",
            undoData = mapOf("path" to "newfile.txt")
        )
        undoManager.recordAction(action)
        undoManager.undo(action.id)

        val result = undoManager.redo(action.id)

        assertNotNull(result)
        assertTrue(result!!.success)
        assertTrue(result.message.startsWith("Redo:"))
        assertTrue(undoManager.canUndo())
    }

    @Test
    fun `undo returns null for non-existent action`() {
        val result = undoManager.undo("non-existent-id")

        assertNull(result)
    }

    @Test
    fun `redo returns null for non-existent action`() {
        val result = undoManager.redo("non-existent-id")

        assertNull(result)
    }

    @Test
    fun `recording new action clears redo stack`() {
        val action1 = UndoableAction(type = "A", description = "First action", undoData = emptyMap())
        val action2 = UndoableAction(type = "B", description = "Second action", undoData = emptyMap())

        undoManager.recordAction(action1)
        undoManager.undo(action1.id)
        assertTrue(undoManager.canRedo())

        undoManager.recordAction(action2)
        assertFalse(undoManager.canRedo())
    }

    @Test
    fun `getUndoHistory returns actions in reverse chronological order`() {
        val action1 = UndoableAction(type = "A", description = "First", undoData = emptyMap())
        val action2 = UndoableAction(type = "B", description = "Second", undoData = emptyMap())
        val action3 = UndoableAction(type = "C", description = "Third", undoData = emptyMap())

        undoManager.recordAction(action1)
        undoManager.recordAction(action2)
        undoManager.recordAction(action3)

        val history = undoManager.getUndoHistory()
        assertEquals(3, history.size)
        assertEquals("C", history[0].type) // most recent first
        assertEquals("B", history[1].type)
        assertEquals("A", history[2].type)
    }

    @Test
    fun `getRedoStack returns undone actions`() {
        val action = UndoableAction(type = "X", description = "Test", undoData = emptyMap())
        undoManager.recordAction(action)
        undoManager.undo(action.id)

        val redoStack = undoManager.getRedoStack()
        assertEquals(1, redoStack.size)
        assertEquals("X", redoStack[0].type)
    }

    @Test
    fun `clearHistory empties both stacks`() {
        val action = UndoableAction(type = "Y", description = "Test", undoData = emptyMap())
        undoManager.recordAction(action)
        undoManager.undo(action.id)

        undoManager.clearHistory()

        assertFalse(undoManager.canUndo())
        assertFalse(undoManager.canRedo())
        assertTrue(undoManager.getUndoHistory().isEmpty())
        assertTrue(undoManager.getRedoStack().isEmpty())
    }

    // --- determineLevel mapping from RiskLevel to ConfirmationLevel ---

    @Test
    fun `requestConfirmation maps risk level to confirmation level`() = runTest {
        val lowRisk = confirmationManager.requestConfirmation(
            "send email notification", emptyMap()
        )
        // LOW risk -> SIMPLE_CONFIRM
        assertEquals(ConfirmationLevel.SIMPLE_CONFIRM, lowRisk.requiredLevel)

        val highRisk = confirmationManager.requestConfirmation(
            "delete all files", mapOf("files" to listOf("*"))
        )
        // HIGH risk -> DOUBLE_CONFIRM
        assertEquals(ConfirmationLevel.DOUBLE_CONFIRM, highRisk.requiredLevel)

        val criticalRisk = confirmationManager.requestConfirmation(
            "format hard drive", emptyMap()
        )
        // CRITICAL risk -> PHRASE_CONFIRM
        assertEquals(ConfirmationLevel.PHRASE_CONFIRM, criticalRisk.requiredLevel)
    }

    @Test
    fun `requestConfirmation with overrideLevel uses provided level`() = runTest {
        val request = confirmationManager.requestConfirmation(
            "view file", emptyMap(), overrideLevel = ConfirmationLevel.DOUBLE_CONFIRM
        )

        assertEquals(ConfirmationLevel.DOUBLE_CONFIRM, request.requiredLevel)
    }

    @Test
    fun `riskAssessor score scales with risk level ordinal`() {
        val none = riskAssessor.assess("read file")
        assertEquals(0f, none.score, 0.001f)

        val high = riskAssessor.assess("delete database")
        assertTrue(high.score > 0.5f)
    }
}
