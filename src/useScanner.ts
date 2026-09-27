import { useState, useCallback, useRef, useMemo } from "react";
import { invoke, convertFileSrc, Channel } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import i18n from "./i18n";
import type { DriveInfo, ScannedPhoto, FolderEntry, FolderNode, ImportProgress, AnalysisResult } from "./types";
import {
  EMPTY_HISTORY, applyRatingPatch, applySelectionPatch, patchPath,
  popRedo, popUndo, pushPatch,
  type History, type Patch,
} from "./undo";

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
  // 选择集镜像: 撤销/重做与 clearSelection 要在不新增 useCallback 依赖的前提下读当前值
  // (加进依赖数组会让所有卡片的 onToggle 引用变化、双层 memo 失效 — 交接 §6 P2-6)。
  const selectedPathsRef = useRef<Set<string>>(selectedPaths);

  // 选择集的**唯一**写入 wrapper: 同步镜像 + 触发渲染。
  // 引用恒定(useCallback([]) + setState 引用稳定), 所以不会给任何回调引入新依赖。
  // 任何地方直接调 setSelectedPaths 都会让镜像过期 → 补丁的 prev 记错。
  const selectPaths = useCallback((next: Set<string>) => {
    selectedPathsRef.current = next;
    setSelectedPaths(next);
  }, []);

  // 后台计数请求代次 — 只有最新一次设备切换的计数结果才允许写回(见 browseDrive)
  const countGenRef = useRef(0);

  // ═══ Phase 2 · 撤销/重做(patch stack) ═══════════════════════════════
  // 栈放 useState 而不是 useRef: 文档建议的 useRef 与"导出 canUndo/canRedo"
  // 自相矛盾 —— ref 不触发渲染, 那两个值会永远是首次渲染的 false(谎报)。
  // {undo, redo} 作为**一个** state 原子更新, 避免两次 setState 把栈撕裂。
  const [history, setHistory] = useState<History>(EMPTY_HISTORY);
  const [lastUndoPath, setLastUndoPath] = useState<string | null>(null);

  // 补丁记录: 只由 setRating / 勾选三条路径调用
  const recordPatch = useCallback((p: Patch) => {
    setHistory((prev) => pushPatch(prev, p));
  }, []);

  // 切文件夹 / 切设备必须清空两个栈(文档 Phase 2 关键陷阱): 否则会把 A 文件夹的
  // 评分/勾选撤销到 B 文件夹的展示上(勾选尤其危险: loadFolder 里已清空 selectedPaths,
  // 跨文件夹撤销会凭空造出一份选择)。必须**同步**调用, 不能放 effect ——
  // 切换途中按 Ctrl+Z 不能落到新列表上。
  const clearHistory = useCallback(() => {
    setHistory(EMPTY_HISTORY);
    setLastUndoPath(null);
  }, []);

  // 勾选变更的唯一提交口: 同步镜像 + 记补丁, 一步都不能漏
  // (漏了镜像 → 撤销读到旧选择集; 漏了补丁 → 该次勾选撤不回来)
  const commitSelection = useCallback((next: Set<string>, path: string) => {
    const prevPaths = [...selectedPathsRef.current];
    selectPaths(next);
    setLastClicked(path);
    recordPatch({ kind: "selection", paths: [...next], prev: prevPaths, next: [...next] });
  }, [recordPatch, selectPaths]);

  const handlePhotoClick = useCallback((path: string, event: { ctrlKey: boolean; shiftKey: boolean }) => {
    const photoPaths = photos.map((p) => p.path);
    if (event.ctrlKey) {
      // Ctrl+click: toggle single
      const next = new Set(selectedPathsRef.current);
      if (next.has(path)) next.delete(path); else next.add(path);
      commitSelection(next, path);
    } else if (event.shiftKey && lastClicked) {
      // Shift+click: select range
      const start = photoPaths.indexOf(lastClicked);
      const end = photoPaths.indexOf(path);
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        commitSelection(new Set(photoPaths.slice(from, to + 1)), path);
      }
    } else {
      // 单击: 切换勾选(累积) — 连续点击多张照片保持已勾选的
      const next = new Set(selectedPathsRef.current);
      if (next.has(path)) next.delete(path); else next.add(path);
      commitSelection(next, path);
    }
  }, [photos, lastClicked, commitSelection]);

  const selectAll = useCallback(() => {
    const all = photos.map((p) => p.path);
    const prevPaths = [...selectedPathsRef.current];
    const next = new Set(all);
    selectPaths(next);
    // 全选不移动 lastClicked 锚点(保持现状行为)
    recordPatch({ kind: "selection", paths: all, prev: prevPaths, next: all });
  }, [photos, recordPatch, selectPaths]);

  const clearSelection = useCallback(() => {
    const prevPaths = [...selectedPathsRef.current];
    const next = new Set<string>();
    selectPaths(next);
    recordPatch({ kind: "selection", paths: [], prev: prevPaths, next: [] });
  }, [recordPatch, selectPaths]);

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

  // 评分镜像: 撤销/重做要在调用处同步读到"当前星级", 不能等 re-render
  // (理由见下面 undo 的注释); 同时它让 setRating 无需依赖 ratings,
  // 避免所有卡片的 onRate 换引用、双层 memo 失效。
  const ratingsRef = useRef<Record<string, number>>(ratings);

  const setRating = useCallback((path: string, stars: number) => {
    setRatings((prev) => {
      const before = prev[path] ?? 0;
      // 无变化早退: 既避免多余渲染, 也让"连点同一颗星"根本不产生补丁
      // (viewer 星条第二次点击会传 0, 这里挡住的是真正的重复赋值)
      if (before === stars) return prev;
      recordPatch({ kind: "rating", path, prev: before, next: stars });
      const next = { ...prev, [path]: stars };
      // 副作用写在 updater 内是既有写法(localStorage 本来就在这里), 但 React 19
      // StrictMode(main.tsx 有 <React.StrictMode>)会把 updater 调两次 —— 所以
      // pushPatch 必须做栈顶去重, 且去重判据只比较稳定原语(见 src/undo.ts 文件头)。
      ratingsRef.current = next;
      try { localStorage.setItem("imagefilter-ratings", JSON.stringify(next)); } catch {}
      return next;
    });
  }, [recordPatch]);

  // 撤销 / 重做: 只回放补丁, 评分仍汇流到 setRating 的同一份 localStorage key
  //
  // 为什么要用 ratingsRef 而不是闭包里的 ratings: React 的 state 更新是异步的,
  // 同一 tick 内连按两次 Ctrl+Z(keydown 连发)若从闭包里的 ratings 求逆, 两次会读到
  // 同一份旧值 —— 第二条补丁被静默吞掉, 用户看到"按了没反应"。
  // 镜像在 updater 内赋值, 与既有的 localStorage.setItem 同款写法
  // (StrictMode 双调用只是幂等写两次同一个值, 且 applied 只被赋同一个补丁)。
  const undo = useCallback((): Patch | null => {
    let applied: Patch | null = null;
    setHistory((prev) => {
      const { patch, history: next } = popUndo(prev);
      if (!patch) return prev;
      applied = patch;
      if (patch.kind === "rating") {
        const next$ = applyRatingPatch(ratingsRef.current, patch);
        if (next$ !== ratingsRef.current) {
          ratingsRef.current = next$;
          try { localStorage.setItem("imagefilter-ratings", JSON.stringify(next$)); } catch {}
          setRatings(next$);
        }
      } else {
        const next$ = applySelectionPatch(selectedPathsRef.current, patch);
        if (next$ !== selectedPathsRef.current) selectPaths(next$);
      }
      return next;
    });
    // 让查看器能回到"被撤销的那张"(自动前进可能已经把用户带到下一张了);
    // 用 state 而不是 ref: 它要在同一次提交里被 App 作为 prop 传给查看器
    const target = applied ? patchPath(applied) : null;
    if (target) setLastUndoPath(target);
    return applied;
  }, []);

  const redo = useCallback((): Patch | null => {
    let applied: Patch | null = null;
    setHistory((prev) => {
      const { patch, history: next } = popRedo(prev);
      if (!patch) return prev;
      applied = patch;
      // 重做 = 把补丁的 prev/next 对调后再回放(生成新对象, 不改动栈里那份)
      if (patch.kind === "rating") {
        const next$ = applyRatingPatch(ratingsRef.current, { kind: "rating", path: patch.path, prev: patch.next, next: patch.prev });
        if (next$ !== ratingsRef.current) {
          ratingsRef.current = next$;
          try { localStorage.setItem("imagefilter-ratings", JSON.stringify(next$)); } catch {}
          setRatings(next$);
        }
      } else {
        const next$ = applySelectionPatch(selectedPathsRef.current, { kind: "selection", paths: patch.paths, prev: patch.next, next: patch.prev });
        if (next$ !== selectedPathsRef.current) selectPaths(next$);
      }
      return next;
    });
    const target = applied ? patchPath(applied) : null;
    if (target) setLastUndoPath(target);
    return applied;
  }, []);

  const canUndo = useMemo(() => history.undo.length > 0, [history]);
  const canRedo = useMemo(() => history.redo.length > 0, [history]);

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

  // 评分后自动前进（默认开）。与 preloadFull 同款：父组件持有状态、立即写 localStorage。
  // key 用 imagefilter-auto-advance：只有显式 "false" 才算关, 缺省/损坏一律当开。
  // 注意不要改成 useLocalStorageSetting<boolean>: 那个 hook 用 String(v) 存、原样读回字符串,
  // "false" 是真值 → 开关会永远关不掉。
  const [autoAdvance, setAutoAdvance] = useState(() => {
    try { return localStorage.getItem("imagefilter-auto-advance") !== "false"; } catch { return true; }
  });
  const toggleAutoAdvance = useCallback(() => {
    setAutoAdvance((prev) => {
      const next = !prev;
      try { localStorage.setItem("imagefilter-auto-advance", String(next)); } catch {}
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
    clearHistory(); // 切设备清空撤销栈(见 clearHistory 注释)
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
      // 只有最新一次切换的结果才允许写回: 连续切换设备时旧请求会晚到,
      // 若直接 applyCounts 会把已经换掉的设备树覆盖成旧数据。
      // (真正省 CPU 的是后端: count_folders 内部有代次取消 + 固定/网络盘跳过递归计数)
      const folderPaths = entry.subfolders.map((f) => f.path);
      if (folderPaths.length > 0) {
        const myGen = ++countGenRef.current;
        setCounting(true);
        invoke<Record<string, number>>("count_folders", { folderPaths })
          .then((map) => {
            if (myGen !== countGenRef.current) return; // 已被后续切换取代
            setFolderTree((prev) => applyCounts(prev, map));
            setCounting(false);
          })
          .catch((err) => {
            console.error("count_folders:", err);
            if (myGen === countGenRef.current) setCounting(false);
          });
      }
    } catch (err) {
      console.error("browse_directory failed:", err);
    } finally {
      setBrowsing(false);
    }
  }, []);

  const loadFolder = useCallback(async (folderPath: string) => {
    clearHistory(); // 切文件夹清空撤销栈(见 clearHistory 注释), 必须在第一个 await 之前
    setLoadingFolder(true);
    setActiveFolder(folderPath);
    allowAssetDir(folderPath); // asset 协议按需放行该文件夹
    setPhotos([]);
    setThumbnails({});
    setSelectedPhoto(null);
    selectPaths(new Set()); // 必须走 wrapper: 否则选择集镜像会留着上一个文件夹的勾选

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
    // Phase 2: canUndo/canRedo 已导出, 但当前 UI(帮助文案)只写静态说明, 暂未消费
    // —— 若要加"撤销"按钮/置灰状态, 直接用这两个布尔值即可(它们随栈变化重渲染)
    undo, redo, canUndo, canRedo, lastUndoPath,
    pickDestDir, startImport, preloadFull, togglePreloadFull,
    autoAdvance, toggleAutoAdvance,
  };
}
