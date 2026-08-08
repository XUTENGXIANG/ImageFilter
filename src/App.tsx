import { useEffect, useState } from "react";
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
    toggleSelect,
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
              <button onClick={selectAll} className="text-[10px] text-zinc-500 hover:text-zinc-300">
                全选
              </button>
              <button onClick={clearSelection} className="text-[10px] text-zinc-500 hover:text-zinc-300">
                取消
              </button>
              <span className="text-[10px] text-zinc-600">
                已选 {selectedPaths.size}/{photos.length}
              </span>
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
              <p className="text-zinc-600 text-sm">
                {selectedDrive
                  ? activeFolder
                    ? "此文件夹无照片"
                    : "点击左侧文件夹查看照片"
                  : "选择设备后点击文件夹"}
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-4 gap-2 content-start">
              {photos.map((photo) => (
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
                  onToggle={() => toggleSelect(photo.path)}
                  onClick={() => {
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
          {canExpand ? (open ? "▼" : "▶") : "📁"}
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
  photo,
  thumbnail,
  isSelected,
  isChecked,
  onClick,
  onToggle,
}: {
  photo: ScannedPhoto;
  thumbnail?: string;
  isSelected: boolean;
  isChecked: boolean;
  onClick: () => void;
  onToggle: () => void;
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
        onClick={(e) => { e.stopPropagation(); onToggle(); }}
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
      </div>
      <div className="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 to-transparent p-2 pt-6 opacity-0 group-hover:opacity-100 transition-opacity">
        <p className="text-[10px] text-zinc-200 truncate leading-tight">{photo.fileName}</p>
        <p className="text-[9px] text-zinc-400">{formatBytes(photo.fileSize)}</p>
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
