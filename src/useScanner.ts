import { useState, useCallback } from "react";
import { invoke, convertFileSrc, Channel } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import type { DriveInfo, ScannedPhoto, FolderEntry } from "./types";

export interface ImportProgress {
  fileName: string;
  status: string;
  message: string;
  percent: number;
}

export interface FolderNode {
  name: string;
  path: string;
  children: FolderNode[];
  photoCount: number;
  hasSubdirs: boolean;
}

function entryToNode(entry: FolderEntry): FolderNode {
  return {
    name: entry.name,
    path: entry.path,
    photoCount: entry.photoCount,
    hasSubdirs: entry.hasSubdirs,
    children: entry.subfolders.map(entryToNode),
  };
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

  // Multi-select state
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(new Set());

  const toggleSelect = useCallback((path: string) => {
    setSelectedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  }, []);

  const selectAll = useCallback(() => {
    setSelectedPaths(new Set(photos.map((p) => p.path)));
  }, [photos]);

  const clearSelection = useCallback(() => {
    setSelectedPaths(new Set());
  }, []);

  // Import state
  const [importing, setImporting] = useState(false);
  const [importProgress, setImportProgress] = useState<{ fileName: string; status: string; message: string }[]>([]);
  const [importError, setImportError] = useState<string | null>(null);
  const [destDir, setDestDir] = useState<string | null>(null);
  const [folderRule, setFolderRule] = useState("");
  const [fileRule, setFileRule] = useState("");
  const [customFolder, setCustomFolder] = useState("");
  const [useCustomFolder, setUseCustomFolder] = useState(false);
  const [importResult, setImportResult] = useState<{ok: number; fail: number} | null>(null);

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
    setPhotos([]);
    setThumbnails({});
    setSelectedPhoto(null);
    setFolderTree(null);
    setActiveFolder("");

    try {
      const entry = await invoke<FolderEntry>("browse_directory", { dirPath: mountPoint });
      const root: FolderNode = {
        name: "全部", path: mountPoint, photoCount: entry.photoCount,
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
        setFolderTree((prev) =>
          mergeChildren(prev, folderPath, subEntry.subfolders.map(entryToNode))
        );
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
    const dir = await open({ directory: true, title: "选择导入目标文件夹" });
    if (dir) setDestDir(dir as string);
    return dir;
  }, []);

  /** Start importing selected or all photos */
  const startImport = useCallback(async (paths: string[]) => {
    if (!destDir || paths.length === 0) return;
    setImporting(true);
    setImportProgress([]);

    const onProgress = new Channel<ImportProgress>();
    onProgress.onmessage = (p: ImportProgress) => {
      setImportProgress((prev) => [...prev, p]);
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
  }, [destDir, folderRule, fileRule]);

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

  const loadThumbnail = useCallback(
    async (filePath: string, size = 300) => {
      if (thumbnails[filePath]) return thumbnails[filePath];
      try {
        const diskPath = await invoke<string>("get_thumbnail_path", { filePath, maxSize: size });
        const assetUrl = convertFileSrc(diskPath);
        setThumbnails((prev) => ({ ...prev, [filePath]: assetUrl }));
        return assetUrl;
      } catch {
        setThumbnails((prev) => ({ ...prev, [filePath]: "__err__" }));
        return null;
      }
    },
    [thumbnails]
  );

  return {
    drives, selectedDrive, folderTree, activeFolder, photos,
    selectedPhoto, thumbnails, browsing, loadingFolder, counting,
    detectDrives, browseDrive, loadFolder, loadThumbnail, loadExif, setSelectedPhoto,
    importing, importProgress, importError, importResult, destDir,
    selectedPaths, toggleSelect, selectAll, clearSelection,
    folderRule, fileRule, setFolderRule, setFileRule,
    customFolder, setCustomFolder, useCustomFolder, setUseCustomFolder,
    pickDestDir, startImport,
  };
}
