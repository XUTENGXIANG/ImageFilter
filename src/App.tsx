import { useEffect, useState, useMemo, useRef, useCallback, memo } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { PixelMenu, SEPARATOR, type MenuItem } from "./contextmenu";
import { FloatingPanel } from "./panel";
import { PhotoViewer } from "./viewer";
import { useScanner } from "./useScanner";
import { TitleBar } from "./components/title-bar";
import { ExifPanel } from "./components/exif-panel";
import { WelcomeGuide } from "./components/welcome-guide";
import { ScrollFadeZone } from "./components/scroll-fade-zone";
import { FolderTreeItem } from "./components/folder-tree-item";
import { PhotoCard } from "./components/photo-card";
import { PhotoToolbar } from "./components/photo-toolbar";
import { ImportBar } from "./components/import-bar";
import { LrcProgressDialog } from "./components/lrc-progress-dialog";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from "./components/ui/dialog";
// ═══════════════════════════════════════════════════════════════════
// 🎨 图标约定: 本项目所有图标一律使用 bytedance/IconPark (@icon-park/react)
//    参考: https://github.com/bytedance/IconPark
//    用法: import { 图标名 } from "@icon-park/react"
//    支持 theme="outline|filled|two-tone|multi-color" size fill 等
//    请不要混用 emoji/文字符号 等其他图标方案
// ═══════════════════════════════════════════════════════════════════
import { Disk, DiskOne } from "@icon-park/react";
import type { ScannedPhoto, AnalysisResult, FlagFilter } from "./types";
import type { Patch } from "./undo";
import { LABEL_ORDER, isLabelChord, type Label } from "./labels";
import { xmpErrKey, type XmpNotice } from "./xmp";
import { lrcErrKey, type LrcNotice } from "./lightroom";

/**
 * 撤销/重做的 toast 文案。
 * 在 App 里生成而不是在 useScanner 里: hook 不该依赖 i18n 文案,
 * 且文案必须随语言切换即时变化(不能在入栈时预格式化)。
 * ⚠️ 插值一律**单括号** {n}: i18n/index.ts 把 prefix/suffix 改成了 { 与 },
 * 写成 i18next 官方文档那种双括号会原样显示(会话 ② 踩过, docs 前置约束 5)。
 */
function patchToast(t: TFunction, p: Patch, kind: "undo" | "redo"): string {
  const verb = kind === "undo" ? t("toast.undo") : t("toast.undoRedo");
  let what: string;
  if (p.kind === "rating") {
    what = t("toast.ratingChange", { prev: p.prev, next: p.next });
  } else if (p.kind === "label") {
    // 标签值要翻成"红/黄/…"/"无标签", 渲染时才拼文案(语言可切)
    const name = (l: Label | null) => (l ? t(`label.${l}`) : t("label.none"));
    what = t("toast.labelChange", { prev: name(p.prev), next: name(p.next) });
  } else {
    what = t("toast.selectionChange", { n: p.next.length });
  }
  return `${verb}：${what}`;
}

/**
 * Phase 5 · XMP 边车的一次性提示文案(与 patchToast 同理: 在 App 里生成, 不放进 hook)。
 * 错误码来自 Rust 的闭集, 这里再白名单校验一次(xmpErrKey), 未知码落到 unknown。
 */
function xmpNoticeText(t: TFunction, n: XmpNotice): string {
  const reason = t(`xmp.err.${xmpErrKey(n.code)}`);
  if (n.kind === "downgraded") return t("xmp.toastDowngraded", { reason });
  if (n.kind === "overflow") return t("xmp.toastQueueOverflow", { n: n.n });
  return t("xmp.toastFailed", { reason });
}

/**
 * Phase 7 · Lightroom 发送失败的一次性提示文案(同 xmpNoticeText 的纪律:
 * 在 App 里用 i18n 拼, hook 只给闭集错误码)。
 */
function lrcNoticeText(t: TFunction, n: LrcNotice): string {
  const reason = t(`lrc.err.${lrcErrKey(n.code)}`, { n: n.n });
  return t("lrc.toastFailed", { reason });
}

/**
 * ③ 分析结果筛选: **没分析过的照片一律不算命中**(docs 4.2) ——
 * 所以"只看模糊"在还没分析时会得到空网格, 必须靠网格顶部的"还有 N 张未分析"提示兜底。
 * duplicate 用 `duplicateGroup !== undefined`: **包含"最佳"那张**
 * (它卡片上显示"最佳"徽标而不是"重复", 筛选口径与徽标口径不同, 有意如此, docs 4.5)。
 */
function matchesFlag(a: AnalysisResult | undefined, flag: FlagFilter): boolean {
  if (flag === "all") return true;
  if (!a) return false;
  if (flag === "blurry") return !!a.isBlurry;
  if (flag === "over") return !!a.isOverexposed;
  if (flag === "under") return !!a.isUnderexposed;
  if (flag === "duplicate") return a.duplicateGroup !== undefined;
  return !!a.isBestInGroup; // best
}

/**
 * 照片网格项 — memoized 组件:
 * 所有事件处理器收进 useCallback, 配合 App 传入的稳定回调,
 * 使缩略图/评分/勾选变化时只重渲染受影响的卡片, 而非整表重渲染
 *
 * ⚠️ Phase 4(4.4-A): 这里**不再**给每张卡片包一个 PixelMenu。
 * 右键菜单项依赖 selectedPaths(如"导入选中 N 张"), 每张卡片各拿一份
 * props 就等于"任何一次勾选都让所有卡片换 props" → 双层 memo 全废。
 * 现在整块网格共用一个菜单, 右键时由 onCtx(photo) 记下目标(见 App 里那层 PixelMenu)。
 */
const PhotoGridItem = memo(function PhotoGridItem({
  photo, thumbnail, isSelected, isChecked, analysis, rating, label,
  onToggle, onRate, onOpenViewer, onSelect, onCtx, loadThumb,
}: {
  photo: ScannedPhoto;
  thumbnail?: string;
  isSelected: boolean;
  isChecked: boolean;
  analysis?: AnalysisResult;
  rating?: number;
  label?: Label;
  onToggle: (path: string, e: { ctrlKey: boolean; shiftKey: boolean }) => void;
  onRate: (path: string, stars: number) => void;
  onOpenViewer: (photo: ScannedPhoto, rect: { x: number; y: number; w: number; h: number }) => void;
  onSelect: (photo: ScannedPhoto) => void;
  onCtx: (photo: ScannedPhoto) => void;
  loadThumb: (path: string) => void;
}) {
  const thumbLoaded = useRef(false);

  const handleClick = useCallback((e: React.MouseEvent) => {
    onToggle(photo.path, { ctrlKey: e.ctrlKey, shiftKey: e.shiftKey });
    onSelect(photo);
    if (!thumbLoaded.current) {
      thumbLoaded.current = true;
      loadThumb(photo.path);
    }
  }, [photo, onToggle, onSelect, loadThumb]);

  const handleDoubleClick = useCallback((e: React.MouseEvent) => {
    const r = e.currentTarget.getBoundingClientRect();
    onOpenViewer(photo, { x: r.x, y: r.y, w: r.width, h: r.height });
  }, [photo, onOpenViewer]);

  const handleRate = useCallback((s: number) => onRate(photo.path, s), [photo, onRate]);
  const handleCtx = useCallback(() => onCtx(photo), [photo, onCtx]);

  return (
    <PhotoCard
      photo={photo}
      thumbnail={thumbnail}
      isSelected={isSelected}
      isChecked={isChecked}
      onToggle={handleClick}
      analysis={analysis}
      rating={rating}
      label={label}
      onRate={handleRate}
      onDoubleClick={handleDoubleClick}
      onContextMenu={handleCtx}
      onClick={handleClick}
    />
  );
});

function App() {
  const { t } = useTranslation();
  const {
    drives,
    selectedDrive,
    folderTree,
    activeFolder,
    photos,
    selectedPhoto,
    thumbnails,
    browsing,
    loadingFolder,
    counting,
    detectDrives,
    browseDrive,
    loadFolder,
    loadThumbnail,
    loadExif,
    setSelectedPhoto,
    analyzing,
    analysis,
    runAnalysis,
    stopAnalysis,
    ratings,
    setRating,
    sortBy,
    setSortBy,
    starFilter,
    setStarFilter,
    labels,
    setLabel,
    labelFilter,
    setLabelFilter,
    flagFilter,
    setFlagFilter,
    sortDir,
    setSortDir,
    labelModifier,
    setLabelModifier,
    importing,
    importProgress,
    importDone,
    importError,
    importResult,
    // Phase 6 / 6.1: 导入历史(列表 + 总数 + 加载入口), 只透传给 ImportBar 里的历史对话框
    importHistory,
    // Phase 6 / 6.2: 命名方案(下拉 + 另存为), 只透传给高级选项面板
    importScheme,
    customFolder,
    setCustomFolder,
    useCustomFolder,
    setUseCustomFolder,
    destDir,
    selectedPaths,
    handlePhotoClick,
    selectAll,
    clearSelection,
    folderRule,
    fileRule,
    setFolderRule,
    setFileRule,
    pickDestDir,
    startImport,
    preloadFull,
    togglePreloadFull,
    autoAdvance,
    toggleAutoAdvance,
    undo,
    redo,
    lastUndoPath,
    // Phase 5: XMP 边车
    xmpMode,
    setXmpMode,
    xmpAskPending,
    resolveXmpAsk,
    xmpStatus,
    xmpNotice,
    // Phase 7: Lightroom 衔接
    lrcProbe,
    probeLightroom,
    lrcMode,
    setLrcMode,
    lrcSending,
    lrcSent,
    lrcNotice,
    importToLightroom,
    lrcAlreadyRunning,
    setLrcAlreadyRunning,
    forceCloseLightroomAndRetry,
    lrcPhase,
  } = useScanner();

  // 图片查看器: viewerIndex=null 关闭, 数字=打开第N张
  // 注意: 必须声明在下面的键盘快捷键 effect 之前 —— 该 effect 的依赖数组在渲染时
  // 会被急切求值, 声明放在后面会触发 TDZ(ReferenceError: Cannot access before initialization)
  const [viewerIndex, setViewerIndex] = useState<number | null>(null);
  const [viewerOrigin, setViewerOrigin] = useState<{ x: number; y: number; w: number; h: number } | undefined>(undefined);

  // 切换文件夹时关闭查看器: photos 会被整体替换, 查看器不能继续持有旧列表的索引
  useEffect(() => {
    setViewerIndex(null);
  }, [activeFolder]);

  // 弹出提示浮窗
  //
  // Phase 7 · 「导入到 LrC」的模态进度框是否显示。
  // 链一开始就自动弹出(chains 进入 importing/launching); 用户点"在后台继续"或按 Esc
  // 只把这一层的 open 关掉 —— 下一次 phase 变化时 effect 会按当前 phase 重新算,
  // 所以**手动关闭不会被这个 effect 又弹回来**(它只依赖 lrcPhase, 关闭不改变 lrcPhase)。
  const [lrcProgressOpen, setLrcProgressOpen] = useState(false);
  useEffect(() => {
    setLrcProgressOpen(lrcPhase !== "idle");
  }, [lrcPhase]);
  // (位置在 Ctrl+Z effect 之前: 那个 effect 的依赖数组会被急切求值, showToast 若是
  //  const 声明在后面就触发 TDZ — 与 viewerIndex 是同一条纪律)
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<number | undefined>(undefined);
  // ms 默认 1200(既有手感不变); Phase 5 的边车失败提示传 4000 ——
  // "写入 .xmp 失败：存储卡处于写保护（只读）" 这类长文案 1.2s 读不完(会话 ② 已记录)。
  const showToast = (msg: string, ms = 1200) => {
    setToast(msg);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), ms);
  };

  // Phase 5: 边车的一次性提示(档位降级/写入失败/队列溢出)。
  // hook 只给"发生了什么"(notice.seq 保证每次都重新触发), 文案在这里用 i18n 拼。
  useEffect(() => {
    if (!xmpNotice) return;
    showToast(xmpNoticeText(t, xmpNotice), 4000);
    // 只依赖 notice 本身: seq 变了就是一次新提示
  }, [xmpNotice]);

  // Phase 7: Lightroom 发送失败提示(成功提示走 ImportBar 里的常驻行, 不用 toast ——
  // 它需要同时说明"发的是哪个文件夹"和"还有 N 个文件夹没发", 一句话 toast 装不下)。
  useEffect(() => {
    if (!lrcNotice) return;
    showToast(lrcNoticeText(t, lrcNotice), 4000);
  }, [lrcNotice]);

  // Disable browser default context menu
  useEffect(() => {
    const handler = (e: MouseEvent) => e.preventDefault();
    window.addEventListener("contextmenu", handler);
    return () => window.removeEventListener("contextmenu", handler);
  }, []);

  // 屏蔽 Ctrl+A 全选文本（输入框内除外）+ Phase 2 的 Ctrl+Z / Ctrl+Shift+Z 撤销重做
  // 两者共用同一份"输入框豁免"判断, 合并成一个监听器, 少一个 window 事件回调。
  //
  // ⚠️ 依赖数组必须含 viewerIndex(与下面快捷键 effect 同一个坑, 交接 §5-7):
  // 否则查看器打开后闭包里的值仍是 null, 会出现 App 与 viewer 双重撤销 ——
  // 一次 Ctrl+Z 撤两步。查看器内的撤销由 viewer 自己的 handler 处理。
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      const inField = tag === "INPUT" || tag === "TEXTAREA"; // 输入框内交给浏览器原生撤销
      const mod = (e.ctrlKey || e.metaKey) && !e.altKey;
      const key = e.key.toLowerCase();
      if (mod && key === "a") {
        if (!inField) e.preventDefault();
        return;
      }
      if (mod && key === "z") {
        if (inField || viewerIndex !== null) return;
        const done = e.shiftKey ? redo() : undo();
        // 只有真的撤销/重做了才 preventDefault: 空栈时把 Ctrl+Z 让给浏览器,
        // 避免"明明什么都没撤, 却把原生撤销也吃掉了"
        if (done) {
          e.preventDefault();
          showToast(patchToast(t, done, e.shiftKey ? "redo" : "undo"));
        }
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [viewerIndex, undo, redo, t]);

  useEffect(() => {
    detectDrives();
    const timer = setInterval(detectDrives, 5000);
    return () => clearInterval(timer);
  }, [detectDrives]);

  // Keyboard shortcuts: J=rate3, X=rate0, 1-5=star (查看器打开时不处理, 交给viewer)
  // Phase 4 追加: 颜色标签的组合键(Ctrl/Alt + 1-5 / 0, 修饰键由设置决定)
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (viewerIndex !== null) return;
      if (!selectedPhoto || e.target instanceof HTMLInputElement) return;
      const key = e.key.toLowerCase();
      // 颜色标签。判定统一走 isLabelChord(App 与 viewer 共用一份语义, 不许各写一遍)。
      // ⚠️ 必须排在下面的 plain 1-5 之前: 否则 Ctrl+2 会先被星级分支吃掉、变成打星。
      // 打标**不**触发任何前进(与评分不同, 那在 viewer 里), 也不动勾选。
      if (isLabelChord(e, labelModifier) && key >= "0" && key <= "5") {
        // 0 = 清除; 1-5 按 LABEL_ORDER 映射到五种颜色(与查看器完全同一套)
        setLabel(selectedPhoto.path, key === "0" ? null : LABEL_ORDER[Number(key) - 1]);
        e.preventDefault();
        return;
      }
      if (key === "j") setRating(selectedPhoto.path, 3);
      else if (key === "x") setRating(selectedPhoto.path, 0);
      else if (key >= "1" && key <= "5") setRating(selectedPhoto.path, Number(key));
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
    // viewerIndex 必须进依赖: 否则查看器打开后闭包里的值仍是旧值(null),
    // 本处理器会与 viewer 自己的快捷键同时触发, 把"选中的那张"也改掉星级
  }, [selectedPhoto, setRating, viewerIndex, setLabel, labelModifier]);

  // Sort + filter photos —— 三个维度**叠加(交集)**, 顺序只按"短路成本"排:
  //   ① 星级(一次数值比较, 最便宜) → ② 标签(Set 命中) → ③ 分析(取对象+多字段, 最贵)
  //   → ④ 排序(必须在所有筛选之后, 否则白排被丢掉的项) → ⑤ 方向
  // 顺序不影响结果(都是 AND), 只影响性能; 方向放在最后是为了不做"3 键 × 2 方向"六个比较器。
  const sortedPhotos = useMemo(() => {
    let list = [...photos];
    // ① 星级: 0=全部, 1-5=≥N星(保持既有语义)
    if (starFilter > 0) {
      list = list.filter((p) => (ratings[p.path] || 0) >= starFilter);
    }
    // ② 标签: 空数组=不过滤; 多选=命中任一
    if (labelFilter.length > 0) {
      const want = new Set(labelFilter);
      list = list.filter((p) => {
        const l = labels[p.path];
        return l !== undefined && want.has(l);
      });
    }
    // ③ 分析结果: all=不过滤; 其余一律"有分析结果且命中"(matchesFlag 里有注释)
    if (flagFilter !== "all") {
      list = list.filter((p) => matchesFlag(analysis[p.path], flagFilter));
    }
    // ④ 排序: asc = "今天的观感"(name/type A→Z, date 新→旧), desc = 反转比较器。
    // 刻意不把 asc 理解成字面"旧→新": 否则第一次切到日期排序就会觉得"排序反了"(docs 4.5)。
    const cmp = (a: ScannedPhoto, b: ScannedPhoto): number => {
      if (sortBy === "name") return a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase());
      if (sortBy === "type") {
        const ea = a.fileName.split(".").pop()?.toLowerCase() || "";
        const eb = b.fileName.split(".").pop()?.toLowerCase() || "";
        return ea.localeCompare(eb) || a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase());
      }
      return b.modifiedAt - a.modifiedAt;
    };
    list.sort(sortDir === "desc" ? (a, b) => -cmp(a, b) : cmp);
    return list;
  }, [photos, sortBy, sortDir, starFilter, labelFilter, flagFilter, ratings, labels, analysis]);

  // ── Phase 4 · 分析范围(选区优先) ─────────────────────────────────────
  // scope 一份定义、三处共用(工具栏按钮文案 / "未分析"提示的 N / 提示按钮要分析的对象),
  // 数字才会永远对得上。与 photos 取交集: selectedPaths 可能还留着上一个设备的残留路径
  // (browseDrive 不清选择集是既有行为, 本 Phase 不改它, docs 4.2)。
  const selectionScope = useMemo(() => {
    const inFolder = new Set(photos.map((p) => p.path));
    return [...selectedPaths].filter((p) => inFolder.has(p));
  }, [photos, selectedPaths]);
  const analysisScope = useMemo(
    () => (selectionScope.length > 0 ? selectionScope : photos.map((p) => p.path)),
    [selectionScope, photos],
  );

  // "未分析"提示: 四个条件同时成立才显示(docs 4.2)
  const pendingAnalysis = useMemo(
    () => analysisScope.filter((p) => !analysis[p]),
    [analysisScope, analysis],
  );
  const showPendingHint =
    flagFilter !== "all" && photos.length > 0 && !analyzing && pendingAnalysis.length > 0;

  // 三维筛选后 0 命中的兜底: 只有"还没分析"的提示盖不住"全分析完但 0 命中"这一支。
  // 与"未分析"提示互斥(!showPendingHint): 后者已经解释了为什么是空的,
  // 两条横幅一起弹只是噪音。
  const anyFilterActive = starFilter > 0 || labelFilter.length > 0 || flagFilter !== "all";
  const showNoMatch =
    photos.length > 0 && sortedPhotos.length === 0 && anyFilterActive && !showPendingHint;
  // 清筛选: 三维复位, **不动排序方式与方向**(用户没要求把排序也重置)
  const clearFilters = useCallback(() => {
    setStarFilter(0);
    setLabelFilter([]);
    setFlagFilter("all");
  }, [setStarFilter, setLabelFilter, setFlagFilter]);

  // Context menu — tracks which photo was right-clicked for menu items
  const [ctxTarget, setCtxTarget] = useState<ScannedPhoto | null>(null);

  const photoMenuItems = useMemo((): MenuItem[] => {
    if (!ctxTarget) return [];
    const sp = ctxTarget;
    const isSel = selectedPaths.has(sp.path);
    return [
      { label: isSel && selectedPaths.size > 1 ? t("menu.importCount", { n: selectedPaths.size }) : t("menu.importSelected"), action: () => startImport(isSel ? [...selectedPaths] : [sp.path]) },
      { label: t("menu.rating"), children: [
        { label: "★★★★★", action: () => setRating(sp.path, 5) },
        { label: "★★★★", action: () => setRating(sp.path, 4) },
        { label: "★★★", action: () => setRating(sp.path, 3) },
        { label: "★★", action: () => setRating(sp.path, 2) },
        { label: "★", action: () => setRating(sp.path, 1) },
        { label: t("menu.clearRating"), action: () => setRating(sp.path, 0) },
      ]},
      // Phase 4: 颜色标签的鼠标入口(键盘是 Ctrl/Alt + 1-5)。
      // 菜单项不显示"当前是哪个颜色" —— MenuItem 只有字符串标签, 加对勾就得用文字符号,
      // 与"图标一律 IconPark"的约定冲突; 当前色由卡片上的色点表达。
      { label: t("label.title"), children: [
        ...LABEL_ORDER.map((l): MenuItem => ({ label: t(`label.${l}`), action: () => setLabel(sp.path, l) })),
        { label: t("label.clear"), action: () => setLabel(sp.path, null) },
      ]},
      { label: t("menu.viewExif"), action: () => { setSelectedPhoto(sp); loadExif(sp); } },
      { label: t("menu.openLocation"), action: () => { const dir = sp.path.replace(/\\[^\\]+$/, ""); invoke("open_folder", { path: dir }); } },
      SEPARATOR,
      { label: t("menu.selectAll"), action: selectAll },
      { label: t("menu.deselect"), action: clearSelection },
    ];
  }, [ctxTarget, selectedPaths, startImport, setRating, setLabel, loadExif, selectAll, clearSelection, t]);

  const emptyMenuItems = useMemo((): MenuItem[] => [
    { label: t("menu.refresh"), action: () => selectedDrive && browseDrive(selectedDrive!) },
    { label: t("menu.importAll"), action: () => startImport(photos.map((p) => p.path)) },
    { label: t("menu.selectAll"), action: selectAll },
    { label: t("menu.ai"), action: () => runAnalysis(photos.map((p) => p.path)) },
  ], [photos, selectedDrive, startImport, selectAll, browseDrive, runAnalysis, t]);

  // 照片网格 — 稳定回调(配合 PhotoGridItem memo, 避免整表重渲染)
  const openViewer = useCallback((photo: ScannedPhoto, rect: { x: number; y: number; w: number; h: number }) => {
    if (photo.isVideo) return; // 视频暂不支持预览
    setViewerOrigin(rect);
    setViewerIndex(sortedPhotos.indexOf(photo));
  }, [sortedPhotos]);

  const selectPhoto = useCallback((photo: ScannedPhoto) => {
    setSelectedPhoto(photo);
    loadExif(photo);
  }, [loadExif]);

  const ctxPhoto = useCallback((photo: ScannedPhoto) => setCtxTarget(photo), []);

  const toggleSelect = useCallback(
    (path: string) => handlePhotoClick(path, { ctrlKey: false, shiftKey: false }),
    [handlePhotoClick]
  );

  // 透明毛玻璃背景: 默认开启, 深色/浅色随主题切换
  const [transparentBg, setTransparentBg] = useState<boolean>(() => localStorage.getItem("imagefilter-glass") !== "0");

  useEffect(() => {
    localStorage.setItem("imagefilter-glass", transparentBg ? "1" : "0");
  }, [transparentBg]);

  // 浮窗后面整块背景毛玻璃的透明度: 默认 0% 完全透明
  const [backgroundOpacity, setBackgroundOpacity] = useState<number>(() => {
    const v = Number(localStorage.getItem("imagefilter-background-opacity"));
    return Number.isFinite(v) ? Math.min(100, Math.max(0, v)) : 0;
  });

  useEffect(() => {
    document.documentElement.style.setProperty("--background-opacity", `${backgroundOpacity}%`);
    localStorage.setItem("imagefilter-background-opacity", String(backgroundOpacity));
  }, [backgroundOpacity]);

  // 导入栏/工具栏默认收起: 照片或选中数从空变非空时自动展开, 清空后自动收起
  const [toolbarOpen, setToolbarOpen] = useState(false);
  const [importBarOpen, setImportBarOpen] = useState(false);
  const hadPhotosRef = useRef(false);
  const hadSelectionRef = useRef(false);
  const selectedCount = selectedPaths.size;

  useEffect(() => {
    const hasPhotos = photos.length > 0;
    const hasSelection = selectedCount > 0;
    if ((hasPhotos && !hadPhotosRef.current) || (hasSelection && !hadSelectionRef.current)) {
      setToolbarOpen(true);
      setImportBarOpen(true);
    }
    if (!hasPhotos) {
      setToolbarOpen(false);
      setImportBarOpen(false);
    }
    hadPhotosRef.current = hasPhotos;
    hadSelectionRef.current = hasSelection;
  }, [photos.length, selectedCount]);

  // 可见区域全图预加载
  const [visiblePaths, setVisiblePaths] = useState<Set<string>>(new Set());
  const preloadVersionRef = useRef(0);

  useEffect(() => {
    setVisiblePaths(new Set());
    const observer = new IntersectionObserver((entries) => {
      setVisiblePaths((prev) => {
        let changed = false;
        const next = new Set(prev);
        for (const entry of entries) {
          const path = (entry.target as HTMLElement).dataset.photoPath;
          if (!path) continue;
          if (entry.isIntersecting) {
            if (!next.has(path)) { next.add(path); changed = true; }
          } else if (next.delete(path)) {
            changed = true;
          }
        }
        return changed ? next : prev;
      });
    }, { rootMargin: "250px", threshold: 0.01 });
    document.querySelectorAll<HTMLElement>("[data-photo-path]").forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [sortedPhotos]);

  useEffect(() => {
    const version = ++preloadVersionRef.current;
    if (!preloadFull || viewerIndex !== null) return;
    const visiblePhotoPaths = [...visiblePaths].filter((path) =>
      photos.some((p) => p.path === path && !p.isVideo)
    );
    if (visiblePhotoPaths.length === 0) return;

    const timer = window.setTimeout(() => {
      const queue = [...visiblePhotoPaths];
      let cancelled = false;
      const next = () => {
        if (cancelled || version !== preloadVersionRef.current) return;
        const path = queue.shift();
        if (!path) return;
        invoke<string>("get_full_image", { filePath: path })
          .then((diskPath) => new Promise<void>((resolve, reject) => {
            const img = new Image();
            img.decoding = "async";
            const ready = () => {
              if (typeof img.decode === "function") {
                img.decode().then(() => resolve()).catch(() => reject(new Error("decode failed")));
              } else {
                resolve();
              }
            };
            img.onload = ready;
            img.onerror = () => reject(new Error("load failed"));
            img.src = convertFileSrc(diskPath);
          }))
          .catch(() => {})
          .finally(() => next());
      };
      next();
      return () => { cancelled = true; };
    }, 300);

    return () => window.clearTimeout(timer);
  }, [preloadFull, viewerIndex, visiblePaths, photos]);

  const previewSrc = selectedPhoto
    ? (thumbnails[selectedPhoto.path] && thumbnails[selectedPhoto.path] !== "__err__"
        ? thumbnails[selectedPhoto.path]
        : convertFileSrc(selectedPhoto.path))
    : null;

  return (
    <div
      className={`flex flex-col h-screen w-screen overflow-hidden text-zinc-100 transition-colors duration-200 ${transparentBg ? "" : "bg-zinc-950"}`}
      style={transparentBg ? { backgroundColor: backgroundOpacity > 0 ? "var(--background-bg)" : "transparent" } : undefined}
    >
      {/* Custom title bar */}
      <TitleBar
        preloadFull={preloadFull}
        onTogglePreloadFull={togglePreloadFull}
        autoAdvance={autoAdvance}
        onToggleAutoAdvance={toggleAutoAdvance}
        labelModifier={labelModifier}
        onLabelModifierChange={setLabelModifier}
        xmpMode={xmpMode}
        onXmpModeChange={setXmpMode}
        xmpStatus={xmpStatus}
        lrcProbe={lrcProbe}
        lrcMode={lrcMode}
        onLrcModeChange={setLrcMode}
        onReprobeLightroom={probeLightroom}
        transparentBg={transparentBg}
        onToggleTransparentBg={() => setTransparentBg((v) => !v)}
        backgroundOpacity={backgroundOpacity}
        onBackgroundOpacityChange={setBackgroundOpacity}
      />
      <div className="flex flex-1 min-h-0">
      {/* === Left Sidebar === */}
      <FloatingPanel side="left" title={t("devices.panel")}>
        {/* 面板级右键菜单 (空白区域/刷新按钮区域) */}
        <PixelMenu items={[
          { label: t("devices.refresh"), action: () => selectedDrive && browseDrive(selectedDrive!) },
          //{ label: "刷新设备列表", action: detectDrives },
        ]}>
        <div className="px-3 pt-2 pb-1 flex items-center">
          <button onClick={() => selectedDrive && browseDrive(selectedDrive!)} className="press-solid text-[10px] leading-4 px-3 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-400 transition-colors">{t("devices.refresh")}</button>
        </div>
        {/* 设备列表 — 每个设备独立右键菜单, 可移动设备含"弹出设备" */}
        <div className="px-2.5 pb-1 space-y-0.5 max-h-36 overflow-auto no-scrollbar">
            {drives.map((d) => (
            <PixelMenu key={d.mountPoint} items={[
              { label: t("devices.open"), action: () => browseDrive(d.mountPoint) },
              d.driveType === "removable" ? { label: t("devices.eject"), action: () => {
                invoke("eject_drive", { mountPoint: d.mountPoint })
                  .then(() => {
                    showToast(t("devices.ejectOk", { dir: d.mountPoint }));
                    setTimeout(detectDrives, 800); // 弹出后刷新设备列表
                  })
                  .catch((e) => { console.error("eject failed:", e); showToast(t("devices.ejectFail")); });
              } } : { label: t("devices.fixedDisk"), disabled: true },
              { label: t("devices.refreshList"), action: () => selectedDrive && browseDrive(selectedDrive!)  },
            ].filter(Boolean) as MenuItem[]}>
            <button
              onClick={() => browseDrive(d.mountPoint)}
              data-selected={selectedDrive === d.mountPoint}
              className={`press-row w-full text-left px-1.5 py-1.5 rounded text-xs flex items-center gap-1.5 ${
                selectedDrive === d.mountPoint
                  ? "bg-emerald-900/30 text-emerald-300"
                  : "hover:bg-zinc-800/50 text-zinc-400"
              }`}
            >
              {/* 可移动设备=U盘图标, 固定磁盘=磁盘图标 (IconPark) */}
              {d.driveType === "removable"
                ? <DiskOne theme="filled" size="15" strokeWidth={3} className="text-emerald-500 flex-shrink-0" />
                : <Disk theme="filled" size="15" strokeWidth={3} className="text-zinc-500 flex-shrink-0" />}
              <span className="truncate">{d.label}</span>
            </button>
            </PixelMenu>
          ))}
          {drives.length === 0 && (
            <p className="text-zinc-600 text-[11px] px-2">{t("devices.noDevices")}</p>
          )}
        </div>

        <div className="flex-1 overflow-auto px-1.5 py-1.5 no-scrollbar">
          {browsing ? (
            <p className="text-[11px] text-emerald-500 px-1 animate-pulse">
              {t("devices.scanning")}
            </p>
          ) : folderTree ? (
            <div className="tree-enter">
              <div className="border-t border-zinc-800/50 mb-1.5" />
              <button
                onClick={() => loadFolder(folderTree.path)}
                data-selected={activeFolder === folderTree.path}
                className={`press-row w-full text-left px-2 py-1 rounded border text-[11px] mb-1 ${
                  activeFolder === folderTree.path
                    ? "bg-emerald-900/30 border-emerald-800/50 text-emerald-300"
                    : "bg-zinc-800/20 border-zinc-800/30 text-zinc-400 hover:bg-zinc-800/40"
                }`}
              >
                {t("devices.root")}
              </button>
              {folderTree.children.map((child) => (
                <FolderTreeItem
                  key={child.path}
                  node={child}
                  activeFolder={activeFolder}
                  onSelect={loadFolder}
                  depth={1}
                  counting={counting}
                />
              ))}
            </div>
          ) : (
            <p className="text-zinc-600 text-[11px] px-1">
              {selectedDrive ? t("devices.notScanned") : t("devices.selectDevice")}
            </p>
          )}
        </div>

        <div className="p-2 border-t border-zinc-800 text-[10px] text-zinc-600">
          {browsing ? t("devices.browsing") : loadingFolder ? t("devices.loading") : counting ? t("devices.counting") : selectedDrive ? t("devices.photos", { n: photos.length }) : t("devices.ready")}
        </div>
        </PixelMenu>
      </FloatingPanel>

      {/* === Center === */}
      <main className="flex-1 flex flex-col min-w-0 bg-grid">
        {/* 顶部工具栏 — 可折叠圆角浮窗 */}
        <PhotoToolbar
          selectedDrive={selectedDrive}
          photosCount={photos.length}
          selectedCount={selectedPaths.size}
          sortBy={sortBy}
          onSortByChange={(v) => setSortBy(v)}
          sortDir={sortDir}
          onToggleSortDir={() => setSortDir((d) => (d === "asc" ? "desc" : "asc"))}
          starFilter={starFilter}
          onStarFilterChange={setStarFilter}
          labelFilter={labelFilter}
          onLabelFilterChange={setLabelFilter}
          flagFilter={flagFilter}
          onFlagFilterChange={setFlagFilter}
          onClearFilters={clearFilters}
          analyzing={analyzing}
          analyzeCount={selectionScope.length}
          onSelectAll={selectAll}
          onClearSelection={clearSelection}
          onAnalyzeAll={() => runAnalysis(analysisScope)}
          onStopAnalysis={stopAnalysis}
          expanded={toolbarOpen}
          onToggle={() => setToolbarOpen((v) => !v)}
        />

        <PixelMenu items={emptyMenuItems}>
        {/* 中心主区域 — 照片网格/空状态/加载中 */}
        <ScrollFadeZone glass={transparentBg}>
<div className="h-full overflow-auto p-3 no-scrollbar">
          {browsing || loadingFolder ? (
            <div className="flex items-center justify-center h-full">
              <div className="flex flex-col items-center gap-3">
                <div className="w-8 h-8 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
                <p className="text-zinc-500 text-sm">
                  {browsing ? t("grid.browsing") : t("grid.loading")}
                </p>
              </div>
            </div>
          ) : photos.length === 0 ? (
            <div className="flex items-center justify-center h-full">
              {selectedDrive ? (
                <p className="text-zinc-600 text-sm">
                  {activeFolder ? t("grid.noPhotos") : t("grid.clickFolder")}
                </p>
              ) : (
                <WelcomeGuide />
              )}
            </div>
          ) : (
            // Phase 4(4.4-A): 整块网格只挂**一个** PixelMenu —— 右键卡片显示那张的菜单,
            // 右键空白回落到 emptyMenuItems。每张卡片各挂一个, 就等于"任何一次勾选都让
            // 所有卡片换 props", 双层 memo 全废(见 PhotoGridItem 上方注释)。
            <PixelMenu items={ctxTarget ? photoMenuItems : emptyMenuItems}>
            <div
              onContextMenu={(e) => {
                // 空白处右键: 清掉上一个右键目标, 菜单才会回落到"空白菜单"。
                // 卡片自己的 handler 先跑(只置不清), 这里靠 DOM 判定区分目标,
                // 不依赖两次 setState 的先后 —— 见 docs 4.4-A 的"新鲜度不变式"。
                if (!(e.target as HTMLElement).closest("[data-photo-path]")) setCtxTarget(null);
              }}
            >
              {/* 维度③ 的提示: 只看分析结果、还有没分析过的照片时才出现(docs 4.2) */}
              {showPendingHint && (
                <div className="mb-2 flex items-center gap-2 text-[11px] text-amber-200/90 bg-amber-500/10 border border-amber-500/20 rounded-lg px-3 py-1.5">
                  <span>{t("grid.pendingAnalysis", { n: pendingAnalysis.length })}</span>
                  <button
                    onClick={() => runAnalysis(pendingAnalysis)}
                    disabled={analyzing}
                    className="px-2 py-0.5 rounded bg-amber-500/20 hover:bg-amber-500/30 disabled:opacity-50"
                  >{t("grid.analyzePending", { n: pendingAnalysis.length })}</button>
                </div>
              )}
              {/* 三维筛到 0 张的兜底: "未分析"提示盖不住"全分析完但没命中"这一支 */}
              {showNoMatch && (
                <div className="mb-2 flex items-center gap-2 text-[11px] text-zinc-400 bg-zinc-800/40 border border-zinc-700/40 rounded-lg px-3 py-1.5">
                  <span>{t("grid.noMatch")}</span>
                  <button
                    onClick={clearFilters}
                    className="px-2 py-0.5 rounded bg-zinc-700/60 hover:bg-zinc-700"
                  >{t("toolbar.clearFilters")}</button>
                </div>
              )}
            <div className="grid photo-grid gap-2 content-start">
              {sortedPhotos.map((photo) => (
                <PhotoGridItem
                  key={photo.path}
                  photo={photo}
                  thumbnail={
                    thumbnails[photo.path] === "__err__"
                      ? undefined
                      : thumbnails[photo.path]
                  }
                  isSelected={selectedPhoto?.path === photo.path}
                  isChecked={selectedPaths.has(photo.path)}
                  analysis={analysis[photo.path]}
                  rating={ratings[photo.path]}
                  label={labels[photo.path]}
                  onToggle={handlePhotoClick}
                  onRate={setRating}
                  onOpenViewer={openViewer}
                  onSelect={selectPhoto}
                  onCtx={ctxPhoto}
                  loadThumb={loadThumbnail}
                />
              ))}
            </div>
            </div>
            </PixelMenu>
          )}
        </div>
        </ScrollFadeZone>
        </PixelMenu>

        {/* ═══ 底部导入栏 — 可折叠圆角浮窗 ═══ */}
        <ImportBar
          destDir={destDir}
          folderRule={folderRule}
          fileRule={fileRule}
          setFolderRule={setFolderRule}
          setFileRule={setFileRule}
          customFolder={customFolder}
          setCustomFolder={setCustomFolder}
          useCustomFolder={useCustomFolder}
          setUseCustomFolder={setUseCustomFolder}
          importing={importing}
          importProgress={importProgress}
          importDone={importDone}
          importError={importError}
          importResult={importResult}
          history={importHistory}
          scheme={importScheme}
          selectedCount={selectedPaths.size}
          lrcProbe={lrcProbe}
          lrcSending={lrcSending}
          lrcSent={lrcSent}
          lrcPhase={lrcPhase}
          onSendToLightroom={importToLightroom}
          onPickDestDir={pickDestDir}
          onOpenFolder={(dir) => invoke("open_folder", { path: dir })}
          onImport={() => startImport([...selectedPaths])}
          expanded={importBarOpen}
          onToggle={() => setImportBarOpen((v) => !v)}
        />
      </main>

      {/* ═══ 右侧面板 — EXIF详细信息浮窗 ═══ */}
      <FloatingPanel side="right" title={t("exif.panel")} defaultOpen={false} autoOpenKey={selectedPhoto?.path}>
        <div className="flex-1 overflow-auto p-3 no-scrollbar">
          {selectedPhoto ? (
            <ExifPanel photo={selectedPhoto} previewSrc={previewSrc} />
          ) : (
            <p className="text-zinc-600 text-xs text-center mt-8">{t("exif.hint")}</p>
          )}
        </div>
      </FloatingPanel>
      </div>{/* close inner flex row */}
      {/* 图片查看器 — 双击打开 */}
      {viewerIndex !== null && sortedPhotos.length > 0 && (
        <PhotoViewer
          photos={sortedPhotos}
          index={viewerIndex}
          ratings={ratings}
          onRate={setRating}
          labels={labels}
          onLabel={setLabel}
          labelModifier={labelModifier}
          onClose={() => setViewerIndex(null)}
          originRect={viewerOrigin}
          thumbnails={thumbnails}
          selectedPaths={selectedPaths}
          onToggleSelect={toggleSelect}
          autoAdvance={autoAdvance}
          onUndo={undo}
          onRedo={redo}
          undoTargetPath={lastUndoPath}
          onUndoToast={(p, kind) => showToast(patchToast(t, p, kind))}
        />
      )}
      {/* Phase 5 · XMP 边车 ask 档的"问一次"弹窗。
          本地评分/标签**已经写完**了 —— 这个框只决定"要不要落盘", 所以用户选"只写本机"
          不需要回滚任何东西; 直接关掉/按 Esc = 未作答(本会话不再问, 下次启动再问)。 */}
      <Dialog
        open={xmpAskPending}
        onOpenChange={(o) => { if (!o) resolveXmpAsk(null); }}
      >
        <DialogContent className="w-[460px]">
          <DialogHeader>
            <DialogTitle>{t("xmp.askTitle")}</DialogTitle>
            <DialogDescription>{t("xmp.askBody")}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <button
              type="button"
              onClick={() => resolveXmpAsk("off")}
              className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted"
            >
              {t("xmp.askNo")}
            </button>
            <button
              type="button"
              onClick={() => resolveXmpAsk("on")}
              className="px-3 py-1.5 rounded-md bg-emerald-600 hover:bg-emerald-500 text-white text-sm font-medium"
            >
              {t("xmp.askYes")}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Phase 7 · "Lightroom 已在运行"。
          实测(本机 LrC 15.2.1) + FastRawViewer 作者的说明: Lightroom 已经在运行时,
          Adobe 会**忽略**命令行传进去的路径, 导入对话框停在它上一次的源上 —— 也就是说
          "跳转到这批照片"不会发生。唯一可靠的办法是**冷启动**(先关掉 LrC 再带路径启动)。
          LrC 15.2.1 不接受任何"礼貌"的关闭请求(实测 CloseMainWindow / taskkill 不带 /F /
          WM_CLOSE 三种都无效), 所以"强制关闭"会丢掉未保存的调整 ——
          这个决定只能由用户做, 因此这里给两个按钮, 而不是程序擅自杀进程。 */}
      <Dialog open={lrcAlreadyRunning} onOpenChange={(o) => { if (!o) setLrcAlreadyRunning(false); }}>
        <DialogContent className="w-[460px]">
          <DialogHeader>
            <DialogTitle>{t("lrc.runningTitle")}</DialogTitle>
            <DialogDescription>{t("lrc.runningBody")}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setLrcAlreadyRunning(false)}
              className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted"
            >
              {t("lrc.runningRetry")}
            </button>
            <button
              type="button"
              disabled={lrcSending}
              onClick={forceCloseLightroomAndRetry}
              className="px-3 py-1.5 rounded-md bg-red-600 hover:bg-red-500 disabled:bg-zinc-700 text-white text-sm font-medium"
            >
              {lrcSending ? t("lrc.sending") : t("lrc.runningForce")}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Phase 7 · 「导入到 LrC」这条链的模态进度提示。
          导入与启动 Lightroom 各自可能要几十秒, 期间必须有明确的"在干什么"。
          用户关掉它(或按 Esc)之后, 导入栏那行脉冲文字照旧显示 —— 反馈只是从模态降级为常驻。 */}
      <LrcProgressDialog
        phase={lrcPhase}
        imported={importDone}
        total={selectedPaths.size}
        open={lrcProgressOpen}
        onOpenChange={setLrcProgressOpen}
      />

      {/* 弹出提示浮窗 — 渐变出现停留1秒后消失 */}
      <div
        className={`fixed top-16 left-1/2 -translate-x-1/2 px-4 py-2 rounded-lg bg-emerald-600/90 text-white text-sm shadow-2xl z-[200] transition-all duration-300 ${
          toast ? "opacity-100 scale-100" : "opacity-0 scale-95 pointer-events-none"
        }`}
      >
        {toast}
      </div>
    </div>
  );
}

/** Recursive folder tree item */
export default App;
