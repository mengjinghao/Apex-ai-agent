/**
 * Apex Terminal — Deep Sea Aurora Theme (精致版)
 *
 * Refined from ApexTerminalTheme.kt.
 * Deep space black (indigo tint) + electric cyan + coral pink.
 */

export const APEX_THEME = {
  background: "#0A0E1A",
  backgroundDeep: "#060912",
  surface: "#111827",
  surfaceVariant: "#1A2332",
  surfaceElevated: "#1E2940",
  foreground: "#E2E8F0",
  foregroundMuted: "#94A3B8",
  foregroundDim: "#64748B",

  primary: "#00E5FF", // electric cyan
  primaryDim: "#00B8D4",
  accent: "#FF6B9D", // coral pink
  accentDim: "#E91E63",

  success: "#4ADE80", // mint
  warning: "#FBBF24", // amber
  error: "#EF4444", // rose
  info: "#60A5FA", // sky
  violet: "#A78BFA",

  terminalCursor: "#00E5FF",
} as const;

export type AccentToken = "cyan" | "pink" | "amber" | "mint" | "rose" | "violet" | "sky";

export const ACCENT_COLOR: Record<AccentToken, string> = {
  cyan: APEX_THEME.primary,
  pink: APEX_THEME.accent,
  amber: APEX_THEME.warning,
  mint: APEX_THEME.success,
  rose: APEX_THEME.error,
  violet: APEX_THEME.violet,
  sky: APEX_THEME.info,
};

/** Agent role colors (from ApexTerminalTheme.AgentColors) */
export const AGENT_COLORS = {
  supervisor: APEX_THEME.primary,
  worker: APEX_THEME.success,
  reviewer: APEX_THEME.warning,
  critic: APEX_THEME.accent,
  observer: APEX_THEME.info,
  system: APEX_THEME.foregroundMuted,
} as const;
