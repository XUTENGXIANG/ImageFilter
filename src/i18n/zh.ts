// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
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
    // 检查更新(只比版本号, 不下载不安装)。五种状态复用同一个按钮, 按钮字面即状态
    checkUpdate: "检查更新",
    checkUpdateHint: "与 GitHub 上最新正式版比对版本号",
    checkingUpdate: "正在检查…",
    updateLatest: "已是最新",
    updateLatestHint: "已经是最新正式版（点击可重新检查）",
    updateAvailable: "有新版本 {v}",
    updateAvailableHint: "打开 GitHub 发布页下载",
    updateFailed: "检查失败，重试",
    updateFailedHint: "检查失败：{reason}",
    updateErrNetwork: "网络不可用",
    updateErrTimeout: "请求超时",
    updateErrHttp: "HTTP {code}",
    updateErrUnexpected: "响应异常",
    theme: "主题",
    themeDesc: "深色 / 浅色",
    dark: "深色",
    light: "浅色",
    language: "语言",
    languageDesc: "界面显示语言",
    preload: "可见区域全图预加载",
    preloadDesc: "预载可见区域原图，打开查看器更快",
    autoAdvance: "评分后自动前进",
    autoAdvanceDesc: "评分后自动下一张（← 回退）",
    showCounts: "显示目录数量",
    showCountsDesc: "在左侧目录树显示数量",
    labelKeys: "颜色标签快捷键",
    labelKeysDesc: "打标修饰键（被占用换 Alt）",
    // Phase 5 · XMP 边车(三档开关, 默认"询问" —— 会话 ⑥ 由"关闭"改来, 见 src/xmp.ts)
    // 状态文案挂在同一行的 desc 上, **不新增整行** —— 加行 = 重新算对话框总高(会话 ③ 第 28 项)
    // 文案从简(会话 ⑤ 收尾): 每行说明控制在**一行**内, 细节交给三档按钮自己的字面与悬停
    xmpMode: "评分/颜色标签写入 .xmp",
    xmpModeDesc: "写 .xmp 边车；关闭 = 本机",
    xmpModeOff: "关闭",
    xmpModeAsk: "询问",
    xmpModeOn: "写入",
    xmpStatusOff: "未启用",
    xmpStatusWritable: "{dir} 可写",
    xmpStatusUnwritable: "{dir} 不可写：{reason}",
    xmpStatusDegraded: "（不支持原子覆盖，已直接写入）",
    // Phase 7 · Lightroom 衔接(设置里的发送模式; 状态文案同样拼进 desc, 不新增行)
    lrcMode: "Lightroom 衔接",
    lrcModeDialog: "打开导入",
    lrcModeSilent: "静默导入（未支持）",
    lrcModeSilentWhy: "需先在 Lightroom 配置“自动导入”（尚未支持）",
    lrcReprobe: "重新检测",
    lrcProbing: "检测中…",
    lrcNotFound: "未检测到 Lightroom Classic",
    lrcRunning: "运行中",
    lrcNotRunning: "未运行",
    transparentBg: "透明毛玻璃背景",
    transparentBgDesc: "用 Windows Mica 玻璃背景",
  transparentBgDescMac: "macOS 的透明玻璃背景",
  lrcUnsupportedMac: "macOS 版暂不支持 Lightroom 衔接（仅 Windows）",
  macPermTitle: "macOS 需要文件访问权限",
  macPermBody: "读取照片和写 .xmp 会触发权限询问；授予一次「完全磁盘访问权限」即可避免反复弹窗。",
  macPermPath: "系统设置 → 隐私与安全性 → 完全磁盘访问权限 → 勾选 ImageFilter",
  macPermOpen: "打开系统设置",
  macPermLater: "稍后再说",
    transparentBgUnsupported: "当前系统不支持（需要 Windows 11）",
    transparentBgOpacity: "标题栏玻璃透明度",
    transparentBgOpacityDesc: "只影响标题栏",
    backgroundOpacity: "背景玻璃透明度",
    backgroundOpacityDesc: "只影响浮窗后的背景",
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
    disks: "磁盘",
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
    folders: "{n} 文件夹",
    videos: "{n} 视频",
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
    sortBy: "排序方式",
    starFilterLabel: "星级筛选",
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
    select: "勾选这张照片",
    unselect: "取消勾选这张照片",
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
    collapseLeft: "收起设备面板",
    collapseRight: "收起信息面板",
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

  // ── Phase 7 · Lightroom 衔接 ──
  // 错误码与 src-tauri/src/lightroom.rs 的 LrcError::code() 一一对应
  // (前端白名单校验后拼 `lrc.err.<code>`, 未知码落到 unknown)。单括号插值。
  lrc: {
    send: "导入到 LrC",
    sendTip: "先把选中的照片导入到目标文件夹，再打开 Lightroom 的导入页面（页面上只有这批照片）",
    sending: "处理中…",
    importingPhotos: "正在导入照片…",
    startingLightroom: "正在启动 Lightroom…",
    importingPhotosBody: "正在把 {done}/{total} 张照片复制到目标文件夹并校验。请勿关闭本窗口。",
    startingLightroomBody: "照片已就位，正在打开 Lightroom 的导入页面。它可能要十几秒甚至更久（取决于目录库大小），请稍候 — 出现窗口后这里会自动关闭。",
    keepInBackground: "在后台继续",
    sent: "已导入 {n} 张：{dir}",
    sentStaged: "（目标文件夹非空，已放入子文件夹 → 在 LrC 里按该路径导入）",
    runningTitle: "Lightroom 正在运行",
    runningBody: "Lightroom 已经开着时，它不会理会传进去的文件夹路径，导入页面会停在它上一次的位置。请先关闭 Lightroom，再点「重试」。「强制关闭并继续」会直接结束 Lightroom 进程——未保存的调整会丢失。",
    runningRetry: "我已关闭，重试",
    runningForce: "强制关闭并继续",
    toastFailed: "交给 Lightroom 失败：{reason}",
    err: {
      notFound: "未找到 Lightroom，请在设置里点“重新检测”",
      noFolder: "没有可用的目标文件夹",
      alreadyRunning: "Lightroom 正在运行（需先关闭它）",
      launchFailed: "启动 Lightroom 失败",
      notSupported: "该平台暂不支持",
      notImplemented: "该模式尚未实现",
      noNewPhotos: "选中的 {n} 张在目标里都已经有了（内容相同），没有新照片可导入",
      unknown: "未知错误",
    },
  },

  // ── Phase 8 · 图库服务(手机端) ──
  // 面板长在设置对话框里(见 src/components/settings-dialog.tsx 的 ServerPanel): 开关/监听状态/
  // 配对码/共享目录/排查清单。状态形状见 src-tauri/src/server/mod.rs 的 ServerStatus(camelCase)。
  server: {
    title: "局域网远程浏览",
    switchDesc: "远程服务开关，默认关闭",
    on: "已开启",
    off: "已关闭",
    listening: "正在监听 {url}",
    // 开启失败时的那一句: 后端刻意**不**回滚开关(Task 8), 所以"你要它开、它没在跑"是合法状态,
    // 界面必须如实说, 并把 lastError 摆在旁边 —— 否则用户只会以为自己没点中。
    wantedNotRunning: "服务未运行",
    // 关闭不是"瞬间断开所有连接": stop() 之后端口还有一个**有界**的收敛窗口(实测最坏 63ms),
    // 期间仍可能完成握手。界面不许把"关"说成比实际更强(这条措辞就是那个边界)。
    offNote: "已关闭",
    lastError: "上次的错误",
    tokenLabel: "配对码",
    tokenDesc: "重置后需重新配对",
    tokenEmpty: "服务启动时自动生成",
    copy: "复制",
    copied: "已复制",
    copyFailed: "复制失败，请手动复制",
    rotate: "重置",
    rotateConfirm: "确认重置",
    sharesLabel: "共享目录",
    sharesDesc: "允许访问目录",
    shareEmpty: "尚未共享目录：手机端无法访问",
    pathPlaceholder: "输入文件夹路径",
    browse: "浏览…",
    add: "添加",
    remove: "移除",
    unavailable: "无法获取服务状态：{reason}",
    actionFailed: "操作失败：{reason}",
    tipsTitle: "无法连接？",
    sleepTip: "睡眠模式关闭（系统电源设置中更改为“从不”）",
    firewallTip: "首次开启时在 Windows 防火墙允许“专用网络”",
    // M4′ Task 2：LAN-only 决策（2026-10-07）之后**不再有“隧道”这个选项** ——
    // 原文案里的“或都加入了同一个 EasyTier/Tailscale 网络”已按决策删除。
    appRunningTip: "检查：电脑是否睡眠、应用运行状态、服务正常启动、相同局域网环境",

    // ── M4′ Task 2 · 局域网地址 / 防火墙 / 公用网络防护（LAN-only） ──
    // 面板长在 ServerPanel 里；数据来自 `get_server_network_hints`（NetHints, camelCase）。
    // 纪律：面板里**不许**出现“隧道 / EasyTier / Tailscale / 端口转发 / 公网暴露”的入口 ——
    // 本设计明确不做公网暴露，只警告（规格 §4.3）。
    lanTitle: "局域网地址",
    lanDesc: "手机与电脑需在同一局域网环境",
    lanEmpty: "未检测到局域网地址",
    rescan: "重新检测",
    firewallHintTitle: "防火墙问题",
    firewallHint: "Windows 安全中心 → 防火墙和网络保护 → 允许应用通过防火墙 → ImageFilter → 勾选“专用”。",
    firewallHintGeneric: "在 Windows 防火墙允许 ImageFilter 通过“专用网络”。",
    publicTitle: "当前网络被 Windows 标为“公用”",
    publicWarn: "“公用”是不可信网络，存在一定风险",
    publicOffer: "建议使用专用网络",
    publicConfirm: "我了解风险，仍要开启",
    publicAck: "已在 {time} 确认风险",
    publicAckNoTime: "本次运行已确认风险",
    publicIpTitle: "局域网地址错误",
    publicIpWarn: "{list} 存在暴露风险；已了解风险确认开启。",
    profileUnknown: "当前为未知网络；已了解风险确认开启。",

    // ── M4′ Task 1 · 配对二维码（桌面端生成） ──
    // 数据来自 `get_server_pairing_qr`（PairingQr, camelCase）；返回 null = 这次没有二维码。
    // 后端**不会**在没有私有地址时回落到公网地址或 127.0.0.1，所以这里必须把原因说出来，
    // 而不是画一个空框：没有局域网地址时原因由上面的 lanEmpty 负责，只有"有地址、没配对码"
    // 这一种才由 qrNoToken 负责。
    // Fix 1：二维码只在服务**真的在监听**时才有（后端没在监听就返回 null）。没在监听时
    // 由 qrNotRunning 说清原因并保留手输那条路 —— 一张装在配置端口上的码会把手机指向
    // 一个没人听的端口，而它看上去比一句话更权威。
    qrShow: "用手机扫码连接",
    qrHide: "收起二维码",
    qrScanHint: "使用手机 ImageFilter 扫码",
    qrNoToken: "服务启动后生成配对码与二维码。",
    qrNotRunning: "服务异常；请先检查服务状态，或手动填写下方信息。",
    // 扫码是增强，不是唯一路径（M4′ Task 1 Step 4 第 3 条：拒绝相机权限也要能配对）。
    qrFallback: "无法使用？尝试手动配对？",

    // ── M4′ Task 3 · 只读模式 ──
    // 「开」= 所有写端点回 403 forbiddenPath（复用既有码，不新造）；读端点照常。
    readOnly: "只读模式",
    readOnlyDesc: "手机端仅浏览",

    // ── M4′ Task 3 · 谁在连我 ──
    // 纪律：这里只有 HTTP 这一层真的知道的东西。**不许**编设备名或位置。
  },

  // ── 2026-10-25 · mDNS 配对（桌面端） ──
  // 配对窗口 / 待确认请求 / 已连接设备。命令与事件见 src/lib/pairing.ts（冻结契约 §0.3）。
  // 纪律：v1 的「移除」只是忘记这台设备，**不吊销**配对码 —— forgetNote 必须把这件事写清楚，
  // 否则用户会以为移除 = 已经踢下线（真正吊销只能轮换配对码，所有设备重连）。
  pairing: {
    sectionTitle: "配对新设备",
    windowHint: "配对前请点击此按钮接收配对请求",
    openWindow: "配对新设备",
    closeWindow: "关闭配对窗口",
    windowOpen: "配对窗口已打开",
    windowClosed: "配对窗口未打开",
    serviceOff: "先开启远程服务",
    remaining: "剩余 {time}",
    pendingTitle: "待确认的请求",
    pendingDesc: "请求 60 秒后自动过期",
    morePending: "还有 {n} 个待确认请求",
    pairedDevices: "已连接设备",
    noDevices: "暂无已配对设备",
    forget: "移除",
    lastSeen: "最近连接 {time}",
    requestTitle: "配对请求",
    requestBody: "这台设备想连接图库服务。请确认它是你自己的设备，且在同一局域网环境。",
    deviceOn: "设备：{name}（{ip}）",
    requestedAt: "请求时间 {time}",
    expiresIn: "请求将在 {seconds} 秒后自动过期",
    expired: "请求已过期",
    allow: "允许",
    deny: "拒绝",
    advanced: "高级 / 手动配对",
    actionFailed: "操作失败：{reason}",
    unavailable: "未知配对状态：{reason}",
  },

  // 手机「同步」确认门（方案 A）
  sync: {
    requestTitle: "手机请求同步",
    requestBody: "这台手机想把星级/色标同步到电脑。允许后手机才会推送未同步的改动，并重新读取电脑上的星级。",
    device: "设备：{name}",
    items: "待写 {n} 项",
    expiresIn: "请求将在 {seconds} 秒后自动过期",
    expired: "请求已过期",
    allow: "允许",
    deny: "拒绝",
    actionFailed: "操作失败：{reason}",
  },

};
