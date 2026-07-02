/* METADATA
{
  "name": "deepseek_ai",
  "display_name": { "zh": "DeepSeek AI", "en": "DeepSeek AI" },
  "description": {
    "zh": "DeepSeek 大模型集成，支持文本生成、代码生成、数学推理等。",
    "en": "DeepSeek LLM integration supporting text generation, code generation, and math reasoning."
  },
  "env": [
    { "name": "DEEPSEEK_API_KEY", "description": { "zh": "DeepSeek API Key", "en": "DeepSeek API Key" }, "required": true },
    { "name": "DEEPSEEK_MODEL", "description": { "zh": "默认模型（可选）", "en": "Default model (optional)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与 DeepSeek 对话", "en": "Chat with DeepSeek" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "coder",
      "description": { "zh": "DeepSeek 代码生成", "en": "DeepSeek code generation" },
      "parameters": [
        { "name": "task", "description": { "zh": "代码任务描述", "en": "Code task description" }, "type": "string", "required": true },
        { "name": "language", "description": { "zh": "编程语言", "en": "Programming language" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const deepseekAI = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const API_BASE_URL = "https://api.deepseek.com/v1/chat/completions";

    function getApiKey() {
        const apiKey = getEnv("DEEPSEEK_API_KEY");
        if (!apiKey) throw new Error("DEEPSEEK_API_KEY 未配置");
        return apiKey;
    }

    function getModel(params) {
        return params.model || getEnv("DEEPSEEK_MODEL") || "deepseek-chat";
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
            .url(API_BASE_URL)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`DeepSeek API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.choices?.[0]?.message?.content || "";
    }

    async function coder(params) {
        const apiKey = getApiKey();
        const lang = params.language || "Python";
        const prompt = `请用${lang}编写代码：${params.task}\n\n只输出代码。`;
        const body = {
            model: "deepseek-coder",
            messages: [{ role: "user", content: prompt }]
        };
        const headers = {
            "Authorization": `Bearer ${apiKey}`,
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(API_BASE_URL)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`DeepSeek Coder Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.choices?.[0]?.message?.content || "";
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
        chat: (p) => wrap(chat, p, "DeepSeek对话成功", "DeepSeek对话失败"),
        coder: (p) => wrap(coder, p, "代码生成成功", "代码生成失败")
    };
})();
exports.chat = deepseekAI.chat;
exports.coder = deepseekAI.coder;