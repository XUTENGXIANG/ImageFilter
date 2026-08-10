import { useEffect, useState, useMemo, useRef } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { useTranslation } from "react-i18next";
import { PixelMenu, SEPARATOR, type MenuItem } from "./contextmenu";
import { FloatingPanel } from "./panel";
import { PhotoViewer } from "./viewer";
import { useScanner, type FolderNode } from "./useScanner";
import { setLanguage, type Lang } from "./i18n";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "@/components/ui/dialog";
// ═══════════════════════════════════════════════════════════════════
// 🎨 图标约定: 本项目所有图标一律使用 bytedance/IconPark (@icon-park/react)
//    参考: https://github.com/bytedance/IconPark
//    用法: import { 图标名 } from "@icon-park/react"
//    支持 theme="outline|filled|two-tone|multi-color" size fill 等
//    请不要混用 emoji/文字符号 等其他图标方案
// ═══════════════════════════════════════════════════════════════════
import { Setting, Sun, Moon, Close, Disk, DiskOne, Help } from "@icon-park/react";
import type { ScannedPhoto } from "./types";

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1073741824) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1073741824).toFixed(2)} GB`;
}

// ── 标题栏 ──────────────────────────────────
// 高度: h-9 (36px)  背景: bg-zinc-900  底部边框: border-zinc-800
// 拖拽: data-tauri-drag-region  禁止选中: select-none
function TitleBar({ preloadFull, onTogglePreloadFull }: { preloadFull: boolean; onTogglePreloadFull: () => void }) {
  const { t } = useTranslation();
  const win = getCurrentWindow();
  // 设置面板开关
  const [showSettings, setShowSettings] = useState(false);
  // 使用说明开关
  const [showHelp, setShowHelp] = useState(false);
  // 主题状态: dark=深色 light=浅色(默认)
  const [theme, setTheme] = useState<"dark" | "light">(() =>
    (localStorage.getItem("pixelflow-theme") as "dark" | "light") || "light"
  );
  // 语言状态: 跟随 localStorage 持久化
  const [lang, setLang] = useState<Lang>(() =>
    (localStorage.getItem("pixelflow-lang") as Lang) || "zh"
  );
  useEffect(() => {
    const root = document.documentElement;
    if (theme === "dark") {
      root.classList.add("dark");
      root.setAttribute("data-theme", "dark");
    } else {
      root.classList.remove("dark");
      root.setAttribute("data-theme", "light");
    }
    localStorage.setItem("pixelflow-theme", theme);
  }, [theme]);

  return (
    <div
      data-tauri-drag-region
      className="h-9 flex items-center justify-between px-1 bg-zinc-900 border-b border-zinc-800 select-none flex-shrink-0"
    >
      <span className="text-[11px] text-zinc-500 ml-3">PixelFlow</span>
      <div className="flex items-center h-full">
        {/* 使用说明按钮 — IconPark Help */}
        <button onClick={() => setShowHelp(true)}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800"
          title={t("titlebar.help")}
        >
          <Help theme="filled" size="15" strokeWidth={3} />
        </button>
        {/* 设置按钮 — IconPark Setting */}
        <button onClick={() => setShowSettings(true)}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800"
          title={t("titlebar.settings")}
        >
          <Setting theme="filled" size="15" strokeWidth={3} />
        </button>
        {/* 主题切换按钮 — IconPark Sun/Moon */}
        <button onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          className="w-8 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800"
          title={theme === "dark" ? t("titlebar.themeLight") : t("titlebar.themeDark")}
        >
          {theme === "dark" ? <Sun theme="filled" size="15" strokeWidth={3} /> : <Moon theme="filled" size="15" strokeWidth={3} />}
        </button>
        {/* 最小化按钮 */}
        <button onClick={() => win.minimize()}
          className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800">
          <svg width="10" height="1"><rect width="10" height="1" fill="currentColor"/></svg>
        </button>
        {/* 最大化按钮 */}
        <button onClick={() => win.toggleMaximize()}
          className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800">
          <svg width="10" height="10" viewBox="0 0 10 10">
            <rect x="0.5" y="0.5" width="9" height="9" fill="none" stroke="currentColor" strokeWidth="1"/>
          </svg>
        </button>
        {/* 关闭按钮 — IconPark Close, hover变红 */}
        <button onClick={() => win.close()}
          className="w-10 h-full flex items-center justify-center text-zinc-500 hover:text-red-400 hover:bg-red-400/10">
          <Close theme="filled" size="14" strokeWidth={4} />
        </button>
      </div>

      {/* ═══ 设置浮窗 ═══ */}
      <Dialog open={showSettings} onOpenChange={setShowSettings}>
        <DialogContent className="w-[420px] max-h-[80vh] overflow-auto">
          <DialogHeader>
            <DialogTitle>{t("settings.title")}</DialogTitle>
            <DialogDescription>{t("settings.subtitle")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            {/* 语言设置 */}
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-foreground">{t("settings.language")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.languageDesc")}</p>
              </div>
              <div className="flex rounded-md border border-border overflow-hidden text-sm">
                {(["zh", "en"] as Lang[]).map((l) => (
                  <button
                    key={l}
                    onClick={() => { setLang(l); setLanguage(l); }}
                    className={`px-3 py-1.5 transition-colors ${lang === l ? "bg-foreground text-background" : "hover:bg-muted text-muted-foreground"}`}
                  >
                    {l === "zh" ? "中文" : "EN"}
                  </button>
                ))}
              </div>
            </div>
            {/* 主题设置 */}
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-foreground">{t("settings.theme")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.themeDesc")}</p>
              </div>
              <button
                onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
                className="px-3 py-1.5 rounded-md border border-border text-sm hover:bg-muted"
              >
                {theme === "dark" ? `🌙 ${t("settings.dark")}` : `☀️ ${t("settings.light")}`}
              </button>
            </div>
            {/* 可见区域全图预加载 */}
            <div className="flex items-start justify-between gap-3">
              <div className="flex-1 min-w-0">
                <p className="text-sm text-foreground">{t("settings.preload")}</p>
                <p className="text-xs text-muted-foreground">{t("settings.preloadDesc")}</p>
              </div>
              <button
                onClick={onTogglePreloadFull}
                className={`mt-0.5 w-10 h-6 rounded-full relative shrink-0 transition-colors ${preloadFull ? "bg-emerald-500" : "bg-muted"}`}
              >
                <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-all ${preloadFull ? "left-[18px]" : "left-0.5"}`} />
              </button>
            </div>
            {/* 更多设置占位 */}
            <div className="border-t border-border pt-3">
              <p className="text-xs text-muted-foreground text-center py-4">
                {t("settings.more")}
              </p>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      {/* ═══ 使用说明浮窗 ═══ */}
      <Dialog open={showHelp} onOpenChange={setShowHelp}>
        <DialogContent className="w-[480px] max-h-[80vh] overflow-auto">
          <DialogHeader>
            <DialogTitle>{t("help.title")}</DialogTitle>
            <DialogDescription>{t("help.subtitle")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2 text-sm">
            <div className="space-y-2">
              <Step num="1" title={t("help.step1Title")} desc={t("help.step1Desc")} />
              <Step num="2" title={t("help.step2Title")} desc={t("help.step2Desc")} />
              <Step num="3" title={t("help.step3Title")} desc={t("help.step3Desc")} />
              <Step num="4" title={t("help.step4Title")} desc={t("help.step4Desc")} />
            </div>
            <div className="border-t border-border pt-3">
              <p className="text-xs font-medium text-foreground mb-2">{t("help.shortcuts")}</p>
              <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
                <span><kbd className="px-1 bg-muted rounded">J</kbd> {t("help.keep")}</span>
                <span><kbd className="px-1 bg-muted rounded">X</kbd> {t("help.trash")}</span>
                <span><kbd className="px-1 bg-muted rounded">1-5</kbd> {t("help.star")}</span>
                <span><kbd className="px-1 bg-muted rounded">R</kbd> {t("help.rotate")}</span>
                <span><kbd className="px-1 bg-muted rounded">←→</kbd> {t("help.nav")}</span>
                <span><kbd className="px-1 bg-muted rounded">0</kbd> {t("help.reset")}</span>
                <span><kbd className="px-1 bg-muted rounded">Ctrl+点击</kbd> {t("help.multi")}</span>
                <span><kbd className="px-1 bg-muted rounded">Shift+点击</kbd> {t("help.range")}</span>
              </div>
            </div>
            <div className="border-t border-border pt-3">
              <p className="text-xs font-medium text-foreground mb-2">{t("help.contextMenu")}</p>
              <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
                <span>{t("help.ctxPhoto")}</span>
                <span>{t("help.ctxEmpty")}</span>
                <span>{t("help.ctxDevice")}</span>
                <span>{t("help.ctxFolder")}</span>
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

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
    importing,
    importProgress,
    importError,
    importResult,
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
  } = useScanner();

  // Disable browser default context menu
  useEffect(() => {
    const handler = (e: MouseEvent) => e.preventDefault();
    window.addEventListener("contextmenu", handler);
    return () => window.removeEventListener("contextmenu", handler);
  }, []);

  // 屏蔽 Ctrl+A 全选文本（输入框内除外）
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === "a") {
        const tag = (e.target as HTMLElement)?.tagName;
        if (tag !== "INPUT" && tag !== "TEXTAREA") {
          e.preventDefault();
        }
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  useEffect(() => {
    detectDrives();
    const timer = setInterval(detectDrives, 5000);
    return () => clearInterval(timer);
  }, [detectDrives]);

  // Keyboard shortcuts: J=rate3, X=rate0, 1-5=star (查看器打开时不处理, 交给viewer)
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (viewerIndex !== null) return;
      if (!selectedPhoto || e.target instanceof HTMLInputElement) return;
      const key = e.key.toLowerCase();
      if (key === "j") setRating(selectedPhoto.path, 3);
      else if (key === "x") setRating(selectedPhoto.path, 0);
      else if (key >= "1" && key <= "5") setRating(selectedPhoto.path, Number(key));
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [selectedPhoto, setRating]);

  // Sort + filter photos
  const sortedPhotos = useMemo(() => {
    let list = [...photos];
    // Star filter
    if (starFilter > 0) {
      list = list.filter((p) => (ratings[p.path] || 0) >= starFilter);
    }
    // Sort
    if (sortBy === "name") {
      list.sort((a, b) => a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase()));
    } else if (sortBy === "type") {
      list.sort((a, b) => {
        const ea = a.fileName.split(".").pop()?.toLowerCase() || "";
        const eb = b.fileName.split(".").pop()?.toLowerCase() || "";
        return ea.localeCompare(eb) || a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase());
      });
    } else if (sortBy === "date") {
      list.sort((a, b) => b.modifiedAt - a.modifiedAt);
    }
    return list;
  }, [photos, sortBy, starFilter, ratings]);

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
      { label: t("menu.viewExif"), action: () => { setSelectedPhoto(sp); loadExif(sp); } },
      { label: t("menu.openLocation"), action: () => { const dir = sp.path.replace(/\\[^\\]+$/, ""); invoke("open_folder", { path: dir }); } },
      SEPARATOR,
      { label: t("menu.selectAll"), action: selectAll },
      { label: t("menu.deselect"), action: clearSelection },
    ];
  }, [ctxTarget, selectedPaths, startImport, setRating, loadExif, selectAll, clearSelection, t]);

  const emptyMenuItems = useMemo((): MenuItem[] => [
    { label: t("menu.refresh"), action: () => selectedDrive && browseDrive(selectedDrive!) },
    { label: t("menu.importAll"), action: () => startImport(photos.map((p) => p.path)) },
    { label: t("menu.selectAll"), action: selectAll },
    { label: t("menu.ai"), action: () => runAnalysis(photos.map((p) => p.path)) },
  ], [photos, selectedDrive, startImport, selectAll, browseDrive, runAnalysis, t]);

  // 弹出提示浮窗
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<number | undefined>(undefined);
  const showToast = (msg: string) => {
    setToast(msg);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 1200);
  };

  // 图片查看器: viewerIndex=null 关闭, 数字=打开第N张
  const [viewerIndex, setViewerIndex] = useState<number | null>(null);
  const [viewerOrigin, setViewerOrigin] = useState<{ x: number; y: number; w: number; h: number } | undefined>(undefined);

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
          .then((diskPath) => new Promise<void>((resolve) => {
            const img = new Image();
            img.onload = () => resolve();
            img.onerror = () => resolve();
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
    <div className="flex flex-col h-screen w-screen bg-zinc-950 text-zinc-100">
      {/* Custom title bar */}
      <TitleBar preloadFull={preloadFull} onTogglePreloadFull={togglePreloadFull} />
      <div className="flex flex-1 min-h-0">
      {/* === Left Sidebar === */}
      <FloatingPanel side="left" title={t("devices.panel")}>
        {/* 面板级右键菜单 (空白区域/刷新按钮区域) */}
        <PixelMenu items={[
          { label: t("devices.refresh"), action: () => selectedDrive && browseDrive(selectedDrive!) },
          //{ label: "刷新设备列表", action: detectDrives },
        ]}>
        <div className="px-3 pt-2 pb-1 flex items-center">
          <button onClick={() => selectedDrive && browseDrive(selectedDrive!)} className="text-[10px] px-3 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-400 transition-colors">{t("devices.refresh")}</button>
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
              className={`w-full text-left px-1.5 py-1.5 rounded text-xs flex items-center gap-1.5 ${
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
            <div>
              <div className="border-t border-zinc-800/50 mb-1.5" />
              <button
                onClick={() => loadFolder(folderTree.path)}
                className={`w-full text-left px-2 py-1 rounded border text-[11px] mb-1 ${
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
        {/* 工具栏 — 全选/取消/排序/星级筛选/缩略图滑块/AI分析 */}
<div className="h-9 border-b border-white/5 flex items-center px-4 gap-2 flex-shrink-0 bg-zinc-950">
          {selectedDrive && photos.length > 0 && (
            <>
              <button onClick={selectAll} className="text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.selectAll")}</button>
              <button onClick={clearSelection} className="text-[10px] text-zinc-500 hover:text-zinc-300">{t("toolbar.clear")}</button>
              <span className="text-[10px] text-zinc-600">{t("toolbar.selected", { n: selectedPaths.size, total: photos.length })}</span>
              {/* Sort */}
              <select value={sortBy} onChange={(e) => setSortBy(e.target.value as any)}
                className="bg-zinc-800 text-[10px] text-zinc-400 px-1 py-0.5 rounded border border-zinc-700">
                <option value="name">{t("toolbar.sortName")}</option>
                <option value="type">{t("toolbar.sortType")}</option>
                <option value="date">{t("toolbar.sortDate")}</option>
              </select>
              {/* Star filter */}
              {[0,1,2,3,4,5].map((s) => (
                <button key={s} onClick={() => setStarFilter(starFilter === s ? 0 : s)}
                  className={`text-[10px] px-1 rounded ${starFilter === s ? "text-amber-400 bg-amber-400/10" : "text-zinc-600 hover:text-zinc-400"}`}
                >{s === 0 ? t("toolbar.all") : "★".repeat(s)}</button>
              ))}
              <ThumbSizeSlider />
              <div className="flex-1" />
              <button
                onClick={() => analyzing ? stopAnalysis() : runAnalysis(photos.map((p) => p.path))}
                className={`text-[10px] px-2 py-0.5 rounded text-zinc-400 ${
                  analyzing
                    ? "bg-red-900/50 hover:bg-red-800/50 text-red-400"
                    : "bg-zinc-800 hover:bg-zinc-700"
                }`}
              >
                {analyzing ? t("toolbar.stop") : t("toolbar.ai")}
              </button>
            </>
          )}
        </div>

        <PixelMenu items={emptyMenuItems}>
        {/* 中心主区域 — 照片网格/空状态/加载中 */}
        <ScrollFadeZone>
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
            <PixelMenu items={emptyMenuItems}>
            <div className="grid photo-grid gap-2 content-start">
              {sortedPhotos.map((photo) => (
                <PixelMenu key={photo.path} items={photoMenuItems} onOpenChange={(open) => { if (open) setCtxTarget(photo); }}>
                <PhotoCard
                  key={photo.path}
                  photo={photo}
                  thumbnail={
                    thumbnails[photo.path] === "__err__"
                      ? undefined
                      : thumbnails[photo.path]
                  }
                  isSelected={selectedPhoto?.path === photo.path}
                  isChecked={selectedPaths.has(photo.path)}
                  onToggle={(e: React.MouseEvent) => handlePhotoClick(photo.path, { ctrlKey: e.ctrlKey, shiftKey: e.shiftKey })}
                  analysis={analysis[photo.path]}
                  rating={ratings[photo.path]}
                  onRate={(s: number) => setRating(photo.path, s)}
                  onDoubleClick={(e: React.MouseEvent) => {
                    if (photo.isVideo) return; // 视频暂不支持预览
                    const r = e.currentTarget.getBoundingClientRect();
                    setViewerOrigin({ x: r.x, y: r.y, w: r.width, h: r.height });
                    setViewerIndex(sortedPhotos.indexOf(photo));
                  }}
                  onContextMenu={() => setCtxTarget(photo)}
                  onClick={(e: React.MouseEvent) => {
                    handlePhotoClick(photo.path, { ctrlKey: e.ctrlKey, shiftKey: e.shiftKey });
                    setSelectedPhoto(photo);
                    if (!thumbnails[photo.path]) loadThumbnail(photo.path, 300);
                    loadExif(photo);
                  }}
                />
                </PixelMenu>
              ))}
            </div>
            </PixelMenu>
          )}
        </div>
        </ScrollFadeZone>
        </PixelMenu>

{/* ═══ 底部导入栏 — 目标文件夹 + 导入按钮 + 进度 ═══ */}
        <div className="border-t border-white/5 flex-shrink-0 bg-zinc-950">
          <div className="flex items-center gap-2 px-3 py-1.5">
            <button
              onClick={pickDestDir}
              className="text-[10px] px-2 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-400 truncate max-w-[180px]"
            >
              {destDir ? `...${destDir.slice(-25)}` : t("import.pickDest")}
            </button>
            {destDir && (
              <button
                onClick={() => invoke("open_folder", { path: destDir })}
                className="text-[10px] px-1.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-500"
                title={t("import.openFolder")}
              >
                📂
              </button>
            )}
            <div className="flex-1" />
            <span className="text-[10px] text-zinc-600">
              {!destDir ? t("import.needDest") :
               selectedPaths.size === 0 ? t("import.needSelect") :
               importing ? t("import.importing") : ""}
            </span>
            <button
              disabled={!destDir || selectedPaths.size === 0 || importing}
              onClick={() => startImport([...selectedPaths])}
              className="text-[10px] px-3 py-1 rounded bg-emerald-600 hover:bg-emerald-500 disabled:bg-zinc-700 disabled:text-zinc-500 text-white font-medium"
            >
              {importing
                ? t("import.importingCount", { done: importProgress.filter((p: {status: string}) => p.status === "done").length, total: selectedPaths.size })
                : t("import.importCount", { n: selectedPaths.size })}
            </button>
          </div>
          {/* Advanced: naming rules */}
          {importError && (
            <div className="px-3 pb-1 text-[10px] text-red-400">{t("import.error", { msg: importError })}</div>
          )}
          {importResult && (
            <div className="px-3 pb-1 text-[10px] text-emerald-400">
              {t("import.doneOk", { n: importResult.ok })}{importResult.fail > 0 ? t("import.doneFail", { n: importResult.fail }) : ""}
            </div>
          )}
          <AdvancedOptions
            folderRule={folderRule}
            fileRule={fileRule}
            setFolderRule={setFolderRule}
            setFileRule={setFileRule}
            customFolder={customFolder}
            setCustomFolder={setCustomFolder}
            useCustomFolder={useCustomFolder}
            setUseCustomFolder={setUseCustomFolder}
          />
          {importing && importProgress.length > 0 && (
            <div className="px-3 pb-1.5 max-h-16 overflow-auto no-scrollbar">
              {importProgress.slice(-4).map((p, i) => (
                <div key={i} className="text-[9px] text-zinc-500 flex gap-1.5">
                  <span className={
                    p.status === "error" ? "text-red-400" :
                    p.status === "done" ? "text-emerald-400" :
                    p.status === "skipped" ? "text-zinc-600" : "text-zinc-500"
                  }>
                    {p.status === "done" ? "✓" :
                     p.status === "error" ? "✗" :
                     p.status === "skipped" ? "→" : "·"}
                  </span>
                  <span className="truncate flex-1">{p.fileName}</span>
                  <span className="flex-shrink-0">{p.message}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </main>

      {/* ═══ 右侧面板 — EXIF详细信息浮窗 ═══ */}
      <FloatingPanel side="right" title={t("exif.panel")}>
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
          onClose={() => setViewerIndex(null)}
          originRect={viewerOrigin}
          thumbnails={thumbnails}
        />
      )}
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
function FolderTreeItem({
  node, activeFolder, onSelect, depth, counting,
}: {
  node: FolderNode; activeFolder: string; onSelect: (path: string) => void;
  depth: number; counting: boolean;
}) {
  const [open, setOpen] = useState(false);
  const canExpand = node.hasSubdirs || node.children.length > 0;
  const isActive = activeFolder === node.path;

  return (
    <div>
      <button
        onClick={() => {
          if (canExpand) setOpen(!open);
          onSelect(node.path);
        }}
        className={`w-full text-left rounded text-[11px] flex items-center gap-1 ${
          isActive
            ? "bg-emerald-900/30 text-emerald-300"
            : "text-zinc-400 hover:bg-zinc-800/50"
        }`}
        style={{ paddingLeft: `${depth * 12 + 8}px`, paddingRight: "4px", paddingTop: "2px", paddingBottom: "2px" }}
      >
        <span className="text-[10px] w-3 flex-shrink-0">
          {canExpand ? (open ? "▼" : "▶") : "📂"}
        </span>
        <span className="truncate">{node.name}</span>
        {!(counting && node.photoCount === 0) && (
          <span className="text-zinc-600 ml-auto flex-shrink-0">
            {node.photoCount}
          </span>
        )}
      </button>
      {open && canExpand &&
        node.children.map((c) => (
          <FolderTreeItem
            key={c.path}
            node={c}
            activeFolder={activeFolder}
            onSelect={onSelect}
            depth={depth + 1}
            counting={counting}
          />
        ))}
    </div>
  );
}

{/* ═══ 照片缩略图卡片 ═══ */}
{/* 尺寸: aspect-square  圆角: rounded-lg  边框: border-2  选中: border-emerald-400 */}
{/* 角标: RAW(amber) 视频(blue) 模糊(red) 过曝(yellow) 欠曝(indigo) 重复(gray) 最佳(emerald) */}
function PhotoCard({
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
      {/* Select checkbox — always visible when checked, hover for unchecked */}
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

{/* ═══ 右侧 EXIF 信息面板 ═══ */}
function ExifPanel({ photo, previewSrc }: { photo: ScannedPhoto; previewSrc: string | null }) {
  const { t } = useTranslation();
  const { exif } = photo;
  return (
    <div className="space-y-4">
      {previewSrc && (
        <div className="aspect-square rounded-lg bg-zinc-800 overflow-hidden">
          <img src={previewSrc} alt={photo.fileName} className="w-full h-full object-cover" />
        </div>
      )}
      <Section title={t("exif.fileInfo")}>
        <Row label={t("exif.fileName")} value={photo.fileName} />
        <Row label={t("exif.size")} value={formatBytes(photo.fileSize)} />
        <Row label={t("exif.type")} value={photo.isRaw ? "RAW" : photo.isVideo ? t("exif.typeVideo") : t("exif.typeImage")} />
      </Section>
      {(exif.cameraMake || exif.cameraModel) && (
        <Section title={t("exif.camera")}>
          <Row label={t("exif.brand")} value={exif.cameraMake} />
          <Row label={t("exif.model")} value={exif.cameraModel} />
          <Row label={t("exif.lens")} value={exif.lensModel} />
        </Section>
      )}
      {(exif.aperture || exif.shutterSpeed || exif.iso) && (
        <Section title={t("exif.params")}>
          <Row label={t("exif.aperture")} value={exif.aperture} />
          <Row label={t("exif.shutter")} value={exif.shutterSpeed} />
          <Row label={t("exif.iso")} value={exif.iso?.toString()} />
          <Row label={t("exif.focal")} value={exif.focalLength} />
        </Section>
      )}
      {exif.dateTaken && (
        <Section title={t("exif.date")}>
          <p className="text-[11px] text-zinc-300">{exif.dateTaken}</p>
        </Section>
      )}
      {exif.imageWidth && (
        <Section title={t("exif.dims")}>
          <p className="text-[11px] text-zinc-300">{exif.imageWidth} × {exif.imageHeight}</p>
        </Section>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h3 className="text-[10px] font-semibold text-zinc-500 uppercase mb-1.5">{title}</h3>
      <div className="text-[11px] space-y-1">{children}</div>
    </section>
  );
}

/** 从文件名取扩展名标签 (dng→DNG, jpg→JPG, png→PNG...) */
function formatBadge(fileName: string): string {
  const ext = fileName.split(".").pop()?.toUpperCase() || "";
  return ext || "?";
}

function Badge({ color, label }: { color: string; label: string }) {
  return <span className={`text-[9px] px-1.5 py-0.5 rounded ${color} text-white font-medium`}>{label}</span>;
}

function Row({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <div className="flex justify-between gap-2">
      <span className="text-zinc-500 flex-shrink-0">{label}</span>
      <span className="text-zinc-300 text-right truncate">{value}</span>
    </div>
  );
}

function ThumbSizeSlider() {
  const [cols, setCols] = useState(() => {
    try { return parseInt(localStorage.getItem("pixelflow-cols") || "4"); }
    catch { return 4; }
  });
  // Apply grid-cols via CSS variable approach — inject <style>
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

// 滚动遮罩 — 滚动时上下淡入淡出，静止时隐藏
function ScrollFadeZone({ children }: { children: React.ReactNode }) {
  const [scrolling, setScrolling] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  const zoneRef = useRef<HTMLDivElement>(null);

  // 原生捕获阶段监听所有后代滚动事件
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

  return (
    <div ref={zoneRef} className="relative flex-1 min-h-0">
      {children}
      {/* 顶部遮罩 */}
      <div className={maskCls} style={{ top: 0, background: "linear-gradient(to bottom, var(--color-zinc-950), transparent)" }} />
      {/* 底部遮罩 */}
      <div className={maskCls} style={{ bottom: 0, background: "linear-gradient(to top, var(--color-zinc-950), transparent)" }} />
    </div>
  );
}

function WelcomeGuide() {
  const { t } = useTranslation();
  return (
    <div className="max-w-md text-center space-y-6 p-8">
      <h1 className="text-2xl font-light text-zinc-300 tracking-wide">PixelFlow</h1>
      <p className="text-xs text-zinc-500">{t("welcome.subtitle")}</p>
      <div className="space-y-3 text-left">
        <Step num="1" title={t("help.step1Title")} desc={t("help.step1Desc")} />
        <Step num="2" title={t("help.step2Title")} desc={t("help.step2Desc")} />
        <Step num="3" title={t("help.step3Title")} desc={t("help.step3Desc")} />
        <Step num="4" title={t("help.step4Title")} desc={t("help.step4Desc")} />
      </div>
      <div className="pt-4 border-t border-zinc-800 text-left text-[10px] text-zinc-600 space-y-1">
        <p><kbd className="px-1 bg-zinc-800 rounded text-zinc-400">J</kbd> {t("welcome.keep")} <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">X</kbd> {t("welcome.trash")} <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">1-5</kbd> {t("welcome.star")}</p>
        <p><kbd className="px-1 bg-zinc-800 rounded text-zinc-400">Ctrl+点击</kbd> {t("help.multi")} <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">Shift+点击</kbd> {t("help.range")}</p>
      </div>
    </div>
  );
}

function Step({ num, title, desc }: { num: string; title: string; desc: string }) {
  return (
    <div className="flex gap-3">
      <span className="w-5 h-5 rounded-full bg-zinc-800 text-zinc-400 text-[10px] flex items-center justify-center flex-shrink-0 mt-0.5">{num}</span>
      <div>
        <p className="text-xs text-zinc-300">{title}</p>
        <p className="text-[10px] text-zinc-600">{desc}</p>
      </div>
    </div>
  );
}

function AdvancedOptions({
  folderRule, fileRule, setFolderRule, setFileRule,
  customFolder, setCustomFolder, useCustomFolder, setUseCustomFolder,
}: {
  folderRule: string; fileRule: string;
  setFolderRule: (v: string) => void; setFileRule: (v: string) => void;
  customFolder: string; setCustomFolder: (v: string) => void;
  useCustomFolder: boolean; setUseCustomFolder: (v: boolean) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  const toggleDate = () => setFolderRule(folderRule.includes("{date}") ? "" : "{date}");
  const toggleCamera = () => setFolderRule(folderRule.includes("{camera}") ? folderRule.replace("/{camera}","").replace("{camera}/","").replace("{camera}","") : (folderRule ? folderRule + "/{camera}" : "{camera}"));
  const toggleSeq = () => setFileRule(fileRule === "{seq}.{ext}" ? "" : "{seq}.{ext}");

  return (
    <div className="px-3">
      <button
        onClick={() => setOpen(!open)}
        className="text-[10px] text-zinc-600 hover:text-zinc-400"
      >
        {open ? `▾ ${t("import.advanced")}` : `▸ ${t("import.advanced")}`}
      </button>
      {open && (
        <div className="mt-1 pb-1.5 space-y-1">
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={folderRule.includes("{date}")} onChange={toggleDate}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.dateFolder")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.dateFolderEx")}</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={folderRule.includes("{camera}")} onChange={toggleCamera}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.cameraFolder")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.cameraFolderEx")}</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={fileRule === "{seq}.{ext}"} onChange={toggleSeq}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.seqRename")}</span>
            <span className="text-[9px] text-zinc-600">{t("import.seqRenameEx")}</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={useCustomFolder}
              onChange={() => setUseCustomFolder(!useCustomFolder)}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">{t("import.subFolder")}</span>
            {useCustomFolder && (
              <input
                value={customFolder}
                onChange={(e) => setCustomFolder(e.target.value)}
                placeholder={t("import.subFolderPlaceholder")}
                className="w-28 bg-zinc-800 text-[10px] text-zinc-300 px-2 py-0.5 rounded border border-zinc-700"
              />
            )}
          </label>
        </div>
      )}
    </div>
  );
}

export default App;
