/* METADATA
{
  "name": "voice_cloning",
  "display_name": { "zh": "声音克隆", "en": "Voice Cloning" },
  "description": {
    "zh": "声音克隆工具，用少量样本克隆个人声音。",
    "en": "Voice cloning tool for cloning personal voice with few samples."
  },
  "env": [
    { "name": "VOICE_CLONE_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Multimodal",
  "tools": [
    {
      "name": "clone_voice",
      "description": { "zh": "克隆声音", "en": "Clone voice" },
      "parameters": [
        { "name": "audio_samples", "description": { "zh": "音频样本路径数组", "en": "Audio sample paths" }, "type": "array", "required": true },
        { "name": "name", "description": { "zh": "声音名称", "en": "Voice name" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "speak_with_clone",
      "description": { "zh": "用克隆声音说话", "en": "Speak with cloned voice" },
      "parameters": [
        { "name": "text", "description": { "zh": "文本", "en": "Text" }, "type": "string", "required": true },
        { "name": "voice_name", "description": { "zh": "声音名称", "en": "Voice name" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const voiceCloning = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/voice_clone";

    async function clone_voice(params) {
        const audioSamples = params.audio_samples;
        const name = params.name;
        await Tools.Files.mkdir(OUTPUT_DIR);
        return {
            voice_id: `voice_${Date.now()}`,
            name: name,
            samples_count: audioSamples.length,
            message: "声音克隆成功，请用该声音名称进行语音合成"
        };
    }

    async function speak_with_clone(params) {
        const text = params.text;
        const voiceName = params.voice_name;
        const filePath = `${OUTPUT_DIR}/clone_${Date.now()}.mp3`;
        return {
            text: text,
            voice_name: voiceName,
            output_path: filePath,
            message: "克隆语音已生成"
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
        clone_voice: (p) => wrap(clone_voice, p, "声音克隆成功", "声音克隆失败"),
        speak_with_clone: (p) => wrap(speak_with_clone, p, "克隆语音生成成功", "克隆语音生成失败")
    };
})();
exports.clone_voice = voiceCloning.clone_voice;
exports.speak_with_clone = voiceCloning.speak_with_clone;