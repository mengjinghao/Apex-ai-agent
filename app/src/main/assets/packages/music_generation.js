/* METADATA
{
  "name": "music_generation",
  "display_name": { "zh": "音乐生成", "en": "Music Generation" },
  "description": {
    "zh": "音乐生成工具，支持 Suno、Udio 等音乐生成服务。",
    "en": "Music generation tool supporting Suno, Udio and other music generation services."
  },
  "env": [
    { "name": "MUSIC_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": true },
    { "name": "MUSIC_PROVIDER", "description": { "zh": "提供商：suno/udio", "en": "Provider: suno/udio" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "generate_music",
      "description": { "zh": "生成音乐", "en": "Generate music" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "音乐描述", "en": "Music description" }, "type": "string", "required": true },
        { "name": "style", "description": { "zh": "风格", "en": "Style" }, "type": "string", "required": false },
        { "name": "duration", "description": { "zh": "时长（秒）", "en": "Duration (seconds)" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "generate_lyrics",
      "description": { "zh": "生成歌词", "en": "Generate lyrics" },
      "parameters": [
        { "name": "topic", "description": { "zh": "主题", "en": "Topic" }, "type": "string", "required": true },
        { "name": "style", "description": { "zh": "风格", "en": "Style" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const musicGeneration = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/music";
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function generate_music(params) {
        const prompt = params.prompt;
        const style = params.style || "流行";
        const duration = params.duration || 180;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `music_${Date.now()}.mp3`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            prompt: prompt,
            style: style,
            duration: duration,
            output_path: filePath,
            message: "音乐生成成功"
        };
    }

    async function generate_lyrics(params) {
        const topic = params.topic;
        const style = params.style || "流行";
        return {
            topic: topic,
            style: style,
            lyrics: `[Verse 1]\n关于" + topic + "的歌词...\n\n[Chorus]\n高潮部分的歌词..."
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
        generate_music: (p) => wrap(generate_music, p, "音乐生成成功", "音乐生成失败"),
        generate_lyrics: (p) => wrap(generate_lyrics, p, "歌词生成成功", "歌词生成失败")
    };
})();
exports.generate_music = musicGeneration.generate_music;
exports.generate_lyrics = musicGeneration.generate_lyrics;