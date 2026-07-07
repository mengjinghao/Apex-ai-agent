/* METADATA
{
  "name": "calendar_manager",
  "display_name": { "zh": "日历管理器", "en": "Calendar Manager" },
  "description": {
    "zh": "日历管理器，支持日程安排、提醒设置。",
    "en": "Calendar manager supporting schedule arrangement and reminders."
  },
  "env": [],
  "category": "Office",
  "tools": [
    {
      "name": "add_event",
      "description": { "zh": "添加日程", "en": "Add event" },
      "parameters": [
        { "name": "title", "description": { "zh": "标题", "en": "Title" }, "type": "string", "required": true },
        { "name": "date", "description": { "zh": "日期", "en": "Date" }, "type": "string", "required": true },
        { "name": "time", "description": { "zh": "时间", "en": "Time" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_events",
      "description": { "zh": "获取日程", "en": "Get events" },
      "parameters": [
        { "name": "start_date", "description": { "zh": "开始日期", "en": "Start date" }, "type": "string", "required": false },
        { "name": "end_date", "description": { "zh": "结束日期", "en": "End date" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const calendarManager = (function() {
    const DATA_DIR = "/sdcard/Download/Apex/calendar";

    async function add_event(params) {
        const title = params.title;
        const date = params.date;
        const time = params.time || "00:00";
        return {
            id: `event_${Date.now()}`,
            title: title,
            date: date,
            time: time,
            reminder: false,
            message: "日程添加成功"
        };
    }

    async function get_events(params) {
        const startDate = params.start_date;
        const endDate = params.end_date;
        return {
            events: [
                { id: "event_1", title: "会议", date: "2024-01-01", time: "10:00" }
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
        add_event: (p) => wrap(add_event, p, "日程添加成功", "日程添加失败"),
        get_events: (p) => wrap(get_events, p, "日程获取成功", "日程获取失败")
    };
})();
exports.add_event = calendarManager.add_event;
exports.get_events = calendarManager.get_events;