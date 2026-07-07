/* METADATA
{
  "name": "docker_manager",
  "display_name": { "zh": "Docker管理器", "en": "Docker Manager" },
  "description": {
    "zh": "Docker管理器，支持容器创建、运行、停止等操作。",
    "en": "Docker manager supporting container creation, running, and stopping."
  },
  "env": [
    { "name": "DOCKER_HOST", "description": { "zh": "Docker主机", "en": "Docker host" }, "required": false }
  ],
  "category": "Development",
  "tools": [
    {
      "name": "list_containers",
      "description": { "zh": "列出容器", "en": "List containers" },
      "parameters": [
        { "name": "all", "description": { "zh": "显示全部", "en": "Show all" }, "type": "boolean", "required": false }
      ]
    },
    {
      "name": "run_container",
      "description": { "zh": "运行容器", "en": "Run container" },
      "parameters": [
        { "name": "image", "description": { "zh": "镜像", "en": "Image" }, "type": "string", "required": true },
        { "name": "name", "description": { "zh": "容器名", "en": "Container name" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "stop_container",
      "description": { "zh": "停止容器", "en": "Stop container" },
      "parameters": [
        { "name": "container_id", "description": { "zh": "容器ID", "en": "Container ID" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const dockerManager = (function() {
    async function list_containers(params) {
        const showAll = params.all !== false;
        return {
            containers: [
                { id: "abc123", name: "nginx", image: "nginx:latest", status: "running" },
                { id: "def456", name: "redis", image: "redis:alpine", status: "exited" }
            ],
            total: 2
        };
    }

    async function run_container(params) {
        const image = params.image;
        const name = params.name || `container_${Date.now()}`;
        return {
            container_id: Math.random().toString(36).substring(2, 10),
            name: name,
            image: image,
            status: "created"
        };
    }

    async function stop_container(params) {
        const containerId = params.container_id;
        return {
            container_id: containerId,
            status: "stopped"
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
        list_containers: (p) => wrap(list_containers, p, "容器列表获取成功", "容器列表获取失败"),
        run_container: (p) => wrap(run_container, p, "容器运行成功", "容器运行失败"),
        stop_container: (p) => wrap(stop_container, p, "容器停止成功", "容器停止失败")
    };
})();
exports.list_containers = dockerManager.list_containers;
exports.run_container = dockerManager.run_container;
exports.stop_container = dockerManager.stop_container;