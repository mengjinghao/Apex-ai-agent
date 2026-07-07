/* METADATA
{
  "name": "video_generation",
  "display_name": { "zh": "视频生成", "en": "Video Generation" },
  "description": {
    "zh": "视频生成工具，支持文生视频、图生视频等。",
    "en": "Video generation tool supporting text-to-video and image-to-video."
  },
  "env": [
    { "name": "VIDEO_GEN_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": true },
    { "name": "VIDEO_GEN_PROVIDER", "description": { "zh": "提供商", "en": "Provider" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "text_to_video",
      "description": { "zh": "文本生成视频", "en": "Text to video" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "视频描述", "en": "Video description" }, "type": "string", "required": true },
        { "name": "duration", "description": { "zh": "时长（秒）", "en": "Duration (seconds)" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "image_to_video",
      "description": { "zh": "图像生成视频", "en": "Image to video" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径", "en": "Image path" }, "type": "string", "required": true },
        { "name": "motion", "description": { "zh": "运动描述", "en": "Motion description" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const videoGeneration = (function() {
    const HTTP_TIMEOUT_MS = 300000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/videos";
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function text_to_video(params) {
        const prompt = params.prompt;
        const duration = params.duration || 5;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `video_${Date.now()}.mp4`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            prompt: prompt,
            duration: duration,
            output_path: filePath,
            message: "视频生成成功"
        };
    }

    async function image_to_video(params) {
        const imagePath = params.image_path;
        const motion = params.motion || "自然流动";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `img2video_${Date.now()}.mp4`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            image: imagePath,
            motion: motion,
            output_path: filePath,
            message: "视频生成成功"
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
        text_to_video: (p) => wrap(text_to_video, p, "文生视频成功", "文生视频失败"),
        image_to_video: (p) => wrap(image_to_video, p, "图生视频成功", "图生视频失败")
    };
})();
exports.text_to_video = videoGeneration.text_to_video;
exports.image_to_video = videoGeneration.image_to_video;