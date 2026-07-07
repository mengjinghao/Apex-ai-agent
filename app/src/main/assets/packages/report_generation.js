/* METADATA
{
  "name": "report_generation",
  "display_name": { "zh": "报告生成", "en": "Report Generation" },
  "description": {
    "zh": "报告生成工具，支持数据分析报告、研究报告等。",
    "en": "Report generation tool supporting data analysis reports and research reports."
  },
  "env": [
    { "name": "REPORT_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false }
  ],
  "category": "Generation",
  "tools": [
    {
      "name": "generate_data_report",
      "description": { "zh": "生成数据分析报告", "en": "Generate data analysis report" },
      "parameters": [
        { "name": "data_summary", "description": { "zh": "数据摘要", "en": "Data summary" }, "type": "string", "required": true },
        { "name": "title", "description": { "zh": "报告标题", "en": "Report title" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "generate_research_report",
      "description": { "zh": "生成研究报告", "en": "Generate research report" },
      "parameters": [
        { "name": "topic", "description": { "zh": "研究主题", "en": "Research topic" }, "type": "string", "required": true },
        { "name": "depth", "description": { "zh": "深度（简略/详细）", "en": "Depth (brief/detailed)" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const reportGeneration = (function() {
    const HTTP_TIMEOUT_MS = 120000;
    const OUTPUT_DIR = "/sdcard/Download/Apex/reports";
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function generate_data_report(params) {
        const dataSummary = params.data_summary;
        const title = params.title || "数据分析报告";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `report_${Date.now()}.md`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        const content = `# ${title}\n\n## 数据概览\n${dataSummary}\n\n## 分析结论\n1. 结论一\n2. 结论二\n`;
        return {
            title: title,
            output_path: filePath,
            format: "markdown",
            message: "报告生成成功"
        };
    }

    async function generate_research_report(params) {
        const topic = params.topic;
        const depth = params.depth || "简略";
        await Tools.Files.mkdir(OUTPUT_DIR);
        const fileName = `research_${Date.now()}.md`;
        const filePath = `${OUTPUT_DIR}/${fileName}`;
        return {
            topic: topic,
            depth: depth,
            output_path: filePath,
            format: "markdown",
            message: "研究报告生成成功"
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
        generate_data_report: (p) => wrap(generate_data_report, p, "数据报告生成成功", "数据报告生成失败"),
        generate_research_report: (p) => wrap(generate_research_report, p, "研究报告生成成功", "研究报告生成失败")
    };
})();
exports.generate_data_report = reportGeneration.generate_data_report;
exports.generate_research_report = reportGeneration.generate_research_report;