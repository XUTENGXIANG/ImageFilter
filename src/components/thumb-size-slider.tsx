import { useEffect, useState } from "react";

export function ThumbSizeSlider() {
  const [cols, setCols] = useState(() => {
    try { return parseInt(localStorage.getItem("pixelflow-cols") || "4"); }
    catch { return 4; }
  });
  useEffect(() => {
    const id = "pixelflow-grid-cols";
    let el = document.getElementById(id) as HTMLStyleElement | null;
    if (!el) { el = document.createElement("style"); el.id = id; document.head.appendChild(el); }
    el.textContent = `.photo-grid { grid-template-columns: repeat(${cols}, minmax(0, 1fr)); }`;
    localStorage.setItem("pixelflow-cols", String(cols));
  }, [cols]);
  const pct = ((cols - 2) / (8 - 2)) * 100;
  return (
    <input
      type="range" min={2} max={8} value={cols}
      onChange={(e) => setCols(Number(e.target.value))}
      className="thumb-slider w-16 h-4 cursor-pointer"
      title={`${cols} cols`}
      style={{
        background: `linear-gradient(to right,
          var(--thumb-left) 0%, var(--thumb-left) ${pct}%,
          var(--thumb-right) ${pct}%, var(--thumb-right) 100%)`,
      }}
    />
  );
}
