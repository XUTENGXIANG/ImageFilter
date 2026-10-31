// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useLocalStorageNumber } from "../hooks/use-local-storage-setting";
import { Slider } from "./ui/slider";
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
  return (
    <Tip label={t("toolbar.cols", { n: cols })} className="flex items-center shrink-0">
      <Slider
        value={cols}
        min={2}
        max={8}
        onChange={setCols}
        aria-label={t("toolbar.cols", { n: cols })}
        aria-valuetext={t("toolbar.cols", { n: cols })}
        valueText={String(cols)}
        showValue
        className="w-20"
        valueClassName="w-3 text-right text-[10px] text-zinc-500"
      />
    </Tip>
  );
}
