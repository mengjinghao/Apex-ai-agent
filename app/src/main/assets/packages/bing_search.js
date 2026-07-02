/*
METADATA
{
    "name": "bing_search",
    "display_name": {
        "zh": "Bing 搜索",
        "en": "Bing Search"
    },
    "description": { "zh": "使用Bing搜索引擎进行网络搜索。", "en": "Use Bing search engine for web search." },
    "category": "Search",
    "tools": [
        {
            "name": "search",
            "description": { "zh": "执行Bing搜索并返回格式化的结果。", "en": "Run a Bing search and return formatted results." },
            "parameters": [
                {
                    "name": "query",
                    "description": { "zh": "搜索查询字符串", "en": "Search query string." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "count",
                    "description": { "zh": "返回的结果数量 (默认: 10)", "en": "Number of results to return (default: 10)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "api_key",
                    "description": { "zh": "Bing API密钥 (可选，使用环境变量BING_API_KEY)", "en": "Bing API key (optional, uses BING_API_KEY env var)." },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}*/
const bing_search = (function () {
    const client = OkHttp.newClient();
    const BASE_URL = "https://api.bing.microsoft.com/v7.0/search";
    /**
     * Search Bing and return formatted results.
     * @param params Search parameters including query, count, and api_key.
     * @returns A formatted string of search results.
     */
    async function search(params) {
        const { query } = params;
        let count = 10;
        if (params.count) {
            const parsedCount = parseInt(params.count, 10);
            if (!isNaN(parsedCount) && parsedCount > 0 && parsedCount <= 50) {
                count = parsedCount;
            }
        }
        let apiKey = params.api_key || process.env.BING_API_KEY;
        if (!apiKey) {
            throw new Error("Bing API密钥未配置。请设置环境变量BING_API_KEY或在参数中提供api_key。");
        }
        if (!query) {
            throw new Error("查询不能为空");
        }
        console.log(`正在从Bing搜索: ${query}`);
        const request = client.newRequest()
            .url(BASE_URL)
            .method('GET')
            .addQueryParameter('q', query)
            .addQueryParameter('count', count.toString())
            .addQueryParameter('mkt', 'zh-CN')
            .headers({
                "Ocp-Apim-Subscription-Key": apiKey,
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        const results = [];
        if (jsonResponse.webPages && jsonResponse.webPages.value) {
            jsonResponse.webPages.value.forEach((item, index) => {
                results.push({
                    title: item.name,
                    link: item.url,
                    snippet: item.snippet,
                    position: index + 1
                });
            });
        }
        console.log(`成功找到 ${results.length} 个结果`);
        return format_results_for_llm(results);
    }
    /**
     * Formats search results into a readable string for LLMs.
     * @param results The list of search results.
     * @returns A formatted string.
     */
    function format_results_for_llm(results) {
        if (!results || results.length === 0) {
            return "没有为您的搜索查询找到结果。";
        }
        const output = results.map(r => `${r.position}. ${r.title}\n   URL: ${r.link}\n   摘要: ${r.snippet}`);
        return `找到 ${results.length} 个搜索结果:\n\n${output.join('\n\n')}`;
    }
    /**
     * Wraps function calls for standardized success/error handling.
     */
    async function bing_wrap(func, params, successMessage, failMessage) {
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
        search: (params) => bing_wrap(search, params, '搜索完成', '搜索失败'),
    };
})();
exports.search = bing_search.search;
