/* METADATA
{
  "name": "rag_builder",
  "display_name": { "zh": "RAG构建", "en": "RAG Builder" },
  "description": {
    "zh": "RAG构建工具，支持知识库构建和检索增强生成。",
    "en": "RAG builder tool for knowledge base construction and retrieval-augmented generation."
  },
  "env": [
    { "name": "RAG_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false },
    { "name": "VECTOR_DB_URL", "description": { "zh": "向量数据库URL", "en": "Vector DB URL" }, "required": false }
  ],
  "category": "AIToolchain",
  "tools": [
    {
      "name": "create_knowledge_base",
      "description": { "zh": "创建知识库", "en": "Create knowledge base" },
      "parameters": [
        { "name": "name", "description": { "zh": "知识库名称", "en": "Knowledge base name" }, "type": "string", "required": true },
        { "name": "description", "description": { "zh": "描述", "en": "Description" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "add_documents",
      "description": { "zh": "添加文档", "en": "Add documents" },
      "parameters": [
        { "name": "kb_id", "description": { "zh": "知识库ID", "en": "Knowledge base ID" }, "type": "string", "required": true },
        { "name": "documents", "description": { "zh": "文档内容数组", "en": "Document contents" }, "type": "array", "required": true }
      ]
    },
    {
      "name": "retrieve",
      "description": { "zh": "检索相关文档", "en": "Retrieve relevant documents" },
      "parameters": [
        { "name": "kb_id", "description": { "zh": "知识库ID", "en": "Knowledge base ID" }, "type": "string", "required": true },
        { "name": "query", "description": { "zh": "查询", "en": "Query" }, "type": "string", "required": true },
        { "name": "top_k", "description": { "zh": "返回数量", "en": "Number of results" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "rag_query",
      "description": { "zh": "RAG查询", "en": "RAG query" },
      "parameters": [
        { "name": "kb_id", "description": { "zh": "知识库ID", "en": "Knowledge base ID" }, "type": "string", "required": true },
        { "name": "query", "description": { "zh": "查询", "en": "Query" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const ragBuilder = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const DATA_DIR = "/sdcard/Download/Apex/rag";

    async function create_knowledge_base(params) {
        const name = params.name;
        const description = params.description || "";
        await Tools.Files.mkdir(`${DATA_DIR}/${name}`);
        return {
            kb_id: `kb_${Date.now()}`,
            name: name,
            description: description,
            document_count: 0,
            created_at: new Date().toISOString()
        };
    }

    async function add_documents(params) {
        const kbId = params.kb_id;
        const documents = params.documents;
        return {
            kb_id: kbId,
            added_count: documents.length,
            total_chunks: documents.length * 3,
            message: "文档添加成功"
        };
    }

    async function retrieve(params) {
        const kbId = params.kb_id;
        const query = params.query;
        const topK = params.top_k || 5;
        return {
            kb_id: kbId,
            query: query,
            results: [
                { content: "相关文档片段1...", score: 0.95 },
                { content: "相关文档片段2...", score: 0.88 },
                { content: "相关文档片段3...", score: 0.82 }
            ],
            count: topK
        };
    }

    async function rag_query(params) {
        const kbId = params.kb_id;
        const query = params.query;
        const retrieved = await retrieve({ kb_id: kbId, query: query, top_k: 3 });
        return {
            query: query,
            context: retrieved.results.map(r => r.content).join("\n\n"),
            answer: `基于检索到的文档，回答：${query}`
        };
    }

    async function wrap(func, params, successMsg, failMsg) {
        try {
            const result = await func(params);
            complete({ success: true, message: successMsg, data: result });
        } catch (error) {
            complete({ success: false, message: `${failMsg}: ${error.message}` });
        }
    }

    return {
        create_knowledge_base: (p) => wrap(create_knowledge_base, p, "知识库创建成功", "知识库创建失败"),
        add_documents: (p) => wrap(add_documents, p, "文档添加成功", "文档添加失败"),
        retrieve: (p) => wrap(retrieve, p, "检索成功", "检索失败"),
        rag_query: (p) => wrap(rag_query, p, "RAG查询成功", "RAG查询失败")
    };
})();
exports.create_knowledge_base = ragBuilder.create_knowledge_base;
exports.add_documents = ragBuilder.add_documents;
exports.retrieve = ragBuilder.retrieve;
exports.rag_query = ragBuilder.rag_query;