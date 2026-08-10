# PixelFlow 项目日志

> 更新时间：2026-08-10
> 当前 HEAD：`22fc7d5`

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
