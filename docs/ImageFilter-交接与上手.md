# ImageFilter 交接与上手（单一文档）

> **给接手的新会话/协作者**：读完这一份即可开始改代码。深度证据（每条问题的代码行号、标定过程、社区调研来源）在 [ImageFilter-架构与机制走查.md](ImageFilter-架构与机制走查.md)。
>
> 最后更新：本轮会话结束 · HEAD `8ec4eaf` · 版本 `1.0.1` · 工作区干净，**已推送到 `origin/master`**（与远端同步）

---

## 0. 30 秒速览

| 维度 | 事实 |
|---|---|
| 是什么 | SD 卡/相机照片的**初筛导入工具**：插卡 → 秒级预览 → 星级/AI 废片筛选 → 按模板归档。全程本地处理 |
| 形态 | Tauri 2 桌面应用，单窗口 1200×800、**无边框 + transparent**，标题栏 React 自绘 |
| 技术栈 | 前端 React 19 + TS 5.7 + Tailwind 4 + `@base-ui/react` + IconPark + i18next（无状态库/路由/动画库）；后端 Rust + rawler/WIC/tinydng(DNG) + zune-jpeg + **image_hasher** + kamadak-exif + tokio + sqlx(SQLite) + rayon |
| 规模 | 后端约 2.5k 行 Rust（含测试）+ 前端约 2.9k 行 TS/TSX + 一个 C++ DNG 桥接 |
| 测试 | **26 个活跃单测**（analyzer / importer / scanner），另有 3 个 `#[ignore]` 标定助手。**前端零测试** |
| 已知缺口 | macOS 整条链未实机验证；D6/D7/D9–D11/D13 未修（见 §6） |

**一句话架构**：单页三栏（左设备树 / 中网格 / 右 EXIF）+ 全屏查看器；业务状态全部收敛在 `useScanner()`，经 21 个 Tauri command 命令式取数，长任务用 `Channel` 流式回推进度。

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

## 1. 跑起来（含所有已知坑）

```bash
cd A:\tenent
npx tauri dev          # Vite(1420) + Rust 增量编译 + 自动弹窗
npx tauri build        # 产物在 src-tauri/target/release/bundle/
cargo test --lib       # 在 src-tauri/ 下执行；26 活跃 + 3 忽略
```

| 坑 | 现象 / 处理 |
|---|---|
| **端口 1420 被占** | 旧 vite 进程不会随窗口关闭退出 → `beforeDevCommand terminated with non-zero status`。`netstat -ano \| findstr :1420` 找 PID，`taskkill /PID <PID> /F` 后重启 |
| **dev 被临时文件搞崩** | 同一个报错还有另一个来源：某些工具/编辑器**原子保存**（先写 `.xxx.<pid>.<uuid>.tmpdir/xxx.tmp` 再改名）时，Vite watcher 去 watch 正被占用的临时文件会抛**未捕获的 EBUSY** 并直接终止 dev。已在 `vite.config.ts` 的 `watch.ignored` 里加 `**/.*.tmpdir/**` 与 `**/*.tmp` 兜住；若仍遇到，重跑 `npx tauri dev` 即可（与代码无关） |
| **改 profile 后的链接失败** | 出现 `LNK2019: 无法解析的外部符号 anon.*.llvm.*`（cdylib 链接失败，而 rlib 测试能过）→ 是残留的过期增量产物，`cargo clean -p image-filter` 后重建即恢复（实测清掉 11 GiB） |
| **dev 构建速度** | `Cargo.toml` 已加 `[profile.dev] opt-level = 2` + `[profile.dev.package."*"] opt-level = 3`：dev 下 AI 分析 **63.0s → 5.67s**（29 张）。首次或改 profile 后需重建全部依赖约 2–3 分钟，之后增量约 20s |
| **热重载** | 前端走 Vite HMR；**Rust 改动自动重编译并重启窗口**（编辑中途的编译错误会在日志里出现，属正常） |
| **版本号** | 发布需同步 **4 处**：`package.json`、`src-tauri/tauri.conf.json`、`src-tauri/Cargo.toml`、`src/components/settings-dialog.tsx`（旧文档只提了前三处） |
| **发布流程** | 推代码 → `git tag vX.Y.Z` + `git push origin vX.Y.Z` → GitHub Actions 构建安装包 → **人工建 Release** 才算发布 |
| **运行期数据** | 缓存 `%LOCALAPPDATA%\image-filter\{thumbnails_v2, preview_v4, full_v3}`；数据库 `%APPDATA%\com.imagefilter.app\image-filter.db` |
| **本地文档** | `HANDOFF.md` / `PROJECT_LOG.md` 在 `.gitignore` 里（不入库）。本文件在 `docs/` 下，**已入库**，是唯一权威交接文档 |

---

## 2. 代码地图

### 后端（`src-tauri/`）

| 文件 | 行数 | 职责 |
|---|---|---|
| `src/lib.rs` | 114 | 命令注册（21 个）、asset scope 启动放行缓存目录、Mica 玻璃、SQLite 初始化 |
| `src/analyzer.rs` | 726 | **清晰度/曝光/重复**三大指标 + 标定助手测试；`metrics_of` 单次归一化+luma |
| `src/scanner/images.rs` | 615 | 三级解码（缩略图/预览/全图）+ 磁盘缓存 + JPEG 严格段解析 |
| `src/importer.rs` | 429 | 导入引擎：模板、MD5 双向校验、绝不覆盖、写历史 |
| `src/scanner/browse.rs` | 270 | 浏览/扫描/计数（含环检测、**计数代次取消**、固定盘跳过） |
| `src/scanner/drives.rs` | 200 | 盘符枚举、卷标、IOCTL 弹出 |
| `src/win_wic.rs` | 141 | Windows WIC RAW 解码 + Shell 缩略图（1GB 尺寸上限） |
| `src/db.rs` | 120 | SQLite：`import_history` / `import_rules` |
| `src/tinydng.rs` + `third_party/tinydng/bridge.cpp` | 58 + C++ | DNG 解码 FFI（**双侧**尺寸/位深/长度校验） |
| `src/scanner/exif.rs` `exif_common.rs` | 85 | EXIF 字段读取与 Orientation 收敛 |

**命令面**（`lib.rs`）：`allow_asset_dir` `set_glass_bg` `detect_drives` `open_folder` `eject_drive` `browse_directory` `count_folders` `scan_directory` `get_exif` `get_thumbnail_path` `get_preview_image` `get_full_image` `batch_thumbnails` `import_photos` `analyze_photos` `find_duplicates` `stop_analysis` `get_import_history` `get_rules` `save_rule`

### 前端（`src/`）

| 文件 | 行数 | 职责 |
|---|---|---|
| `App.tsx` | 589 | 三栏布局、右键菜单接线、快捷键、可见区预加载、Toast |
| `useScanner.ts` | 339 | **唯一业务状态源**：设备/树/照片/勾选/星级/导入/分析/预加载开关 |
| `viewer.tsx` | 399 | 查看器：渐进加载状态机、clip-path 动画、缩放旋转拖拽、评分 |
| `components/` | ~700 | title-bar / photo-toolbar / import-bar / photo-card / exif-panel / folder-tree-item / settings-dialog / help-* / collapsible-bar / scroll-fade-zone / tip / ui/* |
| `contextmenu.tsx` `panel.tsx` | 121 | 自绘右键菜单（**全项目唯一用 Radix 处**）、左右浮窗 |
| `i18n/` | 390 | zh/en **各 171 个 key，完全对齐**；插值改为单括号 `{n}` |
| `index.css` | 237 | Tailwind 4 + OKLCh token；浅色主题靠**反转 `--color-zinc-*` 变量**实现 |

---

## 3. 数据流主线

```
插卡 → detect_drives(前端 5s 轮询) → 左栏设备列表（可移动优先）
点设备 → browse_directory(单层) → 建根 + 子文件夹；allow_asset_dir 放行
         └─ count_folders(子目录) 后台计数 → applyCounts()   ← 固定/网络盘跳过
点子文件夹 → loadFolder → scan_directory(非递归、不读 EXIF) → 照片网格
                        └─ batch_thumbnails(Channel 逐张回推) → thumbnails[源路径]
单击照片 → selectedPhoto + get_exif（按需富化该条）
双击照片 → PhotoViewer：缩略图铺底 → 内嵌JPEG → 全解码（三级替换）
勾选/Shift 范围选 → selectedPaths
导入 → import_photos(模板, Channel) → 检查中→复制中→完成/跳过/错误
AI 分析 → analyze_photos(串行+可中止) → find_duplicates(4 线程哈希)
```

---

## 4. 关键机制（改之前必须理解）

### 4.1 三级解码与缓存
| 级别 | 命令 | 实现 | 缓存目录 |
|---|---|---|---|
| 缩略图 | `get_thumbnail_path` / `batch_thumbnails` | **Windows Shell `IShellItemImageFactory`**（与资源管理器同源），失败才回落解码；JPEG 用 zune-jpeg | `thumbnails_v2`（键含 maxSize） |
| 预览 | `get_preview_image` | RAW：mmap 扫描 SOI/EOI 取**最大内嵌 JPEG**（16MB 前瞻）；非 RAW 直接返回原路径 | `preview_v4` |
| 全图 | `get_full_image` | ①内嵌预览 ≥3000px 直接用 ②WIC ③rawler 全解码（`catch_unwind`）④位深自适应兜底 | `full_v3`（**只认长边 ≥1500px**，否则删除重建） |

缓存键 = `hash(路径 + mtime秒)`。目录名里的 `v2/v3/v4` 就是缓存版本，**改缓存格式/校验逻辑时必须升版本**，否则坏条目会一直被复用。

### 4.2 并发与线程模型（CPU 相关，改动前先读）
- 阻塞 I/O 一律走 `spawn_blocking`（导入、解码、分析、计数），不占 tokio worker。
- 批量缩略图：`JoinSet` + `Semaphore(4)`。
- RAW 全解码：全局 `Semaphore(1)` + `AtomicU64` 任务号（新请求让旧请求放弃）。
- `analyze_photos`：**串行** + `AtomicBool` 中止标志（每张检查一次）。
- `find_duplicates`：固定线程池**上限 4 线程**（此前裸 `par_iter` 占满 16 核，笔记本直接卡死）。
- `count_folders`：全局**代次** `COUNT_GENERATION`，新请求让旧遍历在**逐目录**检查中立即收手 → 同一时刻最多一个遍历在跑。
- 这套「原子代次/标志 + 逐项检查」是本项目处理"用户连续操作要取消旧任务"的统一模式，新增长任务请沿用。

### 4.3 查看器状态机（`viewer.tsx`，**别顺手简化**）
四条加载分支，每条都是修视觉 bug 换来的：进入/退出 clip-path 动画（30ms / 250ms）；首次加载（缩略图→预览→**600ms debounce** 全解码，切换时**故意不清空 `src`** 避免黑帧）；快速连切（`<500ms` 判 rapid，预览 **120ms debounce**）；邻居预取（±1 张，250ms debounce，命中 `loadedSrcRef` 即秒显）。
另有「列表收缩重锚」：按 `anchorPathRef` 记住正在看的照片，被星级筛选移出列表时**关闭查看器**而不是跳成另一张。

### 4.4 导入契约（数据安全，不许绕过）
目标已存在 → 双端 MD5：相同则 `skipped`（不计数），不同则生成 `_1/_2/...` 唯一名；复制后**再验一次** MD5；`is_safe_relative` 要求路径全为 `Normal` 组件（防逃逸）；成功后写 `import_history`。**绝不静默覆盖。**

### 4.5 两个分析指标（阈值有标定依据，改前先看注释）
- **清晰度**：归一化到 1024 长边 → 8×8 分块 → 取**最大块**拉普拉斯方差，`< BLUR_THRESHOLD = 75` 判模糊。理由：全图方差会被虚化背景拉低（大光圈人像误报）。标定：28 张真实照片最小 122.4 / σ=6 软化最大 57.6（分离度 2.12×）→ 误报 0/28。
- **重复**：`image_hasher` 的 `DoubleGradient` + 16×16 = **256 位**，位级 Hamming 距离 ≤ `DUPLICATE_MAX_DISTANCE = 5`（约 98% 相似）才归组。标定：不同源误判 4/371（1.1%），真正不同的场景全 >46 位。想更宽松（把同场景连拍也归组）调到 8~10。
- 曝光：`>250` 占比 >15% 过曝、`<5` 占比 >30% 欠曝。
- 连拍组内"最佳" = `(曝光是否正常, 清晰度)` 字典序最大。

### 4.6 安全模型
asset 协议 scope **收紧为空**（`tauri.conf.json`），启动只放行缓存目录，用户浏览到的路径由前端调 `allow_asset_dir` **按需放行**；CSP 必须含 `img-src ... asset: http://asset.localhost`；capabilities 无 shell 权限；FFI 边界（tinydng Rust+C++ 双侧、WIC 1GB）不可删。

### 4.7 前端持久化 key
`imagefilter-theme`（深/浅，index.html 有防闪脚本）、`-lang`、`-glass`、`-glass-opacity`、`-background-opacity`、`-preload-full`、`-ratings`（星级，JSON）、`-cols`（网格列数）。

---

## 5. 必守的坑（不要"顺手简化"）

1. **viewer 的加载状态机四条分支**——删任何一条都会回归黑屏/卡顿。
2. **`full_v3` 只接受长边 ≥1500px**；缓存键含 mtime；**改格式必须升缓存目录版本**。
3. **RAW 解码顺序**：内嵌 JPEG → WIC → rawler；DNG 走独立路径（rawler `raw_to_srgb` → tinydng → WIC）并有 `Semaphore(1)` + 任务号防堆积。
4. **tinydng FFI 双侧校验**（Rust 与 C++ 各自 20000 维 / 512MB / spp≤8 / 数据长度）；`CreateFileW` 弹出序列需要 `Win32_Security` feature。
5. **asset scope 不要改回 `["**"]`**；CSP 不要开 `dangerousDisableAssetCspModification`。
6. **tooltip 用 `.tooltip-wrap`**（`index.css` 的 `@layer components`，为覆盖 Tailwind `absolute` 而存在）。
7. **`App.tsx` 快捷键 effect 的依赖必须含 `viewerIndex`，且 `viewerIndex` 的 `useState` 必须声明在该 effect 之前**——依赖数组在渲染时急切求值，声明在后会 TDZ 崩溃（tsc 会报 TS2448）。
8. **`jpeg_dimensions` 必须严格按段长度遍历**：宽松的"扫到 0xFF 就当段头"会在垃圾字节里撞出假 SOF，把坏数据当合法预览缓存（查看器空白 + 分析器静默跳过）。
9. **固定/网络盘不做递归计数**（`is_bulk_volume`）——否则点一下设备就是整盘遍历（实测 C:\Users 单次 18.5s）。注意 `inspect_path` 底层 `GetDriveTypeW` **只认卷根**，必须先 `ancestors()` 归到卷根。
10. **视频双击不打开查看器**；EXIF 方向统一在 Rust 侧物理校正，非 RAW 另加 CSS `image-orientation: from-image`。
11. **缩略图优先走 Shell**，失败才回落到解码；批量并发 4。
12. **导入绝不静默覆盖**（见 §4.4）。
13. **主题 key 统一 `imagefilter-theme`**，默认深色。
14. **可见区预加载**用 `IntersectionObserver` + 版本号取消，设置项即时生效无需重启。

---

## 6. 已知问题与优化建议（按优先级，均未修）

### P1 — 影响体验或有数据风险
| # | 问题 | 证据 / 位置 | 建议 |
|---|---|---|---|
| 1 | **EXIF 读取对大文件是"全量进内存"** | `exif_common::open_exif` 走 kamadak-exif，TIFF 分支整文件读入；每选中一张就调一次，导入时模板变量再读两次 | 只读文件头；或按路径缓存一次结果、导入时复用 |
| 2 | **前端零测试** | viewer 四条加载分支、重锚逻辑、`useScanner` 的树合并纯函数都只有类型检查 | 先测纯函数（`mergeChildren/applyCounts/updateHasSubdirs`），再考虑给 viewer 加 vitest |
| 3 | **`IOCTL_STORAGE_MEDIA_REMOVAL` 未真正生效** | `drives.rs` 该 IOCTL 传 `None/0` 且丢弃返回值；语义上需要 `PREVENT_MEDIA_REMOVAL{false}` 结构体入参 | 按 MSDN 传结构体；或把注释改成"尽力而为的弹出序列" |
| 4 | **macOS 整条链未验证** | `/Volumes` 枚举、`diskutil eject`、无 Mica 降级、`~/.cache` 路径都只有代码 | 有 Mac 环境时实测；README 已声明"缺乏构建环境" |

### P2 — 质量与一致性
| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 5 | 跳过被计入失败数、`{seq}` 跳号 | `useScanner.startImport` 用 `paths.length - count`；`importer` 跳过时不递增序号 | 命令改为返回 `{imported, skipped, failed}`；`{seq}` 用独立计数器 |
| 6 | `detect_drives` 每 5s 轮询 + `handlePhotoClick` 依赖 `[photos,lastClicked]` 使双层 memo 在点击时失效 | `App.tsx` / `useScanner.ts` | 比对后再 `setDrives`；`lastClicked` 改 `useRef` |
| 7 | `App.tsx` 预加载里有一处死取消逻辑（`setTimeout` 回调内 return 清理函数，返回值被丢弃） | `App.tsx` | 删掉以免误导，真正生效的是版本号比较 |
| 8 | 无缩略图的 RAW 在 EXIF 面板显示空白 | `App.tsx` 直接 `convertFileSrc(原文件)` | 回落 `get_preview_image` |
| 9 | 计数对**可移动卡**仍是 O(所有目录)，且每目录一次 `canonicalize`（Windows 上不便宜） | `browse.rs::count_photos_recursive_inner` | 环检测只在 `is_symlink()` 时做 `canonicalize`（保留 depth 上限兜底） |
| 10 | 一致性清理 | `ui/badge` `ui/input` `ui/separator` **零引用**；`components.json` 仍写 `lucide` 与未用的 `@react-bits`（与"一律 IconPark"约定冲突）；版本号硬编码 4 处；`photo-card.tsx` 内联重复了 `types.ts` 的 `AnalysisResult`；`index.html` 固定 `lang="zh-CN"`；`App.tsx` 的 `replace(/\\[^\\]+$/,"")` 只适用 Windows | 逐项清理；README 里"打开所在位置"在 macOS 下会取不到目录 |
| 11 | CSP 未显式声明 `object-src` / `base-uri` / `form-action` | `tauri.conf.json` | 显式补上更稳妥（Tauri 默认注入会补部分） |
| 12 | 某些读卡器会把 SD 卡报成 `fixed` → 该卡失去文件夹计数 | `is_bulk_volume` 只排除 `fixed`/`remote` | 若实际遇到，改成"体积/深度预算"而非按卷类型排除 |

### 未验证的假设（别当真）
- CR3(ISOBMFF) / RAF 能否取到 EXIF —— 代码路径存在，**无真实样本验证**。
- 模糊阈值 75 与重复阈值 5 都是在 **28 张真实照片**上标定的，样本偏小；真实 RAW 大批量使用时建议复核（改常量即可，注释里写了标定方法）。

---

## 7. 本轮会话已修复（4 个提交，已推送）

| 提交 | 内容 |
|---|---|
| `b47b055` | 星级双写、查看器崩溃/跳图、导入模板层级被压平、tinydng 泄漏、浅色主题文字色；同时补上 Rust 单测基建 |
| `ae0a453` | 模糊指标重做（分块最大 + 真实照片重标定）、重复检测换 `image_hasher`（256bit Hamming）、AI 分析 CPU 大降、`jpeg_dimensions` 严格化 + 预览缓存升 v4 |
| `a4f6991` | 连续切换设备导致整盘遍历 / CPU 打满：计数代次取消、固定盘跳过、前端代次守卫 |
| `8ec4eaf` | 新增本交接文档（并把过期的 `HANDOFF.md` / `PROJECT_LOG.md` 改为指针） |

单测最终分布（**26 活跃 + 3 忽略**）：`analyzer` 10 / `importer` 9 / `scanner::images` 3 / `scanner::browse` 4；忽略的 3 个是 §8 的标定助手。

关键数字（都可复现，方法见 §8）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| AI 分析 29 张（dev） | 63.0 s | **5.67 s** |
| AI 分析 29 张（release） | 2.17 s | 2.17 s（基准） |
| 真实照片被误判模糊 | **25/28** | **0/28** |
| 不同照片被误判重复 | 15/371 对 ≥0.85 | 4/371 对（1.1%） |
| 切换设备峰值 CPU | **506% 单核（≈5 核）** | 固定盘不再触发遍历；同一时刻最多一个遍历 |

**修复过程中被新测试抓出的真 bug**（说明测试的价值）：`is_bulk_volume` 第一版把子目录路径直接喂给 `inspect_path`，而 `GetDriveTypeW` 只认卷根 → 守卫**永远不生效**；`build_dest_path` 首版把文件夹模板的 `/` 也清洗成 `_` → 多级目录被压平。

---

## 8. 验证与工具

```bash
cd src-tauri
cargo test --lib          # 26 活跃 + 3 忽略
cargo check --lib         # 应无警告
cargo test --release --lib bench_analyze_cache -- --ignored --nocapture   # 生产构建下的分析耗时
```

**3 个 `#[ignore]` 标定助手**（样本取自应用自身缓存，不碰原始照片库；新会话可直接复用）：

| 助手 | 用途 | 运行方式 |
|---|---|---|
| `bench_analyze_cache` | 真实样张在新指标下的分布 + 单张分析耗时 | `cargo test --lib bench_analyze_cache -- --ignored --nocapture` |
| `calibrate_duplicate_thresholds` | 感知哈希 Hamming 距离分布 + 阈值扫描（正/负样本） | `cargo test --lib calibrate_duplicate_thresholds -- --ignored --nocapture` |
| `bench_count_folder` | 目录递归计数耗时（评估"点设备=整盘遍历"的成本） | `$env:IMAGEFILTER_COUNT_PATHS="C:\Users;C:\Program Files"; cargo test --lib bench_count_folder -- --ignored --nocapture` |

**实测数据（供后续调参对照）**
- 分析耗时：debug（旧 profile）平均 2173 ms/张 → dev 新 profile 195 ms/张 → release 74.7 ms/张。
- 目录递归计数：`C:\Windows\System32` 0.46s / `C:\Program Files` 3.12s / `C:\Users` 18.47s。
- 清晰度分布（28 张真实照片）：最小 125.9 / p10 156.7 / 中位 411.2 / 最大 2015.9；σ=6 软化后 ≤57.6。
- 重复距离（256bit DoubleGradient）：同源 preview/full 2/4/6/9/10/14/24；不同源最小 4/4/5/5/7/8/9…，**真正不同的场景全部 >46**。

---

## 9. 发版计划（当前：v1.1.0）

> **本节已随 Phase 1–7 更新**。原计划里的 `v1.0.2`（那批模糊/重复/CPU 修复）与后续的 Phase 1–6（自动前进、撤销、1:1、颜色标签与三维筛选、XMP 边车、导入历史/命名方案/导入统计）、Phase 7（Lightroom 衔接）**合并为一次 `v1.1.0`** 发布。
> 版本号已改为 `1.1.0`（4 处），见 [ImageFilter-Phase7-Lightroom衔接.md](ImageFilter-Phase7-Lightroom衔接.md) 与 [ImageFilter-功能实施方案.md](ImageFilter-功能实施方案.md)。

### 9.1 发布前检查
- [x] `cargo test --lib`（66 活跃 + 3 忽略）与 `npx tsc --noEmit` / `npx vite build` 全绿；i18n zh/en 各 273 叶子 key 对齐
- [x] Phase 7 的完整链路已由使用者在真机验证（导入 → 打开 LrC 导入页 → 页面只含本批）
- [ ] 在真实 SD 卡上跑一遍核心路径：浏览 → 缩略图 → 查看器（含**连续快速切换**）→ 星级筛选 → AI 分析 → 导入（校验归档层级与"跳过/改名"行为）
- [ ] 复核两个阈值（见 §6 "未验证的假设"）：`BLUR_THRESHOLD = 75`、`DUPLICATE_MAX_DISTANCE = 5` 在更大样本上是否仍合适
- [ ] macOS 全链仍未实机验证 —— README 里保留"缺乏构建环境"的提示，别在 Release notes 里声称已支持
- **Phase 7 只在 Windows 生效**：`lightroom.rs` 的所有 Windows 专属代码都在 `cfg(target_os = "windows")` 内，非 Windows 平台 `probe_lightroom` 直接返回"未找到"，功能入口自动隐藏。macOS 上"用 `open -a` 打开 LrC"未实现、也未验证。

### 9.2 改版本号（**必须 4 处**，只改一处会导致界面/安装包/包管理器版本不一致）

| 文件 | 字段 |
|---|---|
| `package.json` | `version` |
| `src-tauri/tauri.conf.json` | `version` |
| `src-tauri/Cargo.toml` | `[package] version`（`Cargo.lock` 随构建自动更新） |
| `src/components/settings-dialog.tsx` | 关于对话框里**硬编码**的版本字符串 |

### 9.3 打 tag 并推送

```bash
git tag v1.1.0
git push origin master
git push origin v1.1.0
```

### 9.4 产物与正式发布
- 安装包由 `.github/workflows/build.yml` 在 tag 推送后构建：**Windows（NSIS `setup.exe` + MSI）与 macOS（universal）两平台并行**
- 该工作流用 `tauri-action` **自动创建 draft release 并上传安装包**（`releaseDraft: true`）
- **仍需人工到 GitHub 把那份 draft 点成 Publish** 才算正式发布 —— 只推 tag 只会得到一份草稿
- Release notes 建议说明：`preview_v3 → v4` 的缓存升级会在**首次浏览 RAW 时重建预览缓存**，属预期行为

### 9.5 本次发布应包含的用户可见变化

**v1.0.1 → 现在（原 v1.0.2 那批修复）：**
- 大光圈虚化背景的照片**不再被误标"模糊"**
- 连拍"最佳"改为兼顾曝光（不再选过曝/欠曝的那张）
- 重复标记显著收敛（只归"几乎同一张"）
- AI 分析 CPU 占用大幅下降（dev 下 29 张 **63s → 5.7s**）
- 连续切换设备不再触发整盘遍历 / CPU 打满
- 修复：查看器内评分会误改另一张照片、星级筛选下查看器崩溃或跳图、导入的文件夹层级被压平、浅色主题基础文字色、某类 RAW 预览空白且分析被静默跳过

**v1.1.0 新增（Phase 1–6）：**
- 评分后**自动前进**（打完分不用再按方向键）
- `Ctrl+Z` / `Ctrl+Shift+Z` **撤销/重做**（评分与勾选）
- 查看器 **`Z` 切 1:1 实际像素**（以鼠标位置为锚点）
- **颜色标签**（`Ctrl+1`–`Ctrl+5`，修饰键可换 Ctrl/Alt）+ **星级/标签/分析三维叠加筛选**
- **评分与色标写入 `.xmp` 边车**（三档开关，缺省"询问"），导入时连同边车一起复制
- **导入历史**界面、**命名方案预设**、导入结果区分"成功/改名/跳过/失败"、`{seq}` 按输入顺序编号

**v1.1.0 新增（Phase 7 · 仅 Windows）：**
- 导入栏「**导入到 LrC**」：先把选中的照片导入目标文件夹，再打开 Lightroom 的导入页面，**页面上只有这一批**
  （目标文件夹非空时自动建 `ImageFilter_YYYYMMDD_HHmm` 子文件夹，避免 LrC 列出整个目录）
- 自动定位 Lightroom 安装位置（`.lrcat` 文件关联优先 —— 装在非标准目录时这是唯一能命中的方式）
- Lightroom 已在运行时**不静默失败**：弹框让你选"关掉后重试"或"强制关闭并继续"（Adobe 会忽略已运行实例收到的路径参数）

---

## 10. 文档索引

| 文档 | 用途 |
|---|---|
| **本文件** | 唯一权威交接文档：现状、机制、必守坑位、未修问题与优化建议、验证工具、**发版计划** |
| [ImageFilter-架构与机制走查.md](ImageFilter-架构与机制走查.md) | 深度证据：D1–D14 逐条（含代码行号、复现数据）、§8 三轮修复的根因与社区调研来源 |
| [ImageFilter-功能实施方案.md](ImageFilter-功能实施方案.md) | Phase 1–6 的施工图 + 各会话决策日志（含**被否决方案**）+ 手测清单 |
| [ImageFilter-Phase7-Lightroom衔接.md](ImageFilter-Phase7-Lightroom衔接.md) | Phase 7 与 Lightroom Classic 衔接：实机探路结论、决策日志、手测清单、已知限制 |
| [README.md](../README.md) / [README.en.md](../README.en.md) | 对外介绍、安装与快捷键 |
| `PROJECT_LOG.md`、`HANDOFF.md`（本地，未入库） | **已过期**，保留仅作历史；以本文件为准 |
| `docs/superpowers/` 下的 plans 与 specs | 落地页网站（另一个仓库）的计划与设计规格 |
