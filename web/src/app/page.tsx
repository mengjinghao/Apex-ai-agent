"use client";

import { useState } from "react";
import { AuraTerminal } from "@/components/aura-terminal";
import { AURA_FORMS, type AuraForm } from "@/lib/aura-mascot";
import { ACCENT_COLOR, APEX_THEME } from "@/lib/terminal-theme";

export default function Home() {
  const [form, setForm] = useState<AuraForm>("IDLE");

  return (
    <div
      className="flex min-h-screen flex-col"
      style={{
        background: APEX_THEME.backgroundDeep,
        color: APEX_THEME.foreground,
      }}
    >
      <BackgroundFx />

      <header className="relative z-10 border-b border-white/5 px-4 py-3 backdrop-blur-sm sm:px-6">
        <div className="mx-auto flex max-w-7xl items-center gap-3">
          <Logo />
          <div className="flex flex-col">
            <span className="font-mono text-sm font-semibold tracking-wide" style={{ color: APEX_THEME.primary }}>
              APEX TERMINAL
            </span>
            <span className="font-mono text-[10px] text-slate-500">Deep Sea Aurora · Aura Jellyfish</span>
          </div>
          <nav className="ml-auto hidden items-center gap-1 font-mono text-xs sm:flex">
            <HeaderTag color={APEX_THEME.primary} label="v2.4.0" />
            <HeaderTag color={APEX_THEME.accent} label="burst-ready" />
            <HeaderTag color={APEX_THEME.success} label="22 forms" />
          </nav>
        </div>
      </header>

      <main className="relative z-10 mx-auto flex w-full max-w-7xl flex-1 flex-col gap-4 px-4 py-4 sm:px-6 sm:py-6">
        {/* Hero strip */}
        <section className="flex flex-col items-start justify-between gap-3 sm:flex-row sm:items-center">
          <div>
            <h1 className="font-mono text-lg font-bold tracking-tight sm:text-xl">
              <span style={{ color: APEX_THEME.primary }}>Aura</span>{" "}
              <span className="text-slate-300">· 深海极光水母</span>
            </h1>
            <p className="mt-1 font-mono text-xs text-slate-500">
              精致版终端吉祥物 — 14 形态高精度 ASCII 动画 · 多层光晕 · 生物荧光粒子
            </p>
          </div>
          <div className="flex items-center gap-2 font-mono text-[11px] text-slate-400">
            <span className="inline-block h-2 w-2 animate-pulse rounded-full" style={{ background: APEX_THEME.success, boxShadow: `0 0 8px ${APEX_THEME.success}` }} />
            session active
          </div>
        </section>

        {/* Terminal */}
        <section className="flex min-h-[460px] flex-1 flex-col md:min-h-[560px]">
          <AuraTerminal form={form} onFormChange={setForm} />
        </section>

        {/* Form selector */}
        <section>
          <div className="mb-2 flex items-center gap-2 font-mono text-xs text-slate-400">
            <span style={{ color: APEX_THEME.primary }}>◆</span> AURA 形态切换
            <span className="text-slate-600">— 点击切换吉祥物动画</span>
          </div>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-8">
            {AURA_FORMS.map((f) => {
              const active = f.form === form;
              const color = ACCENT_COLOR[f.accent];
              return (
                <button
                  key={f.form}
                  onClick={() => setForm(f.form)}
                  className="group relative flex flex-col items-start gap-0.5 overflow-hidden rounded-lg border px-3 py-2 text-left transition-all duration-200"
                  style={{
                    borderColor: active ? color : "rgba(255,255,255,0.08)",
                    background: active
                      ? `linear-gradient(135deg, ${color}22 0%, ${color}08 100%)`
                      : "rgba(255,255,255,0.02)",
                    boxShadow: active ? `0 0 0 1px ${color}55, 0 4px 20px -4px ${color}44` : "none",
                  }}
                >
                  <span className="text-base leading-none">{f.emoji}</span>
                  <span
                    className="font-mono text-[11px] font-semibold"
                    style={{ color: active ? color : APEX_THEME.foreground }}
                  >
                    {f.displayName}
                  </span>
                  <span className="font-mono text-[9px] uppercase tracking-wide text-slate-500">
                    {f.form}
                  </span>
                  {active && (
                    <span
                      className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full"
                      style={{ background: color, boxShadow: `0 0 6px ${color}` }}
                    />
                  )}
                </button>
              );
            })}
          </div>
        </section>
      </main>

      <footer
        className="relative z-10 mt-auto border-t border-white/5 px-4 py-3 sm:px-6"
        style={{ background: APEX_THEME.backgroundDeep }}
      >
        <div className="mx-auto flex max-w-7xl flex-col items-center justify-between gap-2 font-mono text-[11px] text-slate-500 sm:flex-row">
          <div className="flex items-center gap-2">
            <span style={{ color: APEX_THEME.primary }}>◆</span>
            Apex Auto Agent · Aura 深海极光水母 · 精致版
          </div>
          <div className="flex items-center gap-3">
            <span className="hidden sm:inline">burst-kernel ✓ · shizuku ✓ · multi-agent ✓</span>
            <span style={{ color: APEX_THEME.accent }}>refined UI</span>
          </div>
        </div>
      </footer>
    </div>
  );
}

function HeaderTag({ color, label }: { color: string; label: string }) {
  return (
    <span
      className="rounded-full border px-2.5 py-0.5"
      style={{ borderColor: `${color}44`, color, background: `${color}11` }}
    >
      {label}
    </span>
  );
}

function Logo() {
  return (
    <div
      className="relative flex h-9 w-9 items-center justify-center rounded-lg"
      style={{
        background: `linear-gradient(135deg, ${APEX_THEME.primary}33 0%, ${APEX_THEME.accent}22 100%)`,
        border: `1px solid ${APEX_THEME.primary}44`,
        boxShadow: `0 0 16px -2px ${APEX_THEME.primary}55, inset 0 0 12px -4px ${APEX_THEME.primary}`,
      }}
    >
      <span
        className="font-mono text-sm font-bold"
        style={{ color: APEX_THEME.primary, textShadow: `0 0 8px ${APEX_THEME.primary}` }}
      >
        A
      </span>
    </div>
  );
}

/** Ambient deep-sea background effects: gradient + drifting glow orbs. */
function BackgroundFx() {
  return (
    <div className="pointer-events-none fixed inset-0 overflow-hidden" aria-hidden>
      {/* base gradient */}
      <div
        className="absolute inset-0"
        style={{
          background: `radial-gradient(ellipse at 20% 0%, ${APEX_THEME.primary}14 0%, transparent 50%), radial-gradient(ellipse at 80% 100%, ${APEX_THEME.accent}12 0%, transparent 50%), ${APEX_THEME.backgroundDeep}`,
        }}
      />
      {/* drifting orbs */}
      <div
        className="absolute -left-20 top-1/4 h-72 w-72 rounded-full opacity-30 blur-3xl"
        style={{ background: APEX_THEME.primary, animation: "orb-drift 18s ease-in-out infinite" }}
      />
      <div
        className="absolute -right-20 bottom-1/4 h-80 w-80 rounded-full opacity-20 blur-3xl"
        style={{ background: APEX_THEME.accent, animation: "orb-drift 22s ease-in-out infinite reverse" }}
      />
      <div
        className="absolute left-1/3 top-1/2 h-64 w-64 rounded-full opacity-10 blur-3xl"
        style={{ background: APEX_THEME.violet, animation: "orb-drift 26s ease-in-out infinite" }}
      />
      {/* subtle grid */}
      <div
        className="absolute inset-0 opacity-[0.04]"
        style={{
          backgroundImage: `linear-gradient(${APEX_THEME.primary} 1px, transparent 1px), linear-gradient(90deg, ${APEX_THEME.primary} 1px, transparent 1px)`,
          backgroundSize: "48px 48px",
        }}
      />
      <style>{`
        @keyframes orb-drift {
          0%, 100% { transform: translate(0, 0) scale(1); }
          33% { transform: translate(40px, -30px) scale(1.1); }
          66% { transform: translate(-30px, 40px) scale(0.95); }
        }
      `}</style>
    </div>
  );
}
