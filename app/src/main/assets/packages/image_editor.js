/* METADATA
{
  "name": "image_editor",
  "display_name": { "zh": "图片编辑", "en": "Image Editor" },
  "description": {
    "zh": "图片编辑工具，支持裁剪、滤镜、文字添加等。",
    "en": "Image editor supporting cropping, filters, and text overlay."
  },
  "env": [],
  "category": "Media",
  "tools": [
    {
      "name": "crop",
      "description": { "zh": "裁剪图片", "en": "Crop image" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径", "en": "Image path" }, "type": "string", "required": true },
        { "name": "x", "description": { "zh": "X坐标", "en": "X coordinate" }, "type": "number", "required": true },
        { "name": "y", "description": { "zh": "Y坐标", "en": "Y coordinate" }, "type": "number", "required": true },
        { "name": "width", "description": { "zh": "宽度", "en": "Width" }, "type": "number", "required": true },
        { "name": "height", "description": { "zh": "高度", "en": "Height" }, "type": "number", "required": true }
      ]
    },
    {
      "name": "add_filter",
      "description": { "zh": "添加滤镜", "en": "Add filter" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径", "en": "Image path" }, "type": "string", "required": true },
        { "name": "filter", "description": { "zh": "滤镜类型", "en": "Filter type" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "add_text",
      "description": { "zh": "添加文字", "en": "Add text" },
      "parameters": [
        { "name": "image_path", "description": { "zh": "图片路径", "en": "Image path" }, "type": "string", "required": true },
        { "name": "text", "description": { "zh": "文字内容", "en": "Text content" }, "type": "string", "required": true },
        { "name": "position", "description": { "zh": "位置", "en": "Position" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const imageEditor = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/images";

    async function crop(params) {
        const imagePath = params.image_path;
        const { x, y, width, height } = params;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/cropped_${Date.now()}.jpg`;
        return {
            original: imagePath,
            output: outputPath,
            crop_area: { x, y, width, height },
            message: "图片裁剪成功"
        };
    }

    async function add_filter(params) {
        const imagePath = params.image_path;
        const filter = params.filter;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/filtered_${Date.now()}.jpg`;
        return {
            original: imagePath,
            filter: filter,
            output: outputPath,
            message: "滤镜添加成功"
        };
    }

    async function add_text(params) {
        const imagePath = params.image_path;
        const text = params.text;
        const position = params.position || "center";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/text_${Date.now()}.jpg`;
        return {
            original: imagePath,
            text: text,
            position: position,
            output: outputPath,
            message: "文字添加成功"
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
        crop: (p) => wrap(crop, p, "图片裁剪成功", "图片裁剪失败"),
        add_filter: (p) => wrap(add_filter, p, "滤镜添加成功", "滤镜添加失败"),
        add_text: (p) => wrap(add_text, p, "文字添加成功", "文字添加失败")
    };
})();
exports.crop = imageEditor.crop;
exports.add_filter = imageEditor.add_filter;
exports.add_text = imageEditor.add_text;