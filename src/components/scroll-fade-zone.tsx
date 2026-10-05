import { useEffect, useRef, useState } from "react";

// 滚动遮罩 — 滚动时上下淡入淡出，静止时隐藏
export function ScrollFadeZone({
  children,
  glass = false,
  className = "relative flex-1 min-h-0",
}: {
  children: React.ReactNode;
  glass?: boolean;
  /** 默认是"吃掉剩余高度的中间层"; 网格铺满整个区域时由调用方改成 absolute inset-0 */
  className?: string;
}) {
  const [scrolling, setScrolling] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  const zoneRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = zoneRef.current;
    if (!el) return;
    const handler = () => {
      setScrolling(true);
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setScrolling(false), 250);
    };
    el.addEventListener("scroll", handler, true);
    return () => el.removeEventListener("scroll", handler, true);
  }, []);

  const maskCls = `pointer-events-none absolute left-0 right-0 h-7 z-10 transition-opacity duration-300 ${
    scrolling ? "opacity-100" : "opacity-0"
  }`;
  const fadeColor = glass ? "var(--glass-fade)" : "var(--color-zinc-950)";

  return (
    <div ref={zoneRef} className={className}>
      {children}
      {/* 遮罩要贴在浮窗的下沿/上沿, 而不是窗口的边 —— 两条栏浮在网格上时,
          窗口边的遮罩会被栏本身盖住、等于没有。高度取自 CollapsibleBar 写的变量。 */}
      <div className={maskCls} style={{ top: "var(--top-bar-h, 0px)", background: `linear-gradient(to bottom, ${fadeColor}, transparent)` }} />
      <div className={maskCls} style={{ bottom: "var(--bottom-bar-h, 0px)", background: `linear-gradient(to top, ${fadeColor}, transparent)` }} />
    </div>
  );
}
