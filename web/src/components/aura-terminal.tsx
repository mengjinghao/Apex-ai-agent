"use client";

import { useEffect, useRef, useState } from "react";
import { AuraJellyfish } from "./aura-jellyfish";
import { getFormMeta, AURA_FORMS, type AuraForm } from "@/lib/aura-mascot";
import { APEX_THEME, ACCENT_COLOR } from "@/lib/terminal-theme";

interface TerminalLine {
  id: number;
  kind: "prompt" | "output" | "system" | "error" | "success" | "agent";
  text: string;
  agent?: string;
}

interface AuraTerminalProps {
  form: AuraForm;
  onFormChange: (f: AuraForm) => void;
}

const BOOT_LINES: Omit<TerminalLine, "id">[] = [
  { kind: "system", text: "Apex Terminal v2.4.0 — Deep Sea Aurora Edition" },
  { kind: "system", text: "内核: burst-kernel · 引擎: shizuku · 模型: deepseek-v3" },
  { kind: "success", text: "✓ Aura 吉祥物已就绪 — 深海极光水母 · 25 形态" },
  { kind: "system", text: "输入 help 查看可用命令，或点击下方形态切换 Aura。" },
];

const HELP_LINES: Omit<TerminalLine, "id">[] = [
  { kind: "output", text: "可用命令:" },
  { kind: "output", text: "  help              显示此帮助" },
  { kind: "output", text: "  status            查看 Aura 与系统状态" },
  { kind: "output", text: "  form <名称>       切换 Aura 形态 (22 种)" },
  { kind: "output", text: "  burst             进入狂暴模式" },
  { kind: "output", text: "  agent <任务>      派遣 Agent 执行任务" },
  { kind: "output", text: "  ── 功能触发形态 ──" },
  { kind: "output", text: "  remember          记忆系统读写" },
  { kind: "output", text: "  analyze           代码分析审计" },
  { kind: "output", text: "  learn             进化系统迭代" },
  { kind: "output", text: "  net               网络请求同步" },
  { kind: "output", text: "  root              Shizuku/Root 提权" },
  { kind: "output", text: "  plan              任务分解规划" },
  { kind: "output", text: "  compile           代码生成编译" },
  { kind: "output", text: "  connect           MCP/插件连接" },
  { kind: "output", text: "  tool              调用工具" },
  { kind: "output", text: "  skill             Skills 技能加载" },
  { kind: "output", text: "  mcp               MCP 服务接入" },
  { kind: "output", text: "  clear             清空终端" },
  { kind: "output", text: "  about             关于 Apex Terminal" },
];

const ABOUT_LINES: Omit<TerminalLine, "id">[] = [
  { kind: "output", text: "╭───────────────────────────────────────╮" },
  { kind: "output", text: "│  Apex Auto Agent — 移动端 AI 自动化平台  │" },
  { kind: "output", text: "│  以狂暴模式微内核为核心，融合多 Agent     │" },
  { kind: "output", text: "│  协作、深度记忆与可插拔技能。             │" },
  { kind: "output", text: "╰───────────────────────────────────────╯" },
  { kind: "output", text: "吉祥物: Aura (深海极光水母) · 14 形态动画" },
  { kind: "output", text: "主题: Deep Sea Aurora · 电光青 + 珊瑚粉" },
];

let lineId = 0;
const nextId = () => ++lineId;

export function AuraTerminal({ form, onFormChange }: AuraTerminalProps) {
  const [lines, setLines] = useState<TerminalLine[]>(() =>
    BOOT_LINES.map((l) => ({ ...l, id: nextId() }))
  );
  const [input, setInput] = useState("");
  const [history, setHistory] = useState<string[]>([]);
  const [historyIdx, setHistoryIdx] = useState(-1);
  const [busy, setBusy] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const meta = getFormMeta(form);
  const accent = ACCENT_COLOR[meta.accent];

  // Auto-scroll to bottom on new lines
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [lines]);

  const pushLines = (newLines: Omit<TerminalLine, "id">[]) => {
    setLines((prev) => [...prev, ...newLines.map((l) => ({ ...l, id: nextId() }))]);
  };

  const runCommand = async (raw: string) => {
    const cmd = raw.trim();
    if (!cmd) return;
    pushLines([{ kind: "prompt", text: cmd }]);
    setHistory((h) => [cmd, ...h].slice(0, 50));
    setHistoryIdx(-1);

    const [name, ...args] = cmd.split(/\s+/);
    const lower = name.toLowerCase();

    if (lower === "clear") {
      setLines([]);
      return;
    }
    if (lower === "help") {
      pushLines(HELP_LINES);
      return;
    }
    if (lower === "about") {
      pushLines(ABOUT_LINES);
      return;
    }
    if (lower === "status") {
      pushLines([
        { kind: "output", text: `Aura 形态: ${meta.displayName} (${form})  ${meta.emoji}` },
        { kind: "output", text: `描述: ${meta.description}` },
        { kind: "output", text: `狂暴模式: idle   Agent 模式: standard   记忆: 已加载` },
        { kind: "success", text: `✓ 系统运行正常 · CPU 12% · MEM 348MB` },
      ]);
      return;
    }
    if (lower === "form") {
      const target = args[0]?.toUpperCase();
      const valid: AuraForm[] = AURA_FORMS.map((f) => f.form);
      if (target && valid.includes(target as AuraForm)) {
        onFormChange(target as AuraForm);
        pushLines([
          { kind: "success", text: `✓ Aura 切换为 ${getFormMeta(target as AuraForm).displayName} (${target})` },
        ]);
      } else {
        pushLines([{ kind: "error", text: `未知形态: ${args[0] ?? "(空)"}。可用: ${AURA_FORMS.map(f=>f.form.toLowerCase()).join(", ")}` }]);
      }
      return;
    }
    if (lower === "burst") {
      setBusy(true);
      onFormChange("BERSERK");
      pushLines([
        { kind: "agent", agent: "supervisor", text: "启动狂暴模式 (Burst Mode)..." },
        { kind: "output", text: "  → 加载技能: ReAct, ToT, Racing, RedBlueAdversarial..." },
      ]);
      await delay(900);
      pushLines([{ kind: "output", text: "  → 多路径推理: ✓  对抗评估: ✓  自我修正: ✓" }]);
      await delay(700);
      onFormChange("SUCCESS");
      pushLines([{ kind: "success", text: "✓ 狂暴模式任务完成 · 耗时 1.6s · 节省 73% token" }]);
      await delay(800);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "agent") {
      const task = args.join(" ");
      if (!task) {
        pushLines([{ kind: "error", text: "用法: agent <任务描述>" }]);
        return;
      }
      setBusy(true);
      onFormChange("THINKING");
      pushLines([{ kind: "agent", agent: "supervisor", text: `收到任务: "${task}"` }]);
      await delay(700);
      pushLines([{ kind: "agent", agent: "planner", text: "分解任务为 3 个子步骤..." }]);
      onFormChange("EXECUTING");
      await delay(900);
      pushLines([
        { kind: "agent", agent: "worker", text: "▶ 执行步骤 1/3: 环境探测" },
        { kind: "output", text: "  → SystemProbe: Android 14, root=true, shizuku=active" },
      ]);
      await delay(800);
      pushLines([
        { kind: "agent", agent: "worker", text: "▶ 执行步骤 2/3: 工具调用" },
        { kind: "output", text: "  → FileTool.read / ProcessTool.exec / NetworkTool.get" },
      ]);
      await delay(800);
      pushLines([
        { kind: "agent", agent: "reviewer", text: "▶ 执行步骤 3/3: 结果审查" },
        { kind: "output", text: "  → 审查通过，质量评分 9.2/10" },
      ]);
      await delay(500);
      onFormChange("SUCCESS");
      pushLines([{ kind: "success", text: `✓ 任务完成: "${task}"` }]);
      await delay(700);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }

    // ===== 功能扩展命令:对应 8 个新形态,演示功能触发 =====
    if (lower === "remember" || lower === "memory") {
      setBusy(true);
      onFormChange("REMEMBERING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "记忆系统读写中..." }]);
      await delay(900);
      pushLines([{ kind: "output", text: "  → 短期记忆: 3 条 · 长期记忆: 1,247 条 · 工具记忆: 89 条" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 记忆已存档 · 检索延迟 12ms" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "analyze") {
      setBusy(true);
      onFormChange("ANALYZING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "代码分析审计中..." }]);
      await delay(1000);
      pushLines([{ kind: "output", text: "  → 扫描文件: 1,534 · 函数: 8,201 · 逻辑漏洞: 2" }]);
      await delay(800);
      pushLines([{ kind: "success", text: "✓ 分析完成 · 代码质量 8.7/10" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "learn") {
      setBusy(true);
      onFormChange("LEARNING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "本地 LLaMA 推理学习中..." }]);
      await delay(1200);
      pushLines([{ kind: "output", text: "  → 神经网络层数: 32 · 参数: 7B · 推理速度: 18 tok/s" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 知识已吸收 · 置信度 94%" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "net" || lower === "network") {
      setBusy(true);
      onFormChange("NETWORKING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "网络请求中..." }]);
      await delay(900);
      pushLines([{ kind: "output", text: "  → GET /api/sync · 数据包: 12 KB · 延迟 43ms" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 数据同步完成" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "root") {
      setBusy(true);
      onFormChange("ROOT");
      pushLines([{ kind: "agent", agent: "supervisor", text: "Shizuku/Root 提权激活..." }]);
      await delay(1000);
      pushLines([{ kind: "output", text: "  → Shizuku: active · Root: granted · SELinux: permissive" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 提权成功 · 系统级权限已获取" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "plan") {
      setBusy(true);
      onFormChange("PLANNING");
      pushLines([{ kind: "agent", agent: "planner", text: "任务分解规划中..." }]);
      await delay(1100);
      pushLines([{ kind: "output", text: "  → 步骤 1: 环境探测 · 步骤 2: 工具调用 · 步骤 3: 结果审查" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 规划完成 · 3 步流程图已生成" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "compile" || lower === "build") {
      setBusy(true);
      onFormChange("COMPILING");
      pushLines([{ kind: "agent", agent: "worker", text: "代码生成编译中..." }]);
      await delay(1200);
      pushLines([{ kind: "output", text: "  → 生成文件: 12 · 编译: ✓ · 优化: ✓" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 编译成功 · 耗时 2.1s · 0 错误" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "connect") {
      setBusy(true);
      onFormChange("CONNECTING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "MCP/插件连接中..." }]);
      await delay(1000);
      pushLines([{ kind: "output", text: "  → MCP servers: 24 · 插件: 11 · 集成: 5" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 所有模块已接入" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "tool" || lower === "tools") {
      setBusy(true);
      onFormChange("TOOLING");
      pushLines([{ kind: "agent", agent: "worker", text: "调用工具中..." }]);
      await delay(900);
      pushLines([{ kind: "output", text: "  → FileTool.read · ProcessTool.exec · NetworkTool.get" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 工具调用完成 · 3 个工具执行成功" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "skill" || lower === "skills") {
      setBusy(true);
      onFormChange("SKILLING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "Skills 技能加载中..." }]);
      await delay(1000);
      pushLines([{ kind: "output", text: "  → ReAct · ToT · Racing · RedBlueAdversarial" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ 4 个技能已加载就绪" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }
    if (lower === "mcp") {
      setBusy(true);
      onFormChange("MCPING");
      pushLines([{ kind: "agent", agent: "supervisor", text: "MCP 服务接入中..." }]);
      await delay(1000);
      pushLines([{ kind: "output", text: "  → filesystem · git · sqlite · playwright" }]);
      await delay(700);
      pushLines([{ kind: "success", text: "✓ MCP 服务已连接 · 上下文协议就绪" }]);
      await delay(600);
      onFormChange("IDLE");
      setBusy(false);
      return;
    }

    pushLines([
      { kind: "error", text: `command not found: ${name} — 输入 help 查看可用命令` },
    ]);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      runCommand(input);
      setInput("");
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      const idx = Math.min(historyIdx + 1, history.length - 1);
      if (idx >= 0) {
        setHistoryIdx(idx);
        setInput(history[idx]);
      }
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      const idx = Math.max(historyIdx - 1, -1);
      setHistoryIdx(idx);
      setInput(idx === -1 ? "" : history[idx]);
    }
  };

  return (
    <div
      className="flex h-full flex-col overflow-hidden rounded-2xl border border-white/10 shadow-2xl"
      style={{
        background: `linear-gradient(180deg, ${APEX_THEME.surface} 0%, ${APEX_THEME.background} 100%)`,
        boxShadow: `0 30px 80px -20px ${APEX_THEME.backgroundDeep}, 0 0 0 1px ${accent}22, inset 0 1px 0 ${APEX_THEME.foreground}10`,
      }}
    >
      <TerminalHeader form={form} />

      {/* Body: mascot + output */}
      <div className="grid min-h-0 flex-1 grid-cols-1 md:grid-cols-[320px_1fr]">
        {/* Mascot pane */}
        <div
          className="relative flex flex-col items-center justify-center gap-4 border-b border-white/5 p-6 md:border-b-0 md:border-r"
          style={{
            background: `radial-gradient(ellipse at 50% 40%, ${accent}14 0%, transparent 70%)`,
          }}
        >
          <AuraJellyfish form={form} maxWidth={240} />
          <div className="text-center">
            <div
              className="font-mono text-sm font-semibold tracking-wide"
              style={{ color: accent, textShadow: `0 0 12px ${accent}88` }}
            >
              {meta.emoji} {meta.displayName}
            </div>
            <div className="mt-1 text-xs text-slate-400">{meta.description}</div>
          </div>
          {/* depth grid */}
          <div
            className="pointer-events-none absolute inset-0 opacity-[0.07]"
            style={{
              backgroundImage: `linear-gradient(${APEX_THEME.primary}22 1px, transparent 1px), linear-gradient(90deg, ${APEX_THEME.primary}22 1px, transparent 1px)`,
              backgroundSize: "24px 24px",
            }}
            aria-hidden
          />
        </div>

        {/* Output pane */}
        <div className="flex min-h-0 flex-1 flex-col">
          <div
            ref={scrollRef}
            className="aura-scroll min-h-0 flex-1 overflow-y-auto px-4 py-3 font-mono text-[13px] leading-relaxed"
            onClick={() => inputRef.current?.focus()}
          >
            {lines.map((line) => (
              <Line key={line.id} line={line} accent={accent} />
            ))}
          </div>

          {/* Input row */}
          <div
            className="flex items-center gap-2 border-t border-white/5 px-4 py-2.5 font-mono text-[13px]"
            style={{ background: APEX_THEME.backgroundDeep }}
          >
            <span style={{ color: accent }}>
              {busy ? "⟳" : "❯"}
            </span>
            <span className="text-slate-400">aura@apex</span>
            <span className="text-slate-600">:</span>
            <span style={{ color: APEX_THEME.info }}>~</span>
            <span className="text-slate-600">$</span>
            <input
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              spellCheck={false}
              autoComplete="off"
              placeholder={busy ? "Aura 执行中..." : "输入命令 (help / agent / burst / form ...)"}
              disabled={busy}
              className="flex-1 bg-transparent text-slate-100 caret-cyan-400 outline-none placeholder:text-slate-600 disabled:opacity-60"
              style={{ caretColor: accent }}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

function TerminalHeader({ form }: { form: AuraForm }) {
  const meta = getFormMeta(form);
  const accent = ACCENT_COLOR[meta.accent];
  return (
    <div
      className="flex items-center gap-3 border-b border-white/5 px-4 py-2.5"
      style={{ background: APEX_THEME.surfaceVariant }}
    >
      <div className="flex gap-2">
        <span className="h-3 w-3 rounded-full" style={{ background: "#FF5F57" }} />
        <span className="h-3 w-3 rounded-full" style={{ background: "#FEBC2E" }} />
        <span className="h-3 w-3 rounded-full" style={{ background: "#28C840" }} />
      </div>
      <div className="ml-2 flex items-center gap-2 font-mono text-xs text-slate-400">
        <span
          className="inline-block h-1.5 w-1.5 animate-pulse rounded-full"
          style={{ background: accent, boxShadow: `0 0 8px ${accent}` }}
        />
        apex-terminal — aura-session — {form.toLowerCase()}
      </div>
      <div className="ml-auto flex items-center gap-3 font-mono text-[11px] text-slate-500">
        <span>burst-kernel ✓</span>
        <span className="hidden sm:inline">shizuku ✓</span>
        <span className="hidden md:inline">deepseek-v3</span>
      </div>
    </div>
  );
}

function Line({ line, accent }: { line: TerminalLine; accent: string }) {
  if (line.kind === "prompt") {
    return (
      <div className="mt-1 flex gap-2">
        <span style={{ color: accent }}>❯</span>
        <span className="text-slate-400">aura@apex</span>
        <span className="text-slate-600">:</span>
        <span style={{ color: APEX_THEME.info }}>~</span>
        <span className="text-slate-600">$</span>
        <span className="text-slate-100">{line.text}</span>
      </div>
    );
  }

  const colors: Record<TerminalLine["kind"], string> = {
    prompt: APEX_THEME.foreground,
    output: APEX_THEME.foregroundMuted,
    system: APEX_THEME.info,
    error: APEX_THEME.error,
    success: APEX_THEME.success,
    agent: APEX_THEME.foreground,
  };

  if (line.kind === "agent" && line.agent) {
    const roleColor =
      line.agent === "supervisor"
        ? APEX_THEME.primary
        : line.agent === "worker"
        ? APEX_THEME.success
        : line.agent === "reviewer"
        ? APEX_THEME.warning
        : line.agent === "planner"
        ? APEX_THEME.info
        : APEX_THEME.accent;
    return (
      <div className="mt-0.5 flex gap-2">
        <span style={{ color: roleColor }}>● [{line.agent}]</span>
        <span className="text-slate-300">{line.text}</span>
      </div>
    );
  }

  return (
    <div
      className="mt-0.5 whitespace-pre-wrap"
      style={{ color: colors[line.kind] }}
    >
      {line.text}
    </div>
  );
}

function delay(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}
