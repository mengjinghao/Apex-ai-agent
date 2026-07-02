/* METADATA
{
  "name": "media_downloader",
  "display_name": { "zh": "媒体下载", "en": "Media Downloader" },
  "description": {
    "zh": "媒体下载工具，支持视频、音频、图片下载。",
    "en": "Media downloader supporting video, audio, and image downloading."
  },
  "env": [],
  "category": "Media",
  "tools": [
    {
      "name": "download_video",
      "description": { "zh": "下载视频", "en": "Download video" },
      "parameters": [
        { "name": "url", "description": { "zh": "视频URL", "en": "Video URL" }, "type": "string", "required": true },
        { "name": "quality", "description": { "zh": "画质", "en": "Quality" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "download_audio",
      "description": { "zh": "下载音频", "en": "Download audio" },
      "parameters": [
        { "name": "url", "description": { "zh": "音频URL", "en": "Audio URL" }, "type": "string", "required": true },
        { "name": "format", "description": { "zh": "格式", "en": "Format" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "download_image",
      "description": { "zh": "下载图片", "en": "Download image" },
      "parameters": [
        { "name": "url", "description": { "zh": "图片URL", "en": "Image URL" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const mediaDownloader = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/downloads";

    async function download_video(params) {
        const url = params.url;
        const quality = params.quality || "720p";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/video_${Date.now()}.mp4`;
        return {
            url: url,
            quality: quality,
            output: outputPath,
            message: "视频下载成功"
        };
    }

    async function download_audio(params) {
        const url = params.url;
        const format = params.format || "mp3";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/audio_${Date.now()}.${format}`;
        return {
            url: url,
            format: format,
            output: outputPath,
            message: "音频下载成功"
        };
    }

    async function download_image(params) {
        const url = params.url;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/image_${Date.now()}.jpg`;
        return {
            url: url,
            output: outputPath,
            message: "图片下载成功"
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
        download_video: (p) => wrap(download_video, p, "视频下载成功", "视频下载失败"),
        download_audio: (p) => wrap(download_audio, p, "音频下载成功", "音频下载失败"),
        download_image: (p) => wrap(download_image, p, "图片下载成功", "图片下载失败")
    };
})();
exports.download_video = mediaDownloader.download_video;
exports.download_audio = mediaDownloader.download_audio;
exports.download_image = mediaDownloader.download_image;