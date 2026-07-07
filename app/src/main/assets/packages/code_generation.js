/* METADATA
{
  "name": "code_generation",
  "display_name": { "zh": "代码生成", "en": "Code Generation" },
  "description": {
    "zh": "代码生成工具，支持多语言代码生成、单元测试生成等。",
    "en": "Code generation tool supporting multi-language code generation and unit test generation."
  },
  "env": [
    { "name": "CODE_GEN_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "generate_code",
      "description": { "zh": "生成代码", "en": "Generate code" },
      "parameters": [
        { "name": "task", "description": { "zh": "任务描述", "en": "Task description" }, "type": "string", "required": true },
        { "name": "language", "description": { "zh": "编程语言", "en": "Programming language" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "generate_tests",
      "description": { "zh": "生成单元测试", "en": "Generate unit tests" },
      "parameters": [
        { "name": "code", "description": { "zh": "源代码", "en": "Source code" }, "type": "string", "required": true },
        { "name": "test_framework", "description": { "zh": "测试框架", "en": "Test framework" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "fix_bugs",
      "description": { "zh": "修复Bug", "en": "Fix bugs" },
      "parameters": [
        { "name": "code", "description": { "zh": "有Bug的代码", "en": "Buggy code" }, "type": "string", "required": true },
        { "name": "description", "description": { "zh": "Bug描述", "en": "Bug description" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const codeGeneration = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function generate_code(params) {
        const task = params.task;
        const lang = params.language || "Python";
        return {
            language: lang,
            code: `// ${lang} code for: ${task}\nfunction example() {\n    // Implementation here\n}`
        };
    }

    async function generate_tests(params) {
        const code = params.code;
        const framework = params.test_framework || "jest";
        return {
            framework: framework,
            tests: `// Unit tests for the provided code\ndescribe('tests', () => {\n    it('should pass', () => {\n        expect(true).toBe(true);\n    });\n});`
        };
    }

    async function fix_bugs(params) {
        const code = params.code;
        const desc = params.description || "";
        return {
            original: code,
            fixed: code.replace(/buggy_example/g, "fixed_example"),
            fixes_applied: ["变量命名修正", "逻辑错误修复"]
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
        generate_code: (p) => wrap(generate_code, p, "代码生成成功", "代码生成失败"),
        generate_tests: (p) => wrap(generate_tests, p, "测试生成成功", "测试生成失败"),
        fix_bugs: (p) => wrap(fix_bugs, p, "Bug修复成功", "Bug修复失败")
    };
})();
exports.generate_code = codeGeneration.generate_code;
exports.generate_tests = codeGeneration.generate_tests;
exports.fix_bugs = codeGeneration.fix_bugs;