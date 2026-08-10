import { useEffect, useState, useMemo, useRef } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { useTranslation } from "react-i18next";
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
import { ThumbSizeSlider } from "./components/thumb-size-slider";
import { AdvancedOptions } from "./components/advanced-options";
// ═══════════════════════════════════════════════════════════════════
// 🎨 图标约定: 本项目所有图标一律使用 bytedance/IconPark (@icon-park/react)
//    参考: https://github.com/bytedance/IconPark
//    用法: import { 图标名 } from "@icon-park/react"
//    支持 theme="outline|filled|two-tone|multi-color" size fill 等
//    请不要混用 emoji/文字符号 等其他图标方案
// ═══════════════════════════════════════════════════════════════════
import { Disk, DiskOne } from "@icon-park/react";
import type { ScannedPhoto } from "./types";

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
  // 透明毛玻璃背景: 默认开启, 深色/浅色随主题切换
  const [transparentBg, setTransparentBg] = useState<boolean>(() => localStorage.getItem("pixelflow-glass") !== "0");

  useEffect(() => {
    localStorage.setItem("pixelflow-glass", transparentBg ? "1" : "0");
  }, [transparentBg]);

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
    <div className={`flex flex-col h-screen w-screen text-zinc-100 transition-colors duration-200 ${transparentBg ? "bg-zinc-950/70" : "bg-zinc-950"}`}>
      {/* Custom title bar */}
      <TitleBar
        preloadFull={preloadFull}
        onTogglePreloadFull={togglePreloadFull}
        transparentBg={transparentBg}
        onToggleTransparentBg={() => setTransparentBg((v) => !v)}
      />
      <div className="flex flex-1 min-h-0">
      {/* === Left Sidebar === */}
      <FloatingPanel side="left" title={t("devices.panel")} glass={transparentBg}>
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
<div className={`h-9 border-b border-white/5 flex items-center px-4 gap-2 flex-shrink-0 ${transparentBg ? "bg-zinc-950/70" : "bg-zinc-950"}`}>
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
        <div className={`border-t border-white/5 flex-shrink-0 ${transparentBg ? "bg-zinc-950/70" : "bg-zinc-950"}`}>
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
      <FloatingPanel side="right" title={t("exif.panel")} glass={transparentBg}>
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
export default App;
