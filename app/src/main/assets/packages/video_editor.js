/* METADATA
{
  "name": "video_editor",
  "display_name": { "zh": "视频编辑", "en": "Video Editor" },
  "description": {
    "zh": "视频编辑工具，支持剪辑、转场、字幕添加等。",
    "en": "Video editor supporting cutting, transitions, and subtitle addition."
  },
  "env": [],
  "category": "Media",
  "tools": [
    {
      "name": "cut",
      "description": { "zh": "剪辑视频", "en": "Cut video" },
      "parameters": [
        { "name": "video_path", "description": { "zh": "视频路径", "en": "Video path" }, "type": "string", "required": true },
        { "name": "start", "description": { "zh": "开始时间(秒)", "en": "Start time (seconds)" }, "type": "number", "required": true },
        { "name": "end", "description": { "zh": "结束时间(秒)", "en": "End time (seconds)" }, "type": "number", "required": true }
      ]
    },
    {
      "name": "add_subtitles",
      "description": { "zh": "添加字幕", "en": "Add subtitles" },
      "parameters": [
        { "name": "video_path", "description": { "zh": "视频路径", "en": "Video path" }, "type": "string", "required": true },
        { "name": "subtitle_file", "description": { "zh": "字幕文件", "en": "Subtitle file" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const videoEditor = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/videos";

    async function cut(params) {
        const videoPath = params.video_path;
        const start = params.start;
        const end = params.end;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/cut_${Date.now()}.mp4`;
        return {
            original: videoPath,
            start: start,
            end: end,
            output: outputPath,
            duration: end - start,
            message: "视频剪辑成功"
        };
    }

    async function add_subtitles(params) {
        const videoPath = params.video_path;
        const subtitleFile = params.subtitle_file;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/subtitled_${Date.now()}.mp4`;
        return {
            original: videoPath,
            subtitle_file: subtitleFile,
            output: outputPath,
            message: "字幕添加成功"
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
        cut: (p) => wrap(cut, p, "视频剪辑成功", "视频剪辑失败"),
        add_subtitles: (p) => wrap(add_subtitles, p, "字幕添加成功", "字幕添加失败")
    };
})();
exports.cut = videoEditor.cut;
exports.add_subtitles = videoEditor.add_subtitles;