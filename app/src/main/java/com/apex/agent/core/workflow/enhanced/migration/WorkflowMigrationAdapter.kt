package com.apex.agent.core.workflow.enhanced.migration

import com.apex.agent.core.workflow.enhanced.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作流迁移适配器
 *
 * 将旧版工作流定义迁移到 EnhancedWorkflow 格式
 *
 * 支持迁移源格式：
 * 1. 旧版 Core Workflow（com.apex.core.workflow，TRIGGER/EXECUTE/CONDITION/LOGIC/EXTRACT）
 * 2. 旧版 Skill Workflow（core.tools.skill.WorkflowNode）
 * 3. 旧版 Domain Workflow（domain.model.WorkflowModels）
 * 4. n8n workflow JSON 导出
 * 5. Dify DSL 导出
 *
 * 迁移策略：
 * - 保留语义，转换节点类型
 * - 自动生成连接（基于 source/target 节点 ID）
 * - 补全缺失的默认配置
 * - 输出迁移报告（含 warning 列表）
 */
class WorkflowMigrationAdapter {

    data class MigrationResult(
        val workflow: EnhancedWorkflow?,
        val warnings: List<String>,
        val errors: List<String>
    ) {
        val isSuccess: Boolean get() = workflow != null && errors.isEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 从 JSON 字符串迁移（自动识别源格式）
     */
    fun migrateFromJson(jsonStr: String): MigrationResult {
        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            when {
                isN8nFormat(obj) -> migrateFromN8n(obj)
                isDifyFormat(obj) -> migrateFromDify(obj)
                isOldCoreFormat(obj) -> migrateFromOldCore(obj)
                isOldSkillFormat(obj) -> migrateFromOldSkill(obj)
                isOldDomainFormat(obj) -> migrateFromOldDomain(obj)
                else -> MigrationResult(null, emptyList(), listOf("无法识别的工作流 JSON 格式"))
            }
        } catch (e: Exception) {
            MigrationResult(null, emptyList(), listOf("解析失败: ${e.message}"))
        }
    }

    /**
     * 从旧版 Core Workflow 迁移
     * 节点类型：TRIGGER / EXECUTE / CONDITION / LOGIC / EXTRACT
     */
    fun migrateFromOldCore(obj: JsonObject): MigrationResult {
        val warnings = mutableListOf<String>()
        val nodes = mutableListOf<EnhancedNode>()
        val connections = mutableListOf<EnhancedConnection>()

        val oldNodes = obj["nodes"] as? JsonArray
        val oldConns = obj["connections"] as? JsonArray

        val idMap = mutableMapOf<String, String>()  // 旧ID -> 新ID

        oldNodes?.forEach { nodeElem ->
            val nodeObj = nodeElem.jsonObject
            val oldId = nodeObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newId = "enode_${System.currentTimeMillis()}_${nodes.size}"
            idMap[oldId] = newId

            val name = nodeObj["name"]?.jsonPrimitive?.contentOrNull ?: "未命名节点"
            val typeStr = nodeObj["type"]?.jsonPrimitive?.contentOrNull ?: "EXECUTE"
            val newType = when (typeStr.uppercase()) {
                "TRIGGER" -> EnhancedNodeType.TRIGGER
                "EXECUTE" -> EnhancedNodeType.EXECUTE
                "CONDITION" -> EnhancedNodeType.CONDITION
                "LOGIC" -> EnhancedNodeType.LOGIC
                "EXTRACT" -> EnhancedNodeType.EXTRACT
                else -> {
                    warnings.add("未知节点类型 $typeStr，降级为 EXECUTE")
                    EnhancedNodeType.EXECUTE
                }
            }

            val config = parseOldNodeConfig(nodeObj, newType)
            val position = parsePosition(nodeObj["position"])

            nodes.add(EnhancedNode(
                id = newId,
                name = name,
                type = newType,
                config = config,
                position = position
            ))
        }

        oldConns?.forEach { connElem ->
            val connObj = connElem.jsonObject
            val sourceId = connObj["sourceNodeId"]?.jsonPrimitive?.contentOrNull
            val targetId = connObj["targetNodeId"]?.jsonPrimitive?.contentOrNull
            if (sourceId == null || targetId == null) return@forEach

            val newSource = idMap[sourceId] ?: run {
                warnings.add("连接的源节点 $sourceId 未找到对应")
                return@forEach
            }
            val newTarget = idMap[targetId] ?: run {
                warnings.add("连接的目标节点 $targetId 未找到对应")
                return@forEach
            }

            val condStr = connObj["condition"]?.jsonPrimitive?.contentOrNull ?: "ON_SUCCESS"
            val newCond = when (condStr.uppercase()) {
                "ON_SUCCESS", "SUCCESS" -> ConnectionConditionDef.ON_SUCCESS
                "ON_ERROR", "ERROR" -> ConnectionConditionDef.ON_ERROR
                "TRUE" -> ConnectionConditionDef.TRUE
                "FALSE" -> ConnectionConditionDef.FALSE
                "ALWAYS", "ANY" -> ConnectionConditionDef.ALWAYS
                else -> ConnectionConditionDef.ON_SUCCESS
            }

            connections.add(EnhancedConnection(
                sourceNodeId = newSource,
                targetNodeId = newTarget,
                condition = newCond
            ))
        }

        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "迁移的工作流"
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "从旧版 Core Workflow 迁移"

        val workflow = EnhancedWorkflow(
            name = name,
            description = description,
            nodes = nodes,
            connections = connections,
            metadata = mapOf("migratedFrom" to "oldCore", "migratedAt" to System.currentTimeMillis().toString())
        )
        return MigrationResult(workflow, warnings, emptyList())
    }

    /**
     * 从旧版 Skill Workflow 迁移
     */
    fun migrateFromOldSkill(obj: JsonObject): MigrationResult {
        val warnings = mutableListOf<String>()
        val nodes = mutableListOf<EnhancedNode>()
        val connections = mutableListOf<EnhancedConnection>()

        val defObj = obj["definition"]?.jsonObject ?: obj
        val oldNodes = defObj["nodes"] as? JsonArray ?: run {
            return MigrationResult(null, emptyList(), listOf("Skill Workflow 缺少 nodes 字段"))
        }
        val oldConns = defObj["connections"] as? JsonArray ?: JsonArray(emptyList())

        val idMap = mutableMapOf<String, String>()

        oldNodes.forEach { nodeElem ->
            val nodeObj = nodeElem.jsonObject
            val oldId = nodeObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newId = "enode_${System.currentTimeMillis()}_${nodes.size}"
            idMap[oldId] = newId

            val name = nodeObj["name"]?.jsonPrimitive?.contentOrNull ?: "节点"
            val typeStr = nodeObj["type"]?.jsonPrimitive?.contentOrNull ?: "EXECUTE"
            val newType = when (typeStr.uppercase()) {
                "TRIGGER" -> EnhancedNodeType.TRIGGER
                "EXECUTE" -> EnhancedNodeType.EXECUTE
                "CONDITION" -> EnhancedNodeType.CONDITION
                "LOGIC" -> EnhancedNodeType.LOGIC
                "EXTRACT" -> EnhancedNodeType.EXTRACT
                else -> EnhancedNodeType.EXECUTE.also { warnings.add("未知类型 $typeStr 降级为 EXECUTE") }
            }

            val config = parseOldNodeConfig(nodeObj, newType)
            val position = parsePosition(nodeObj["position"])

            nodes.add(EnhancedNode(
                id = newId, name = name, type = newType, config = config, position = position
            ))
        }

        oldConns.forEach { connElem ->
            val connObj = connElem.jsonObject
            val sourceId = connObj["sourceNodeId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val targetId = connObj["targetNodeId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newSource = idMap[sourceId] ?: return@forEach
            val newTarget = idMap[targetId] ?: return@forEach

            val condStr = connObj["condition"]?.jsonPrimitive?.contentOrNull ?: "ON_SUCCESS"
            val newCond = ConnectionConditionDef.fromString(condStr)

            connections.add(EnhancedConnection(sourceNodeId = newSource, targetNodeId = newTarget, condition = newCond))
        }

        val name = defObj["name"]?.jsonPrimitive?.contentOrNull ?: "迁移的工作流"
        val description = defObj["description"]?.jsonPrimitive?.contentOrNull ?: "从旧版 Skill Workflow 迁移"

        val workflow = EnhancedWorkflow(
            name = name, description = description,
            nodes = nodes, connections = connections,
            metadata = mapOf("migratedFrom" to "oldSkill", "migratedAt" to System.currentTimeMillis().toString())
        )
        return MigrationResult(workflow, warnings, emptyList())
    }

    /**
     * 从旧版 Domain Workflow 迁移
     * 节点类型：START / END / ACTION / CONDITION / PARALLEL / LOOP / DELAY / SCRIPT / SUB_WORKFLOW / NOTIFICATION
     */
    fun migrateFromOldDomain(obj: JsonObject): MigrationResult {
        val warnings = mutableListOf<String>()
        val nodes = mutableListOf<EnhancedNode>()
        val connections = mutableListOf<EnhancedConnection>()

        val oldNodes = obj["nodes"] as? JsonArray ?: JsonArray(emptyList())
        val oldEdges = obj["edges"] as? JsonArray ?: JsonArray(emptyList())

        val idMap = mutableMapOf<String, String>()

        oldNodes.forEach { nodeElem ->
            val nodeObj = nodeElem.jsonObject
            val oldId = nodeObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newId = "enode_${System.currentTimeMillis()}_${nodes.size}"
            idMap[oldId] = newId

            val label = nodeObj["label"]?.jsonPrimitive?.contentOrNull ?: "节点"
            val typeStr = nodeObj["type"]?.jsonPrimitive?.contentOrNull ?: "ACTION"
            val newType = when (typeStr.uppercase()) {
                "START" -> EnhancedNodeType.TRIGGER
                "END" -> EnhancedNodeType.END
                "ACTION" -> EnhancedNodeType.EXECUTE
                "CONDITION" -> EnhancedNodeType.CONDITION
                "PARALLEL" -> EnhancedNodeType.FAN_OUT
                "LOOP" -> EnhancedNodeType.LOOP
                "DELAY" -> EnhancedNodeType.DELAY
                "SCRIPT" -> EnhancedNodeType.EXECUTE
                "SUB_WORKFLOW" -> EnhancedNodeType.SUB_WORKFLOW
                "NOTIFICATION" -> EnhancedNodeType.EXECUTE
                else -> EnhancedNodeType.EXECUTE.also { warnings.add("未知类型 $typeStr 降级为 EXECUTE") }
            }

            val oldConfig = (nodeObj["config"] as? JsonObject)?.let { c ->
                c.entries.associate { it.key to (it.value.jsonPrimitive.contentOrNull ?: "") }
            } ?: emptyMap()

            val config = when (newType) {
                EnhancedNodeType.EXECUTE -> EnhancedNodeConfig(
                    actionType = oldConfig["actionType"] ?: "log",
                    actionConfig = oldConfig
                )
                EnhancedNodeType.DELAY -> EnhancedNodeConfig(
                    delayMs = oldConfig["delayMs"]?.toLongOrNull()
                )
                EnhancedNodeType.SUB_WORKFLOW -> EnhancedNodeConfig(
                    subWorkflowConfig = SubWorkflowConfigDef(
                        subWorkflowId = oldConfig["subWorkflowId"] ?: ""
                    )
                )
                else -> EnhancedNodeConfig()
            }

            nodes.add(EnhancedNode(id = newId, name = label, type = newType, config = config))
        }

        oldEdges.forEach { edgeElem ->
            val edgeObj = edgeElem.jsonObject
            val sourceId = edgeObj["sourceNodeId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val targetId = edgeObj["targetNodeId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newSource = idMap[sourceId] ?: return@forEach
            val newTarget = idMap[targetId] ?: return@forEach
            val condStr = edgeObj["condition"]?.jsonPrimitive?.contentOrNull
            val newCond = if (condStr == null) ConnectionConditionDef.ON_SUCCESS
                         else ConnectionConditionDef.fromString(condStr)
            connections.add(EnhancedConnection(sourceNodeId = newSource, targetNodeId = newTarget, condition = newCond))
        }

        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "迁移的工作流"
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "从旧版 Domain Workflow 迁移"

        val workflow = EnhancedWorkflow(
            name = name, description = description,
            nodes = nodes, connections = connections,
            metadata = mapOf("migratedFrom" to "oldDomain", "migratedAt" to System.currentTimeMillis().toString())
        )
        return MigrationResult(workflow, warnings, emptyList())
    }

    /**
     * 从 n8n workflow JSON 迁移
     * n8n 格式：{ name, nodes: [{ name, type, parameters, position }], connections: {...} }
     */
    fun migrateFromN8n(obj: JsonObject): MigrationResult {
        val warnings = mutableListOf<String>()
        val nodes = mutableListOf<EnhancedNode>()
        val connections = mutableListOf<EnhancedConnection>()

        val n8nNodes = obj["nodes"] as? JsonArray ?: JsonArray(emptyList())
        val n8nConns = obj["connections"] as? JsonObject ?: JsonObject(emptyMap())

        val idMap = mutableMapOf<String, String>()

        n8nNodes.forEach { nodeElem ->
            val nodeObj = nodeElem.jsonObject
            val name = nodeObj["name"]?.jsonPrimitive?.contentOrNull ?: "节点"
            val n8nType = nodeObj["type"]?.jsonPrimitive?.contentOrNull ?: "n8n-nodes-base.noOp"

            // n8n 节点类型映射
            val (newType, warning) = when {
                n8nType.contains("trigger", ignoreCase = true) -> EnhancedNodeType.TRIGGER to null
                n8nType.contains("if", ignoreCase = true) -> EnhancedNodeType.CONDITION to null
                n8nType.contains("switch", ignoreCase = true) -> EnhancedNodeType.CONDITION to null
                n8nType.contains("merge", ignoreCase = true) -> EnhancedNodeType.FAN_IN to null
                n8nType.contains("split", ignoreCase = true) -> EnhancedNodeType.FAN_OUT to null
                n8nType.contains("loop", ignoreCase = true) -> EnhancedNodeType.LOOP to null
                n8nType.contains("wait", ignoreCase = true) -> EnhancedNodeType.DELAY to null
                n8nType.contains("executeWorkflow", ignoreCase = true) -> EnhancedNodeType.SUB_WORKFLOW to null
                n8nType.contains("noOp", ignoreCase = true) -> EnhancedNodeType.END to null
                else -> EnhancedNodeType.EXECUTE to "未知 n8n 类型 $n8nType，映射为 EXECUTE"
            }
            warning?.let { warnings.add(it) }

            val newId = "enode_${System.currentTimeMillis()}_${nodes.size}"
            idMap[name] = newId  // n8n 用 name 作为连接引用

            val parameters = (nodeObj["parameters"] as? JsonObject)?.let { p ->
                p.entries.associate { it.key to (it.value.jsonPrimitive.contentOrNull ?: "") }
            } ?: emptyMap()

            val config = when (newType) {
                EnhancedNodeType.TRIGGER -> EnhancedNodeConfig(
                    triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)
                )
                EnhancedNodeType.EXECUTE -> EnhancedNodeConfig(
                    actionType = parameters["resource"] ?: parameters["operation"] ?: "log",
                    actionConfig = parameters
                )
                else -> EnhancedNodeConfig()
            }

            val position = parsePosition(nodeObj["position"])
            nodes.add(EnhancedNode(id = newId, name = name, type = newType, config = config, position = position))
        }

        // n8n connections 格式: { "源节点名": { "main": [[{ "node": "目标节点名", "type": "main", "index": 0 }]] } }
        n8nConns.forEach { (sourceName, connData) ->
            val sourceId = idMap[sourceName] ?: return@forEach
            val mainConns = (connData as? JsonObject)?.get("main") as? JsonArray ?: return@forEach
            mainConns.forEach { outputArray ->
                (outputArray as? JsonArray)?.forEach { outputElem ->
                    val outputObj = outputElem.jsonObject
                    val targetName = outputObj["node"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val targetId = idMap[targetName] ?: return@forEach
                    val condType = outputObj["type"]?.jsonPrimitive?.contentOrNull ?: "main"
                    val newCond = if (condType == "main") ConnectionConditionDef.ON_SUCCESS
                                 else ConnectionConditionDef.ALWAYS
                    connections.add(EnhancedConnection(sourceNodeId = sourceId, targetNodeId = targetId, condition = newCond))
                }
            }
        }

        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "从 n8n 迁移的工作流"
        val workflow = EnhancedWorkflow(
            name = name, description = "从 n8n workflow JSON 迁移",
            nodes = nodes, connections = connections,
            metadata = mapOf("migratedFrom" to "n8n", "migratedAt" to System.currentTimeMillis().toString())
        )
        return MigrationResult(workflow, warnings, emptyList())
    }

    /**
     * 从 Dify DSL 迁移
     */
    fun migrateFromDify(obj: JsonObject): MigrationResult {
        val warnings = mutableListOf<String>()
        val nodes = mutableListOf<EnhancedNode>()
        val connections = mutableListOf<EnhancedConnection>()

        val difyGraph = obj["graph"]?.jsonObject ?: obj
        val difyNodes = difyGraph["nodes"] as? JsonArray ?: JsonArray(emptyList())
        val difyEdges = difyGraph["edges"] as? JsonArray ?: JsonArray(emptyList())

        val idMap = mutableMapOf<String, String>()

        difyNodes.forEach { nodeElem ->
            val nodeObj = nodeElem.jsonObject
            val oldId = nodeObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newId = "enode_${System.currentTimeMillis()}_${nodes.size}"
            idMap[oldId] = newId

            val title = (nodeObj["data"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull ?: "节点"
            val difyType = nodeObj["type"]?.jsonPrimitive?.contentOrNull ?: "custom"

            val newType = when {
                difyType == "start" || difyType.contains("start", true) -> EnhancedNodeType.TRIGGER
                difyType == "end" || difyType.contains("end", true) -> EnhancedNodeType.END
                difyType == "if-else" || difyType.contains("if", true) -> EnhancedNodeType.CONDITION
                difyType == "iteration" || difyType.contains("loop", true) -> EnhancedNodeType.LOOP
                difyType.contains("llm", true) -> EnhancedNodeType.EXECUTE
                difyType.contains("code", true) -> EnhancedNodeType.EXECUTE
                difyType.contains("http", true) -> EnhancedNodeType.EXECUTE
                difyType.contains("tool", true) -> EnhancedNodeType.EXECUTE
                difyType.contains("knowledge", true) -> EnhancedNodeType.EXECUTE
                else -> EnhancedNodeType.EXECUTE.also { warnings.add("未知 Dify 类型 $difyType") }
            }

            val config = when (newType) {
                EnhancedNodeType.TRIGGER -> EnhancedNodeConfig(
                    triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)
                )
                EnhancedNodeType.EXECUTE -> EnhancedNodeConfig(
                    actionType = difyType,
                    actionConfig = emptyMap()
                )
                else -> EnhancedNodeConfig()
            }

            val position = parsePosition(nodeObj["position"])
            nodes.add(EnhancedNode(id = newId, name = title, type = newType, config = config, position = position))
        }

        difyEdges.forEach { edgeElem ->
            val edgeObj = edgeElem.jsonObject
            val sourceId = edgeObj["source"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val targetId = edgeObj["target"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val newSource = idMap[sourceId] ?: return@forEach
            val newTarget = idMap[targetId] ?: return@forEach
            val sourceHandle = edgeObj["sourceHandle"]?.jsonPrimitive?.contentOrNull
            val newCond = when (sourceHandle?.lowercase()) {
                "true", "if_true" -> ConnectionConditionDef.TRUE
                "false", "if_false" -> ConnectionConditionDef.FALSE
                else -> ConnectionConditionDef.ON_SUCCESS
            }
            connections.add(EnhancedConnection(sourceNodeId = newSource, targetNodeId = newTarget, condition = newCond))
        }

        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "从 Dify 迁移的工作流"
        val workflow = EnhancedWorkflow(
            name = name, description = "从 Dify DSL 迁移",
            nodes = nodes, connections = connections,
            metadata = mapOf("migratedFrom" to "dify", "migratedAt" to System.currentTimeMillis().toString())
        )
        return MigrationResult(workflow, warnings, emptyList())
    }

    // ============ 格式识别 ============

    private fun isN8nFormat(obj: JsonObject): Boolean {
        val hasNodes = obj["nodes"] is JsonArray
        val hasConns = obj["connections"] is JsonObject
        val firstNode = (obj["nodes"] as? JsonArray)?.firstOrNull()?.jsonObject
        val isN8nType = firstNode?.get("type")?.jsonPrimitive?.contentOrNull?.startsWith("n8n-nodes") == true
        return hasNodes && hasConns && isN8nType
    }

    private fun isDifyFormat(obj: JsonObject): Boolean {
        val hasGraph = obj["graph"] is JsonObject
        val graphObj = obj["graph"]?.jsonObject
        val hasNodesInGraph = graphObj?.get("nodes") is JsonArray
        val hasEdgesInGraph = graphObj?.get("edges") is JsonArray
        return hasGraph && hasNodesInGraph && hasEdgesInGraph
    }

    private fun isOldCoreFormat(obj: JsonObject): Boolean {
        val nodes = obj["nodes"] as? JsonArray ?: return false
        val firstNode = nodes.firstOrNull()?.jsonObject ?: return false
        val type = firstNode["type"]?.jsonPrimitive?.contentOrNull ?: return false
        return type.uppercase() in setOf("TRIGGER", "EXECUTE", "CONDITION", "LOGIC", "EXTRACT")
    }

    private fun isOldSkillFormat(obj: JsonObject): Boolean {
        return obj["definition"] is JsonObject ||
               (obj["nodes"] is JsonArray && obj["connections"] is JsonArray &&
                (obj["nodes"] as JsonArray).firstOrNull()?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull in
                setOf("TRIGGER", "EXECUTE", "CONDITION", "LOGIC", "EXTRACT"))
    }

    private fun isOldDomainFormat(obj: JsonObject): Boolean {
        val nodes = obj["nodes"] as? JsonArray ?: return false
        val firstNode = nodes.firstOrNull()?.jsonObject ?: return false
        val type = firstNode["type"]?.jsonPrimitive?.contentOrNull ?: return false
        return type.uppercase() in setOf("START", "END", "ACTION", "PARALLEL", "SCRIPT", "NOTIFICATION")
    }

    // ============ 辅助方法 ============

    private fun parseOldNodeConfig(nodeObj: JsonObject, type: EnhancedNodeType): EnhancedNodeConfig {
        val configObj = nodeObj["config"] as? JsonObject
        val configMap = configObj?.entries?.associate { e ->
            e.key to (e.value.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyMap()

        return when (type) {
            EnhancedNodeType.TRIGGER -> {
                val triggerTypeStr = configMap["triggerType"] ?: "MANUAL"
                val triggerType = runCatching { TriggerTypeDef.valueOf(triggerTypeStr.uppercase()) }
                    .getOrDefault(TriggerTypeDef.MANUAL)
                EnhancedNodeConfig(
                    triggerConfig = TriggerConfigDef(triggerType = triggerType)
                )
            }
            EnhancedNodeType.EXECUTE -> EnhancedNodeConfig(
                actionType = configMap["actionType"] ?: "log",
                actionConfig = configMap.filterKeys { it != "actionType" }
            )
            EnhancedNodeType.CONDITION -> EnhancedNodeConfig(
                left = configMap["left"]?.let { ParameterValueDef.static(it) },
                right = configMap["right"]?.let { ParameterValueDef.static(it) },
                operator = configMap["operator"] ?: "=="
            )
            EnhancedNodeType.LOGIC -> EnhancedNodeConfig(
                operator = configMap["operator"] ?: "AND"
            )
            EnhancedNodeType.EXTRACT -> EnhancedNodeConfig(
                extractMode = runCatching { ExtractModeDef.valueOf(configMap["mode"] ?: "REGEX") }
                    .getOrDefault(ExtractModeDef.REGEX),
                expression = configMap["expression"]
            )
            else -> EnhancedNodeConfig()
        }
    }

    private fun parsePosition(elem: kotlinx.serialization.json.JsonElement?): NodePositionDef {
        val arr = elem as? JsonArray ?: return NodePositionDef()
        return if (arr.size >= 2) {
            NodePositionDef(
                x = arr[0].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: 0f,
                y = arr[1].jsonPrimitive.contentOrNull?.toFloatOrNull() ?: 0f
            )
        } else NodePositionDef()
    }
}
