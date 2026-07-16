package com.apex.agent.core.tools.defaultTool.standard

/**
 * 内存查询工具的配置常?*/
object MemoryQueryConfig {
    /**
     * 每个配置文件的最大查询快照数?    */
    const val MAX_QUERY_SNAPSHOTS_PER_PROFILE = 32

    /**
     * 默认相关性阈?    */
    const val DEFAULT_RELEVANCE_THRESHOLD = 0.0

    /**
     * 默认查询限制
     */
    const val DEFAULT_QUERY_LIMIT = 20

    /**
     * 通配符查询的最大限?    */
    const val MAX_WILDCARD_QUERY_LIMIT = 500

    /**
     * 截断模式的最大内容长?    */
    const val MAX_CONTENT_LENGTH_TRUNCATED = 40

    /**
     * 最大显示的分块数量
     */
    const val MAX_CHUNKS_DISPLAYED = 5

    /**
     * 最大显示的操作日志数量
     */
    const val MAX_OPERATION_LOGS_DISPLAYED = 20

    /**
     * 最大显示的相关记忆数量
     */
    const val MAX_RELEVANT_MEMORIES_DISPLAYED = 10

    /**
     * 路径查找的最大跳?    */
    const val MAX_HOPS_FOR_PATH_FINDING = 10

    /**
     * 路径查找的最大路径数
     */
    const val MAX_PATHS_FOR_PATH_FINDING = 10

    /**
     * 图相关记忆的最大跳?    */
    const val MAX_HOPS_FOR_GRAPH_RELATED = 5

    /**
     * 链式思考搜索的最大步骤数
     */
    const val MAX_STEPS_FOR_CHAIN_OF_THOUGHT = 10

    /**
     * 重要性阈?    */
    const val DEFAULT_IMPORTANCE_THRESHOLD = 0.8f

    /**
     * 默认链接权重
     */
    const val DEFAULT_LINK_WEIGHT = 0.7f

    /**
     * 默认相似度阈?    */
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.92f
}
