import { useEffect, useRef, useState } from "react";

// 滚动遮罩 — 滚动时上下淡入淡出，静止时隐藏
export function ScrollFadeZone({ children, glass = false }: { children: React.ReactNode; glass?: boolean }) {
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
    <div ref={zoneRef} className="relative flex-1 min-h-0">
      {children}
      <div className={maskCls} style={{ top: 0, background: `linear-gradient(to bottom, ${fadeColor}, transparent)` }} />
      <div className={maskCls} style={{ bottom: 0, background: `linear-gradient(to top, ${fadeColor}, transparent)` }} />
    </div>
  );
}
