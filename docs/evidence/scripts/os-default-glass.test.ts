// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// src/os-capability.ts 的纯逻辑断言
// 跑法(项目既有约定: esbuild + Node, 不引 vitest):
//   npx esbuild docs/evidence/scripts/os-default-glass.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/os-default-glass.mjs
//   node .design-audit/_probe/os-default-glass.mjs
// (产物落在 gitignore 的 .design-audit/ 下, 不入库)
import {
  osDefaultGlass,
  micaUnsupported,
  type OsCapabilities,
} from "../../../src/os-capability";

let fail = 0;
let pass = 0;
function eq(name: string, got: unknown, want: unknown) {
  const g = JSON.stringify(got);
  const w = JSON.stringify(want);
  if (g === w) { pass++; console.log("  ok   " + name); }
  else { fail++; console.log("  FAIL " + name + "\n        got  " + g + "\n        want " + w); }
}

const WIN11: OsCapabilities = { platform: "windows", windowsBuild: 26200, supportsMica: true };
const WIN10: OsCapabilities = { platform: "windows", windowsBuild: 19045, supportsMica: false };
const MAC: OsCapabilities = { platform: "macos", windowsBuild: null, supportsMica: false };
/** Windows, 但构建号读不到 —— 语义是"不知道", 不是"不支持" */
const WIN_NO_BUILD: OsCapabilities = { platform: "windows", windowsBuild: null, supportsMica: false };
/** 整体探测失败(超时 / invoke 报错 / 返回了非预期形状) */
const PROBE_FAILED: OsCapabilities = { platform: "unknown", windowsBuild: null, supportsMica: false };

console.log("osDefaultGlass · 没存过值(新装用户):");
eq("Win11 → 开", osDefaultGlass(WIN11, null), true);
eq("Win10 → 关", osDefaultGlass(WIN10, null), false);
eq("macOS → 开(不能因为 supportsMica 恒为 false 就把默认改掉)",
  osDefaultGlass(MAC, null), true);
eq("探测失败 → 开(保持今天的行为)", osDefaultGlass(PROBE_FAILED, null), true);
eq("Windows 但构建号读不到 → 开(别把 Win11 用户误关)",
  osDefaultGlass(WIN_NO_BUILD, null), true);

console.log("osDefaultGlass · 存过值(永远听用户的, 老用户不被纠正):");
eq("Win11 + 存过 \"1\" → 开", osDefaultGlass(WIN11, "1"), true);
eq("Win10 + 存过 \"1\" → 开(老用户不被纠正)", osDefaultGlass(WIN10, "1"), true);
eq("macOS + 存过 \"1\" → 开", osDefaultGlass(MAC, "1"), true);
eq("Win11 + 存过 \"0\" → 关", osDefaultGlass(WIN11, "0"), false);
eq("Win10 + 存过 \"0\" → 关", osDefaultGlass(WIN10, "0"), false);
eq("macOS + 存过 \"0\" → 关", osDefaultGlass(MAC, "0"), false);
eq("探测失败 + 存过 \"0\" → 关", osDefaultGlass(PROBE_FAILED, "0"), false);

console.log("micaUnsupported(设置里是否置灰):");
eq("Win10 → 置灰", micaUnsupported(WIN10), true);
eq("Win11 → 不置灰", micaUnsupported(WIN11), false);
eq("macOS → 不置灰", micaUnsupported(MAC), false);
eq("探测失败 → 不置灰(不能因为探测失败就把开关锁死)", micaUnsupported(PROBE_FAILED), false);
eq("Windows 但构建号读不到 → 不置灰", micaUnsupported(WIN_NO_BUILD), false);

console.log("\n" + (fail === 0 ? "ALL PASS" : "FAILURES") + ": " + pass + " passed, " + fail + " failed");
process.exit(fail === 0 ? 0 : 1);
