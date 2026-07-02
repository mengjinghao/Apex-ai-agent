/* METADATA
{
  "name": "health_monitor",
  "display_name": { "zh": "健康监测", "en": "Health Monitor" },
  "description": {
    "zh": "健康监测工具，支持步数、心率、睡眠监测。",
    "en": "Health monitor supporting step count, heart rate, and sleep tracking."
  },
  "env": [],
  "category": "Life",
  "tools": [
    {
      "name": "log_steps",
      "description": { "zh": "记录步数", "en": "Log steps" },
      "parameters": [
        { "name": "steps", "description": { "zh": "步数", "en": "Steps" }, "type": "number", "required": true },
        { "name": "date", "description": { "zh": "日期", "en": "Date" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "log_heart_rate",
      "description": { "zh": "记录心率", "en": "Log heart rate" },
      "parameters": [
        { "name": "bpm", "description": { "zh": "每分钟心跳", "en": "Beats per minute" }, "type": "number", "required": true }
      ]
    },
    {
      "name": "log_sleep",
      "description": { "zh": "记录睡眠", "en": "Log sleep" },
      "parameters": [
        { "name": "hours", "description": { "zh": "睡眠时长", "en": "Hours slept" }, "type": "number", "required": true },
        { "name": "quality", "description": { "zh": "质量", "en": "Quality" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_health_summary",
      "description": { "zh": "获取健康摘要", "en": "Get health summary" },
      "parameters": [
        { "name": "period", "description": { "zh": "周期", "en": "Period" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const healthMonitor = (function() {
    async function log_steps(params) {
        const steps = params.steps;
        const date = params.date || new Date().toISOString().split("T")[0];
        return {
            date: date,
            steps: steps,
            goal: 10000,
            achievement: (steps / 10000 * 100).toFixed(1) + "%"
        };
    }

    async function log_heart_rate(params) {
        const bpm = params.bpm;
        return {
            bpm: bpm,
            status: bpm < 60 ? "偏低" : bpm > 100 ? "偏高" : "正常",
            logged_at: new Date().toISOString()
        };
    }

    async function log_sleep(params) {
        const hours = params.hours;
        const quality = params.quality || "一般";
        return {
            hours: hours,
            quality: quality,
            date: new Date().toISOString().split("T")[0]
        };
    }

    async function get_health_summary(params) {
        const period = params.period || "week";
        return {
            period: period,
            avg_steps: 8500,
            avg_heart_rate: 72,
            avg_sleep_hours: 7.5,
            recommendations: ["今日步数达标", "保持良好睡眠习惯"]
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
        log_steps: (p) => wrap(log_steps, p, "步数记录成功", "步数记录失败"),
        log_heart_rate: (p) => wrap(log_heart_rate, p, "心率记录成功", "心率记录失败"),
        log_sleep: (p) => wrap(log_sleep, p, "睡眠记录成功", "睡眠记录失败"),
        get_health_summary: (p) => wrap(get_health_summary, p, "健康摘要获取成功", "健康摘要获取失败")
    };
})();
exports.log_steps = healthMonitor.log_steps;
exports.log_heart_rate = healthMonitor.log_heart_rate;
exports.log_sleep = healthMonitor.log_sleep;
exports.get_health_summary = healthMonitor.get_health_summary;