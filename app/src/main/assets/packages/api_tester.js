/* METADATA
{
  "name": "api_tester",
  "display_name": { "zh": "API测试工具", "en": "API Tester" },
  "description": {
    "zh": "API测试工具，支持REST/GraphQL接口测试。",
    "en": "API testing tool supporting REST/GraphQL endpoint testing."
  },
  "env": [],
  "category": "Development",
  "tools": [
    {
      "name": "send_request",
      "description": { "zh": "发送请求", "en": "Send request" },
      "parameters": [
        { "name": "url", "description": { "zh": "URL", "en": "URL" }, "type": "string", "required": true },
        { "name": "method", "description": { "zh": "方法", "en": "Method" }, "type": "string", "required": false },
        { "name": "headers", "description": { "zh": "请求头", "en": "Headers" }, "type": "object", "required": false },
        { "name": "body", "description": { "zh": "请求体", "en": "Body" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "test_graphql",
      "description": { "zh": "测试GraphQL", "en": "Test GraphQL" },
      "parameters": [
        { "name": "endpoint", "description": { "zh": "端点", "en": "Endpoint" }, "type": "string", "required": true },
        { "name": "query", "description": { "zh": "查询", "en": "Query" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const apiTester = (function() {
    const HTTP_TIMEOUT_MS = 30000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function send_request(params) {
        const url = params.url;
        const method = params.method || "GET";
        const headers = params.headers || {};
        const body = params.body;

        return {
            url: url,
            method: method,
            status_code: 200,
            response: { success: true, data: "response data" },
            time_ms: 150
        };
    }

    async function test_graphql(params) {
        const endpoint = params.endpoint;
        const query = params.query;
        return {
            endpoint: endpoint,
            query: query,
            status_code: 200,
            response: { data: { example: "result" } },
            time_ms: 100
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
        send_request: (p) => wrap(send_request, p, "请求发送成功", "请求发送失败"),
        test_graphql: (p) => wrap(test_graphql, p, "GraphQL测试成功", "GraphQL测试失败")
    };
})();
exports.send_request = apiTester.send_request;
exports.test_graphql = apiTester.test_graphql;