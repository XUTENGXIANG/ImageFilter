# ImageFilter 落地页实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在独立仓库 `A:\ImageFilter-Website` 构建 ImageFilter 产品落地页（Aceternity 风格 + 真实软件 UI 迷你演示 + 7 个 React Bits 动效组件），部署到 Vercel。

**Architecture:** 网站仓库与主仓库（A:\tenent）完全分离。迷你演示复用软件真实 UI（`src/demo/` 复制自主项目 `src/`），通过 Vite alias 将 `@tauri-apps/*` 替换为 mock 模块注入假数据，UI 组件零修改。落地页各区块由 codex 子代理编写。

**Tech Stack:** Vite 6 + React 19 + TypeScript + Tailwind CSS 4 + motion + GSAP + i18next + React Bits（shadcn MCP 安装）+ Vitest（mock 层测试）

**Spec:** `docs/superpowers/specs/2026-08-11-imagefilter-website-design.md`

## Global Constraints

- 网站仓库路径：`A:\ImageFilter-Website`（与主仓库 A:\tenent 平级，互不依赖）
- demo 内组件代码**零修改**（App.tsx/useScanner.ts/viewer.tsx/components/i18n/lib/types/hooks 原样复制），假数据只从 mock 层注入
- 主仓库 A:\tenent **不改动任何代码**（除本文档目录 docs/superpowers/）
- 文案语言：中英双语，落地页文案复用 README 中文 + 新译英文，调性"高级"（工作流/创作/影像，避免工具化土味）
- 深色主题：近黑 `#050505`；配色 = 紫/蓝/青光晕
- 图标：IconPark（软件 demo 内）、lucide（落地页/React Bits 默认）
- 生产构建必须 `npx tsc --noEmit && npm run build` 零错误
- 每次完成一个 Task 必须 git commit（信息注明 Task 编号）

---

### Task 1: 初始化网站仓库骨架

**Files:**
- Create: `A:\ImageFilter-Website\` 全部脚手架文件

**Interfaces:**
- Produces: 可运行的空 Vite 项目（npm run dev 起服务，npm run build 出静态产物）

- [ ] **Step 1: 脚手架生成**

```bash
cd /a && npm create vite@latest ImageFilter-Website -- --template react-ts
cd ImageFilter-Website && npm install
npm install tailwindcss @tailwindcss/vite tw-animate-css class-variance-authority clsx tailwind-merge i18next react-i18next motion gsap @icon-park/react @base-ui/react @radix-ui/react-context-menu react-masonry-virtualized lucide-react
npm install -D vitest @fontsource-variable/geist
```

- [ ] **Step 2: Tailwind 4 接入**

`vite.config.ts` 加 `@tailwindcss/vite` 插件；`src/index.css` 写入：

```css
@import "tailwindcss";
@import "tw-animate-css";
@import "@fontsource-variable/geist";

:root { font-family: Inter, system-ui, sans-serif; }
```

- [ ] **Step 3: 清理脚手架样板**（删 App.css、logo 等），保留最小 App

- [ ] **Step 4: 验证**

Run: `npm run build`
Expected: 构建成功，无 TS 错误

- [ ] **Step 5: Commit**

```bash
git init && git add -A && git commit -m "task1: Vite+React+TS+Tailwind4 骨架"
```

---

### Task 2: tauri mock 层（TDD）

**Files:**
- Create: `src/demo/mock/fake-data.ts`（假数据生成器，纯函数）
- Create: `src/demo/mock/placeholder.ts`（渐变占位图 data URL 生成器）
- Create: `src/demo/mock/tauri-mock.ts`（invoke/convertFileSrc/Channel/getCurrentWindow/open mock）
- Create: `src/demo/mock/fake-data.test.ts`（Vitest 测试）
- Modify: `vite.config.ts`（alias 配置）

**Interfaces:**
- `fakeData.getDrives(): DriveInfo[]` — 3 个设备：`E:\`（CANON_DC，removable）、`C:\`（本地磁盘，fixed）、`D:\`（照片文件夹，fixed）
- `fakeData.getPhotos(dirPath): ScannedPhoto[]` — 24 张假照片，路径形如 `FAKE:/SD/DCIM/100CANON/IMG_0001.CR2`，扩展名轮换 `.CR2/.ARW/.DNG/.JPG/.NEF`；`exif` 字段：camera=SONY/A7M4 等、aperture/shutter/iso/focalLength 伪随机；`width/height` 随机 4000-8000；`star` 随机 0-5
- `fakeData.getFolderTree(mountPoint): FolderEntry` — 树形：`/DCIM`（children: `100CANON`、`101CANON`）、`/PRIVATE`；每层 photoCount 按子文件夹数伪随机
- `fakeData.getCounts(folderPaths: string[]): Record<string, number>`
- `fakeData.getExif(path): ExifData` — 确定性伪随机（同一路径每次一致，用字符串 hash 做种子）
- `fakeData.getAnalysis(paths): AnalysisResult[]` — 按路径 hash 决定 blurScore/isBlurry/isOverexposed/duplicateGroup
- `placeholder.thumbnail(path): string` — data URL SVG：按路径 hash 选渐变色调 + 中央相机图标 + 噪点，256px
- `placeholder.fullImage(path): string` — 同上 1600px
- `mockInvoke(cmd: string, args: Record<string, unknown>): Promise<unknown>` — 分发表（见 Step 3）
- `mockConvertFileSrc(path: string): string` — 查 placeholder
- `class Channel<T>` — `{ onmessage: ((msg: T) => void) | null; post(msg: T) }`
- `mockGetCurrentWindow()` — `{ minimize(), toggleMaximize(), close(), isMaximized() => Promise<boolean>, onResized() }`
- `mockOpenDialog() => Promise<null>`（返回 null，模拟取消）

**Mock 命令分发表**（invoke 完整清单，来自主项目 grep 核实）：

| 命令 | args | 返回 |
|------|------|------|
| `detect_drives` | — | `Promise<DriveInfo[]>` |
| `browse_directory` | `{ dirPath }` | `Promise<FolderEntry>` |
| `count_folders` | `{ folderPaths }` | `Promise<Record<string,number>>` |
| `scan_directory` | `{ dirPath }` | `Promise<ScannedPhoto[]>`（延迟 300ms） |
| `batch_thumbnails` | `{ filePaths, maxSize, onProgress }` | 逐张 post 进度 → `Promise<string[]>`（路径表） |
| `get_thumbnail_path` | `{ filePath, maxSize }` | `Promise<string>`（`FAKE:/thumb/{hash}.jpg`） |
| `get_preview_image` | `{ filePath }` | `Promise<string>`（`FAKE:/preview/{hash}.jpg`） |
| `get_full_image` | `{ filePath }` | `Promise<string>`（`FAKE:/full/{hash}.jpg`，延迟 600ms 模拟解码） |
| `get_exif` | `{ filePath }` | `Promise<ExifData>` |
| `import_photos` | `{ onProgress }` | 逐条 post 进度（4 条递增）→ `Promise<number>`（延迟 1200ms） |
| `analyze_photos` | `{ filePaths, onProgress }` | post 进度 → `Promise<AnalysisResult[]>` |
| `find_duplicates` | `{ filePaths }` | `Promise<AnalysisResult[]>` |
| `stop_analysis` | — | `Promise<void>` |
| `set_glass_bg` | `{ enabled, dark }` | `Promise<void>`（空操作） |
| `eject_drive` | `{ mountPoint }` | `Promise<void>` |
| `open_folder` | `{ path }` | `Promise<void>`（空操作） |

- [ ] **Step 1: 写 fake-data 测试（先红）**

`src/demo/mock/fake-data.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { getDrives, getPhotos, getExif } from "./fake-data";

describe("fake-data", () => {
  it("返回 3 个设备, 可移动设备在前", () => {
    const d = getDrives();
    expect(d).toHaveLength(3);
    expect(d[0].driveType).toBe("removable");
  });
  it("同一路径的 EXIF 确定性一致", () => {
    const p = "FAKE:/SD/DCIM/100CANON/IMG_0001.CR2";
    expect(getExif(p)).toEqual(getExif(p));
  });
  it("照片扩展名在 RAW/JPG 集合内", () => {
    const photos = getPhotos("FAKE:/SD/DCIM/100CANON");
    expect(photos.length).toBeGreaterThan(0);
    for (const ph of photos)
      expect(ph.path).toMatch(/\.(CR2|ARW|DNG|JPG|NEF)$/i);
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npx vitest run`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 fake-data.ts / placeholder.ts / tauri-mock.ts**

fake-data 核心：字符串 hash 种子 → 确定性伪随机；`getPhotos` 生成 24 张（编号 `IMG_%04d`）；placeholder 用 `<svg>` 模板字符串生成渐变 + 图标。

tauri-mock 关键代码：

```ts
// 按命令分发
const handlers: Record<string, (args: any) => Promise<unknown>> = {
  detect_drives: async () => fakeData.getDrives(),
  browse_directory: async (a) => fakeData.getFolderTree(a.dirPath),
  scan_directory: async (a) => delay(300, fakeData.getPhotos(a.dirPath)),
  get_exif: async (a) => fakeData.getExif(a.filePath),
  // ...其余按上表
};
export async function invoke(cmd: string, args: any = {}): Promise<any> {
  const h = handlers[cmd];
  if (!h) throw new Error(`[mock] unknown command: ${cmd}`);
  return h(args);
}
export function convertFileSrc(path: string): string {
  return path.startsWith("FAKE:") ? placeholder.forPath(path) : path;
}
export class Channel<T> { onmessage: ((m: T) => void) | null = null; post(m: T) { this.onmessage?.(m); } }
export function getCurrentWindow() { return { minimize: async()=>{}, toggleMaximize: async()=>{}, close: async()=>{}, isMaximized: async()=>false, onResized: ()=>{}, }; }
```

`export const open = async () => null;`

- [ ] **Step 4: 运行测试确认通过**

Run: `npx vitest run`
Expected: PASS 3/3

- [ ] **Step 5: vite.config.ts 加 alias**

```ts
import path from "path";
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: {
    "@tauri-apps/api": path.resolve(__dirname, "src/demo/mock/tauri-mock.ts"),
    "@tauri-apps/plugin-dialog": path.resolve(__dirname, "src/demo/mock/tauri-mock.ts"),
  }},
});
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "task2: tauri mock 层(fake-data/placeholder/invoke) + TDD 验证"
```

---

### Task 3: 复制软件 UI 进 demo 并跑通

**Files:**
- Create: `src/demo/ui/` 下全部复制文件
- Create: `src/demo/ImageFilterDemo.tsx`
- Create: `src/demo/index.css`（复制主项目 `src/index.css` 并适配）
- Create: `src/main.tsx`（改为渲染 LandingPage 占位 + demo）

**Interfaces:**
- `ImageFilterDemo` — 导出 React 组件 `({ className })`，内部渲染软件主界面
- 复制清单（从 `A:\tenent\src\`）：`App.tsx`、`useScanner.ts`、`viewer.tsx`、`contextmenu.tsx`、`panel.tsx`、`types.ts`、`i18n/`、`lib/`、`hooks/`、`components/`（全部）→ 落到 `src/demo/ui/` 同结构
- **关键**：`title-bar.tsx` 的窗口按钮（最小化/最大化/关闭）在 demo 中隐藏——用 CSS 类或传递 prop 控制；`set_glass_bg` 已被 mock 空操作，毛玻璃不生效属预期

- [ ] **Step 1: 复制文件**

```bash
cd /a/ImageFilter-Website/src/demo
mkdir -p ui
cp -r /a/tenent/src/App.tsx /a/tenent/src/useScanner.ts /a/tenent/src/viewer.tsx \
      /a/tenent/src/contextmenu.tsx /a/tenent/src/panel.tsx /a/tenent/src/types.ts ui/
cp -r /a/tenent/src/components /a/tenent/src/i18n /a/tenent/src/lib /a/tenent/src/hooks ui/
cp /a/tenent/src/index.css ./index.css
```

- [ ] **Step 2: 复制后修正 import 相对路径**

`ui/` 内文件互相引用用相对路径（`./App`、`../lib/utils` 等原样保留即可）；main.tsx 的 `./i18n` 改为 `./demo/ui/i18n`

- [ ] **Step 3: ImageFilterDemo.tsx**

```tsx
import App from "./ui/App";

export default function ImageFilterDemo({ className }: { className?: string }) {
  return (
    <div className={className} style={{ height: 560, overflow: "hidden", borderRadius: 12 }}>
      <App />
    </div>
  );
}
```

- [ ] **Step 4: main.tsx 临时渲染 demo 验证**

```tsx
import ImageFilterDemo from "./demo/ImageFilterDemo";
import "./demo/index.css";
// 临时: 仅渲染 demo
```

- [ ] **Step 5: 验证 demo 全交互**

Run: `npm run dev` → 浏览器打开
Expected（逐项点验）：设备列表 3 项可点选 → 文件夹树展开 → 照片网格 24 张渐变缩略图 → 单击勾选/再点取消、计数联动 → 双击打开查看器（缩放/旋转/星级/方向键切换）→ 星级排序/筛选 → 主题切换 → 中英切换 → 底部导入按钮走完 4 条进度完成动画
若查看器黑屏或图片不显示：检查 `convertFileSrc` mock 返回值是否被 `<img>` 直接消费

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "task3: demo 复用软件真实 UI, mock 数据跑通全交互"
```

---

### Task 4: React Bits 组件安装

**Files:**
- Modify: `components.json`（建在仓库根，registry: @react-bits）

**Interfaces:**
- Produces: `src/components/ui/` 或 `src/components/react-bits/` 下 7 个组件可用

- [ ] **Step 1: components.json 注册**

```json
{ "registries": { "@react-bits": "https://reactbits.dev/r/{name}.json" } }
```

- [ ] **Step 2: 用 shadcn MCP 搜索确切组件名**（本会话 MCP 可用）

调用 `search_items_in_registries`，查询：`fold text`、`specular button`、`card nav`、`dot field`、`carousel`、`border glow`、`card swap`
确认每个组件的注册名（如 `SpecularButton-TS-TW`），注意 fold-text 实际注册名可能为 `FoldText-TS-TW`

- [ ] **Step 3: 安装（TS-TW 变体，-y）**

```bash
npx shadcn@latest add @react-bits/<Name>-TS-TW -y
# 逐个安装 7 个; 组件落到 src/components/ui/ 或按 MCP 提示
```

若某组件注册表缺失（如 fold-text 未搜到）：fallback = 从 reactbits.dev 对应页面复制源码（jsrepo: `npx jsrepo add https://reactbits.dev/default/TextAnimations/FoldText` 或手动复制），依赖 gsap 已在 Task 1 安装

- [ ] **Step 4: 冒烟验证**

Run: `npm run build`
Expected: 7 个组件全部编译通过（tsc 零错误）

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "task4: 安装 React Bits 7 动效组件(fold-text/specular/cardnav/dotfield/carousel/borderglow/cardswap)"
```

---

### Task 5: 落地页骨架 + Nav + Hero（codex 子代理）

**Files:**
- Create: `src/landing/LandingPage.tsx`、`Nav.tsx`、`Hero.tsx`、`DemoWindow.tsx`、`index.css`（落地页样式层）
- Modify: `src/main.tsx`（渲染 LandingPage；demo 临时验证移除）
- Create: `src/landing/i18n.ts`（落地页文案 zh/en 两份翻译对象）

**Interfaces:**
- `LandingPage` — 单页根组件，锚点 `#features #screenshots #workflow #download`
- `Nav` — props `{ lang, onToggleLang }`；Card Nav 卡片滑动效果
- `Hero` — fold-text GSAP 标题 + DotField 背景 + `<DemoWindow/>`
- `DemoWindow` — 玻璃边框窗口（Border Glow）+ 装饰标题栏 + `<ImageFilterDemo/>` 内嵌
- 落地页语言状态由 LandingPage 持有（useState<"zh"|"en">），透传 Nav 与各区块

**codex 提示词（粘贴给 codex exec）：**

```
你在 A:\ImageFilter-Website 项目中工作。这是一个产品落地页网站。
任务：实现 src/landing/ 下的落地页骨架与 Hero 区。

背景：
- ImageFilter 是摄影师 RAW 照片筛选归档工具(工作流: 拍摄→初筛→归档)
- 网站风格参考 ui.aceternity.com: 深色近黑背景(#050505), 紫/蓝/青 radial 光晕, 玻璃拟态卡片
- 技术栈: React 19 + Tailwind CSS 4 + motion + GSAP + i18next(单括号插值 {n})
- 已有可用组件: src/components/ui/ 下 React Bits 组件(FoldText/SpecularButton/CardNav/DotField/Carousel/BorderGlow/CardSwap), src/demo/ImageFilterDemo.tsx(软件界面演示)

实现要求:
1. Nav.tsx: 玻璃顶栏(fixed, backdrop-blur), Logo + ImageFilter 字样, 锚点导航(特性/界面/工作流/下载)用 CardNav 效果, 右上角 中/EN 切换按钮
2. Hero.tsx: 
   - 背景: DotField 组件 + 紫/蓝/青 radial 光晕(绝对定位 blur 层)
   - 大标题 "ImageFilter" 用 FoldText 组件(GSAP 逐字 3D 折叠, 滚动进入视口触发)
   - 副标题: "为创作者而生的 RAW 选片与归档工作流" (i18n 双语)
   - 两个下载按钮: 玻璃风格(SpecularButton 效果), Windows 图标 + macOS 图标, 链接 GitHub Releases(https://github.com/XUTENGXIANG/ImageFilter/releases)
3. DemoWindow.tsx: 大圆角窗口(max-w-5xl), BorderGlow 边框, 顶部装饰标题栏(红黄绿圆点 + ImageFilter 文字), 内嵌 <ImageFilterDemo/>, 窗口下方加一行小字提示 "点击照片体验选片流程 — Click a photo to try"
4. LandingPage.tsx: 组装 Nav + Hero + 占位区块(Features/Workflow/Download/Footer 先留空组件或注释)
5. 文案放 src/landing/i18n.ts 统一管理(zh/en 两个对象, key 相同), 通过 useTranslation 或 props 使用

约束:
- 不要修改 src/demo/ 下任何文件(软件 UI 复制品, 零修改约定)
- 不要修改 src/components/ui/ 下 React Bits 组件
- 中文文案调性高级: 用"选片工作流/创作/影像", 避免"智能导入工具"等土味表述
- 构建验证: npx tsc --noEmit 零错误 + npm run build 成功
- 完成后运行 npm run dev 人工确认 Hero 动效正常(标题折叠动画触发, 点阵跟随鼠标)
```

- [ ] **Step 1: 运行 codex**

```bash
cd /a/ImageFilter-Website && "$LOCALAPPDATA/OpenAI/Codex/bin/8e8bf206e63ac436/codex.exe" exec --full-auto "<上面的提示词>"
```

- [ ] **Step 2: 主会话审查**

Run: `npx tsc --noEmit && npm run build`
Expected: 零错误；代码审查：文案调性、锚点结构、demo 零修改约束是否遵守

- [ ] **Step 3: 修复审查发现的问题（主会话直接改）**

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "task5: 落地页骨架+Nav(Hero(fold-text/DotField)+DemoWindow(BorderGlow)"
```

---

### Task 6: Screenshots 轮播 + Features 卡片（codex 子代理）

**Files:**
- Create: `src/landing/Screenshots.tsx`、`Features.tsx`
- Modify: `src/landing/LandingPage.tsx`（挂载两区块）
- Create: `src/assets/`（复制 `A:\tenent\assets\screenshot.png` + `A:\tenent\src-tauri\icons\128x128.png`）

**Interfaces:**
- `Screenshots` — Carousel 组件轮播界面截图（当前 1-2 张，卡片可后续追加；加注释说明补图位置）
- `Features` — 6 张卡片：RAW 原生解码 / LrC 同款星级筛选 / AI 废片检测 / 命名规则自动归档 / MD5 数据校验 / 全部本地处理；CardSwap hover 翻转显详情 + BorderGlow 边框

**codex 提示词要点（与 Task 5 同模板，替换任务段）：**
- Screenshots.tsx：用 Carousel 组件（React Bits），卡片 16:9 圆角、内含截图；当前 assets 只有 screenshot.png，其余卡片先用渐变占位并注释"待补充截图"
- Features.tsx：6 卡网格（md:2 / lg:3），每卡正面=IconPark 图标+标题，反面=一句描述；hover 用 CardSwap 翻转；边框 BorderGlow；滚动进场 motion fade-up stagger
- 中文文案（高级调性）：RAW 原生解码（"主流 RAW 格式原生支持，DNG 内置专用解码器"）、星级筛选（"LrC 同款星级体系，快速完成初筛"）、AI 检测（"模糊/过曝/连拍重复自动识别"）、命名规则（"模板化自动归档"）、MD5（"校验保证数据完整"）、本地处理（"全程本地，不上传"）

- [ ] **Step 1: 运行 codex（同 Task 5 命令格式）**
- [ ] **Step 2: 主会话审查 + 修复**

Run: `npx tsc --noEmit && npm run build`

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "task6: Screenshots 3D轮播 + Features 卡片(CardSwap+BorderGlow)"
```

---

### Task 7: Workflow + Download + Footer（codex 子代理）

**Files:**
- Create: `src/landing/Workflow.tsx`、`Download.tsx`、`Footer.tsx`
- Modify: `src/landing/LandingPage.tsx`（挂载）

**Interfaces:**
- `Workflow` — 三步横排（插卡识别 → 预览筛选 → 导入归档），数字徽标 + 图标 + 文案，滚动进场动画
- `Download` — 版本 v1.0.0 + 3 张安装包卡片（Windows setup.exe 推荐 / Windows MSI / macOS universal.dmg），每卡文件名 + 说明 + SpecularButton 下载按钮（直链 GitHub Releases 具体资产 URL）+ "查看 GitHub Releases" 链接
- `Footer` — GitHub 仓库链接 + MIT 许可 + © 2026

**codex 提示词要点：**
- 下载链接直链：`https://github.com/XUTENGXIANG/ImageFilter/releases/download/v1.0/ImageFilter_1.0.0_x64-setup.exe`、`.../ImageFilter_1.0.0_x64_zh-CN.msi`、`.../ImageFilter_1.0.0_universal.dmg`
- Workflow 步骤：① 插卡自动识别设备 ② 秒级预览 + 星级筛选（可提查看器）③ 一键导入归档（命名规则 + MD5）
- Footer 底部加"在演示窗口里体验"小入口锚点回顶部

- [ ] **Step 1: 运行 codex**
- [ ] **Step 2: 审查 + 修复**（tsc + build）
- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "task7: Workflow三步+Download安装包(SpecularButton)+Footer"
```

---

### Task 8: 响应式 + 视觉打磨 + 最终验证

**Files:**
- Modify: 各 landing 组件（响应式断点、间距、字号）

- [ ] **Step 1: 响应式检查**

手动验证三档：桌面 1440px / 平板 768px / 手机 390px
要求：Nav 锚点收起到汉堡菜单（或隐藏副项）；Hero 标题 clamp() 缩放；DemoWindow 宽度 100% 自适应（demo 内部若固定宽度，外层 scale 适配）；Features 网格 1→2→3 列；Download 卡片纵向堆叠

- [ ] **Step 2: 视觉打磨**

对照 spec 第 6 节逐项检查：背景光晕、网格底纹（如需）、spotlight 跟随、动效时序、中英切换状态、页面滚动流畅度（demo 内部滚动与页面滚动隔离）

- [ ] **Step 3: 全量验证**

Run: `npx tsc --noEmit && npx vitest run && npm run build`
Expected: 全绿

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "task8: 响应式适配+视觉打磨+全量验证"
```

---

### Task 9: GitHub 仓库创建 + Vercel 部署

**Files:**
- Modify: `README.md`（网站仓库自己的简介）

- [ ] **Step 1: 创建 GitHub 仓库**（gh CLI，需用户批准）

```bash
gh repo create ImageFilter-Website --public --source . --remote origin --push
```

- [ ] **Step 2: Vercel 部署**

```bash
npx vercel login && npx vercel --prod
```
或用户在 Vercel dashboard 导入该仓库。域名：默认 `imagefilter-website.vercel.app`

- [ ] **Step 3: 上线验证**

访问线上 URL：全区块渲染、demo 可玩、下载链接可达、中英切换正常、无控制台报错

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "task9: 部署上线"
```

---

### Task 10: 项目日志 + 收尾

- [ ] **Step 1: 主仓库 A:\tenent 更新 PROJECT_LOG.md**

在"最近开发"顶部追加条目：2026-08-11 ImageFilter 落地页网站（独立仓库、Aceternity 风格、真实 UI 迷你演示、React Bits 7 组件、Vercel 部署地址）

- [ ] **Step 2: 收尾清单**

- [ ] 落地页与 demo 均无 console 报错
- [ ] 手机/平板/桌面三档正常
- [ ] GitHub 仓库公开、无 agent 文件（.mcp.json 在网站仓库如生成需加 .gitignore）
- [ ] 主仓库 A:\tenent 工作树干净（仅 docs/ 新增 plan 文档待提交）

```bash
cd /a/tenent && git add docs/superpowers/plans/ && git commit -m "docs: 落地页实现计划"
```

---

## Self-Review 结果（写计划后自查）

1. **Spec 覆盖**：✓ 独立仓库（T1/T9）、Aceternity 风格（T5）、真实 UI 演示零修改（T3）、mock 层（T2）、7 组件（T4）、双语（T5-T7）、素材（T6）、部署（T9）、codex 分工（T5-T7）
2. **占位符扫描**：无 TBD；codex 提示词含完整要求与验收标准
3. **类型一致性**：fakeData 函数签名在 T2 定义、T3 消费处一致；demo 组件路径 `src/demo/ui/` 与 import 修正一致
