/* METADATA
{
  "name": "todo_list",
  "display_name": { "zh": "待办事项", "en": "Todo List" },
  "description": {
    "zh": "待办事项管理器，支持任务分类、优先级设置。",
    "en": "Todo list manager supporting task categorization and priority setting."
  },
  "env": [],
  "category": "Office",
  "tools": [
    {
      "name": "add_task",
      "description": { "zh": "添加任务", "en": "Add task" },
      "parameters": [
        { "name": "title", "description": { "zh": "标题", "en": "Title" }, "type": "string", "required": true },
        { "name": "priority", "description": { "zh": "优先级", "en": "Priority" }, "type": "string", "required": false },
        { "name": "category", "description": { "zh": "分类", "en": "Category" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_tasks",
      "description": { "zh": "获取任务列表", "en": "Get task list" },
      "parameters": [
        { "name": "status", "description": { "zh": "状态", "en": "Status" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "complete_task",
      "description": { "zh": "完成任务", "en": "Complete task" },
      "parameters": [
        { "name": "task_id", "description": { "zh": "任务ID", "en": "Task ID" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const todoList = (function() {
    async function add_task(params) {
        const title = params.title;
        const priority = params.priority || "medium";
        const category = params.category || "默认";
        return {
            id: `task_${Date.now()}`,
            title: title,
            priority: priority,
            category: category,
            status: "pending",
            created_at: new Date().toISOString()
        };
    }

    async function get_tasks(params) {
        const status = params.status;
        return {
            tasks: [
                { id: "task_1", title: "完成任务", priority: "high", status: "pending" }
            ],
            count: 1
        };
    }

    async function complete_task(params) {
        const taskId = params.task_id;
        return {
            task_id: taskId,
            status: "completed",
            completed_at: new Date().toISOString()
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
        add_task: (p) => wrap(add_task, p, "任务添加成功", "任务添加失败"),
        get_tasks: (p) => wrap(get_tasks, p, "任务列表获取成功", "任务列表获取失败"),
        complete_task: (p) => wrap(complete_task, p, "任务完成成功", "任务完成失败")
    };
})();
exports.add_task = todoList.add_task;
exports.get_tasks = todoList.get_tasks;
exports.complete_task = todoList.complete_task;