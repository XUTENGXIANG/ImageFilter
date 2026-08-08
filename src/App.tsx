import { useEffect, useState, useMemo } from "react";
import { invoke, convertFileSrc } from "@tauri-apps/api/core";
import { useScanner, type FolderNode } from "./useScanner";
import type { ScannedPhoto } from "./types";

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1073741824) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1073741824).toFixed(2)} GB`;
}

function App() {
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
  } = useScanner();

  useEffect(() => {
    detectDrives();
    const timer = setInterval(detectDrives, 5000);
    return () => clearInterval(timer);
  }, [detectDrives]);

  // Keyboard shortcuts: J=rate3, X=rate0, 1-5=star
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
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

  const previewSrc = selectedPhoto
    ? (thumbnails[selectedPhoto.path] && thumbnails[selectedPhoto.path] !== "__err__"
        ? thumbnails[selectedPhoto.path]
        : convertFileSrc(selectedPhoto.path))
    : null;

  return (
    <div className="flex h-screen w-screen bg-zinc-950 text-zinc-100">
      {/* === Left Sidebar === */}
      <aside className="w-60 min-w-[15rem] border-r border-zinc-800 flex flex-col">
        {/* Drive list */}
        <div className="p-3 border-b border-zinc-800">
          <button
            onClick={detectDrives}
            className="w-full text-left text-xs font-semibold text-zinc-400 uppercase tracking-wider hover:text-zinc-300"
          >
            设备 ↻
          </button>
          <div className="mt-2 space-y-0.5 max-h-36 overflow-auto">
            {drives.map((d) => (
              <button
                key={d.mountPoint}
                onClick={() => browseDrive(d.mountPoint)}
                className={`w-full text-left px-2 py-1.5 rounded text-xs flex items-center gap-1.5 ${
                  selectedDrive === d.mountPoint
                    ? "bg-emerald-900/30 text-emerald-300"
                    : "hover:bg-zinc-800/50 text-zinc-400"
                }`}
              >
                <span>{d.driveType === "removable" ? "💾" : "💿"}</span>
                <span className="truncate">{d.label}</span>
              </button>
            ))}
            {drives.length === 0 && (
              <p className="text-zinc-600 text-[11px] px-2">未检测到设备</p>
            )}
          </div>
        </div>

        {/* Folder tree */}
        <div className="flex-1 overflow-auto p-2">
          <h3 className="text-[10px] font-semibold text-zinc-500 uppercase px-1 mb-1">
            文件夹
          </h3>
          {browsing ? (
            <p className="text-[11px] text-emerald-500 px-1 animate-pulse">
              扫描目录结构...
            </p>
          ) : folderTree ? (
            <div>
              {/* Root "全部" */}
              <button
                onClick={() => loadFolder(folderTree.path)}
                className={`w-full text-left px-2 py-1 rounded text-[11px] mb-0.5 ${
                  activeFolder === folderTree.path
                    ? "bg-emerald-900/30 text-emerald-300"
                    : "text-zinc-400 hover:bg-zinc-800/50"
                }`}
              >
                全部{!counting || folderTree.photoCount > 0 ? ` (${folderTree.photoCount})` : ""}
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
              {selectedDrive ? "未扫描" : "选择设备"}
            </p>
          )}
        </div>

        <div className="p-2 border-t border-zinc-800 text-[10px] text-zinc-600">
          {browsing
            ? "浏览中..."
            : loadingFolder
            ? "加载中..."
            : counting
            ? "正在读取照片数..."
            : selectedDrive
            ? `${photos.length} 张`
            : "就绪"}
        </div>
      </aside>

      {/* === Center === */}
      <main className="flex-1 flex flex-col min-w-0">
        <div className="h-9 border-b border-zinc-800 flex items-center px-4 gap-2 flex-shrink-0 bg-zinc-950">
          {selectedDrive && photos.length > 0 && (
            <>
              <button onClick={selectAll} className="text-[10px] text-zinc-500 hover:text-zinc-300">全选</button>
              <button onClick={clearSelection} className="text-[10px] text-zinc-500 hover:text-zinc-300">取消</button>
              <span className="text-[10px] text-zinc-600">已选 {selectedPaths.size}/{photos.length}</span>
              {/* Sort */}
              <select value={sortBy} onChange={(e) => setSortBy(e.target.value as any)}
                className="bg-zinc-800 text-[10px] text-zinc-400 px-1 py-0.5 rounded border border-zinc-700">
                <option value="name">文件名</option>
                <option value="type">类型</option>
                <option value="date">日期</option>
              </select>
              {/* Star filter */}
              {[0,1,2,3,4,5].map((s) => (
                <button key={s} onClick={() => setStarFilter(starFilter === s ? 0 : s)}
                  className={`text-[10px] px-1 rounded ${starFilter === s ? "text-amber-400 bg-amber-400/10" : "text-zinc-600 hover:text-zinc-400"}`}
                >{s === 0 ? "全部" : "★".repeat(s)}</button>
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
                {analyzing ? "停止" : "AI 分析"}
              </button>
            </>
          )}
        </div>

        <div className="flex-1 overflow-auto p-3">
          {browsing || loadingFolder ? (
            <div className="flex items-center justify-center h-full">
              <div className="flex flex-col items-center gap-3">
                <div className="w-8 h-8 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
                <p className="text-zinc-500 text-sm">
                  {browsing ? "浏览目录..." : "加载照片..."}
                </p>
              </div>
            </div>
          ) : photos.length === 0 ? (
            <div className="flex items-center justify-center h-full">
              {selectedDrive ? (
                <p className="text-zinc-600 text-sm">
                  {activeFolder ? "此文件夹无照片" : "点击左侧文件夹查看照片"}
                </p>
              ) : (
                <WelcomeGuide />
              )}
            </div>
          ) : (
            <div className="grid photo-grid gap-2 content-start">
              {sortedPhotos.map((photo) => (
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
                  onClick={(e: React.MouseEvent) => {
                    handlePhotoClick(photo.path, { ctrlKey: e.ctrlKey, shiftKey: e.shiftKey });
                    setSelectedPhoto(photo);
                    if (!thumbnails[photo.path]) loadThumbnail(photo.path, 300);
                    loadExif(photo);
                  }}
                />
              ))}
            </div>
          )}
        </div>

        {/* Import bar */}
        <div className="border-t border-zinc-800 flex-shrink-0 bg-zinc-900">
          <div className="flex items-center gap-2 px-3 py-1.5">
            <button
              onClick={pickDestDir}
              className="text-[10px] px-2 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-400 truncate max-w-[180px]"
            >
              {destDir ? `...${destDir.slice(-25)}` : "选择目标文件夹"}
            </button>
            {destDir && (
              <button
                onClick={() => invoke("open_folder", { path: destDir })}
                className="text-[10px] px-1.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-500"
                title="打开文件夹"
              >
                📂
              </button>
            )}
            <div className="flex-1" />
            <span className="text-[10px] text-zinc-600">
              {!destDir ? "请先选目标文件夹" :
               selectedPaths.size === 0 ? "请勾选要导入的照片" :
               importing ? "导入中..." : ""}
            </span>
            <button
              disabled={!destDir || selectedPaths.size === 0 || importing}
              onClick={() => startImport([...selectedPaths])}
              className="text-[10px] px-3 py-1 rounded bg-emerald-600 hover:bg-emerald-500 disabled:bg-zinc-700 disabled:text-zinc-500 text-white font-medium"
            >
              {importing
                ? `导入中 ${importProgress.filter((p: {status: string}) => p.status === "done").length}/${selectedPaths.size}`
                : `导入 ${selectedPaths.size} 张`}
            </button>
          </div>
          {/* Advanced: naming rules */}
          {importError && (
            <div className="px-3 pb-1 text-[10px] text-red-400">错误: {importError}</div>
          )}
          {importResult && (
            <div className="px-3 pb-1 text-[10px] text-emerald-400">
              导入完成 ✓ {importResult.ok} 张成功{importResult.fail > 0 ? `，${importResult.fail} 张失败` : ""}
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
            <div className="px-3 pb-1.5 max-h-16 overflow-auto">
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

      {/* === Right Panel === */}
      <aside className="w-72 min-w-[18rem] border-l border-zinc-800 flex flex-col">
        <div className="p-3 border-b border-zinc-800">
          <h2 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
            详细信息
          </h2>
        </div>
        <div className="flex-1 overflow-auto p-3">
          {selectedPhoto ? (
            <ExifPanel photo={selectedPhoto} previewSrc={previewSrc} />
          ) : (
            <p className="text-zinc-600 text-xs text-center mt-8">
              选中照片查看 EXIF
            </p>
          )}
        </div>
      </aside>
    </div>
  );
}

/** Recursive folder tree item */
function FolderTreeItem({
  node,
  activeFolder,
  onSelect,
  depth,
  counting,
}: {
  node: FolderNode;
  activeFolder: string;
  onSelect: (path: string) => void;
  depth: number;
  counting: boolean;
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

function PhotoCard({
  photo, thumbnail, isSelected, isChecked, onClick, onToggle, analysis, rating, onRate,
}: {
  photo: ScannedPhoto; thumbnail?: string; isSelected: boolean; isChecked: boolean;
  onClick: (e: React.MouseEvent) => void; onToggle: (e: React.MouseEvent) => void;
  analysis?: { isBlurry?: boolean; isOverexposed?: boolean; isUnderexposed?: boolean; isBestInGroup?: boolean; duplicateGroup?: number };
  rating?: number; onRate?: (stars: number) => void;
}) {
  return (
    <div
      onClick={onClick}
      className={`relative aspect-square rounded-lg overflow-hidden cursor-pointer border-2 transition-all group ${
        isSelected
          ? "border-emerald-400 shadow-lg shadow-emerald-500/20"
          : "border-zinc-800 hover:border-zinc-600"
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
        {photo.isRaw && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-amber-600/80 text-white font-medium">RAW</span>
        )}
        {photo.isVideo && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-blue-600/80 text-white font-medium">视频</span>
        )}
        {analysis?.isBlurry && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-red-600/80 text-white font-medium">模糊</span>
        )}
        {analysis?.isOverexposed && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-yellow-600/80 text-white font-medium">过曝</span>
        )}
        {analysis?.isUnderexposed && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-indigo-600/80 text-white font-medium">欠曝</span>
        )}
        {analysis?.duplicateGroup !== undefined && !analysis?.isBestInGroup && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-gray-600/80 text-white font-medium">重复</span>
        )}
        {analysis?.isBestInGroup && (
          <span className="text-[9px] px-1.5 py-0.5 rounded bg-emerald-600/80 text-white font-medium">最佳</span>
        )}
      </div>
      {(rating ?? 0) > 0 && (
        <div className="absolute bottom-1.5 right-1.5 text-[10px] text-amber-400">
          {"★".repeat(rating ?? 0)}
        </div>
      )}
      <div className="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 to-transparent p-2 pt-6 opacity-0 group-hover:opacity-100 transition-opacity">
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

function ExifPanel({ photo, previewSrc }: { photo: ScannedPhoto; previewSrc: string | null }) {
  const { exif } = photo;
  return (
    <div className="space-y-4">
      {previewSrc && (
        <div className="aspect-square rounded-lg bg-zinc-800 overflow-hidden">
          <img src={previewSrc} alt={photo.fileName} className="w-full h-full object-cover" />
        </div>
      )}
      <Section title="文件信息">
        <Row label="文件名" value={photo.fileName} />
        <Row label="大小" value={formatBytes(photo.fileSize)} />
        <Row label="类型" value={photo.isRaw ? "RAW" : photo.isVideo ? "视频" : "图片"} />
      </Section>
      {(exif.cameraMake || exif.cameraModel) && (
        <Section title="相机">
          <Row label="品牌" value={exif.cameraMake} />
          <Row label="型号" value={exif.cameraModel} />
          <Row label="镜头" value={exif.lensModel} />
        </Section>
      )}
      {(exif.aperture || exif.shutterSpeed || exif.iso) && (
        <Section title="拍摄参数">
          <Row label="光圈" value={exif.aperture} />
          <Row label="快门" value={exif.shutterSpeed} />
          <Row label="ISO" value={exif.iso?.toString()} />
          <Row label="焦距" value={exif.focalLength} />
        </Section>
      )}
      {exif.dateTaken && (
        <Section title="日期">
          <p className="text-[11px] text-zinc-300">{exif.dateTaken}</p>
        </Section>
      )}
      {exif.imageWidth && (
        <Section title="尺寸">
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
  return (
    <input
      type="range" min={2} max={8} value={cols}
      onChange={(e) => setCols(Number(e.target.value))}
      className="w-16 h-4 accent-emerald-500 cursor-pointer"
      title={`${cols} 列`}
    />
  );
}

function WelcomeGuide() {
  return (
    <div className="max-w-md text-center space-y-6 p-8">
      <h1 className="text-2xl font-light text-zinc-300 tracking-wide">PixelFlow</h1>
      <p className="text-xs text-zinc-500">SD 卡照片智能导入工具</p>
      <div className="space-y-3 text-left">
        <Step num="1" title="插入 SD 卡" desc="插入相机存储卡，左栏自动检测设备" />
        <Step num="2" title="浏览照片" desc="点设备 → 文件夹树秒出 → 点文件夹查看照片" />
        <Step num="3" title="筛选/评分" desc="点 AI 分析检查废片，鼠标 hover 缩略图打星评分" />
        <Step num="4" title="导入电脑" desc="勾选照片 → 选目标文件夹 → 点导入" />
      </div>
      <div className="pt-4 border-t border-zinc-800 text-left text-[10px] text-zinc-600 space-y-1">
        <p><kbd className="px-1 bg-zinc-800 rounded text-zinc-400">J</kbd> 保留 <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">X</kbd> 废弃 <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">1-5</kbd> 星级</p>
        <p><kbd className="px-1 bg-zinc-800 rounded text-zinc-400">Ctrl+点击</kbd> 多选 <kbd className="px-1 bg-zinc-800 rounded text-zinc-400">Shift+点击</kbd> 范围选择</p>
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
        {open ? "▾ 高级选项" : "▸ 高级选项"}
      </button>
      {open && (
        <div className="mt-1 pb-1.5 space-y-1">
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={folderRule.includes("{date}")} onChange={toggleDate}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">按拍摄日期分文件夹</span>
            <span className="text-[9px] text-zinc-600">如 2024-08-08/照片.jpg</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={folderRule.includes("{camera}")} onChange={toggleCamera}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">按相机型号分文件夹</span>
            <span className="text-[9px] text-zinc-600">如 Sony-A7M4/照片.jpg</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={fileRule === "{seq}.{ext}"} onChange={toggleSeq}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">按序号重命名</span>
            <span className="text-[9px] text-zinc-600">如 0001.ARW</span>
          </label>
          <label className="flex items-center gap-1.5 cursor-pointer">
            <input type="checkbox" checked={useCustomFolder}
              onChange={() => setUseCustomFolder(!useCustomFolder)}
              className="w-3 h-3 accent-emerald-500" />
            <span className="text-[10px] text-zinc-400">导入到子文件夹</span>
            {useCustomFolder && (
              <input
                value={customFolder}
                onChange={(e) => setCustomFolder(e.target.value)}
                placeholder="输入文件夹名"
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
