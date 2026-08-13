import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useLocalStorageNumber } from "../hooks/use-local-storage-setting";
import { Tip } from "./tip";

export function ThumbSizeSlider() {
  const { t } = useTranslation();
  // 数值经 Number.isFinite + clamp 校验, 损坏的 localStorage 不会产生 NaN 非法 CSS
  const [cols, setCols] = useLocalStorageNumber("imagefilter-cols", 4, 2, 8);
  useEffect(() => {
    const id = "imagefilter-grid-cols";
    let el = document.getElementById(id) as HTMLStyleElement | null;
    if (!el) { el = document.createElement("style"); el.id = id; document.head.appendChild(el); }
    el.textContent = `.photo-grid { grid-template-columns: repeat(${cols}, minmax(0, 1fr)); }`;
  }, [cols]);
  const pct = ((cols - 2) / (8 - 2)) * 100;
  return (
    <Tip label={t("toolbar.cols", { n: cols })} className="flex items-center">
    <input
      type="range" min={2} max={8} value={cols}
      onChange={(e) => setCols(Number(e.target.value))}
      className="thumb-slider w-16 h-4 cursor-pointer"
      style={{
        background: `linear-gradient(to right,
          var(--thumb-left) 0%, var(--thumb-left) ${pct}%,
          var(--thumb-right) ${pct}%, var(--thumb-right) 100%)`,
      }}
    />
    </Tip>
  );
}
