/* METADATA
{
  "name": "google_gemini",
  "display_name": { "zh": "Google Gemini", "en": "Google Gemini" },
  "description": {
    "zh": "Google Gemini 多模态大模型集成，支持文本、图像、视频理解与生成。",
    "en": "Google Gemini multimodal LLM integration supporting text, image, and video understanding and generation."
  },
  "env": [
    { "name": "GEMINI_API_KEY", "description": { "zh": "Google Gemini API Key", "en": "Google Gemini API Key" }, "required": true },
    { "name": "GEMINI_MODEL", "description": { "zh": "默认模型（可选）", "en": "Default model (optional)" }, "required": false }
  ],
  "category": "LLM",
  "tools": [
    {
      "name": "chat",
      "description": { "zh": "与 Gemini 对话", "en": "Chat with Gemini" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "用户输入", "en": "User input" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "模型名称", "en": "Model name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "generate_image",
      "description": { "zh": "使用 Gemini 生成图像", "en": "Generate image with Gemini" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "图像描述", "en": "Image prompt" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const googleGemini = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    function getApiKey() {
        const apiKey = getEnv("GEMINI_API_KEY");
        if (!apiKey) throw new Error("GEMINI_API_KEY 未配置");
        return apiKey;
    }

    function getModel(params) {
        return params.model || getEnv("GEMINI_MODEL") || "gemini-2.0-flash";
    }

    async function chat(params) {
        const apiKey = getApiKey();
        const model = getModel(params);
        const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`;
        const body = {
            contents: [{ parts: [{ text: params.prompt }] }],
            generationConfig: { temperature: 0.9, topK: 40, topP: 0.95, maxOutputTokens: 8192 }
        };
        const request = client.newRequest()
            .url(url)
            .method("POST")
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) throw new Error(`Gemini API Error: ${response.statusCode}`);
        const data = JSON.parse(response.content);
        return data.candidates?.[0]?.content?.parts?.[0]?.text || "";
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
        chat: (p) => wrap(chat, p, "Gemini对话成功", "Gemini对话失败")
    };
})();
exports.chat = googleGemini.chat;
exports.generate_image = googleGemini.generate_image;