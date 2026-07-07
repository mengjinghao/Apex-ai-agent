/*
METADATA
{
    "name": "libretranslate",
    "display_name": {
        "zh": "LibreTranslate 翻译",
        "en": "LibreTranslate"
    },
    "description": { "zh": "使用LibreTranslate进行免费机器翻译。", "en": "Use LibreTranslate for free machine translation." },
    "category": "Translation",
    "tools": [
        {
            "name": "translate",
            "description": { "zh": "将文本从源语言翻译成目标语言。", "en": "Translate text from source language to target language." },
            "parameters": [
                {
                    "name": "text",
                    "description": { "zh": "要翻译的文本", "en": "Text to translate." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "source",
                    "description": { "zh": "源语言代码 (默认: auto, 自动检测)", "en": "Source language code (default: auto, auto-detect)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "target",
                    "description": { "zh": "目标语言代码 (默认: zh)", "en": "Target language code (default: zh)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "api_url",
                    "description": { "zh": "LibreTranslate API端点 (默认: https://libretranslate.de)", "en": "LibreTranslate API endpoint (default: https://libretranslate.de)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "api_key",
                    "description": { "zh": "API密钥 (可选)", "en": "API key (optional)." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "get_languages",
            "description": { "zh": "获取支持的语言列表。", "en": "Get list of supported languages." },
            "parameters": [
                {
                    "name": "api_url",
                    "description": { "zh": "LibreTranslate API端点", "en": "LibreTranslate API endpoint." },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}*/
const libretranslate = (function () {
    const client = OkHttp.newClient();
    const DEFAULT_API_URL = "https://libretranslate.de";
    /**
     * Translate text using LibreTranslate.
     * @param params Translation parameters.
     * @returns Translated text.
     */
    async function translate(params) {
        const { text } = params;
        const source = params.source || "auto";
        const target = params.target || "zh";
        const apiUrl = params.api_url || DEFAULT_API_URL;
        const apiKey = params.api_key || process.env.LIBRETRANSLATE_API_KEY;
        if (!text) {
            throw new Error("要翻译的文本不能为空");
        }
        console.log(`正在翻译: ${text.substring(0, 50)}... (${source} -> ${target})`);
        const requestBody = {
            q: text,
            source: source,
            target: target
        };
        if (apiKey) {
            requestBody.api_key = apiKey;
        }
        const request = client.newRequest()
            .url(`${apiUrl}/translate`)
            .method('POST')
            .body(JSON.stringify(requestBody), 'json')
            .headers({
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        if (jsonResponse.translatedText) {
            console.log(`翻译完成: ${jsonResponse.translatedText.substring(0, 50)}...`);
            return jsonResponse.translatedText;
        } else {
            throw new Error("翻译失败，未返回翻译结果");
        }
    }
    /**
     * Get list of supported languages.
     * @param params Parameters including optional api_url.
     * @returns List of supported languages.
     */
    async function get_languages(params) {
        const apiUrl = params.api_url || DEFAULT_API_URL;
        console.log(`正在获取支持的语言列表: ${apiUrl}`);
        const request = client.newRequest()
            .url(`${apiUrl}/languages`)
            .method('GET')
            .headers({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const languages = JSON.parse(response.content);
        console.log(`获取到 ${languages.length} 种支持的语言`);
        return format_languages_for_llm(languages);
    }
    /**
     * Format languages list for LLM.
     * @param languages List of language objects.
     * @returns Formatted string.
     */
    function format_languages_for_llm(languages) {
        if (!languages || languages.length === 0) {
            return "未获取到支持的语言列表";
        }
        const output = languages.map(lang => `${lang.code} - ${lang.name}`);
        return `支持的语言列表 (${languages.length}种):\n${output.join('\n')}`;
    }
    /**
     * Wraps function calls for standardized success/error handling.
     */
    async function lt_wrap(func, params, successMessage, failMessage) {
        try {
            console.log(`开始执行函数: ${func.name || '匿名函数'}`);
            const result = await func(params);
            complete({ success: true, message: successMessage, data: result });
        }
        catch (error) {
            console.error(`函数 ${func.name || '匿名函数'} 执行失败! 错误: ${error.message}`);
            complete({ success: false, message: `${failMessage}: ${error.message}`, error_stack: error.stack });
        }
    }
    return {
        translate: (params) => lt_wrap(translate, params, '翻译完成', '翻译失败'),
        get_languages: (params) => lt_wrap(get_languages, params, '获取语言列表完成', '获取语言列表失败'),
    };
})();
exports.translate = libretranslate.translate;
exports.get_languages = libretranslate.get_languages;
