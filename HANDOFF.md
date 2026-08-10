# PixelFlow 交接文档(给 Codex)

> 交接时间:2026-08-10。由 Claude Code 交接,当前 HEAD `d5338c1`。

## 1. 项目是什么

**PixelFlow** — SD 卡照片智能导入工具(Tauri 2 桌面应用):
插卡 → 秒级预览(RAW 原生支持)→ 星级评分/AI 废片检测 → 一键导入电脑。

技术栈:
- **前端**:React 19 + TypeScript + Tailwind CSS v4 + shadcn/ui(OKLCh 色系, Geist 字体)
- **后端**:Rust + Tauri 2,解码栈 rawler + kamadak-exif + zune-jpeg + Windows WIC + tinydng(DNG C++ 解码器, 通过 cc crate 编译)
- **图标约定**:一律使用 bytedance/IconPark(`@icon-park/react`),不要混用 emoji 图标
- 无 i18n(纯中文界面),无动画库(手写 CSS)

## 2. 如何运行 dev 版本并在桌面呈现窗口

```bash
cd a:\tenent
npx tauri dev
```

**tauri dev 做的事**(按顺序):
1. 先执行 `beforeDevCommand`(`npm run dev`)启动 Vite dev server,端口 **1420**
2. 编译 Rust(`src-tauri/`,首次 2-5 分钟,之后增量秒级)
3. 编译完成后自动启动 `pixel-flow.exe`,**桌面弹出应用窗口**

窗口参数([tauri.conf.json](src-tauri/tauri.conf.json)):1200x800、最小 900x600、**decorations: false(无边框)**,标题栏是 React 自绘组件,靠 `data-tauri-drag-region` 属性实现拖拽移动窗口。

### 后台运行(Claude/Codex 场景)

```bash
# 前台运行会阻塞终端, 后台跑并把输出写到日志:
npx tauri dev 2>&1 | tail -30   # 或重定向到文件
```

### 端口 1420 被占用(常见坑)

旧 vite 进程不会随窗口关闭而退出。现象:`beforeDevCommand terminated with non-zero status`。

```bash
netstat -ano | grep ":1420" | grep LISTEN   # 找到 PID
taskkill /PID <PID> /F                        # 杀掉
npx tauri dev                                 # 重启
```

### 确认窗口是否在运行

```bash
tasklist | grep -i pixel-flow   # 看到 pixel-flow.exe 即窗口在跑
```

## 3. git 状态

```
master 分支, 本地 d5338c1
d5338c1 回到 6ac758e 稳定版本, 保留最新 README 与 CSP 修复   ← HEAD
6ac758e 查看器旋转+IOCTL安全弹出+设备面板右键+使用说明浮窗      ← 稳定版代码
```

- **GitHub 远端在 2f44681**,本地已分叉,以后 push 需要 `--force`
- 之前的精简重构(P0-P3)和 PhotoSwipe 看图器实验**已全部丢弃**,以 6ac758e 手写查看器为准
- GitHub token 已 revoke,需要用户提供新 token 才能 push

## 4. 代码结构(6ac758e 版)

```
src/
  App.tsx        ~1030行 全部组件: TitleBar(含设置/帮助Dialog)/设备面板/
                  照片网格/PhotoCard/ExifPanel/导入栏/右键菜单/欢迎页
  viewer.tsx     ~300行  图片查看器: 渐进加载(缩略图→内嵌JPEG→全解码)/
                  clip-path放大动画/旋转缩放拖拽/星级
  useScanner.ts  中央hook(设备/扫描/导入/分析/评分/排序 全部状态)
  contextmenu.tsx  右键菜单封装(Radix)
  panel.tsx        可折叠浮窗面板
  types.ts / index.css
src-tauri/
  src/scanner/mod.rs    类型与扩展名常量
  src/scanner/drives.rs 设备检测/IOCTL弹出/打开文件夹
  src/scanner/browse.rs 浏览/扫描/文件夹计数
  src/scanner/exif.rs   EXIF 懒加载
  src/scanner/images.rs 缓存/方向校正/缩略图/预览/全解码
  src/analyzer.rs   AI分析(模糊/曝光/重复)
  src/importer.rs   导入(模板/MD5/进度)
  src/win_wic.rs     WIC RAW解码
  src/tinydng.rs     DNG FFI
  src/db.rs          SQLite
  third_party/tinydng/  C++ DNG解码器(cc crate 编译)
```

## 5. 关键坑与注意事项(务必遵守)

1. **viewer.tsx 的加载状态机别乱简化** — 每个分支(首次260ms延迟/切换立即/快速切换120ms debounce/全解码300ms debounce)都是修"闪黑/卡顿"bug 得出来的,删任何分支都会回归视觉 bug
2. **生产版图片显示依赖 CSP** — tauri.conf.json 的 csp 必须包含 `img-src ... http://asset.localhost`(Windows 生产构建 asset 协议默认 http 而非 https;`use_https_scheme` 默认 false)。删了安装版全部图片会黑
3. **缩略图缓存目录**:`%LOCALAPPDATA%\pixel-flow\thumbnails_v2|preview_v3|full_v3`,缓存键 = path+mtime 哈希;`full_v3` 只接受长边 ≥1500px 的全图缓存,低分辨率缓存会自动作废重建
4. **RAW 解码链路**:内嵌JPEG(mmap 零解码)→ WIC(300ms级)→ rawler 全解码(慢, 有 Semaphore 并发1 + AtomicU64 任务号防堆积)
5. **DNG 独立路径**:rawler 完整显影(`raw_to_srgb`, 与 Windows Photos 同一档清晰度)→ tinydng → WIC,不与其他 RAW 混用;`win_wic.rs` 已补 `CoInitializeEx`,否则 WIC 直接失败
6. **IOCTL 弹出设备**:CreateFileW 必须 GENERIC_READ|GENERIC_WRITE,序列 LOCK→DISMOUNT→MEDIA_REMOVAL→EJECT;CreateFileW 在 windows crate 需要 `Win32_Security` feature
7. **鼠标提示 tooltip** 用 `.tooltip-wrap`(index.css @layer components 内, 覆盖 Tailwind absolute 冲突)
8. **App.tsx 键盘快捷键** effect 依赖含 viewerIndex(viewer 打开时屏蔽)
9. 视频双击不打开查看器(禁用)

10. **可见区域全图预加载**:设置开关对 RAW/JPEG/PNG 等所有照片格式一视同仁;App 用 IntersectionObserver 监听当前可见卡片,逐个调用 `get_full_image` 预热,开关即时生效无需重启

11. **EXIF 方向**:缩略图/预览/全图在 Rust 侧统一读取 EXIF Orientation 并物理校正;非 RAW 原图另加 CSS `image-orientation: from-image`

12. **缩略图加速**:优先用 Windows Shell `IShellItemImageFactory`(Explorer 同款缩略图缓存),失败才走解码兜底;批量生成并发 4 个,不再逐张排队

## 6. 用户偏好(来自长期协作)

- 能用开源库就用,不要自己造轮子;用前先查 GitHub/文档验证
- 改 bug 前先查清根因,不要瞎猜("停停停,你上网查查行不行")
- 修复后不要让旧问题回归
- 界面要美观精致(圆角/渐变/动画),深色为主 + 浅色主题
- 发布流程:tauri build → 上传安装包到 GitHub release
