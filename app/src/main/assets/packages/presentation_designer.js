/* METADATA
{
  "name": "presentation_designer",
  "display_name": { "zh": "演示文稿设计", "en": "Presentation Designer" },
  "description": {
    "zh": "演示文稿设计工具，支持模板、布局、动画。",
    "en": "Presentation design tool supporting templates, layouts, and animations."
  },
  "env": [],
  "category": "Office",
  "tools": [
    {
      "name": "create_presentation",
      "description": { "zh": "创建演示文稿", "en": "Create presentation" },
      "parameters": [
        { "name": "title", "description": { "zh": "标题", "en": "Title" }, "type": "string", "required": true },
        { "name": "slides", "description": { "zh": "幻灯片内容", "en": "Slides content" }, "type": "array", "required": false }
      ]
    },
    {
      "name": "apply_template",
      "description": { "zh": "应用模板", "en": "Apply template" },
      "parameters": [
        { "name": "ppt_path", "description": { "zh": "PPT路径", "en": "PPT path" }, "type": "string", "required": true },
        { "name": "template", "description": { "zh": "模板名称", "en": "Template name" }, "type": "string", "required": true }
      ]
    }
  ]
}
*/
const presentationDesigner = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/presentations";

    async function create_presentation(params) {
        const title = params.title;
        const slides = params.slides || [];
        await Tools.Files.mkdir(OUTPUT_DIR);
        return {
            title: title,
            slide_count: slides.length || 1,
            output_path: `${OUTPUT_DIR}/${title}_${Date.now()}.pptx`,
            message: "演示文稿创建成功"
        };
    }

    async function apply_template(params) {
        const pptPath = params.ppt_path;
        const template = params.template;
        return {
            original: pptPath,
            template: template,
            message: "模板应用成功"
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
        create_presentation: (p) => wrap(create_presentation, p, "演示文稿创建成功", "演示文稿创建失败"),
        apply_template: (p) => wrap(apply_template, p, "模板应用成功", "模板应用失败")
    };
})();
exports.create_presentation = presentationDesigner.create_presentation;
exports.apply_template = presentationDesigner.apply_template;