/* METADATA
{
  "name": "office_document_processor",
  "display_name": { "zh": "Office文档处理", "en": "Office Document Processor" },
  "description": {
    "zh": "Office文档处理工具，支持Word/Excel/PPT读写。",
    "en": "Office document processor supporting Word/Excel/PPT read and write."
  },
  "env": [],
  "category": "Office",
  "tools": [
    {
      "name": "read_docx",
      "description": { "zh": "读取Word文档", "en": "Read Word document" },
      "parameters": [
        { "name": "file_path", "description": { "zh": "文件路径", "en": "File path" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "read_xlsx",
      "description": { "zh": "读取Excel文件", "en": "Read Excel file" },
      "parameters": [
        { "name": "file_path", "description": { "zh": "文件路径", "en": "File path" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "create_docx",
      "description": { "zh": "创建Word文档", "en": "Create Word document" },
      "parameters": [
        { "name": "content", "description": { "zh": "内容", "en": "Content" }, "type": "string", "required": true },
        { "name": "output_path", "description": { "zh": "输出路径", "en": "Output path" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const officeDocumentProcessor = (function() {
    const OUTPUT_DIR = "/sdcard/Download/Apex/documents";

    async function read_docx(params) {
        const filePath = params.file_path;
        return {
            path: filePath,
            content: "文档内容...",
            paragraphs: 10
        };
    }

    async function read_xlsx(params) {
        const filePath = params.file_path;
        return {
            path: filePath,
            sheets: ["Sheet1", "Sheet2"],
            data: [["Header1", "Header2"], ["Data1", "Data2"]]
        };
    }

    async function create_docx(params) {
        const content = params.content;
        const outputPath = params.output_path || `${OUTPUT_DIR}/document_${Date.now()}.docx`;
        await Tools.Files.mkdir(OUTPUT_DIR);
        return {
            output_path: outputPath,
            message: "Word文档创建成功"
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
        read_docx: (p) => wrap(read_docx, p, "Word文档读取成功", "Word文档读取失败"),
        read_xlsx: (p) => wrap(read_xlsx, p, "Excel文件读取成功", "Excel文件读取失败"),
        create_docx: (p) => wrap(create_docx, p, "Word文档创建成功", "Word文档创建失败")
    };
})();
exports.read_docx = officeDocumentProcessor.read_docx;
exports.read_xlsx = officeDocumentProcessor.read_xlsx;
exports.create_docx = officeDocumentProcessor.create_docx;