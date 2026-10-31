// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

export interface SegmentedOption<T extends string> {
  value: T;
  label: ReactNode;
  disabled?: boolean;
  title?: string;
}

interface SegmentedControlProps<T extends string> {
  options: ReadonlyArray<SegmentedOption<T>>;
  value: T;
  onChange: (value: T) => void;
  /** sm = 更紧凑（LRC 那一行），md = 默认设置行 */
  size?: "sm" | "md";
  /** 分段控件右侧的附加动作（例如 LRC 的「重新检测」） */
  trailing?: ReactNode;
  className?: string;
}

/**
 * 分段控件：语言 / 标签修饰键 / XMP 模式 / Lightroom 模式共用。
 *
 * 之前这四处各写了一遍 `flex rounded-2xl border … overflow-hidden` 和同样的
 * 选中/禁用样式；差异只有 label、value 和 disabled 规则。
 */
export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  size = "md",
  trailing,
  className,
}: SegmentedControlProps<T>) {
  return (
    <div className={cn("flex rounded-2xl border border-border overflow-hidden text-sm", className)}>
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            disabled={option.disabled}
            title={option.title}
            onClick={() => onChange(option.value)}
            className={cn(
              size === "sm" ? "px-3 py-1" : "px-3 py-1.5",
              "transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50",
              option.disabled
                ? "text-muted-foreground/50 cursor-not-allowed"
                : selected
                  ? "bg-foreground text-background"
                  : "hover:bg-muted text-muted-foreground",
            )}
          >
            {option.label}
          </button>
        );
      })}
      {trailing}
    </div>
  );
}
