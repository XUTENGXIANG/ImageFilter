// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import type { InputHTMLAttributes } from "react";

import { cn } from "@/lib/utils";

interface SliderProps
  extends Omit<
    InputHTMLAttributes<HTMLInputElement>,
    "type" | "value" | "onChange" | "min" | "max" | "step"
  > {
  value: number;
  min?: number;
  max?: number;
  step?: number;
  onChange: (value: number) => void;
  /** 当前值的可读文本：显示在右侧，同时作为 aria-valuetext */
  valueText?: string;
  showValue?: boolean;
  valueClassName?: string;
}

/**
 * 滑块：原生 range + 已选进度 + 当前值。
 *
 * 键盘直接走原生 range 的行为（方向键 / Home / End / PageUp / PageDown），
 * 拖动时 `onChange` 实时更新；当前值既显示在右侧，也通过 `aria-valuetext`
 * 交给读屏。`:focus-visible` 轮廓与悬停手柄放大在 `.thumb-slider` 里。
 */
export function Slider({
  value,
  min = 0,
  max = 100,
  step = 1,
  onChange,
  valueText,
  showValue = true,
  valueClassName,
  className,
  style,
  ...props
}: SliderProps) {
  const raw = max === min ? 0 : ((value - min) / (max - min)) * 100;
  const pct = Math.min(100, Math.max(0, raw));

  return (
    <div className={cn("slider-root flex min-w-0 items-center gap-2", className)}>
      <div className="relative h-6 min-w-0 flex-1">
        {/* 轨道自己做 4px 圆头；input 只负责 24px 命中区和手柄，
            用透明背景把它自己的方角轨道藏掉。 */}
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-x-0 top-1/2 h-1 -translate-y-1/2 overflow-hidden rounded-full"
          style={{ backgroundColor: "var(--thumb-right)" }}
        >
          <div
            className="h-full rounded-full"
            style={{ width: `${pct}%`, backgroundColor: "var(--thumb-left)" }}
          />
        </div>
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          aria-valuetext={valueText}
          className="thumb-slider relative block w-full cursor-pointer"
          style={{ ...style, background: "transparent" }}
          {...props}
        />
      </div>
      {showValue && valueText ? (
        <span
          className={cn(
            "shrink-0 text-xs tabular-nums text-muted-foreground",
            valueClassName,
          )}
        >
          {valueText}
        </span>
      ) : null}
    </div>
  );
}
