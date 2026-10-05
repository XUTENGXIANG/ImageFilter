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
  "launchFailed",
  "notSupported",
  "notImplemented",
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

/** send_to_lightroom 失败时前端要展示的东西 */
export interface LrcNotice {
  /** 单调递增序号: 同样的失败连续发生两次也要能再触发一次提示 */
  seq: number;
  code: LrcErrCode;
}

/**
 * 成功提示的文案参数。刻意把"漏掉了别的文件夹"显式带出来 ——
 * 否则用户会以为选中的 20 张全进对话框了, 而实际只发了其中 12 张所在的目录。
 */
export interface LrcSentInfo {
  /** 已交给 LrC 的文件夹 */
  folder: string;
  /** 该文件夹里被选中的照片数 */
  count: number;
  /** 选中的照片还散布在其它 N 个文件夹(0 = 全部都在同一个文件夹里) */
  alsoInOtherFolders: number;
  /** true = 没有勾选, 发的是"当前浏览的文件夹" */
  fromActiveFolder: boolean;
}
