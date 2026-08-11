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
import { PhotoToolbar } from "./components/photo-toolbar";
import { ImportBar } from "./components/import-bar";
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
        {/* 顶部工具栏 — 可折叠圆角浮窗 */}
        <PhotoToolbar
          selectedDrive={selectedDrive}
          photosCount={photos.length}
          selectedCount={selectedPaths.size}
          sortBy={sortBy}
          onSortByChange={(v) => setSortBy(v)}
          starFilter={starFilter}
          onStarFilterChange={setStarFilter}
          analyzing={analyzing}
          onSelectAll={selectAll}
          onClearSelection={clearSelection}
          onAnalyzeAll={() => runAnalysis(photos.map((p) => p.path))}
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
          importError={importError}
          importResult={importResult}
          selectedCount={selectedPaths.size}
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
          onClose={() => setViewerIndex(null)}
          originRect={viewerOrigin}
          thumbnails={thumbnails}
          selectedPaths={selectedPaths}
          onToggleSelect={(path) => handlePhotoClick(path, { ctrlKey: false, shiftKey: false })}
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
