# ImageFilter 架构与机制走查报告

> 走查对象：`A:\tenent`（git `master` @ `4f1068f`，版本 `1.0.1`，tag 已推、未建 Release）
> 走查方式：逐文件精读（前端 + Rust + C++ 桥接 + 配置）、对关键算法用真实图片实测复算、跨提交比对、逐字核对 CSS 级联与 CSP
> 总体结论：**架构分层清晰，安全边界（asset scope / FFI / 导入覆盖策略）收得比同类项目紧**；但"废片判定"这条业务主线存在**已实测证实失效**的核心指标，另有若干会导致误写星级、查看器崩溃的缺陷；全项目零测试。

---

## 0. 速览

| 维度 | 事实 |
|---|---|
| 定位 | SD 卡/相机照片的**初筛导入工具**：插卡 → 秒级预览 → 星级/废片筛选 → 按模板归档 |
| 形态 | Tauri 2 桌面应用，单窗口 1200×800（最小 900×600）、**无边框 + transparent**，标题栏 React 自绘 |
| 规模 | 前端 ~2.8k 行 TS/TSX（App 620 行 + viewer 405 + useScanner 367 + 22 组件），后端 ~2.0k 行 Rust + C++ DNG 解码桥接，CSS 252 行 |
| 前端栈 | React 19 + TS 5.7 + Tailwind 4 + `@base-ui/react` + IconPark + i18next，**无状态库、无路由、无动画库** |
| 后端栈 | tokio + sqlx(SQLite) + rawler/WIC/tinydng(DNG) + zune-jpeg + imgfprint + kamadak-exif + rayon + memmap2 |
| 状态 | 首轮走查时**零自动化测试**；第二轮已补 **22 个 Rust 单测**（analyzer/importer/images），**前端仍无测试**，CI 只构建不测试；`HANDOFF.md`/`PROJECT_LOG.md` 被 `.gitignore:10-11` 忽略，且已与代码漂移（见 D2） |

**一句话架构**：单页三栏 UI（左设备树 / 中网格 / 右 EXIF）+ 全屏查看器；业务状态全部收敛在 `useScanner()`，经 21 个 Tauri command 命令式取数，长任务用 `Channel` 流式回推进度。

```
┌──────────────────────── WebView (React 19) ─────────────────────────┐
│ TitleBar │ FloatingPanel(左) │ 网格+PhotoToolbar+ImportBar │ EXIF(右) │
│   └── useScanner()   ← 唯一业务状态源（设备/树/照片/勾选/星级/导入/分析）│
│   └── PhotoViewer    ← 独立状态机（渐进加载 + 缩放/旋转/评分）          │
└───────┬────────────────────────────────────────────────┬────────────┘
        │ invoke(21 cmds) + Channel(进度)                 │ asset://（图片字节）
┌───────▼────────────────────────────────────────────────▼────────────┐
│ Rust: scanner{drives,browse,exif,images} analyzer importer db        │
│  解码链: 内嵌JPEG(mmap) → WIC(系统 codec) → rawler 全解码 → tinydng   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. 运行形态与命令面

**命令面**（`src-tauri/src/lib.rs:100-121`，共 21 个）：

| 域 | 命令 | 说明 |
|---|---|---|
| 基建 | `allow_asset_dir` / `set_glass_bg` | 运行时放行 asset 目录；切换 Windows Mica 深/浅 |
| 设备 | `detect_drives` / `open_folder` / `eject_drive` | 枚举盘符、系统打开目录、IOCTL 弹出 |
| 浏览 | `browse_directory` / `count_folders` / `scan_directory` | 单层列表 / 递归计数 / 列当前层照片 |
| EXIF | `get_exif` | 选中照片时按需读取 |
| 图像 | `get_thumbnail_path` / `get_preview_image` / `get_full_image` / `batch_thumbnails` | 三级解码 + 批量流式 |
| 导入 | `import_photos`（`Channel<ImportProgress>`） | 复制 + MD5 校验 + 写历史 |
| 分析 | `analyze_photos`(Channel) / `find_duplicates` / `stop_analysis` | 模糊曝光流式；重复检测；中止 |
| 数据 | `get_import_history` / `get_rules` / `save_rule` | SQLite |

**启动序列**（`lib.rs:56-99`）：放行缓存目录 → 定位 `app_data_dir/image-filter.db` → `db::init_db` 建表（`import_history`、`import_rules` + `INSERT OR IGNORE` 默认规则）→ `manage(DbState)`。外设变化靠**前端每 5s 轮询** `detect_drives`（`App.tsx:162-166`），后端不监听设备事件。

**线程模型**（性能关键）：
- 所有阻塞 I/O（复制、双端 MD5、解码）都进 `spawn_blocking`，不占 tokio worker（`importer.rs:269`；`images.rs:202/226/243/277/295`）。
- 批量缩略图：`JoinSet` + `Semaphore(4)`，每张完成即经 Channel 回推（`images.rs:397-423`）。
- RAW 全解码：全局 `Semaphore(1)` + `AtomicU64` 任务号，新请求让旧请求在临界区前后两处自检放弃（`images.rs:7-13,192-198,265-271`）——"快速翻 RAW 不卡死"的核心。

---

## 2. 数据流主线（插卡 → 选片 → 导入）

```
插卡
 └─ detect_drives(5s 轮询) ────────────► 左栏设备列表（可移动优先）
点设备
 └─ browse_directory(单层) ────────────► 建根节点 + 子文件夹
    ├─ allow_asset_dir(mount)            （asset scope 按需放行）
    └─ count_folders(子目录数组) ──异步─► applyCounts() 回填各文件夹照片数
点子文件夹
 └─ loadFolder(path)
    ├─ scan_directory ──► ScannedPhoto[]（名/大小/是否RAW/是否视频/mtime，**不含 EXIF**）
    ├─ batch_thumbnails(Channel) ──► 落盘缓存 → convertFileSrc 存入 thumbnails[源路径]
    └─ browse_directory ──► mergeChildren() 懒加载下一层 + updateHasSubdirs()
单击照片 ─► selectedPhoto + get_exif（按需富化该条）
双击照片 ─► PhotoViewer（缩略图铺底 → 内嵌JPEG → 全解码，三级替换）
勾选/Shift 范围选 ─► selectedPaths
导入 ─► import_photos(模板, Channel) ─► 检查中→复制中→完成/跳过/错误
```

---

## 3. 核心机制逐条拆解

### 3.1 设备识别与安全弹出

- **枚举**：遍历 `A..Z`，`Path::exists()` 过滤无介质盘，类型由 `inspect_path`（Windows 后端即 `GetDriveTypeW`）映射为 `removable/fixed/network/cdrom/ramdisk`，**可移动优先排序**（`drives.rs:156-199`）。卷标用裸 `extern "system"` 声明 `GetVolumeInformationW`，失败回退 `D:\ (可移动)`（`drives.rs:22-40,180-184`）。
- **macOS**：读 `/Volumes`，跳过隐藏卷与 `Macintosh HD`（防误弹系统盘），全部按 removable 处理（`drives.rs:206-223`）；Linux 返回空表。
- **弹出**（`drives.rs:59-118`）：`\\.\D:` → `CreateFileW(GENERIC_READ|GENERIC_WRITE, FILE_SHARE_READ|FILE_SHARE_WRITE, OPEN_EXISTING)` → `FSCTL_LOCK_VOLUME(0x00090018)` → `FSCTL_DISMOUNT_VOLUME(0x00090020)` → `IOCTL_STORAGE_MEDIA_REMOVAL(0x002D4804)` → `IOCTL_STORAGE_EJECT_MEDIA(0x002D4808)`，三条退出路径均 `CloseHandle`；macOS 走 `diskutil eject`。
- **缺口**：`DriveInfo` 无剩余容量字段（`drives.rs:7-12`），`available` 恒 `true`——产品若要显示"卡剩余空间"需新增。

### 3.2 目录浏览：单层懒加载 + 后台计数

- `browse_directory` **只列一层**，子项 `photo_count=0`、`subfolders=[]`，仅探一层 `has_subdirs`（`browse.rs:22-31,65-74`）。前端 `entryToNode/mergeChildren/updateHasSubdirs/applyCounts`（`useScanner.ts:7-52`）在不可变树上做增量合并，避免整树重建。
- 计数：`count_folders` → `count_photos_recursive`，`canonicalize` 结果入 `visited` 集合做**符号链接环检测**，另有 `depth>64` 兜底，符号链接目录单独分支（`browse.rs:89-107`）。
- 扩展名白名单集中在 `scanner/mod.rs:8-17`（RAW 十种 + 视频四种等）。
- `scan_directory` 非递归且**不解析 EXIF**（`exif=default`），这是"进文件夹秒出列表"的前提（`browse.rs:122-181`）。

### 3.3 三级解码链路与缓存（技术核心）

| 级别 | 命令 | 实现 | 时延特征 |
|---|---|---|---|
| 缩略图 | `get_thumbnail_path` / `batch_thumbnails` | **Windows Shell `IShellItemImageFactory`（与资源管理器同源）**，失败才回落解码；JPEG 走 zune-jpeg，RAW 抽内嵌 JPEG，视频用 ffmpeg 抽帧 | 并发 4，落盘后不重复解码 |
| 预览 | `get_preview_image` | RAW：mmap 扫描 SOI/EOI 取**分辨率最大的内嵌 JPEG**（16MB 前瞻上限；无 EOI 的坏文件跳过签名，避免 O(n²)）；非 RAW 直接返回原路径 | 秒开 |
| 全图 | `get_full_image` | ①内嵌预览 ≥3000px 直接用 ②WIC 系统 codec ③rawler `raw_to_srgb` 全解码（`catch_unwind` 包裹）④位深自适应灰度兜底 | 慢，有取消机制 |

- **缓存**：`%LOCALAPPDATA%\image-filter\{thumbnails_v2,preview_v3,full_v3}`（非 Windows 为 `~/.cache/image-filter`），键 = `hash(路径 + mtime秒)`，缩略图键额外带 `maxSize`（`images.rs:17-23,440`）。
- **`full_v3` 只认全图**：`FULL_MIN_EDGE=1500`，命中但尺寸不足则删除重建（`images.rs:37-41,180-185`）——防止低分辨率缓存污染高清显示。
- **DNG 独立路径**（`images.rs:191-255`）：rawler `raw_to_srgb` → tinydng → WIC → 纯 rawler，每步都要求 `is_full_res_image` 且 `resize_max_edge(5000)`。
- **方向校正统一**：`apply_exif_orientation` 先 flip 后 transpose，映射交给 `rawler::Orientation::from_u16(...).to_flips()`（`images.rs:79-98`）；非 RAW 原图另加 CSS `image-orientation: from-image`（`index.css:60-62`）。
- **位深自适应**（`images.rs:531-547`）：按数据实际最大值归一化，避免固定 65535 让 8-bit 全黑、12/14-bit 偏暗——注释写明了这是踩过的坑。

### 3.4 EXIF 与模板变量

`exif_common` 收敛三件事：`open_exif`（kamadak-exif `read_from_container`）、`orientation`(0x0112)、`first_text_field`。`get_exif` 读 Make/Model/Lens/FocalLength/FNumber/ExposureTime/ISO/DateTimeOriginal/尺寸（`exif.rs:21-60`）。
模板变量 `{date} {year} {month} {day} {camera} {original} {ext} {seq}`，日期与相机取自 EXIF，**全部经 `sanitize_path` 洗掉 `< > : " / \ | ? *` 并把空格换 `_`**（`importer.rs:24-112`）。

### 3.5 查看器状态机（`viewer.tsx`）

HANDOFF 明令"别顺手简化"的部分，实测确有四条加载分支：
1. **进入/退出动画**：先按缩略图矩形渲染，30ms 后 `clip-path` 过渡到全屏；关闭先缩回再等 250ms 真关（`viewer.tsx:109-120,282-289`）。
2. **首次加载**：缩略图铺底 → `get_preview_image`（单次切换零延迟发）→ 600ms debounce 后 `get_full_image` 后台替换淡入；**切换时故意不清空 `src`**，避免空 src 黑帧（`viewer.tsx:104,135`）。
3. **快速连切**：`now - lastSwitch < 500ms` 判为 rapid，预览请求 debounce 120ms 才发（`viewer.tsx:180-203`）。
4. **邻居预取**：`±1` 张 250ms debounce 预取预览存入 `loadedSrcRef`，回切命中即秒显（`viewer.tsx:68-91`）。

交互：滚轮缩放 0.2–8×（≤1 自动居中复位）、放大后拖拽平移、`R/Shift+R` 旋转、`0` 复位、空格切换勾选、`←/→` 循环切换、`J/X/1-5` 评分。

### 3.6 可见区全图预加载（`App.tsx:307-371`）

`IntersectionObserver`（`rootMargin:"250px"`, `threshold:0.01`）监听所有 `[data-photo-path]` 卡片（`photo-card.tsx:40`），维护 `visiblePaths`；开关打开时以 300ms 延迟启动**串行队列**逐张 `get_full_image` + `img.decode()` 预热，用 `preloadVersionRef` 版本号在依赖变化时中止旧队列。开关持久化在 `imagefilter-preload-full`，即时生效无需重启。

### 3.7 导入引擎（`importer.rs`）——数据安全设计最扎实的一环

- **绝不静默覆盖**：目标存在 → 双端 MD5 比对：相同 `skipped`（不计数），不同则循环 `_1/_2/...` 唯一名（上限 9999）（`importer.rs:136-221`）。
- **复制后校验**：再次双端 MD5，不一致报错（`importer.rs:206-211`）。
- **路径逃逸纵深防御**：`is_safe_relative` 要求目标路径全为 `Component::Normal`（`importer.rs:126-130`）。
- **留痕**：校验通过写 `import_history`，写失败仅 `eprintln` 不影响导入（`importer.rs:321-332`）。
- 进度经 `Channel<ImportProgress>` 推 `checking/copying/done/skipped/error`；前端只留最近 100 条防数组膨胀（`useScanner.ts:262-265`）。

### 3.8 废片分析（`analyzer.rs`）——⚠ 见 D1/D3

- 模糊：`laplacian_variance` + `score < 100` → `is_blurry`。
- 曝光：`>250` 占比 >15% 判过曝、`<5` 占比 >30% 判欠曝（`analyzer.rs:67-87`）。
- 重复/连拍：`imgfprint` 多哈希指纹，**按文件大小 ±16KB 分桶**只比同桶与相邻桶（O(n²)→近线性），相似度 >0.85 归组，组内按"最高模糊分"选最佳（`analyzer.rs:145-234`）。
- 可中止：全局 `AtomicBool`，串行循环每项检查（`analyzer.rs:100-134`）。
- RAW 走内嵌 JPEG 分析（`load_analysis_image/bytes`），避免 `image::open` 静默失败。

### 3.9 安全模型（比同类项目收得更紧）

- **asset 协议 scope 收紧为空**（`tauri.conf.json:29-32`），启动仅放行缓存目录（`lib.rs:59-79`），用户浏览到的设备/文件夹/目标目录由前端调 `allow_asset_dir` **按需放行**（`useScanner.ts:17-20,162,201,246`）——取代早期 `["**"]` 全盘通配。
- **CSP**（`tauri.conf.json:26`，逐字）：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' asset: http://asset.localhost https://asset.localhost data:; font-src 'self' data:; connect-src ipc: http://ipc.localhost; media-src 'self' asset: http://asset.localhost https://asset.localhost`，并开 `freezePrototype:true`、`dangerousDisableAssetCspModification:false`。
- **capabilities**（`capabilities/default.json`）：`core:default`、window 精细操作（start-dragging/minimize/toggle-maximize/close/set-resizable）、app/path/event/resources、fs/dialog/notification/opener 默认集，**无 shell 权限**。
- **FFI 双侧校验**：tinydng Rust 侧（`tinydng.rs:32-49`：维度 ≤20000、`checked_mul` 防回绕、缓冲 ≤512MB）与 C++ 侧（`bridge.cpp:30-42`：同上限 + `spp≤8` + `data.size() < w*h*spp*bps` 拒绝）各校验一遍；WIC 统一 `w*h*4 > 1GB` 拒绝（`win_wic.rs:80,147`）。
- unsafe 仅集中在 4 个文件约 25 处；运行期热点无 `unwrap()/panic!()`（`lib.rs:84,123` 的两个 `expect` 在启动期）。

---

## 4. 前端 UI 与配置

### 4.1 组件图与职责

```
main.tsx ─► App.tsx（TitleBar + 三栏 + Toast + 查看器）
 ├─ TitleBar（窗口控制 + 主题/语言/玻璃；调 set_glass_bg，title-bar.tsx:55-60）
 │   └─ SettingsDialog（全部设置项）/ HelpDialog（HelpContent → Step）
 ├─ FloatingPanel(左, panel.tsx) ─► 设备列表 + FolderTreeItem(递归) ─ 各自 PixelMenu
 ├─ main ─┬─ PhotoToolbar（全选/清空/排序/星筛/列数/AI，内含 CollapsibleBar + ThumbSizeSlider）
 │        ├─ ScrollFadeZone ─► 网格 PhotoGridItem(memo) ─► PhotoCard(memo)
 │        └─ ImportBar（目标目录/模板/进度末 4 条；含 CollapsibleBar + AdvancedOptions）
 ├─ FloatingPanel(右) ─► ExifPanel（Section/Row，空值 Row 返回 null）
 └─ PhotoViewer（全屏，独立状态机）
基础件：ui/{button,toggle,dialog}（@base-ui/react）+ Tip(.tooltip-wrap)/CollapsibleBar/ScrollFadeZone/Step
```

- 业务状态全部来自 `useScanner`（`App.tsx:90-139`），App 本地 state 仅 9 个：`ctxTarget / toast / viewerIndex / viewerOrigin / transparentBg / backgroundOpacity / toolbarOpen / importBarOpen / visiblePaths`。
- **memo 设计**：`PhotoGridItem`（`App.tsx:31`）与 `PhotoCard`（`photo-card.tsx:29`）双层 memo，配合 `photoMenuItems` 的 `useMemo`。但 `handlePhotoClick` 依赖 `[photos, lastClicked]`（`useScanner.ts:98`）→ 每次点击换引用 → 所有卡片的 `onToggle` 变化，**双层 memo 在点击时基本失效**（见 D10）。
- 折叠动画用 `grid-template-rows: 0fr/1fr`（`collapsible-bar.tsx:21-22`）；侧栏收起宽度 6px 且带 `inert`，`autoOpenKey` 变化自动展开（`panel.tsx:19-28`）。
- 右键菜单是**全项目唯一用 Radix 的地方**（`contextmenu.tsx`），其余基元为 `@base-ui/react`。
- `thumb-size-slider.tsx` 注入 `#imagefilter-grid-cols` 样式 + `imagefilter-cols`(2–8) 控制网格列数。

### 4.2 主题、样式与持久化

- **防闪**：`index.html:4-13` 在加载前同步读 `imagefilter-theme` 给 `<html>` 打 `dark` 类，与 TitleBar 同一 key。
- **`index.css`(252 行) 分层**：`@import` → `@custom-variant dark` → 未分层 `:root`(token) → `@theme inline` 映射 → `.dark` → `@layer base` → 未分层 `:root[data-theme="light"]`。token 是 shadcn 风格 OKLCh 全套，另有自定义 `--glass-opacity/--glass-bg/--background-opacity/--background-bg`。
- **浅色主题的实现很巧妙也很脆**：不靠逐元素改类，而是**在 `:root[data-theme="light"]` 下重新定义 Tailwind 的 `--color-zinc-*` 变量**（近黑↔近白反转，`index.css:226-252`）+ `color-scheme: light`，让硬编码的 `text-zinc-100/400` 等自动适配。代价是**任何未进 `@layer` 的同名规则会压过它**——`body{color:#fafafa}`（`:54-58`，未分层）正是如此（见 D8）。
- **毛玻璃**：Rust 侧 `Effect::MicaDark/MicaLight`（`lib.rs:31-34`），前端 TitleBar 调 `set_glass_bg`；非 Windows 平台后端直接返回、前端降级为不透明（`lib.rs:41-45`）。`--background-opacity` 由 App 写入 `documentElement` 实现"浮窗后整块背景可调透明度"。
- **`.tooltip-wrap` 的存在理由**：`position:relative` 放在 `@layer components`（`index.css:111-113`），以便 utilities 层的 Tailwind `absolute` 能覆盖它（viewer/panel 的浮层提示需要）；`.tooltip` 样式放文件末尾无层以取得最高优先级（`:114-134`）。

| localStorage key | 用途 | 默认 |
|---|---|---|
| `imagefilter-theme` | 深/浅色 | dark |
| `imagefilter-lang` | 中/英 | zh |
| `imagefilter-glass` / `imagefilter-glass-opacity` | 透明背景开关 / 玻璃不透明度 | 开 / 70 |
| `imagefilter-background-opacity` | 浮窗后背景透明度 | 0 |
| `imagefilter-preload-full` | 可见区全图预加载 | false |
| `imagefilter-ratings` | 星级（按路径，JSON） | {} |
| `imagefilter-cols` | 网格列数 | 4 |

### 4.3 i18n

`i18n/index.ts:18-27`：zh/en 双资源、`lng` 读 `imagefilter-lang`（默认 zh）、`fallbackLng:"zh"`，并把插值改成**单括号 `{n}`**（`prefix:"{"`, `suffix:"}"`, `escapeValue:false`）。经实测核对：zh/en 各 171 个 key 行（含 13 个分组键，叶子键 158），**名称序列 `Compare-Object` 无任何差异**，无"代码用而未定义"也无"定义未引用"。`setLanguage()` 切换即回写。

### 4.4 构建与配置

- `vite.config.ts`：`react()` + `tailwindcss()`、`@ → ./src`、**端口 1420 `strictPort`**（故旧 vite 进程不退出会直接 `beforeDevCommand terminated with non-zero status`）、HMR ws:1421 仅当 `TAURI_DEV_HOST`、watch 忽略 `src-tauri/**`。
- `tsconfig`：ES2021 + strict + `noUnusedLocals/Parameters` + `paths @/*`，`include:["src"]`（不含 `src-tauri`）。
- `tauri.conf.json`：`decorations:false` + `transparent:true`、`frontendDist ../dist`、`beforeDevCommand "npm run dev"`、bundle targets `all`、NSIS（currentUser + 简中/英 + 语言选择器）、WiX 中文。
- 版本号 `1.0.1` 硬编码在两处（`tauri.conf.json:5`、`settings-dialog.tsx:126`），加上 `package.json`/`Cargo.toml` 共四处，HANDOFF 只提到三处（见 D13）。

---

## 5. 走查发现

> 编号按发现顺序，非严格严重度排序。
> **状态**：D1 / D2 / D3 / D4 / D5 / D8 已于本轮修复，D14 由新增单测当场抓出并一并修复；全部有测试或构建证据，详见 **§7 修复记录与验证**。
> D1–D4 会直接影响用户的选片决策、写入错误数据或导致界面报错，属优先项。

### D1（严重·已实测）模糊检测实际失效，徽标几乎永不出现

`laplacian_variance`（`analyzer.rs:22-64`）返回的并不是"拉普拉斯方差"：它先求 `mean = 平均|拉普拉斯响应|`，再求**灰度值相对这个 mean 的方差**，即 `score ≈ E[(gray − mean|L|)²]` —— 这基本只反映**全局亮度离散度**，与对焦清晰度无关。判定用 `score < 100`（`analyzer.rs:97`），徽标由 `photo-card.tsx:71`（`grid.blurry`）渲染。

实测（对 `assets/screenshot.png` 按该函数逐行等价复算）：

| 用例 | 本实现 score | 是否判模糊(<100) | 教科书拉普拉斯方差 |
|---|---|---|---|
| 原图 | **56569** | 否 | 534.1 |
| 高斯模糊 r=3 | 57987 | 否 | 0.5 |
| 高斯模糊 r=8 | 57823 | 否 | 0.3 |
| 高斯模糊 r=15 | 57533 | 否 | 0.3 |
| 纯灰(128) | 16384 | 否 | 0.0 |
| 近乎黑(20) | 400 | 否 | 0.0 |

**模糊 15px 的图得分与原图几乎一样，连纯灰图都远超阈值**——只有近乎全黑的图才可能 <100。即"模糊"筛选在真实使用中不生效，阈值注释（`<100 模糊 / 100–300 偏软 / >300 清晰`）与实际语义完全对不上。
**建议**：改为真正的 `lap.var()` 并在真实样张上重新标定阈值，或换成熟库；无论如何补一个单测锁住指标。

### D2（严重）查看器打开时，全局快捷键会误改"另一张照片"的星级

`App.tsx:169-180` 的 handler 有 `if (viewerIndex !== null) return;`，但 effect 依赖是 `[selectedPhoto, setRating]`——**漏了 `viewerIndex`**，闭包里的值永远是上次执行时的旧值（通常 `null`）。于是查看器打开后：

- `viewer.tsx:244-246` 给**当前查看的照片**评分；
- 同时 `App.tsx:174-176` 也触发，给**上次单击选中的照片**（可能是另一张）评分。

用户在查看器里翻到第 N 张再按星，那张"被选中但没在看"的照片会被静默改星级并写入 `imagefilter-ratings`。`HANDOFF.md:103` 声称"effect 依赖含 viewerIndex"，与代码不符；跨提交比对（`2a1f936` → `4f1068f`）显示该写法上一版就存在，**不是本轮修复的回归，是文档一直写错**。
**建议**：依赖数组补 `viewerIndex`（最小改动），或把评分快捷键收敛到一处、viewer 打开时由其独占。

### D3（中高）连拍"最佳"选择依赖 D1 的失效分数

`find_duplicates` 用"最高模糊分"选组内最佳（`analyzer.rs:222-228`），而该分数已证明不反映清晰度；前端还给 `isBestInGroup` 打绿色"最佳"徽标（`photo-card.tsx:75`）引导保留。等于**在连拍组里可能推荐错的那张**。
**建议**：与 D1 一并修，修好后改为"清晰度 + 曝光"综合而非单一分数。

### D4（中高）查看器索引不与列表重同步：轻则跳图，重则界面报错

`viewer` 的 `cur` 是内部 state（`viewer.tsx:43`），列表变化时不重同步；而 `sortedPhotos` 会因 `starFilter`/`ratings` 变化而收缩（`App.tsx:183-202`）。触发链：
1. 用户在工具栏设了星级筛选（如 ≥2 星），打开查看器；
2. 按 `X`（0 星）或低于阈值的星 → 该图立刻被过滤出 `sortedPhotos`；
3. 列表变短 → `photos[cur]` 可能变 `undefined` → `viewer.tsx:278` 的 `if (!photo) return null` 让**查看器凭空消失**（而 `viewerIndex` 仍非 null）；
4. 更糟：`viewer.tsx:235-248` 的键盘 handler 已在早退之前注册，其 `j/x/1-5` 分支直接读 `photo.path` → **按键即 `TypeError: Cannot read properties of undefined`**（`←/→` 分支因 `navigateTo` 有 `if (!target) return` 而安全）。

另有同源隐患：`useScanner.loadFolder` 重置了 `photos`，但 `viewerIndex` 在 App 里、无人清理（`useScanner.ts:198-206`），若将来放开"查看器打开时切文件夹"，会直接命中同一路径。
**建议**：viewer 内 `useEffect` 监听 `photos.length/cur` 做 clamp 或自动关闭；键盘 handler 首行加 `if (!photo) return;`；`loadFolder` 时把 `viewerIndex` 置空。

### D5（中）tinydng 早退路径内存泄漏

`tinydng.rs:28-30`：`if ret == 0 || rgb.is_null() || w <= 0 || h <= 0 { return None; }`——当 C 侧已 `malloc`（`rgb` 非空）却返回非正尺寸时直接返回，**未调用 `tinydng_free`**（后两条失败路径 `:43,:47` 都正确释放）。异常 DNG 反复触发会累积泄漏。
**建议**：把释放统一到校验失败分支，或在早退前先判 `!rgb.is_null()` 再 free。

### D6（中）`IOCTL_STORAGE_MEDIA_REMOVAL` 未真正生效

`drives.rs:91-109` 对 `PREVENT_MEDIA_REMOVAL` 传 `None/0` 输入缓冲且丢弃返回值（`let _ = ioctl(0x002D4804)`）。该 IOCTL 需要 `PREVENT_MEDIA_REMOVAL{ PreventMediaRemoval: FALSE }` 结构体入参；当前等于"名义上走完整序列"。弹出本身仍靠最后的 `EJECT_MEDIA` 成功，但与注释"与资源管理器一致"有出入；另 `CreateFileW` 未请求 `FILE_SHARE_DELETE`。
**建议**：按 MSDN 传结构体入参，或把注释改为"尽力而为的弹出序列"。

### D7（中）EXIF 读取对大文件是"全量进内存"

`exif_common::open_exif` 走 kamadak-exif `read_from_container`，其 TIFF 分支会把**整个文件读入内存**；而 `get_exif` 每选中一张就调一次，导入时模板变量还要再读日期/相机（`importer.rs:84-97`）。对 50–100MB 级 RAW/TIFF，单张选择即一次全量读。
**建议**：只读文件头（RAW 的 EXIF 在头部/内嵌 JPEG 内），或对同一路径缓存结果、导入时复用；另需实机确认 CR3(ISOBMFF)/RAF 能否取到 EXIF（代码路径存在但无样本验证）。

### D8（中低）浅色主题的基础文字色被未分层规则压过

`body { color: #fafafa }`（`index.css:54-58`，**未分层**）在级联层规则下优先于 `@layer base` 的 `body { @apply text-foreground }`（`:213-219`）。因此浅色主题下，**任何没有显式文字色类的元素会继承近白色**，落在近白背景上对比度不足甚至不可见。多数元素因硬编码了 `text-zinc-*`（浅色下被反转为深色）而幸免，属"少数漏网元素出问题"的隐性缺陷。
**建议**：删掉未分层的 `color`（让 `text-foreground` 生效），或把它移入 `@layer base`。

### D9（低）跳过被计为失败、`{seq}` 跳号

- `useScanner.ts:278`：`const failed = paths.length - count;`——`import_photos` 返回的 `count` 不含 `skipped`，因此**"已跳过"被计入失败数**，完成提示的数字会误导。
- `importer.rs:261,317`：`{seq}` 用 `imported + 1`，而 `skipped` 提前 `continue` 不递增 → 序号跳号（0001、0003…）。
**建议**：`import_photos` 改返回 `{imported, skipped, failed}`；`{seq}` 用独立计数器或在跳过时也递增。

### D14（中高·本轮由新增单测当场抓出）文件夹模板的多级目录被压平

`build_dest_path` 对替换后的文件夹模板调用的是 `sanitize_path`，而它会把手写的路径分隔符也替换成 `_`（`importer.rs`）。而 UI 恰恰是用 `/` 拼多级模板的：同时勾选"按日期 + 按相机"会生成 `{date}/{camera}`（`advanced-options.tsx:28,36`），界面提示也写的是"如 2024-08-08/照片.jpg"（`i18n/zh.ts:134,136`），README 更承诺 `{date}/{camera}/{seq}.{ext}` → `2026-08-10/Sony_A7M4/0001.ARW`。

实际结果：**两级目录被压成单层** —— `2026-08-10_Sony_A7M4/0001.ARW`。用户在界面上勾了两个"分文件夹"选项，却得到一层带下划线的目录。
**建议（已实施）**：新增 `sanitize_template_path` —— 按 `/`、`\` 拆段、逐段清洗后再用 `/` 连接，目录层级得以保留，段内非法字符仍会被清掉。

### D10（低）两类渲染/取消开销

- **5s 轮询整树重渲**：`App.tsx:162-166` 每 5s `setDrives(newArray)`，数组身份必变 → App 与左栏必然重渲染（网格因 memo 幸免）。建议比对 `mountPoint+driveType+label` 序列后再 set。
- **memo 在点击时失效**：`handlePhotoClick` 依赖 `[photos, lastClicked]`（`useScanner.ts:98`）→ 每次点击所有卡片 `onToggle` 变引用 → 双层 memo 白设。建议改用 `useRef` 存 `lastClicked` 或用函数式更新去掉依赖。
- **死取消逻辑**：`App.tsx:367` 在 `setTimeout` 回调体内 `return () => {cancelled = true}`，该返回值被 `setTimeout` 丢弃，`cancelled` 恒 `false`；真正生效的是版本号比较（`:345`）。建议删除以免误导后人。

### D11（低）无缩略图的 RAW 在 EXIF 面板显示空白

`App.tsx:373-377`：没有缩略图时直接 `convertFileSrc(selectedPhoto.path)`，RAW 文件 WebView 无法解码 → 预览空白（快速点选或缩略图失败时暴露）。建议回落 `get_preview_image` 取内嵌 JPEG。

### D12（工程缺口）全项目零测试

> **第二轮已部分处理**：新增 22 个 Rust 单测（覆盖模糊指标、尺度归一化、曝光阈值、JPEG 段解析、导入模板与路径安全、"绝不覆盖"契约、感知哈希距离序），另有 2 个默认忽略的标定/计时助手。**前端仍无测试**（viewer 四条加载分支与重锚逻辑只有类型检查 + 推演）。

无任何 `#[test]`/`#[cfg(test)]`，前端无 vitest/jest 配置与测试文件，CI（`.github/workflows/build.yml`）只构建不测试。而项目恰有几处**只能靠回归测试守住**的逻辑：viewer 四条加载分支（HANDOFF 自述"每个分支都是修视觉 bug 换来的"）、`full_v3` 的 ≥1500px 缓存有效性、导入的"相同跳过/不同改名"策略、模糊/曝光阈值。**D1 这类纯计算错误，一个 20 行单测就能拦住** —— 事实上 D14 就是新增单测当场抓出来的。
**建议**：优先给 `analyzer.rs`（纯函数最易测）、`importer.rs::{build_dest_path,sanitize_path,is_safe_relative}`、`images.rs::jpeg_dimensions` 补 Rust 单测；前端先测 `useScanner` 里的纯函数（`mergeChildren/applyCounts/updateHasSubdirs`）。

### D13（细节与一致性）

- `App.tsx:502` 与 `:526` **用同一 `emptyMenuItems` 嵌套了两层 `PixelMenu`**，覆盖同一区域，属冗余包装（左栏 `:397`/`:407` 的嵌套是不同菜单，属有意设计）。
- `App.tsx:619-620` 遗留注释 `/** Recursive folder tree item */` 贴在 `export default App;` 上，与代码无关（该组件已拆到 `components/folder-tree-item.tsx`）。
- **零引用的 UI 基元**：`ui/badge.tsx`、`ui/input.tsx`、`ui/separator.tsx` 无人引用（仅 `dialog`/`toggle`/`button` 在用），是 shadcn 生成后的残留。
- `components.json:13` 仍写 `iconLibrary:"lucide"`、`:24` 带未使用的 `@react-bits` registry，与 `App.tsx:17-21` 明令"一律 IconPark"的约定冲突，易误导后续脚手架命令。
- 版本号硬编码四处（`package.json`/`Cargo.toml`/`tauri.conf.json:5`/`settings-dialog.tsx:126`），HANDOFF 只提三处。
- 类型重复：`photo-card.tsx:34` 内联了 `analysis` 形状，与 `types.ts:48` 的 `AnalysisResult` 重复定义。
- `index.html:2` 固定 `lang="zh-CN"`，切英文不同步；`App.tsx:222` 的 `replace(/\\[^\\]+$/,"")` 只适用 Windows 路径分隔符（macOS 下"打开所在位置"会取不到目录）。
- CSP 未声明 `object-src` / `base-uri` / `form-action`（Tauri 默认注入会补部分，但显式声明更稳妥）；`index.html:4` 的内联防闪脚本依赖 Tauri 的 CSP 改写注入 nonce/hash（`dangerousDisableAssetCspModification:false` 保持了这一行为，**不要改成 true**）。

---

## 6. 设计取舍与风险地图

**值得保留、不要"顺手简化"的**
1. asset scope 从 `["**"]` 收紧为"空 + 运行时按需放行"——安全与可用性的正确折中。
2. 导入的"绝不静默覆盖 + 双端 MD5 + 唯一后缀 + 写历史"——照片工具最不能出错的地方，这里做对了。
3. 任务号 + `Semaphore(1)` 的 RAW 解码取消；`full_v3` 只认 ≥1500px 的缓存有效性校验。
4. FFI 双侧边界校验（Rust + C++）、`checked_mul` 防回绕、WIC 1GB 上限。
5. viewer 四段加载分支与"切换不清空 src"——都是视觉 bug 的直接经验产物。
6. 目录计数的符号链接环检测 + `depth` 兜底；浅色主题用反转 Tailwind 色板变量而非逐元素改类（思路聪明，但要防 D8 那类级联层冲突）。

**脆弱点**
1. **经验型代码缺测试**（D12）：viewer 状态机与缓存有效性最容易被后人"优化"坏。
2. **文档与代码漂移**（D2）：HANDOFF 是这批坑位的唯一载体，却被 `.gitignore` 忽略、不进仓库 → 新协作者拿不到；本报告即为此缺口补的仓库内文档。
3. **列表驱动的索引无同步机制**（D4）：查看器/过滤/列表三者的不变式没有任何代码或测试兜底。
4. **macOS 未实机验证**（README 自述"缺乏构建环境"）：`/Volumes` 枚举、`diskutil eject`、无 Mica 降级、`~/.cache` 这条链只有代码没有证据；D13 的路径分隔符问题也在这一侧。
5. **零 CI 测试门禁**：没有测试就无法加门禁，形成循环——建议从 `analyzer.rs` 破局。

---

## 7. 修复记录与验证（本轮）

### 改动清单

| 发现 | 改动 | 文件 |
|---|---|---|
| D1 | `laplacian_variance` 改为**真·拉普拉斯响应方差**；新增归一化常量 `BLUR_ANALYSIS_EDGE = 1024` 与 `sharpness_of()`；阈值提为具名常量 `BLUR_THRESHOLD` 并在注释里写明标定依据 | `analyzer.rs` |
| D3 | 连拍组内"最佳"改为按 `(曝光是否正常, 清晰度)` 字典序比较；`find_duplicates` 复用同一次图像加载计算曝光，并把原来匿名的 4 元组换成具名 `Entry` | `analyzer.rs` |
| D2 | 快捷键 effect 依赖补 `viewerIndex`；**并把 `viewerIndex`/`viewerOrigin` 的声明上移到该 effect 之前**（依赖数组在渲染时急切求值，声明在后会触发 TDZ） | `App.tsx` |
| D4 | 查看器按"正在看的照片路径"重锚（`anchorPathRef`）：锚点仍在列表中就跟随其索引，已消失则关闭查看器；键盘 handler 首行加 `if (!photo) return`；切换文件夹时关闭查看器 | `viewer.tsx`、`App.tsx` |
| D5 | tinydng 早退路径补 `tinydng_free` | `tinydng.rs` |
| D8 | 删除未分层的 `body { color: #fafafa }`，正文颜色交回 `@layer base` 的 `text-foreground` | `index.css` |
| D14 | 新增 `sanitize_template_path`，保留模板中的目录层级 | `importer.rs` |
| D12（部分） | 新增 16 个 Rust 单测（analyzer 7 + importer 9），覆盖模糊指标、尺度归一化、曝光阈值、模板与路径安全、"绝不覆盖"契约 | `analyzer.rs`、`importer.rs` |

### 验证证据

```
$ cargo test --lib
running 16 tests
test result: ok. 16 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 8.78s
cargo exit code = 0

$ tsc --noEmit        → exit 0
$ vite build          → ✓ built in 7.90s （exit 0）

阈值标定实测（同一张图, 长边 → 拉普拉斯方差）:
  512 → 139.5    768 → 175.9    1024 → 203.4    1536 → 534.1     ← 归一化的必要性
归一化到 1024 后: 锐 1059.7 / 高斯 σ=2 → 8.3 / σ=6 → 0.5         ← 与阈值 100 的分离度足够
```

### 修复过程中的两个额外发现

1. **依赖数组会急切求值**：只把 `viewerIndex` 加进 deps 会直接触发 `ReferenceError`（tsc 报 TS2448/TS2454）。必须同时把状态声明上移 —— 这也解释了原作者为何"漏掉"这个依赖：不是疏忽，而是加进去就会炸。
2. **D14 是新单测当场抓出来的**：`build_dest_path` 的测试第一次运行就红了，报 `0000-00-00_Unknown\...` 而非嵌套目录 —— 测试没写错，是真缺陷。

### 尚未处理（保持原样，供后续排期）

- D6（`MEDIA_REMOVAL` 入参）、D7（EXIF 全量读）、D9（跳过计数 / `{seq}` 跳号）、D10（轮询与 memo）、D11（EXIF 面板 RAW 预览空白）、D13（一致性清理）。
- D12 只覆盖了 Rust 纯逻辑：**前端仍无测试**。viewer 的四条加载分支与新加的重锚逻辑目前只有类型检查 + 逐行推演，**未在 GUI 里实机验证**（建议下一步跑一次 `npx tauri dev` 手测：查看器内按 `1-5` 后核对网格里"选中那张"的星级是否被改动；开 ≥2 星筛选后在查看器内按 `X`，确认查看器关闭而非跳图）。

### 阈值使用提醒

`BLUR_THRESHOLD = 100.0` 的成立前提是"已归一化到 1024 长边"，且标定数据来自一张 UI 截图（高频细节偏多）。**真实 RAW 上的误报率需实机复核**：若"低细节场景"（大光圈虚化人像、雾天、大面积纯色天空）被误标模糊，应下调该常量 —— 代码注释里已标出位置。

---

## 8. 第二轮修复（真实使用反馈驱动）

用户在 `tauri dev` 里实测后反馈三个问题：**模糊误报高（大光圈虚化背景被报模糊）、重复标记太多、AI 分析时 CPU 占用高导致电脑卡**。本轮按"先定位 → 社区调研 → 最小改动 → 回归验证"的流程处理。

### 8.1 模糊误报：指标选错了聚合方式，且阈值标定样本错了

**根因有两层**：
1. **阈值标定样本错误**（上一轮的锅）：阈值 100 是在一张 UI 截图上标的（文字边缘多，全图方差 203），而真实照片的全图方差中位数只有 **52** —— 实测**25/28 张真实照片被判为模糊**。
2. **聚合方式不对**：全图方差会被大面积平滑背景拉低，而"主体清晰 + 背景虚化"的照片主体其实是清晰的。

**修复**：改为**归一化(1024 长边)后取 8×8 分块拉普拉斯方差的最大值**，并用真实样张重新标定阈值。

| 指标 | 真实照片(29 张) | σ=6 软化后 | 分离度 |
|---|---|---|---|
| 全图方差（上一轮） | 最小 36.1 / 中位 108.8 | 最大 33.4 | **1.08×（几乎无法分离）** |
| 分块最大值（本轮） | 最小 **122.4** / p10 188.4 / 中位 407.6 | 最大 **57.6** | **2.12×** |

阈值取 75（距最软的软图 1.30×、距最糊的真图 1.63×）→ 该样本上**真实照片误报 0/28、软化图命中 28/28**。Rust 实测分布（最小 125.9 / 中位 411.2）与独立 Python 复算（122.4 / 407.6）互相印证。

**社区调研结论**：Rust 生态**没有**可用的成熟无参考 IQA 库 —— crates.io 搜 `brisque` 只有 2 个结果，其中 `oximedia-quality` 的 BRISQUE **没实现 SVR 模型**（源码注释自认只是特征均值映射），其 blur 模块同样是全图 Laplacian，与我们的旧实现等价；CPBD/JNB 只有论文与 Python/C++ 参考实现，无 Rust 移植；OpenCV 的 `QualityBRISQUE` 需要用户自备 OpenCV C++ 与 `.yml` 模型，对 Tauri 打包是重负担。而 FastRawViewer 的 focus peaking 用的正是"局部高频/边缘"而非全图均值 —— **分块取最大是当前生态下的正确务实解，不是将就**。

### 8.2 CPU 占用高：debug 构建 + 三处可避免的重复计算

**最大的单一因素是构建档位**。实测同一批 29 张真实照片：

| 输入 | debug（`tauri dev`） | release | 加速 |
|---|---|---|---|
| 5000×3333（JPEG 原图路径） | 4900 ms/张 | **183 ms/张** | 27× |
| 1616×1080（RAW 内嵌预览路径） | 976 ms/张 | **34 ms/张** | 29× |
| **29 张合计** | **63.0 s** | **2.17 s** | **29×** |

500 张照片在 debug 下就是约 20 分钟满载 CPU —— 这正是"电脑很卡"的来源。已在 `Cargo.toml` 增加 dev profile 配置（依赖 o3、本项目 o2），日常 dev 体感即可接近 release。

其余三处是**真实的算法性浪费**，与构建档位无关，在 release 下同样存在：

| 问题 | 修复 |
|---|---|
| 曝光检查在**全尺寸**图上另做一次 `to_luma8()`（24MP 原图 = 多遍历 2400 万像素、多分配 24MB） | 新增 `metrics_of()`：归一化**一次**、灰度转换**一次**，清晰度与曝光共用 |
| `find_duplicates` 用裸 `par_iter` **占满全部逻辑核**（本机 16 线程）→ 整机卡顿 | 改为固定线程池，上限 4（`clamp(2, 4)`），留余量给 UI 与系统 |
| `analyze_photos` 的解码/统计循环跑在 **tokio worker** 上，阻塞异步运行时 | 整体移入 `spawn_blocking`；`ABORT` 中止标志仍逐张检查 |

### 8.3 附带发现并修复：损坏的预览缓存（同时导致查看器空白 + 分析静默跳过）

计时基准里那张照片 0.5ms 就返回 `<无法分析>`。查缓存发现 `preview_v3/b19755bbbe7f2164_prev.jpg` 以 **`ff d8 ff 7d`** 开头 —— `0x7d` 不是合法 JPEG 标记。

**根因**：`jpeg_dimensions` 用"扫到 `0xFF` 就当段头"的方式找 SOF，于是在**任意垃圾字节**里都能撞出假 SOF（110KB 里撞上 `ff c0` 的概率并不低），坏数据因此被判定为"合法预览"写进缓存并长期复用。双重影响：查看器显示空白；分析器 `load_analysis_image` 直接返回 None，那张照片被**静默**当作"不模糊"。

**修复**：`jpeg_dimensions` 改为**严格按段长度遍历**（段头必须 `0xFF`、校验段长与越界、正确处理无长度的独立标记、遇非 `0xFF` 段边界即判定损坏）；`preview_v3` → **`preview_v4`** 让已缓存的坏条目自然失效。新增 3 个单测（真编码 JPEG 的正例 + 复现该损坏结构并内含"像 SOF 的字节"的反例 + 非 JPEG/过短输入）。

### 8.4 重复标记太多：换成成熟库 + 位级 Hamming 距离（已实施）

**根因**（不是阈值调小了事，是分数语义错了）：`imgfprint` 的 `score` 里块相似度是**条件均值** —— Hamming 距离 >32/64 的块被**排除、不计入分母**。于是"16 块里 8 块一样、8 块完全不同"的图，块相似度仍是 1.0；大面积平坦区（天空、虚化背景）的块哈希天然雷同，不同照片因此被推高。实测：同场景连拍 0.93–0.96，而**同一文件的不同分辨率版本也只有 0.89–0.96**，加 `perceptual_distance` 兜底**无法分离**（负样本 1–7 与正样本 0–3 重叠）。

**社区调研结论**：换用 **`image_hasher 3.1.1`**（105 万下载、2026-02 仍在更新、作者即 Czkawka 作者、依赖 `image 0.25` 与本项目一致），它给的是**纯 Hamming 距离**（无条件、可解释）。Czkawka 源码里的真阈值表 `SIMILAR_VALUES`（按 hash 尺寸分档，hash 16 行为 `[2, 5, 15, 30, 40, 40]`）与 GUI 说明（16 是默认档、32/64 档"几乎不会有误报"）都指向"用位级距离分档"这条路。

**实施**：`HashAlg::DoubleGradient` + `hash_size(16, 16)` = **256 位**，距离 ≤ `DUPLICATE_MAX_DISTANCE` 才归组；顺带把"一张图解码两次"改成**一次**（同一个 `DynamicImage` 既算哈希也算清晰度/曝光），`imgfprint` 依赖整个移除。

**阈值标定**（应用自身缓存的 28 张真实照片，378 个两两组合）：

| 阈值(位) | 不同源误判 | 同源命中 |
|---|---|---|
| ≤2 | 0/371 | 1/7 |
| **≤5（采用）** | **4/371（1.1%）** | 2/7 |
| ≤10 | 11/371 | 5/7 |
| ≤30 | 15/371 | 7/7 |

关键事实：**同源的 preview/full 对（2/4/6/9/10/14/24）与"同场景连拍"负样本（4–16）完全重叠** —— 那些连拍帧在感知上确实几乎一样；而真正不同的场景**全部 >46 位**（断层明显）。所以取严档 ≤5（约 98% 相似）只归并最接近的一小撮，直接回应"标记太多"。想更宽松（把同场景连拍也归组）把常量调到 8~10 即可 —— 这一行有完整标定注释。

**已知取舍**：仍保留"按文件大小分桶"裁剪比较集（数千张时 O(n²)→近线性），代价是文件大小差异悬殊的同一张图（如重新导出）可能不被比较；连拍场景下文件大小几乎一致，不受影响。

### 8.5 本轮验证证据

```
$ cargo test --lib                      → 22 passed; 0 failed; 2 ignored   (exit 0)
$ cargo check --lib                     → 无警告                          (exit 0)
$ tsc --noEmit                          → exit 0
$ cargo test --release --lib bench…     → 29 张 / 2.17s  / 平均 74.7 ms
$ cargo test --lib bench…（新 profile）  → 29 张 / 5.67s  / 平均 195 ms
$ cargo test --lib bench…（旧 profile）  → 29 张 / 63.0s  / 平均 2173 ms
$ 阈值标定（28 张真实照片 / 378 对）      → 见 §8.4 表格
```

新增测试共 8 个：`sharp_subject_on_smooth_background_is_not_blurry`（**直接锁住用户抱怨的虚化场景**）、`flat_and_gradient_images_are_blurry`、`duplicate_hamming_distance_orders_copies_below_threshold`、`jpeg_dimensions_*` ×3，外加两个默认忽略的标定/计时助手（`bench_analyze_cache`、`calibrate_duplicate_thresholds`，样本取自应用自身缓存，不触碰原始照片库）。

**未在 GUI 实机验证**：以上均为量测与单测；建议重启 `npx tauri dev` 后手测三项：AI 分析的负载是否可接受、虚化背景照片是否还被标模糊、重复徽标是否明显变少。

---

## 附录 A · 事实速查

**缓存目录**：`%LOCALAPPDATA%\image-filter\{thumbnails_v2,preview_v3,full_v3}`（macOS/Linux：`$XDG_CACHE_HOME` 或 `~/.cache/image-filter/...`），文件名 `{hash16}_{maxSize}.jpg`；数据库 `app_data_dir/image-filter.db`。

**SQLite**：`import_history(id, source_path, dest_path, file_hash, file_size, imported_at)`；`import_rules(id, name UNIQUE, folder_template, file_template, is_default, created_at)` + 默认规则 `{date}` / `{original}`。

**关键常量**：全图缓存最小长边 1500；全图输出上限 5000；缩略图批量并发 4；RAW 全解码并发 1；内嵌 JPEG 前瞻 16MB；预览可用下限 600px；直接用内嵌预览的阈值 3000px；重复检测分桶 16KB、相似阈值 0.85；过曝 >250 占比 15%、欠曝 <5 占比 30%；候选内嵌 JPEG 上限 64 个。

**依赖要点**（`src-tauri/Cargo.toml`）：`tauri 2`(protocol-asset)、`rawler 0.7`、`kamadak-exif 0.5`、`image 0.25`、`zune-jpeg 0.4`（dev 下 opt-level=3）、`imgfprint 0.4`、`sqlx 0.8`(runtime-tokio+sqlite)、`tokio 1`(full)、`rayon 1.12`、`memmap2 0.9`、Windows 专属 `inspect_path 0.3` + `windows 0.62`(Shell/Gdi/Com/Storage_FileSystem/Foundation/Imaging/System_IO/Security)；build-deps `cc + tauri-build`（cc 编 `third_party/tinydng/bridge.cpp` → 静态库 `tinydng_bridge`）。
