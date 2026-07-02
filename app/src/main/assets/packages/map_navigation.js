/* METADATA
{
  "name": "map_navigation",
  "display_name": { "zh": "地图导航", "en": "Map Navigation" },
  "description": {
    "zh": "地图导航工具，支持路线规划、POI查询。",
    "en": "Map navigation tool supporting route planning and POI search."
  },
  "env": [
    { "name": "MAP_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Life",
  "tools": [
    {
      "name": "search_place",
      "description": { "zh": "搜索地点", "en": "Search place" },
      "parameters": [
        { "name": "keyword", "description": { "zh": "关键词", "en": "Keyword" }, "type": "string", "required": true },
        { "name": "city", "description": { "zh": "城市", "en": "City" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "route_planning",
      "description": { "zh": "路线规划", "en": "Route planning" },
      "parameters": [
        { "name": "from", "description": { "zh": "起点", "en": "From" }, "type": "string", "required": true },
        { "name": "to", "description": { "zh": "终点", "en": "To" }, "type": "string", "required": true },
        { "name": "mode", "description": { "zh": "出行方式", "en": "Mode" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const mapNavigation = (function() {
    async function search_place(params) {
        const keyword = params.keyword;
        const city = params.city || "北京";
        return {
            keyword: keyword,
            city: city,
            results: [
                { name: "示例地点", address: "北京市朝阳区", distance: "500m" }
            ],
            count: 1
        };
    }

    async function route_planning(params) {
        const from = params.from;
        const to = params.to;
        const mode = params.mode || "driving";
        return {
            from: from,
            to: to,
            mode: mode,
            distance: "10km",
            duration: "25分钟",
            route: ["路线点1", "路线点2", "路线点3"]
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
        search_place: (p) => wrap(search_place, p, "地点搜索成功", "地点搜索失败"),
        route_planning: (p) => wrap(route_planning, p, "路线规划成功", "路线规划失败")
    };
})();
exports.search_place = mapNavigation.search_place;
exports.route_planning = mapNavigation.route_planning;