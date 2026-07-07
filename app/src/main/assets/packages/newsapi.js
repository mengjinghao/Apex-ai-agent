/*
METADATA
{
    "name": "newsapi",
    "display_name": {
        "zh": "新闻资讯",
        "en": "News API"
    },
    "description": { "zh": "使用NewsAPI获取全球新闻资讯。", "en": "Get global news using NewsAPI." },
    "category": "News",
    "tools": [
        {
            "name": "get_top_headlines",
            "description": { "zh": "获取头条新闻。", "en": "Get top headlines." },
            "parameters": [
                {
                    "name": "country",
                    "description": { "zh": "国家代码 (如: cn, us, uk)", "en": "Country code (e.g., cn, us, uk)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "category",
                    "description": { "zh": "新闻类别 (business, entertainment, general, health, science, sports, technology)", "en": "News category (business, entertainment, general, health, science, sports, technology)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "pageSize",
                    "description": { "zh": "每页结果数 (默认: 10)", "en": "Number of results per page (default: 10)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "api_key",
                    "description": { "zh": "NewsAPI密钥", "en": "NewsAPI key." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "get_everything",
            "description": { "zh": "搜索新闻文章。", "en": "Search news articles." },
            "parameters": [
                {
                    "name": "q",
                    "description": { "zh": "搜索关键词", "en": "Search query." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "sources",
                    "description": { "zh": "新闻来源", "en": "News sources." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "pageSize",
                    "description": { "zh": "每页结果数 (默认: 10)", "en": "Number of results per page (default: 10)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "api_key",
                    "description": { "zh": "NewsAPI密钥", "en": "NewsAPI key." },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}*/
const newsapi = (function () {
    const client = OkHttp.newClient();
    const BASE_URL = "https://newsapi.org/v2";
    /**
     * Get top headlines.
     * @param params Parameters including country, category, pageSize, and api_key.
     * @returns Formatted news results.
     */
    async function get_top_headlines(params) {
        const country = params.country || "cn";
        const category = params.category;
        let pageSize = 10;
        if (params.pageSize) {
            const parsedPageSize = parseInt(params.pageSize, 10);
            if (!isNaN(parsedPageSize) && parsedPageSize > 0 && parsedPageSize <= 100) {
                pageSize = parsedPageSize;
            }
        }
        let apiKey = params.api_key || process.env.NEWSAPI_KEY;
        if (!apiKey) {
            throw new Error("NewsAPI密钥未配置。请设置环境变量NEWSAPI_KEY或在参数中提供api_key。");
        }
        console.log(`正在获取头条新闻: country=${country}, category=${category}`);
        let url = `${BASE_URL}/top-headlines?country=${country}&pageSize=${pageSize}&apiKey=${apiKey}`;
        if (category) {
            url += `&category=${category}`;
        }
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
        if (jsonResponse.status !== "ok") {
            throw new Error(`API错误: ${jsonResponse.message}`);
        }
        return format_news_for_llm(jsonResponse.articles);
    }
    /**
     * Search news articles.
     * @param params Parameters including q, sources, pageSize, and api_key.
     * @returns Formatted news results.
     */
    async function get_everything(params) {
        const { q } = params;
        const sources = params.sources;
        let pageSize = 10;
        if (params.pageSize) {
            const parsedPageSize = parseInt(params.pageSize, 10);
            if (!isNaN(parsedPageSize) && parsedPageSize > 0 && parsedPageSize <= 100) {
                pageSize = parsedPageSize;
            }
        }
        let apiKey = params.api_key || process.env.NEWSAPI_KEY;
        if (!apiKey) {
            throw new Error("NewsAPI密钥未配置。请设置环境变量NEWSAPI_KEY或在参数中提供api_key。");
        }
        if (!q) {
            throw new Error("搜索关键词不能为空");
        }
        console.log(`正在搜索新闻: ${q}`);
        let url = `${BASE_URL}/everything?q=${encodeURIComponent(q)}&pageSize=${pageSize}&apiKey=${apiKey}`;
        if (sources) {
            url += `&sources=${sources}`;
        }
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
        if (jsonResponse.status !== "ok") {
            throw new Error(`API错误: ${jsonResponse.message}`);
        }
        return format_news_for_llm(jsonResponse.articles);
    }
    /**
     * Format news articles for LLM.
     * @param articles List of articles.
     * @returns Formatted string.
     */
    function format_news_for_llm(articles) {
        if (!articles || articles.length === 0) {
            return "没有找到相关新闻。";
        }
        const output = articles.map((article, index) => {
            const date = article.publishedAt ? new Date(article.publishedAt).toLocaleDateString('zh-CN') : '';
            return `${index + 1}. ${article.title}\n   来源: ${article.source.name || article.author || '未知'}\n   日期: ${date}\n   URL: ${article.url}\n   摘要: ${article.description || ''}`;
        });
        return `找到 ${articles.length} 条新闻:\n\n${output.join('\n\n')}`;
    }
    /**
     * Wraps function calls for standardized success/error handling.
     */
    async function news_wrap(func, params, successMessage, failMessage) {
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
        get_top_headlines: (params) => news_wrap(get_top_headlines, params, '获取头条新闻完成', '获取头条新闻失败'),
        get_everything: (params) => news_wrap(get_everything, params, '搜索新闻完成', '搜索新闻失败'),
    };
})();
exports.get_top_headlines = newsapi.get_top_headlines;
exports.get_everything = newsapi.get_everything;
