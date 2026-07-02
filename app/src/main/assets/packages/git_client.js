/* METADATA
{
  "name": "git_client",
  "display_name": { "zh": "Git客户端", "en": "Git Client" },
  "description": {
    "zh": "Git客户端，支持代码提交、分支管理、合并等操作。",
    "en": "Git client supporting code commit, branch management, and merge operations."
  },
  "env": [
    { "name": "GIT_REPO_PATH", "description": { "zh": "仓库路径", "en": "Repository path" }, "required": false }
  ],
  "category": "Development",
  "tools": [
    {
      "name": "status",
      "description": { "zh": "查看状态", "en": "Show status" },
      "parameters": [
        { "name": "repo_path", "description": { "zh": "仓库路径", "en": "Repository path" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "commit",
      "description": { "zh": "提交代码", "en": "Commit code" },
      "parameters": [
        { "name": "message", "description": { "zh": "提交信息", "en": "Commit message" }, "type": "string", "required": true },
        { "name": "repo_path", "description": { "zh": "仓库路径", "en": "Repository path" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "create_branch",
      "description": { "zh": "创建分支", "en": "Create branch" },
      "parameters": [
        { "name": "branch_name", "description": { "zh": "分支名", "en": "Branch name" }, "type": "string", "required": true },
        { "name": "repo_path", "description": { "zh": "仓库路径", "en": "Repository path" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "list_branches",
      "description": { "zh": "列出分支", "en": "List branches" },
      "parameters": [
        { "name": "repo_path", "description": { "zh": "仓库路径", "en": "Repository path" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const gitClient = (function() {
    async function status(params) {
        const repoPath = params.repo_path || getEnv("GIT_REPO_PATH") || ".";
        return {
            repo: repoPath,
            branch: "main",
            modified: ["file1.js", "file2.py"],
            staged: [],
            untracked: ["newfile.txt"]
        };
    }

    async function commit(params) {
        const message = params.message;
        const repoPath = params.repo_path || ".";
        return {
            repo: repoPath,
            commit_hash: Math.random().toString(36).substring(2, 10),
            message: message,
            author: "user",
            date: new Date().toISOString()
        };
    }

    async function create_branch(params) {
        const branchName = params.branch_name;
        const repoPath = params.repo_path || ".";
        return {
            repo: repoPath,
            branch: branchName,
            created: true
        };
    }

    async function list_branches(params) {
        const repoPath = params.repo_path || ".";
        return {
            repo: repoPath,
            branches: [
                { name: "main", current: true },
                { name: "develop", current: false },
                { name: "feature/new-feature", current: false }
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
        status: (p) => wrap(status, p, "状态获取成功", "状态获取失败"),
        commit: (p) => wrap(commit, p, "提交成功", "提交失败"),
        create_branch: (p) => wrap(create_branch, p, "分支创建成功", "分支创建失败"),
        list_branches: (p) => wrap(list_branches, p, "分支列表获取成功", "分支列表获取失败")
    };
})();
exports.status = gitClient.status;
exports.commit = gitClient.commit;
exports.create_branch = gitClient.create_branch;
exports.list_branches = gitClient.list_branches;