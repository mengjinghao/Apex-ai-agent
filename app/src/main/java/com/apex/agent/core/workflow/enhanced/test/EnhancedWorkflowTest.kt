package com.apex.agent.core.workflow.enhanced.test

import com.apex.agent.core.workflow.enhanced.expression.ExpressionEvaluator
import com.apex.agent.core.workflow.enhanced.model.*
import com.apex.agent.core.workflow.enhanced.scheduler.CronParser
import com.apex.agent.core.workflow.enhanced.validation.WorkflowValidator
import com.apex.agent.core.workflow.enhanced.replay.WorkflowReplayer
import com.apex.agent.core.workflow.enhanced.monitor.WorkflowMonitor
import com.apex.agent.core.workflow.enhanced.serializer.WorkflowSerializer
import com.apex.agent.core.workflow.enhanced.migration.WorkflowMigrationAdapter
import org.junit.Test
import org.junit.Assert.*
import java.util.Calendar
import java.util.TimeZone

/**
 * 增强工作流系统单元测试
 *
 * 覆盖：表达式引擎、Cron 解析、DAG 校验、迁移、序列化、监控
 */
class EnhancedWorkflowTest {

    // ============ 表达式引擎测试 ============

    @Test
    fun testExpressionEvaluator_basic() {
        val eval = ExpressionEvaluator()
        val ctx = mapOf("user" to mapOf("age" to 25, "name" to "Alice"))

        assertEquals(25.0, eval.evaluate("\${user.age}", ctx))
        assertEquals("Alice", eval.evaluate("\${user.name}", ctx))
        assertEquals(true, eval.evaluate("\${user.age} > 18", ctx))
        assertEquals(false, eval.evaluate("\${user.age} < 20", ctx))
    }

    @Test
    fun testExpressionEvaluator_logicalOperators() {
        val eval = ExpressionEvaluator()
        val ctx = mapOf("age" to 25, "country" to "CN")

        assertEquals(true, eval.evaluate("\${age} > 18 && \${country} == 'CN'", ctx))
        assertEquals(false, eval.evaluate("\${age} > 30 || \${country} == 'US'", ctx))
        assertEquals(true, eval.evaluate("!false", ctx))
    }

    @Test
    fun testExpressionEvaluator_ternary() {
        val eval = ExpressionEvaluator()
        val ctx = mapOf("status" to "success")

        assertEquals("完成", eval.evaluate("\${status} == 'success' ? '完成' : '失败'", ctx))
        assertEquals("失败", eval.evaluate("\${status} != 'success' ? '完成' : '失败'", ctx))
    }

    @Test
    fun testExpressionEvaluator_stringMethods() {
        val eval = ExpressionEvaluator()
        val ctx = mapOf("msg" to "Hello World")

        assertEquals(true, eval.evaluate("\${msg} contains 'World'", ctx))
        assertEquals(true, eval.evaluate("\${msg} startsWith 'Hello'", ctx))
        assertEquals(true, eval.evaluate("\${msg} endsWith 'World'", ctx))
        assertEquals("HELLO WORLD", eval.evaluate("\${msg} upper()", ctx))
    }

    @Test
    fun testExpressionEvaluator_arithmetic() {
        val eval = ExpressionEvaluator()
        assertEquals(8.0, eval.evaluate("3 + 5"))
        assertEquals(15.0, eval.evaluate("3 * 5"))
        assertEquals(2.5, eval.evaluate("5 / 2"))
        assertEquals(1.0, eval.evaluate("10 % 3"))
    }

    @Test
    fun testExpressionEvaluator_nullCoalesce() {
        val eval = ExpressionEvaluator()
        val ctx = mapOf("a" to null, "b" to "default")
        assertEquals("default", eval.evaluate("\${a} ?? \${b}", ctx))
    }

    @Test
    fun testExpressionEvaluator_interpolate() {
        val eval = ExpressionEvaluator()
        val ctx = mapOf("name" to "Alice", "age" to 25)
        val result = eval.interpolate("My name is \${name} and I am \${age} years old", ctx)
        assertEquals("My name is Alice and I am 25 years old", result)
    }

    // ============ Cron 解析测试 ============

    @Test
    fun testCronParser_validExpressions() {
        assertTrue(CronParser.isValid("*/5 * * * *"))
        assertTrue(CronParser.isValid("0 9 * * 1-5"))
        assertTrue(CronParser.isValid("0 0 1 * *"))
        assertTrue(CronParser.isValid("30 14 15 6 *"))
    }

    @Test
    fun testCronParser_invalidExpressions() {
        assertFalse(CronParser.isValid(""))
        assertFalse(CronParser.isValid("* * *"))
        assertFalse(CronParser.isValid("* * * * * *"))
        assertFalse(CronParser.isValid("60 * * * *"))  // 分钟超出范围
    }

    @Test
    fun testCronParser_describe() {
        assertEquals("每分钟", CronParser.describe("* * * * *"))
        assertNotNull(CronParser.describe("0 9 * * 1-5"))
    }

    @Test
    fun testCronParser_nextRun() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.JANUARY, 15, 10, 30, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // 每分钟执行
        val nextMin = CronParser.nextRun("* * * * *", cal.timeInMillis)
        val expected = cal.apply { add(Calendar.MINUTE, 1); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(expected, nextMin)
    }

    // ============ DAG 校验测试 ============

    @Test
    fun testWorkflowValidator_validWorkflow() {
        val trigger = EnhancedNode(name = "T", type = EnhancedNodeType.TRIGGER,
            config = EnhancedNodeConfig(triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)))
        val exec = EnhancedNode(name = "E", type = EnhancedNodeType.EXECUTE,
            config = EnhancedNodeConfig(actionType = "log"))
        val end = EnhancedNode(name = "End", type = EnhancedNodeType.END)

        val wf = EnhancedWorkflow(
            name = "test", nodes = listOf(trigger, exec, end),
            connections = listOf(
                EnhancedConnection(trigger.id, exec.id),
                EnhancedConnection(exec.id, end.id)
            )
        )

        val result = WorkflowValidator().validate(wf)
        assertTrue("有效工作流应该通过校验", result.isValid)
        assertEquals(3, result.topologicalOrder.size)
    }

    @Test
    fun testWorkflowValidator_cycleDetection() {
        val n1 = EnhancedNode(name = "N1", type = EnhancedNodeType.EXECUTE, config = EnhancedNodeConfig(actionType = "log"))
        val n2 = EnhancedNode(name = "N2", type = EnhancedNodeType.EXECUTE, config = EnhancedNodeConfig(actionType = "log"))
        val n3 = EnhancedNode(name = "N3", type = EnhancedNodeType.EXECUTE, config = EnhancedNodeConfig(actionType = "log"))

        // 创建环: n1 -> n2 -> n3 -> n1
        val wf = EnhancedWorkflow(
            name = "cycle", nodes = listOf(n1, n2, n3),
            connections = listOf(
                EnhancedConnection(n1.id, n2.id),
                EnhancedConnection(n2.id, n3.id),
                EnhancedConnection(n3.id, n1.id)
            )
        )

        val result = WorkflowValidator().validate(wf)
        assertFalse("环工作流不应通过校验", result.isValid)
        assertTrue("应检测到环错误", result.errors.any { it.toString().contains("环") })
    }

    @Test
    fun testWorkflowValidator_noStartNode() {
        val n1 = EnhancedNode(name = "N1", type = EnhancedNodeType.EXECUTE, config = EnhancedNodeConfig(actionType = "log"))
        val wf = EnhancedWorkflow(name = "no-start", nodes = listOf(n1), connections = emptyList())
        val result = WorkflowValidator().validate(wf)
        assertFalse(result.isValid)
    }

    @Test
    fun testWorkflowValidator_danglingEdge() {
        val trigger = EnhancedNode(name = "T", type = EnhancedNodeType.TRIGGER,
            config = EnhancedNodeConfig(triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)))

        val wf = EnhancedWorkflow(
            name = "dangling", nodes = listOf(trigger),
            connections = listOf(EnhancedConnection(trigger.id, "non_existent_node"))
        )

        val result = WorkflowValidator().validate(wf)
        assertFalse("悬空边应导致校验失败", result.isValid)
    }

    // ============ 迁移测试 ============

    @Test
    fun testMigration_oldCoreFormat() {
        val oldJson = """
        {
            "name": "旧工作流",
            "description": "测试迁移",
            "nodes": [
                {"id": "n1", "name": "触发", "type": "TRIGGER", "config": {"triggerType": "MANUAL"}},
                {"id": "n2", "name": "执行", "type": "EXECUTE", "config": {"actionType": "log", "message": "hello"}}
            ],
            "connections": [
                {"sourceNodeId": "n1", "targetNodeId": "n2", "condition": "ON_SUCCESS"}
            ]
        }
        """.trimIndent()

        val result = WorkflowMigrationAdapter().migrateFromJson(oldJson)
        assertTrue("迁移应成功", result.isSuccess)
        val wf = result.workflow!!
        assertEquals("旧工作流", wf.name)
        assertEquals(2, wf.nodes.size)
        assertEquals(1, wf.connections.size)
        assertEquals(EnhancedNodeType.TRIGGER, wf.nodes[0].type)
        assertEquals(EnhancedNodeType.EXECUTE, wf.nodes[1].type)
    }

    @Test
    fun testMigration_oldDomainFormat() {
        val oldJson = """
        {
            "name": "域工作流",
            "nodes": [
                {"id": "s1", "type": "START", "label": "开始"},
                {"id": "a1", "type": "ACTION", "label": "动作", "config": {"actionType": "log"}},
                {"id": "e1", "type": "END", "label": "结束"}
            ],
            "edges": [
                {"sourceNodeId": "s1", "targetNodeId": "a1"},
                {"sourceNodeId": "a1", "targetNodeId": "e1"}
            ]
        }
        """.trimIndent()

        val result = WorkflowMigrationAdapter().migrateFromJson(oldJson)
        assertTrue(result.isSuccess)
        val wf = result.workflow!!
        assertEquals(3, wf.nodes.size)
        assertEquals(EnhancedNodeType.TRIGGER, wf.nodes[0].type)
        assertEquals(EnhancedNodeType.EXECUTE, wf.nodes[1].type)
        assertEquals(EnhancedNodeType.END, wf.nodes[2].type)
    }

    // ============ 序列化测试 ============

    @Test
    fun testSerializer_roundTrip() {
        val trigger = EnhancedNode(name = "T", type = EnhancedNodeType.TRIGGER,
            config = EnhancedNodeConfig(triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)))
        val exec = EnhancedNode(name = "E", type = EnhancedNodeType.EXECUTE,
            config = EnhancedNodeConfig(actionType = "log"))

        val wf = EnhancedWorkflow(
            name = "round-trip-test",
            nodes = listOf(trigger, exec),
            connections = listOf(EnhancedConnection(trigger.id, exec.id))
        )

        val serializer = WorkflowSerializer.getInstance()
        val json = serializer.toJson(wf)
        val result = serializer.fromJson(json)

        assertTrue(result.isSuccess)
        assertEquals(1, result.workflows.size)
        assertEquals("round-trip-test", result.workflows[0].name)
        assertEquals(2, result.workflows[0].nodes.size)
    }

    @Test
    fun testSerializer_compactFormat() {
        val trigger = EnhancedNode(name = "T", type = EnhancedNodeType.TRIGGER,
            config = EnhancedNodeConfig(triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)))
        val exec = EnhancedNode(name = "E", type = EnhancedNodeType.EXECUTE,
            config = EnhancedNodeConfig(actionType = "log"))

        val wf = EnhancedWorkflow(
            name = "compact-test",
            nodes = listOf(trigger, exec),
            connections = listOf(EnhancedConnection(trigger.id, exec.id))
        )

        val serializer = WorkflowSerializer.getInstance()
        val compact = serializer.toCompact(wf)
        val result = serializer.fromCompact(compact)

        assertTrue(result.isSuccess)
        assertEquals("compact-test", result.workflows[0].name)
    }

    @Test
    fun testSerializer_checksumValidation() {
        val wf = EnhancedWorkflow(name = "checksum-test", nodes = emptyList(), connections = emptyList())
        val serializer = WorkflowSerializer.getInstance()
        val json = serializer.toJson(wf)

        // 篡改 JSON（破坏校验和）
        val tampered = json.replace("checksum-test", "tampered-test")
        val result = serializer.fromJson(tampered)

        // 校验和应该不匹配
        assertFalse("篡改后应校验失败", result.isSuccess)
    }

    // ============ 监控测试 ============

    @Test
    fun testMonitor_recordsExecution() {
        val monitor = WorkflowMonitor.getInstance()
        monitor.reset()

        monitor.onExecutionStarted("t1", "wf1", "测试工作流")
        monitor.onNodeCompleted("t1", "n1", "EXECUTE", "log", true, 100)
        monitor.onExecutionCompleted("t1", "wf1", "测试工作流", true, 500, 1, null)

        val snapshot = monitor.currentSnapshot()!!
        assertEquals(1, snapshot.totals.totalExecutions)
        assertEquals(1, snapshot.totals.successCount)
        assertEquals(0, snapshot.totals.failureCount)
        assertEquals(1, snapshot.byWorkflow["wf1"]?.executionCount)
    }

    @Test
    fun testMonitor_errorDistribution() {
        val monitor = WorkflowMonitor.getInstance()
        monitor.reset()

        monitor.onExecutionStarted("t1", "wf1", "测试")
        monitor.onExecutionCompleted("t1", "wf1", "测试", false, 100, 0, "connection timeout")

        val snapshot = monitor.currentSnapshot()!!
        assertEquals(1, snapshot.totals.failureCount)
        assertTrue("应记录超时错误", snapshot.errorDistribution.containsKey("TIMEOUT"))
    }

    @Test
    fun testMonitor_prometheusExport() {
        val monitor = WorkflowMonitor.getInstance()
        monitor.reset()

        monitor.onExecutionStarted("t1", "wf1", "测试")
        monitor.onExecutionCompleted("t1", "wf1", "测试", true, 100, 1, null)

        val metrics = monitor.exportPrometheusMetrics()
        assertTrue(metrics.contains("workflow_executions_total"))
        assertTrue(metrics.contains("workflow_active_executions"))
        assertTrue(metrics.contains("workflow_max_concurrency"))
    }

    // ============ 回放测试 ============

    @Test
    fun testReplayer_emptySession() {
        val replayer = WorkflowReplayer()
        val session = replayer.createSession("non_existent_thread")
        assertNull("不存在的线程应返回 null", session)
    }
}
