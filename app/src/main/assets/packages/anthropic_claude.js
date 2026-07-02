/* METADATA
{
  "name": "anthropic_claude",
  "display_name": { "zh": "Anthropic Claude", "en": "Anthropic Claude" },
  "description": {
    "zh": "Anthropic Claude 大模型集成，支持文本生成、对话、代码生成等能力。",
    "en": "Anthropic Claude LLM integration supporting text generation, conversation, and code generation."
  },
  "env": [
    { "name": "ANTHROPIC_API_KEY", "description": { "zh": "Anthropic API Key", "en": "Anthropic API Key" }, "required": true },
    { "name": "ANTHROPIC_MODEL", "description": { "zh": "默认模型（可选，默认 claude-3-5-sonnet）", "en": "Default model (optional, default: claude-3-5-sonnet)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与 Claude 对话", "en": "Chat with Claude" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false },
        { "name": "max_tokens", "description": { "zh": "最大输出token数", "en": "Max output tokens" }, "type": "number", "required": false },
        { "name": "temperature", "description": { "zh": "温度参数", "en": "Temperature" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "generate_code",
      "description": { "zh": "使用 Claude 生成代码", "en": "Generate code with Claude" },
      "parameters": [
        { "name": "task", "description": { "zh": "代码任务描述", "en": "Code task description" }, "type": "string", "required": true },
        { "name": "language", "description": { "zh": "编程语言", "en": "Programming language" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const anthropicClaude = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const API_BASE_URL = "https://api.anthropic.com/v1/messages";

    function getApiKey() {
        const apiKey = getEnv("ANTHROPIC_API_KEY");
        if (!apiKey) throw new Error("ANTHROPIC_API_KEY 未配置");
        return apiKey;
    }

    function getModel(params) {
        return params.model || getEnv("ANTHROPIC_MODEL") || "claude-3-5-sonnet-20241022";
    }

    async function callClaude(params) {
        const apiKey = getApiKey();
        const model = getModel(params);
        const body = {
            model: model,
            max_tokens: params.max_tokens || 4096,
            temperature: params.temperature || 0.7,
            messages: [{ role: "user", content: params.prompt }]
        };
        const headers = {
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json"
        };
        const request = client.newRequest()
            .url(API_BASE_URL)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Claude API Error: ${response.statusCode} - ${response.content}`);
        return JSON.parse(response.content);
    }

    async function chat(params) {
        const result = await callClaude(params);
        return result.content?.[0]?.text || result.completion || "";
    }

    async function generate_code(params) {
        const prompt = `请用${params.language || "Python"}编写代码：${params.task}\n\n只输出代码，不要解释。`;
        const result = await callClaude({ ...params, prompt });
        return result.content?.[0]?.text || result.completion || "";
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
        chat: (p) => wrap(chat, p, "Claude对话成功", "Claude对话失败"),
        generate_code: (p) => wrap(generate_code, p, "代码生成成功", "代码生成失败")
    };
})();
exports.chat = anthropicClaude.chat;
exports.generate_code = anthropicClaude.generate_code;