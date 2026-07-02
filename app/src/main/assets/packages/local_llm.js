/* METADATA
{
  "name": "local_llm",
  "display_name": { "zh": "本地大模型", "en": "Local LLM" },
  "description": {
    "zh": "本地大模型集成，支持 GGUF 格式模型加载，CPU/GPU 加速推理。",
    "en": "Local LLM integration supporting GGUF format models with CPU/GPU acceleration."
  },
  "env": [
    { "name": "LOCAL_LLM_URL", "description": { "zh": "本地模型服务 URL（如 Ollama）", "en": "Local model service URL (e.g. Ollama)" }, "required": true },
    { "name": "LOCAL_LLM_MODEL", "description": { "zh": "默认模型名称", "en": "Default model name" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与本地模型对话", "en": "Chat with local model" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "generate",
      "description": { "zh": "文本生成", "en": "Text generation" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "生成提示", "en": "Generation prompt" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "list_models",
      "description": { "zh": "列出可用模型", "en": "List available models" },
      "parameters": []
    }
  ]
}
*/
const localLLM = (function() {
    const HTTP_TIMEOUT_MS = 300000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    function getBaseUrl() {
        const url = getEnv("LOCAL_LLM_URL");
        if (!url) throw new Error("LOCAL_LLM_URL 未配置");
        return url.replace(/\/$/, "");
    }

    function getModel(params) {
        return params.model || getEnv("LOCAL_LLM_MODEL") || "llama3.2";
    }

    async function chat(params) {
        const baseUrl = getBaseUrl();
        const model = getModel(params);
        const body = {
            model: model,
            messages: [{ role: "user", content: params.prompt }],
            stream: false
        };
        const request = client.newRequest()
            .url(`${baseUrl}/api/chat`)
            .method("POST")
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Local LLM API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.message?.content || data.response || "";
    }

    async function generate(params) {
        const baseUrl = getBaseUrl();
        const model = getModel(params);
        const body = {
            model: model,
            prompt: params.prompt,
            stream: false
        };
        const request = client.newRequest()
            .url(`${baseUrl}/api/generate`)
            .method("POST")
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Local LLM Generate Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.response || "";
    }

    async function list_models() {
        const baseUrl = getBaseUrl();
        const request = client.newRequest()
            .url(`${baseUrl}/api/tags`)
            .method("GET");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Local LLM List Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.models || [];
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
        chat: (p) => wrap(chat, p, "本地对话成功", "本地对话失败"),
        generate: (p) => wrap(generate, p, "生成成功", "生成失败"),
        list_models: (p) => wrap(list_models, p, "模型列表获取成功", "模型列表获取失败")
    };
})();
exports.chat = localLLM.chat;
exports.generate = localLLM.generate;
exports.list_models = localLLM.list_models;