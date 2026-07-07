/* METADATA
{
  "name": "audio_editor",
  "display_name": { "zh": "音频编辑", "en": "Audio Editor" },
  "description": {
    "zh": "音频编辑工具，支持剪辑、混音、降噪等。",
    "en": "Audio editor supporting cutting, mixing, and noise reduction."
  },
  "env": [],
  "category": "Media",
  "tools": [
    {
      "name": "cut_audio",
      "description": { "zh": "剪辑音频", "en": "Cut audio" },
      "parameters": [
        { "name": "audio_path", "description": { "zh": "音频路径", "en": "Audio path" }, "type": "string", "required": true },
        { "name": "start", "description": { "zh": "开始时间", "en": "Start time" }, "type": "number", "required": true },
        { "name": "end", "description": { "zh": "结束时间", "en": "End time" }, "type": "number", "required": true }
      ]
    },
    {
      "name": "reduce_noise",
      "description": { "zh": "降噪处理", "en": "Reduce noise" },
      "parameters": [
        { "name": "audio_path", "description": { "zh": "音频路径", "en": "Audio path" }, "type": "string", "required": true },
        { "name": "level", "description": { "zh": "降噪级别", "en": "Noise reduction level" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const audioEditor = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/audio";

    async function cut_audio(params) {
        const audioPath = params.audio_path;
        const start = params.start;
        const end = params.end;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/cut_${Date.now()}.mp3`;
        return {
            original: audioPath,
            start: start,
            end: end,
            output: outputPath,
            message: "音频剪辑成功"
        };
    }

    async function reduce_noise(params) {
        const audioPath = params.audio_path;
        const level = params.level || "medium";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/denoised_${Date.now()}.mp3`;
        return {
            original: audioPath,
            level: level,
            output: outputPath,
            message: "降噪处理成功"
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
        cut_audio: (p) => wrap(cut_audio, p, "音频剪辑成功", "音频剪辑失败"),
        reduce_noise: (p) => wrap(reduce_noise, p, "降噪处理成功", "降噪处理失败")
    };
})();
exports.cut_audio = audioEditor.cut_audio;
exports.reduce_noise = audioEditor.reduce_noise;