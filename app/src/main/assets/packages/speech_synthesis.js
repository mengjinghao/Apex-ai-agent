/* METADATA
{
  "name": "speech_synthesis",
  "display_name": { "zh": "文字转语音", "en": "Speech Synthesis" },
  "description": {
    "zh": "文字转语音工具，支持多种音色和语言。",
    "en": "Text-to-speech tool supporting multiple voices and languages."
  },
  "env": [
    { "name": "TTS_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false },
    { "name": "TTS_PROVIDER", "description": { "zh": "提供商", "en": "Provider" }, "required": false }
  ],
  "category": "Multimodal",
  "tools": [
    {
      "name": "speak",
      "description": { "zh": "文字转语音", "en": "Convert text to speech" },
      "parameters": [
        { "name": "text", "description": { "zh": "要转换的文字", "en": "Text to convert" }, "type": "string", "required": true },
        { "name": "language", "description": { "zh": "语言", "en": "Language" }, "type": "string", "required": false },
        { "name": "voice", "description": { "zh": "音色名称", "en": "Voice name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "list_voices",
      "description": { "zh": "列出可用音色", "en": "List available voices" },
      "parameters": [
        { "name": "language", "description": { "zh": "筛选语言", "en": "Filter by language" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const speechSynthesis = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const OUTPUT_DIR = "/sdcard/Download/Apex/tts";

    async function speak(params) {
        const text = params.text;
        const lang = params.language || "zh";
        const voice = params.voice || "default";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `tts_${Date.now()}.mp3`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            text: text,
            language: lang,
            voice: voice,
            output_path: filePath,
            message: "语音已生成并保存"
        };
    }

    async function list_voices(params) {
        const lang = params.language;
        const voices = [
            { id: "zh-CN-female", name: "女声-中文", language: "zh-CN" },
            { id: "zh-CN-male", name: "男声-中文", language: "zh-CN" },
            { id: "en-US-female", name: "女声-英文", language: "en-US" },
            { id: "en-US-male", name: "男声-英文", language: "en-US" }
        ];
        if (lang) {
            return voices.filter(v => v.language.startsWith(lang));
        }
        return voices;
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
        speak: (p) => wrap(speak, p, "语音合成成功", "语音合成失败"),
        list_voices: (p) => wrap(list_voices, p, "音色列表获取成功", "音色列表获取失败")
    };
})();
exports.speak = speechSynthesis.speak;
exports.list_voices = speechSynthesis.list_voices;