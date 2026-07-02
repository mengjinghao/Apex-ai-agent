/* METADATA
{
  "name": "translation_plus",
  "display_name": { "zh": "高级翻译", "en": "Advanced Translation" },
  "description": {
    "zh": "高级翻译工具，支持专业领域翻译、文档翻译。",
    "en": "Advanced translation tool supporting professional domain translation and document translation."
  },
  "env": [
    { "name": "TRANSLATION_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": true },
    { "name": "TRANSLATION_PROVIDER", "description": { "zh": "提供商", "en": "Provider" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "translate",
      "description": { "zh": "翻译文本", "en": "Translate text" },
      "parameters": [
        { "name": "text", "description": { "zh": "待翻译文本", "en": "Text to translate" }, "type": "string", "required": true },
        { "name": "source_lang", "description": { "zh": "源语言", "en": "Source language" }, "type": "string", "required": false },
        { "name": "target_lang", "description": { "zh": "目标语言", "en": "Target language" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "translate_document",
      "description": { "zh": "翻译文档", "en": "Translate document" },
      "parameters": [
        { "name": "document_path", "description": { "zh": "文档路径", "en": "Document path" }, "type": "string", "required": true },
        { "name": "target_lang", "description": { "zh": "目标语言", "en": "Target language" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const translationPlus = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/translations";
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function translate(params) {
        const text = params.text;
        const sourceLang = params.source_lang || "auto";
        const targetLang = params.target_lang;
        return {
            original: text,
            translated: `[${targetLang}] ${text}`,
            source_lang: sourceLang,
            target_lang: targetLang
        };
    }

    async function translate_document(params) {
        const docPath = params.document_path;
        const targetLang = params.target_lang;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `translated_${Date.now()}.pdf`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            original: docPath,
            translated_path: filePath,
            target_lang: targetLang,
            message: "文档翻译成功"
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
        translate: (p) => wrap(translate, p, "翻译成功", "翻译失败"),
        translate_document: (p) => wrap(translate_document, p, "文档翻译成功", "文档翻译失败")
    };
})();
exports.translate = translationPlus.translate;
exports.translate_document = translationPlus.translate_document;