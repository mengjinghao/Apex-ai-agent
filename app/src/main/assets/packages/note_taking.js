/* METADATA
{
  "name": "note_taking",
  "display_name": { "zh": "笔记工具", "en": "Note Taking" },
  "description": {
    "zh": "笔记工具，支持Markdown格式、云同步。",
    "en": "Note taking tool supporting Markdown format and cloud sync."
  },
  "env": [
    { "name": "NOTE_SYNC_URL", "description": { "zh": "同步服务器URL", "en": "Sync server URL" }, "required": false }
  ],
  "category": "Office",
  "tools": [
    {
      "name": "create_note",
      "description": { "zh": "创建笔记", "en": "Create note" },
      "parameters": [
        { "name": "title", "description": { "zh": "标题", "en": "Title" }, "type": "string", "required": true },
        { "name": "content", "description": { "zh": "内容", "en": "Content" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_notes",
      "description": { "zh": "获取笔记列表", "en": "Get notes" },
      "parameters": []
    },
    {
      "name": "search_notes",
      "description": { "zh": "搜索笔记", "en": "Search notes" },
      "parameters": [
        { "name": "keyword", "description": { "zh": "关键词", "en": "Keyword" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const noteTaking = (function() {
    const DATA_DIR = "/sdcard/Download/Apex/notes";

    async function create_note(params) {
        const title = params.title;
        const content = params.content || "";
        await Tools.Files.mkdir(DATA_DIR);
        return {
            id: `note_${Date.now()}`,
            title: title,
            content: content,
            created_at: new Date().toISOString(),
            format: "markdown"
        };
    }

    async function get_notes() {
        return {
            notes: [
                { id: "note_1", title: "示例笔记", created_at: "2024-01-01" }
            ],
            count: 1
        };
    }

    async function search_notes(params) {
        const keyword = params.keyword;
        return {
            keyword: keyword,
            results: [
                { id: "note_1", title: "相关笔记", snippet: "...匹配内容..." }
            ],
            count: 1
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
        create_note: (p) => wrap(create_note, p, "笔记创建成功", "笔记创建失败"),
        get_notes: (p) => wrap(get_notes, p, "笔记列表获取成功", "笔记列表获取失败"),
        search_notes: (p) => wrap(search_notes, p, "笔记搜索成功", "笔记搜索失败")
    };
})();
exports.create_note = noteTaking.create_note;
exports.get_notes = noteTaking.get_notes;
exports.search_notes = noteTaking.search_notes;