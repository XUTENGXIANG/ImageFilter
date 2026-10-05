// ═══════════════════════════════════════════════════════════════════
// Phase 7 · Lightroom Classic 衔接的纯逻辑层(零 React / 零 Tauri 依赖)
//
// 与 src/xmp.ts 同款纪律: 组件只消费结果, 规则都写在这里; useScanner 负责 invoke。
//
// 两条不变式(改动前先读):
// 1. **"传给 LrC 什么"只由 selectedPaths + photos + activeFolder 决定**,
//    且必须是**单个文件夹** —— 实测只验证过 `Lightroom.exe "<文件夹>"`;
//    传多个路径的行为没有证据, 所以这里的返回值只有一个 string。
// 2. **多文件夹不猜、不静默丢**: 选中的照片跨文件夹时取"包含最多选中照片"的那个,
//    并用 alsoInOtherFolders 把"还有别的文件夹被漏掉"如实告诉调用方。
//    (静默只发一个 = 用户以为全发了, 这是最坏的一种正确性外观。)
//
// Rust 侧对应 src-tauri/src/lightroom.rs 的 LIGHTROOM_ERR_CODES 契约。
// ═══════════════════════════════════════════════════════════════════

/** 与 Rust LrcError::code() 一一对应的闭集(那边有单测钉住字符串) */
export const LRC_ERR_CODES = [
  "notFound",
  "noFolder",
  /**
   * Lightroom 已在运行 —— Adobe 会**忽略**这时传进去的路径参数, 导入对话框停在
   * 上一次的源上。所以必须冷启动: 让用户先关掉 LrC, 或由用户明确选择"强制关闭"。
   * 依据见 src-tauri/src/lightroom.rs 的 LrcError::AlreadyRunning。
   */
  "alreadyRunning",
  "launchFailed",
  "notSupported",
  "notImplemented",
  "noNewPhotos",
  "unknown",
] as const;

export type LrcErrCode = (typeof LRC_ERR_CODES)[number];

/** 未知码落到 unknown(不让脏值进 t()) */
export function lrcErrKey(code: string | null | undefined): LrcErrCode {
  return (LRC_ERR_CODES as readonly string[]).includes(code ?? "")
    ? (code as LrcErrCode)
    : "unknown";
}

// ── 发送模式(设置里切换) ────────────────────────────────────────────
//
// "dialog"  = 模式 2: 打开 LrC 的导入对话框(本 Phase 实现, 实机验证过)
// "silent"  = 模式 1: 把文件送进 LrC 的"自动导入"监听文件夹(需要用户先在
//             LrC 里配好; 本机实测**从未配过**, 所以这条路径尚未实现)
//
// 缺省 = "dialog"。理由: 它是唯一不需要用户任何前置配置的模式,
// 而 "silent" 在没配监听文件夹时是一条死路(会得到一句"还没实现"而不是静默失败)。

export type LrcSendMode = "dialog" | "silent";

export const LRC_MODE_STORAGE_KEY = "imagefilter-lrc-mode";

/** 只认显式 "silent"; 其余(缺省/损坏/拼错)一律 "dialog" —— 与 readXmpMode 同款防御 */
export function readLrcMode(): LrcSendMode {
  try {
    return localStorage.getItem(LRC_MODE_STORAGE_KEY) === "silent" ? "silent" : "dialog";
  } catch {
    return "dialog";
  }
}

export function writeLrcMode(m: LrcSendMode): void {
  try {
    localStorage.setItem(LRC_MODE_STORAGE_KEY, m);
  } catch {}
}

/**
 * 当前档位是否**可真正执行**。
 *
 * "silent" 是留给模式 1(送进 LrC 的自动导入监听文件夹)的位置, 尚未实现, 且
 * 没有实测证据支持任何实现方式(本机 LrC 从未配过监听文件夹)。与其给一个点了
 * 只会报错的入口, 不如让 UI 明确禁用 + 说明原因。
 * 这里集中一处判断 —— 设置页与 useScanner 都读它, 免得两边各写一份"哪个能用"。
 */
export function isLrcModeUsable(m: LrcSendMode): boolean {
  return m === "dialog";
}

// ── 路径工具 ────────────────────────────────────────────────────────
//
// 注意: 项目里已有 xmp.ts::dirOfPath(那份是权威, 不许再写第三份)。这里**不**重复实现,
// 由调用方传入或从 xmp.ts 导入。本文件只保留"挑选文件夹"的纯决策。

/**
 * 从选中路径里挑出要交给 LrC 的那**一个**文件夹。
 *
 * 规则(全部可单测):
 *   · 只看选中的; 选中为空 → 回落 activeFolder
 *   · 跨文件夹时取"出现次数最多"的; 次数相同取路径字典序小的(确定性, 不依赖 Map 顺序)
 *   · 返回值里的 count 是"要发出去的那个文件夹里, 被选中的照片数"
 */
export interface FolderPick {
  /** 要传给 LrC 的文件夹; 没有可用值时 null */
  folder: string | null;
  /** 该文件夹里有几张是被选中的 */
  count: number;
  /** 选中的照片还散布在其它几个文件夹里(>0 时 UI 必须提示) */
  alsoInOtherFolders: number;
  /** true = 没有选中, folder 来自 activeFolder 回落 */
  fromActiveFolder: boolean;
}

/**
 * 从选中路径里挑出要交给 LrC 的那一个文件夹。
 *
 * @param selectedPaths 已勾选的源文件完整路径(顺序即勾选顺序)
 * @param activeFolder  当前浏览的文件夹(选中为空时的回落)
 * @param dirOf         取父目录的函数(由调用方注入 xmp.ts::dirOfPath, 避免两份实现)
 */
export function pickFolderForLightroom(
  selectedPaths: readonly string[],
  activeFolder: string,
  dirOf: (p: string) => string
): FolderPick {
  if (selectedPaths.length === 0) {
    return {
      folder: activeFolder || null,
      count: 0,
      alsoInOtherFolders: 0,
      fromActiveFolder: true,
    };
  }

  // 计数(显式排序保证确定性: 不依赖 Map 的插入顺序)
  const tally = new Map<string, number>();
  for (const p of selectedPaths) {
    const dir = dirOf(p);
    if (!dir) continue;
    tally.set(dir, (tally.get(dir) ?? 0) + 1);
  }
  if (tally.size === 0) {
    return {
      folder: activeFolder || null,
      count: 0,
      alsoInOtherFolders: 0,
      fromActiveFolder: true,
    };
  }

  const entries = [...tally.entries()].sort((a, b) =>
    b[1] !== a[1] ? b[1] - a[1] : a[0].localeCompare(b[0])
  );
  const [folder, count] = entries[0];
  return {
    folder,
    count,
    alsoInOtherFolders: entries.length - 1,
    fromActiveFolder: false,
  };
}

// ── "导入到哪 + 交给谁" 的决策(核心) ─────────────────────────────────
//
// 需求(用户原话, 会话 ⑥ 定): 点「导入 LrC」后
//   1. 没选目标文件夹 → 先弹窗让用户选;
//   2. 有目标文件夹 → 先把选中的照片**导入**到那里(原文件逐字节复制);
//   3. 再打开 LrC 的导入页面, 页面上**只有刚导入的那些照片**;
//   4. 用户在 LrC 里点一次「导入」即可。
//
// 第 3 条是这个功能的难点: LrC 的导入页面会列出**它拿到的那个文件夹的全部内容**。
// 所以:
//   · 目标文件夹**是空的** → 直接导进去, 把目标文件夹交给 LrC(里面正好只有这批);
//   · 目标文件夹**已有东西** → 导进一个新建的子文件夹, 把**子文件夹**交给 LrC。
// 这样"页面上只有选中的照片"是确定成立的, 不用赌 LrC 的判断。

/** 子文件夹名前缀。用户可据此一眼认出是 ImageFilter 建的。 */
export const LRC_STAGE_PREFIX = "ImageFilter";

/**
 * 生成子文件夹名(带时间戳, 便于多批共存且可读)。
 *
 * 为什么不用 `_` 开头: Windows 资源管理器把下划线开头的项排在最前, 反而更显眼;
 * 而这个名字是要给用户看的(他会去里面挪文件), 清楚比短更重要。
 */
export function stageFolderName(now: Date): string {
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return (
    LRC_STAGE_PREFIX +
    "_" +
    now.getFullYear() +
    p(now.getMonth() + 1) +
    p(now.getDate()) +
    "_" +
    p(now.getHours()) +
    p(now.getMinutes())
  );
}

/** 拼接子文件夹路径(不依赖 Node 的 path 模块, 前端要能在浏览器里跑) */
function joinPath(dir: string, name: string): string {
  const trimmed = dir.replace(/[\\/]+$/, "");
  // 注意: 分隔符必须在**剥掉末尾分隔符之前**判断 —— 否则 `D:\` 会退化成 "D",
  // 判成"没有反斜杠"从而拼出 `D/ImageFilter_...`(单测里就是这么被抓出来的)。
  const sep = /\\/.test(dir) ? "\\" : "/";
  return trimmed + sep + name;
}

export interface LrcImportPlan {
  /** 真正要导入到的目录(即最终交给 LrC 的那个) */
  destDir: string;
  /** true = 目标文件夹非空, destDir 是新建的子文件夹 */
  staged: boolean;
  /** 子文件夹的完整路径(staged=false 时为 null) */
  stageDir: string | null;
}

/**
 * 依据"目标文件夹是否为空"决定导入去向。
 *
 * @param destDir      用户选的目标文件夹
 * @param destIsEmpty  Rust 侧 is_dir_empty 的结果
 * @param now          注入当前时间(纯函数, 便于单测)
 */
export function planLrcImport(
  destDir: string,
  destIsEmpty: boolean,
  now: Date
): LrcImportPlan {
  if (destIsEmpty) {
    return { destDir, staged: false, stageDir: null };
  }
  const stageDir = joinPath(destDir, stageFolderName(now));
  return { destDir: stageDir, staged: true, stageDir };
}

/**
 * 「导入到 LrC」这条链当前走到哪一步 —— 只用于界面反馈。
 *
 * 为什么要分步: 整条链是"**导入**(可能要几十秒) → **启动 Lightroom**(又要几十秒,
 * 大目录库更久)"。中间那段黑屏期用户看不到任何反馈, 会以为按钮没反应而重复点。
 * 所以把两步分开报告, 让 UI 能说清"现在在干什么"。
 */
export type LrcPhase = "idle" | "importing" | "launching";

/** 交给 LrC 失败时前端要展示的东西 */
export interface LrcNotice {
  /** 单调递增序号: 同样的失败连续发生两次也要能再触发一次提示 */
  seq: number;
  code: LrcErrCode;
  /** 可选计数(noNewPhotos 用: "选中的 N 张都已经在里面了") */
  n: number;
}

/**
 * 成功提示的文案参数。
 *
 * 一定要说清"是否另建了子文件夹": 用户导完要去那个子文件夹里挪文件,
 * 不告诉他路径等于活干了一半。
 */
export interface LrcSentInfo {
  /** 交给 LrC 的文件夹(可能就是目标文件夹, 也可能是新建的子文件夹) */
  folder: string;
  /** 本次真正导入进去的照片数(不含跳过) */
  count: number;
  /** true = 目标文件夹非空, folder 是新建的子文件夹 */
  staged: boolean;
}
