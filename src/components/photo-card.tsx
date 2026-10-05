import { memo } from "react";
import { useTranslation } from "react-i18next";
import { Movie, PictureOne } from "@icon-park/react";
import { formatBytes } from "../lib/format";
import type { ScannedPhoto } from "../types";
import { LABEL_BG, type Label } from "../labels";

/** 从文件名取扩展名标签 (dng→DNG, jpg→JPG, png→PNG...) */
function formatBadge(fileName: string): string {
  const ext = fileName.split(".").pop()?.toUpperCase() || "";
  return ext || "?";
}

/** 徽章色调 — 语义化取值, 具体颜色由 CSS 的 .badge-glass[data-tone] 决定,
 *  调用点不再拼 Tailwind 类名(材质/配色集中在 index.css 的令牌层)。 */
export type BadgeTone = "raw" | "jpg" | "video" | "blurry" | "over" | "under" | "dup" | "best";

/** 照片卡片徽章。材质=毛玻璃, 见 index.css 的 .badge-glass(含 alpha 的实测依据)。 */
function Badge({ tone, label }: { tone: BadgeTone; label: string }) {
  return (
    <span className="badge-glass text-[9px] px-1.5 py-0.5 rounded font-medium" data-tone={tone}>
      {label}
    </span>
  );
}

/** 照片类型徽标 — 三种互斥状态折叠为单一逻辑 */
function TypeBadge({ photo }: { photo: ScannedPhoto }) {
  const { t } = useTranslation();
  if (photo.isVideo) return <Badge tone="video" label={t("grid.video")} />;
  return <Badge tone={photo.isRaw ? "raw" : "jpg"} label={formatBadge(photo.fileName)} />;
}

export const PhotoCard = memo(function PhotoCard({
  photo, thumbnail, isSelected, isChecked, onClick, onToggle, analysis, rating, label, onRate, onContextMenu, onDoubleClick,
}: {
  photo: ScannedPhoto; thumbnail?: string; isSelected: boolean; isChecked: boolean;
  onClick: (e: React.MouseEvent) => void; onToggle: (e: React.MouseEvent) => void;
  analysis?: { isBlurry?: boolean; isOverexposed?: boolean; isUnderexposed?: boolean; isBestInGroup?: boolean; duplicateGroup?: number };
  rating?: number; label?: Label; onRate?: (stars: number) => void; onContextMenu?: () => void; onDoubleClick?: (e: React.MouseEvent) => void;
}) {
  const { t } = useTranslation();
  return (
    <div
      data-photo-path={photo.path}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      onContextMenu={onContextMenu}
      className={`relative aspect-square rounded-lg overflow-hidden cursor-pointer border-2 border-zinc-800 transition-all group ${
        isSelected
          ? "!border-emerald-400 shadow-lg shadow-emerald-500/20"
          : ""
      }`}
    >
      {thumbnail ? (
        <img src={thumbnail} alt={photo.fileName} className="w-full h-full object-cover" loading="lazy" />
      ) : (
        <div className="w-full h-full bg-zinc-800/50 flex items-center justify-center">
          {photo.isVideo
            ? <Movie theme="filled" size="20" className="opacity-40" />
            : <PictureOne theme="filled" size="20" className="opacity-40" />}
        </div>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onToggle(e); }}
        aria-label={isChecked ? t("grid.unselect") : t("grid.select")}
        aria-pressed={isChecked}
        className={`hit-24 absolute top-1.5 right-1.5 w-5 h-5 rounded border-2 flex items-center justify-center transition-opacity z-10 ${
          isChecked
            ? "bg-emerald-500 border-emerald-500 opacity-100"
            : "border-zinc-400 bg-black/40 opacity-0 group-hover:opacity-100"
        }`}
      >
        {isChecked && <span className="text-white text-[10px] font-bold">✓</span>}
      </button>
      <div className="absolute top-1.5 left-1.5 flex gap-1">
        <TypeBadge photo={photo} />
        {analysis?.isBlurry && <Badge tone="blurry" label={t("grid.blurry")} />}
        {analysis?.isOverexposed && <Badge tone="over" label={t("grid.overexposed")} />}
        {analysis?.isUnderexposed && <Badge tone="under" label={t("grid.underexposed")} />}
        {analysis?.duplicateGroup !== undefined && !analysis?.isBestInGroup && <Badge tone="dup" label={t("grid.duplicate")} />}
        {analysis?.isBestInGroup && <Badge tone="best" label={t("grid.best")} />}
        {/* Phase 4: 颜色标签 —— **只读**展示(卡片不新增可点区域: 免得又多一个
            "点了会不会勾选"的交互面)。鼠标入口是右键"颜色标签"子菜单,
            键盘是 Ctrl/Alt + 1-5(修饰键在设置里选)。用圆点而不是图标/文字:
            五种颜色本身就是信息, 这里也不适合挂 tooltip(卡片是 overflow-hidden)。 */}
        {label && (
          <span className={`w-3 h-3 mt-[3px] rounded-full border border-white/60 shadow ${LABEL_BG[label]}`} />
        )}
      </div>
      {(rating ?? 0) > 0 && (
        <div className="absolute bottom-1.5 right-1.5 text-[10px] text-amber-400">
          {"★".repeat(rating ?? 0)}
        </div>
      )}
      <div className="absolute bottom-0 inset-x-0 hover-overlay p-2 pt-6 opacity-0 group-hover:opacity-100 transition-opacity">
        <p className="text-[10px] text-zinc-200 truncate leading-tight">{photo.fileName}</p>
        <p className="text-[9px] text-zinc-400">{formatBytes(photo.fileSize)}</p>
        {onRate && (
          <div className="flex gap-0.5 mt-0.5">
            {[1,2,3,4,5].map((s) => (
              <button key={s} onClick={(e) => { e.stopPropagation(); onRate(s); }}
                className={`w-6 h-6 flex items-center justify-center text-[10px] ${(rating ?? 0) >= s ? "text-amber-400" : "text-zinc-600 hover:text-amber-500"}`}
              >★</button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
});
