// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import React, { useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

/** 子节点里是否存在"有意义的可见文本"。
 *  纯符号(◀ ▶ ▸)不算 —— 读出来没有语义，仍需要补可访问名称。 */
const MEANINGFUL_TEXT = /[\p{L}\p{N}]/u;

function hasMeaningfulText(node: React.ReactNode): boolean {
  let found = false;
  React.Children.forEach(node, (child) => {
    if (found || child === null || child === undefined || typeof child === "boolean") return;
    if (typeof child === "string" || typeof child === "number") {
      if (MEANINGFUL_TEXT.test(String(child))) found = true;
      return;
    }
    if (React.isValidElement(child)) {
      found = hasMeaningfulText((child.props as { children?: React.ReactNode }).children);
    }
  });
  return found;
}

/** 自定义圆角 tooltip — 替代浏览器默认方形提示。
 *
 *  无障碍: 图标按钮没有可见文本, tooltip 文案就是它唯一的可访问名称来源,
 *  这里把它补成 aria-label (WCAG 4.1.2); 调用方已显式给了 aria-label 则不覆盖。
 *  有可见文本的控件不加 aria-label —— 否则会盖掉可见文案 (WCAG 2.5.3 Label in Name)。
 *
 *  定位: tooltip 通过 portal 挂到 body, 并用 position: fixed 计算坐标。
 *  留在面板 / 弹窗内部时, 祖先的 overflow:hidden 会把绝对定位的 tooltip 裁掉
 *  (实测: 设备面板右上角的「收起设备面板」只露出半句)。portal 之后 z-index 也
 *  不再受父级 stacking context 限制。 */
export function Tip({ label, children, className = "" }: { label: string; children: React.ReactNode; className?: string }) {
  let trigger = children;
  if (React.isValidElement(children)) {
    const childProps = children.props as { children?: React.ReactNode; "aria-label"?: string };
    if (!childProps["aria-label"] && !hasMeaningfulText(childProps.children)) {
      trigger = React.cloneElement(
        children as React.ReactElement<{ "aria-label"?: string }>,
        { "aria-label": label },
      );
    }
  }

  const anchorRef = useRef<HTMLSpanElement>(null);
  const tipRef = useRef<HTMLSpanElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);

  const show = () => {
    const rect = anchorRef.current?.getBoundingClientRect();
    if (!rect) return;
    setPos({ top: rect.bottom + 8, left: rect.left + rect.width / 2 });
  };
  const hide = () => setPos(null);

  // 渲染后用实际宽度把中心点夹在视口内，避免贴边时只露出一半。
  useLayoutEffect(() => {
    if (!pos || !tipRef.current) return;
    const half = tipRef.current.offsetWidth / 2;
    const left = Math.min(Math.max(pos.left, half + 8), window.innerWidth - half - 8);
    if (left !== pos.left) tipRef.current.style.left = `${left}px`;
  }, [pos]);

  return (
    <>
      <span
        ref={anchorRef}
        className={`tooltip-wrap ${className}`}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={(event) => {
          // 只在键盘焦点（:focus-visible）时显示；鼠标点击后的 focus 不应该让 tooltip 赖着不走。
          if ((event.target as HTMLElement).matches(":focus-visible")) show();
        }}
        onBlur={hide}
      >
        {trigger}
      </span>
      {pos
        ? createPortal(
            <span
              ref={tipRef}
              aria-hidden="true"
              className="tooltip"
              style={{ top: pos.top, left: pos.left }}
            >
              {label}
            </span>,
            document.body,
          )
        : null}
    </>
  );
}
