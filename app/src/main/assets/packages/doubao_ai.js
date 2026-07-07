/* METADATA
{
  "name": "doubao_ai",
  "display_name": { "zh": "字节跳动豆包", "en": "ByteDance Doubao" },
  "description": {
    "zh": "字节跳动豆包大模型集成，支持文本生成、对话、创意写作等。",
    "en": "ByteDance Doubao LLM integration supporting text generation, conversation, and creative writing."
  },
  "env": [
    { "name": "DOUBAO_API_KEY", "description": { "zh": "豆包 API Key", "en": "Doubao API Key" }, "required": true },
    { "name": "DOUBAO_MODEL", "description": { "zh": "默认模型（可选）", "en": "Default model (optional)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与豆包对话", "en": "Chat with Doubao" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "write",
      "description": { "zh": "创意写作", "en": "Creative writing" },
      "parameters": [
        { "name": "topic", "description": { "zh": "写作主题", "en": "Writing topic" }, "type": "string", "required": true },
        { "name": "style", "description": { "zh": "写作风格", "en": "Writing style" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const doubaoAI = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const API_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";

    function getApiKey() {
        const apiKey = getEnv("DOUBAO_API_KEY");
        if (!apiKey) throw new Error("DOUBAO_API_KEY 未配置");
        return apiKey;
    }

    function getModel(params) {
        return params.model || getEnv("DOUBAO_MODEL") || "doubao-pro-32k";
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
        if (!response.isSuccessful()) throw new Error(`Doubao API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.choices?.[0]?.message?.content || "";
    }

    async function write(params) {
        const apiKey = getApiKey();
        const style = params.style || "专业";
        const prompt = `请用${style}风格撰写关于"${params.topic}"的文章。`;
        const model = getModel({});
        const body = {
            model: model,
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
        if (!response.isSuccessful()) throw new Error(`Doubao Write Error: ${response.statusCode}`);
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
        chat: (p) => wrap(chat, p, "豆包对话成功", "豆包对话失败"),
        write: (p) => wrap(write, p, "写作成功", "写作失败")
    };
})();
exports.chat = doubaoAI.chat;
exports.write = doubaoAI.write;