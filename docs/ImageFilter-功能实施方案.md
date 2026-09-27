# ImageFilter 功能实施方案（P0 / P1）

> 定位：本文是**动手前的施工图**，不是交接文档。现状与机制见 [ImageFilter-交接与上手.md](ImageFilter-交接与上手.md)，深度证据见 [ImageFilter-架构与机制走查.md](ImageFilter-架构与机制走查.md)。
>
> 基线：HEAD `6996b11` · 版本 `1.0.1` · 工作区干净 · `npx tsc --noEmit` exit 0 · `cargo test --lib` 26 活跃 + 3 忽略。
>
> 每个 Phase 都是**可独立提交、可独立回归**的最小单元；建议一次只做一个 Phase，做完跑一次验收清单再进下一个。

---

## 0. 总览与顺序

| Phase | 内容 | 触及文件 | 新增命令 | 预估 |
|---|---|---|---|---|
| **1** | 评分后自动前进 | `viewer.tsx`、`App.tsx`、`i18n/{zh,en}.ts` | 0 | 半天 |
| **2** | 撤销 / 重做（`Ctrl+Z` / `Ctrl+Shift+Z`） | `useScanner.ts`、`App.tsx`、`viewer.tsx`、`i18n` | 0 | 1 天 |
| **3** | 1:1 像素查看（`Z`） | `viewer.tsx`、`i18n` | 0 | 半天 |
| **4** | 颜色标签 + 可叠加筛选 | `useScanner.ts`、`App.tsx`、`photo-toolbar.tsx`、`photo-card.tsx`、`viewer.tsx`、`contextmenu` 用法、`i18n` | 0 | 1.5 天 |
| **5** | 评分/色标写 XMP 边车（可选开关） | 新增 `src-tauri/src/xmp.rs`、`lib.rs`、`useScanner.ts`、`settings-dialog.tsx`、`i18n` | 2 | 2 天 |
| **6** | 接上"后端已有但没界面"的三件事 | `useScanner.ts`、`import-bar.tsx`、`advanced-options.tsx`、`importer.rs`、`db.rs`、`i18n` | 0（复用 3 个） | 1.5 天 |

**为什么是这个顺序**：1–4 是纯前端、零后端风险、每次 culling 都会用到；5 引入磁盘写入（要谨慎）放后面；6 改导入返回值属于**契约变更**，需要同步前端 + 可能同步落地页 demo 的 mock 层，所以放最后单独做。

**怎么用这份文档（跨会话操作方式）**：

一个会话只做一个 Phase（或本文后面指定的"会话分组"），做完、在 GUI 里验过、**提交**之后再开下一个。原因是会话历史每一轮都会重发，长会话后期每一轮都在为早已翻篇的内容付费；换会话的启动成本（重读本文 + 目标文件）通常远低于此。

每个 Phase 收尾时必须做三件事，否则下个会话会重读代码库或做出不一致的决定：

1. 提交改动，保证工作区干净（`git status` 无未跟踪的源码改动）；
2. 在文末 **[决策日志](#附-决策日志decision-log)** 追加 5 行（代码状态 / 已定决定 / 被否决方案 / 验收结果 / 遗留项）；
3. 下个会话开场只发一句：指向**本文件 + 目标 Phase**，并要求"先输出改动计划再动手"。

**共同的前置约束（每个 Phase 都要守）**：

1. **不许简化查看器的四条加载分支**（交接文档 §5-1）。所有改动是在这四条之上加分支，不是替换。
2. **评分目前是"单一事实源"**：`useScanner.setRating` 是唯一写入点（[useScanner.ts:127](../src/useScanner.ts:127)），任何新写入路径（撤销、XMP、标签）都必须汇流到这里或与它并列并被同一处序列化，否则会出现"查看器与网格不一致"这类回归（交接文档 §7 修过）。
3. **i18n 双写**：zh 与 en 各 171 个 key 目前完全对齐，**新增 key 必须两边同时加**，否则会踩 fallback。
4. **落地页 demo 的 mock 层**（另一仓库，见 [superpowers/specs](superpowers/specs/2026-08-11-imagefilter-website-design.md)）会 mock Tauri API：**Phase 5/6 一旦改命令签名，需要同步那份 mock**，否则官网迷你演示会白屏。

---

## Phase 1 · 评分后自动前进（Auto-advance）

### 目标
在查看器内按 `J` / `X` / `1`–`5`（以及点击星条）后，自动跳到下一张。**打完最后一张自动关闭查看器回到网格**（符合"翻完这一批"的心智）。默认开启，可在设置里关。

### 为什么
初筛是"手比脑快"的场景。现在每评一张都要自己按 `→`，等于每个决策多一次按键，是全流程里最廉价也最高频的摩擦。

### 改动点

**A. `src/viewer.tsx`**

1. 键盘 handler 内的评分分支（[viewer.tsx:268-270](../src/viewer.tsx:268)）：

```tsx
else if (e.key.toLowerCase() === "j") { onRate(photo.path, 3); autoNext(); }
else if (e.key.toLowerCase() === "x") { onRate(photo.path, 0); autoNext(); }
else if (e.key >= "1" && e.key <= "5") { onRate(photo.path, Number(e.key)); autoNext(); }
```

2. 新增 `autoNext`（放在 `navigateTo` 之后，避免 TDZ）：

```tsx
const autoNext = useCallback(() => {
  if (!autoAdvance) return;
  if (cur + 1 < photos.length) navigateTo(cur + 1);
  else handleClose();          // 最后一张 → 回网格
}, [autoAdvance, cur, photos.length, navigateTo, handleClose]);
```

3. 星条按钮（[viewer.tsx:357](../src/viewer.tsx:357)）点击后同样调 `autoNext()`，但**"点同一颗星取消评分"也前进吗？** → 前进。行为一致性优先，用户想留下就用 `←` 回退。
4. 新增 prop `autoAdvance: boolean`。

### 关键陷阱（必须实现，否则会引出新 bug）

- **`navigateTo` 会写 `lastSwitchRef`**（[viewer.tsx:100](../src/viewer.tsx:100) 附近），而该 ref 用来判定"快速连切 → 预览请求 debounce 120ms"（[viewer.tsx:184-206](../src/viewer.tsx:184)）。自动前进如果也走这条路径，用户"慢速逐张评分"会被误判成 rapid，反而多出 120ms 延迟。
  **做法**：给 `navigateTo` 加第二个参数 `markSwitch = true`；`autoNext` 传 `false`。这样自动前进不写 `lastSwitchRef`，下一张按正常路径立即发预览。
- **不要等图片加载完成再前进**。加载是异步的，等它会把"评分"这个操作变成有延迟的动作。刷新键位流即可。
- **不要在最后一张循环回第一张**——那会让 culling 永远不结束；关闭查看器才是正确终点。
- 与"锚点重锚"effect（[viewer.tsx:232-245](../src/viewer.tsx:232)）的交互：开着星级筛选时自动前进到下一张，若下一张不在筛选结果里则它本来就不在 `photos` 数组中，`navigateTo` 会自然跳过——**这正好是想要的行为**，不要额外处理。

**B. `src/App.tsx`**：把 `autoAdvance` 从 `useScanner` 传入 `PhotoViewer`（[App.tsx:604-616](../src/App.tsx:604)）。

**C. `src/useScanner.ts`**：新增持久化设置，照抄 `preloadFull` 的写法（[useScanner.ts:142-151](../src/useScanner.ts:142)）：

```ts
const [autoAdvance, setAutoAdvance] = useState(() => localStorage.getItem("imagefilter-auto-advance") !== "false"); // 默认开
```

并在 return 对象里导出（[useScanner.ts:363-374](../src/useScanner.ts:363)）。

**D. `src/components/settings-dialog.tsx`**：仿 `preloadFull` 那一行（[settings-dialog.tsx:103-105](../src/components/settings-dialog.tsx:103)）加一个 `SettingRow` + `Toggle`，链路 `App → TitleBar → SettingsDialog`（三处 props 透传，与 `preloadFull` 完全一致的路径）。

**E. i18n**：`settings.autoAdvance` / `settings.autoAdvanceDesc` / `help.autoAdvance`（zh + en 各 3 个）。

### 验收
1. 打开查看器，连按 `3` 五次 → 前进 5 张，星级落在正确的 5 张上（对照网格，**不能出现"改了另一张"**——这是 §7 修过的回归）。
2. 逐张按 `X` 到文件夹最后一张 → 查看器自动关闭，网格正常显示。
3. 慢速（>1s/张）评分时，下一张的高清图**立即**出现，无明显延迟卡顿；快速连按（<500ms）时仍保持原有防抖手感。
4. 关掉设置开关 → 评分不再前进，行为回到现状。

---

## Phase 2 · 撤销 / 重做

### 目标
`Ctrl+Z` 撤销最近一次**评分或勾选**变更，`Ctrl+Shift+Z` 重做。栈深 100，只存在于当前会话（**不跨重启**——跨重启的撤销语义会很难解释，且要落盘）。

### 设计：补丁栈（patch stack），而不是状态快照

快照法（存整份 `ratings`）在几千张时每次按键都要克隆大对象。这里用**逆补丁**：

```ts
type Patch =
  | { kind: "rating"; path: string; prev: number; next: number }
  | { kind: "selection"; paths: string[]; prev: string[]; next: string[] };
```

- `undoStack: Patch[]`、`redoStack: Patch[]`，用 `useRef` 持有（不需要触发渲染）。
- 任何新变更 `push` 到 `undoStack` 并**清空 `redoStack`**。
- 撤销 = 取栈顶，应用其 `prev`，移入 `redoStack`；重做反之。

UI 上给一个 toast 反馈：`撤销：4★ → 3★`，让用户知道撤销了什么（复用 [App.tsx:271](../src/App.tsx:271) 的 `showToast`）。

### 改动点

**A. `src/useScanner.ts`**

1. `setRating`（[useScanner.ts:127-133](../src/useScanner.ts:127)）内部先记录补丁：

```ts
const setRating = useCallback((path: string, stars: number) => {
  setRatings((prev) => {
    const before = prev[path] ?? 0;
    if (before === stars) return prev;              // 无变化不入栈
    pushPatch({ kind: "rating", path, prev: before, next: stars });
    const next = { ...prev, [path]: stars };
    try { localStorage.setItem("imagefilter-ratings", JSON.stringify(next)); } catch {}
    return next;
  });
}, []);
```

> ⚠️ 在 `setRatings` 的 updater 里做副作用（`pushPatch`）在 React 18/19 的 StrictMode 下会被**调用两次**（开发环境）。当前 `main.tsx` 未使用 StrictMode，所以现状可接受；但更稳的写法是**在 updater 外面**用 `ratings[path]` 读取旧值——代价是 `setRating` 需要依赖 `ratings`，会让所有卡片的 `onRate` 引用变化、双层 memo 失效（这正是交接文档 §6 P2-6 提到的老问题）。**权衡后建议**：保持 updater 内写入 + 在 `pushPatch` 内做基于 `(path, prev, next)` 的**去重**（与栈顶完全相同的补丁不重复入栈），这样即使被调用两次也只入栈一条。

2. 勾选变更同理：`handlePhotoClick`（[useScanner.ts:73-101](../src/useScanner.ts:73)）、`selectAll`、`clearSelection` 三处都要记录 `prev/next` 的路径数组。

3. 新增导出：`undo()`、`redo()`、`canUndo`、`canRedo`。

**B. `src/App.tsx`**：新增一个 `keydown` 监听（放在现有快捷键 effect 旁边 [App.tsx:180](../src/App.tsx:180)）：

```tsx
if ((e.ctrlKey || e.metaKey) && key === "z") {
  e.preventDefault();
  e.shiftKey ? redo() : undo();
  return;
}
```

注意 [App.tsx:160-171](../src/App.tsx:160) 已经拦了 `Ctrl+A`，这里要**同时**在输入框内豁免（`e.target` 是 INPUT/TEXTAREA 时不处理），否则用户没法在目标目录/模板输入框里撤销打字。

**C. `src/viewer.tsx`**：查看器的 keydown handler 里也要处理 `Ctrl+Z`（否则在查看器内撤销无效）。或者更干净：把撤销键放在**唯一的 window 级 handler** 里，并让查看器的 handler 对它早退。

### 关键陷阱

- **撤销必须同时撤销 UI 连锁反应**：撤销一个评分，如果当前开着 ≥N 星筛选，撤回后那张图会重新出现/消失——这是**正确**行为。但查看器的锚点逻辑（[viewer.tsx:232-245](../src/viewer.tsx:232)）会因列表变化重锚，需要验收"在查看器里撤销评分不会跳图/关闭"。
- **撤销不跨越文件夹切换**：`loadFolder` 应清空两个栈（否则会把 A 文件夹的评分撤销到 B 文件夹的展示上，用户看到的是"没反应"）。同理 `browseDrive`。
- **不做撤销的**：导入（文件已落盘，撤销要删文件——另立功能）、分析结果、筛选条件本身。要在 help 里写清楚，避免误解。

### 验收
1. 打分 → `Ctrl+Z` → 星级回到上一状态（网格徽标 + localStorage 同步回退）。
2. 连打 5 张分 → 连按 `Ctrl+Z` 5 次 → 5 张全部回退，顺序正确（后进先出）。
3. 撤销 2 次 → 重做 1 次 → 状态与"撤销 1 次"相同。
4. 勾选 3 张 → `Ctrl+Z` → 勾选回退。
5. 切文件夹后按 `Ctrl+Z` → **无事发生**（不报错、不改别的文件夹的数据）。
6. 在模板输入框内 `Ctrl+Z` → 撤销的是**输入文字**，不是星级。

---

## Phase 3 · 1:1 像素查看

### 目标
按 `Z` 在"适应窗口"与"1:1 实际像素"之间切换；进入 1:1 时**以鼠标位置为锚点**放大（如果鼠标不在图上则以视图中心为锚点），这样能立刻看清"眼睛/对焦点到底糊没糊"。

### 现状与难点
现在只有连续缩放 `scale`（0.2–8×，[viewer.tsx:278-285](../src/viewer.tsx:278)），**没有到 1:1 的那一档**。而 `<img>` 用的是 `max-w-full max-h-full object-contain` + CSS `transform: scale(...)`（[viewer.tsx:391-399](../src/viewer.tsx:391)），所以 `scale=1` 是"适应窗口"，**不等于 100%**。

### 算法（尺寸只从 DOM 读一次，不引入后端改动）

```ts
/** 适应窗口比例 = 元素盒子 / 自然尺寸 */
function fitScaleOf(imgEl: HTMLImageElement) {
  const r = imgEl.getBoundingClientRect();          // 已含当前 transform，需除以 scale 还原
  const s = scaleRef.current || 1;
  const w = r.width / s, h = r.height / s;
  return Math.min(w / imgEl.naturalWidth, h / imgEl.naturalHeight);
}

/** 以容器内坐标 (cx, cy)（相对视图区中心）为锚点跳到 1:1 */
function zoomToActual(cx: number, cy: number) {
  const imgEl = imgRef.current; if (!imgEl) return;
  if (scale > 1) { setScale(1); setOffset({ x: 0, y: 0 }); return; }   // 再按一次 → 适应窗口
  const k = 1 / fitScaleOf(imgEl);
  setScale(k);
  setOffset({
    x: offset.x + (cx - offset.x) * (1 - k),   // 锚点保持不动
    y: offset.y + (cy - offset.y) * (1 - k),
  });
}
```

- `cx/cy` 取法：记录一个 `onMouseMove` 更新的 `hoverRef = { x: e.clientX - centerX, y: e.clientY - centerY }`（`centerX/Y` 来自图片区 `getBoundingClientRect()` 的中心）。鼠标没进过图区就用 `{0,0}`（视图中心）。
- 需要给 `<img>` 加 `ref={imgRef}`（[viewer.tsx:386](../src/viewer.tsx:386)）。
- 若 `1/fitScale` 落在 0.2–8 之外，夹到范围内（[viewer.tsx:264-265](../src/viewer.tsx:264) 的上下限）。

### 改动点
- `viewer.tsx`：新增 `imgRef`、`hoverRef`、`fitScaleOf`、`zoomToActual`；keydown 加 `z` 分支（注意别和现有的 `0`（重置）冲突，`0` 保持"回适应窗口 + 清旋转/偏移"）；底部提示条加一条 `Z 1:1`（[viewer.tsx:419-426](../src/viewer.tsx:419)）。
- 可选增强：双击图面切 1:1（现在双击无绑定，不冲突）。
- i18n：`viewer.actual`（zh: `"1:1 实际像素 (Z)"`）。

### 验收
1. 打开一张 RAW，按 `Z` → 图像明显放大到 100%（能数清像素级细节），再按 `Z` 回到适应窗口。
2. 鼠标放在画面左上某细节上按 `Z` → **该细节仍在鼠标位置附近**（锚点正确）。
3. 在 1:1 下拖动平移正常、滚轮缩放正常；按 `0` 回适应窗口。
4. 图片小于窗口时（如小 JPEG）按 `Z` 不应放大到超过 1:1。

---

## Phase 4 · 颜色标签 + 可叠加筛选

这是四个 Phase 里唯一"改数据模型"的，但**只加不改**，风险可控。

### 4.1 颜色标签（label）

沿用评分那套（localStorage + `Record<path, T>`），新增独立 key，**不动 `imagefilter-ratings`**：

```ts
// useScanner.ts
export type Label = "red" | "yellow" | "green" | "blue" | "purple";
const [labels, setLabels] = useState<Record<string, Label>>(() => {
  try { return JSON.parse(localStorage.getItem("imagefilter-labels") || "{}"); } catch { return {}; }
});
const setLabel = useCallback((path: string, label: Label | null) => { /* 同 setRating，含 Phase 2 的补丁记录 */ }, []);
```

- **快捷键**：`6`–`9` + `0` 被占用，`0` 是查看器重置。建议用 **`F1`–`F5`**？会撞系统。**用 `Ctrl+1`–`Ctrl+5`**（正好与"`1`–`5` 是星级"形成记忆对照），`Ctrl+0` 清除标签。备选：不加全局快捷键，只走右键菜单（[App.tsx:226-233](../src/App.tsx:226) 的评分菜单下面加一个"颜色标签"子菜单）+ 卡片角落一个小三角。
- **展示**：卡片左上角徽标行末尾加一个色点（[photo-card.tsx:69-76](../src/components/photo-card.tsx:69)）；查看器顶部工具栏加一排色点按钮（[viewer.tsx:355-360](../src/viewer.tsx:355) 星条旁边）。
- **不做**：标签改名/自定义、标签自动推断（AI 猜场景）。

### 4.2 筛选拆成"星级 + 标签 + 分析结果"三个维度叠加

现在只有 `starFilter` 一个数字（[App.tsx:199-201](../src/App.tsx:199)）。改成：

```ts
const [starFilter, setStarFilter] = useState(0);            // 0=全部, 1-5=≥N星（保持现状语义）
const [labelFilter, setLabelFilter] = useState<Label[]>([]); // 空=全部；多选=命中任一
const [flagFilter, setFlagFilter] = useState<FlagFilter>("all"); // all|blurry|over|under|duplicate|best
const [sortDir, setSortDir] = useState<"asc"|"desc">("asc");     // 顺手补上方向切换（现在缺）
```

`sortedPhotos`（[App.tsx:196-215](../src/App.tsx:196)）里串起来：星级 → 标签 → 分析 → 排序 → 方向。

**关键点：分析筛选是"有分析结果的才命中"还是"没分析的也算"？**
必须选前者并**在 UI 上显式提示**：如果用户还没点过"AI 分析"，选了"只看模糊"会得到空网格，看起来像 bug。做法：当 `flagFilter !== "all"` 且 `analysis` 为空（或覆盖率 < 100%）时，在网格顶部显示一条提示："还有 N 张未分析，[立即分析]"。这条提示直接把 Phase 4 和"分析只作用于选区"的需求串起来了。

**Chips 布局**：工具栏 [photo-toolbar.tsx:58-66](../src/components/photo-toolbar.tsx:58) 那一行已经比较满（全选/取消/已选/排序/6 个星级/列数/分析/收起）。建议把"标签 + 分析筛选"收进一个**筛选下拉面板**（`CollapsibleBar` 已有折叠能力，可直接复用其模式），而不是硬塞进这一行。

### 4.3 顺带修的两个相关小问题
- **分析只作用于选区**：工具栏按钮现在是 `photos.map(...)`（[App.tsx:506](../src/App.tsx:506)）。改成：`selectedPaths.size > 0 ? [...selectedPaths] : photos.map(...)`，并在按钮文案上体现（`AI 分析 (N)`）。这一条让"选 10 张只分析 10 张"成为可能，是 Phase 4 的主要收益之一。
- **`handlePhotoClick` 的 `lastClicked` 改 `useRef`**（[useScanner.ts:68,101](../src/useScanner.ts:68)）：这是交接文档 §6 P2-6 的老问题——`lastClicked` 在依赖数组里让所有卡片 `onToggle` 每次点击都换引用，双层 memo 失效。既然 Phase 4 要动筛选与卡片，顺手改成 `lastClickedRef`（Shift 范围选只用 `photoPaths` 和 ref，不依赖 state），**注意改完要专门验收 Shift 范围选**。

### 验收
1. `Ctrl+2` 给几张打红标 → 卡片显示色点 → 勾选"红"筛选 → 只剩红标。
2. 星级 ≥3 + 红标 叠加 → 结果是交集。
3. 未分析时选"只看模糊" → 出现"还有 N 张未分析"的提示而不是空白网格。
4. 分析完成后选"只看重复" → 只剩有 `duplicateGroup` 的；"只看最佳"只剩 `isBestInGroup`。
5. 勾选 3 张 → 点"AI 分析" → **只分析这 3 张**（看进度条张数）。
6. Shift 点击范围选仍正确；连续点击 10 张，React DevTools 里其它卡片**不重渲染**（memo 生效）。

---

## Phase 5 · 评分/色标写 XMP 边车（可选开关）

### 为什么这是最大的短板
决策现在只活在**本机 webview 的 localStorage** 里（[useScanner.ts:120-133](../src/useScanner.ts:120)），key 是绝对路径、无任何内容校验。后果：
- 换盘符 / 换机器 → 星级全丢；
- 卡格式化后新照片占用了同名路径 → **旧星级静默贴到不相干的照片上**（[App.tsx:550](../src/App.tsx:550) 直接 `ratings[photo.path]`）；
- Lightroom / digiKam / darktable **完全读不到**这些决策。

XMP 边车是行业事实标准（`xmp:Rating` / `xmp:Label`），写了之后决策跟着文件走，也能互通。

### 5.1 后端：新增 `src-tauri/src/xmp.rs`（约 120 行）

**放哪**：与照片**同目录**的 `IMG_1234.xmp`（对 RAW），或 `IMG_1234.jpg.xmp`（对 JPEG——Lightroom 对非 RAW 用"全名 + .xmp"，digiKam/ExifTool 两种都认）。**建议默认用 `stem.xmp` 并在设置里说明**，因为本项目的主体是 RAW。

**读写的最小实现**：
- 读：读文件 → 在 `<x:xmpmeta>` 里找 `xmp:Rating="N"` 与 `xmp:Label="..."`。**用一个极小的字符串扫描 + 正则即可**，不要引入 XML 解析依赖（保依赖面）。
- 写：**保留文件中已有的其它字段**，只替换/插入这两个属性（这是关键——用户可能已经在 Lightroom 里写了关键词、版权）。做法：若文件存在则读全文替换属性；不存在则从模板生成最小合法 XMP。
- 校验：与现有 `tinydng`/WIC 同样的思路——写入前限长（比如 1MB），路径走 `is_safe_relative` 同款断言思路（**边车路径必须由源路径推导，不接受前端传任意路径**）。

**两个新命令**（注册到 [lib.rs:100-121](../src-tauri/src/lib.rs:100)）：

```rust
#[tauri::command] pub async fn read_decision(file_path: String) -> Result<Option<Decision>, String>
#[tauri::command] pub async fn write_decision(file_path: String, rating: Option<u8>, label: Option<String>) -> Result<(), String>
```

- 写盘必须走 `tokio::task::spawn_blocking`（与 [importer.rs:286](../src-tauri/src/importer.rs:286) 同款），不占 tokio worker。
- 批量写（一次给 N 张）建议只提供**批量命令** `write_decisions(decisions: Vec<...>)` + `Channel` 进度，避免前端循环 N 次 IPC。这是"读完本地状态后一次性回写整卡"的常用路径。

### 5.2 前端：三档开关，默认"只写本机"

新增设置项 `imagefilter-xmp-mode`：

| 档位 | 行为 |
|---|---|
| `off`（默认） | 只写 localStorage，**完全不碰卡**（保持 v1.0.x 行为，零风险） |
| `ask` | 弹一次确认"是否把星级写入照片所在文件夹的 .xmp 文件？" |
| `on` | 每次评分/打标 → 防抖 600ms 后写边车；读取时**边车优先**，localStorage 作为缓存 |

**读取合并策略**（`loadFolder` 之后）：对当前文件夹的照片，若 `xmp-mode=on`，并发 4 个 `read_decision` 拉取一次，**边车有值则覆盖本地**，并把合并结果回写 localStorage。这必须在 **`scan_directory` 之后、渲染之前**完成，否则会看到星级"跳一下"。

### 5.3 风险与红线（不可绕过）
- **绝不删除卡上的文件**，只增改 `.xmp`。
- 写卡失败必须**可见地**报错（现在项目里 `eprintln!` 是不给用户看的，[importer.rs:348](../src-tauri/src/importer.rs:348)）——这是"卡写保护"最常见的场景，用 toast + 设置页状态提示。
- **写卡前必须确认目标可写**（只读卡/写保护），失败时自动降级为 `off` 并提示，不要每次评分都弹错。
- 与 Phase 2 的交互：撤销一个评分 → 也要回写边车（或标记为"待同步"）。建议引入一个**待同步集合**，避免每次撤销都同步写盘。
- **与导入的关系**：导入时是否一起复制 `.xmp`？→ **应该复制**（否则归档里没有决策）。这需要在 [importer.rs:277](../src-tauri/src/importer.rs:277) 附近加一步"若源目录存在同名 .xmp，一并复制并套用同一模板改名"。这是"绝不覆盖"契约的延伸，**要新增单测**（照抄 [importer.rs:462](../src-tauri/src/importer.rs:462) 的 `copy_one` 测试结构）。

### 验收
1. `off` 档：评分后文件夹里**不出现**任何新文件（这是默认，必须零副作用）。
2. `on` 档：给一张 RAW 打 4 星 → 出现 `IMG_1234.xmp`，内容含 `xmp:Rating="4"`；用文本编辑器手工改其它字段再评分 → **其它字段仍在**。
3. 删掉 localStorage 里的 `imagefilter-ratings`，重开 App、重新浏览该文件夹 → 星级从边车恢复。
4. 只读卡（或把文件设为只读）→ 评分仍生效于本地，出现一次可读的错误提示，不反复弹窗。
5. 导入带边车的照片 → 归档目录里 `.xmp` 按同一模板改名并存在，内容是改名前的那份。

---

## Phase 6 · 接上"后端已有但没界面"的三件事

这三件都是**后端已完成**，属于投入产出比最高的部分。建议拆成三个独立提交。

### 6.1 导入历史界面

**现状**：`import_history` 每次成功导入都在写（[importer.rs:338](../src-tauri/src/importer.rs:338)），`get_import_history` 已注册（[db.rs:91](../src-tauri/src/db.rs:91)）但前端零调用。

**做法**：
- `useScanner` 加 `importHistory` 状态 + `loadImportHistory(limit)`。
- UI：设置对话框里加一个"导入历史"tab/区块，列出 `文件 → 目标 / 时间 / 大小`，支持**点击跳转到目标目录**（复用 `open_folder`）。默认读 100 条，加"加载更多"。
- **顺手修 [db.rs:95](../src-tauri/src/db.rs:95)**：`limit` 默认 100 会截断大导入，建议改成 `limit: Option<u32>` 显式传 + 加一个 `count` 查询给 UI 显示总数。
- **`{seq}` 改名后原文件名丢失**的问题，正是靠这个界面补救——列表里要同时显示 `source_path` 和 `dest_path`。

### 6.2 命名模板预设（方案）管理

**现状**：`import_rules` 表 + 默认规则 `('默认','{date}','{original}')` 已建好（[db.rs:32-51](../src-tauri/src/db.rs:32)），`get_rules`/`save_rule` 已注册，但前端零调用；UI 的 `folderRule`/`fileRule` 是内存态、每次开 App 重置（[useScanner.ts:135-136](../src/useScanner.ts:135)）。

**做法**：
- 启动时 `get_rules()` 拉一次；高级选项面板（[advanced-options.tsx](../src/components/advanced-options.tsx)）里加一个下拉"方案"，选中即写入 `folderRule`/`fileRule`/`customFolder`，另有"另存为…"调 `save_rule`。
- **必须先修 [db.rs:123](../src-tauri/src/db.rs:123) 的 `save_rule` bug**：`INSERT OR REPLACE` 会分配新 rowid 并把 `is_default` 重置为 0。改为 `INSERT ... ON CONFLICT(name) DO UPDATE SET folder_template=excluded..., file_template=excluded...`。
- 顺带修 `advanced-options.tsx:39` 的写死问题：勾"按序号重命名"现在把整条规则替换成 `{seq}.{ext}`，**默认行为保持不变**（否则老用户升级后归档结构突变），但勾选框旁边加一个"保留原文件名"子选项 → `{seq}_{original}.{ext}`。

### 6.3 导入结果统计（跳过 vs 改名 vs 失败）

**现状**：`import_photos` 只返回 `u32`（复制成功数，[importer.rs:250](../src-tauri/src/importer.rs:250)），前端用 `paths.length - count` 推"失败"（[useScanner.ts:286](../src/useScanner.ts:286)）→ **"跳过"被算成失败**；`_1` 改名只藏在进度消息里；`"verifying"` 状态声明了但从未发出（[importer.rs:8](../src-tauri/src/importer.rs:8)）。

**做法**：
- 返回类型改为结构体（**契约变更**）：

```rust
#[derive(Serialize)] #[serde(rename_all = "camelCase")]
pub struct ImportSummary { pub imported: u32, pub skipped: u32, pub renamed: u32, pub failed: u32 }
```

- `ImportedFile` 增加 `renamed: bool`（[importer.rs:14-20](../src-tauri/src/importer.rs:14)），在 [importer.rs:204](../src-tauri/src/importer.rs:204) 成功生成唯一名时置位，进度里发一个新的 `"renamed"` 状态。
- 真正发出 `"verifying"`：把"复制 + 校验"拆成两条进度消息（[importer.rs:221-228](../src-tauri/src/importer.rs:221)），让"MD5 校验"这个卖点**在 UI 上可见**。
- `{seq}` 计数器改为**独立计数**：现在用 `imported + 1`（[importer.rs:278](../src-tauri/src/importer.rs:278)），跳过/失败会让编号整体前移（比"跳号"更糟：编号与拍摄顺序错位）。改为按输入顺序递增的 `seq_counter`，跳过也递增（保持"编号 = 拍摄顺序位次"）。
- 前端：`importResult` 改为 `ImportSummary`，[import-bar.tsx:93-97](../src/components/import-bar.tsx:93) 显示四行明细 + **"导出清单"**（写一个 `manifest.txt`/CSV 到目标目录，复用 `copy_one` 时代的落盘思路；这条可选）。
- **落地页 mock 同步**：`import_photos` 返回值变了，官网 demo 的 mock 层要一起改（见本文开头的共同约束 4）。

### 验收
1. 重复导入同一批 → 提示"N 张成功、M 张已存在相同（跳过）"，**不再把跳过算成失败**。
2. 目标已有同名但内容不同 → 提示里出现"改名"计数，且归档里是 `_1`。
3. 能在进度里看到"校验中"这一步。
4. 序号重命名：源目录里跳过第 2 张时，第 3 张得到 `0003`（不是 `0002`）。
5. 重启 App → 上次用的命名方案仍在（来自 `import_rules`）。
6. 历史界面能查到刚才那次导入，点目标路径能打开目录。

---

## 附 · 每个 Phase 做完都要跑的回归清单

```bash
cd A:\tenent
npx tsc --noEmit                 # 前端类型
cd src-tauri && cargo test --lib # 26 活跃必须全绿
cd src-tauri && cargo check --lib
```

手测（GUI）必查项——这些是历史上真实回归过的地方：

1. 查看器内按 `1`–`5` 后，**网格里"被选中但没在看"的那张星级不能被改动**（§7 修过的双写 bug）。
2. 开着 ≥2 星筛选，在查看器内把当前图打到 0 星 → 查看器**关闭**，不是跳到另一张（§7 重锚）。
3. 连续快速切换设备 → 任务管理器 CPU 不飙升（§8.6 代次取消）。
4. 大光圈虚化背景的照片**不出现**"模糊"红标（§8.1 分块最大）。
5. 导入一次后检查归档层级没被压平（`{date}/{camera}` 必须是两级目录，§8/D14）。
6. 浅色主题下新建的任何浮层/文字**不能出现近白字压近白底**（§D8 级联层）。
7. i18n 切到英文，**新加的功能文案不能露出中文或 key**。

## 附 · 建议的提交切分

| 提交 | 内容 |
|---|---|
| `feat(viewer): 评分后自动前进` | Phase 1 |
| `feat(undo): 评分/勾选撤销与重做` | Phase 2 |
| `feat(viewer): 1:1 实际像素查看` | Phase 3 |
| `feat(cull): 颜色标签与可叠加筛选` + `perf(grid): lastClicked 改 ref 恢复 memo` | Phase 4（可拆两个） |
| `feat(xmp): 评分/色标写入边车文件` (+ 导入时一并复制 .xmp) | Phase 5 |
| `feat(import): 导入历史界面` / `feat(import): 命名方案预设` / `fix(import): 区分跳过与失败, 修正序号计数` | Phase 6（三个独立提交） |

版本号：以上全部落地后建议作为 **v1.1.0**（用户可感知的功能新增），发布仍需同步 **4 处**版本号（`package.json`、`src-tauri/tauri.conf.json`、`src-tauri/Cargo.toml`、`src/components/settings-dialog.tsx:126`）。

---

## 附 · 会话分组（按"一个会话一个可验证交付物"划分）

| 会话 | 内容 | 为什么这样切 | 预估 token |
|---|---|---|---|
| ① | **Phase 1 + 3** | 都只动 `viewer.tsx` 的键盘分支与缩放，文件读一次办两件事，第二个功能边际成本极低 | 15–25 万 |
| ② | **Phase 2** | 动 `useScanner`/`App`/`viewer` 三处，且与筛选、重锚有交互，必须单独验证 | 20–35 万 |
| ③ | **Phase 4** | 改数据模型 + 工具栏 + 卡片，铺得最广；与 Phase 2 混做会导致**出 bug 时无法判断是哪个功能引入的** | 30–50 万 |
| ④ | **Phase 5** | 引入磁盘写入，风险面与验证方式完全独立于前端改动 | 30–50 万 |
| ⑤ | **Phase 6** | 三件事同在 `importer.rs`/`db.rs`，读一次文件办三件事，可连续三个提交 | 25–45 万 |

**判断"该合还是该拆"的两条线**：同一个文件/同一处代码的关联修改 → 合并；独立可验证、或混在一起后坏了分不清是谁弄坏的 → 拆开。

---

## 附 · 决策日志（Decision Log）

> **每个 Phase 收尾时追加 5 行**，不要写进聊天记录——聊天记录不会进下个会话。
> 其中第 3 项（**被否决的方案与原因**）最容易被忘、代价最高：不写下来，下个会话可能重新提议同一个方案，白讨论一轮。
>
> 另有两条纪律：
> - **决定也要落进代码注释**，不能只写在本文档。例：Phase 1 的"自动前进不写 `lastSwitchRef`"必须带注释说明原因，否则日后会被当成多余代码删掉，防抖就坏了（参照 `analyzer.rs:31-65` 那种把标定依据写在常量上方的做法）。
> - **只记结论不记过程**。过程在 git log 和提交信息里。

### 模板（复制这一段填）

```markdown
### 会话 N · Phase X（YYYY-MM-DD）

- **代码状态**：HEAD `<sha>` · 工作区干净 · 提交 `<sha> <message>`
- **已定决定**：(决定 + 一句话理由)
- **被否决方案**：(方案 + 否决原因)  ← 最重要，别省
- **验收结果**：通过 N 项 / 未实机验证 N 项（写出是哪几项）
- **遗留 / 本次不做**：(砍掉的东西、已知未修的问题)
```

### 会话 ⓪ · 现状走查与方案（2026-09-27，仅文档，无代码改动）

- **代码状态**：HEAD `6996b11` · 工作区干净（唯一未跟踪文件 `task-7-review-package.decoded.txt` 不属于本项目，勿提交）· 版本 `1.0.1` · `npx tsc --noEmit` exit 0
- **已定决定**：
  - 六个功能拆成 Phase 1–6，正文已含每项的文件/行号/陷阱/验收；
  - 顺序为"纯前端 → 写磁盘 → 改导入契约"，理由是把不可逆风险放后面；
  - Phase 5（XMP）**默认关闭**，三档开关，保持 v1.0.x 的零副作用行为；
  - Phase 2 撤销栈**刻意不跨重启**，切文件夹/切设备时清空（跨重启的撤销语义无法向用户解释）；
  - Phase 6 的 `{seq}` 改为**按输入顺序独立递增**，跳过也递增（保持"编号 = 拍摄顺序位次"）；
  - 落地页 demo 的 mock 层在本仓库之外，Phase 5/6 改命令签名时**必须同步**，否则官网迷你演示白屏。
- **被否决方案**：
  - 撤销用"状态快照"而非逆补丁 → 否决：几千张时每次按键都要克隆整份 `ratings`；且 `setRating` 的 updater 内写副作用在 StrictMode 下会双调用，故采用逆补丁 + 栈顶去重（详见 Phase 2）；
  - 颜色标签复用 `imagefilter-ratings` 存储 → 否决：会污染现有数据类型，改为独立 key `imagefilter-labels`；
  - XMP 边车引入 XML 解析依赖 → 否决：保依赖面，用字符串替换保留其它字段即可。
- **验收结果**：未改任何代码，无需验收；已完成事实核对（含发现交接文档"21 个 command"应为 20 个）。
- **遗留 / 本次不做**：Phase 5 是否复制 `.xmp` 进归档、Phase 4 标签快捷键是否用 `Ctrl+1`–`Ctrl+5`，均留待对应会话落地时定。

---

### 会话 ① · Phase 1 + Phase 3（2026-09-27）

- **代码状态**：起点 HEAD `30085f1` · 起点工作区干净（仅 `task-7-review-package.decoded.txt` 未跟踪，不属本项目）· 提交 `d56ae25 feat(viewer): 评分后自动前进`、`3dbe4c5 feat(viewer): 1:1 实际像素查看`、`fa0c9f2 docs: 决策日志追加会话 ①` · `npx tsc --noEmit` 三次均 exit 0 · `npx vite build` exit 0 · 期间有一条**非本会话**提交 `2cd6385 fix(dev): 加固 vite watch 忽略规则` 落在 `3dbe4c5` 与 `fa0c9f2` 之间（只改 `vite.config.ts` 与交接文档，与本会话无文件重叠）
- **已定决定**：
  - **改动了 8 个文件而非会话提示里写的"只动 viewer.tsx"**：自动前进的开关按 Phase 1 原设计落进设置对话框（`useScanner` → `App` → `TitleBar` → `SettingsDialog`，与 `preloadFull` 同路径），否则正文验收第 4 条（关开关→不前进）在 GUI 里无法手测；`help-content.tsx` 也加了 2 行，否则新增的 `help.*` key 是死 key；
  - 评分后自动前进默认**开**，key `imagefilter-auto-advance`，只有显式 `"false"` 才算关（缺省/损坏一律当开），**不用** `useLocalStorageSetting`（见被否决）；
  - 自动前进复用 `navigateTo`（与 `→` 完全同路），**不加** `markSwitch` 参数；
  - 1:1 用独立 `pixelView` 标记判定，**不用** `scale > 1`；`fitScaleOf` 用 `offsetWidth/offsetHeight`；
  - 锚点用通式 `offset' = cx − k·(cx − offset)/scale`（文档的 `(1−k)` 形式是 `scale===1` 的特例）；旋转 90/270 时退回以视图中心为锚；
  - 补做"预览图 → 全解码图换 `src` 后重锚"（文档未覆盖，见被否决里那条不做的方案）；
  - 加 `lastResetPathRef` 守卫：只在**真的换图**时重置缩放/旋转/偏移，修掉"评分把 1:1 弹回适应窗口"；
  - 新增长按守卫 `NON_REPEAT_KEYS`（`j/x/1-5/z`），**不含 `←/→`**（长按连翻是既有手感）；
  - `Z` 不抢 `Ctrl/Cmd/Alt`，给 Phase 2 的 `Ctrl+Z` 让路；
  - 文档要求的两条纪律都执行了：`lastSwitchRef` 单点写入与"自动前进不得写它"写进了 `viewer.tsx` 代码注释，不只写在本文件。
- **被否决方案**：
  - 按文档给 `navigateTo` 加第二个参数 `markSwitch = true`、`autoNext` 传 `false` → **否决**：核对代码后发现 `navigateTo`(viewer.tsx:120-133) **根本不写** `lastSwitchRef`，真正的唯一写入点是渐进加载 effect（viewer.tsx:276-280）。加这个参数是空参数，会让人误以为防抖开关在 `navigateTo` 里，日后照着它改必坏；改为用注释锁死"`lastSwitchRef` 只由该 effect 写"的不变式；
  - 文档 `fitScaleOf` 用 `scaleRef.current` + `getBoundingClientRect()` → **否决**：文件里没有 `scaleRef`；且 rect 含 transform，旋转 90/270 时宽高互换，算出的 fitScale 是错的；
  - 用 `scale > 1` 判断"是否已是 1:1" → **否决**：小图（小于窗口）的 1:1 比例 `< 1`，会陷入"再按 Z 又进 1:1"的死循环；
  - 不处理"预览图换全解码图导致 1:1 漂移" → **否决**：打开 RAW 后 600ms 内按 `Z` 必现（naturalWidth 1616 → 6000，1:1 静默变成约 1.6×），用 `img.onLoad` + 闭式重锚解决；
  - 用 `useLocalStorageSetting<boolean>` 存开关 → **否决**：该 hook `String(v)` 存、原样读回字符串，`"false"` 是真值 → 开关会永远关不掉；
  - 最后一张循环回第一张 → **否决**：culling 永不结束，改为关闭查看器回网格；
  - 自动前进加"in-viewer 开关 chip"作为"只动 viewer.tsx"的折中 → **否决**：与设置对话框里的同一开关会形成两处入口，且设置链路本身是文档既有设计。
- **验收结果**：**静态验收 4 项通过**——两个提交各自 `npx tsc --noEmit` exit 0、`npx vite build` exit 0、i18n 静态校验通过（zh/en 各 163 个叶子 key 完全对齐，源码里 163 个 `t("…")` 全部能解析到、5 个新 key 全部被引用、无死 key）。**实机 GUI 通过 7 项**（清单第 1、4、5、8、9、10、16 项，2026-09-27 用户在 `npx tauri dev` 下实测）：
  - 第 1 项：连按 `3` 五次前进 5 张，星级落在看到的那 5 张上；
  - 第 4 项：长按 `3` 一秒只前进 1 张（`NON_REPEAT_KEYS` 守卫生效）；
  - 第 5 项：设置里关掉开关后评分不再前进，重开仍为关；
  - 第 8 项：英文界面下新文案无中文残留、无裸露 key；
  - 第 9/10 项：按 `Z` 放大到 1:1，鼠标所在细节仍停在鼠标附近，再按 `Z` 回适应窗口；
  - 第 16 项：关掉自动前进后在 1:1 下打分，画面不被弹回适应窗口（`lastResetPathRef` 守卫生效）。
  **其余 13 项未实机验证**：第 2、3、6、7、11、12、13、14、15、17、18、19、20 项（末张关闭、慢/快切换手感、≥2 星筛选下打 0 星、点同一颗星取消也前进、预览 → 全解码重锚、鼠标未进图区按 `Z`、滚轮/`0` 退出 1:1、旋转下按 `Z`、小图不放大、网格双写回归、重锚关闭、浅色主题、切设备 CPU）。
- **遗留 / 本次不做**：
  - 双击图面切 1:1（文档列为可选）未做；
  - `1/fitScale` 仍夹在既有 `0.2–8` 上限内：约 >48MP 的图在窄窗口下到不了精确 1:1（差 ~2%，肉眼不可辨）；
  - 小图（`k < 1`）无法拖动平移 —— `onMouseDown` 仍按 `scale <= 1` 拦截，属既有行为，未改；
  - `useLocalStorageSetting` 对布尔值的类型陷阱是公共 hook 的问题，本次只在 `useScanner` 里绕开，未修 hook 本身；
  - 长按守卫只覆盖查看器；网格里的全局评分键（App.tsx:182-195）长按仍是幂等重写同一张，无风险故未动；
  - 查看器在处理"切图 `onLoad` 与重置 effect"时用 `lastResetPathRef === currentPathRef` 做确定性判定，理论上不再有竞态，但该判定依赖这两个 ref 的更新顺序（`navigateTo` 同步写、effect 后写），后续若改动加载流程需一并复核。

---

## 附 · 会话 ① GUI 手测清单（Phase 1 + 3）

> 已实机通过：**第 1、4、5、8、9、10、16 项**（2026-09-27）。其余 13 项仍待执行——下次碰查看器/设置相关代码前，优先补第 2、6、11、13 项（末张关闭、≥2 星筛选下打 0 星、预览→全解码重锚、滚轮/`0` 退出 1:1），这四项失败概率最高。
> 执行前：`npm run tauri dev`（或已打包的 v1.0.1 开发版），打开一个含 RAW 的文件夹。
> 每项后标注结果：✅ 通过 / ❌ 失败（附现象）/ ⏭ 跳过（附原因）。做完把结果回填进上面的决策日志"验收结果"。

**Phase 1 · 评分后自动前进**

1. 连按 `3` 五次 → 前进 5 张；对照网格，星级落在**看到的那 5 张**上（不能出现"改了另一张"，这是 §7 修过的双写回归）。
2. 逐张按 `X` 到文件夹最后一张 → 查看器自动关闭回网格；**不回卷**到第一张。
3. 慢速（>1s/张）评分：下一张高清图**立即**出现；快速连按（<500ms）：保留"旧图停留一下"的防抖手感，不闪黑。
4. **长按 `3` 一秒** → 只前进 1 张（`e.repeat` 被挡），不能批量误写星级。
5. 设置对话框关掉"评分后自动前进" → 评分不前进；重开 App 后仍是关（localStorage 生效）。
6. 开着 ≥2 星筛选，在查看器内把当前图打到 0 星 → **前进到下一张**（不是关闭、不是跳错），且下一张确实还在筛选结果里。
7. 点星条上"已经亮着的那颗星"取消评分 → 同样前进。
8. 切到英文界面重跑第 1、5 条：设置项与底部提示条文案不露中文、不露 key。

**Phase 3 · 1:1 实际像素**

9. 按 `Z` → 明显放大到 100%（能数清像素级细节）；再按 `Z` → 回适应窗口。
10. 鼠标停在画面左上某细节（如机身型号字）按 `Z` → **该细节仍在鼠标位置附近**；换到右下角再按 `Z` 同理。
11. 打开 RAW 后**立刻**按 `Z`，等 600ms 全解码落地 → 仍是 1:1（不漂成约 1.6×、不跳变）。
12. 鼠标没进过图区（键盘直接操作）按 `Z` → 以视图中心为锚，无异常偏移。
13. 1:1 下拖动平移正常、滚轮缩放正常；滚轮后再按 `Z` 是"重新进 1:1"而非"退出"；按 `0` 回适应窗口并清旋转。
14. 先按 `R` 旋转 90° 再按 `Z` → 以中心为锚、不跳飞（精确鼠标锚点在旋转下不成立，是设计取舍）。
15. 小 JPEG（小于窗口）按 `Z` → 不放大超过 1:1；再按 `Z` 正常切回。
16. 关掉自动前进后，在 1:1 下点星条打一次分 → **不弹回**适应窗口；用 `←/→` 切图则正常重置为适应窗口。

**老回归（历史真实回归过的地方）**

17. 查看器内按 `1`-`5` 后，网格里"选中但没在看"的那张星级**不变**（§7 双写 bug）。
18. 开着 ≥2 星筛选、在查看器内把当前图打到阈值以下 → 查看器关闭而不是跳到另一张（§7 重锚）。
19. 浅色主题下新建的设置行（"评分后自动前进"）不出现近白字压近白底（§D8 级联层）。
20. 连续快速切换设备 → 任务管理器 CPU 不飙升（§8.6 代次取消；本次未碰导入/扫描，抽查即可）。

---

## 附 · 会话启动提示（复制粘贴即可开新会话）

> **用法**：一次只粘一段。第一句必须要求"先只输出改动计划"，这样如果我理解偏了，你在 2000 token 内就能发现，而不是等我改完 5 个文件。

### 会话 ①（Phase 1 + 3）— 现在可用

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 1 和 Phase 3（同一会话，都只动 viewer.tsx）。

先只输出改动计划：要改哪几个文件、每处改什么、会新增哪些 i18n key，
以及 Phase 1 那个 lastSwitchRef 陷阱你打算怎么处理。我确认后再动手。

约束：不动导入逻辑、不动 Rust、不加依赖；i18n 必须 zh + en 同时加；
改完跑 npx tsc --noEmit，并给出 GUI 手测清单。
```

### 会话 ②（Phase 2）— 会话 ① 收尾后再用

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 2（撤销/重做）。

先读该文档的 Phase 2 一节和文末决策日志，再只输出改动计划：
补丁栈的数据结构、三处记录点、Ctrl+Z 在输入框内的豁免怎么处理、
以及切文件夹清空栈的落点。我确认后再动手。

约束：前端零测试的现状不变，但 Phase 2 的纯函数（补丁应用/去重）请补 vitest 或
先说明为何不补；不许改 Rust；i18n 双写。
```

### 会话 ③（Phase 4）— 会话 ② 收尾后再用

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 4（颜色标签 + 可叠加筛选）。

先读该文档的 Phase 4 一节和文末决策日志，再只输出改动计划：
标签存储 key、快捷键方案（文档推荐 Ctrl+1–Ctrl+5，可提出更好方案）、
筛选三维度在 sortedPhotos 里的组合顺序、"未分析"提示的触发条件、
以及 lastClicked 改 useRef 后如何保证 Shift 范围选不回归。我确认后再动手。

约束：不动 Rust、不加依赖；i18n 双写；必须包含"分析只作用于选区"这一项。
```

### 会话 ④（Phase 5）— 会话 ③ 收尾后再用

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 5（评分/色标写 XMP 边车）。

先读该文档的 Phase 5 一节和文末决策日志，再只输出改动计划：
xmp.rs 的读写策略（如何保留已有字段）、两个命令的签名、
三档开关的状态机、读取合并的时机、以及导入时复制 .xmp 的落点与单测。
我确认后再动手。这一 Phase 要写用户的存储卡，请把失败路径列全。

约束：默认 off（不碰卡）；不许引入 XML 解析依赖；写盘走 spawn_blocking；
新增单测照抄 importer.rs 的 copy_one 测试结构。
```

### 会话 ⑤（Phase 6）— 会话 ④ 收尾后再用

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 6（导入历史 / 命名方案预设 / 结果统计）。

先读该文档的 Phase 6 一节和文末决策日志，再只输出改动计划，
按三个独立提交组织：6.1 历史界面、6.2 方案预设（含先修 save_rule 的 is_default bug）、
6.3 ImportSummary 契约变更（含 {seq} 独立计数与 verifying 状态）。
我确认后再动手。

约束：ImportSummary 是契约变更，请明确列出前端所有受影响调用点；
提醒我同步落地页仓库的 mock 层；i18n 双写。
```

