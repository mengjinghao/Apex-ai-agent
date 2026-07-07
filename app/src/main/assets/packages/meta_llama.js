/* METADATA
{
  "name": "meta_llama",
  "display_name": { "zh": "Meta Llama", "en": "Meta Llama" },
  "description": {
    "zh": "Meta Llama 大模型集成，支持文本生成、对话、推理等能力。",
    "en": "Meta Llama LLM integration supporting text generation, conversation, and reasoning."
  },
  "env": [
    { "name": "LLAMA_API_URL", "description": { "zh": "Llama API 端点", "en": "Llama API endpoint" }, "required": true },
    { "name": "LLAMA_API_KEY", "description": { "zh": "API Key（如需要）", "en": "API Key (if required)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与 Llama 对话", "en": "Chat with Llama" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "system", "description": { "zh": "系统提示词", "en": "System prompt" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "complete",
      "description": { "zh": "文本补全", "en": "Text completion" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "输入文本", "en": "Input text" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const metaLlama = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    function getBaseUrl() {
        const url = getEnv("LLAMA_API_URL");
        if (!url) throw new Error("LLAMA_API_URL 未配置");
        return url.replace(/\/$/, "");
    }

    function getApiKey() {
        return getEnv("LLAMA_API_KEY") || "";
    }

    async function chat(params) {
        const baseUrl = getBaseUrl();
        const apiKey = getApiKey();
        const headers = { "content-type": "application/json" };
        if (apiKey) headers["Authorization"] = `Bearer ${apiKey}`;
        const body = {
            messages: [
                ...(params.system ? [{ role: "system", content: params.system }] : []),
                { role: "user", content: params.prompt }
            ]
        };
        const request = client.newRequest()
            .url(`${baseUrl}/chat/completions`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Llama API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.choices?.[0]?.message?.content || "";
    }

    async function complete(params) {
        const baseUrl = getBaseUrl();
        const apiKey = getApiKey();
        const headers = { "content-type": "application/json" };
        if (apiKey) headers["Authorization"] = `Bearer ${apiKey}`;
        const body = { prompt: params.prompt };
        const request = client.newRequest()
            .url(`${baseUrl}/completions`)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Llama API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.choices?.[0]?.text || "";
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
        chat: (p) => wrap(chat, p, "Llama对话成功", "Llama对话失败"),
        complete: (p) => wrap(complete, p, "文本补全成功", "文本补全失败")
    };
})();
exports.chat = metaLlama.chat;
exports.complete = metaLlama.complete;