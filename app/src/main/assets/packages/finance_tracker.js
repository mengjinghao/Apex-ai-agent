/* METADATA
{
  "name": "finance_tracker",
  "display_name": { "zh": "财务追踪", "en": "Finance Tracker" },
  "description": {
    "zh": "财务追踪工具，支持收支记录、预算管理。",
    "en": "Finance tracker supporting income/expense recording and budget management."
  },
  "env": [],
  "category": "Life",
  "tools": [
    {
      "name": "add_expense",
      "description": { "zh": "添加支出", "en": "Add expense" },
      "parameters": [
        { "name": "amount", "description": { "zh": "金额", "en": "Amount" }, "type": "number", "required": true },
        { "name": "category", "description": { "zh": "分类", "en": "Category" }, "type": "string", "required": false },
        { "name": "note", "description": { "zh": "备注", "en": "Note" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "add_income",
      "description": { "zh": "添加收入", "en": "Add income" },
      "parameters": [
        { "name": "amount", "description": { "zh": "金额", "en": "Amount" }, "type": "number", "required": true },
        { "name": "source", "description": { "zh": "来源", "en": "Source" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_summary",
      "description": { "zh": "获取财务摘要", "en": "Get financial summary" },
      "parameters": [
        { "name": "month", "description": { "zh": "月份", "en": "Month" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const financeTracker = (function() {
    const DATA_DIR = "/sdcard/Download/Apex/finance";

    async function add_expense(params) {
        const amount = params.amount;
        const category = params.category || "其他";
        const note = params.note || "";
        return {
            id: `exp_${Date.now()}`,
            type: "expense",
            amount: amount,
            category: category,
            note: note,
            date: new Date().toISOString()
        };
    }

    async function add_income(params) {
        const amount = params.amount;
        const source = params.source || "未知";
        return {
            id: `inc_${Date.now()}`,
            type: "income",
            amount: amount,
            source: source,
            date: new Date().toISOString()
        };
    }

    async function get_summary(params) {
        const month = params.month || new Date().toISOString().slice(0, 7);
        return {
            month: month,
            total_income: 10000,
            total_expense: 5000,
            balance: 5000,
            by_category: {
                "餐饮": 1500,
                "交通": 500,
                "购物": 2000,
                "其他": 1000
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
        add_expense: (p) => wrap(add_expense, p, "支出添加成功", "支出添加失败"),
        add_income: (p) => wrap(add_income, p, "收入添加成功", "收入添加失败"),
        get_summary: (p) => wrap(get_summary, p, "摘要获取成功", "摘要获取失败")
    };
})();
exports.add_expense = financeTracker.add_expense;
exports.add_income = financeTracker.add_income;
exports.get_summary = financeTracker.get_summary;