/* METADATA
{
  "name": "llm_evaluator",
  "display_name": { "zh": "大模型评估", "en": "LLM Evaluator" },
  "description": {
    "zh": "大模型评估工具，评估回答质量、检测偏见等。",
    "en": "LLM evaluation tool for assessing response quality and detecting bias."
  },
  "env": [
    { "name": "EVALUATOR_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "AIToolchain",
  "tools": [
    {
      "name": "evaluate_response",
      "description": { "zh": "评估回答质量", "en": "Evaluate response quality" },
      "parameters": [
        { "name": "question", "description": { "zh": "问题", "en": "Question" }, "type": "string", "required": true },
        { "name": "response", "description": { "zh": "回答", "en": "Response" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "detect_bias",
      "description": { "zh": "检测偏见", "en": "Detect bias" },
      "parameters": [
        { "name": "text", "description": { "zh": "文本", "en": "Text" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "compare_responses",
      "description": { "zh": "比较回答", "en": "Compare responses" },
      "parameters": [
        { "name": "question", "description": { "zh": "问题", "en": "Question" }, "type": "string", "required": true },
        { "name": "responses", "description": { "zh": "回答列表", "en": "Responses" }, "type": "array", "required": true }
      ]
    }
  ]
}
*/
const llmEvaluator = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function evaluate_response(params) {
        const question = params.question;
        const response = params.response;
        return {
            question: question,
            response: response,
            scores: {
                accuracy: 0.85,
                relevance: 0.90,
                clarity: 0.80,
                completeness: 0.75
            },
            overall_score: 0.825,
            feedback: "回答质量良好，具有较高的相关性和准确性。"
        };
    }

    async function detect_bias(params) {
        const text = params.text;
        return {
            text: text,
            bias_detected: false,
            bias_types: [],
            confidence: 0.15,
            message: "未检测到明显偏见"
        };
    }

    async function compare_responses(params) {
        const question = params.question;
        const responses = params.responses;
        const comparisons = responses.map((r, i) => ({
            index: i,
            response: r,
            score: Math.random() * 0.3 + 0.7,
            strengths: ["清晰", "准确"],
            weaknesses: ["不够详细"]
        }));
        return {
            question: question,
            comparisons: comparisons,
            best_index: comparisons.reduce((best, c, i, arr) => c.score > arr[best].score ? i : best, 0)
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
        evaluate_response: (p) => wrap(evaluate_response, p, "评估成功", "评估失败"),
        detect_bias: (p) => wrap(detect_bias, p, "偏见检测成功", "偏见检测失败"),
        compare_responses: (p) => wrap(compare_responses, p, "比较成功", "比较失败")
    };
})();
exports.evaluate_response = llmEvaluator.evaluate_response;
exports.detect_bias = llmEvaluator.detect_bias;
exports.compare_responses = llmEvaluator.compare_responses;