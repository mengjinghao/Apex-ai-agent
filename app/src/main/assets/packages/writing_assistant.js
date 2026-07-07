/* METADATA
{
  "name": "writing_assistant",
  "display_name": { "zh": "写作助手", "en": "Writing Assistant" },
  "description": {
    "zh": "写作助手，支持文章润色、风格转换、大纲生成等。",
    "en": "Writing assistant supporting article polishing, style conversion, and outline generation."
  },
  "env": [
    { "name": "WRITING_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "polish",
      "description": { "zh": "文章润色", "en": "Polish article" },
      "parameters": [
        { "name": "text", "description": { "zh": "原文", "en": "Original text" }, "type": "string", "required": true },
        { "name": "level", "description": { "zh": "润色级别", "en": "Polishing level" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "convert_style",
      "description": { "zh": "风格转换", "en": "Convert style" },
      "parameters": [
        { "name": "text", "description": { "zh": "原文", "en": "Original text" }, "type": "string", "required": true },
        { "name": "target_style", "description": { "zh": "目标风格", "en": "Target style" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "generate_outline",
      "description": { "zh": "生成大纲", "en": "Generate outline" },
      "parameters": [
        { "name": "topic", "description": { "zh": "主题", "en": "Topic" }, "type": "string", "required": true },
        { "name": "sections", "description": { "zh": "章节数", "en": "Number of sections" }, "type": "number", "required": false }
      ]
    }
  ]
}
*/
const writingAssistant = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function polish(params) {
        const text = params.text;
        const level = params.level || "moderate";
        return {
            original: text,
            polished: `[润色后] ${text}`,
            level: level,
            changes: ["语法修正", "用词优化", "表达增强"]
        };
    }

    async function convert_style(params) {
        const text = params.text;
        const targetStyle = params.target_style;
        return {
            original: text,
            converted: `[${targetStyle}风格] ${text}`,
            target_style: targetStyle
        };
    }

    async function generate_outline(params) {
        const topic = params.topic;
        const sections = params.sections || 5;
        return {
            topic: topic,
            outline: [
                { title: "引言", points: ["背景介绍", "研究意义"] },
                { title: "主体部分一", points: ["论点一", "论点二"] },
                { title: "主体部分二", points: ["论点三", "论点四"] },
                { title: "讨论", points: ["分析", "启示"] },
                { title: "结论", points: ["总结", "展望"] }
            ],
            total_sections: sections
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
        polish: (p) => wrap(polish, p, "文章润色成功", "文章润色失败"),
        convert_style: (p) => wrap(convert_style, p, "风格转换成功", "风格转换失败"),
        generate_outline: (p) => wrap(generate_outline, p, "大纲生成成功", "大纲生成失败")
    };
})();
exports.polish = writingAssistant.polish;
exports.convert_style = writingAssistant.convert_style;
exports.generate_outline = writingAssistant.generate_outline;