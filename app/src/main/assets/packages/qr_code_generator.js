/* METADATA
{
  "name": "qr_code_generator",
  "display_name": { "zh": "二维码生成", "en": "QR Code Generator" },
  "description": {
    "zh": "二维码生成工具，支持文本、网址、名片等。",
    "en": "QR code generator supporting text, URLs, and vCards."
  },
  "env": [],
  "category": "Media",
  "tools": [
    {
      "name": "generate_qr",
      "description": { "zh": "生成二维码", "en": "Generate QR code" },
      "parameters": [
        { "name": "content", "description": { "zh": "内容", "en": "Content" }, "type": "string", "required": true },
        { "name": "size", "description": { "zh": "尺寸", "en": "Size" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "generate_url_qr",
      "description": { "zh": "生成URL二维码", "en": "Generate URL QR code" },
      "parameters": [
        { "name": "url", "description": { "zh": "网址", "en": "URL" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "generate_vcard_qr",
      "description": { "zh": "生成名片二维码", "en": "Generate vCard QR code" },
      "parameters": [
        { "name": "name", "description": { "zh": "姓名", "en": "Name" }, "type": "string", "required": true },
        { "name": "phone", "description": { "zh": "电话", "en": "Phone" }, "type": "string", "required": false },
        { "name": "email", "description": { "zh": "邮箱", "en": "Email" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const qrCodeGenerator = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/qr";

    async function generate_qr(params) {
        const content = params.content;
        const size = params.size || 300;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/qr_${Date.now()}.png`;
        return {
            content: content,
            size: size,
            output: outputPath,
            message: "二维码生成成功"
        };
    }

    async function generate_url_qr(params) {
        const url = params.url;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/url_qr_${Date.now()}.png`;
        return {
            url: url,
            output: outputPath,
            message: "URL二维码生成成功"
        };
    }

    async function generate_vcard_qr(params) {
        const { name, phone, email } = params;
        const content = `BEGIN:VCARD\nVERSION:3.0\nFN:${name}\nTEL:${phone || ""}\nEMAIL:${email || ""}\nEND:VCARD`;
        await Tools.Files.mkdir(OUTPUT_DIR);
        const outputPath = `${OUTPUT_DIR}/vcard_qr_${Date.now()}.png`;
        return {
            name: name,
            output: outputPath,
            message: "名片二维码生成成功"
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
        generate_qr: (p) => wrap(generate_qr, p, "二维码生成成功", "二维码生成失败"),
        generate_url_qr: (p) => wrap(generate_url_qr, p, "URL二维码生成成功", "URL二维码生成失败"),
        generate_vcard_qr: (p) => wrap(generate_vcard_qr, p, "名片二维码生成成功", "名片二维码生成失败")
    };
})();
exports.generate_qr = qrCodeGenerator.generate_qr;
exports.generate_url_qr = qrCodeGenerator.generate_url_qr;
exports.generate_vcard_qr = qrCodeGenerator.generate_vcard_qr;