// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import type { ButtonHTMLAttributes } from "react";

import { cn } from "@/lib/utils";

type DialogActionVariant = "default" | "primary" | "danger" | "dangerOutline" | "warning";

const VARIANTS: Record<DialogActionVariant, string> = {
  default: "border-border text-muted-foreground hover:bg-muted hover:text-foreground",
  primary: "border-transparent bg-brand-strong text-white font-medium hover:bg-brand",
  danger: "border-transparent bg-danger-strong text-white font-medium hover:bg-danger-strong/90",
  dangerOutline: "border-danger/50 text-danger hover:bg-danger/10",
  warning: "border-warning/60 text-foreground hover:bg-warning/20",
};

/**
 * 对话框里的小动作按钮（复制 / 添加 / 移除 / 允许 / 拒绝…）。
 *
 * 抽出来的理由：settings-dialog 里同一套 `px-2 py-1 rounded-2xl border …`
 * 被重复写了十几次；改一次间距要翻遍整个文件。颜色走语义令牌，不直接写
 * emerald / red / amber 档位。
 */
export function DialogAction({
  className,
  variant = "default",
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: DialogActionVariant;
}) {
  return (
    <button
      type={type}
      className={cn(
        "shrink-0 rounded-2xl border px-2 py-1 text-xs transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50",
        "disabled:opacity-50 disabled:cursor-not-allowed",
        VARIANTS[variant],
        className,
      )}
      {...props}
    />
  );
}
