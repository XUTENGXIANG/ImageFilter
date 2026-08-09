import { useEffect, useRef, useState, useCallback } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import type { ScannedPhoto } from "./types";

interface Props {
  photos: ScannedPhoto[];
  index: number;
  ratings: Record<string, number>;
  onRate: (path: string, stars: number) => void;
  onClose: () => void;
}

export function PhotoViewer({ photos, index, ratings, onRate, onClose }: Props) {
  const [cur, setCur] = useState(index);
  const lastSwitchRef = useRef(0);
  const [src, setSrc] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const dragRef = useRef<{ startX: number; startY: number; ox: number; oy: number; dragging: boolean }>({ startX: 0, startY: 0, ox: 0, oy: 0, dragging: false });

  const photo = photos[cur];

  // 渐进加载: 先内嵌JPEG秒开, 后台全解码后无感替换
  useEffect(() => {
    if (!photo) return;
    setScale(1);
    setOffset({ x: 0, y: 0 });

    // 非RAW直接显示原文件（零解码）
    if (!photo.isRaw) {
      setSrc(convertFileSrc(photo.path));
      setLoading(false);
      return;
    }

    setLoading(true);
    let cancelled = false;
    let fullTimer: number | undefined;

    // 第1步: 内嵌JPEG — 单次切换立即发(零延迟), 快速连续切换时debounce 120ms
    const now = performance.now();
    const rapid = now - lastSwitchRef.current < 500;
    lastSwitchRef.current = now;
    const doPreview = () => {
      invoke<string>("get_preview_image", { filePath: photo.path })
        .then((p) => {
          if (!cancelled) { setSrc(convertFileSrc(p)); setLoading(false); }
        })
        .catch(() => { if (!cancelled) setLoading(false); });
    };
    if (rapid) {
      const pt = window.setTimeout(doPreview, 120);
      return () => { cancelled = true; window.clearTimeout(pt); window.clearTimeout(fullTimer); };
    }
    doPreview();

    // 第2步: 后台全解码（debounce 300ms — 快速切换时旧请求根本不发）
    fullTimer = window.setTimeout(() => {
      invoke<string>("get_full_image", { filePath: photo.path })
        .then((p) => {
          if (!cancelled) setSrc(convertFileSrc(p));
        })
        .catch(() => {});
    }, 300);

    return () => {
      cancelled = true;
      window.clearTimeout(fullTimer);
    };

    return () => { cancelled = true; };
  }, [photo]);

  // 导航按钮显隐: 鼠标移动显示, 静止2秒隐藏
  const [showNav, setShowNav] = useState(true);
  const navTimer = useRef<number | undefined>(undefined);
  const showNavOnMove = () => {
    setShowNav(true);
    window.clearTimeout(navTimer.current);
    navTimer.current = window.setTimeout(() => setShowNav(false), 2000);
  };

  // Keyboard: ←/→ navigate, Esc close, +/- zoom, J/X/1-5 rate
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") { onClose(); }
      else if (e.key === "ArrowLeft") { setCur((c) => (c - 1 + photos.length) % photos.length); }
      else if (e.key === "ArrowRight") { setCur((c) => (c + 1) % photos.length); }
      else if (e.key === "=" || e.key === "+") { setScale((s) => Math.min(8, s * 1.25)); }
      else if (e.key === "-") { setScale((s) => Math.max(0.2, s / 1.25)); }
      else if (e.key === "0") { setScale(1); setOffset({ x: 0, y: 0 }); }
      else if (e.key.toLowerCase() === "j") { onRate(photo.path, 3); }
      else if (e.key.toLowerCase() === "x") { onRate(photo.path, 0); }
      else if (e.key >= "1" && e.key <= "5") { onRate(photo.path, Number(e.key)); }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [photo, photos.length, onClose, onRate]);

  // Wheel zoom (centered)
  const onWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setScale((s) => {
      const next = e.deltaY < 0 ? s * 1.15 : s / 1.15;
      return Math.min(8, Math.max(0.2, next));
    });
  }, []);

  // Drag pan (only when zoomed)
  const onMouseDown = (e: React.MouseEvent) => {
    if (scale <= 1) return;
    dragRef.current = { startX: e.clientX, startY: e.clientY, ox: offset.x, oy: offset.y, dragging: true };
  };
  const onMouseMove = (e: React.MouseEvent) => {
    showNavOnMove();
    if (!dragRef.current.dragging) return;
    setOffset({
      x: dragRef.current.ox + (e.clientX - dragRef.current.startX),
      y: dragRef.current.oy + (e.clientY - dragRef.current.startY),
    });
  };
  const onMouseUp = () => { dragRef.current.dragging = false; };

  if (!photo) return null;
  const rating = ratings[photo.path] || 0;

  return (
    <div
      className="fixed inset-0 z-50 bg-black/95 flex flex-col"
      onWheel={onWheel}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseUp={onMouseUp}
    >
      {/* 顶部工具栏 — 可拖拽窗口 */}
      <div data-tauri-drag-region className="flex items-center justify-between px-4 py-2 flex-shrink-0 select-none">
        <div className="flex items-center gap-2" data-tauri-drag-region>
          <span className="text-xs text-zinc-400">{photo.fileName}</span>
          <span className="text-[10px] text-zinc-600">
            {cur + 1} / {photos.length}
            <span className="ml-2 text-amber-500/80">{photo.fileName.split(".").pop()?.toUpperCase()}</span>
          </span>
        </div>
        <div className="flex items-center gap-1">
          {/* 星级 */}
          {[1, 2, 3, 4, 5].map((s) => (
            <button key={s} data-tauri-drag-region={false} onClick={() => onRate(photo.path, rating === s ? 0 : s)}
              className={`text-sm px-0.5 ${rating >= s ? "text-amber-400" : "text-zinc-600 hover:text-zinc-400"}`}
            >★</button>
          ))}
          <button data-tauri-drag-region={false} onClick={onClose} className="ml-3 w-8 h-8 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-400">
            ✕
          </button>
        </div>
      </div>

      {/* 图片区 */}
      <div className="flex-1 relative overflow-hidden flex items-center justify-center select-none">
        {loading && !src ? (
          <div className="flex flex-col items-center gap-3">
            <div className="w-8 h-8 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
            <p className="text-zinc-500 text-xs">解码原图中...</p>
          </div>
        ) : src ? (
          <img
            src={src}
            alt={photo.fileName}
            draggable={false}
            className="max-w-full max-h-full object-contain transition-transform duration-100"
            style={{
              transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
              cursor: scale > 1 ? "grab" : "default",
            }}
          />
        ) : null}

        {/* 左右切换按钮 — 鼠标静止2秒淡出 */}
        <button
          onClick={(e) => { e.stopPropagation(); setCur((c) => (c - 1 + photos.length) % photos.length); }}
          className={`absolute left-3 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-black/35 hover:bg-black/60 text-white/80 hover:text-white flex items-center justify-center text-lg transition-opacity duration-300 ${showNav ? "opacity-100" : "opacity-0"}`}
          title="上一张 (←)"
        >&lt;</button>
        <button
          onClick={(e) => { e.stopPropagation(); setCur((c) => (c + 1) % photos.length); }}
          className={`absolute right-3 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-black/35 hover:bg-black/60 text-white/80 hover:text-white flex items-center justify-center text-lg transition-opacity duration-300 ${showNav ? "opacity-100" : "opacity-0"}`}
          title="下一张 (→)"
        >&gt;</button>
      </div>

      {/* 底部提示 */}
      <div className="flex items-center justify-center gap-3 py-2 flex-shrink-0 text-[10px] text-zinc-600">
        <span>← → 切换</span>
        <span>滚轮 缩放</span>
        <span>拖动 平移</span>
        <span>0 重置</span>
        <span>J 保留 / X 废弃 / 1-5 星级</span>
      </div>
    </div>
  );
}
