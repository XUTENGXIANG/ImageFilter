import { useEffect, useRef, useState, useCallback } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
// ═══════════════════════════════════════════════════════
// 🎨 图标约定: 本项目所有图标一律使用 bytedance/IconPark (@icon-park/react)
//    参考: https://github.com/bytedance/IconPark
// ═══════════════════════════════════════════════════════
import { Close, Left, Right, RotateOne, Rotate } from "@icon-park/react";
import type { ScannedPhoto } from "./types";
import { Tip } from "./components/tip";

interface Props {
  photos: ScannedPhoto[];
  index: number;
  ratings: Record<string, number>;
  onRate: (path: string, stars: number) => void;
  onClose: () => void;
  originRect?: { x: number; y: number; w: number; h: number }; // 缩略图位置
  thumbnails: Record<string, string>; // 已有缩略图缓存 (秒显)
  selectedPaths: Set<string>; // 多选状态(与缩略图联动)
  onToggleSelect: (path: string) => void; // 切换勾选
}

function preloadImage(src: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.decoding = "async";
    const ready = () => {
      if (typeof img.decode === "function") {
        img.decode().then(() => resolve(src)).catch(() => reject(new Error("decode failed")));
      } else {
        resolve(src);
      }
    };
    img.onload = ready;
    img.onerror = () => reject(new Error("load failed"));
    img.src = src;
  });
}

export function PhotoViewer({ photos, index, ratings, onRate, onClose, originRect, thumbnails, selectedPaths, onToggleSelect }: Props) {
  const { t } = useTranslation();
  const [cur, setCur] = useState(index);
  // 缩放动画: entering=true 从缩略图位置放大; leaving=true 缩回后关闭
  const [entered, setEntered] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const lastSwitchRef = useRef(0);
  const [src, setSrc] = useState<string | null>(null);   // 高清图 (preview/full)
  const [showSrc, setShowSrc] = useState(false);          // 高清图淡入
  const [fallbackThumbs, setFallbackThumbs] = useState<Record<string, string>>({});
  const [scale, setScale] = useState(1);
  const [rotation, setRotation] = useState(0);            // 0/90/180/270
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const dragRef = useRef<{ startX: number; startY: number; ox: number; oy: number; dragging: boolean }>({ startX: 0, startY: 0, ox: 0, oy: 0, dragging: false });
  const loadedSrcRef = useRef<Record<string, string>>({});
  const currentPathRef = useRef<string | null>(null);
  const prefetchingRef = useRef<Set<string>>(new Set());
  const prefetchTimerRef = useRef<number | undefined>(undefined);

  const commitLoaded = useCallback((path: string, ready: string) => {
    loadedSrcRef.current[path] = ready;
    if (currentPathRef.current === path) {
      setSrc(ready);
      setShowSrc(true);
    }
  }, []);

  const prefetchNeighbors = useCallback((list: ScannedPhoto[], idx: number) => {
    const pending: string[] = [];
    for (const ni of [idx - 1, idx + 1]) {
      const neighbor = list[ni];
      if (!neighbor || loadedSrcRef.current[neighbor.path] || prefetchingRef.current.has(neighbor.path)) continue;
      prefetchingRef.current.add(neighbor.path);
      pending.push(neighbor.path);
    }
    for (const path of pending) {
      invoke<string>("get_preview_image", { filePath: path })
        .then((p) => preloadImage(convertFileSrc(p)))
        .then((ready) => { loadedSrcRef.current[path] = ready; })
        .catch(() => {})
        .finally(() => { prefetchingRef.current.delete(path); });
    }
  }, []);

  const schedulePrefetch = useCallback((list: ScannedPhoto[], idx: number) => {
    if (prefetchTimerRef.current) window.clearTimeout(prefetchTimerRef.current);
    prefetchTimerRef.current = window.setTimeout(() => {
      prefetchTimerRef.current = undefined;
      prefetchNeighbors(list, idx);
    }, 250);
  }, [prefetchNeighbors]);

  const photo = photos[cur];

  const navigateTo = useCallback((next: number) => {
    const target = photos[next];
    if (!target) return;
    currentPathRef.current = target.path;
    const cached = loadedSrcRef.current[target.path];
    if (cached) {
      setSrc(cached);
      setShowSrc(true);
    } else {
      // 不主动清空当前图: 新图就绪前保留旧图, 避免空 src 黑帧
    }
    setCur(next);
  }, [photos]);

  // 进入动画: 先渲染缩略图矩形, 30ms后过渡到全屏
  useEffect(() => {
    const t = window.setTimeout(() => setEntered(true), 30);
    return () => window.clearTimeout(t);
  }, []);

  // 关闭: 先缩回缩略图位置再真正关闭
  const handleClose = () => {
    if (leaving) return;
    setLeaving(true);
    window.setTimeout(onClose, 250);
  };

  // 渐进加载: 先内嵌JPEG秒开, 后台全解码后无感替换
  useEffect(() => {
    if (!photo) return;
    const path = photo.path;
    currentPathRef.current = path;
    const cached = loadedSrcRef.current[path];
    setScale(1);
    setOffset({ x: 0, y: 0 });
    setRotation(0);
    if (cached) {
      setSrc(cached);
      setShowSrc(true);
    } else {
      // 不主动清空当前图: 新图就绪前保留旧图, 避免空 src 黑帧
    }

    let cancelled = false;

    const knownThumb = thumbnails[path] && thumbnails[path] !== "__err__"
      ? thumbnails[path]
      : fallbackThumbs[path];
    if (!cached && !knownThumb) {
      invoke<string>("get_thumbnail_path", { filePath: path, maxSize: 300 })
        .then((p) => {
          if (!cancelled) {
            setFallbackThumbs((prev) => ({ ...prev, [path]: convertFileSrc(p) }));
          }
        })
        .catch(() => {});
    }

    const handleReady = (ready: string) => {
      loadedSrcRef.current[path] = ready;
      if (cancelled) return;
      commitLoaded(path, ready);
      schedulePrefetch(photos, cur);
    };

    // 非RAW直接显示原文件（零解码）
    if (!photo.isRaw) {
      const src = convertFileSrc(photo.path);
      if (!cached) {
        preloadImage(src).then(handleReady);
      } else {
        schedulePrefetch(photos, cur);
      }
      return () => {
        cancelled = true;
        if (prefetchTimerRef.current) {
          window.clearTimeout(prefetchTimerRef.current);
          prefetchTimerRef.current = undefined;
        }
      };
    }

    let fullTimer: number | undefined;

    // 第1步: 内嵌JPEG — 单次切换立即发(零延迟), 快速连续切换时debounce 120ms
    const now = performance.now();
    const rapid = now - lastSwitchRef.current < 500;
    lastSwitchRef.current = now;
    const doPreview = () => {
      invoke<string>("get_preview_image", { filePath: photo.path })
        .then((p) => {
          return preloadImage(convertFileSrc(p));
        })
        .then(handleReady)
        .catch(() => {});
    };
    if (rapid) {
      const pt = window.setTimeout(doPreview, 120);
      return () => {
        cancelled = true;
        window.clearTimeout(pt);
        window.clearTimeout(fullTimer);
        if (prefetchTimerRef.current) {
          window.clearTimeout(prefetchTimerRef.current);
          prefetchTimerRef.current = undefined;
        }
      };
    }
    doPreview();

    // 第2步: 后台全解码（debounce 600ms — 快速切换时旧请求根本不发）
    fullTimer = window.setTimeout(() => {
      invoke<string>("get_full_image", { filePath: photo.path })
        .then((p) => {
          return preloadImage(convertFileSrc(p));
        })
        .then(handleReady)
        .catch(() => {});
    }, 600);

    return () => {
      cancelled = true;
      window.clearTimeout(fullTimer);
      if (prefetchTimerRef.current) {
        window.clearTimeout(prefetchTimerRef.current);
        prefetchTimerRef.current = undefined;
      }
    };
  }, [photo, photos, cur, commitLoaded, schedulePrefetch]);

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
      if (e.key === "Escape") { handleClose(); }
      else if (e.key === "ArrowLeft") { navigateTo((cur - 1 + photos.length) % photos.length); }
      else if (e.key === "ArrowRight") { navigateTo((cur + 1) % photos.length); }
      else if (e.key === "=" || e.key === "+") { setScale((s) => Math.min(8, s * 1.25)); }
      else if (e.key === "-") { setScale((s) => Math.max(0.2, s / 1.25)); }
      else if (e.key === "0") { setScale(1); setOffset({ x: 0, y: 0 }); setRotation(0); }
      else if (e.key.toLowerCase() === "r") { setRotation((r) => (e.shiftKey ? (r + 270) % 360 : (r + 90) % 360)); }
      else if (e.key.toLowerCase() === "j") { onRate(photo.path, 3); }
      else if (e.key.toLowerCase() === "x") { onRate(photo.path, 0); }
      else if (e.key >= "1" && e.key <= "5") { onRate(photo.path, Number(e.key)); }
      else if (e.key === " ") { e.preventDefault(); onToggleSelect(photo.path); } // 空格: 切换勾选
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [photo, cur, photos.length, navigateTo, onClose, onRate]);

  // Wheel zoom — 缩到<=1时居中(重置offset)
  const onWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setScale((s) => {
      const next = Math.min(8, Math.max(0.2, e.deltaY < 0 ? s * 1.15 : s / 1.15));
      if (next <= 1) setOffset({ x: 0, y: 0 });
      return next;
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

  // 缩放动画 clip-path: 始终保留属性, 从缩略图矩形过渡到全屏 inset(0)
  const clipPathVal = originRect && (!entered || leaving)
    ? `inset(${originRect.y}px calc(100% - ${originRect.x + originRect.w}px) calc(100% - ${originRect.y + originRect.h}px) ${originRect.x}px round 12px)`
    : "inset(0px round 0px)";
  const clipStyle: React.CSSProperties = {
    clipPath: clipPathVal,
    transition: "clip-path 250ms cubic-bezier(0.4, 0, 0.2, 1)",
    willChange: "clip-path, opacity",
  };

  return (
    <div
      className="fixed inset-0 z-50 bg-black/95 flex flex-col overflow-hidden"
      style={clipStyle}
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
          {/* 勾选框 — 与缩略图多选联动, 空格键切换 */}
          <Tip label={selectedPaths.has(photo.path) ? t("viewer.unselect") : t("viewer.select")}>
          <button data-tauri-drag-region={false} onClick={() => onToggleSelect(photo.path)}
            className={`w-5 h-5 rounded border-2 flex items-center justify-center transition-colors ${
              selectedPaths.has(photo.path) ? "bg-emerald-500 border-emerald-500" : "border-zinc-500 hover:border-zinc-300"
            }`}
          >
            {selectedPaths.has(photo.path) && <span className="text-white text-[10px] font-bold leading-none">✓</span>}
          </button>
          </Tip>
          {/* 旋转按钮 — 逆时针/顺时针 */}
          <Tip label={t("viewer.rotateCCW")}>
          <button data-tauri-drag-region={false} onClick={() => setRotation((r) => (r + 270) % 360)}
            className="w-8 h-8 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-400"
          ><Rotate theme="filled" size="15" strokeWidth={3} style={{ transform: "scaleX(-1)" }} /></button>
          </Tip>
          <Tip label={t("viewer.rotateCW")}>
          <button data-tauri-drag-region={false} onClick={() => setRotation((r) => (r + 90) % 360)}
            className="w-8 h-8 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-400"
          ><RotateOne theme="filled" size="15" strokeWidth={3} /></button>
          </Tip>
          {/* 星级 */}
          {[1, 2, 3, 4, 5].map((s) => (
            <button key={s} data-tauri-drag-region={false} onClick={() => onRate(photo.path, rating === s ? 0 : s)}
              className={`text-sm px-0.5 ${rating >= s ? "text-amber-400" : "text-zinc-600 hover:text-zinc-400"}`}
            >★</button>
          ))}
          <button data-tauri-drag-region={false} onClick={handleClose} className="ml-3 w-8 h-8 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-400">
            <Close theme="filled" size="16" strokeWidth={3} />
          </button>
        </div>
      </div>

      {/* 图片区 — 缩略图铺底秒显, 高清图加载后淡入替换 */}
      <div className="flex-1 relative overflow-hidden flex items-center justify-center select-none">
        {/* 缩略图 (秒显) */}
        {(() => {
          const thumb = thumbnails[photo.path] && thumbnails[photo.path] !== "__err__"
            ? thumbnails[photo.path]
            : fallbackThumbs[photo.path];
          return thumb && thumb !== "__err__" ? (
            <img
              src={thumb}
              alt=""
              draggable={false}
              className="absolute inset-0 w-full h-full object-contain transition-opacity duration-300"
              style={{ opacity: src && showSrc ? 0 : 1 }}
            />
          ) : null;
        })()}
        {/* 高清图 (preview/full, 淡入) — 拖拽时禁用transform过渡保证跟手 */}
        {src ? (
          <img
            src={src}
            alt={photo.fileName}
            draggable={false}
            decoding="async"
            className="max-w-full max-h-full object-contain"
            style={{
              transform: `translate(${offset.x}px, ${offset.y}px) rotate(${rotation}deg) scale(${scale})`,
              cursor: scale > 1 ? "grab" : "default",
              opacity: showSrc ? 1 : 0,
              transition: dragRef.current.dragging
                ? "opacity 300ms ease"
                : "opacity 300ms ease, transform 100ms",
            }}
          />
        ) : null}

        {/* 左右切换按钮 — 鼠标静止2秒淡出 */}
        <Tip label={t("viewer.prev")} className={`absolute left-3 top-1/2 -translate-y-1/2 ${showNav ? "opacity-100" : "opacity-0"}`}>
        <button
          onClick={(e) => { e.stopPropagation(); navigateTo((cur - 1 + photos.length) % photos.length); }}
          className={`w-10 h-10 rounded-full bg-black/35 hover:bg-black/60 text-white/80 hover:text-white flex items-center justify-center transition-opacity duration-300`}
        ><Left theme="filled" size="18" strokeWidth={3} /></button>
        </Tip>
        <Tip label={t("viewer.next")} className={`absolute right-3 top-1/2 -translate-y-1/2 ${showNav ? "opacity-100" : "opacity-0"}`}>
        <button
          onClick={(e) => { e.stopPropagation(); navigateTo((cur + 1) % photos.length); }}
          className={`w-10 h-10 rounded-full bg-black/35 hover:bg-black/60 text-white/80 hover:text-white flex items-center justify-center transition-opacity duration-300`}
        ><Right theme="filled" size="18" strokeWidth={3} /></button>
        </Tip>
      </div>

      {/* 底部提示 */}
      <div className="flex items-center justify-center gap-3 py-2 flex-shrink-0 text-[10px] text-zinc-600">
        <span>{t("viewer.nav")}</span>
        <span>{t("viewer.zoom")}</span>
        <span>{t("viewer.pan")}</span>
        <span>{t("viewer.reset")}</span>
        <span>{t("viewer.rotate")}</span>
        <span>{t("viewer.rate")}</span>
      </div>
    </div>
  );
}
