// 文件夹树取证用桩：严格模仿 src-tauri/src/scanner/browse.rs 的真实返回形状 ——
// browse_directory 只返回**直接子目录**，每个子项的 subfolders 一律为空 vec![]，
// 只带 has_subdirs 标志。子目录内容要等用户展开后由再一次 browse_directory 补齐。
// 这是本探针的全部意义：默认桩 (harness.html 里的 FOLDER) 写死了空 subfolders，
// 反而掩盖了"点开 → 先空 → 后到"的真实时序。
(function () {
  var cbId = 0, fns = {};
  var Q = new URLSearchParams(location.search);
  // ?gap=250 → 模拟慢卡：browse_directory 的往返延迟(ms)
  var GAP = parseInt(Q.get("gap") || "0", 10) || 0;

  var DIRS = {};
  function def(path, name, photos, kids) {
    DIRS[path] = { path: path, name: name, photos: photos, kids: kids || [] };
    return DIRS[path];
  }
  var E = "E:\\"; // 注意: 已含尾部反斜杠
  // 统一拼路径 —— E 自带一个 \，再手写 "\\" 会拼出 E:\\DCIM 这种查不到的键
  function join(p, k) { return p.charAt(p.length - 1) === "\\" ? p + k : p + "\\" + k; }
  def(E, "EOS_DIGITAL", 0, ["DCIM"]);
  def(E + "DCIM", "DCIM", 0, ["100CANON", "101CANON", "102EOSR5", "103EOSR5", "200CANON"]);
  def(E + "DCIM\\100CANON", "100CANON", 12, []);
  def(E + "DCIM\\101CANON", "101CANON", 48, []);
  // 有照片的子目录里再套一层。kids 必须显式列上 RAW/JPG —— 原先这里写的是 []，
  // 于是 103EOSR5 成了**叶子**（hasSubdirs=false），下面两行 RAW/JPG 成了永远取不到的死数据，
  // 而 Task 3 的探针（收起态 inert / 三层嵌套不裁剪）与 Task 4 的取证都要求它是可展开的分支。
  def(E + "DCIM\\103EOSR5", "103EOSR5", 0, ["RAW", "JPG"]);
  def(E + "DCIM\\103EOSR5\\RAW", "RAW", 96, []);
  def(E + "DCIM\\103EOSR5\\JPG", "JPG", 96, []);
  def(E + "DCIM\\200CANON", "200CANON", 204, []);
  var shoots = [
    ["2023-11-02 婚礼", 312], ["2023-12-24 平安夜", 88],
    ["2024-01-15 外拍", 146], ["2024-02-03 棚拍", 64],
  ];
  var shootPaths = shoots.map(function (s) { return s[0]; });
  def(E + "DCIM\\102EOSR5", "102EOSR5", 0, shootPaths);
  shoots.forEach(function (s) { def(E + "DCIM\\102EOSR5\\" + s[0], s[0], s[1], []); });

  function subtreePhotos(path) {
    var d = DIRS[path];
    if (!d) return 0;
    return d.photos + d.kids.reduce(function (a, k) { return a + subtreePhotos(join(path, k)); }, 0);
  }
  function entry(path) {
    var d = DIRS[path];
    return {
      path: path,
      name: d.name,
      photoCount: d.photos,
      hasSubdirs: d.kids.length > 0,
      subfolders: d.kids.map(function (k) { return entry(join(path, k)); })
        .map(function (c) { c.subfolders = []; return c; }), // ← 关键：只留一层
    };
  }
  function photosFor(path) {
    var d = DIRS[path];
    if (!d || !d.photos) return [];
    var n = Math.min(d.photos, 30);
    var out = [];
    for (var i = 1; i <= n; i++) {
      out.push({
        path: path + "\\IMG_" + ("000" + i).slice(-4) + ".CR3",
        fileName: "IMG_" + ("000" + i).slice(-4) + ".CR3",
        fileSize: 24576000 + i * 1000,
        isRaw: true, isVideo: false,
        modifiedAt: 1735689600000 + i * 60000,
        exif: { cameraMake: "Canon", cameraModel: "EOS R5", lensModel: "RF 24-70mm F2.8 L IS USM",
          focalLength: "50mm", aperture: "f/2.8", shutterSpeed: "1/250", iso: 400,
          dateTaken: "2025-01-01 10:00:00", imageWidth: 8192, imageHeight: 5464, fileSize: 24576000 },
      });
    }
    return out;
  }
  function delay(v) { return GAP ? new Promise(function (r) { setTimeout(function () { r(v); }, GAP); }) : v; }

  function stub(cmd, args) {
    switch (cmd) {
      // 系统能力桩。缺省模拟本机(Win11 / 26200 → 支持 Mica)。
      //   ?win10=1  → 模拟 Win10(19045, 不支持) —— 验默认关 + 置灰
      //   ?osfail=1 → 返回 null, 模拟"读不到构建号" —— 验前端退回今天的行为
      case "get_os_capabilities":
        if (Q.has("win10")) return { platform: "windows", windowsBuild: 19045, supportsMica: false };
        if (Q.has("osfail")) return null;
        return { platform: "windows", windowsBuild: 26200, supportsMica: true };
      case "detect_drives":
        return [{ mountPoint: E, driveType: "removable", label: "EOS_DIGITAL", available: true }];
      case "browse_directory":
        return delay(DIRS[args.dirPath] ? entry(args.dirPath) : entry(E));
      case "scan_directory":
        return delay(photosFor(args.dirPath));
      case "count_folders": {
        var map = {};
        (args.folderPaths || []).forEach(function (p) { map[p] = subtreePhotos(p); });
        return delay(map);
      }
      case "read_decisions": return [];
      case "get_import_history": return [];
      case "count_import_history": return 0;
      case "get_rules": return [];
      case "is_dir_empty": return true;
      case "probe_xmp_target": return { ok: false };
      case "probe_lightroom": return { found: false };
      default: return null;
    }
  }
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: "main" }, currentWebview: { label: "main" } },
    invoke: function (cmd, args) {
      window.__MOCK_CALLS__ = window.__MOCK_CALLS__ || {};
      window.__MOCK_CALLS__[cmd] = (window.__MOCK_CALLS__[cmd] || 0) + 1;
      if (cmd === "plugin:event|listen") return Promise.resolve(++cbId);
      if (cmd === "plugin:event|unlisten") return Promise.resolve(null);
      var v = stub(cmd, args || {});
      return v && typeof v.then === "function" ? v : Promise.resolve(v);
    },
    transformCallback: function (cb) { var id = ++cbId; fns[id] = cb; return id; },
    unregisterCallback: function (id) { delete fns[id]; },
    convertFileSrc: function (p) {
      var m = String(p).match(/(\d+)\./); var n = m ? parseInt(m[1], 10) : 0;
      var fill = n % 2 === 1 ? "%23ffffff" : "%230a1016";
      return "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='4' height='3'><rect width='4' height='3' fill='" + fill + "'/></svg>";
    },
    plugins: {},
  };
  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener: function () {} };

  // harness.html 自己有内联桩，且它在 addInitScript 之后执行，会直接覆盖掉上面这份。
  // 用带 setter 的属性定义把 __TAURI_INTERNALS__ 钉死成"只读为我这份"，
  // 让 harness 的赋值落空 —— 探针专用手法，不进产品代码。
  var MINE = window.__TAURI_INTERNALS__;
  Object.defineProperty(window, "__TAURI_INTERNALS__", {
    configurable: true,
    get: function () { return MINE; },
    set: function () { /* 忽略 harness 的覆盖 */ },
  });
})();
