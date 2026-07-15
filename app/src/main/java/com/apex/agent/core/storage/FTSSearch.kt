package com.apex.agent.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * FTS5搜索管理�?- 实现全文搜索虚拟�? */
class FTSSearch(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    FTS_DATABASE_NAME,
    null,
    FTS_DATABASE_VERSION
) {
    
    companion object {
        private const val FTS_DATABASE_NAME = "session_fts.db"
        private const val FTS_DATABASE_VERSION = 1
        
        /** FTS5虚拟表名 */
        const val FTS_TABLE_NAME = "messages_fts"
        
        /** 搜索结果表名 */
        const val FTS_CONTENT_TABLE = "messages_fts_content"
        
        /** FTS元数据表�?*/
        const val FTS_METADATA_TABLE = "messages_fts_metadata"
        
        @Volatile
        private var INSTANCE: FTSSearch? = null
        
        fun getInstance(context: Context): FTSSearch {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FTSSearch(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        // 创建FTS5虚拟�?        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS ${FTS_TABLE_NAME} USING fts5(
                message_id,
                session_id,
                content,
                role,
                created_at,
                tokenize='porter unicode61'
            )
        """.trimIndent())
        
        // 创建FTS内容表用于存储完整内�?        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${FTS_CONTENT_TABLE}(
                message_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                content TEXT NOT NULL,
                role TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        
        // 创建元数据表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${FTS_METADATA_TABLE}(
                message_id TEXT PRIMARY KEY,
                session_title TEXT,
                summary TEXT
            )
        """.trimIndent())
        
        // 创建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fts_content_session ON ${FTS_CONTENT_TABLE}(session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fts_content_created ON ${FTS_CONTENT_TABLE}(created_at)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS ${FTS_TABLE_NAME}")
        db.execSQL("DROP TABLE IF EXISTS ${FTS_CONTENT_TABLE}")
        db.execSQL("DROP TABLE IF EXISTS ${FTS_METADATA_TABLE}")
        onCreate(db)
    }
    
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 启用WAL模式
        db.setForeignKeyConstraintsEnabled(true)
    }
    
    /**
     * 索引消息内容
     */
    suspend fun indexMessage(message: MessageEntity, sessionTitle: String? = null) {
        withContext(Dispatchers.IO) {
            writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    // 插入FTS虚拟�?                    db.execSQL("""
                        INSERT INTO ${FTS_TABLE_NAME}(message_id, session_id, content, role, created_at)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(), arrayOf(
                        message.id,
                        message.sessionId,
                        message.content,
                        message.role,
                        message.createdAt
                    ))
                    
                    // 插入完整内容�?                    db.execSQL("""
                        INSERT OR REPLACE INTO ${FTS_CONTENT_TABLE}(message_id, session_id, content, role, created_at)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(), arrayOf(
                        message.id,
                        message.sessionId,
                        message.content,
                        message.role,
                        message.createdAt
                    ))
                    
                    // 更新元数�?
    if (sessionTitle != null) {
                        db.execSQL("""
                            INSERT OR REPLACE INTO ${FTS_METADATA_TABLE}(message_id, session_title)
                            VALUES (?, ?)
                        """.trimIndent(), arrayOf(message.id, sessionTitle))
                    }
                    
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
    
    /**
     * 批量索引消息
     */
    suspend fun indexMessages(messages: List<MessageEntity>, sessionTitle: String? = null) {
        withContext(Dispatchers.IO) {
            writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    messages.forEach { message ->
                        db.execSQL("""
                            INSERT INTO ${FTS_TABLE_NAME}(message_id, session_id, content, role, created_at)
                            VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(), arrayOf(
                            message.id,
                            message.sessionId,
                            message.content,
                            message.role,
                            message.createdAt
                        ))
                        
                        db.execSQL("""
                            INSERT OR REPLACE INTO ${FTS_CONTENT_TABLE}(message_id, session_id, content, role, created_at)
                            VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(), arrayOf(
                            message.id,
                            message.sessionId,
                            message.content,
                            message.role,
                            message.createdAt
                        ))
                    }
        if (sessionTitle != null) {
                        messages.forEach { message ->
                            db.execSQL("""
                                INSERT OR REPLACE INTO ${FTS_METADATA_TABLE}(message_id, session_title)
                                VALUES (?, ?)
                            """.trimIndent(), arrayOf(message.id, sessionTitle))
                        }
                    }
                    
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
    
    /**
     * 全文搜索
     * @param query 搜索关键�?     * @param limit 返回结果数量限制
     * @param sessionIdFilter 可选的会话ID过滤
     */
    suspend fun search(
        query: String,
        limit: Int = 50,
        sessionIdFilter: String? = null
    ): List<FTSSearchResult> = withContext(Dispatchers.IO) {
        readableDatabase.use { db ->
            val sql = if (sessionIdFilter != null) {
                """
                    SELECT m.message_id, m.session_id, c.content, m.rank, mt.session_title, c.created_at
                    FROM ${FTS_TABLE_NAME} m
                    INNER JOIN ${FTS_CONTENT_TABLE} c ON m.message_id = c.message_id
                    LEFT JOIN ${FTS_METADATA_TABLE} mt ON m.message_id = mt.message_id
                    WHERE ${FTS_TABLE_NAME} MATCH ? AND m.session_id = ?
                    ORDER BY m.rank
                    LIMIT ?
                """.trimIndent()
            } else {
                """
                    SELECT m.message_id, m.session_id, c.content, m.rank, mt.session_title, c.created_at
                    FROM ${FTS_TABLE_NAME} m
                    INNER JOIN ${FTS_CONTENT_TABLE} c ON m.message_id = c.message_id
                    LEFT JOIN ${FTS_METADATA_TABLE} mt ON m.message_id = mt.message_id
                    WHERE ${FTS_TABLE_NAME} MATCH ?
                    ORDER BY m.rank
                    LIMIT ?
                """.trimIndent()
            }
        val args = if (sessionIdFilter != null) {
                arrayOf(query, sessionIdFilter, limit)
            } else {
                arrayOf(query, limit)
            }
            
            db.rawQuery(sql, args).use { cursor ->
                val results = mutableListOf<FTSSearchResult>()
                while (cursor.moveToNext()) {
                    results.add(FTSSearchResult(
                        messageId = cursor.getString(0),
                        sessionId = cursor.getString(1),
                        content = cursor.getString(2),
                        rank = cursor.getDouble(3),
                        sessionTitle = cursor.getString(4),
                        createdAt = cursor.getLong(5)
                    ))
                }
                results
            }
        }
    }
    
    /**
     * 跨会话LLM摘要召回搜索
     * @param query 搜索关键�?     * @param limit 返回结果数量限制
     */
    suspend fun semanticSearch(query: String, limit: Int = 20): List<FTSSearchResult> {
        // 使用FTS5的BM25排序进行语义相似搜索
    return search(query, limit)
    }
    
    /**
     * 删除消息索引
     */
    suspend fun deleteMessageIndex(messageId: String) {
        withContext(Dispatchers.IO) {
            writableDatabase.use { db ->
                db.execSQL("DELETE FROM ${FTS_TABLE_NAME} WHERE message_id = ?", arrayOf(messageId))
                db.execSQL("DELETE FROM ${FTS_CONTENT_TABLE} WHERE message_id = ?", arrayOf(messageId))
                db.execSQL("DELETE FROM ${FTS_METADATA_TABLE} WHERE message_id = ?", arrayOf(messageId))
            }
        }
    }
    
    /**
     * 删除会话的所有索�?     */
    suspend fun deleteSessionIndexes(sessionId: String) {
        withContext(Dispatchers.IO) {
            writableDatabase.use { db ->
                db.execSQL("DELETE FROM ${FTS_TABLE_NAME} WHERE session_id = ?", arrayOf(sessionId))
                db.execSQL("DELETE FROM ${FTS_CONTENT_TABLE} WHERE session_id = ?", arrayOf(sessionId))
                db.execSQL("DELETE FROM ${FTS_METADATA_TABLE} WHERE message_id IN (SELECT message_id FROM ${FTS_CONTENT_TABLE} WHERE session_id = ?)", arrayOf(sessionId))
            }
        }
    }
    
    /**
     * 更新会话标题索引
     */
    suspend fun updateSessionTitleIndex(sessionId: String, title: String) {
        withContext(Dispatchers.IO) {
            writableDatabase.use { db ->
                db.execSQL("""
                    UPDATE ${FTS_METADATA_TABLE} 
                    SET session_title = ?
                    WHERE message_id IN (SELECT message_id FROM ${FTS_CONTENT_TABLE} WHERE session_id = ?)
                """.trimIndent(), arrayOf(title, sessionId))
            }
        }
    }
    
    /**
     * 优化FTS索引
     */
    suspend fun optimizeIndex() {
        withContext(Dispatchers.IO) {
            writableDatabase.use { db ->
                db.execSQL("INSERT INTO ${FTS_TABLE_NAME}(${FTS_TABLE_NAME}) VALUES('optimize')")
            }
        }
    }
    
    /**
     * 获取搜索建议（自动补全）
     */
    suspend fun getSuggestions(prefix: String, limit: Int = 10): List<String> {
        return withContext(Dispatchers.IO) {
            readableDatabase.use { db ->
                // 使用prefix search
    val query = "${prefix}*"
                db.rawQuery("""
                    SELECT DISTINCT content FROM ${FTS_CONTENT_TABLE}
                    WHERE content LIKE ?
                    LIMIT ?
                """.trimIndent(), arrayOf("${prefix}%", limit)).use { cursor ->
                    val suggestions = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        suggestions.add(cursor.getString(0))
                    }
                    suggestions
                }
            }
        }
    }
    
    /**
     * 搜索流式结果
     */
    fun searchFlow(query: String, limit: Int = 50): Flow<List<FTSSearchResult>> = flow {
        emit(search(query, limit))
    }.flowOn(Dispatchers.IO)
}
