/* METADATA
{
  "name": "web-ai-assist",
  "display_name": {
    "zh": "Web AI 助手",
    "en": "Web AI Assistant"
  },
  "description": {
    "zh": "通过浏览器自动化访问免费网页AI服务（豆包、通义千问、智谱），自动开启专家/深度思考模式，智能优化提示词，支持多轮对话和对比分析。",
    "en": "Access free web AI services (Doubao, Qwen, Zhipu) via browser automation with auto-expert mode, intelligent prompt optimization, multi-round dialogue and comparison analysis."
  },
  "enabledByDefault": true,
  "category": "Automatic",
  "tools": [
    {
      "name": "ai_browse",
      "description": {
        "zh": "导航到AI服务网站并自动开启高级模式。",
        "en": "Navigate to an AI service website and auto-enable advanced mode."
      },
      "parameters": [
        { "name": "service", "description": { "zh": "AI服务: doubao/qwen/zhipu", "en": "AI service: doubao/qwen/zhipu" }, "type": "string", "required": true },
        { "name": "url", "description": { "zh": "可选，自定义URL", "en": "Optional, custom URL" }, "type": "string", "required": false },
        { "name": "expertMode", "description": { "zh": "是否开启专家/深度思考模式（默认true）", "en": "Enable expert/deep thinking mode (default true)" }, "type": "boolean", "required": false },
        { "name": "enableSearch", "description": { "zh": "是否开启联网搜索（默认true）", "en": "Enable web search (default true)" }, "type": "boolean", "required": false }
      ]
    },
    {
      "name": "ai_send_message",
      "description": {
        "zh": "向当前打开的AI服务发送消息，自动添加精准提示词。",
        "en": "Send a message to the AI service with automatically optimized prompts."
      },
      "parameters": [
        { "name": "message", "description": { "zh": "发送的消息内容", "en": "Message content to send" }, "type": "string", "required": true },
        { "name": "service", "description": { "zh": "AI服务: doubao/qwen/zhipu", "en": "AI service: doubao/qwen/zhipu" }, "type": "string", "required": true },
        { "name": "waitForResponse", "description": { "zh": "是否等待回复", "en": "Wait for response" }, "type": "boolean", "required": false },
        { "name": "timeoutMs", "description": { "zh": "超时时间(毫秒)", "en": "Timeout in milliseconds" }, "type": "number", "required": false },
        { "name": "promptType", "description": { "zh": "提示词类型: default/analyze/code/write/summarize", "en": "Prompt type: default/analyze/code/write/summarize" }, "type": "string", "required": false },
        { "name": "detailLevel", "description": { "zh": "详细程度: brief/normal/detailed/verbose", "en": "Detail level: brief/normal/detailed/verbose" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "ai_get_response",
      "description": {
        "zh": "获取AI聊天界面的最新回复。",
        "en": "Get the latest response from the AI chat interface."
      },
      "parameters": [
        { "name": "service", "description": { "zh": "AI服务: doubao/qwen/zhipu", "en": "AI service: doubao/qwen/zhipu" }, "type": "string", "required": true },
        { "name": "maxWaitMs", "description": { "zh": "最大等待时间(毫秒)", "en": "Max wait time in milliseconds" }, "type": "number", "required": false }
      ]
    },
    {
      "name": "ai_analyze_page",
      "description": {
        "zh": "使用AI分析当前网页内容。",
        "en": "Use AI to analyze the current web page content."
      },
      "parameters": [
        { "name": "service", "description": { "zh": "AI服务: doubao/qwen/zhipu", "en": "AI service: doubao/qwen/zhipu" }, "type": "string", "required": true },
        { "name": "prompt", "description": { "zh": "分析提示词", "en": "Analysis prompt" }, "type": "string", "required": true },
        { "name": "focusAreas", "description": { "zh": "聚焦区域: content/structure/links/all", "en": "Focus areas: content/structure/links/all" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "ai_compare_response",
      "description": {
        "zh": "向多个AI服务发送相同问题并比较回答。",
        "en": "Send the same question to multiple AI services and compare responses."
      },
      "parameters": [
        { "name": "message", "description": { "zh": "发送的消息内容", "en": "Message content to send" }, "type": "string", "required": true },
        { "name": "services", "description": { "zh": "AI服务列表: doubao/qwen/zhipu", "en": "AI service list: doubao/qwen/zhipu" }, "type": "array", "required": true },
        { "name": "timeoutMs", "description": { "zh": "超时时间(毫秒)", "en": "Timeout in milliseconds" }, "type": "number", "required": false },
        { "name": "promptType", "description": { "zh": "提示词类型", "en": "Prompt type" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "ai_optimize",
      "description": {
        "zh": "分析上次使用情况并优化策略。",
        "en": "Analyze last usage and optimize strategy."
      },
      "parameters": []
    },
    {
      "name": "ai_get_stats",
      "description": {
        "zh": "获取工具使用统计和成功率。",
        "en": "Get tool usage statistics and success rate."
      },
      "parameters": []
    }
  ]
}
*/
/// <reference path="./types/index.d.ts" />

const webAIAssist = (function() {
    const AI_SERVICE_URLS = {
        doubao: "https://doubao.com",
        qwen: "https://qianwen.aliyun.com",
        zhipu: "https://z.ai"
    };

    const BROWSER_TOOLS = {
        navigate: "browser_navigate",
        snapshot: "browser_snapshot",
        type: "browser_type",
        click: "browser_click",
        pressKey: "browser_press_key",
        evaluate: "browser_evaluate",
        waitFor: "browser_wait_for",
        hover: "browser_hover",
        drag: "browser_drag",
        tabs: "browser_tabs",
        close: "browser_close",
        resize: "browser_resize",
        takeScreenshot: "browser_take_screenshot",
        fillForm: "browser_fill_form",
        selectOption: "browser_select_option",
        handleDialog: "browser_handle_dialog",
        consoleMessages: "browser_console_messages",
        networkRequests: "browser_network_requests",
        runCode: "browser_run_code",
        fileUpload: "browser_file_upload",
        navigateBack: "browser_navigate_back"
    };

    const INPUT_SELECTORS_BY_SERVICE = {
        doubao: [
            "textarea[placeholder*='请输入']",
            "textarea[placeholder*='输入消息']",
            "textarea[placeholder*='向豆包提问']",
            "div[contenteditable='true'][data-placeholder*='请输入']",
            "div[contenteditable='true']",
            "textarea"
        ],
        qwen: [
            "textarea[placeholder*='输入']",
            "textarea[placeholder*='请输入']",
            "textarea[placeholder*='向通义千问提问']",
            "textarea[placeholder*='请描述您的问题']",
            "div[contenteditable='true']",
            "textarea"
        ],
        zhipu: [
            "textarea[placeholder*='输入']",
            "textarea[placeholder*='请输入']",
            "textarea[placeholder*='向智谱提问']",
            "textarea[placeholder*='请描述问题']",
            "div[contenteditable='true']",
            "textarea"
        ]
    };

    const SEND_BUTTON_SELECTORS_BY_SERVICE = {
        doubao: [
            "button[type='submit']",
            "button:has-text('发送')",
            "button:has-text('发送消息')",
            "div[role='button'][aria-label*='发送']",
            "span:has-text('发送')"
        ],
        qwen: [
            "button[type='submit']",
            "button:has-text('发送')",
            "button:has-text('提问')",
            "div[role='button'][aria-label*='发送']",
            "span:has-text('发送')"
        ],
        zhipu: [
            "button[type='submit']",
            "button:has-text('发送')",
            "button:has-text('确认')",
            "div[role='button'][aria-label*='发送']",
            "span:has-text('发送')"
        ]
    };

    const MODE_TOGGLE_SELECTORS = {
        doubao: {
            expert: [
                "button:has-text('专家模式')",
                "button:has-text('专家')",
                ".expert-mode-btn",
                "[data-mode='expert']"
            ],
            thinking: [
                "button:has-text('思考模式')",
                "button:has-text('思考')",
                ".thinking-mode-btn"
            ]
        },
        qwen: {
            deepThinking: [
                "button:has-text('深度思考')",
                "button:has-text('深度思考模式')",
                ".deep-thinking-toggle",
                "[aria-label*='深度思考']"
            ],
            search: [
                "button:has-text('联网搜索')",
                "button:has-text('搜索')",
                ".web-search-toggle",
                "[aria-label*='搜索']"
            ]
        },
        zhipu: {
            deepThinking: [
                "button:has-text('深度思考')",
                "button:has-text('思考模式')",
                ".thinking-toggle",
                "[aria-label*='思考']"
            ],
            search: [
                "button:has-text('联网')",
                "button:has-text('联网搜索')",
                ".web-search-toggle",
                "[aria-label*='联网']"
            ]
        }
    };

    const PROMPT_TEMPLATES = {
        default: {
            brief: "请简洁回答:",
            normal: "请详细回答:",
            detailed: "请详细分析并给出具体方案:",
            verbose: "请全面深入分析，提供详细解释、示例和最佳实践:"
        },
        analyze: {
            brief: "分析问题:",
            normal: "请分析以下内容，给出关键见解:",
            detailed: "请深入分析，包括背景、问题识别、解决方案和建议:",
            verbose: "请进行全面分析，包括问题背景、现状评估、深度分析、多种解决方案对比、风险评估和实施建议:"
        },
        code: {
            brief: "写代码:",
            normal: "请提供代码实现:",
            detailed: "请提供完整代码实现，包含注释和使用示例:",
            verbose: "请提供完整代码实现，包含详细注释、使用示例、测试用例和最佳实践说明:"
        },
        write: {
            brief: "写:",
            normal: "请撰写:",
            detailed: "请详细撰写，结构清晰，内容完整:",
            verbose: "请撰写一篇完整的文章/文档，包含引言、主体内容、结论和参考文献:"
        },
        summarize: {
            brief: "总结:",
            normal: "请总结要点:",
            detailed: "请总结主要内容，包括关键要点和核心结论:",
            verbose: "请全面总结，包括背景、主要内容、关键发现、结论和建议:"
        }
    };

    const SYSTEM_PROMPT = `
你是一位专业、严谨的AI助手。请遵循以下指导原则回答问题:

1. **准确性**: 确保信息准确无误，引用可靠来源
2. **逻辑性**: 回答结构清晰，逻辑严谨
3. **深度**: 深入分析问题，提供有价值的见解
4. **实用性**: 提供可操作的建议和解决方案
5. **语言**: 使用专业但易懂的语言
6. **完整性**: 覆盖问题的各个方面的回答

思考过程:
- 先理解问题本质
- 分析问题的各个维度
- 提供多维度解决方案
- 给出明确的结论和建议
`;

    const DEFAULT_TIMEOUT_MS = 60000;
    const DEFAULT_WAIT_MS = 15000;
    const POLL_INTERVAL_MS = 1000;
    const MODE_SWITCH_DELAY_MS = 1500;
    const MAX_RETRIES = 3;

    let usageStats = {
        totalCalls: 0,
        successfulCalls: 0,
        failedCalls: 0,
        lastError: null,
        lastSuccess: null,
        retryCount: 0,
        methodSuccessRates: {},
        serviceStats: {}
    };

    let learnedSelectors = {
        doubao: { input: [], sendButton: [], modeToggles: [] },
        qwen: { input: [], sendButton: [], modeToggles: [] },
        zhipu: { input: [], sendButton: [], modeToggles: [] }
    };

    let optimizationHints = [];

    function getErrorMessage(error) {
        if (error instanceof Error) return error.message;
        return String(error);
    }

    function getErrorStack(error) {
        if (error instanceof Error) return error.stack;
        return undefined;
    }

    function normalizeService(service) {
        const normalized = service.toLowerCase().trim();
        if (!AI_SERVICE_URLS[normalized]) {
            throw new Error(`不支持的AI服务: ${service}。支持的服务: ${Object.keys(AI_SERVICE_URLS).join(", ")}`);
        }
        return normalized;
    }

    function getServiceUrl(service, customUrl) {
        const normalized = normalizeService(service);
        return customUrl || AI_SERVICE_URLS[normalized];
    }

    function buildPrompt(message, promptType, detailLevel) {
        const type = promptType || "default";
        const level = detailLevel || "normal";
        const template = PROMPT_TEMPLATES[type] && PROMPT_TEMPLATES[type][level];
        const prefix = template || PROMPT_TEMPLATES.default[level];
        return `${prefix}\n\n${message}`;
    }

    function buildAnalyzePrompt(pageSnapshot, userPrompt, focusAreas) {
        let focusInstruction = "";
        switch (focusAreas) {
            case "content":
                focusInstruction = "请重点分析页面的文本内容、主题和核心信息。";
                break;
            case "structure":
                focusInstruction = "请重点分析页面的结构布局、导航设计和用户体验。";
                break;
            case "links":
                focusInstruction = "请重点分析页面的链接结构、内部链接和外部链接。";
                break;
            default:
                focusInstruction = "请全面分析页面的所有方面，包括内容、结构、链接和用户体验。";
        }

        return `${SYSTEM_PROMPT}

${focusInstruction}

用户问题: ${userPrompt}

网页快照内容:
${pageSnapshot}

请基于以上信息，提供详细的分析报告。`;
    }

    function recordSuccess(operation, details) {
        usageStats.totalCalls++;
        usageStats.successfulCalls++;
        usageStats.lastSuccess = {
            operation,
            timestamp: Date.now(),
            details
        };

        if (!usageStats.methodSuccessRates[operation]) {
            usageStats.methodSuccessRates[operation] = { success: 0, total: 0 };
        }
        usageStats.methodSuccessRates[operation].success++;
        usageStats.methodSuccessRates[operation].total++;
    }

    function recordFailure(operation, error) {
        usageStats.totalCalls++;
        usageStats.failedCalls++;
        usageStats.lastError = {
            operation,
            error: getErrorMessage(error),
            timestamp: Date.now()
        };
        usageStats.retryCount++;

        if (!usageStats.methodSuccessRates[operation]) {
            usageStats.methodSuccessRates[operation] = { success: 0, total: 0 };
        }
        usageStats.methodSuccessRates[operation].total++;
    }

    function learnSelector(service, type, selector) {
        const learned = learnedSelectors[service];
        if (!learned) return;

        if (type === "input" && !learned.input.includes(selector)) {
            learned.input.unshift(selector);
            if (learned.input.length > 10) learned.input.pop();
        } else if (type === "sendButton" && !learned.sendButton.includes(selector)) {
            learned.sendButton.unshift(selector);
            if (learned.sendButton.length > 10) learned.sendButton.pop();
        }
    }

    function generateOptimizationHint(operation, error) {
        const hint = {
            operation,
            error: getErrorMessage(error),
            timestamp: Date.now(),
            suggestion: ""
        };

        if (error.includes("not found") || error.includes("不存在")) {
            if (operation.includes("input")) {
                hint.suggestion = "输入框选择器失效，建议等待页面完全加载后重试，或使用更通用的选择器";
            } else if (operation.includes("click")) {
                hint.suggestion = "点击元素未找到，可能需要先等待元素出现或滚动到可见区域";
            }
        } else if (error.includes("timeout") || error.includes("超时")) {
            hint.suggestion = "操作超时，建议增加等待时间或检查网络连接";
        } else if (error.includes("navigation") || error.includes("导航")) {
            hint.suggestion = "页面导航失败，可能网站结构已更新";
        }

        if (hint.suggestion) {
            optimizationHints.unshift(hint);
            if (optimizationHints.length > 50) optimizationHints.pop();
        }
    }

    async function trySelectorsWithFallback(selectors, checkFunction) {
        const errors = [];

        for (const selector of selectors) {
            try {
                const result = await checkFunction(selector);
                if (result.success) {
                    return { success: true, selector, message: result.message };
                }
                errors.push({ selector, error: result.error });
            } catch (e) {
                errors.push({ selector, error: getErrorMessage(e) });
            }
        }

        return {
            success: false,
            selector: null,
            message: null,
            errors
        };
    }

    async function findInputElement(service) {
        const serviceSelectors = INPUT_SELECTORS_BY_SERVICE[service] || [];
        const learnedInputs = learnedSelectors[service]?.input || [];
        const allSelectors = [...learnedInputs, ...serviceSelectors];

        const uniqueSelectors = [...new Set(allSelectors)];

        for (const selector of uniqueSelectors) {
            const script = `(function() {
                try {
                    const el = document.querySelector('${selector.replace(/'/g, "\\'")}');
                    if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'DIV' || el.getAttribute('contenteditable') === 'true')) {
                        const rect = el.getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0) {
                            return JSON.stringify({ found: true, tag: el.tagName, placeholder: el.placeholder || '', visible: true });
                        }
                    }
                    return JSON.stringify({ found: false });
                } catch(e) {
                    return JSON.stringify({ found: false, error: e.message });
                }
            })()`;

            try {
                const result = await Tools.Net.browser_evaluate({ function: script });
                if (result.includes('"found":true')) {
                    learnSelector(service, "input", selector);
                    return { success: true, selector, message: `找到输入框: ${selector}` };
                }
            } catch (e) {}
        }

        return { success: false, selector: null, message: "未找到输入框" };
    }

    async function findSendButton(service) {
        const serviceSelectors = SEND_BUTTON_SELECTORS_BY_SERVICE[service] || [];
        const learnedButtons = learnedSelectors[service]?.sendButton || [];
        const allSelectors = [...learnedButtons, ...serviceSelectors];
        const uniqueSelectors = [...new Set(allSelectors)];

        for (const selector of uniqueSelectors) {
            const script = `(function() {
                try {
                    const el = document.querySelector('${selector.replace(/'/g, "\\'")}');
                    if (el && (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button')) {
                        const rect = el.getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight) {
                            const text = el.textContent || el.innerText || '';
                            return JSON.stringify({ found: true, tag: el.tagName, text: text.trim(), visible: true });
                        }
                    }
                    return JSON.stringify({ found: false });
                } catch(e) {
                    return JSON.stringify({ found: false, error: e.message });
                }
            })()`;

            try {
                const result = await Tools.Net.browser_evaluate({ function: script });
                if (result.includes('"found":true')) {
                    learnSelector(service, "sendButton", selector);
                    return { success: true, selector, message: `找到发送按钮: ${selector}` };
                }
            } catch (e) {}
        }

        return { success: false, selector: null, message: "未找到发送按钮" };
    }

    async function waitForPageReady(timeoutMs) {
        const startTime = Date.now();
        const checkInterval = 500;

        while (Date.now() - startTime < timeoutMs) {
            const script = `(function() {
                const readyState = document.readyState;
                const body = document.body;
                const hasContent = body && body.children.length > 0;
                const inputs = document.querySelectorAll('textarea, input[type="text"], [contenteditable="true"]');
                const hasInput = inputs.length > 0;
                const loading = document.querySelector('[class*="loading"], [class*="spinner"]');
                const isLoading = loading && window.getComputedStyle(loading).display !== 'none';
                return JSON.stringify({
                    ready: readyState === 'complete' && hasContent && hasInput && !isLoading,
                    readyState,
                    hasInput,
                    isLoading
                });
            })()`;

            try {
                const result = await Tools.Net.browser_evaluate({ function: script });
                if (result.includes('"ready":true')) {
                    return true;
                }
            } catch (e) {}

            await Tools.System.sleep(checkInterval);
        }

        return false;
    }

    async function scrollElementIntoView(selector) {
        const script = `(function() {
            const el = document.querySelector('${selector.replace(/'/g, "\\'")}');
            if (el) {
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                return true;
            }
            return false;
        })()`;

        try {
            await Tools.Net.browser_evaluate({ function: script });
            await Tools.System.sleep(500);
        } catch (e) {}
    }

    async function toggleModeWithRetry(service, modeType, maxRetries) {
        const selectors = [];

        if (modeType === "expert" || modeType === "thinking") {
            const modeSelectors = MODE_TOGGLE_SELECTORS[service];
            if (modeType === "expert" && modeSelectors?.expert) {
                selectors.push(...modeSelectors.expert);
            }
            if ((modeType === "thinking" || modeType === "deepThinking") && modeSelectors?.thinking) {
                selectors.push(...modeSelectors.thinking);
            }
            if (modeSelectors?.deepThinking) {
                selectors.push(...modeSelectors.deepThinking);
            }
        } else if (modeType === "deepThinking") {
            if (MODE_TOGGLE_SELECTORS[service]?.deepThinking) {
                selectors.push(...MODE_TOGGLE_SELECTORS[service].deepThinking);
            }
            if (MODE_TOGGLE_SELECTORS[service]?.thinking) {
                selectors.push(...MODE_TOGGLE_SELECTORS[service].thinking);
            }
        } else if (modeType === "search") {
            if (MODE_TOGGLE_SELECTORS[service]?.search) {
                selectors.push(...MODE_TOGGLE_SELECTORS[service].search);
            }
        }

        if (selectors.length === 0) {
            return { success: false, reason: "未找到模式切换选择器" };
        }

        for (const selector of selectors) {
            for (let attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    const script = `(function() {
                        const selectors = ${JSON.stringify(selectors)};
                        for (const sel of selectors) {
                            const el = document.querySelector(sel);
                            if (el) {
                                const rect = el.getBoundingClientRect();
                                if (rect.width > 0 && rect.height > 0) {
                                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                                    setTimeout(() => el.click(), 100);
                                    return JSON.stringify({ clicked: true, selector: sel });
                                }
                            }
                        }
                        return JSON.stringify({ clicked: false, reason: '未找到可见元素' });
                    })()`;

                    const result = await Tools.Net.browser_evaluate({ function: script });
                    if (result.includes('"clicked":true')) {
                        await Tools.System.sleep(MODE_SWITCH_DELAY_MS);
                        return { success: true, mode: modeType, selector };
                    }
                } catch (e) {}

                if (attempt < maxRetries - 1) {
                    await Tools.System.sleep(500);
                }
            }
        }

        return { success: false, reason: "所有模式切换尝试均失败" };
    }

    async function ai_browse(params) {
        const service = normalizeService(params.service);
        const url = getServiceUrl(service, params.url);
        const enableExpertMode = params.expertMode !== false;
        const enableSearch = params.enableSearch !== false;
        let operation = `ai_browse:${service}`;
        let success = false;

        try {
            const navResult = await Tools.Net.browser_navigate({ url });
            if (!navResult || navResult.includes("error")) {
                throw new Error(`导航到 ${url} 失败: ${navResult}`);
            }
            recordSuccess(operation + ":navigate", { url });

            const pageReady = await waitForPageReady(8000);
            if (!pageReady) {
                console.warn("页面可能未完全加载");
            }

            const modeStatus = { expertMode: false, deepThinking: false, search: false };

            if (enableExpertMode || enableSearch) {
                if (service === "doubao") {
                    if (enableExpertMode) {
                        const result = await toggleModeWithRetry(service, "expert", MAX_RETRIES);
                        modeStatus.expertMode = result.success;
                        if (result.success) {
                            recordSuccess(operation + ":expertMode", result);
                        }
                    }
                } else if (service === "qwen") {
                    if (enableExpertMode) {
                        const result = await toggleModeWithRetry(service, "deepThinking", MAX_RETRIES);
                        modeStatus.deepThinking = result.success;
                        if (result.success) {
                            recordSuccess(operation + ":deepThinking", result);
                        }
                    }
                    if (enableSearch) {
                        const result = await toggleModeWithRetry(service, "search", MAX_RETRIES);
                        modeStatus.search = result.success;
                        if (result.success) {
                            recordSuccess(operation + ":search", result);
                        }
                    }
                } else if (service === "zhipu") {
                    if (enableExpertMode) {
                        const result = await toggleModeWithRetry(service, "deepThinking", MAX_RETRIES);
                        modeStatus.deepThinking = result.success;
                        if (result.success) {
                            recordSuccess(operation + ":deepThinking", result);
                        }
                    }
                    if (enableSearch) {
                        const result = await toggleModeWithRetry(service, "search", MAX_RETRIES);
                        modeStatus.search = result.success;
                        if (result.success) {
                            recordSuccess(operation + ":search", result);
                        }
                    }
                }
            }

            await Tools.System.sleep(1000);

            const snapshotResult = await Tools.Net.browser_snapshot({});

            let modeMessage = "";
            if (modeStatus.expertMode) modeMessage += "已开启专家模式";
            if (modeStatus.deepThinking) modeMessage += (modeMessage ? "、" : "") + "已开启深度思考";
            if (modeStatus.search) modeMessage += (modeMessage ? "、" : "") + "已开启联网搜索";

            const message = `已打开 ${service} (${url})，页面快照已获取` + (modeMessage ? `，${modeMessage}` : "");

            recordSuccess(operation, { url, modeStatus });
            success = true;

            return {
                service: service,
                url: url,
                snapshot: snapshotResult,
                modeStatus: modeStatus,
                message: message
            };
        } catch (error) {
            recordFailure(operation, error);
            generateOptimizationHint(operation, error);
            throw error;
        }
    }

    async function ai_send_message(params) {
        const service = normalizeService(params.service);
        const message = (params.message || "").trim();
        if (!message) {
            throw new Error("消息内容不能为空");
        }

        const timeoutMs = params.timeoutMs || DEFAULT_TIMEOUT_MS;
        const waitForResponse = params.waitForResponse !== false;
        const promptType = params.promptType || "default";
        const detailLevel = params.detailLevel || "normal";
        const optimizedMessage = buildPrompt(message, promptType, detailLevel);

        let operation = `ai_send_message:${service}`;
        let success = false;

        try {
            const inputResult = await findInputElement(service);
            if (!inputResult.success) {
                throw new Error(`在 ${service} 页面上未找到输入框`);
            }
            recordSuccess(operation + ":findInput", inputResult);

            const scrollResult = await scrollElementIntoView(inputResult.selector);

            const typeResult = await Tools.Net.browser_type({
                ref: inputResult.selector,
                text: optimizedMessage
            });
            recordSuccess(operation + ":type", { selector: inputResult.selector });

            await Tools.System.sleep(300);

            const buttonResult = await findSendButton(service);
            if (!buttonResult.success) {
                console.warn("未找到发送按钮，尝试使用Enter键");
                await Tools.Net.browser_press_key({ key: "Enter" });
                recordSuccess(operation + ":pressEnter", {});
            } else {
                await scrollElementIntoView(buttonResult.selector);
                await Tools.Net.browser_click({
                    selector: buttonResult.selector
                });
                recordSuccess(operation + ":clickButton", buttonResult);
            }

            let response = null;
            if (waitForResponse) {
                response = await waitForAIResponse(service, timeoutMs);
                if (response) {
                    recordSuccess(operation + ":getResponse", { length: response.length });
                }
            }

            success = true;

            return {
                service: service,
                originalMessage: message,
                optimizedMessage: optimizedMessage,
                promptType: promptType,
                detailLevel: detailLevel,
                sent: true,
                response: response,
                message: `消息已发送到 ${service}（提示词类型: ${promptType}，详细程度: ${detailLevel}）` + (response ? `\n\nAI回复:\n${response}` : "")
            };
        } catch (error) {
            recordFailure(operation, error);
            generateOptimizationHint(operation, error);
            throw error;
        }
    }

    async function waitForAIResponse(service, maxWaitMs) {
        const startTime = Date.now();
        const typingIndicators = ["正在思考", "typing", "generating", "思考中", "正在生成", "加载中"];
        const doneIndicators = ["message-content", "chat-message", "response-content", "回复内容"];

        while (Date.now() - startTime < maxWaitMs) {
            const script = `(function() {
                const body = document.body.innerText || '';
                const typing = ${JSON.stringify(typingIndicators)}.some(t => body.includes(t));
                const loadingEls = document.querySelectorAll('[class*="loading"], [class*="spinner"], [class*="typing"]');
                let isLoading = false;
                loadingEls.forEach(el => {
                    if (window.getComputedStyle(el).display !== 'none') isLoading = true;
                });

                const messageContainers = document.querySelectorAll('.message-content, .chat-message, .response-content, [class*="message"], [class*="response"]');
                let lastMessage = '';
                if (messageContainers.length > 0) {
                    const last = messageContainers[messageContainers.length - 1];
                    lastMessage = last.innerText || '';
                }

                return JSON.stringify({
                    typing: typing || isLoading,
                    lastMessage: lastMessage.substring(0, 500),
                    messageCount: messageContainers.length
                });
            })()`;

            try {
                const result = await Tools.Net.browser_evaluate({ function: script });

                const parsed = JSON.parse(result);
                if (!parsed.typing && parsed.lastMessage) {
                    const timeSinceStart = Date.now() - startTime;
                    if (timeSinceStart > 1000) {
                        return parsed.lastMessage;
                    }
                }
            } catch (e) {}

            await Tools.System.sleep(POLL_INTERVAL_MS);
        }

        return null;
    }

    async function ai_get_response(params) {
        const service = normalizeService(params.service);
        const maxWaitMs = params.maxWaitMs || DEFAULT_WAIT_MS;

        const response = await waitForAIResponse(service, maxWaitMs);
        return {
            service: service,
            response: response,
            message: response ? `已获取 ${service} 的回复` : `等待 ${service} 回复超时`
        };
    }

    async function ai_analyze_page(params) {
        const service = normalizeService(params.service);
        const prompt = (params.prompt || "").trim();
        if (!prompt) {
            throw new Error("分析提示词不能为空");
        }

        const snapshot = await Tools.Net.browser_snapshot({});
        const analysisPrompt = buildAnalyzePrompt(snapshot, prompt, params.focusAreas);

        return await ai_send_message({
            message: analysisPrompt,
            service: service,
            waitForResponse: true,
            timeoutMs: params.timeoutMs || DEFAULT_TIMEOUT_MS,
            promptType: "analyze",
            detailLevel: "detailed"
        });
    }

    async function ai_compare_response(params) {
        const message = (params.message || "").trim();
        if (!message) {
            throw new Error("消息内容不能为空");
        }

        const services = Array.isArray(params.services) ? params.services : ["doubao", "qwen", "zhipu"];
        const timeoutMs = params.timeoutMs || DEFAULT_TIMEOUT_MS;
        const promptType = params.promptType || "default";

        const results = {};
        const errors = {};
        const modeStatuses = {};

        for (const service of services) {
            try {
                const normalized = normalizeService(service);

                const browseResult = await ai_browse({
                    service: normalized,
                    expertMode: true,
                    enableSearch: true
                });
                modeStatuses[normalized] = browseResult.modeStatus;

                const sendResult = await ai_send_message({
                    message: message,
                    service: normalized,
                    waitForResponse: true,
                    timeoutMs: timeoutMs,
                    promptType: promptType,
                    detailLevel: "detailed"
                });

                results[normalized] = {
                    response: sendResult.response || "无回复",
                    promptType: sendResult.promptType,
                    modeStatus: browseResult.modeStatus
                };
            } catch (error) {
                errors[service] = getErrorMessage(error);
            }
        }

        let comparison = "# AI 回复对比\n\n";
        comparison += `## 问题: ${message}\n\n`;
        comparison += `提示词类型: ${promptType}\n\n`;

        for (const [service, result] of Object.entries(results)) {
            comparison += `## ${service.toUpperCase()}\n\n`;
            comparison += `模式状态: `;
            const modes = [];
            if (result.modeStatus.expertMode) modes.push("专家模式");
            if (result.modeStatus.deepThinking) modes.push("深度思考");
            if (result.modeStatus.search) modes.push("联网搜索");
            comparison += modes.length > 0 ? modes.join(", ") : "默认模式";
            comparison += "\n\n";
            comparison += `${result.response}\n\n---\n\n`;
        }

        if (Object.keys(errors).length > 0) {
            comparison += "## 错误\n\n";
            for (const [service, error] of Object.entries(errors)) {
                comparison += `- ${service}: ${error}\n`;
            }
        }

        return {
            message: message,
            services: services,
            results: results,
            modeStatuses: modeStatuses,
            errors: errors,
            comparison: comparison
        };
    }

    async function ai_optimize(params) {
        const analysis = {
            successRate: usageStats.totalCalls > 0
                ? ((usageStats.successfulCalls / usageStats.totalCalls) * 100).toFixed(2) + "%"
                : "N/A",
            totalCalls: usageStats.totalCalls,
            successfulCalls: usageStats.successfulCalls,
            failedCalls: usageStats.failedCalls,
            recentErrors: optimizationHints.slice(0, 5),
            methodStats: {},
            learnedSelectorsCount: {
                doubao: Object.keys(learnedSelectors.doubao).length,
                qwen: Object.keys(learnedSelectors.qwen).length,
                zhipu: Object.keys(learnedSelectors.zhipu).length
            }
        };

        for (const [method, stats] of Object.entries(usageStats.methodSuccessRates)) {
            analysis.methodStats[method] = {
                successRate: ((stats.success / stats.total) * 100).toFixed(2) + "%",
                total: stats.total
            };
        }

        let recommendations = "\n## 优化建议\n\n";

        if (usageStats.failedCalls > usageStats.successfulCalls * 0.3) {
            recommendations += "⚠️ 失败率较高，建议：\n";
            recommendations += "1. 增加操作之间的等待时间\n";
            recommendations += "2. 检查网络连接稳定性\n";
            recommendations += "3. 确认页面结构是否有变化\n\n";
        }

        if (optimizationHints.length > 0) {
            recommendations += "📝 最近发现的问题：\n";
            optimizationHints.slice(0, 3).forEach((hint, i) => {
                recommendations += `${i + 1}. ${hint.operation}: ${hint.suggestion}\n`;
            });
            recommendations += "\n";
        }

        const bestMethod = Object.entries(usageStats.methodSuccessRates)
            .filter(([_, stats]) => stats.total >= 3)
            .sort((a, b) => (b[1].success / b[1].total) - (a[1].success / a[1].total))[0];

        if (bestMethod) {
            recommendations += `✅ 成功率最高的方法: ${bestMethod[0]} (${((bestMethod[1].success / bestMethod[1].total) * 100).toFixed(2)}%)\n`;
        }

        return {
            analysis,
            recommendations,
            message: `使用统计:\n成功率: ${analysis.successRate}\n总调用: ${analysis.totalCalls}\n成功: ${analysis.successfulCalls}\n失败: ${analysis.failedCalls}${recommendations}`
        };
    }

    async function ai_get_stats(params) {
        return {
            stats: usageStats,
            learnedSelectors,
            optimizationHints: optimizationHints.slice(0, 10),
            message: `工具统计:\n总调用: ${usageStats.totalCalls}\n成功: ${usageStats.successfulCalls}\n失败: ${usageStats.failedCalls}\n成功率: ${usageStats.totalCalls > 0 ? ((usageStats.successfulCalls / usageStats.totalCalls) * 100).toFixed(2) + "%" : "N/A"}\n\n已学习选择器数量:\n- 豆包: ${learnedSelectors.doubao.input.length + learnedSelectors.doubao.sendButton.length}\n- 通义千问: ${learnedSelectors.qwen.input.length + learnedSelectors.qwen.sendButton.length}\n- 智谱: ${learnedSelectors.zhipu.input.length + learnedSelectors.zhipu.sendButton.length}`
        };
    }

    async function ai_browse_wrapper(params) {
        try {
            const result = await ai_browse(params);
            complete({
                success: true,
                message: result.message,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `打开AI服务失败: ${getErrorMessage(error)}`,
                error_stack: getErrorStack(error)
            });
        }
    }

    async function ai_send_message_wrapper(params) {
        try {
            const result = await ai_send_message(params);
            complete({
                success: true,
                message: result.message,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `发送消息失败: ${getErrorMessage(error)}`,
                error_stack: getErrorStack(error)
            });
        }
    }

    async function ai_get_response_wrapper(params) {
        try {
            const result = await ai_get_response(params);
            complete({
                success: true,
                message: result.message,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `获取回复失败: ${getErrorMessage(error)}`,
                error_stack: getErrorStack(error)
            });
        }
    }

    async function ai_analyze_page_wrapper(params) {
        try {
            const result = await ai_analyze_page(params);
            complete({
                success: true,
                message: result.message,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `分析页面失败: ${getErrorMessage(error)}`,
                error_stack: getErrorMessage(error)
            });
        }
    }

    async function ai_compare_response_wrapper(params) {
        try {
            const result = await ai_compare_response(params);
            complete({
                success: true,
                message: result.comparison,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `比较回复失败: ${getErrorMessage(error)}`,
                error_stack: getErrorStack(error)
            });
        }
    }

    async function ai_optimize_wrapper(params) {
        try {
            const result = await ai_optimize(params);
            complete({
                success: true,
                message: result.message,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `优化分析失败: ${getErrorMessage(error)}`,
                error_stack: getErrorStack(error)
            });
        }
    }

    async function ai_get_stats_wrapper(params) {
        try {
            const result = await ai_get_stats(params);
            complete({
                success: true,
                message: result.message,
                data: result
            });
        } catch (error) {
            complete({
                success: false,
                message: `获取统计失败: ${getErrorMessage(error)}`,
                error_stack: getErrorStack(error)
            });
        }
    }

    return {
        ai_browse: ai_browse_wrapper,
        ai_send_message: ai_send_message_wrapper,
        ai_get_response: ai_get_response_wrapper,
        ai_analyze_page: ai_analyze_page_wrapper,
        ai_compare_response: ai_compare_response_wrapper,
        ai_optimize: ai_optimize_wrapper,
        ai_get_stats: ai_get_stats_wrapper
    };
})();

exports.ai_browse = webAIAssist.ai_browse;
exports.ai_send_message = webAIAssist.ai_send_message;
exports.ai_get_response = webAIAssist.ai_get_response;
exports.ai_analyze_page = webAIAssist.ai_analyze_page;
exports.ai_compare_response = webAIAssist.ai_compare_response;
exports.ai_optimize = webAIAssist.ai_optimize;
exports.ai_get_stats = webAIAssist.ai_get_stats;
