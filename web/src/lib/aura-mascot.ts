/**
 * Apex Terminal — "Aura" 水母吉祥物形态定义
 *
 * 22 种形态(14 基础 + 8 功能扩展),每种对应一张 PNG 图像。
 * 设计:深海极光生物荧光水母,圆顶钟盖 + 飘逸触手。
 * 形态由终端/Agent/狂暴模式/功能状态自动触发。
 *
 * PNG 渲染(Android 端用 ImageView + 浮动动画,Web 预览用 <img>)。
 * 不再用 ASCII —— 有机圆弧生物在 ASCII 里无法精致表达。
 */

export type AuraForm =
  // 基础 14 形态
  | "IDLE" | "THINKING" | "TYPING" | "EXECUTING" | "BERSERK"
  | "SUCCESS" | "ERROR" | "SLEEPING" | "EVOLVING" | "COLLABORATING"
  | "LOADING" | "CELEBRATING" | "CURIOUS" | "SHIELDING"
  // 功能扩展 11 形态
  | "REMEMBERING" | "ANALYZING" | "LEARNING" | "NETWORKING"
  | "ROOT" | "PLANNING" | "COMPILING" | "CONNECTING"
  | "TOOLING" | "SKILLING" | "MCPING";

export type AuraAccent = "cyan" | "pink" | "amber" | "mint" | "rose" | "violet" | "sky";

export interface AuraFormMeta {
  form: AuraForm;
  displayName: string;
  description: string;
  emoji: string;
  intervalMs: number;
  accent: AuraAccent;
}

export const AURA_FORMS: AuraFormMeta[] = [
  // ===== 基础 14 形态 =====
  { form: "IDLE", displayName: "漂浮", description: "轻柔漂浮 · 触手微摆", emoji: "🪼", intervalMs: 1100, accent: "cyan" },
  { form: "THINKING", displayName: "思考", description: "凝神思考 · 光圈扩散", emoji: "🤔", intervalMs: 750, accent: "sky" },
  { form: "TYPING", displayName: "打字", description: "触手快速敲击", emoji: "⌨️", intervalMs: 280, accent: "cyan" },
  { form: "EXECUTING", displayName: "执行", description: "彩色荧光 · 透明外层", emoji: "🚀", intervalMs: 340, accent: "pink" },
  { form: "BERSERK", displayName: "狂暴", description: "血红双眼 · 电环绕", emoji: "🔥", intervalMs: 160, accent: "rose" },
  { form: "SUCCESS", displayName: "成功", description: "开心上浮 · 星光散落", emoji: "✨", intervalMs: 600, accent: "mint" },
  { form: "ERROR", displayName: "错误", description: "颤抖下沉 · 触手蜷缩", emoji: "💢", intervalMs: 160, accent: "rose" },
  { form: "SLEEPING", displayName: "休眠", description: "闭眼休眠 · 呼吸微光", emoji: "💤", intervalMs: 2800, accent: "violet" },
  { form: "EVOLVING", displayName: "演化", description: "DNA 螺旋 · 光点变异", emoji: "🧬", intervalMs: 520, accent: "mint" },
  { form: "COLLABORATING", displayName: "协作", description: "多分身 · 连接协作", emoji: "🎭", intervalMs: 850, accent: "sky" },
  { form: "LOADING", displayName: "加载", description: "旋转扫描 · 进度指示", emoji: "⏳", intervalMs: 200, accent: "cyan" },
  { form: "CELEBRATING", displayName: "庆祝", description: "礼花跳跃 · 狂欢", emoji: "🎉", intervalMs: 450, accent: "amber" },
  { form: "CURIOUS", displayName: "好奇", description: "黄色身体 · 两眼探头", emoji: "👀", intervalMs: 700, accent: "amber" },
  { form: "SHIELDING", displayName: "防御", description: "光盾展开 · 安全", emoji: "🛡️", accent: "sky", intervalMs: 550 },
  // ===== 功能扩展 8 形态 =====
  { form: "REMEMBERING", displayName: "记忆", description: "记忆系统 · 神经节点", emoji: "🧠", intervalMs: 650, accent: "mint" },
  { form: "ANALYZING", displayName: "分析", description: "代码分析 · 放大镜", emoji: "🔍", intervalMs: 500, accent: "cyan" },
  { form: "LEARNING", displayName: "进化", description: "进化系统 · 自我迭代", emoji: "🌱", intervalMs: 700, accent: "violet" },
  { form: "NETWORKING", displayName: "联网", description: "网络请求 · 数据流动", emoji: "🌐", intervalMs: 400, accent: "sky" },
  { form: "ROOT", displayName: "提权", description: "Shizuku/Root · 皇冠", emoji: "👑", intervalMs: 600, accent: "amber" },
  { form: "PLANNING", displayName: "规划", description: "任务分解 · 流程图", emoji: "🗺️", intervalMs: 550, accent: "cyan" },
  { form: "COMPILING", displayName: "编译", description: "代码生成 · 齿轮", emoji: "⚙️", intervalMs: 320, accent: "pink" },
  { form: "CONNECTING", displayName: "连接", description: "MCP/插件 · 模块接入", emoji: "🔌", intervalMs: 450, accent: "mint" },
  { form: "TOOLING", displayName: "工具", description: "调用工具 · 工具箱", emoji: "🛠️", intervalMs: 500, accent: "amber" },
  { form: "SKILLING", displayName: "技能", description: "Skills 技能 · 模块加载", emoji: "💎", intervalMs: 480, accent: "cyan" },
  { form: "MCPING", displayName: "MCP", description: "MCP 协议 · 服务接入", emoji: "🔗", intervalMs: 460, accent: "mint" },
];

export function getFormMeta(form: AuraForm): AuraFormMeta {
  return AURA_FORMS.find((f) => f.form === form) ?? AURA_FORMS[0];
}
