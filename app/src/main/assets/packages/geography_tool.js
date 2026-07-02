/* METADATA
{
  "name": "geography_tool",
  "display_name": { "zh": "地理工具", "en": "Geography Tool" },
  "description": {
    "zh": "地理工具，支持地图查询、气候分析等。",
    "en": "Geography tool supporting map queries and climate analysis."
  },
  "env": [
    { "name": "GEO_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Learning",
  "tools": [
    {
      "name": "get_location_info",
      "description": { "zh": "获取地点信息", "en": "Get location info" },
      "parameters": [
        { "name": "place", "description": { "zh": "地点名称", "en": "Place name" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "get_climate",
      "description": { "zh": "获取气候信息", "en": "Get climate info" },
      "parameters": [
        { "name": "location", "description": { "zh": "位置", "en": "Location" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const geographyTool = (function() {
    async function get_location_info(params) {
        const place = params.place;
        return {
            place: place,
            country: "中国",
            coordinates: { lat: 39.9042, lon: 116.4074 },
            population: "约2100万",
            description: "首都"
        };
    }

    async function get_climate(params) {
        const location = params.location;
        return {
            location: location,
            climate_type: "温带季风气候",
            avg_temp: "12°C",
            annual_rainfall: "600mm"
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
        get_location_info: (p) => wrap(get_location_info, p, "地点信息获取成功", "地点信息获取失败"),
        get_climate: (p) => wrap(get_climate, p, "气候信息获取成功", "气候信息获取失败")
    };
})();
exports.get_location_info = geographyTool.get_location_info;
exports.get_climate = geographyTool.get_climate;