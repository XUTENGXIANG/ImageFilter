// 简体中文翻译 — 界面默认语言
// 约定: 所有界面文本必须抽到本文件, 组件内只用 t("key")
export default {
  // ── 标题栏 ──
  titlebar: {
    help: "使用说明",
    settings: "设置",
    themeLight: "切换到浅色主题",
    themeDark: "切换到深色主题",
    min: "最小化",
    max: "最大化",
    close: "关闭",
  },

  // ── 设置面板 ──
  settings: {
    title: "设置",
    subtitle: "ImageFilter 应用设置",
    version: "版本 {v}",
    theme: "主题",
    themeDesc: "深色 / 浅色",
    dark: "深色",
    light: "浅色",
    language: "语言",
    languageDesc: "界面显示语言",
    preload: "可见区域全图预加载",
    preloadDesc: "预载当前可见区域所有照片全图，打开查看器更快；开关立即生效，无需重启",
    autoAdvance: "评分后自动前进",
    autoAdvanceDesc: "按 J / X / 1-5 或点星条后自动跳到下一张，打完最后一张自动关闭查看器；想留下就用 ← 回退",
    labelKeys: "颜色标签快捷键",
    labelKeysDesc: "颜色标签用哪个修饰键：Ctrl+1-5 打标、Ctrl+0 清除；若 Ctrl+数字被系统占用就选 Alt",
    // Phase 5 · XMP 边车(三档开关, 默认"关闭" = 只写本机、完全不碰卡)
    // 状态文案挂在同一行的 desc 上, **不新增整行** —— 加行 = 重新算对话框总高(会话 ③ 第 28 项)
    xmpMode: "评分/颜色标签写入 .xmp",
    xmpModeDesc: "关闭 = 只写本机（默认，完全不碰卡）；询问 = 首次写入时问一次；写入 = 评分/打标后自动写照片同目录的 .xmp 边车文件，LR/digiKam 也能读到",
    xmpModeOff: "关闭",
    xmpModeAsk: "询问",
    xmpModeOn: "写入",
    xmpStatusOff: "未启用",
    xmpStatusWritable: "{dir} 可写",
    xmpStatusUnwritable: "{dir} 不可写：{reason}",
    xmpStatusDegraded: "（该位置不支持原子覆盖，已降级为直接写入）",
    transparentBg: "透明毛玻璃背景",
    transparentBgDesc: "启用后使用 Windows Mica 毛玻璃，随深色/浅色主题自动切换；立即生效",
    transparentBgOpacity: "标题栏玻璃透明度",
    transparentBgOpacityDesc: "调节标题栏玻璃背景的透明度；不影响浮窗，关闭毛玻璃时无效",
    backgroundOpacity: "背景玻璃透明度",
    backgroundOpacityDesc: "调节浮窗后面整块背景毛玻璃的透明度；不影响浮窗，关闭毛玻璃时无效",
  },

  // ── 使用说明 ──
  help: {
    title: "使用说明",
    subtitle: "ImageFilter — 照片导入工具",
    step1Title: "插入 SD 卡",
    step1Desc: "插入相机存储卡，左栏自动检测设备",
    step2Title: "浏览照片",
    step2Desc: "可筛格式类型，查看 EXIF，略缩图预览，raw原图查看...",
    step3Title: "筛选/评分",
    step3Desc: "点 AI 分析检查",
    step4Title: "导入电脑",
    step4Desc: "勾选照片 → 选目标文件夹 → 点导入",
    shortcuts: "键盘快捷键",
    keep: "保留(3星)",
    trash: "废弃(0星)",
    star: "星级评分",
    label: "Ctrl/Alt+1-5",
    labelDesc: "颜色标签（网格与查看器）",
    autoAdvance: "评分后自动前进",
    rotate: "旋转(查看器)",
    nav: "切换(查看器)",
    reset: "重置(查看器)",
    actual: "1:1 实际像素",
    undo: "Ctrl+Z",
    undoDesc: "撤销评分/勾选(仅本次会话，切文件夹或设备后清空)",
    select: "勾选(查看器)",
    space: "空格",
    ctrlClick: "Ctrl+点击",
    shiftClick: "Shift+点击",
    multi: "多选",
    range: "范围选择",
    contextMenu: "右键菜单",
    ctxPhoto: "照片: 导入/评分/EXIF/打开位置",
    ctxEmpty: "空白: 刷新/导入全部/AI分析",
    ctxDevice: "设备: 打开/弹出设备(可移动)",
    ctxFolder: "文件夹: 打开/导入全部",
  },

  // ── 设备面板 ──
  devices: {
    panel: "设备",
    refresh: "刷新",
    open: "打开",
    eject: "弹出设备",
    ejectOk: "已弹出 {dir}",
    ejectFail: "弹出失败",
    fixedDisk: "固定磁盘不可弹出",
    refreshList: "刷新设备列表",
    noDevices: "未检测到设备",
    scanning: "扫描目录结构...",
    root: "根目录",
    notScanned: "未扫描",
    selectDevice: "选择设备",
    browsing: "浏览中...",
    loading: "加载中...",
    counting: "正在读取照片...",
    photos: "{n} 张",
    ready: "就绪",
  },

  // ── 工具栏 ──
  toolbar: {
    selectAll: "全选",
    clear: "取消",
    selected: "已选 {n}/{total}",
    sortName: "文件名",
    sortType: "类型",
    sortDate: "日期",
    all: "全部",
    starFilter: "≥{n}★",
    stop: "停止",
    ai: "AI 分析",
    aiCount: "AI 分析选中 ({n})",
    aiSelected: "只分析选中的 {n} 张",
    filter: "筛选",
    flags: "分析结果",
    sortDir: "切换排序方向",
    clearFilters: "清除筛选",
    cols: "{n} 列",
    empty: "打开照片文件夹后显示工具栏",
  },

  // ── 照片网格 ──
  grid: {
    browsing: "浏览目录...",
    loading: "加载照片...",
    noPhotos: "此文件夹无照片",
    clickFolder: "点击左侧文件夹查看照片",
    video: "视频",
    blurry: "模糊",
    overexposed: "过曝",
    underexposed: "欠曝",
    duplicate: "重复",
    best: "最佳",
    pendingAnalysis: "还有 {n} 张未分析",
    analyzePending: "分析这 {n} 张",
    noMatch: "没有照片符合当前筛选",
  },

  // ── 导入栏 ──
  import: {
    pickDest: "选择目标文件夹",
    pickDestTitle: "选择导入目标文件夹",
    openFolder: "打开文件夹",
    needDest: "请先选目标文件夹",
    needSelect: "请勾选要导入的照片",
    importing: "导入中...",
    importingCount: "导入中 {done}/{total}",
    importCount: "导入 {n} 张",
    error: "错误: {msg}",
    doneOk: "导入完成 ✓ {n} 张成功",
    // Phase 6 / 6.3 · 四行明细。renamed 那句以"其中"开头 —— 它是 imported 的**子集**,
    // 不是并列的第五类(否则四段相加对不上)。跳过必须与失败分开显示。
    doneRenamed: "其中 {n} 张重名，改名导入",
    doneSkipped: "{n} 张已存在相同，跳过",
    doneFail: "{n} 张失败",
    advanced: "高级选项",
    dateFolder: "按拍摄日期分文件夹",
    dateFolderEx: "如 2024-08-08/照片.jpg",
    cameraFolder: "按相机型号分文件夹",
    cameraFolderEx: "如 Sony-A7M4/照片.jpg",
    seqRename: "按序号重命名",
    seqRenameEx: "如 0001.ARW",
    subFolder: "导入到子文件夹",
    subFolderPlaceholder: "输入文件夹名",
    // Phase 6 / 6.1 · 导入历史(独立对话框, 入口在导入栏)
    history: "导入历史",
    historyHint: "归档后原文件名只能在这里找回；点归档路径可打开所在文件夹",
    historyTotal: "共 {n} 条记录",
    historyShown: "已显示 {shown} / {total}",
    historyEmpty: "还没有导入记录",
    historyError: "读取导入历史失败：{msg}",
    historyLoadMore: "加载更多",
    historyColSource: "原文件",
    historyColDest: "归档",
    historyOpenFolder: "打开所在文件夹",
    // Phase 6 / 6.2 · 命名方案(import_rules)
    // 注意: 任何值里都不许出现裸的 {seq} / {ext} —— i18n/index.ts 把 prefix/suffix 设成了
    // { }, 值里的花括号会被当成插值变量解析掉(既有 key 一律只写"具体例子", 如 0001.ARW)
    scheme: "命名方案",
    schemeCustom: "自定义",
    schemeSaveAs: "另存为",
    schemeNamePlaceholder: "方案名",
    schemeSave: "保存",
    schemeError: "命名方案出错：{msg}",
    seqKeepOriginal: "保留原文件名",
    seqKeepEx: "如 0001_IMG_1234.ARW",
    fileRuleNowLabel: "当前文件名：",
    fileRuleOriginal: "原名",
  },

  // ── 颜色标签(Phase 4) ──
  // title 同时用作工具栏筛选分区标题与右键子菜单标题(一处定义, 两处调用)
  label: {
    title: "颜色标签",
    red: "红",
    yellow: "黄",
    green: "绿",
    blue: "蓝",
    purple: "紫",
    clear: "清除颜色标签",
    none: "无标签",
  },

  // ── 右键菜单 ──
  menu: {
    importSelected: "导入选中",
    importCount: "导入 {n} 张",
    rating: "评分",
    clearRating: "清除评分",
    viewExif: "查看 EXIF",
    openLocation: "打开位置",
    selectAll: "全选",
    deselect: "取消选择",
    refresh: "刷新",
    importAll: "导入全部",
    ai: "AI 分析",
  },

  // ── EXIF 信息面板 ──
  exif: {
    panel: "详细信息",
    hint: "选中照片查看 EXIF",
    fileInfo: "文件信息",
    fileName: "文件名",
    size: "大小",
    type: "类型",
    typeImage: "图片",
    typeVideo: "视频",
    camera: "相机",
    brand: "品牌",
    model: "型号",
    lens: "镜头",
    params: "拍摄参数",
    aperture: "光圈",
    shutter: "快门",
    iso: "ISO",
    focal: "焦距",
    date: "日期",
    dims: "尺寸",
  },

  // ── 欢迎页 ──
  welcome: {
    subtitle: "照片筛选导入工具",
  },

  // ── 图片查看器 ──
  viewer: {
    rotateCCW: "逆时针旋转 (Shift+R)",
    rotateCW: "顺时针旋转 (R)",
    prev: "上一张 (←)",
    next: "下一张 (→)",
    nav: "← → 切换",
    zoom: "滚轮 缩放",
    actual: "1:1 实际像素 (Z)",
    pan: "拖动 平移",
    reset: "0 重置",
    rotate: "R 旋转",
    rate: "J 保留 / X 废弃 / 1-5 星级",
    select: "勾选 (空格)",
    unselect: "取消勾选 (空格)",
  },

  // ── 折叠工具条 ──
  bars: {
    expand: "展开",
    collapse: "收起",
  },

  // ── 浮窗面板 ──
  panel: {
    expandLeft: "展开设备面板",
    expandRight: "展开信息面板",
  },

  // ── 提示浮窗(toast) ──
  toast: {
    undo: "已撤销",
    undoRedo: "已重做",
    // 注意: 本项目插值用**单括号** {n}(见 i18n/index.ts 的 prefix/suffix),
    // 写成 {{prev}} 会被解析成不存在的变量 "{prev" 并原样显示。
    ratingChange: "{prev}★ → {next}★",
    labelChange: "{prev} → {next}",
    selectionChange: "勾选 {n} 张",
  },

  // ── Phase 5 · XMP 边车 ──
  // 错误码与 src-tauri/src/xmp.rs 的 XmpError::code() 一一对应(前端白名单校验后
  // 用动态前缀 `xmp.err.<code>`, 未知码落到 unknown)。全部**单括号**插值。
  xmp: {
    askTitle: "把星级/颜色标签写入 .xmp？",
    askBody: "会在照片所在文件夹生成同名的 .xmp 边车文件（例如 IMG_1234.xmp），Lightroom / digiKam 等软件也能读到这些决策。只增改 .xmp，绝不改动或删除你的照片。",
    askYes: "写入 .xmp",
    askNo: "只写本机",
    toastFailed: "写入 .xmp 失败：{reason}",
    toastDowngraded: "该位置不可写，已切回“只写本机”：{reason}",
    toastQueueOverflow: "改动太多，本次只同步最近 {n} 张",
    err: {
      writeProtect: "存储卡处于写保护（只读）",
      readOnly: "文件或文件夹是只读属性",
      permission: "权限不足",
      diskFull: "磁盘空间不足",
      busy: "文件被其它程序占用",
      notXmp: "已存在的 .xmp 不是可识别的 XMP（未修改）",
      unsupportedForm: "该 .xmp 使用了不支持的写法（未修改）",
      tooLarge: ".xmp 超过 1 MB 上限（未修改）",
      encoding: ".xmp 不是 UTF-8 编码（未修改）",
      pathTooLong: "路径过长",
      unknown: "未知错误",
    },
  },

};
