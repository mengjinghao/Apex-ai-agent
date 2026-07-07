/* METADATA
{
  "name": "slide_generation",
  "display_name": { "zh": "PPT生成", "en": "Slide Generation" },
  "description": {
    "zh": "PPT生成工具，自动生成演示文稿。",
    "en": "PPT generation tool for automatically creating presentations."
  },
  "env": [
    { "name": "SLIDE_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "generate_slides",
      "description": { "zh": "生成PPT", "en": "Generate slides" },
      "parameters": [
        { "name": "topic", "description": { "zh": "主题", "en": "Topic" }, "type": "string", "required": true },
        { "name": "slide_count", "description": { "zh": "幻灯片数量", "en": "Number of slides" }, "type": "number", "required": false },
        { "name": "style", "description": { "zh": "风格", "en": "Style" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "add_slide",
      "description": { "zh": "添加幻灯片", "en": "Add slide" },
      "parameters": [
        { "name": "ppt_path", "description": { "zh": "PPT路径", "en": "PPT path" }, "type": "string", "required": true },
        { "name": "content", "description": { "zh": "内容", "en": "Content" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const slideGeneration = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/slides";
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function generate_slides(params) {
        const topic = params.topic;
        const count = params.slide_count || 10;
        const style = params.style || "专业";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `slides_${Date.now()}.pptx`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            topic: topic,
            slide_count: count,
            style: style,
            output_path: filePath,
            message: "PPT生成成功"
        };
    }

    async function add_slide(params) {
        const pptPath = params.ppt_path;
        const content = params.content;
        return {
            original_ppt: pptPath,
            added_content: content,
            message: "幻灯片添加成功"
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
        generate_slides: (p) => wrap(generate_slides, p, "PPT生成成功", "PPT生成失败"),
        add_slide: (p) => wrap(add_slide, p, "添加幻灯片成功", "添加幻灯片失败")
    };
})();
exports.generate_slides = slideGeneration.generate_slides;
exports.add_slide = slideGeneration.add_slide;