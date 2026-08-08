import { useState, useCallback } from "react";
import { invoke, convertFileSrc, Channel } from "@tauri-apps/api/core";
import type { DriveInfo, ScannedPhoto, FolderEntry } from "./types";

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
  };
}
