// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//
// 断言 `src/xmp.ts` 的「服务端路径 → 桌面端键空间」映射与「未落盘不放行」判据。
//
// 为什么要有这个脚本: 这条映射是一份**字符串契约**, 用眼睛看不出来。而它错了不会报错,
// 只会让手机改的星级/色标在电脑上不刷新(live-update 通道存在的唯一理由), 同时往
// imagefilter-ratings / imagefilter-labels 里为每张被手机碰过的照片攒一个带 `\\?\`
// 前缀的重复键。成因: 服务端每个端点都先 `ensure_within`(内部 canonicalize)再回话
// (src-tauri/src/server/paths.rs:16-19), Windows 上 canonicalize 给出的是 verbatim
// 路径(实测 rustc 1.97.1: `A:\tenent` → `\\?\A:\tenent`); 桌面端自己的键却是朴素路径
// (src-tauri/src/scanner/drives.rs:159 的 `format!("{}:\\", letter)`,
// browse.rs:26-34 的 `entry.path()`)。
//
// 跑法(项目既有约定: 仓库自带 esbuild 转译 + Node, 不引 vitest):
//   node docs/evidence/scripts/desktop-key-map.cjs
// 退出码: 0 = 全绿; 1 = 有断言失败。
const path = require("path");
const esbuild = require("esbuild");

// ── 取纯函数: src/xmp.ts 零 React / 零 Tauri 依赖, 所以能整份 bundle 后在 Node 里跑 ──
const ROOT = path.resolve(__dirname, "..", "..", "..");
const built = esbuild.buildSync({
  entryPoints: [path.join(ROOT, "src", "xmp.ts")],
  bundle: true,
  platform: "node",
  format: "cjs",
  write: false,
  logLevel: "silent",
});
const mod = { exports: {} };
new Function("module", "exports", "require", built.outputFiles[0].text)(mod, mod.exports, require);
const { toDesktopKey, remapRemoteDecisions, mergeRemoteRatings } = mod.exports;
if (typeof toDesktopKey !== "function" || typeof remapRemoteDecisions !== "function" || typeof mergeRemoteRatings !== "function") {
  console.error("FATAL: src/xmp.ts 里找不到 toDesktopKey / remapRemoteDecisions / mergeRemoteRatings");
  process.exit(1);
}

// ── 断言器 ──────────────────────────────────────────────────────────
/** 比较: 走 JSON 字面量, 于是字符串/数字/数组/对象都用同一套(字符串也逐字符精确) */
function same(got, want) {
  return JSON.stringify(got) === JSON.stringify(want);
}
/** 带注入收集器的断言器: 真实断言与"自检"共用同一个实现, 免得自检测的是另一套逻辑 */
function makeCheck(collector, tag) {
  const t = tag || "";
  return function check(label, got, want) {
    if (same(got, want)) {
      collector.ok++;
      console.log("  ok    " + t + label + "\n          → " + JSON.stringify(got));
      return true;
    }
    collector.bad.push(label + ": got " + JSON.stringify(got) + ", want " + JSON.stringify(want));
    console.log("  FAIL  " + t + label + "\n          got  " + JSON.stringify(got) + "\n          want " + JSON.stringify(want));
    return false;
  };
}
const probe = { ok: 0, bad: [] };
const check = makeCheck(probe);

// ── 自检: 这个断言器**真的会红** ────────────────────────────────────
// 拿一个显然错的期望喂给它, 必须被记成失败(而不是被静默放过)。否则下面全绿毫无意义
// —— 这一条也顺手把"必须不匹配的反例"钉在了断言器自己身上。
const self = { ok: 0, bad: [] };
makeCheck(self, "[自检·这里出现 FAIL 是预期的] ")("反例: C:\\a.jpg ≠ \\\\?\\C:\\a.jpg", "C:\\a.jpg", "\\\\?\\C:\\a.jpg");
if (self.bad.length !== 1 || self.ok !== 0) {
  console.error("FATAL: 断言器失灵(故意写错的期望没有被记为失败), 本脚本的绿色不可信");
  process.exit(1);
}
console.log("自检: 断言器能判否 —— 故意写错的期望被记为失败 (bad=" + self.bad.length + ", ok=" + self.ok + ")");
console.log("");

console.log("── toDesktopKey: 服务端路径 → 桌面端键 ─────────────────────");
const MAP = [
  // [说明, 输入, 期望]
  [String.raw`verbatim 盘符路径(服务端 canonicalize 的典型输出)`, "\\\\?\\C:\\Users\\me\\Pictures\\a.jpg", "C:\\Users\\me\\Pictures\\a.jpg"],
  [String.raw`verbatim 盘符根(桌面端设备树的根就是 X:\)`, "\\\\?\\A:\\", "A:\\"],
  [String.raw`verbatim UNC → 朴素 UNC(\\?\UNC\srv\share ↔ \\srv\share)`, "\\\\?\\UNC\\nas\\photos\\2024\\a.jpg", "\\\\nas\\photos\\2024\\a.jpg"],
  [String.raw`verbatim UNC 只剩 server\share 两级`, "\\\\?\\UNC\\nas\\photos", "\\\\nas\\photos"],
  ["朴素路径(盘符 + 目录 + 文件): 原样不动", "C:\\Users\\me\\Pictures\\a.jpg", "C:\\Users\\me\\Pictures\\a.jpg"],
  [String.raw`朴素 UNC(\\nas\photos\a.jpg): 原样不动`, "\\\\nas\\photos\\a.jpg", "\\\\nas\\photos\\a.jpg"],
  ["相对路径(非 Windows / 不该被碰): 原样不动", "photos/a.jpg", "photos/a.jpg"],
  [String.raw`幂等: 映射过一遍的结果(\\?\A:\tenent\a.jpg)再映射还是它自己`, toDesktopKey("\\\\?\\A:\\tenent\\a.jpg"), "A:\\tenent\\a.jpg"],
  // ── 必须**不**匹配(改动即为误伤; 这几条让脚本具备"变红"的能力) ──
  [String.raw`中间含 ?\ 但不是前缀: 不动`, "C:\\photos\\?\\a.jpg", "C:\\photos\\?\\a.jpg"],
  [String.raw`\\?\Volume{GUID} 既不是盘符也不是 UNC: 原样退回`, "\\\\?\\Volume{9f0c0e2a-0000-0000-0000-100000000000}\\a.jpg", "\\\\?\\Volume{9f0c0e2a-0000-0000-0000-100000000000}\\a.jpg"],
  [String.raw`\\?\UNC\server 少一级 share: 不成 UNC, 原样退回`, "\\\\?\\UNC\\nas", "\\\\?\\UNC\\nas"],
  [String.raw`只有前缀 \\?\: 原样退回`, "\\\\?\\", "\\\\?\\"],
  [String.raw`前缀后面是相对路径(\\?\photos\a.jpg, 非绝对): 原样退回`, "\\\\?\\photos\\a.jpg", "\\\\?\\photos\\a.jpg"],
  [String.raw`verbatim 里不允许正斜杠(\\?\C:/a.jpg 是畸形路径): 原样退回`, "\\\\?\\C:/ph/a.jpg", "\\\\?\\C:/ph/a.jpg"],
];
for (const [label, input, want] of MAP) check(label, toDesktopKey(input), want);

console.log("");
console.log("── remapRemoteDecisions: 事件 → 桌面键 + 未落盘不放行 ──────");
const ev = (p, rating, label) => ({ path: p, rating, label, sidecar: p.replace(/\.[^.]*$/, ".xmp") });
const evA = ev("\\\\?\\C:\\ph\\a.jpg", 4, null);
const evB = ev("\\\\?\\C:\\ph\\b.jpg", 2, "red");
const evC = ev("C:\\ph\\c.jpg", 5, "green"); // 朴素形式(理论上不该出现, 但必须原样放行)

const all = remapRemoteDecisions([evA, evB, evC]);
check("三条都映射到朴素键(顺序不变)", JSON.stringify(all.map((x) => x.path)), JSON.stringify(["C:\\ph\\a.jpg", "C:\\ph\\b.jpg", "C:\\ph\\c.jpg"]));
check("值原样带过来(rating)", JSON.stringify(all.map((x) => x.rating)), JSON.stringify([4, 2, 5]));
check("值原样带过来(label)", JSON.stringify(all.map((x) => x.label)), JSON.stringify([null, "red", "green"]));
check("不改写入参(纯函数)", evA.path, "\\\\?\\C:\\ph\\a.jpg");

const dropped = remapRemoteDecisions([evA, evB, evC], (k) => k === "C:\\ph\\b.jpg");
check("待落盘的那条被挡掉, 其余照旧", JSON.stringify(dropped.map((x) => x.path)), JSON.stringify(["C:\\ph\\a.jpg", "C:\\ph\\c.jpg"]));
check("判据作用在**桌面键**上: 用 verbatim 形式去挡是挡不住的(错键空间不生效)", remapRemoteDecisions([evA], (k) => k === "\\\\?\\C:\\ph\\a.jpg").length, 1);
check("判据作用在**桌面键**上: 用映射后的键挡得住", remapRemoteDecisions([evA], (k) => k === "C:\\ph\\a.jpg").length, 0);
check("缺省判据 = 不挡(纯映射)", remapRemoteDecisions([evA, evB, evC]).length, 3);
check("空批次 → 空结果", remapRemoteDecisions([]).length, 0);

console.log("");
console.log("── 组合: 事件 → 本地 map(用真实的 mergeRemoteRatings 跑完整链路) ──");
// 本地 map 里 b.jpg 是用户刚在电脑上改成 2★ 的值(如果它还在待落盘集合里, 就不许被事件盖掉)
const local = { "C:\\ph\\a.jpg": 4, "C:\\ph\\b.jpg": 2 };
const hasVerbatimKey = (m) => Object.keys(m).some((k) => k.startsWith("\\\\?\\"));

// ① 修复后的链路 + b.jpg 待落盘: 事件必须被整条挡掉(不变式 2 的"边车覆盖本地"会毁掉用户的值)
const guarded = mergeRemoteRatings(local, remapRemoteDecisions([ev("\\\\?\\C:\\ph\\b.jpg", 5, null)], (k) => k === "C:\\ph\\b.jpg"));
check("① 待落盘: 没有任何变更(changed 为空)", guarded.changed.length, 0);
check("① 待落盘: 用户刚改的 2★ 还在", guarded.map["C:\\ph\\b.jpg"], 2);
check("① 待落盘: 没有多出 verbatim 键", hasVerbatimKey(guarded.map), false);

// ② 修复后的链路 + 不待落盘: 事件落到**朴素键**上, 而且不产生第二个键
const applied = mergeRemoteRatings(local, remapRemoteDecisions([ev("\\\\?\\C:\\ph\\b.jpg", 5, null)]));
check("② 正常合并: changed 就是那条朴素键", JSON.stringify(applied.changed), JSON.stringify(["C:\\ph\\b.jpg"]));
check("② 正常合并: 值落到朴素键上(界面会刷新)", applied.map["C:\\ph\\b.jpg"], 5);
check("② 正常合并: 没有多出 verbatim 键", hasVerbatimKey(applied.map), false);
check("② 正常合并: 键总数不变", Object.keys(applied.map).length, 2);

// ③ 修复**前**的链路(把事件路径原样喂给 merge): 朴素键纹丝不动、多出一个 `\\?\...` 重复键
//    —— 这正是线上症状的机制: "电脑上不刷新" + "localStorage 攒重复键"。这条断言保证
//    脚本真的能证明修复的价值(而不是只证明映射函数自洽)。
const naive = mergeRemoteRatings(local, [ev("\\\\?\\C:\\ph\\b.jpg", 5, null)]);
check("③ 修复前(对照): 朴素键不动 = 界面不刷新", naive.map["C:\\ph\\b.jpg"], 2);
check("③ 修复前(对照): 多出一个 verbatim 重复键", hasVerbatimKey(naive.map), true);
check("③ 修复前(对照): 键总数 +1", Object.keys(naive.map).length, 3);

console.log("");
console.log("结果: " + probe.ok + " 条通过, " + probe.bad.length + " 条失败");
if (probe.bad.length > 0) {
  for (const b of probe.bad) console.error("  ✗ " + b);
}
console.log(probe.bad.length === 0 ? "DESKTOP-KEY-MAP: OK" : "DESKTOP-KEY-MAP: MISMATCH");
process.exit(probe.bad.length === 0 ? 0 : 1);
