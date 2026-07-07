/*
METADATA
{
    "name": "wolfram_alpha",
    "display_name": {
        "zh": "Wolfram Alpha",
        "en": "Wolfram Alpha"
    },
    "description": { "zh": "使用Wolfram Alpha进行知识计算和问答。", "en": "Use Wolfram Alpha for knowledge computation and Q&A." },
    "category": "Computation",
    "tools": [
        {
            "name": "query",
            "description": { "zh": "向Wolfram Alpha查询问题并获取答案。", "en": "Query Wolfram Alpha and get answers." },
            "parameters": [
                {
                    "name": "input",
                    "description": { "zh": "查询输入", "en": "Query input." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "app_id",
                    "description": { "zh": "Wolfram Alpha AppID", "en": "Wolfram Alpha AppID." },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}*/
const wolfram_alpha = (function () {
    const client = OkHttp.newClient();
    const BASE_URL = "https://api.wolframalpha.com/v2/query";
    /**
     * Query Wolfram Alpha.
     * @param params Parameters including input and app_id.
     * @returns Formatted results.
     */
    async function query(params) {
        const { input } = params;
        let appId = params.app_id || process.env.WOLFRAM_APP_ID;
        if (!appId) {
            throw new Error("Wolfram Alpha AppID未配置。请设置环境变量WOLFRAM_APP_ID或在参数中提供app_id。");
        }
        if (!input) {
            throw new Error("查询输入不能为空");
        }
        console.log(`正在查询Wolfram Alpha: ${input}`);
        const url = `${BASE_URL}?input=${encodeURIComponent(input)}&appid=${appId}&output=json`;
        const request = client.newRequest()
            .url(url)
            .method('GET')
            .headers({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        return parse_wolfram_response(jsonResponse);
    }
    /**
     * Parse Wolfram Alpha JSON response.
     * @param response The API response.
     * @returns Formatted results string.
     */
    function parse_wolfram_response(response) {
        if (!response || !response.queryresult) {
            return "无法解析Wolfram Alpha响应";
        }
        const result = response.queryresult;
        if (result.error) {
            return `API错误: ${result.error.msg}`;
        }
        if (!result.success) {
            return "查询未成功。Wolfram Alpha无法理解或处理该查询。";
        }
        const pods = result.pods || [];
        if (pods.length === 0) {
            return "未返回任何结果";
        }
        const results = [];
        pods.forEach(pod => {
            const title = pod.title || "未知";
            const subpods = pod.subpods || [];
            subpods.forEach(subpod => {
                let content = "";
                if (subpod.plaintext) {
                    content = subpod.plaintext;
                } else if (subpod.img && subpod.img.src) {
                    content = `[图片: ${subpod.img.alt || '结果图片'}]`;
                }
                if (content) {
                    results.push({ title, content });
                }
            });
        });
        return format_results_for_llm(results);
    }
    /**
     * Format results for LLM.
     * @param results List of result objects.
     * @returns Formatted string.
     */
    function format_results_for_llm(results) {
        if (!results || results.length === 0) {
            return "未找到结果";
        }
        const output = results.map((r, index) => `${index + 1}. ${r.title}\n   ${r.content}`);
        return `查询结果:\n\n${output.join('\n\n')}`;
    }
    /**
     * Wraps function calls for standardized success/error handling.
     */
    async function wa_wrap(func, params, successMessage, failMessage) {
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
        query: (params) => wa_wrap(query, params, '查询完成', '查询失败'),
    };
})();
exports.query = wolfram_alpha.query;
