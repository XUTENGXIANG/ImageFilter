# ImageFilter 产品落地页设计文档

> 日期：2026-08-11
> 状态：已获用户确认（2026-08-11）
> 目标仓库：独立 GitHub 仓库 `ImageFilter-Website`，部署 Vercel

## 1. 背景与目标

ImageFilter 是面向摄影师的 SD 卡照片智能导入工具（Tauri 2 + React 19，v1.0.0 已发布，支持 Windows/macOS）。现有宣传渠道只有 GitHub README，需要一个产品落地页来介绍产品并引导下载。

**目标**：一页式产品官网，参考 [ui.aceternity.com](https://ui.aceternity.com) 首页的视觉语言（深色 + 光晕 + 居中模拟应用窗口），核心亮点是一个**可交互的迷你程序演示**——网页里直接运行 ImageFilter 真实 UI 的缩小版。

## 2. 需求决策（已与用户确认）

| 项 | 决定 |
|----|------|
| 定位 | 产品落地页（单页锚点导航） |
| 风格参考 | Aceternity UI 首页：深色近黑背景、radial 光晕、网格底纹、spotlight hover |
| 迷你演示 | **完整复用软件真实 UI**（不是手写 mockup），mock 掉 Tauri API 数据层 |
| 演示交互 | 设备点选、照片网格勾选、查看器（缩放/旋转/星级）、筛选排序、深浅主题、中英切换、导入流程动画（不真实落盘） |
| 语言 | 中英双语切换（复用软件 i18next 翻译文件） |
| 素材 | 现有 assets/screenshot.png + src-tauri/icons/128x128.png；demo 照片用 CSS 渐变占位图（data URL，离线可用） |
| 技术栈 | Vite + React + Tailwind CSS 4 + motion（与软件一致） |
| 部署 | 独立 GitHub 仓库 → Vercel（root directory = 仓库根） |
| 不模拟 | 真实文件系统访问、Mica 毛玻璃、窗口最小化/最大化/关闭 |

## 3. 架构

```
ImageFilter-Website (新仓库)
├── index.html
├── vite.config.ts      ← alias: @tauri-apps/api、@tauri-apps/plugin-* → mock/
├── package.json
└── src/
    ├── main.tsx
    ├── index.css       ← 从主仓库复制（Tailwind 4 + 软件全局样式）
    ├── landing/        ← 落地页（Aceternity 风格）
    │   ├── LandingPage.tsx    # 单页结构：Nav/Hero/Features/Workflow/Download/Footer
    │   ├── Nav.tsx            # 顶栏：Logo + 锚点导航 + 语言切换
    │   ├── Hero.tsx           # 大标题 + 双下载按钮 + 迷你演示窗口
    │   ├── Features.tsx       # 6 张玻璃拟态特性卡片
    │   ├── Workflow.tsx       # 三步流程（插卡识别→预览筛选→一键导入）
    │   ├── Download.tsx       # v1.0.0 版本 + 3 安装包卡片 + GitHub Releases 链接
    │   ├── Footer.tsx
    │   └── DemoWindow.tsx     # 玻璃窗口容器，内嵌 <ImageFilterDemo/>
    ├── demo/            ← 从主仓库复制的软件 UI（组件一行不改）
    │   ├── ImageFilterDemo.tsx  # 入口：渲染 App 主界面（裁剪标题栏窗口控制）
    │   ├── App.tsx            # 复制（保留全部业务交互）
    │   ├── useScanner.ts      # 复制
    │   ├── viewer.tsx         # 复制
    │   ├── components/        # 复制
    │   ├── i18n/              # 复制（zh/en 翻译文件）
    │   ├── lib/               # 复制（utils 等）
    │   └── mock/              ← 新增 mock 层
    │       ├── tauri-mock.ts      # Vite alias 目标：invoke/listen/dialog 等全部 mock
    │       ├── fake-data.ts       # 假设备/假照片列表/假 EXIF 生成器
    │       └── placeholder.ts     # 渐变占位图 data URL 生成器
    └── assets/           ← screenshot.png、128x128.png 复制
```

## 4. 迷你演示（核心）

### 4.1 mock 层设计

软件 UI 通过 `@tauri-apps/api`（invoke）与 Rust 后端通信。网站中此模块不存在，用 **Vite alias** 替换：

```ts
// vite.config.ts
resolve: {
  alias: {
    "@tauri-apps/api": path.resolve(__dirname, "src/demo/mock/tauri-mock.ts"),
  },
}
```

mock 模块导出与软件代码相同的 API 形状：
- `invoke(cmd, args)`：按命令名分发到假数据——
  - `detect_drives` → 3 个设备（CANON_DC E:\ 可移动 / 本地磁盘 / 照片文件夹）
  - `scan_directory` / `batch_thumbnails` → ~20 张假照片（渐变占位 data URL），模拟分批延迟
  - `get_exif` → 假 EXIF（相机/光圈/快门/ISO/焦距）
  - `get_full_image` / `get_preview_image` → 返回占位大图
  - `import_photos` → 延迟 1.5s 后返回成功（导入流程动画）
  - `set_glass_bg`、`open_folder`、`eject_drive` → 空实现/固定返回
- `listen` / `emit` → 简单事件总线
- `plugin-dialog` 等其它 `@tauri-apps/plugin-*` → 按需 mock

**约束：demo 内所有组件代码零修改**，只靠 mock 层注入假数据。

### 4.2 假照片生成

- 每张"照片" = 不同色调的 CSS 渐变（data URL SVG：渐变 + 居中相机图标/光圈符号 + 轻微噪点纹理），尺寸 256px 缩略图 / 1600px 全图
- 文件名/拍摄时间/EXIF 由生成器按顺序伪随机产出，保证排序/筛选功能可演示

### 4.3 与落地页的衔接

- `DemoWindow.tsx`：圆角大窗口（max-w ~1100px），毛玻璃边框 + 顶部装饰标题栏（红黄绿圆点 + "ImageFilter" 字样 + 语言提示），内嵌 demo
- 软件自身标题栏（窗口控制按钮）在 demo 内隐藏/装饰化——不 mock 的项
- 软件默认浅色主题保留（深色网站中形成展示对比），可点击主题切换

## 5. 落地页区块内容

| 区块 | 内容 |
|------|------|
| Nav | Logo + 产品名 · 锚点（特性/工作流/下载）· 中/EN 切换 |
| Hero | 大标题「ImageFilter」+ 一句话定位（SD 卡照片智能导入工具）+ Windows/macOS 双下载按钮 + 迷你演示窗口 |
| Features | 6 卡片：RAW 原生解码 · LrC 同款星级筛选 · AI 废片检测 · 命名规则自动归档 · MD5 数据校验 · 全部本地处理 |
| Workflow | 三步：插卡自动识别 → 秒级预览 + 星级筛选 → 一键导入归档 |
| Download | v1.0.0 + 安装包卡片（setup.exe 推荐 / zh-CN.msi / macOS universal.dmg）+ GitHub Releases 链接 |
| Footer | GitHub 仓库 · MIT · 版权 |

文案复用 README 中文 + 新译英文。

## 6. 视觉规范

- 背景：近黑 `#050505`；装饰 = 紫/蓝/青 radial 光晕（绝对定位模糊层）+ 1px 网格线底纹 + 鼠标 spotlight
- 玻璃卡片：`bg-white/5` + `backdrop-blur` + `border-white/10`，hover 光晕
- 字体：Inter（system-ui 回退），标题大字号响应式 `clamp()`
- 动效：motion 滚动显现（fade-up + stagger），spotlight 跟随鼠标
- 下载按钮：主按钮渐变高亮 + hover scale

## 7. 执行方式

- 主会话负责：设计、仓库初始化、mock 层、集成、构建验证
- **codex 子代理**（`codex exec`，用户机器已装）负责大块前端编写：落地页各区块组件、demo 复制适配
- 每块 codex 产出后主会话审查（`npx tsc --noEmit` + `npm run build` + 浏览器验证）

## 8. 验收清单

- [ ] `npx tsc --noEmit` 零错误，`npm run build` 成功
- [ ] Vercel 部署上线，可访问
- [ ] demo：设备点选 → 网格切换 → 勾选计数 → 查看器（缩放/旋转/星级）→ 筛选排序 → 主题/语言切换 → 导入动画，全部可玩
- [ ] 中英双语完整（落地页 + demo 内）
- [ ] 响应式（桌面/平板/手机）
- [ ] 主仓库不受影响（无代码改动；本项目 spec 除外）

## 9. 明确不做（YAGNI）

- 真实文件导入/解码（demo 只模拟流程）
- 博客/文档站（后续需要再说）
- 用户登录/评论/分析埋点
- 多语言 >2
- 视频/GIF 演示素材
