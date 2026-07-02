/* METADATA
{
  "name": "mistral_ai",
  "display_name": { "zh": "Mistral AI", "en": "Mistral AI" },
  "description": {
    "zh": "Mistral AI 大模型集成，支持文本生成、对话、代码生成等。",
    "en": "Mistral AI LLM integration supporting text generation, conversation, and code generation."
  },
  "env": [
    { "name": "MISTRAL_API_KEY", "description": { "zh": "Mistral API Key", "en": "Mistral API Key" }, "required": true },
    { "name": "MISTRAL_MODEL", "description": { "zh": "默认模型（可选）", "en": "Default model (optional)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与 Mistral 对话", "en": "Chat with Mistral" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "embeddings",
      "description": { "zh": "生成文本嵌入向量", "en": "Generate text embeddings" },
      "parameters": [
        { "name": "text", "description": { "zh": "输入文本", "en": "Input text" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const mistralAI = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const API_BASE_URL = "https://api.mistral.ai/v1";

    function getApiKey() {
        const apiKey = getEnv("MISTRAL_API_KEY");
        if (!apiKey) throw new Error("MISTRAL_API_KEY 未配置");
        return apiKey;
    }

    function getModel(params) {
        return params.model || getEnv("MISTRAL_MODEL") || "mistral-small-latest";
    }

    async function chat(params) {
        const apiKey = getApiKey();
        const model = getModel(params);
        const body = {
            model: model,
            messages: [{ role: "user", content: params.prompt }]
        };
        const headers = {
            "Authorization": `Bearer ${apiKey}`,
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(`${API_BASE_URL}/chat/completions`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Mistral API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.choices?.[0]?.message?.content || "";
    }

    async function embeddings(params) {
        const apiKey = getApiKey();
        const body = { input: params.text, model: "mistral-embed" };
        const headers = {
            "Authorization": `Bearer ${apiKey}`,
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(`${API_BASE_URL}/embeddings`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Mistral Embeddings Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.data?.[0]?.embedding || [];
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
        chat: (p) => wrap(chat, p, "Mistral对话成功", "Mistral对话失败"),
        embeddings: (p) => wrap(embeddings, p, "嵌入生成成功", "嵌入生成失败")
    };
})();
exports.chat = mistralAI.chat;
exports.embeddings = mistralAI.embeddings;