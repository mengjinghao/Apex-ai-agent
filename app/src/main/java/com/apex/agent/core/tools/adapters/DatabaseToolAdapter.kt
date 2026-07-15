package com.apex.core.tools.adapters

import com.apex.core.tools.ToolAdapter
import com.apex.core.tools.ToolParameter
import com.apex.core.tools.ToolResultData
import com.apex.core.tools.StringResultData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据库工具适配器，支持常见的数据库操作
 * 优化版本：添加连接管理、查询缓存、参数验证等功能
 */
class DatabaseToolAdapter : ToolAdapter {

    private val connections = ConcurrentHashMap<String, Connection>()
    
    // 简单的查询缓存
    private val queryCache = mutableMapOf<String, CachedQueryResult>()
        private val MAX_CACHE_SIZE = 50
    private val CACHE_EXPIRE_TIME = 5 * 60 * 1000L // 5分钟
    override fun getName(): String {
        return "database"
    }

    override fun getDescription(): String {
        return "执行数据库操作，支持查询、插入、更新和删除等操作，包含连接管理和查询缓�?
    }

    override suspend fun execute(parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val action = parameters["action"] as? String 
            ?: return@withContext StringResultData("错误：缺少action参数")
        val connectionId = parameters["connection_id"] as? String ?: "default"

        try {
            when (action) {
                "connect" -> connect(connectionId, parameters)
                "query" -> query(connectionId, parameters)
                "insert" -> insert(connectionId, parameters)
                "update" -> update(connectionId, parameters)
                "delete" -> delete(connectionId, parameters)
                "disconnect" -> disconnect(connectionId)
                "list_connections" -> listConnections()
                "clear_cache" -> clearCache()
                else -> StringResultData("错误：不支持的action: ${action}，可用操作：connect, query, insert, update, delete, disconnect, list_connections, clear_cache")
            }
        } catch (e: SQLException) {
            StringResultData("数据库操作失败：${e.message}\nSQL状态：${e.sqlState}\n错误代码，{e.errorCode}")
        } catch (e: Exception) {
            StringResultData("错误，{e.message}")
        }
    }

    override fun getParameters(): List<ToolParameter> {
        return listOf(
            ToolParameter("action", "string", "操作类型：connect, query, insert, update, delete, disconnect, list_connections, clear_cache", true),
            ToolParameter("connection_id", "string", "连接ID，用于管理多个数据库连接（默认：default�? false, "default"),
            ToolParameter("url", "string", "数据库连接URL（connect操作需要）", false),
            ToolParameter("username", "string", "数据库用户名（connect操作需要）", false),
            ToolParameter("password", "string", "数据库密码（connect操作需要）", false),
            ToolParameter("driver", "string", "JDBC驱动类名（connect操作可选，默认：com.mysql.cj.jdbc.Driver�? false, "com.mysql.cj.jdbc.Driver"),
            ToolParameter("sql", "string", "SQL语句（query, insert, update, delete操作需要）", false),
            ToolParameter("params", "array", "SQL参数（可选）", false),
            ToolParameter("use_cache", "boolean", "是否使用查询缓存（query操作可选，默认：true�? false, true),
            ToolParameter("timeout", "int", "查询超时时间（秒，默认：30�? false, 30)
        )
    }

    override fun isAvailable(): Boolean {
        return true
    }
        private suspend fun connect(connectionId: String, parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val url = parameters["url"] as? String 
            ?: return@withContext StringResultData("错误：缺少url参数")
        val username = parameters["username"] as? String ?: ""
        val password = parameters["password"] as? String ?: ""
        val driver = parameters["driver"] as? String ?: "com.mysql.cj.jdbc.Driver"

        try {
            // 如果连接已存在，先关�?           connections[connectionId]?.let {
    if (!it.isClosed) {
                    it.close()
                }
            }

            // 加载驱动
            Class.forName(driver)
            
            // 创建连接
    val connection = DriverManager.getConnection(url, username, password)
            
            // 设置连接属�?           connection.autoCommit = true
            
            // 保存连接
            connections[connectionId] = connection
            
            StringResultData("成功连接到数据库，连接ID，connectionId")
        } catch (e: ClassNotFoundException) {
            StringResultData("驱动加载失败，{e.message}，请确认驱动类名是否正确")
        } catch (e: SQLException) {
            StringResultData("连接失败，{e.message}")
        }
    }
        private suspend fun query(connectionId: String, parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val sql = parameters["sql"] as? String 
            ?: return@withContext StringResultData("错误：缺少sql参数")
        val params = parameters["params"] as? List<*> ?: emptyList()
        val useCache = parameters["use_cache"] as? Boolean ?: true
        val timeout = (parameters["timeout"] as? Int ?: 30)
        val connection = connections[connectionId] 
            ?: return@withContext StringResultData("错误：未连接到数据库，请先执行connect操作")
        if (connection.isClosed) {
            return@withContext StringResultData("错误：连接已关闭，请重新连接")
        }

        // 检查缓�?
    val cacheKey = "${connectionId}:${sql}:${params.joinToString(",")}"
        if (useCache) {
            queryCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRE_TIME) {
                    return@withContext StringResultData("[缓存] 查询结果:\n${cached.result}")
                } else {
                    queryCache.remove(cacheKey)
                }
            }
        }

        try {
            val statement = connection.prepareStatement(sql).apply {
                queryTimeout = timeout
                params.forEachIndexed { index, param ->
                    setObject(index + 1, param)
                }
            }
        val resultSet = statement.executeQuery()
        val result = StringBuilder()
        if (resultSet != null) {
                val metaData = resultSet.metaData
                val columnCount = metaData.columnCount

                // 输出列名
    for (i in 1..columnCount) {
                    result.append(metaData.getColumnName(i))
        if (i < columnCount) result.append("\t")
                }
                result.append("\n")

                // 输出分隔�?
    for (i in 1..columnCount) {
                    val columnNameLength = metaData.getColumnName(i).length
                    result.append("-".repeat(columnNameLength))
        if (i < columnCount) result.append("\t")
                }
                result.append("\n")

                // 输出数据
    var rowCount = 0
                while (resultSet.next()) {
                    for (i in 1..columnCount) {
                        val value = resultSet.getObject(i)
                        result.append(value ?: "NULL")
        if (i < columnCount) result.append("\t")
                    }
                    result.append("\n")
                    rowCount++
                }
                result.append("\n，的${rowCount} �?
            }

            statement.close()

            // 缓存结果
    if (useCache) {
                if (queryCache.size >= MAX_CACHE_SIZE) {
                    val oldestKey = queryCache.keys.firstOrNull()
                    oldestKey?.let { queryCache.remove(it) }
                }
                queryCache[cacheKey] = CachedQueryResult(result.toString(), System.currentTimeMillis())
            }

            StringResultData("查询成功:\n${result.toString()}")
        } catch (e: SQLException) {
            StringResultData("查询失败，{e.message}")
        }
    }
        private suspend fun insert(connectionId: String, parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val sql = parameters["sql"] as? String 
            ?: return@withContext StringResultData("错误：缺少sql参数")
        val params = parameters["params"] as? List<*> ?: emptyList()
        val timeout = (parameters["timeout"] as? Int ?: 30)
        val connection = connections[connectionId] 
            ?: return@withContext StringResultData("错误：未连接到数据库，请先执行connect操作")
        if (connection.isClosed) {
            return@withContext StringResultData("错误：连接已关闭，请重新连接")
        }

        try {
            val statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).apply {
                queryTimeout = timeout
                params.forEachIndexed { index, param ->
                    setObject(index + 1, param)
                }
            }
        val affectedRows = statement.executeUpdate()
        val generatedKeys = statement.generatedKeys

            val result = StringBuilder()
            result.append("${affectedRows}")
        if (generatedKeys != null && generatedKeys.next()) {
                result.append("\n生成的ID，{generatedKeys.getObject(1)}")
            }

            statement.close()
            StringResultData(result.toString())
        } catch (e: SQLException) {
            StringResultData("插入失败，{e.message}")
        }
    }
        private suspend fun update(connectionId: String, parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val sql = parameters["sql"] as? String 
            ?: return@withContext StringResultData("错误：缺少sql参数")
        val params = parameters["params"] as? List<*> ?: emptyList()
        val timeout = (parameters["timeout"] as? Int ?: 30)
        val connection = connections[connectionId] 
            ?: return@withContext StringResultData("错误：未连接到数据库，请先执行connect操作")
        if (connection.isClosed) {
            return@withContext StringResultData("错误：连接已关闭，请重新连接")
        }

        try {
            val statement = connection.prepareStatement(sql).apply {
                queryTimeout = timeout
                params.forEachIndexed { index, param ->
                    setObject(index + 1, param)
                }
            }
        val affectedRows = statement.executeUpdate()
            statement.close()

            StringResultData("${affectedRows}")
        } catch (e: SQLException) {
            StringResultData("更新失败，{e.message}")
        }
    }
        private suspend fun delete(connectionId: String, parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val sql = parameters["sql"] as? String 
            ?: return@withContext StringResultData("错误：缺少sql参数")
        val params = parameters["params"] as? List<*> ?: emptyList()
        val timeout = (parameters["timeout"] as? Int ?: 30)
        val connection = connections[connectionId] 
            ?: return@withContext StringResultData("错误：未连接到数据库，请先执行connect操作")
        if (connection.isClosed) {
            return@withContext StringResultData("错误：连接已关闭，请重新连接")
        }

        try {
            val statement = connection.prepareStatement(sql).apply {
                queryTimeout = timeout
                params.forEachIndexed { index, param ->
                    setObject(index + 1, param)
                }
            }
        val affectedRows = statement.executeUpdate()
            statement.close()

            StringResultData("${affectedRows}")
        } catch (e: SQLException) {
            StringResultData("删除失败，{e.message}")
        }
    }
        private suspend fun disconnect(connectionId: String): ToolResultData = withContext(Dispatchers.IO) {
        connections[connectionId]?.let {
            try {
                if (!it.isClosed) {
                    it.close()
                }
                connections.remove(connectionId)
                StringResultData("成功断开数据库连接，连接ID，connectionId")
            } catch (e: Exception) {
                StringResultData("断开连接失败，{e.message}")
            }
        } ?: StringResultData("错误：未找到连接ID，connectionId")
    }
        private fun listConnections(): ToolResultData {
        val activeConnections = connections.filterValues { !it.isClosed }
        val closedConnections = connections.filterValues { it.isClosed }
        val result = StringBuilder()
        result.append("数据库连接列表\n")
        result.append("活跃连接(${activeConnections.size}):\n")
        activeConnections.keys.forEach { id ->
            result.append("  - ${id}\n")
        }
        if (closedConnections.isNotEmpty()) {
            result.append("\n已关闭连�?{closedConnections.size}):\n")
            closedConnections.keys.forEach { id ->
                result.append("  - ${id}\n")
            }
        }
        
        result.append("\n缓存查询�?${queryCache.size}")
        return StringResultData(result.toString())
    }
        private fun clearCache(): ToolResultData {
        val cacheSize = queryCache.size
        queryCache.clear()
        return StringResultData("成功清除查询缓存，清除了 ${cacheSize} 条记�?
    }
        private data class CachedQueryResult(
        val result: String,
        val timestamp: Long
    )
}
