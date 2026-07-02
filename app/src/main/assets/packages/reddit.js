/*
METADATA
{
    "name": "reddit",
    "display_name": {
        "zh": "Reddit",
        "en": "Reddit"
    },
    "description": { "zh": "访问Reddit获取帖子和评论。", "en": "Access Reddit for posts and comments." },
    "category": "Social",
    "tools": [
        {
            "name": "get_posts",
            "description": { "zh": "获取Reddit帖子。", "en": "Get Reddit posts." },
            "parameters": [
                {
                    "name": "subreddit",
                    "description": { "zh": "子版块名称 (如: programming, news)", "en": "Subreddit name (e.g., programming, news)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "sort",
                    "description": { "zh": "排序方式 (hot, top, new, rising, controversial)", "en": "Sorting method (hot, top, new, rising, controversial)." },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "limit",
                    "description": { "zh": "返回数量 (默认: 10)", "en": "Number of results (default: 10)." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "get_comments",
            "description": { "zh": "获取帖子的评论。", "en": "Get comments for a post." },
            "parameters": [
                {
                    "name": "url",
                    "description": { "zh": "帖子URL", "en": "Post URL." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "limit",
                    "description": { "zh": "返回评论数量 (默认: 10)", "en": "Number of comments (default: 10)." },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}*/
const reddit = (function () {
    const client = OkHttp.newClient();
    const BASE_URL = "https://www.reddit.com";
    /**
     * Get Reddit posts.
     * @param params Parameters including subreddit, sort, and limit.
     * @returns Formatted posts.
     */
    async function get_posts(params) {
        const subreddit = params.subreddit || "all";
        const sort = params.sort || "hot";
        let limit = 10;
        if (params.limit) {
            const parsedLimit = parseInt(params.limit, 10);
            if (!isNaN(parsedLimit) && parsedLimit > 0 && parsedLimit <= 100) {
                limit = parsedLimit;
            }
        }
        console.log(`正在获取Reddit帖子: r/${subreddit}, sort=${sort}`);
        const url = `${BASE_URL}/r/${subreddit}/${sort}.json?limit=${limit}`;
        const request = client.newRequest()
            .url(url)
            .method('GET')
            .headers({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept": "application/json"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        const posts = [];
        if (jsonResponse.data && jsonResponse.data.children) {
            jsonResponse.data.children.forEach((child) => {
                const post = child.data;
                posts.push({
                    title: post.title,
                    url: post.url,
                    author: post.author,
                    score: post.score,
                    comments: post.num_comments,
                    subreddit: post.subreddit,
                    created: new Date(post.created_utc * 1000).toLocaleString('zh-CN'),
                    selftext: post.selftext || ''
                });
            });
        }
        return format_posts_for_llm(posts);
    }
    /**
     * Get comments for a post.
     * @param params Parameters including url and limit.
     * @returns Formatted comments.
     */
    async function get_comments(params) {
        const { url } = params;
        let limit = 10;
        if (params.limit) {
            const parsedLimit = parseInt(params.limit, 10);
            if (!isNaN(parsedLimit) && parsedLimit > 0 && parsedLimit <= 100) {
                limit = parsedLimit;
            }
        }
        if (!url) {
            throw new Error("帖子URL不能为空");
        }
        console.log(`正在获取评论: ${url}`);
        let commentsUrl = url;
        if (!commentsUrl.endsWith('.json')) {
            commentsUrl = commentsUrl.replace(/\/$/, '') + '.json';
        }
        const request = client.newRequest()
            .url(commentsUrl)
            .method('GET')
            .headers({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept": "application/json"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        const comments = [];
        if (jsonResponse.length > 1 && jsonResponse[1] && jsonResponse[1].data && jsonResponse[1].data.children) {
            jsonResponse[1].data.children.forEach((child) => {
                const comment = child.data;
                if (comment.body) {
                    comments.push({
                        author: comment.author,
                        body: comment.body,
                        score: comment.score,
                        created: new Date(comment.created_utc * 1000).toLocaleString('zh-CN')
                    });
                }
            });
        }
        return format_comments_for_llm(comments.slice(0, limit));
    }
    /**
     * Format posts for LLM.
     * @param posts List of posts.
     * @returns Formatted string.
     */
    function format_posts_for_llm(posts) {
        if (!posts || posts.length === 0) {
            return "未找到帖子";
        }
        const output = posts.map((post, index) => {
            return `${index + 1}. ${post.title}\n   作者: ${post.author}\n   版块: r/${post.subreddit}\n   分数: ${post.score} | 评论: ${post.comments}\n   发布时间: ${post.created}\n   URL: ${post.url}`;
        });
        return `找到 ${posts.length} 个帖子:\n\n${output.join('\n\n')}`;
    }
    /**
     * Format comments for LLM.
     * @param comments List of comments.
     * @returns Formatted string.
     */
    function format_comments_for_llm(comments) {
        if (!comments || comments.length === 0) {
            return "未找到评论";
        }
        const output = comments.map((comment, index) => {
            const body = comment.body.length > 200 ? comment.body.substring(0, 200) + "..." : comment.body;
            return `${index + 1}. ${comment.author} (${comment.score}分)\n   ${body}`;
        });
        return `找到 ${comments.length} 条评论:\n\n${output.join('\n\n')}`;
    }
    /**
     * Wraps function calls for standardized success/error handling.
     */
    async function reddit_wrap(func, params, successMessage, failMessage) {
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
        get_posts: (params) => reddit_wrap(get_posts, params, '获取帖子完成', '获取帖子失败'),
        get_comments: (params) => reddit_wrap(get_comments, params, '获取评论完成', '获取评论失败'),
    };
})();
exports.get_posts = reddit.get_posts;
exports.get_comments = reddit.get_comments;
