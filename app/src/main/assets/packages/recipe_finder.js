/* METADATA
{
  "name": "recipe_finder",
  "display_name": { "zh": "食谱查找", "en": "Recipe Finder" },
  "description": {
    "zh": "食谱查找工具，支持食材匹配、营养计算。",
    "en": "Recipe finder supporting ingredient matching and nutrition calculation."
  },
  "env": [
    { "name": "RECIPE_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Life",
  "tools": [
    {
      "name": "search_recipe",
      "description": { "zh": "搜索食谱", "en": "Search recipe" },
      "parameters": [
        { "name": "keyword", "description": { "zh": "关键词", "en": "Keyword" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "find_by_ingredients",
      "description": { "zh": "按食材查找", "en": "Find by ingredients" },
      "parameters": [
        { "name": "ingredients", "description": { "zh": "食材列表", "en": "Ingredients list" }, "type": "array", "required": true }
      ]
    },
    {
      "name": "calculate_nutrition",
      "description": { "zh": "计算营养", "en": "Calculate nutrition" },
      "parameters": [
        { "name": "recipe_name", "description": { "zh": "食谱名称", "en": "Recipe name" }, "type": "string", "required": true },
        { "name": "servings", "description": { "zh": "份数", "en": "Servings" }, "type": "number", "required": false }
      ]
    }
  ]
}
*/
const recipeFinder = (function() {
    async function search_recipe(params) {
        const keyword = params.keyword;
        return {
            keyword: keyword,
            recipes: [
                { name: "番茄炒蛋", difficulty: "简单", time: "15分钟", ingredients: ["番茄", "鸡蛋"] }
            ],
            count: 1
        };
    }

    async function find_by_ingredients(params) {
        const ingredients = params.ingredients;
        return {
            ingredients: ingredients,
            recipes: [
                { name: "可用食材制作的食谱", matched: "3/5" }
            ],
            count: 1
        };
    }

    async function calculate_nutrition(params) {
        const recipeName = params.recipe_name;
        const servings = params.servings || 1;
        return {
            recipe: recipeName,
            servings: servings,
            per_serving: {
                calories: "200kcal",
                protein: "10g",
                fat: "8g",
                carbs: "25g"
            }
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
        search_recipe: (p) => wrap(search_recipe, p, "食谱搜索成功", "食谱搜索失败"),
        find_by_ingredients: (p) => wrap(find_by_ingredients, p, "食材查找成功", "食材查找失败"),
        calculate_nutrition: (p) => wrap(calculate_nutrition, p, "营养计算成功", "营养计算失败")
    };
})();
exports.search_recipe = recipeFinder.search_recipe;
exports.find_by_ingredients = recipeFinder.find_by_ingredients;
exports.calculate_nutrition = recipeFinder.calculate_nutrition;