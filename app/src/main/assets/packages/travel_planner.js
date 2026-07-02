/* METADATA
{
  "name": "travel_planner",
  "display_name": { "zh": "旅行规划", "en": "Travel Planner" },
  "description": {
    "zh": "旅行规划工具，支持行程安排、酒店预订辅助。",
    "en": "Travel planner supporting itinerary arrangement and hotel booking assistance."
  },
  "env": [
    { "name": "TRAVEL_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Life",
  "tools": [
    {
      "name": "plan_itinerary",
      "description": { "zh": "规划行程", "en": "Plan itinerary" },
      "parameters": [
        { "name": "destination", "description": { "zh": "目的地", "en": "Destination" }, "type": "string", "required": true },
        { "name": "days", "description": { "zh": "天数", "en": "Days" }, "type": "number", "required": true }
      ]
    },
    {
      "name": "search_hotels",
      "description": { "zh": "搜索酒店", "en": "Search hotels" },
      "parameters": [
        { "name": "city", "description": { "zh": "城市", "en": "City" }, "type": "string", "required": true },
        { "name": "check_in", "description": { "zh": "入住日期", "en": "Check-in date" }, "type": "string", "required": false },
        { "name": "check_out", "description": { "zh": "退房日期", "en": "Check-out date" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_attractions",
      "description": { "zh": "获取景点", "en": "Get attractions" },
      "parameters": [
        { "name": "destination", "description": { "zh": "目的地", "en": "Destination" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const travelPlanner = (function() {
    async function plan_itinerary(params) {
        const destination = params.destination;
        const days = params.days;
        const itinerary = [];
        for (let i = 0; i < days; i++) {
            itinerary.push({
                day: i + 1,
                date: `2024-01-0${i + 1}`,
                activities: ["景点A", "景点B", "晚餐"]
            });
        }
        return {
            destination: destination,
            days: days,
            itinerary: itinerary
        };
    }

    async function search_hotels(params) {
        const city = params.city;
        const checkIn = params.check_in;
        const checkOut = params.check_out;
        return {
            city: city,
            check_in: checkIn,
            check_out: checkOut,
            hotels: [
                { name: "示例酒店", rating: 4.5, price: 299, location: "市中心" }
            ],
            count: 1
        };
    }

    async function get_attractions(params) {
        const destination = params.destination;
        return {
            destination: destination,
            attractions: [
                { name: "景点A", rating: 4.8, ticket: "50元", recommended_time: "2小时" },
                { name: "景点B", rating: 4.5, ticket: "免费", recommended_time: "3小时" }
            ],
            count: 2
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
        plan_itinerary: (p) => wrap(plan_itinerary, p, "行程规划成功", "行程规划失败"),
        search_hotels: (p) => wrap(search_hotels, p, "酒店搜索成功", "酒店搜索失败"),
        get_attractions: (p) => wrap(get_attractions, p, "景点获取成功", "景点获取失败")
    };
})();
exports.plan_itinerary = travelPlanner.plan_itinerary;
exports.search_hotels = travelPlanner.search_hotels;
exports.get_attractions = travelPlanner.get_attractions;