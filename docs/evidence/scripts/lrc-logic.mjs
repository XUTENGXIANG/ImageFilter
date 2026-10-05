// src/lightroom.ts
var LRC_ERR_CODES = [
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
  "unknown"
];
function lrcErrKey(code) {
  return LRC_ERR_CODES.includes(code ?? "") ? code : "unknown";
}
var LRC_MODE_STORAGE_KEY = "imagefilter-lrc-mode";
function readLrcMode() {
  try {
    return localStorage.getItem(LRC_MODE_STORAGE_KEY) === "silent" ? "silent" : "dialog";
  } catch {
    return "dialog";
  }
}
function isLrcModeUsable(m) {
  return m === "dialog";
}
var LRC_STAGE_PREFIX = "ImageFilter";
function stageFolderName(now) {
  const p = (n, w = 2) => String(n).padStart(w, "0");
  return LRC_STAGE_PREFIX + "_" + now.getFullYear() + p(now.getMonth() + 1) + p(now.getDate()) + "_" + p(now.getHours()) + p(now.getMinutes());
}
function joinPath(dir, name) {
  const trimmed = dir.replace(/[\\/]+$/, "");
  const sep = /\\/.test(dir) ? "\\" : "/";
  return trimmed + sep + name;
}
function planLrcImport(destDir, destIsEmpty, now) {
  if (destIsEmpty) {
    return { destDir, staged: false, stageDir: null };
  }
  const stageDir = joinPath(destDir, stageFolderName(now));
  return { destDir: stageDir, staged: true, stageDir };
}

// _probe/lrc-logic.test.ts
var fail = 0;
var pass = 0;
function eq(name, got, want) {
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
var t = new Date(2026, 9, 5, 14, 30);
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
    stageDir: "D:\\Photos\\ImageFilter_20261005_1430"
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
    "unknown"
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
