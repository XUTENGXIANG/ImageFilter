import { useState, useCallback, useRef, useMemo } from "react";
import { invoke, convertFileSrc, Channel } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import i18n from "./i18n";
import type { DriveInfo, ScannedPhoto, FolderEntry, FolderNode, ImportProgress, AnalysisResult, FlagFilter, DecisionRead, DecisionWrite, WriteSummary, XmpProbe } from "./types";
import {
  EMPTY_HISTORY, applyLabelPatch, applyRatingPatch, applySelectionPatch, patchPath,
  popRedo, popUndo, pushPatch,
  type History, type Patch,
} from "./undo";
import { LABELS_STORAGE_KEY, readLabels, type Label } from "./labels";
import {
  XMP_MODE_STORAGE_KEY, XMP_QUEUE_LIMIT, buildDecisionWrite, dirOfPath, isDowngradeCandidate,
  mergeRemoteLabels, mergeRemoteRatings, readXmpMode,
  type XmpField, type XmpMode, type XmpNotice, type XmpStatus,
} from "./xmp";

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
  // Shift 范围选的锚点(上次点击的那张)。**故意用 ref 而不是 state**
  // (交接文档 §6 P2-6): 进依赖数组会让 handlePhotoClick 每次点击都换引用,
  // 卡片的 onToggle 随之换引用、双层 memo 全失效。
  // 只由 commitSelection 写、只由 handlePhotoClick 的 Shift 分支读, 都在事件处理器里,
  // 不参与渲染 —— 所以它**不许**出现在任何依赖数组里(放进去就等于退回 state)。
  const lastClickedRef = useRef<string | null>(null);
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

  // ═══ Phase 5 · XMP 边车(档位 / 待同步队列 / 合并 / 可写性探测) ═══════
  // 这一整块是"边车"的**唯一**入口: 想读盘/写盘/改档位都走这里。
  // 与 setRating / setLabel 并列在 hook 内(docs 会话 ④ 的 B 条): 组件里不许出现
  // setRatings/setLabels, 也不许直接 invoke("write_decisions")。
  const [xmpMode, setXmpModeState] = useState<XmpMode>(readXmpMode);
  // 档位镜像: setRating/setLabel 必须是**引用稳定**的回调(档位一旦进依赖数组, 每次改档
  // 都会让所有卡片的 onRate 换引用、双层 memo 失效 —— 交接 §6 P2-6), 所以一律读 ref。
  const xmpModeRef = useRef<XmpMode>(xmpMode);
  // ask 档: 弹窗挂起中 / 本会话是否已经问过(未作答也算"问过了", 下次启动再问)
  const [xmpAskPending, setXmpAskPending] = useState(false);
  const xmpAskAnsweredRef = useRef(false);
  // 待同步队列: path → 要写的**字段集合**。src/xmp.ts 不变式 3: 不存值 ——
  // 值在 flush 时从 ratingsRef/labelsRef 现读, 于是连按十次 Ctrl+Z 只写一次盘。
  const xmpQueueRef = useRef<Map<string, Set<XmpField>>>(new Map());
  const xmpTimerRef = useRef<number | undefined>(undefined);
  const xmpInFlightRef = useRef(false);
  // 代次: 切文件夹后旧读盘结果必须整体丢弃(与 countGenRef 同款纪律)
  const xmpGenRef = useRef(0);
  const xmpSeqRef = useRef(0);
  const xmpFailedRef = useRef<Set<string>>(new Set());
  const xmpNotifiedRef = useRef<Set<string>>(new Set());
  const xmpProbedRef = useRef<Map<string, XmpProbe>>(new Map());
  /** flush ↔ schedule 互相需要, 用 ref 打断(既不 TDZ 也不循环依赖) */
  const xmpFlushRef = useRef<() => void>(() => {});
  /** 当前文件夹(探测与降级提示都要知道"哪个目录不可写") */
  const activeFolderRef = useRef(activeFolder);
  /**
   * 提示/弹窗的**请求位**。
   * 为什么不用 state 直接更新: `queueXmpWrite` 可能在 setRating/setLabel/undo 的
   * updater 里被调用, 而 updater 是**渲染期**执行的(StrictMode 还跑两次) ——
   * 在渲染期 setState 是禁忌。所以先写 ref, 再由下面那段"渲染期搬运"转成 state
   * (React 官方允许的"渲染期调整状态", 条件是当场清空 ref → 必然收敛, 不会死循环)。
   */
  const xmpNoticeRef = useRef<XmpNotice | null>(null);
  const xmpAskWantedRef = useRef(false);
  /** 给 App 的一次性提示(文案在 App 里用 i18n 拼 —— hook 不该依赖 i18n, 会话 ② 定) */
  const [xmpNotice, setXmpNotice] = useState<XmpNotice | null>(null);
  /** 设置页常驻状态行(不能只靠 toast: "这张卡不可写"要能随时回看) */
  const [xmpStatus, setXmpStatus] = useState<XmpStatus | null>(null);

  // 渲染期搬运: ref 请求 → state(幂等收敛, 见上面的注释)
  if (xmpNoticeRef.current) {
    const n = xmpNoticeRef.current;
    xmpNoticeRef.current = null;
    setXmpNotice(n);
  }
  if (xmpAskWantedRef.current) {
    xmpAskWantedRef.current = false;
    setXmpAskPending(true);
  }

  /**
   * 一次性提示: 同一个 (kind|code|目录) 每会话只弹一次 ——
   * 否则"卡写保护"就变成每次评分都弹一次错, 那是骚扰(docs §5.3 的要求是"可见", 不是"刷屏")。
   */
  const notifyXmp = useCallback((kind: XmpNotice["kind"], code: string | null, n = 0) => {
    const key = `${kind}|${code ?? "-"}|${activeFolderRef.current}`;
    if (xmpNotifiedRef.current.has(key)) return;
    xmpNotifiedRef.current.add(key);
    xmpSeqRef.current += 1;
    xmpNoticeRef.current = { seq: xmpSeqRef.current, kind, code, n };
  }, []);

  /** 只改档位状态(不探测、不 flush)。单独抽出来是为了断开 probe ↔ setXmpMode 的循环引用 */
  const applyXmpMode = useCallback((m: XmpMode) => {
    xmpModeRef.current = m;
    setXmpModeState(m);
    try { localStorage.setItem(XMP_MODE_STORAGE_KEY, m); } catch {}
  }, []);

  /** off 的语义是"完全不碰卡": 丢掉待同步队列与失败标记(严格零写盘, 保持 v1.0.x 行为) */
  const dropXmpQueue = useCallback(() => {
    xmpQueueRef.current.clear();
    xmpFailedRef.current.clear();
    window.clearTimeout(xmpTimerRef.current);
    xmpTimerRef.current = undefined;
    setXmpStatus((prev) => (prev ? { ...prev, pending: 0 } : prev));
  }, []);

  /**
   * 原始探测(无副作用), 结果进缓存。抽出来是为了两个用途:
   * ① 档位切到 on / 打开文件夹时先确认可写; ② **写入失败后重新探测** ——
   * 单文件只读与整卷写保护是同一个错误码, 只能靠"目录现在还可写吗"来区分。
   */
  const probeXmpTargetRaw = useCallback(async (dir: string): Promise<XmpProbe | null> => {
    if (!dir) return null;
    try {
      const p = await invoke<XmpProbe>("probe_xmp_target", { dirPath: dir });
      xmpProbedRef.current.set(dir, p);
      return p;
    } catch (err) {
      console.error("probe_xmp_target:", err);
      return null;
    }
  }, []);

  /** 防抖调度(600ms)。已有定时器就复用 —— 连续操作只落一次盘 */
  const scheduleXmpFlush = useCallback((delay = 600) => {
    if (xmpModeRef.current !== "on") return;
    if (xmpTimerRef.current !== undefined) return;
    xmpTimerRef.current = window.setTimeout(() => {
      xmpTimerRef.current = undefined;
      void xmpFlushRef.current();
    }, delay);
  }, []);

  /** 入队(幂等: Set 去重 → StrictMode 双调用安全) */
  const enqueueXmp = useCallback((path: string, fields: XmpField[]) => {
    const q = xmpQueueRef.current;
    let set = q.get(path);
    if (!set) {
      if (q.size >= XMP_QUEUE_LIMIT) {
        // 丢最旧的: Map 保持插入顺序, 第一个 key 就是最旧的
        const oldest = q.keys().next().value as string | undefined;
        if (oldest !== undefined) q.delete(oldest);
        notifyXmp("overflow", null, XMP_QUEUE_LIMIT);
      }
      set = new Set<XmpField>();
      q.set(path, set);
    }
    for (const f of fields) set.add(f);
    // 用户又改了这张 → 解除"本会话不再自动重试"的标记(新值值得再试一次)
    xmpFailedRef.current.delete(path);
  }, [notifyXmp]);

  /**
   * 真正落盘: 一次 IPC 提交队列里的所有 path(撤销风暴会在这里被合并成一条)。
   * 单项失败不影响其它项; 只有"整卷不可写"才降级 off(见 src/xmp.ts::isVolumeFatal)。
   */
  const flushXmpQueue = useCallback(async () => {
    window.clearTimeout(xmpTimerRef.current);
    xmpTimerRef.current = undefined;
    if (xmpModeRef.current !== "on") return;
    if (xmpInFlightRef.current) return; // 单飞: finally 里若队列非空会再排一次
    const q = xmpQueueRef.current;
    if (q.size === 0) return;

    const items: DecisionWrite[] = [];
    for (const [path, fields] of q) {
      if (xmpFailedRef.current.has(path)) continue; // 本会话不再自动重试
      items.push(buildDecisionWrite(path, fields, ratingsRef.current, labelsRef.current));
    }
    q.clear();
    if (items.length === 0) {
      setXmpStatus((prev) => (prev ? { ...prev, pending: 0 } : prev));
      return;
    }

    xmpInFlightRef.current = true;
    try {
      const summary = await invoke<WriteSummary>("write_decisions", { items });
      const failures = summary?.failures ?? [];
      for (const f of failures) xmpFailedRef.current.add(f.path);
      if (failures.length > 0) notifyXmp("failed", failures[0].code, failures.length);
      const fatal = failures.find((f) => isDowngradeCandidate(f.code));
      if (fatal) {
        // ⚠️ **不能凭一次写入失败就降级**: 单个文件只读 / 单文件 ACL 与"整卷写保护"
        // 是同一个错误码(实机踩过: 把一个 .xmp 设成只读, 整个 on 档被关掉了, 而按
        // docs §6 的规矩"单文件问题不降级")。所以重新探测它所在的目录:
        // 探测说可写 → 只是那个文件的问题, 提示一次、档位不动;
        // 探测也说不可写 → 才是真的整目录不可写, 降级 off, 并把原因记进状态行
        // (否则用户只能在 4 秒的 toast 里瞥一眼原因)。
        const dir = dirOfPath(fatal.path);
        const p = await probeXmpTargetRaw(dir);
        if (p && !p.writable) {
          dropXmpQueue();
          applyXmpMode("off");
          setXmpStatus({ dir, writable: false, code: p.code ?? fatal.code, pending: 0, network: p.network, degraded: false });
          notifyXmp("downgraded", p.code ?? fatal.code, 0);
        }
      }
      setXmpStatus((prev) =>
        prev
          ? { ...prev, pending: xmpQueueRef.current.size, degraded: prev.degraded || !!summary?.degraded }
          : prev
      );
    } catch (err) {
      console.error("write_decisions:", err);
      notifyXmp("failed", "unknown", 0);
    } finally {
      xmpInFlightRef.current = false;
      if (xmpModeRef.current === "on" && xmpQueueRef.current.size > 0) scheduleXmpFlush(0);
    }
  }, [applyXmpMode, dropXmpQueue, notifyXmp, probeXmpTargetRaw, scheduleXmpFlush]);
  xmpFlushRef.current = flushXmpQueue;

  /** 探测目录可写性(每个目录每会话只真探一次)。不可写 → 立刻降级 off + 一次性提示 */
  const probeXmpTarget = useCallback(async (dir: string) => {
    if (!dir) return;
    const cached = xmpProbedRef.current.get(dir);
    const p = cached ?? (await probeXmpTargetRaw(dir));
    if (!p) return;
    setXmpStatus({ dir, writable: p.writable, code: p.code, pending: xmpQueueRef.current.size, network: p.network, degraded: false });
    if (!p.writable) {
      dropXmpQueue();
      applyXmpMode("off");
      notifyXmp("downgraded", p.code, 0);
    }
  }, [applyXmpMode, dropXmpQueue, notifyXmp, probeXmpTargetRaw]);

  /** 用户在设置里改档位 */
  const setXmpMode = useCallback((m: XmpMode) => {
    if (m === "off") {
      applyXmpMode("off");
      dropXmpQueue();
      setXmpStatus(null);
      return;
    }
    if (m === "ask") {
      xmpAskAnsweredRef.current = false; // 显式选了"询问" → 允许再问一次
      applyXmpMode("ask");
      return;
    }
    xmpAskAnsweredRef.current = true;
    applyXmpMode("on");
    void probeXmpTarget(activeFolderRef.current); // 先确认可写; 不可写会自己降级回 off
    scheduleXmpFlush(0); // 把"询问"挂起期间入队的条目立刻写掉
  }, [applyXmpMode, dropXmpQueue, probeXmpTarget, scheduleXmpFlush]);

  /**
   * 一次"可能会写盘"的改动入口 —— setRating / setLabel / undo / redo 都走这里。
   * off: 什么都不做(绝不碰卡); ask 未答: 请求弹一次窗; ask 已被忽略: 本会话不写。
   *
   * ⚠️ **只碰 ref**: 它会在 updater(渲染期)里被调用, 所以这里不许 setState
   * (弹窗走 xmpAskWantedRef + 渲染期搬运), 也不许有"读到旧 state 会写错值"的用法
   * (值到 flush 时才从镜像现读)。
   */
  const queueXmpWrite = useCallback((path: string, fields: XmpField[]) => {
    const mode = xmpModeRef.current;
    if (mode === "off") return;
    if (mode === "ask") {
      if (xmpAskAnsweredRef.current) return;
      // 本地**已经**写完, 弹窗只决定"要不要落盘" → 用户选"只写本机"时无需回滚任何东西
      xmpAskWantedRef.current = true;
      enqueueXmp(path, fields);
      return;
    }
    enqueueXmp(path, fields);
    scheduleXmpFlush();
  }, [enqueueXmp, scheduleXmpFlush]);

  /**
   * 回答 ask 档的弹窗。choice = "on"/"off"/null(直接关掉或按 Esc = 未作答):
   * 未作答时档位保持 ask, 但**本会话不再问**(下次启动再问)。
   */
  const resolveXmpAsk = useCallback((choice: "on" | "off" | null) => {
    // 已经作答过又收到一次"关闭"(受控对话框关闭时可能补一次 onOpenChange(false))
    // → 不要清掉刚刚入队的待写项。只有"真的没作答"才走忽略分支。
    if (choice === null && xmpAskAnsweredRef.current) {
      setXmpAskPending(false);
      return;
    }
    xmpAskAnsweredRef.current = true;
    setXmpAskPending(false);
    if (choice === "on") setXmpMode("on");
    else if (choice === "off") setXmpMode("off");
    else dropXmpQueue(); // 本会话忽略: 不写盘, 也不留脏队列
  }, [dropXmpQueue, setXmpMode]);

  /**
   * 合并结果落地(边车 → 本机 map)。**唯一允许"写 ratings/labels 却不记补丁"的路径**
   * (docs 会话 ④ 的 B 条):
   *  · 它是**外部真值校准**, 不是用户操作 —— 逐个 path 调 setRating 会为每张被合并的
   *    照片造一条补丁: 2000 张就把 100 深的撤销栈冲爆, 用户按 Ctrl+Z 会撤到"合并"这一步;
   *  · 它必然发生在 loadFolder 的 clearHistory() 之后、任何用户操作之前, 所以栈里
   *    不可能有引用"合并前值"的补丁 —— 这条不变式是它安全的前提。
   */
  const applyMergedRatings = useCallback((next: Record<string, number>) => {
    ratingsRef.current = next;
    try { localStorage.setItem("imagefilter-ratings", JSON.stringify(next)); } catch {}
    setRatings(next);
  }, []);

  const applyMergedLabels = useCallback((next: Record<string, Label>) => {
    labelsRef.current = next;
    try { localStorage.setItem(LABELS_STORAGE_KEY, JSON.stringify(next)); } catch {}
    setLabels(next);
  }, []);

  // ═══ Phase 2 · 撤销/重做(patch stack) ═══════════════════════════════
  // 栈放 useState 而不是 useRef: 文档建议的 useRef 与"导出 canUndo/canRedo"
  // 自相矛盾 —— ref 不触发渲染, 那两个值会永远是首次渲染的 false(谎报)。
  // {undo, redo} 作为**一个** state 原子更新, 避免两次 setState 把栈撕裂。
  const [history, setHistory] = useState<History>(EMPTY_HISTORY);
  const [lastUndoPath, setLastUndoPath] = useState<string | null>(null);

  // 补丁记录: 只由 setRating / 勾选三条路径 / setLabel 调用
  const recordPatch = useCallback((p: Patch) => {
    setHistory((prev) => pushPatch(prev, p));
  }, []);

  // ── Phase 4 · 颜色标签 ───────────────────────────────────────────────
  // 独立 localStorage key(不动 imagefilter-ratings); 清除 = 删键, 读取时丢非法值
  // —— 取舍见 src/labels.ts 文件头与 docs 4.1 / 4.5。
  const [labels, setLabels] = useState<Record<string, Label>>(readLabels);

  // 标签镜像: 与 ratingsRef 同款 —— 撤销要在同一 tick 内读到"当前"标签
  // (连按两次 Ctrl+Z 时读闭包 state 会两次拿到旧值、静默丢掉第二条补丁),
  // 同时让 setLabel 不必依赖 labels, 卡片 onLabel 引用稳定(双层 memo 不失效)。
  const labelsRef = useRef<Record<string, Label>>(labels);

  // 标签的**唯一写入点**(docs 前置约束 6): 无变化早退 → 记补丁 → 镜像 → 落盘。
  // 任何地方直接调 setLabels 或直接写 localStorage 都会让撤销漏掉这一步 / 镜像过期。
  const setLabel = useCallback((path: string, label: Label | null) => {
    // 无变化早退(与 updater 里那条双保险): 因为下面的入队写在 updater **之外**
    if ((labelsRef.current[path] ?? null) === label) return;
    setLabels((prev) => {
      // 无变化早退: 既避免多余渲染, 也让"Ctrl+2 长按连发"根本不产生补丁
      // (含"给一张没有标签的照片清标签"这种空操作)
      const before = prev[path] ?? null;
      if (before === label) return prev;
      recordPatch({ kind: "label", path, prev: before, next: label });
      const next = { ...prev };
      if (label === null) delete next[path]; else next[path] = label;
      // 副作用写在 updater 内是既有写法(localStorage 本来就在这里); StrictMode 会把
      // updater 调两次, 所以 pushPatch 必须做栈顶去重(见 src/undo.ts 不变式 1)。
      labelsRef.current = next;
      try { localStorage.setItem(LABELS_STORAGE_KEY, JSON.stringify(next)); } catch {}
      return next;
    });
    // Phase 5: 入队边车(off 档在 queueXmpWrite 里直接返回, 所以"绝不碰卡"仍然成立)
    queueXmpWrite(path, ["label"]);
  }, [recordPatch, queueXmpWrite]);

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
    // 锚点同步写(不触发渲染): 三个分支(Ctrl / Shift / 单击)都走这里,
    // 锚点都指向"本次点击的 path" —— 与改 ref 之前逐字节一致:
    // Shift 连点 = 从上一个末端延伸/回缩。
    lastClickedRef.current = path;
    recordPatch({ kind: "selection", paths: [...next], prev: prevPaths, next: [...next] });
  }, [recordPatch, selectPaths]);

  const handlePhotoClick = useCallback((path: string, event: { ctrlKey: boolean; shiftKey: boolean }) => {
    const photoPaths = photos.map((p) => p.path);
    if (event.ctrlKey) {
      // Ctrl+click: toggle single
      const next = new Set(selectedPathsRef.current);
      if (next.has(path)) next.delete(path); else next.add(path);
      commitSelection(next, path);
    } else if (event.shiftKey && lastClickedRef.current) {
      // Shift+click: 范围选。锚点先从 ref 取到局部变量再算 —— 必须**先读后写**,
      // 因为 commitSelection 会把锚点改成"本次点击的 path"。
      // 注意: 基准仍是 photos(扫描顺序), 不是 sortedPhotos(可见顺序),
      // 所以开着筛选/按日期排序时范围"看起来不对"是**既有行为**, Phase 4 不改它
      // (要改得把可见顺序从 App 透传进 hook, 属另一次重构; 见 docs 4.3 遗留)。
      const anchor = lastClickedRef.current;
      const start = photoPaths.indexOf(anchor);
      const end = photoPaths.indexOf(path);
      // 锚点已不在列表里(跨文件夹、或被筛掉) → 与改前一样不产生任何补丁
      if (start >= 0 && end >= 0) {
        const [from, to] = start < end ? [start, end] : [end, start];
        commitSelection(new Set(photoPaths.slice(from, to + 1)), path);
      }
    } else {
      // 单击: 切换勾选(累积) — 连续点击多张照片保持已勾选的
      // (Shift 但锚点为 null 时也落到这里: 退化成普通点击, 不是静默无操作)
      const next = new Set(selectedPathsRef.current);
      if (next.has(path)) next.delete(path); else next.add(path);
      commitSelection(next, path);
    }
  }, [photos, commitSelection]);

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
  // 分析结果镜像: runAnalysis 的增量合并要在"进入分析时"读到当前 map(见其注释),
  // 与 ratingsRef / selectedPathsRef / labelsRef 同款纪律。
  const analysisRef = useRef<Record<string, AnalysisResult>>(analysis);
  /** 分析结果的唯一写入口: 镜像与 state 一起更新, 不许分开写 */
  const commitAnalysis = useCallback((next: Record<string, AnalysisResult>) => {
    analysisRef.current = next;
    setAnalysis(next);
  }, []);
  // Ratings & sort
  const [ratings, setRatings] = useState<Record<string, number>>(() => {
    try { return JSON.parse(localStorage.getItem("imagefilter-ratings") || "{}"); }
    catch { return {}; }
  });
  const [sortBy, setSortBy] = useState<"name" | "type" | "date">("name");
  const [starFilter, setStarFilter] = useState(0); // 0=all, 1-5=filter
  // ── Phase 4 · 筛选三维度 + 排序方向 ──────────────────────────────────
  // 都是**纯视图状态**: 不落盘、不入撤销栈(与 starFilter 同待遇, docs 4.5)。
  // 语义: 星级 = ≥N星; 标签 = 空数组不过滤、多选命中任一; 分析 = all 不过滤,
  // 其余要求"有分析结果且命中"(没分析过的一律不算, 这就是"未分析"提示的存在理由)。
  const [labelFilter, setLabelFilter] = useState<Label[]>([]);
  const [flagFilter, setFlagFilter] = useState<FlagFilter>("all");
  // asc = "今天的观感"(name/type A→Z, date 新→旧), desc = 反转比较器。
  // 刻意不把 asc 理解成字面"旧→新": 否则第一次切到日期排序就会觉得排序反了(docs 4.5)。
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  // 评分镜像: 撤销/重做要在调用处同步读到"当前星级", 不能等 re-render
  // (理由见下面 undo 的注释); 同时它让 setRating 无需依赖 ratings,
  // 避免所有卡片的 onRate 换引用、双层 memo 失效。
  const ratingsRef = useRef<Record<string, number>>(ratings);

  const setRating = useCallback((path: string, stars: number) => {
    // 无变化早退(与下层 updater 里那条双保险): 因为下面的边车入队写在 updater **之外**
    if ((ratingsRef.current[path] ?? 0) === stars) return;
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
    // Phase 5: 入队边车。**必须在 updater 之外** —— updater 是渲染期跑的(StrictMode 还跑
    // 两次), 在里面调 setState(弹窗/状态)就变成渲染期更新。队列是 Set, 重复入队无副作用;
    // 值到 flush 时才现读, 所以"这里读到稍旧的镜像"也不会写错值。
    queueXmpWrite(path, ["rating"]);
  }, [recordPatch, queueXmpWrite]);

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
        // Phase 5: **撤销/重做也要回写边车**(docs 会话 ④ 的 A 条) —— 否则下一次读盘
        // (或 Lightroom)会把撤掉的值复活, 用户看到"我明明撤了它自己又回来了"。
        // 与普通修改走同一条队列: 连撤十次会被合并成一次写(队列只存 path + 字段),
        // 所以这里不需要任何节流。放在 updater 内是安全的 —— queueXmpWrite 只碰 ref。
        queueXmpWrite(patch.path, ["rating"]);
      } else if (patch.kind === "label") {
        // 标签回放: 与评分同款 —— 写镜像 + 落盘 + setState, 三步一起(漏一步就"只退了 UI")
        const next$ = applyLabelPatch(labelsRef.current, patch);
        if (next$ !== labelsRef.current) {
          labelsRef.current = next$;
          try { localStorage.setItem(LABELS_STORAGE_KEY, JSON.stringify(next$)); } catch {}
          setLabels(next$);
        }
        queueXmpWrite(patch.path, ["label"]);
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
  }, [queueXmpWrite]);

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
        queueXmpWrite(patch.path, ["rating"]); // 同 undo: 重做也要落到边车
      } else if (patch.kind === "label") {
        // 重做 = 把 prev/next 对调后回放(生成新补丁, 不改栈里那份 —— 不变式 3)
        const next$ = applyLabelPatch(labelsRef.current, { kind: "label", path: patch.path, prev: patch.next, next: patch.prev });
        if (next$ !== labelsRef.current) {
          labelsRef.current = next$;
          try { localStorage.setItem(LABELS_STORAGE_KEY, JSON.stringify(next$)); } catch {}
          setLabels(next$);
        }
        queueXmpWrite(patch.path, ["label"]);
      } else {
        const next$ = applySelectionPatch(selectedPathsRef.current, { kind: "selection", paths: patch.paths, prev: patch.next, next: patch.prev });
        if (next$ !== selectedPathsRef.current) selectPaths(next$);
      }
      return next;
    });
    const target = applied ? patchPath(applied) : null;
    if (target) setLastUndoPath(target);
    return applied;
  }, [queueXmpWrite]);

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

  // ── Phase 4 · 颜色标签的修饰键(Ctrl / Alt) ───────────────────────────
  // key imagefilter-label-modifier: 只有显式 "alt" 才算 Alt, 缺省/损坏一律当 Ctrl
  // —— 与 autoAdvance 同款防御写法(别改成 useLocalStorageSetting: 那个 hook
  // 对字符串虽能用, 但这里要的是"读取时兜底", 与既有两处开关保持一致)。
  // 为什么可配: Tauri 的 WebView2 可能把 Ctrl+数字 / Ctrl+0 当浏览器加速键吃掉,
  // 那样用户不用等改代码就能切到 Alt(docs 4.1)。
  const [labelModifier, setLabelModifierState] = useState<"ctrl" | "alt">(() => {
    try { return localStorage.getItem("imagefilter-label-modifier") === "alt" ? "alt" : "ctrl"; }
    catch { return "ctrl"; }
  });
  const setLabelModifier = useCallback((v: "ctrl" | "alt") => {
    setLabelModifierState(v);
    try { localStorage.setItem("imagefilter-label-modifier", v); } catch {}
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
    xmpGenRef.current += 1; // Phase 5: 换设备 → 作废在途的边车读取
    activeFolderRef.current = "";
    lastClickedRef.current = null; // 换列表 → Shift 锚点作废(同 loadFolder, 见 docs 4.3)
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
    // Phase 5: 作废在途的边车读取(旧结果一律丢弃), 并记住当前目录(探测/降级提示要用)
    xmpGenRef.current += 1;
    activeFolderRef.current = folderPath;
    setLoadingFolder(true);
    setActiveFolder(folderPath);
    allowAssetDir(folderPath); // asset 协议按需放行该文件夹
    setPhotos([]);
    setThumbnails({});
    setSelectedPhoto(null);
    selectPaths(new Set()); // 必须走 wrapper: 否则选择集镜像会留着上一个文件夹的勾选
    // Shift 锚点也作废: 旧路径不在新列表里, 留着只会让 Shift 点击变成"没反应"
    // (改 ref 之前 lastClicked 状态同样跨文件夹残留 —— 顺手修掉这个死点击)
    lastClickedRef.current = null;

    try {
      const [photosList, subEntry] = await Promise.all([
        invoke<ScannedPhoto[]>("scan_directory", { dirPath: folderPath }),
        invoke<FolderEntry>("browse_directory", { dirPath: folderPath }).catch(() => null),
      ]);

      setPhotos(photosList);

      // Phase 5 · 边车 → 本地 合并(docs §5.2): 必须在 scan_directory **之后**、
      // 渲染之前完成。加载态(spinner)盖住这段时间, 用户看不到星级"跳一下";
      // 放到 effect 里则会先按旧值算一遍 sortedPhotos(≥N★ 筛选), 网格先错再改。
      // 只有 on 档才读卡(ask 未答前不碰卡; off 绝不碰卡)。
      if (xmpModeRef.current === "on" && photosList.length > 0) {
        const myGen = xmpGenRef.current;
        try {
          const remotes = await invoke<DecisionRead[]>("read_decisions", {
            filePaths: photosList.map((p) => p.path),
          });
          // 代次不符 = 用户已经切到别的文件夹 → 整体丢弃(含"回写 localStorage")
          if (myGen === xmpGenRef.current) {
            const r = mergeRemoteRatings(ratingsRef.current, remotes);
            if (r.changed.length > 0) applyMergedRatings(r.map);
            const l = mergeRemoteLabels(labelsRef.current, remotes);
            if (l.changed.length > 0) applyMergedLabels(l.map);
          }
        } catch (err) {
          console.error("read_decisions:", err);
        }
      }

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
    // Phase 5: on 档下每次打开文件夹都确认一次目标可写(每个目录每会话只真探一次)。
    // 不放在 browseDrive(浏览设备树)里: 那时还没有照片, 无谓地碰卡。
    if (xmpModeRef.current === "on") void probeXmpTarget(folderPath);
  }, [applyMergedLabels, applyMergedRatings, probeXmpTarget]);

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

    // Phase 4 · 增量合并(docs 4.4-B): 只丢掉**本次要分析的这些 path**的旧结果,
    // 其余照片的分析结果原样保留。
    // 旧写法 setAnalysis({}) 会让"勾 3 张只分析这 3 张"变成"把其余几百张的结果抹掉":
    // 徽标全消失、未分析计数跳到总数 —— 与"分析只作用于选区"直接冲突。
    // 用 analysisRef 而不是闭包里的 analysis: 后者在同一渲染周期内是旧值。
    const target = new Set(paths);
    const results: Record<string, AnalysisResult> = {};
    for (const [p, r] of Object.entries(analysisRef.current)) {
      if (!target.has(p)) results[p] = r;
    }
    commitAnalysis({ ...results });

    // Step 1: blur + exposure (streaming)
    const onProgress = new Channel<AnalysisResult>();
    onProgress.onmessage = (r: AnalysisResult) => {
      results[r.path] = r;
      commitAnalysis({ ...results });
    };
    await invoke("analyze_photos", { filePaths: paths, onProgress }).catch(console.error);

    // Step 2: duplicate detection
    // 注意(已有语义, 不改 Rust): 判重只在**本次传入的集合内**进行,
    // 所以选区分析时"重复"只在选区内成立、"最佳"也可能与全量结果不同。
    try {
      const dups = await invoke<AnalysisResult[]>("find_duplicates", { filePaths: paths });
      for (const d of dups) {
        if (d.duplicateGroup !== undefined) {
          results[d.path] = { ...(results[d.path] || {} as AnalysisResult), ...d };
        }
      }
      commitAnalysis({ ...results });
    } catch (err) { console.error("find_duplicates:", err); }

    setAnalyzing(false);
  }, [commitAnalysis]);

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
    // Phase 4: 颜色标签 + 三个筛选维度 + 排序方向 + 标签修饰键
    labels, setLabel, labelFilter, setLabelFilter, flagFilter, setFlagFilter,
    sortDir, setSortDir, labelModifier, setLabelModifier,
    // Phase 2: canUndo/canRedo 已导出, 但当前 UI(帮助文案)只写静态说明, 暂未消费
    // —— 若要加"撤销"按钮/置灰状态, 直接用这两个布尔值即可(它们随栈变化重渲染)
    undo, redo, canUndo, canRedo, lastUndoPath,
    pickDestDir, startImport, preloadFull, togglePreloadFull,
    autoAdvance, toggleAutoAdvance,
    // Phase 5: XMP 边车 —— 档位 / ask 弹窗 / 设置页状态行 / 一次性提示。
    // 队列、flush、探测都是内部实现, **不导出**(组件里不许直接 invoke write_decisions)
    xmpMode, setXmpMode, xmpAskPending, resolveXmpAsk, xmpStatus, xmpNotice,
  };
}
