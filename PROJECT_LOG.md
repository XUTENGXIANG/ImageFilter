# PixelFlow 项目日志

> 更新时间：2026-08-11
> 当前 HEAD：`588d8b5`

## 项目概览

PixelFlow 是面向摄影师的 SD 卡照片智能导入工具：插卡 → 秒级预览 RAW → 星级评分/废片筛选 → 一键导入电脑。

- 前端：React 19 + TypeScript + Tailwind CSS v4 + shadcn/ui + IconPark
- 后端：Rust + Tauri 2
- 解码：rawler / tinydng / WIC / Windows Shell 缩略图 / 内嵌 JPEG 提取
- 存储：SQLite

## 当前状态

- 基础 MVP、文件浏览、RAW 预览、评分筛选、AI 分析、导入引擎均可用
- 生产构建 CSP 已修复，安装版图片可正常显示
- 开发版可热重载运行：`npm run dev` + `cargo run`，统一入口 `npx tauri dev`

## 最近开发

### 2026-08-11：修正失焦毛玻璃方案
- 失焦不再直接移除窗口效果导致全透明，改为聚焦使用 Mica、失焦使用传统 SWCA Acrylic
- Acrylic 按深色/浅色主题使用对应 tint，失焦时保留毛玻璃且不会回退成中性灰
- 同时清除了测试过程中误开的 ChatGPT 与 PixelFlow 置顶状态
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过，实机截图确认聚焦/失焦均保留玻璃感

### 2026-08-11：应用失焦时保持背景透明
- Mica 失焦时会回退为中性灰，改为监听窗口 `blur`/`focus` 切换背景效果
- 聚焦时保留 `MicaDark`/`MicaLight`，失焦时移除窗口效果，让透明背景直接透出桌面
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过，实机截图确认失焦不再变灰

### 2026-08-11：新增背景玻璃透明度滑块

- 设置面板新增“背景玻璃透明度”滑块，调节浮窗后面整块背景毛玻璃的透明度
- 背景透明度持久化到 `pixelflow-background-opacity`，默认 0%，且 0% 时根背景直接使用透明
- 标题栏玻璃透明度滑块保持独立，两个滑块在关闭毛玻璃时都会禁用
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过

### 2026-08-11：修正透明度作用范围并恢复浮窗纯白

- 顶部/底部工具条与左右浮窗恢复纯白实体底，不再使用毛玻璃
- 透明度滑块只作用于标题栏玻璃背景，不影响浮窗组件
- 标题栏继续跟随 `--glass-bg`，关闭毛玻璃时滑块禁用
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过

### 2026-08-11：标题栏统一毛玻璃并新增透明度调节

- 标题栏改用 `--glass-bg` 玻璃背景，不再使用独立色值
- 新增“玻璃透明度”设置滑块，实时调节标题栏玻璃背景的透明度，关闭毛玻璃时禁用
- 滑块值持久化到 `pixelflow-glass-opacity`，默认 70%，与原视觉一致
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过

### 2026-08-11：详情面板默认收起并随选中照片自动展开

- 右侧详细信息面板默认收起，不再常驻展开
- 新增 `FloatingPanel` 的 `autoOpenKey` 触发：选中照片后自动展开详情，切换照片时重新展开
- 左侧设备面板保持原有默认展开行为
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过

### 2026-08-11：恢复滚动渐变遮罩

- 恢复 `ScrollFadeZone` 的上下滚动渐变遮罩：滚动时淡入、停止 250ms 后淡出
- 恢复 `--glass-fade` 变量，透明毛玻璃开启时遮罩跟随浅色/深色主题
- 保留根容器透明逻辑，不把整块白色遮罩带回来
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过

### 2026-08-11：移除透明模式整片白色遮罩

- 恢复根容器背景随“透明毛玻璃背景”开关切换：开启时 `bg-transparent` 露出 Mica，关闭时沿用原 `bg-zinc-950`
- 白色底只保留在四个圆角浮窗卡片自身，中央未遮住区域直接显示照片缩略图
- 依据 Tauri 社区结论：WebView2 透明窗口下，根容器不透明白底会盖住 Mica，形成白色/毛玻璃遮罩
- 验证：`npx tsc --noEmit`、`cd src-tauri && cargo check` 通过

### 2026-08-11：移除透明模式主背景与滚动白色渐变

- 毛玻璃开启时主背景改为透明，只保留浮窗卡片本身的玻璃底，不再有整块浅色背景
- 删除 `ScrollFadeZone` 的滚动遮罩和 `--glass-fade`，滑动时不再出现白色渐变

### 2026-08-11：移除浮窗阴影白色块

- 删除 `CollapsibleBar` 与 `FloatingPanel` 的独立阴影层，避免透明毛玻璃下 WebView2 渲染成白色块
- 顶部/底部工具条与左右浮窗统一 `z-40`，查看器保持 `z-50` 最上层

### 2026-08-11：浮窗阴影重构与顶部栏收起按钮集成

- 阴影从卡片剥离为独立阴影层：位于折叠裁剪容器之外，阴影不再被 `overflow-hidden` 裁剪
- 四浮窗阴影层精确贴卡片区域：`CollapsibleBar` 用 `inset-3`，`FloatingPanel` 用 `left-3 right-3 top-3 bottom-3`
- 透明窗口 + Mica 毛玻璃下，阴影层改用条件渲染（无渐变过渡），规避 WebView2 合成白色块问题（待确认）
- `PhotoToolbar` 收起按钮集成到工具栏主体右端（新增 `collapseInside` prop），折叠态保留独立展开按钮
- i18n 补充 `bars.expand` / `bars.collapse` 键（zh/en）
- 提交：`a046bf0`，验证：`tsc --noEmit` 通过

### 2026-08-11：修复顶部栏空状态细线

- 顶部工具栏无照片时手动展开显示占位提示，不再只剩边框细线
- 折叠状态下收起卡片内边距，避免 `grid-template-rows` 折叠后残留细线

### 2026-08-11：修复悬浮栏阴影与右侧面板动画方向

- 右侧信息栏改为贴右边缘展开/收起，避免动画方向反转
- 顶部/底部工具条与左右面板统一使用 `shadow-xl`，卡片外层留出阴影空间，不再被裁剪

### 2026-08-11：优化设备栏/信息栏折叠动画

- `FloatingPanel` 改为内容常驻 + `grid-template-columns` 折叠，展开/收起不再直接卸载内容
- 宽度过渡从 200ms 线性改为 300ms ease-in-out，折叠态按钮保留在窄条内

### 2026-08-11：顶部工具栏改为可折叠圆角浮窗

- 新增 `PhotoToolbar` 组件，全选/取消/排序/星级筛选/缩略图滑块/AI 分析迁入圆角浮窗
- 复用 `CollapsibleBar`，与底部导入栏动画、毛玻璃、自动展开逻辑保持一致

### 2026-08-11：底部导入栏改为可折叠圆角浮窗

- 新增通用 `CollapsibleBar`：圆角卡片、毛玻璃联动、高度过渡动画、独立展开/收起按钮
- 新增 `ImportBar` 组件，底部目标文件夹/导入按钮/高级选项/进度迁移为浮窗卡片
- 默认收起；照片列表或选中数从空变非空时自动展开，清空后自动收起，手动收起不会被同一状态反复拉回
- 回退点：`before-toolbars` tag

### 2026-08-11：Mica 毛玻璃透明背景开关

- `tauri.conf.json` 开启 `transparent`，新增 `set_glass_bg` 后端命令按主题应用 `MicaDark` / `MicaLight`
- 设置面板新增“透明毛玻璃背景”开关，默认开启，持久化到 `pixelflow-glass`，关闭时清除 Mica
- 照片网格、设备浮窗、详细信息浮窗、顶部栏、工具栏、导入栏改为随主题的半透明背景；查看器保持不透明
- 回退点：`before-mica` tag
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口 `BackdropType=2`（Mica 已生效）

### 2026-08-11：查看器切图黑闪根治

- 参考开源社区 PhotoSwipe 相关方案：切图时不再清空旧高清图，新图下载并解码完成前由旧图/缩略图铺底
- 查看器与可见区域预加载统一使用 `img.decode()`，等图片真正解码完成后再替换 `src`，消除 `onload` 后仍出现的空帧
- 按钮与键盘切换共用同一保留旧图逻辑，不再有 `setSrc(null)` 的空屏路径
- 图片加载/解码失败时放弃本次替换，保留旧图或缩略图，避免坏图 `src` 造成黑屏
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口已热重载

### 2026-08-10：查看器切图黑闪修复

- 切换照片时同步预置目标图缓存，避免首帧因 `currentPathRef` 未更新而空屏
- 未命中预览缓存时立即请求缩略图兜底，不再等 120ms debounce 后才出现画面
- 新增本地 `fallbackThumbs`，缩略图到达后无状态闪烁直接铺底
- 键盘/左右按钮统一走 `navigateTo`，保证缓存预置逻辑一致
- 验证：`tsc --noEmit` 通过，开发版窗口正常

### 2026-08-10：代码清理 P4b — 拆分 scanner EXIF/图片模块

- 新增 `scanner/exif.rs`：EXIF 懒加载命令与字段解析
- 新增 `scanner/images.rs`：缓存键、方向校正、预览、缩略图、全图解码、Shell 缩略图
- `mod.rs` 只保留类型与扩展名常量，scanner 单文件拆分完成
- `lib.rs` 命令路径更新为 `scanner::exif::*`、`scanner::images::*`，前端命令名与行为不变
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口正常

### 2026-08-10：代码清理 P4a — 拆分 scanner 设备/浏览模块

- `scanner.rs` 转为 `scanner/mod.rs`
- 新增 `scanner/drives.rs`：`DriveInfo`、设备检测、打开文件夹、IOCTL 弹出
- 新增 `scanner/browse.rs`：目录浏览、文件夹计数、照片扫描
- `lib.rs` 命令路径更新为子模块路径，前端命令名与行为不变
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口正常

### 2026-08-10：代码清理 P3 — 拆分 App.tsx 组件

- 抽出 `TitleBar`、`ExifPanel`、`PhotoCard`、`FolderTreeItem`、`WelcomeGuide`、`ScrollFadeZone`、`ThumbSizeSlider`、`AdvancedOptions`
- 新增 `Step` 与 `formatBytes` 共享模块，App 主文件负责状态与页面组装
- `App.tsx` 从约 1099 行降至约 557 行；JSX、className、i18n key、事件逻辑未改动
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口正常

### 2026-08-10：代码清理 P2 — 消除后端重复代码

- 新增共享 `exif_common::open_exif`，scanner 与 importer 复用同一 EXIF 打开/读取样板
- 新增统一 `cache_hash`，替换预览/全图/缩略图 3 处重复缓存键计算
- 新增统一 `resize_max_edge`，替换 4 处“5000px 缩放”重复逻辑
- 命令签名、缓存目录、DNG 链路、CSP 均未改动
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口正常

### 2026-08-10：代码清理 P1 — 移除未使用组件

- 删除未被 App 引用的 shadcn 侧边栏脚手架：`app-sidebar`、`nav-main`、`nav-projects`、`nav-user`、`team-switcher`
- 删除仅被上述脚手架引用的 UI 组件：`avatar`、`breadcrumb`、`collapsible`、`dropdown-menu`、`sheet`、`sidebar`、`skeleton`、`tooltip`
- 保留已注册但未调用的 Tauri 命令，遵守“不删除命令”约束
- 验证：`tsc --noEmit`、`cargo check` 通过，开发版窗口正常

### 2026-08-10：中英文切换 + 默认浅色主题

- 重建 i18n 体系（`src/i18n/`：zh.ts / en.ts / index.ts，基于 i18next + react-i18next）
- 全部界面文本抽取为 `t()` 调用：标题栏、设置/帮助对话框、设备面板、工具栏、照片角标、导入栏、右键菜单、EXIF 面板、欢迎页、查看器
- 设置面板新增「语言」切换（中文 / EN），持久化于 `pixelflow-lang`
- 默认主题改为浅色（light），首次启动即白色；已设置过主题的用户保持原选择

### 2026-08-10：协作约定 — 所有改动须写入项目日志

- 用户要求：**以后每个改动（新增/修改/修复/重构）都必须同步写入本日志**
- 本约定自 2026-08-10 起生效，Claude / Codex 每次改动后需在本文件"最近开发"顶部追加条目
- 条目格式：日期 + 改动主题 + 具体内容列表

### 2026-08-10：RAW 浏览体验优化

提交：`22fc7d5`

- RAW 全图解码改为 rawler `raw_to_srgb` 完整显影，清晰度对齐 Windows Photos 档位
- DNG 独立链路调整为：rawler 完整显影优先 → tinydng → WIC
- WIC 补上 COM 初始化，解决 RAW 解码静默失败
- 全图/预览/缩略图统一应用 EXIF Orientation，竖图不再横显
- 缓存目录升级：`full_v3`、`preview_v3`、`thumbnails_v2`
- 查看器改为预解码后再替换高清图，消除切图黑闪
- 查看器缓存相邻照片预览，快速切图不再卡顿
- 新增可见区域全图预加载，RAW/JPEG/PNG 一视同仁，开关即时生效
- 缩略图优先使用 Windows Shell `IShellItemImageFactory`，批量生成并发 4

### 2026-08-09：查看器与稳定版

- 查看器支持旋转、缩放、拖拽、星级评分
- IOCTL 安全弹出设备
- 设备面板右键菜单
- 使用说明浮窗
- 回退到 `6ac758e` 稳定版，保留 README 与 CSP 修复

### 2026-08-08：核心功能

- 设备扫描、文件夹浏览、照片网格
- RAW 缩略图/预览/全图渐进加载
- AI 废片分析、重复检测、星级筛选
- 导入引擎、命名规则、MD5 校验

## 关键约定

- **所有改动必须同步写入本日志**（"最近开发"顶部追加条目）
- 图标统一使用 IconPark，不混用 emoji 图标
- 查看器加载状态机不要随意简化，黑闪/卡顿修复依赖这些分支
- 生产 CSP 必须保留 `http://asset.localhost`，否则安装版图片全黑
- DNG 不与其他 RAW 混用，走独立链路
- 无 i18n，界面保持中文

## 验证方式

```bash
npm run build
cd src-tauri && cargo check
npx tauri dev
```

## 待办/注意

- GitHub 远端与本地已分叉，后续 push 需要 `--force` 或重新整理分支
- GitHub token 已 revoke，需要新 token 才能推送
- `src-tauri/Cargo.toml` 有未提交的换行符差异，无内容变化
- `Tencent/` 是输入法日志目录，不属于项目代码，不提交
