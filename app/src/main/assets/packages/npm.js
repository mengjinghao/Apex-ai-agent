/*
METADATA
{
    "name": "npm",
    "display_name": {
        "zh": "npm 包查询",
        "en": "npm Package"
    },
    "description": { "zh": "查询npm包信息和版本历史。", "en": "Query npm package information and version history." },
    "category": "Developer",
    "tools": [
        {
            "name": "get_package_info",
            "description": { "zh": "获取npm包的详细信息。", "en": "Get detailed information about an npm package." },
            "parameters": [
                {
                    "name": "package",
                    "description": { "zh": "npm包名称", "en": "npm package name." },
                    "type": "string",
                    "required": true
                }
            ]
        },
        {
            "name": "get_versions",
            "description": { "zh": "获取npm包的版本历史。", "en": "Get version history of an npm package." },
            "parameters": [
                {
                    "name": "package",
                    "description": { "zh": "npm包名称", "en": "npm package name." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "limit",
                    "description": { "zh": "返回版本数量 (默认: 10)", "en": "Number of versions to return (default: 10)." },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "search_packages",
            "description": { "zh": "搜索npm包。", "en": "Search npm packages." },
            "parameters": [
                {
                    "name": "query",
                    "description": { "zh": "搜索关键词", "en": "Search query." },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "limit",
                    "description": { "zh": "返回数量 (默认: 10)", "en": "Number of results (default: 10)." },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}*/
const npm = (function () {
    const client = OkHttp.newClient();
    const BASE_URL = "https://registry.npmjs.org";
    /**
     * Get package information.
     * @param params Parameters including package name.
     * @returns Formatted package info.
     */
    async function get_package_info(params) {
        const { package: packageName } = params;
        if (!packageName) {
            throw new Error("包名不能为空");
        }
        console.log(`正在获取npm包信息: ${packageName}`);
        const url = `${BASE_URL}/${packageName}`;
        const request = client.newRequest()
            .url(url)
            .method('GET')
            .headers({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept": "application/json"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            if (response.statusCode === 404) {
                throw new Error(`包 ${packageName} 不存在`);
            }
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        return format_package_info(jsonResponse);
    }
    /**
     * Get version history.
     * @param params Parameters including package name and limit.
     * @returns Formatted versions.
     */
    async function get_versions(params) {
        const { package: packageName } = params;
        let limit = 10;
        if (params.limit) {
            const parsedLimit = parseInt(params.limit, 10);
            if (!isNaN(parsedLimit) && parsedLimit > 0) {
                limit = parsedLimit;
            }
        }
        if (!packageName) {
            throw new Error("包名不能为空");
        }
        console.log(`正在获取npm包版本历史: ${packageName}`);
        const url = `${BASE_URL}/${packageName}`;
        const request = client.newRequest()
            .url(url)
            .method('GET')
            .headers({
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept": "application/json"
            });
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            if (response.statusCode === 404) {
                throw new Error(`包 ${packageName} 不存在`);
            }
            throw new Error(`HTTP 错误! 状态码: ${response.statusCode}`);
        }
        const jsonResponse = JSON.parse(response.content);
        const versions = Object.entries(jsonResponse.versions || {}).slice(-limit);
        return format_versions(versions);
    }
    /**
     * Search packages.
     * @param params Parameters including query and limit.
     * @returns Formatted search results.
     */
    async function search_packages(params) {
        const { query } = params;
        let limit = 10;
        if (params.limit) {
            const parsedLimit = parseInt(params.limit, 10);
            if (!isNaN(parsedLimit) && parsedLimit > 0) {
                limit = parsedLimit;
            }
        }
        if (!query) {
            throw new Error("搜索关键词不能为空");
        }
        console.log(`正在搜索npm包: ${query}`);
        const url = `${BASE_URL}/-/v1/search?text=${encodeURIComponent(query)}&size=${limit}`;
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
        return format_search_results(jsonResponse.objects);
    }
    /**
     * Format package info for LLM.
     * @param info Package info object.
     * @returns Formatted string.
     */
    function format_package_info(info) {
        const latestVersion = info['dist-tags'] && info['dist-tags'].latest;
        const latest = info.versions && info.versions[latestVersion];
        return `包名: ${info.name || '未知'}
版本: ${latestVersion || '未知'}
描述: ${info.description || '无'}
作者: ${info.author ? (info.author.name || info.author) : '未知'}
发布时间: ${new Date(info.time && info.time.created).toLocaleString('zh-CN') || '未知'}
最后更新: ${new Date(info.time && info.time.modified).toLocaleString('zh-CN') || '未知'}
主页: ${info.homepage || '无'}
仓库: ${info.repository ? (typeof info.repository === 'object' ? info.repository.url : info.repository) : '无'}
关键词: ${(info.keywords || []).join(', ') || '无'}
许可: ${info.license || '未知'}
依赖数: ${Object.keys(info.dependencies || {}).length}
下载量(周): ${info.downloads && info.downloads.lastWeek ? info.downloads.lastWeek.toLocaleString() : '未知'}

最新版本信息:
- 发布时间: ${new Date((info.time && info.time[latestVersion]) || 0).toLocaleString('zh-CN')}
- 引擎要求: ${latest && latest.engines ? JSON.stringify(latest.engines) : '无'}`;
    }
    /**
     * Format versions for LLM.
     * @param versions List of version entries.
     * @returns Formatted string.
     */
    function format_versions(versions) {
        if (!versions || versions.length === 0) {
            return "未找到版本信息";
        }
        const output = versions.map(([version, details]) => {
            return `${version}`;
        });
        return `版本历史 (最近${versions.length}个):\n${output.join('\n')}`;
    }
    /**
     * Format search results for LLM.
     * @param results List of search result objects.
     * @returns Formatted string.
     */
    function format_search_results(results) {
        if (!results || results.length === 0) {
            return "未找到匹配的包";
        }
        const output = results.map((item, index) => {
            const pkg = item.package;
            return `${index + 1}. ${pkg.name}@${pkg.version}
   描述: ${pkg.description || '无'}
   下载量(周): ${pkg.downloads && pkg.downloads.lastWeek ? pkg.downloads.lastWeek.toLocaleString() : '未知'}
   评分: ${pkg.score && pkg.score.final ? (pkg.score.final * 100).toFixed(1) : '未知'}`;
        });
        return `搜索结果 (${results.length}个):\n\n${output.join('\n\n')}`;
    }
    /**
     * Wraps function calls for standardized success/error handling.
     */
    async function npm_wrap(func, params, successMessage, failMessage) {
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
        get_package_info: (params) => npm_wrap(get_package_info, params, '获取包信息完成', '获取包信息失败'),
        get_versions: (params) => npm_wrap(get_versions, params, '获取版本历史完成', '获取版本历史失败'),
        search_packages: (params) => npm_wrap(search_packages, params, '搜索完成', '搜索失败'),
    };
})();
exports.get_package_info = npm.get_package_info;
exports.get_versions = npm.get_versions;
exports.search_packages = npm.search_packages;
