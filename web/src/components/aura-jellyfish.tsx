"use client";

import { getFormMeta, type AuraForm } from "@/lib/aura-mascot";
import { ACCENT_COLOR } from "@/lib/terminal-theme";

interface AuraJellyfishProps {
  form: AuraForm;
  /** max width in px for the mascot image */
  maxWidth?: number;
}

/**
 * 极简版 Aura 水母渲染器 —— PNG 图像版(对标 Clawd)。
 *
 * 直接显示定稿的极简 PNG 主形象,14 形态各一张图,切换时换图。
 * 不再用 ASCII,因为 ASCII 画有机圆弧生物天生吃亏。
 * 这才是 Android 端真实会用的渲染方式(ImageView/PNG)。
 */
export function AuraJellyfish({ form, maxWidth = 260 }: AuraJellyfishProps) {
  const meta = getFormMeta(form);
  const accent = ACCENT_COLOR[meta.accent];
  const src = `/aura-${form.toLowerCase()}.png`;

  return (
    <div
      className="relative flex items-center justify-center"
      style={{ "--aura": accent } as React.CSSProperties}
    >
      {/* 极淡背景辉光 — 单色呼吸氛围 */}
      <div
        className="pointer-events-none absolute left-1/2 top-1/2 h-56 w-56 -translate-x-1/2 -translate-y-1/2 rounded-full blur-3xl"
        style={{
          background: `radial-gradient(circle, ${accent}22 0%, transparent 65%)`,
          animation: "aura-breath 3.6s ease-in-out infinite",
        }}
        aria-hidden
      />

      <img
        src={src}
        alt={`Aura mascot — ${meta.displayName}`}
        className="relative z-10 select-none"
        style={{
          maxWidth: `${maxWidth}px`,
          width: "100%",
          height: "auto",
          filter: `drop-shadow(0 0 12px ${accent}44)`,
          animation: "aura-bob 4s ease-in-out infinite",
        }}
        draggable={false}
      />

      <style jsx>{`
        @keyframes aura-bob {
          0%, 100% { transform: translateY(0); }
          50% { transform: translateY(-4px); }
        }
        @keyframes aura-breath {
          0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 0.5; }
          50% { transform: translate(-50%, -50%) scale(1.1); opacity: 0.7; }
        }
      `}</style>
    </div>
  );
}
