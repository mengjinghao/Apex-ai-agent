/* METADATA
{
  "name": "project_management",
  "display_name": { "zh": "项目管理", "en": "Project Management" },
  "description": {
    "zh": "项目管理工具，支持甘特图、任务分配。",
    "en": "Project management tool supporting Gantt charts and task assignment."
  },
  "env": [],
  "category": "Office",
  "tools": [
    {
      "name": "create_project",
      "description": { "zh": "创建项目", "en": "Create project" },
      "parameters": [
        { "name": "name", "description": { "zh": "项目名称", "en": "Project name" }, "type": "string", "required": true },
        { "name": "description", "description": { "zh": "描述", "en": "Description" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "add_task_to_project",
      "description": { "zh": "添加项目任务", "en": "Add project task" },
      "parameters": [
        { "name": "project_id", "description": { "zh": "项目ID", "en": "Project ID" }, "type": "string", "required": true },
        { "name": "task_name", "description": { "zh": "任务名称", "en": "Task name" }, "type": "string", "required": true },
        { "name": "assignee", "description": { "zh": "负责人", "en": "Assignee" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_gantt_data",
      "description": { "zh": "获取甘特图数据", "en": "Get Gantt chart data" },
      "parameters": [
        { "name": "project_id", "description": { "zh": "项目ID", "en": "Project ID" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const projectManagement = (function() {
    async function create_project(params) {
        const name = params.name;
        const description = params.description || "";
        return {
            id: `proj_${Date.now()}`,
            name: name,
            description: description,
            created_at: new Date().toISOString(),
            status: "active"
        };
    }

    async function add_task_to_project(params) {
        const projectId = params.project_id;
        const taskName = params.task_name;
        const assignee = params.assignee || "未分配";
        return {
            id: `ptask_${Date.now()}`,
            project_id: projectId,
            task_name: taskName,
            assignee: assignee,
            status: "pending",
            start_date: new Date().toISOString().split("T")[0]
        };
    }

    async function get_gantt_data(params) {
        const projectId = params.project_id;
        return {
            project_id: projectId,
            tasks: [
                { name: "任务1", start: "2024-01-01", end: "2024-01-15", progress: 50 },
                { name: "任务2", start: "2024-01-10", end: "2024-01-20", progress: 30 }
            ]
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
        create_project: (p) => wrap(create_project, p, "项目创建成功", "项目创建失败"),
        add_task_to_project: (p) => wrap(add_task_to_project, p, "任务添加成功", "任务添加失败"),
        get_gantt_data: (p) => wrap(get_gantt_data, p, "甘特图数据获取成功", "甘特图数据获取失败")
    };
})();
exports.create_project = projectManagement.create_project;
exports.add_task_to_project = projectManagement.add_task_to_project;
exports.get_gantt_data = projectManagement.get_gantt_data;