/* METADATA
{
  "name": "cohere_ai",
  "display_name": { "zh": "Cohere AI", "en": "Cohere AI" },
  "description": {
    "zh": "Cohere AI 大模型集成，支持文本生成、嵌入、语义搜索等。",
    "en": "Cohere AI LLM integration supporting text generation, embeddings, and semantic search."
  },
  "env": [
    { "name": "COHERE_API_KEY", "description": { "zh": "Cohere API Key", "en": "Cohere API Key" }, "required": true },
    { "name": "COHERE_MODEL", "description": { "zh": "默认模型（可选）", "en": "Default model (optional)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与 Cohere 对话", "en": "Chat with Cohere" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "embed",
      "description": { "zh": "文本嵌入", "en": "Text embedding" },
      "parameters": [
        { "name": "texts", "description": { "zh": "文本数组", "en": "Array of texts" }, "type": "array", "required": true }
      ]
    },
    {
      "name": "rerank",
      "description": { "zh": "语义重排序", "en": "Semantic reranking" },
      "parameters": [
        { "name": "query", "description": { "zh": "查询文本", "en": "Query text" }, "type": "string", "required": true },
        { "name": "documents", "description": { "zh": "文档列表", "en": "Document list" }, "type": "array", "required": true }
      ]
    }
  ]
}
*/
const cohereAI = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const API_BASE_URL = "https://api.cohere.ai/v1";

    function getApiKey() {
        const apiKey = getEnv("COHERE_API_KEY");
        if (!apiKey) throw new Error("COHERE_API_KEY 未配置");
        return apiKey;
    }

    async function chat(params) {
        const apiKey = getApiKey();
        const model = params.model || getEnv("COHERE_MODEL") || "command-r-plus";
        const body = {
            model: model,
            message: params.prompt,
            chat_history: []
        };
        const headers = {
            "Authorization": `Bearer ${apiKey}`,
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(`${API_BASE_URL}/chat`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Cohere API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.text || "";
    }

    async function embed(params) {
        const apiKey = getApiKey();
        const body = { texts: params.texts, model: "embed-english-v3.0" };
        const headers = {
            "Authorization": `Bearer ${apiKey}`,
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(`${API_BASE_URL}/embed`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Cohere Embed Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.embeddings || [];
    }

    async function rerank(params) {
        const apiKey = getApiKey();
        const body = {
            query: params.query,
            documents: params.documents,
            top_n: 10,
            model: "rerank-english-v3.0"
        };
        const headers = {
            "Authorization": `Bearer ${apiKey}`,
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(`${API_BASE_URL}/rerank`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Cohere Rerank Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.results || [];
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
        chat: (p) => wrap(chat, p, "Cohere对话成功", "Cohere对话失败"),
        embed: (p) => wrap(embed, p, "嵌入成功", "嵌入失败"),
        rerank: (p) => wrap(rerank, p, "重排序成功", "重排序失败")
    };
})();
exports.chat = cohereAI.chat;
exports.embed = cohereAI.embed;
exports.rerank = cohereAI.rerank;