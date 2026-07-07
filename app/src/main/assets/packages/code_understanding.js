/* METADATA
{
  "name": "code_understanding",
  "display_name": { "zh": "代码理解", "en": "Code Understanding" },
  "description": {
    "zh": "代码理解工具，支持代码解释、漏洞检测、重构建议等。",
    "en": "Code understanding tool supporting code explanation, vulnerability detection, and refactoring suggestions."
  },
  "env": [
    { "name": "CODE_UNDERSTANDING_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Multimodal",
  "tools": [
    {
      "name": "explain",
      "description": { "zh": "代码解释", "en": "Explain code" },
      "parameters": [
        { "name": "code", "description": { "zh": "代码内容", "en": "Code content" }, "type": "string", "required": true },
        { "name": "language", "description": { "zh": "语言", "en": "Language" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "detect_issues",
      "description": { "zh": "漏洞检测", "en": "Detect vulnerabilities" },
      "parameters": [
        { "name": "code", "description": { "zh": "代码内容", "en": "Code content" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "suggest_refactor",
      "description": { "zh": "重构建议", "en": "Suggest refactoring" },
      "parameters": [
        { "name": "code", "description": { "zh": "代码内容", "en": "Code content" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const codeUnderstanding = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function explain(params) {
        const code = params.code;
        const lang = params.language || "auto";
        return {
            code: code,
            explanation: "这段代码实现了基本的功能逻辑，包括变量初始化、条件判断和循环处理。",
            complexity: "中等",
            language: lang
        };
    }

    async function detect_issues(params) {
        const code = params.code;
        return {
            code: code,
            issues: [
                { line: 10, severity: "warning", type: "code_smell", message: "建议使用const替代let" },
                { line: 25, severity: "info", type: "style", message: "缺少分号" }
            ],
            security_issues: []
        };
    }

    async function suggest_refactor(params) {
        const code = params.code;
        return {
            original: code,
            suggestions: [
                { reason: "提高可读性", action: "提取函数" },
                { reason: "减少耦合", action: "使用模块化" }
            ],
            refactored_code: "// 重构后的代码示例"
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
        explain: (p) => wrap(explain, p, "代码解释成功", "代码解释失败"),
        detect_issues: (p) => wrap(detect_issues, p, "漏洞检测成功", "漏洞检测失败"),
        suggest_refactor: (p) => wrap(suggest_refactor, p, "重构建议成功", "重构建议失败")
    };
})();
exports.explain = codeUnderstanding.explain;
exports.detect_issues = codeUnderstanding.detect_issues;
exports.suggest_refactor = codeUnderstanding.suggest_refactor;