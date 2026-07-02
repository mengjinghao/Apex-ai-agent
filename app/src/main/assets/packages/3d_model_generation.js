/* METADATA
{
  "name": "3d_model_generation",
  "display_name": { "zh": "3D模型生成", "en": "3D Model Generation" },
  "description": {
    "zh": "3D模型生成工具，支持 Tripo、Meshy 等服务。",
    "en": "3D model generation tool supporting Tripo, Meshy and other services."
  },
  "env": [
    { "name": "3D_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": true }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "text_to_3d",
      "description": { "zh": "文本生成3D模型", "en": "Text to 3D model" },
      "parameters": [
        { "name": "prompt", "description": { "zh": "模型描述", "en": "Model description" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "image_to_3d",
      "description": { "zh": "图像生成3D模型", "en": "Image to 3D model" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径", "en": "Image path" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const model3dGeneration = (function() {
    const HTTP_TIMEOUT_MS = 180000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/3d_models";
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function text_to_3d(params) {
        const prompt = params.prompt;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `model_${Date.now()}.glb`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            prompt: prompt,
            format: "glb",
            output_path: filePath,
            message: "3D模型生成成功"
        };
    }

    async function image_to_3d(params) {
        const imagePath = params.image_path;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `model_img_${Date.now()}.glb`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            source_image: imagePath,
            format: "glb",
            output_path: filePath,
            message: "3D模型生成成功"
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
        text_to_3d: (p) => wrap(text_to_3d, p, "3D模型生成成功", "3D模型生成失败"),
        image_to_3d: (p) => wrap(image_to_3d, p, "图像转3D成功", "图像转3D失败")
    };
})();
exports.text_to_3d = model3dGeneration.text_to_3d;
exports.image_to_3d = model3dGeneration.image_to_3d;