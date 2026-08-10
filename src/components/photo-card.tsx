import { useTranslation } from "react-i18next";
import { formatBytes } from "../lib/format";
import type { ScannedPhoto } from "../types";

/** 从文件名取扩展名标签 (dng→DNG, jpg→JPG, png→PNG...) */
function formatBadge(fileName: string): string {
  const ext = fileName.split(".").pop()?.toUpperCase() || "";
  return ext || "?";
}

function Badge({ color, label }: { color: string; label: string }) {
  return <span className={`text-[9px] px-1.5 py-0.5 rounded ${color} text-white font-medium`}>{label}</span>;
}

export function PhotoCard({
  photo, thumbnail, isSelected, isChecked, onClick, onToggle, analysis, rating, onRate, onContextMenu, onDoubleClick,
}: {
  photo: ScannedPhoto; thumbnail?: string; isSelected: boolean; isChecked: boolean;
  onClick: (e: React.MouseEvent) => void; onToggle: (e: React.MouseEvent) => void;
  analysis?: { isBlurry?: boolean; isOverexposed?: boolean; isUnderexposed?: boolean; isBestInGroup?: boolean; duplicateGroup?: number };
  rating?: number; onRate?: (stars: number) => void; onContextMenu?: () => void; onDoubleClick?: (e: React.MouseEvent) => void;
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
          <span className="text-2xl opacity-40">{photo.isVideo ? "🎬" : "📷"}</span>
        </div>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onToggle(e); }}
        className={`absolute top-1.5 right-1.5 w-5 h-5 rounded border-2 flex items-center justify-center transition-opacity z-10 ${
          isChecked
            ? "bg-emerald-500 border-emerald-500 opacity-100"
            : "border-zinc-400 bg-black/40 opacity-0 group-hover:opacity-100"
        }`}
      >
        {isChecked && <span className="text-white text-[10px] font-bold">✓</span>}
      </button>
      <div className="absolute top-1.5 left-1.5 flex gap-1">
        {photo.isRaw && <Badge color="bg-amber-600/80" label={formatBadge(photo.fileName)} />}
        {!photo.isRaw && !photo.isVideo && <Badge color="bg-zinc-600/80" label={formatBadge(photo.fileName)} />}
        {photo.isVideo && <Badge color="bg-blue-600/80" label={t("grid.video")} />}
        {analysis?.isBlurry && <Badge color="bg-red-600/80" label={t("grid.blurry")} />}
        {analysis?.isOverexposed && <Badge color="bg-yellow-600/80" label={t("grid.overexposed")} />}
        {analysis?.isUnderexposed && <Badge color="bg-indigo-600/80" label={t("grid.underexposed")} />}
        {analysis?.duplicateGroup !== undefined && !analysis?.isBestInGroup && <Badge color="bg-gray-600/80" label={t("grid.duplicate")} />}
        {analysis?.isBestInGroup && <Badge color="bg-emerald-600/80" label={t("grid.best")} />}
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
                className={`text-[10px] ${(rating ?? 0) >= s ? "text-amber-400" : "text-zinc-600 hover:text-amber-500"}`}
              >★</button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
