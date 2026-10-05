import React from "react";

/** 子节点里是否存在"有意义的可见文本"。
 *  纯符号(◀ ▶ ▸)不算 —— 读出来没有语义, 仍需要补可访问名称。 */
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
 *  有可见文本的控件不加 aria-label —— 否则会盖掉可见文案 (WCAG 2.5.3 Label in Name)。 */
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

  return (
    <span className={`tooltip-wrap ${className}`}>
      {trigger}
      <span className="tooltip">{label}</span>
    </span>
  );
}
