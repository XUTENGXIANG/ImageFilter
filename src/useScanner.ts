import { useState, useCallback } from "react";
import { invoke, convertFileSrc, Channel } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import i18n from "./i18n";
import type { DriveInfo, ScannedPhoto, FolderEntry, FolderNode, ImportProgress, AnalysisResult } from "./types";

function entryToNode(entry: FolderEntry): FolderNode {
  return {
    name: entry.name,
    path: entry.path,
    photoCount: entry.photoCount,
    hasSubdirs: entry.hasSubdirs,
    children: entry.subfolders.map(entryToNode),
  };
}

/** 扩展 asset 协议访问范围（assetProtocol.scope 已收紧为空, 浏览时按需放行） */
function allowAssetDir(dir: string) {
  invoke("allow_asset_dir", { dirPath: dir }).catch(() => {});
}

function updateHasSubdirs(root: FolderNode | null, path: string, val: boolean): FolderNode | null {
  if (!root) return null;
  if (root.path === path) return { ...root, hasSubdirs: val };
  return { ...root, children: root.children.map((c) => updateHasSubdirs(c, path, val)!).filter(Boolean) };
}

function applyCounts(root: FolderNode | null, counts: Record<string, number>): FolderNode | null {
  if (!root) return null;
  return {
    ...root,
    photoCount: counts[root.path] ?? root.photoCount,
    children: root.children.map((c) => applyCounts(c, counts)!).filter(Boolean),
  };
}

function mergeChildren(
  root: FolderNode | null,
  parentPath: string,
  children: FolderNode[]
): FolderNode | null {
  if (!root) return null;
  if (root.path === parentPath) {
    const existingPaths = new Set(children.map((c) => c.path));
    const kept = root.children.filter((c) => !existingPaths.has(c.path));
    return { ...root, children: [...kept, ...children] };
  }
  return {
    ...root,
    children: root.children.map((c) => mergeChildren(c, parentPath, children)!).filter(Boolean),
  };
}

export function useScanner() {
  const [drives, setDrives] = useState<DriveInfo[]>([]);
  const [selectedDrive, setSelectedDrive] = useState<string | null>(null);
  const [folderTree, setFolderTree] = useState<FolderNode | null>(null);
  const [activeFolder, setActiveFolder] = useState<string>("");
  const [photos, setPhotos] = useState<ScannedPhoto[]>([]);
  const [selectedPhoto, setSelectedPhoto] = useState<ScannedPhoto | null>(null);
  const [thumbnails, setThumbnails] = useState<Record<string, string>>({});
  const [browsing, setBrowsing] = useState(false);
  const [loadingFolder, setLoadingFolder] = useState(false);
  const [counting, setCounting] = useState(false);

  // Multi-select state (Windows Explorer style)
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(new Set());
  const [lastClicked, setLastClicked] = useState<string | null>(null);

  const handlePhotoClick = useCallback((path: string, event: { ctrlKey: boolean; shiftKey: boolean }) => {
    const photoPaths = photos.map((p) => p.path);
    if (event.ctrlKey) {
      // Ctrl+click: toggle single
      setSelectedPaths((prev) => {
        const next = new Set(prev);
        if (next.has(path)) next.delete(path); else next.add(path);
        return next;
      });
      setLastClicked(path);
    } else if (event.shiftKey && lastClicked) {
      // Shift+click: select range
      const start = photoPaths.indexOf(lastClicked);
      const end = photoPaths.indexOf(path);
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        const range = new Set(photoPaths.slice(from, to + 1));
        setSelectedPaths(range);
      }
    } else {
      // 单击: 切换勾选(累积) — 连续点击多张照片保持已勾选的
      setSelectedPaths((prev) => {
        const next = new Set(prev);
        if (next.has(path)) next.delete(path); else next.add(path);
        return next;
      });
      setLastClicked(path);
    }
  }, [photos, lastClicked]);

  const selectAll = useCallback(() => {
    setSelectedPaths(new Set(photos.map((p) => p.path)));
  }, [photos]);

  const clearSelection = useCallback(() => {
    setSelectedPaths(new Set());
  }, []);

  // Import state
  const [importing, setImporting] = useState(false);
  const [importProgress, setImportProgress] = useState<ImportProgress[]>([]);
  const [importDone, setImportDone] = useState(0);
  const [importError, setImportError] = useState<string | null>(null);
  // AI analysis
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState<Record<string, AnalysisResult>>({});
  // Ratings & sort
  const [ratings, setRatings] = useState<Record<string, number>>(() => {
    try { return JSON.parse(localStorage.getItem("imagefilter-ratings") || "{}"); }
    catch { return {}; }
  });
  const [sortBy, setSortBy] = useState<"name" | "type" | "date">("name");
  const [starFilter, setStarFilter] = useState(0); // 0=all, 1-5=filter

  const setRating = useCallback((path: string, stars: number) => {
    setRatings((prev) => {
      const next = { ...prev, [path]: stars };
      try { localStorage.setItem("imagefilter-ratings", JSON.stringify(next)); } catch {}
      return next;
    });
  }, []);
  const [destDir, setDestDir] = useState<string | null>(null);
  const [folderRule, setFolderRule] = useState("");
  const [fileRule, setFileRule] = useState("");
  const [customFolder, setCustomFolder] = useState("");
  const [useCustomFolder, setUseCustomFolder] = useState(false);
  const [importResult, setImportResult] = useState<{ok: number; fail: number} | null>(null);

  // 可见区域全图预加载开关（App 中由 IntersectionObserver 触发）
  const [preloadFull, setPreloadFull] = useState(() => {
    try { return localStorage.getItem("imagefilter-preload-full") === "true"; } catch { return false; }
  });
  const togglePreloadFull = useCallback(() => {
    setPreloadFull((prev) => {
      const next = !prev;
      try { localStorage.setItem("imagefilter-preload-full", String(next)); } catch {}
      return next;
    });
  }, []);

  const detectDrives = useCallback(async () => {
    try {
      const list = await invoke<DriveInfo[]>("detect_drives");
      setDrives(list);
    } catch (err) {
      console.error("detect_drives failed:", err);
    }
  }, []);

  const browseDrive = useCallback(async (mountPoint: string) => {
    setBrowsing(true);
    setSelectedDrive(mountPoint);
    allowAssetDir(mountPoint); // asset 协议按需放行该设备
    setPhotos([]);
    setThumbnails({});
    setSelectedPhoto(null);
    setFolderTree(null);
    setActiveFolder("");

    try {
      const entry = await invoke<FolderEntry>("browse_directory", { dirPath: mountPoint });
      const root: FolderNode = {
        name: i18n.t("devices.root"), path: mountPoint, photoCount: entry.photoCount,
        hasSubdirs: entry.hasSubdirs, children: entry.subfolders.map(entryToNode),
      };
      setFolderTree(root);

      // Background: count folder photos
      const folderPaths = entry.subfolders.map((f) => f.path);
      if (folderPaths.length > 0) {
        setCounting(true);
        invoke<Record<string, number>>("count_folders", { folderPaths })
          .then((map) => {
            setFolderTree((prev) => applyCounts(prev, map));
            setCounting(false);
          })
          .catch((err) => {
            console.error("count_folders:", err);
            setCounting(false);
          });
      }
    } catch (err) {
      console.error("browse_directory failed:", err);
    } finally {
      setBrowsing(false);
    }
  }, []);

  const loadFolder = useCallback(async (folderPath: string) => {
    setLoadingFolder(true);
    setActiveFolder(folderPath);
    allowAssetDir(folderPath); // asset 协议按需放行该文件夹
    setPhotos([]);
    setThumbnails({});
    setSelectedPhoto(null);
    setSelectedPaths(new Set());

    try {
      const [photosList, subEntry] = await Promise.all([
        invoke<ScannedPhoto[]>("scan_directory", { dirPath: folderPath }),
        invoke<FolderEntry>("browse_directory", { dirPath: folderPath }).catch(() => null),
      ]);

      setPhotos(photosList);

      if (photosList.length > 0) {
        const paths = photosList.map((p) => p.path);
        const onProgress = new Channel<[string, string]>();
        onProgress.onmessage = ([src, diskPath]: [string, string]) => {
          setThumbnails((prev) => ({ ...prev, [src]: convertFileSrc(diskPath) }));
        };
        invoke("batch_thumbnails", { filePaths: paths, maxSize: 300, onProgress })
          .catch((err) => console.error("batch_thumbnails:", err));
      }

      if (subEntry) {
        const hasKids = subEntry.subfolders.length > 0;
        setFolderTree((prev) => {
          let tree = mergeChildren(prev, folderPath, subEntry.subfolders.map(entryToNode));
          tree = updateHasSubdirs(tree, folderPath, hasKids);
          return tree;
        });
      }
    } catch (err) {
      console.error("loadFolder failed:", err);
    } finally {
      setLoadingFolder(false);
    }
  }, []);

  /** Load EXIF on demand when user selects a photo */
  /** Pick destination folder */
  const pickDestDir = useCallback(async () => {
    const dir = await open({ directory: true, title: i18n.t("import.pickDestTitle") });
    if (dir) {
      setDestDir(dir as string);
      allowAssetDir(dir as string); // asset 协议按需放行目标目录
    }
    return dir;
  }, []);

  /** Start importing selected or all photos */
  const startImport = useCallback(async (paths: string[]) => {
    if (!destDir || paths.length === 0) return;
    setImporting(true);
    setImportProgress([]);
    setImportDone(0);

    const onProgress = new Channel<ImportProgress>();
    onProgress.onmessage = (p: ImportProgress) => {
      if (p.status === "done") setImportDone((n) => n + 1);
      // 只保留最近 100 条, 避免大导入时数组/重渲染无限增长
      setImportProgress((prev) => {
        const next = prev.length >= 100 ? prev.slice(prev.length - 99) : prev;
        return [...next, p];
      });
    };

    try {
      const count = await invoke<number>("import_photos", {
        filePaths: paths,
        destDir,
        folderTemplate: folderRule,
        fileTemplate: fileRule,
        customFolder: useCustomFolder ? customFolder : "",
        onProgress,
      });
      setImportError(null);
      const failed = paths.length - count;
      setImportResult({ ok: count, fail: failed });
      setTimeout(() => setImportResult(null), 5000);
    } catch (err: any) {
      console.error("import failed:", err);
      setImportError(String(err));
    } finally {
      setImporting(false);
    }
  }, [destDir, folderRule, fileRule, customFolder, useCustomFolder]);

  /** Stop ongoing analysis */
  const stopAnalysis = useCallback(() => {
    setAnalyzing(false);
    invoke("stop_analysis"); // fire-and-forget
  }, []);

  /** AI analysis: blur + exposure + duplicates */
  const runAnalysis = useCallback(async (paths: string[]) => {
    if (paths.length === 0) return;
    setAnalyzing(true);
    setAnalysis({});

    const results: Record<string, AnalysisResult> = {};

    // Step 1: blur + exposure (streaming)
    const onProgress = new Channel<AnalysisResult>();
    onProgress.onmessage = (r: AnalysisResult) => {
      results[r.path] = r;
      setAnalysis({ ...results });
    };
    await invoke("analyze_photos", { filePaths: paths, onProgress }).catch(console.error);

    // Step 2: duplicate detection
    try {
      const dups = await invoke<AnalysisResult[]>("find_duplicates", { filePaths: paths });
      for (const d of dups) {
        if (d.duplicateGroup !== undefined) {
          results[d.path] = { ...(results[d.path] || {} as AnalysisResult), ...d };
        }
      }
      setAnalysis({ ...results });
    } catch (err) { console.error("find_duplicates:", err); }

    setAnalyzing(false);
  }, []);

  const loadExif = useCallback(async (photo: ScannedPhoto) => {
    if (photo.exif.cameraMake || photo.exif.dateTaken) return photo;
    try {
      const exif = await invoke<ScannedPhoto["exif"]>("get_exif", { filePath: photo.path });
      const enriched = { ...photo, exif };
      setPhotos((prev) => prev.map((p) => (p.path === photo.path ? enriched : p)));
      setSelectedPhoto((prev) => (prev?.path === photo.path ? enriched : prev));
      return enriched;
    } catch {
      return photo;
    }
  }, []);

  // 稳定版本(无 thumbnails 依赖): 每次调用都会查后端, 但后端有磁盘缓存,
  // 命中时立即返回, 不会重复解码; setThumbnails 幂等更新避免多余重渲染
  const loadThumbnail = useCallback(
    async (filePath: string, size = 300) => {
      try {
        const diskPath = await invoke<string>("get_thumbnail_path", { filePath, maxSize: size });
        const assetUrl = convertFileSrc(diskPath);
        setThumbnails((prev) => (prev[filePath] ? prev : { ...prev, [filePath]: assetUrl }));
        return assetUrl;
      } catch {
        setThumbnails((prev) => (prev[filePath] ? prev : { ...prev, [filePath]: "__err__" }));
        return null;
      }
    },
    []
  );

  return {
    drives, selectedDrive, folderTree, activeFolder, photos,
    selectedPhoto, thumbnails, browsing, loadingFolder, counting,
    detectDrives, browseDrive, loadFolder, loadThumbnail, loadExif, setSelectedPhoto,
    importing, importProgress, importDone, importError, importResult, destDir,
    selectedPaths, handlePhotoClick, selectAll, clearSelection,
    folderRule, fileRule, setFolderRule, setFileRule,
    customFolder, setCustomFolder, useCustomFolder, setUseCustomFolder,
    analyzing, analysis, runAnalysis, stopAnalysis,
    ratings, setRating, sortBy, setSortBy, starFilter, setStarFilter,
    pickDestDir, startImport, preloadFull, togglePreloadFull,
  };
}
