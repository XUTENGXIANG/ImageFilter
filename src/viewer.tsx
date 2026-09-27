import { useEffect, useRef, useState, useCallback } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
// ═══════════════════════════════════════════════════════
// 🎨 图标约定: 本项目所有图标一律使用 bytedance/IconPark (@icon-park/react)
//    参考: https://github.com/bytedance/IconPark
// ═══════════════════════════════════════════════════════
import { Close, Left, Right, RotateOne, Rotate } from "@icon-park/react";
import type { ScannedPhoto } from "./types";
import type { Patch } from "./undo";
import { LABEL_BG, LABEL_ORDER, isLabelChord, type Label } from "./labels";
import { Tip } from "./components/tip";

interface Props {
  photos: ScannedPhoto[];
  index: number;
  ratings: Record<string, number>;
  onRate: (path: string, stars: number) => void;
  // ── Phase 4 · 颜色标签 ──
  labels: Record<string, Label>;
  onLabel: (path: string, label: Label | null) => void;
  /** 打标签用的修饰键(Ctrl / Alt, 在设置里选; 见 labels.ts 的 isLabelChord) */
  labelModifier: "ctrl" | "alt";
  onClose: () => void;
  originRect?: { x: number; y: number; w: number; h: number }; // 缩略图位置
  thumbnails: Record<string, string>; // 已有缩略图缓存 (秒显)
  selectedPaths: Set<string>; // 多选状态(与缩略图联动)
  onToggleSelect: (path: string) => void; // 切换勾选
  autoAdvance: boolean; // 评分后自动跳到下一张(设置项, 默认开)
  // ── Phase 2 撤销/重做 ──
  // 可空: 查看器是纯展示组件, 没接撤销能力时 Ctrl+Z 直接不管(不 preventDefault)
  onUndo?: () => Patch | null;
  onRedo?: () => Patch | null;
  /** 最近一次撤销所作用的那张照片: 撤销前先把画面调回它, 用户才看得见撤了什么 */
  undoTargetPath?: string | null;
  onUndoToast?: (patch: Patch, kind: "undo" | "redo") => void;
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

// 长按(键盘 auto-repeat)必须忽略的一次性按键。评分键在"自动前进"开启后尤其危险:
// 长按 3 一秒会把后面几十张全部打上 3 星 —— 用户看不见的批量误写。
// 不含 ←/→: 方向键长按连翻是既有手感, 不能改。
const NON_REPEAT_KEYS = new Set(["j", "x", "1", "2", "3", "4", "5", "z"]);

/** 适应窗口比例 = 元素布局盒 / 自然尺寸。
 *  用 offsetWidth/offsetHeight(布局盒)而不是 getBoundingClientRect(): 后者含 transform,
 *  旋转 90/270 时宽高互换会算错; 布局盒天然不含 transform, 旋转下同样成立。
 *  返回 0 表示图还没就绪(naturalWidth 为 0)。 */
function fitScaleOf(el: HTMLImageElement): number {
  if (!el.naturalWidth || !el.naturalHeight || !el.offsetWidth || !el.offsetHeight) return 0;
  return Math.min(el.offsetWidth / el.naturalWidth, el.offsetHeight / el.naturalHeight);
}

export function PhotoViewer({ photos, index, ratings, onRate, labels, onLabel, labelModifier, onClose, originRect, thumbnails, selectedPaths, onToggleSelect, autoAdvance, onUndo, onRedo, undoTargetPath, onUndoToast }: Props) {
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
  const [pixelView, setPixelView] = useState(false);       // 1:1 实际像素模式标记
  const imgRef = useRef<HTMLImageElement | null>(null);    // 高清图(读 naturalWidth / 布局盒)
  const areaRef = useRef<HTMLDivElement | null>(null);     // 图片区容器(算鼠标相对中心坐标)
  const hoverRef = useRef({ x: 0, y: 0 });                 // 鼠标相对容器中心; 没进过图区就是 {0,0}
  const anchorRef = useRef({ x: 0, y: 0 });                // 上次 1:1 计算用的锚点(src 换图后重锚用)
  const natRef = useRef({ w: 0, h: 0 });                   // 上次 1:1 计算时的自然尺寸(同上)
  const lastResetPathRef = useRef<string | null>(null);    // 只有真换图才重置缩放/旋转/偏移
  const dragRef = useRef<{ startX: number; startY: number; ox: number; oy: number; dragging: boolean }>({ startX: 0, startY: 0, ox: 0, oy: 0, dragging: false });
  const loadedSrcRef = useRef<Record<string, string>>({});
  const currentPathRef = useRef<string | null>(null);
  // 用户"正在看的那张"的路径 — 只由打开/切换写入(加载流程不写),
  // 列表被星级筛选收缩时据此重锚, 避免悄悄换成另一张
  const anchorPathRef = useRef<string | null>(photos[index]?.path ?? null);
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
    anchorPathRef.current = target.path;
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
  const handleClose = useCallback(() => {
    if (leaving) return;
    setLeaving(true);
    window.setTimeout(onClose, 250);
  }, [leaving, onClose]);

  // 评分后自动前进: 最后一张直接关闭查看器回网格(不回卷到第一张, 否则 culling 永不结束)。
  //
  // ⚠️ 两条不许动的约束(重构前先读这里):
  // 1. 不要在这里、也不要在 navigateTo 里写 lastSwitchRef。该 ref 由下面"渐进加载"effect 内
  //    唯一写入, 语义是"距上次真正换图多久", 用于 <500ms 时把预览请求 debounce 120ms。
  //    若在评分路径上再写一次, 自动前进会把时间戳刷成"刚刚" → rapid 恒真,
  //    慢速逐张评分也会白等 120ms。
  // 2. 不要 await 图片加载完成再前进: 等加载会把"评分"变成有延迟的动作, 只刷新键位流即可。
  const autoNext = useCallback(() => {
    if (!autoAdvance) return;
    if (cur + 1 < photos.length) navigateTo(cur + 1);
    else handleClose();
  }, [autoAdvance, cur, photos.length, navigateTo, handleClose]);

  // ── 1:1 实际像素查看 (Z) ──────────────────────────────────
  // 锚点公式: 屏幕坐标 = scale × 布局坐标 + offset
  // (img 在图片区容器内 justify/items-center 居中, 故"容器中心" = "图片布局盒中心",
  //  hoverRef 与布局坐标同一个系)。
  // 要求鼠标下的图面点在缩放前后停在同一个屏幕点 (cx, cy):
  //   之前 cx = scale·px + offset.x  →  px = (cx − offset.x) / scale
  //   之后 cx = k·px + offset.x'      →  offset.x' = cx − k·(cx − offset.x)/scale
  // 文档给的 offset.x + (cx − offset.x)(1 − k) 是本式在 scale === 1 时的特例;
  // 用户可能已滚轮缩放到别的倍数再按 Z, 所以这里用通式(scale===1 时与文档完全一致)。
  const applyPixelView = useCallback((anchor: { x: number; y: number }) => {
    const el = imgRef.current;
    if (!el) return false;                            // 高清图还没挂载
    const fit = fitScaleOf(el);
    if (!fit) return false;                           // naturalWidth 还没就绪
    const k = Math.min(8, Math.max(0.2, 1 / fit));    // 夹到既有 0.2–8 缩放上下限
    anchorRef.current = anchor;
    natRef.current = { w: el.naturalWidth, h: el.naturalHeight };
    setOffset({
      x: anchor.x - (k * (anchor.x - offset.x)) / scale,
      y: anchor.y - (k * (anchor.y - offset.y)) / scale,
    });
    setScale(k);
    setPixelView(true);
    return true;
  }, [offset, scale]);

  // 用独立标记判定是否处于 1:1, 而不是 scale > 1: 小图(小于窗口)的 1:1 比例 < 1,
  // 用 scale > 1 判定会陷入"再按 Z 又进 1:1"的死循环。
  const togglePixelView = useCallback(() => {
    if (pixelView) { setScale(1); setOffset({ x: 0, y: 0 }); setPixelView(false); return; }
    // 旋转 90/270 时图面点与鼠标位置不再满足"平移 + 等比缩放", 退回以视图中心为锚
    applyPixelView(rotation % 360 === 0 ? hoverRef.current : { x: 0, y: 0 });
  }, [pixelView, rotation, applyPixelView]);

  // 内嵌预览图换成全解码图后 naturalWidth 变了(例如 1616 → 6000), 若仍在 1:1 需按新尺寸
  // 重算, 并把锚点上的图面内容钉回原位: offset' = anchor − (naturalNew / naturalOld)·(anchor − offset)
  // (由 k·布局盒 = natural 恒等式推出, 与容器尺寸无关)
  const reapplyPixelView = useCallback(() => {
    const el = imgRef.current;
    if (!el || !el.naturalWidth || !natRef.current.w) return;
    const fit = fitScaleOf(el);
    if (!fit) return;
    const a = anchorRef.current;
    const rx = el.naturalWidth / natRef.current.w;
    const ry = el.naturalHeight / natRef.current.h;
    setOffset({ x: a.x - rx * (a.x - offset.x), y: a.y - ry * (a.y - offset.y) });
    natRef.current = { w: el.naturalWidth, h: el.naturalHeight };
    setScale(Math.min(8, Math.max(0.2, 1 / fit)));
  }, [offset]);

  // 渐进加载: 先内嵌JPEG秒开, 后台全解码后无感替换
  useEffect(() => {
    if (!photo) return;
    const path = photo.path;
    currentPathRef.current = path;
    const cached = loadedSrcRef.current[path];
    // 只在"真的换图"时重置缩放/旋转/偏移: 评分会让 sortedPhotos 换新数组引用, 本 effect
    // 随之重跑 —— 不守卫的话"在 1:1 下打分"(自动前进关闭时)会被弹回适应窗口。
    if (lastResetPathRef.current !== path) {
      lastResetPathRef.current = path;
      setScale(1);
      setOffset({ x: 0, y: 0 });
      setRotation(0);
      setPixelView(false);
    }
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
    // lastSwitchRef 只由本处写入: 任何"切换路径"的代码(navigateTo/autoNext)都不得写它,
    // 否则 rapid 判定被刷新成恒真, 慢速评分也会白等 120ms(见 autoNext 上方注释)。
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

  // 列表被星级筛选/评分变更收缩后重锚: 始终显示"原来看的那张"。
  // 若它已不在列表中(典型场景: 开着 ≥N 星筛选, 在查看器里给它打了更低的星),
  // 就关闭查看器 —— 而不是让 cur 指向另一张照片、悄悄换了内容。
  useEffect(() => {
    if (photos.length === 0) {
      onClose();
      return;
    }
    const wanted = anchorPathRef.current;
    if (wanted === null) return;
    const idx = photos.findIndex((p) => p.path === wanted);
    if (idx === -1) {
      onClose();
      return;
    }
    if (idx !== cur) setCur(idx);
  }, [photos, cur, onClose]);

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
      // 长按(auto-repeat)不重复触发一次性动作(评分): 自动前进开启时长按 3
      // 会把后续几十张全打成 3 星。←/→ 不在名单内, 保留长按连翻。
      if (e.repeat && NON_REPEAT_KEYS.has(e.key.toLowerCase())) return;
      // Phase 2: 查看器内的 Ctrl+Z / Ctrl+Shift+Z。
      // App 的 window 监听器在查看器打开时**整体早退**(App.tsx 的 viewerIndex 判断),
      // 所以这里是查看器内撤销的唯一入口, 不会双触发。
      // `z` 在 NON_REPEAT_KEYS 里(防长按 Z 连切 1:1), 故长按 Ctrl+Z 不会连撤 ——
      // 这是有意的: 否则手抖按住就是"一口气退掉几十步"且不可预期, 本 Phase 不做节流。
      const mod = (e.ctrlKey || e.metaKey) && !e.altKey;
      if (mod && e.key.toLowerCase() === "z") {
        if (!onUndo || !photo) return;             // 未接撤销能力/图未就绪: 不抢浏览器原生撤销
        // 撤销要作用在"被改的那张"上: 自动前进可能已经把画面带到下一张了,
        // 先把画面调回补丁里的那张, 用户才看得见撤了什么(见 docs 会话 ②/§3.3)
        if (undoTargetPath && undoTargetPath !== photo.path) {
          const i = photos.findIndex((p) => p.path === undoTargetPath);
          if (i >= 0) navigateTo(i);
        }
        const done = e.shiftKey ? (onRedo ? onRedo() : null) : onUndo();
        // 不调 autoNext(): 撤销是"我改主意", 一撤销就前进会让被撤的那张立刻滑走。
        // 也不碰 scale/offset/rotation/pixelView —— 撤销不改路径就不该动画面状态。
        if (done) {
          e.preventDefault();
          onUndoToast?.(done, e.shiftKey ? "redo" : "undo");
        }
        return;
      }
      // 列表收缩到当前索引之外时 photo 会是 undefined — 必须在访问 photo.path 之前挡住
      if (!photo) return;
      // ── Phase 4 · 颜色标签(Ctrl/Alt + 1-5 打标, +0 清除) ──
      // ⚠️ 必须排在这一段**最前面**: 下面的分支里有 plain `0`(重置视图)与
      // `e.key >= "1" && e.key <= "5"`(打星), 排在它们后面的话 Ctrl+2 会先被星级吃掉。
      // 判定统一走 isLabelChord(与 App 的网格共用一个语义)。
      // 打标**不**调 autoNext(): 标签是二次分拣, 一前进就看不见刚打的标。
      const labelKey = e.key.toLowerCase();
      if (isLabelChord(e, labelModifier) && labelKey >= "0" && labelKey <= "5") {
        if (labelKey === "0") onLabel(photo.path, null);
        else onLabel(photo.path, LABEL_ORDER[Number(labelKey) - 1]);
        e.preventDefault();
        return;
      }
      if (e.key === "Escape") { handleClose(); }
      else if (e.key === "ArrowLeft") { navigateTo((cur - 1 + photos.length) % photos.length); }
      else if (e.key === "ArrowRight") { navigateTo((cur + 1) % photos.length); }
      else if (e.key === "=" || e.key === "+") { setPixelView(false); setScale((s) => Math.min(8, s * 1.25)); }
      else if (e.key === "-") { setPixelView(false); setScale((s) => Math.max(0.2, s / 1.25)); }
      else if (e.key === "0") { setPixelView(false); setScale(1); setOffset({ x: 0, y: 0 }); setRotation(0); }
      else if (e.key.toLowerCase() === "r") { setRotation((r) => (e.shiftKey ? (r + 270) % 360 : (r + 90) % 360)); }
      // Z: 适应窗口 <-> 1:1 实际像素。不抢 Ctrl/Cmd/Alt, 免得与将来的 Ctrl+Z 撤销冲突
      else if (e.key.toLowerCase() === "z" && !e.ctrlKey && !e.metaKey && !e.altKey) { togglePixelView(); }
      else if (e.key.toLowerCase() === "j") { onRate(photo.path, 3); autoNext(); }
      else if (e.key.toLowerCase() === "x") { onRate(photo.path, 0); autoNext(); }
      else if (e.key >= "1" && e.key <= "5") { onRate(photo.path, Number(e.key)); autoNext(); }
      else if (e.key === " ") { e.preventDefault(); onToggleSelect(photo.path); } // 空格: 切换勾选
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [photo, cur, photos.length, navigateTo, handleClose, onRate, onToggleSelect, autoNext, togglePixelView, onUndo, onRedo, undoTargetPath, onUndoToast, onLabel, labelModifier]);

  // Wheel zoom — 缩到<=1时居中(重置offset)。滚轮改过缩放就不再是 1:1(否则 Z 会陷入死循环)
  const onWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setPixelView(false);
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
          {/* Phase 4 · 颜色标签 — 点亮的那个再点一次 = 清除(与星条按钮同款 toggle;
              键盘走 Ctrl/Alt + 1-5 / 0, 键盘只赋值不 toggle) */}
          {LABEL_ORDER.map((l) => (
            <Tip key={l} label={t(`label.${l}`)} className="flex items-center">
            <button
              data-tauri-drag-region={false}
              onClick={() => onLabel(photo.path, labels[photo.path] === l ? null : l)}
              className={`w-4 h-4 rounded-full border transition-opacity ${
                labels[photo.path] === l
                  ? "ring-2 ring-white/80 border-white/80 opacity-100"
                  : "border-white/25 opacity-60 hover:opacity-100"
              } ${LABEL_BG[l]}`}
            />
            </Tip>
          ))}
          {/* 星级 */}
          {[1, 2, 3, 4, 5].map((s) => (
            <button key={s} data-tauri-drag-region={false} onClick={() => { onRate(photo.path, rating === s ? 0 : s); autoNext(); }}
              className={`text-sm px-0.5 ${rating >= s ? "text-amber-400" : "text-zinc-600 hover:text-zinc-400"}`}
            >★</button>
          ))}
          <button data-tauri-drag-region={false} onClick={handleClose} className="ml-3 w-8 h-8 flex items-center justify-center rounded hover:bg-zinc-800 text-zinc-400">
            <Close theme="filled" size="16" strokeWidth={3} />
          </button>
        </div>
      </div>

      {/* 图片区 — 缩略图铺底秒显, 高清图加载后淡入替换 */}
      <div
        ref={areaRef}
        className="flex-1 relative overflow-hidden flex items-center justify-center select-none"
        onMouseMove={(e) => {
          // 鼠标相对"图片区中心"的坐标: 容器中心 = 图片布局盒中心(img 被 justify/items-center 居中),
          // 与锚点公式用的布局坐标同一个系
          const r = areaRef.current?.getBoundingClientRect();
          if (!r) return;
          hoverRef.current = { x: e.clientX - (r.left + r.width / 2), y: e.clientY - (r.top + r.height / 2) };
        }}
        onMouseLeave={() => { hoverRef.current = { x: 0, y: 0 }; }}   // 鼠标移出图区后按 Z → 以视图中心为锚
      >
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
            ref={imgRef}
            src={src}
            alt={photo.fileName}
            draggable={false}
            decoding="async"
            className="max-w-full max-h-full object-contain"
            // 预览图换成全解码图后 naturalWidth 变了: 仍在 1:1 就按新尺寸重算并把锚点钉住。
            // 只在"同一张图"上重锚: 换图时 lastResetPathRef 还没追上 currentPathRef, 此时
            // 该由加载 effect 去重置缩放, 这里重锚会与它竞态(理论最坏 1 帧)
            onLoad={() => { if (pixelView && lastResetPathRef.current === currentPathRef.current) reapplyPixelView(); }}
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
        <span>{t("viewer.actual")}</span>
        <span>{t("viewer.pan")}</span>
        <span>{t("viewer.reset")}</span>
        <span>{t("viewer.rotate")}</span>
        <span>{t("viewer.rate")}</span>
      </div>
    </div>
  );
}
