package com.apex.agent.core.mcp

/**
 * MCP Server 集成模块
 * 
 * 提供以下功能�? * - MCPServerBridge: stdio 协议桥接，通过本地 Socket 实现
 * - ConversationBridgeTools: 对话桥接工具�? * - MCPCatalog: Nous-approved MCP 目录
 * 
 * 使用方法�? * 
 * 1. 启动 MCP 服务器桥接：
 *    ```kotlin
 *    val bridge = MCPServerBridge.getInstance(context)
 *    bridge.start()
 *    ```
 * 
 * 2. 使用对话桥接工具�? *    ```kotlin
 *    val tools = ConversationBridgeTools.getInstance(context)
 *    val result = tools.conversationsList()
 *    ```
 * 
 * 3. 浏览 MCP 目录�? *    ```kotlin
 *    val catalog = MCPCatalog.getInstance(context)
 *    val servers = catalog.getCatalog()
 *    ```
 */
object MCPServerIntegration {
    /**
     * 模块版本
     */
    const val VERSION = "1.0.0"
    
    /**
     * 默认 MCP 端口
     */
    const val DEFAULT_PORT = 4732
}
