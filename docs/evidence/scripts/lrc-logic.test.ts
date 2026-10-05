// Phase 7 · src/lightroom.ts 的纯逻辑断言
// 跑法(项目既有做法: 临时 esbuild + Node, 不引 vitest):
//   npx esbuild _probe/lrc-logic.test.ts --bundle --platform=node --format=esm --outfile=_probe/lrc-logic.mjs
//   node _probe/lrc-logic.mjs
import {
  planLrcImport,
  stageFolderName,
  lrcErrKey,
  LRC_ERR_CODES,
  isLrcModeUsable,
  readLrcMode,
} from "A:/tenent/src/lightroom.ts";

let fail = 0;
let pass = 0;
function eq(name: string, got: unknown, want: unknown) {
  const g = JSON.stringify(got);
  const w = JSON.stringify(want);
  if (g === w) {
    pass++;
    console.log("  ok   " + name);
  } else {
    fail++;
    console.log("  FAIL " + name + "\n        got  " + g + "\n        want " + w);
  }
}

const t = new Date(2026, 9, 5, 14, 30); // 2026-10-05 14:30 (月份 0-based)

console.log("stageFolderName:");
eq("timestamped, readable", stageFolderName(t), "ImageFilter_20261005_1430");
eq(
  "zero padded",
  stageFolderName(new Date(2026, 0, 2, 3, 4)),
  "ImageFilter_20260102_0304"
);

console.log("planLrcImport:");
eq(
  "empty destination -> import straight into it, hand it to LrC",
  planLrcImport("D:\\Photos", true, t),
  { destDir: "D:\\Photos", staged: false, stageDir: null }
);
eq(
  "non-empty destination -> new timestamped subfolder",
  planLrcImport("D:\\Photos", false, t),
  {
    destDir: "D:\\Photos\\ImageFilter_20261005_1430",
    staged: true,
    stageDir: "D:\\Photos\\ImageFilter_20261005_1430",
  }
);
eq(
  "trailing backslash is not doubled",
  planLrcImport("D:\\Photos\\", false, t).destDir,
  "D:\\Photos\\ImageFilter_20261005_1430"
);
eq(
  "forward slashes keep forward separators",
  planLrcImport("D:/Photos/", false, t).destDir,
  "D:/Photos/ImageFilter_20261005_1430"
);
eq(
  "drive root works (separator decided before trimming)",
  planLrcImport("D:\\", false, t).destDir,
  "D:\\ImageFilter_20261005_1430"
);

console.log("lrcErrKey:");
eq("known code passes through", lrcErrKey("notFound"), "notFound");
eq("new noNewPhotos code", lrcErrKey("noNewPhotos"), "noNewPhotos");
eq("empty -> unknown", lrcErrKey(""), "unknown");
eq("null -> unknown", lrcErrKey(null), "unknown");
eq("garbage -> unknown", lrcErrKey("'; DROP TABLE"), "unknown");

console.log("LRC_ERR_CODES vs Rust LrcError::code():");
eq(
  "closed set",
  [...LRC_ERR_CODES].sort(),
  [
    "alreadyRunning",
    "launchFailed",
    "noFolder",
    "noNewPhotos",
    "notFound",
    "notImplemented",
    "notSupported",
    "unknown",
  ]
);
eq("alreadyRunning is a known code", lrcErrKey("alreadyRunning"), "alreadyRunning");

console.log("isLrcModeUsable / readLrcMode (no localStorage in node):");
eq("dialog usable", isLrcModeUsable("dialog"), true);
eq("silent NOT usable", isLrcModeUsable("silent"), false);
eq("readLrcMode defaults to dialog when localStorage is absent", readLrcMode(), "dialog");

console.log(
  "\n" + (fail === 0 ? "ALL PASS" : "FAILURES") + ": " + pass + " passed, " + fail + " failed"
);
process.exit(fail === 0 ? 0 : 1);
