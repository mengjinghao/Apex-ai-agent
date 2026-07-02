/* METADATA
{
  "name": "food_delivery",
  "display_name": { "zh": "外卖工具", "en": "Food Delivery" },
  "description": {
    "zh": "外卖工具，支持餐厅查询、订单跟踪。",
    "en": "Food delivery tool supporting restaurant search and order tracking."
  },
  "env": [
    { "name": "DELIVERY_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Life",
  "tools": [
    {
      "name": "search_restaurants",
      "description": { "zh": "搜索餐厅", "en": "Search restaurants" },
      "parameters": [
        { "name": "keyword", "description": { "zh": "关键词", "en": "Keyword" }, "type": "string", "required": false },
        { "name": "location", "description": { "zh": "位置", "en": "Location" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "track_order",
      "description": { "zh": "跟踪订单", "en": "Track order" },
      "parameters": [
        { "name": "order_id", "description": { "zh": "订单ID", "en": "Order ID" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const foodDelivery = (function() {
    async function search_restaurants(params) {
        const keyword = params.keyword || "";
        const location = params.location || "当前地点";
        return {
            keyword: keyword,
            location: location,
            restaurants: [
                { name: "示例餐厅", rating: 4.5, delivery_time: "30分钟", minimum: "20元" }
            ],
            count: 1
        };
    }

    async function track_order(params) {
        const orderId = params.order_id;
        return {
            order_id: orderId,
            status: "配送中",
            progress: 0.7,
            estimated_time: "15分钟"
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
        search_restaurants: (p) => wrap(search_restaurants, p, "餐厅搜索成功", "餐厅搜索失败"),
        track_order: (p) => wrap(track_order, p, "订单跟踪成功", "订单跟踪失败")
    };
})();
exports.search_restaurants = foodDelivery.search_restaurants;
exports.track_order = foodDelivery.track_order;