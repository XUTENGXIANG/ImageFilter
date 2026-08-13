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
    rotate: "旋转(查看器)",
    nav: "切换(查看器)",
    reset: "重置(查看器)",
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
    stop: "停止",
    ai: "AI 分析",
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
    doneFail: "，{n} 张失败",
    advanced: "高级选项",
    dateFolder: "按拍摄日期分文件夹",
    dateFolderEx: "如 2024-08-08/照片.jpg",
    cameraFolder: "按相机型号分文件夹",
    cameraFolderEx: "如 Sony-A7M4/照片.jpg",
    seqRename: "按序号重命名",
    seqRenameEx: "如 0001.ARW",
    subFolder: "导入到子文件夹",
    subFolderPlaceholder: "输入文件夹名",
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

};
