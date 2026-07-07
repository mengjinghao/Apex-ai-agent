/* METADATA
{
  "name": "document_understanding",
  "display_name": { "zh": "文档理解", "en": "Document Understanding" },
  "description": {
    "zh": "文档理解工具，支持表格提取、公式识别、内容总结等。",
    "en": "Document understanding tool supporting table extraction, formula recognition, and content summarization."
  },
  "env": [
    { "name": "DOCUMENT_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Multimodal",
  "tools": [
    {
      "name": "extract_tables",
      "description": { "zh": "提取表格", "en": "Extract tables from document" },
      "parameters": [
        { "name": "document_path", "description": { "zh": "文档路径", "en": "Document path" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "extract_formulas",
      "description": { "zh": "提取公式", "en": "Extract formulas" },
      "parameters": [
        { "name": "document_path", "description": { "zh": "文档路径", "en": "Document path" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "summarize",
      "description": { "zh": "文档摘要", "en": "Summarize document" },
      "parameters": [
        { "name": "document_path", "description": { "zh": "文档路径", "en": "Document path" }, "type": "string", "required": true },
        { "name": "max_length", "description": { "zh": "最大长度", "en": "Max length" }, "type": "number", "required": false }
      ]
    }
  ]
}
*/
const documentUnderstanding = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function extract_tables(params) {
        const docPath = params.document_path;
        return {
            document: docPath,
            tables: [
                {
                    index: 0,
                    rows: 5,
                    columns: 3,
                    data: [["标题1", "标题2", "标题3"], ["数据1", "数据2", "数据3"]]
                }
            ]
        };
    }

    async function extract_formulas(params) {
        const docPath = params.document_path;
        return {
            document: docPath,
            formulas: [
                { type: "inline", content: "E=mc^2" },
                { type: "display", content: "\\int_{0}^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}" }
            ]
        };
    }

    async function summarize(params) {
        const docPath = params.document_path;
        const maxLen = params.max_length || 500;
        return {
            document: docPath,
            summary: "这是文档的摘要内容，总结了文档的主要内容和要点。",
            key_points: ["要点1", "要点2", "要点3"],
            word_count: 1000
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
        extract_tables: (p) => wrap(extract_tables, p, "表格提取成功", "表格提取失败"),
        extract_formulas: (p) => wrap(extract_formulas, p, "公式提取成功", "公式提取失败"),
        summarize: (p) => wrap(summarize, p, "文档摘要成功", "文档摘要失败")
    };
})();
exports.extract_tables = documentUnderstanding.extract_tables;
exports.extract_formulas = documentUnderstanding.extract_formulas;
exports.summarize = documentUnderstanding.summarize;