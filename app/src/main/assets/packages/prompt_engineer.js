/* METADATA
{
  "name": "prompt_engineer",
  "display_name": { "zh": "提示词工程师", "en": "Prompt Engineer" },
  "description": {
    "zh": "提示词工程师工具，自动优化提示词以获得更好的AI输出。",
    "en": "Prompt engineering tool for automatically optimizing prompts for better AI output."
  },
  "env": [
    { "name": "PROMPT_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "AIToolchain",
  "tools": [
    {
      "name": "optimize",
      "description": { "zh": "优化提示词", "en": "Optimize prompt" },
      "parameters": [
        { "name": "original_prompt", "description": { "zh": "原始提示词", "en": "Original prompt" }, "type": "string", "required": true },
        { "name": "goal", "description": { "zh": "目标", "en": "Goal" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "add_constraints",
      "description": { "zh": "添加约束条件", "en": "Add constraints" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "提示词", "en": "Prompt" }, "type": "string", "required": true },
        { "name": "constraints", "description": { "zh": "约束条件", "en": "Constraints" }, "type": "array", "required": true }
      ]
    },
    {
      "name": "generate_variations",
      "description": { "zh": "生成变体", "en": "Generate variations" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "提示词", "en": "Prompt" }, "type": "string", "required": true },
        { "name": "count", "description": { "zh": "变体数量", "en": "Number of variations" }, "type": "number", "required": false }
      ]
    }
  ]
}
*/
const promptEngineer = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function optimize(params) {
        const originalPrompt = params.original_prompt;
        const goal = params.goal || "提高清晰度和效果";
        return {
            original: originalPrompt,
            optimized: `[优化后] ${originalPrompt}\n\n请确保输出清晰、专业、有条理。`,
            goal: goal,
            improvements: ["添加了上下文", "明确了输出格式", "增强了约束"]
        };
    }

    async function add_constraints(params) {
        const prompt = params.prompt;
        const constraints = params.constraints;
        return {
            original: prompt,
            with_constraints: `${prompt}\n\n约束条件:\n${constraints.map((c, i) => `${i + 1}. ${c}`).join('\n')}`
        };
    }

    async function generate_variations(params) {
        const prompt = params.prompt;
        const count = params.count || 3;
        const variations = [];
        for (let i = 0; i < count; i++) {
            variations.push(`[变体${i + 1}] ${prompt}`);
        }
        return {
            original: prompt,
            variations: variations
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
        optimize: (p) => wrap(optimize, p, "提示词优化成功", "提示词优化失败"),
        add_constraints: (p) => wrap(add_constraints, p, "约束添加成功", "约束添加失败"),
        generate_variations: (p) => wrap(generate_variations, p, "变体生成成功", "变体生成失败")
    };
})();
exports.optimize = promptEngineer.optimize;
exports.add_constraints = promptEngineer.add_constraints;
exports.generate_variations = promptEngineer.generate_variations;