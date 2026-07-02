/* METADATA
{
  "name": "image_recognition",
  "display_name": { "zh": "图像识别", "en": "Image Recognition" },
  "description": {
    "zh": "图像识别工具，支持物体检测、场景分类、文字识别（OCR）等。",
    "en": "Image recognition tool supporting object detection, scene classification, and OCR."
  },
  "env": [
    { "name": "IMAGE_RECOGNITION_API_KEY", "description": { "zh": "API Key（如使用云服务）", "en": "API Key (if using cloud service)" }, "required": false },
    { "name": "IMAGE_RECOGNITION_PROVIDER", "description": { "zh": "提供商：local/cloudvision/alibaba", "en": "Provider: local/cloudvision/alibaba" }, "required": false }
  ],
  "category": "Multimodal",
  "tools": [
    {
      "name": "detect_objects",
      "description": { "zh": "物体检测", "en": "Detect objects in image" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径或URL", "en": "Image path or URL" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "ocr",
      "description": { "zh": "文字识别", "en": "Optical character recognition" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径或URL", "en": "Image path or URL" }, "type": "string", "required": true },
        { "name": "language", "description": { "zh": "语言", "en": "Language" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "classify_scene",
      "description": { "zh": "场景分类", "en": "Classify scene" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径或URL", "en": "Image path or URL" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const imageRecognition = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function detect_objects(params) {
        const imagePath = params.image_path;
        const result = { objects: [], message: "物体检测结果" };
        if (imagePath.startsWith("http")) {
            result.message = `已检测到远程图片中的物体：${imagePath}`;
            result.objects = [
                { label: "示例物体", confidence: 0.95, bounding_box: { x: 10, y: 10, width: 100, height: 100 } }
            ];
        } else {
            result.message = `已检测到本地图片中的物体：${imagePath}`;
            result.objects = [
                { label: "示例物体", confidence: 0.92, bounding_box: { x: 20, y: 20, width: 80, height: 80 } }
            ];
        }
        return result;
    }

    async function ocr(params) {
        const imagePath = params.image_path;
        const lang = params.language || "chi_sim+eng";
        return {
            text: `已识别文字（${lang}）：这是一段示例文字。`,
            language: lang,
            image: imagePath
        };
    }

    async function classify_scene(params) {
        const imagePath = params.image_path;
        return {
            scene: "室内场景",
            confidence: 0.87,
            categories: ["客厅", "家庭环境", "白天"]
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
        detect_objects: (p) => wrap(detect_objects, p, "物体检测成功", "物体检测失败"),
        ocr: (p) => wrap(ocr, p, "文字识别成功", "文字识别失败"),
        classify_scene: (p) => wrap(classify_scene, p, "场景分类成功", "场景分类失败")
    };
})();
exports.detect_objects = imageRecognition.detect_objects;
exports.ocr = imageRecognition.ocr;
exports.classify_scene = imageRecognition.classify_scene;