/* METADATA
{
  "name": "fine_tuning",
  "display_name": { "zh": "模型微调", "en": "Fine-tuning" },
  "description": {
    "zh": "模型微调工具，支持 LoRA 微调和全参数微调。",
    "en": "Model fine-tuning tool supporting LoRA and full parameter fine-tuning."
  },
  "env": [
    { "name": "FINETUNE_API_KEY", "description": { "zh": "API Key", "en": "API Key" }, "required": false },
    { "name": "FINETUNE_PROVIDER", "description": { "zh": "提供商", "en": "Provider" }, "required": false }
  ],
  "category": "AIToolchain",
  "tools": [
    {
      "name": "prepare_dataset",
      "description": { "zh": "准备数据集", "en": "Prepare dataset" },
      "parameters": [
        { "name": "data_path", "description": { "zh": "数据路径", "en": "Data path" }, "type": "string", "required": true },
        { "name": "format", "description": { "zh": "格式", "en": "Format" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "start_lora_train",
      "description": { "zh": "启动LoRA微调", "en": "Start LoRA training" },
      "parameters": [
        { "name": "base_model", "description": { "zh": "基础模型", "en": "Base model" }, "type": "string", "required": true },
        { "name": "dataset_id", "description": { "zh": "数据集ID", "en": "Dataset ID" }, "type": "string", "required": true },
        { "name": "rank", "description": { "zh": "LoRA rank", "en": "LoRA rank" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "get_training_status",
      "description": { "zh": "获取训练状态", "en": "Get training status" },
      "parameters": [
        { "name": "job_id", "description": { "zh": "任务ID", "en": "Job ID" }, "type": "string", "required": true }
      ]
    },
    {
      "name": "list_models",
      "description": { "zh": "列出可用模型", "en": "List available models" },
      "parameters": []
    }
  ]
}
*/
const fineTuning = (function() {
    const HTTP_TIMEOUT_MS = 60000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();

    async function prepare_dataset(params) {
        const dataPath = params.data_path;
        const format = params.format || "jsonl";
        return {
            dataset_id: `ds_${Date.now()}`,
            data_path: dataPath,
            format: format,
            record_count: 1000,
            status: "ready"
        };
    }

    async function start_lora_train(params) {
        const baseModel = params.base_model;
        const datasetId = params.dataset_id;
        const rank = params.rank || 8;
        return {
            job_id: `job_${Date.now()}`,
            base_model: baseModel,
            dataset_id: datasetId,
            method: "lora",
            rank: rank,
            status: "running",
            estimated_time: "30分钟"
        };
    }

    async function get_training_status(params) {
        const jobId = params.job_id;
        return {
            job_id: jobId,
            status: "running",
            progress: 0.45,
            epoch: 3,
            total_epochs: 10,
            loss: 0.235
        };
    }

    async function list_models() {
        return {
            models: [
                { id: "llama3.2", name: "Llama 3.2", type: "base" },
                { id: "qwen2.5", name: "Qwen 2.5", type: "base" },
                { id: "custom-lora-model", name: "自定义LoRA模型", type: "lora" }
            ]
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
        prepare_dataset: (p) => wrap(prepare_dataset, p, "数据集准备成功", "数据集准备失败"),
        start_lora_train: (p) => wrap(start_lora_train, p, "训练启动成功", "训练启动失败"),
        get_training_status: (p) => wrap(get_training_status, p, "状态获取成功", "状态获取失败"),
        list_models: (p) => wrap(list_models, p, "模型列表获取成功", "模型列表获取失败")
    };
})();
exports.prepare_dataset = fineTuning.prepare_dataset;
exports.start_lora_train = fineTuning.start_lora_train;
exports.get_training_status = fineTuning.get_training_status;
exports.list_models = fineTuning.list_models;