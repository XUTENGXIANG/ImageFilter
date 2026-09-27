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
| **4** | 颜色标签 + 可叠加筛选 | `useScanner.ts`、`App.tsx`、`viewer.tsx`、`photo-card.tsx`、`photo-toolbar.tsx`、`undo.ts`、新增 `labels.ts`、`title-bar.tsx`+`settings-dialog.tsx`（修饰键设置）、`help-content.tsx`、`i18n` | 0 | 2 天 |
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
2. **评分目前是"单一事实源"**：`useScanner.setRating` 是唯一写入点（[useScanner.ts:179](../src/useScanner.ts:179)），任何新写入路径（撤销、XMP、标签）都必须汇流到这里或与它并列并被同一处序列化，否则会出现"查看器与网格不一致"这类回归（交接文档 §7 修过）。
3. **i18n 双写**：zh 与 en 各 **191** 个 key 目前完全对齐（会话 ① 163 → 会话 ② +6 → 会话 ③ +22），**新增 key 必须两边同时加**，否则会踩 fallback。另注意插值写法见第 5 条。
4. **落地页 demo 的 mock 层**（另一仓库，见 [superpowers/specs](superpowers/specs/2026-08-11-imagefilter-website-design.md)）会 mock Tauri API：**Phase 5/6 一旦改命令签名，需要同步那份 mock**，否则官网迷你演示会白屏。
5. **i18n 插值只能用单括号 `{n}`，不是 i18next 默认的双括号**（会话 ② 踩过、已修，见提交 `07b543a`）：[i18n/index.ts:26](../src/i18n/index.ts:26) 把 `prefix`/`suffix` 改成了 `{` 和 `}`，所以写成 `{{n}}` **既不报错也不翻译，而是把 `{{n}}` 原样显示出来**（会话 ② 首轮实机的 toast 就是 `已撤销：{{prev}}★ → {{next}}★`）。**动手加任何带占位符的文案前，先照 `toast.ratingChange` / `toolbar.selected` 这类既有 key 抄写法**，别照 i18next 官方文档写。
6. **任何"会改数据"的新入口都必须能被 `Ctrl+Z` 撤回**（会话 ② 建立的补丁栈纪律）。当前记录点：评分的唯一写入点 `useScanner.setRating`，勾选的三条路径 `commitSelection` / `selectAll` / `clearSelection`（选择集的唯一写入口是 `selectPaths`），纯逻辑与三条不变式在 [undo.ts](../src/undo.ts) 文件头。新增写入路径（颜色标签、日后的 XMP 回填、**任何新勾选按钮**）**要么汇流到既有记录点，要么与它们并列并走同一个补丁栈**；直接调 `setXxx` 而不 `recordPatch` = 这一步撤销不到，用户按 `Ctrl+Z` 会**跳过它去撤更早的操作**。给 `Patch` 加新 `kind` 时，`tsc` 会把 `undo()` / `redo()` / `patchToast()` / `samePatch()` / `patchPath()` 这几个派发点全报出来 —— **逐个补齐，不许用 `any` / `as` 兜底**（"漏点由编译器发现"就是这条纪律的收益）。
7. **提交卫生（会话 ② 起适用，任何会话都不许省）**：**禁止 `git add -A` / `git commit -a`**，只 `git add` 明确列出的路径；`src-tauri/Cargo.toml` 有**非本会话的行尾符噪音**（内容无实质变化），不要提交；仓库根的 `task-7-review-package.decoded.txt` **不属于本项目**，不要提交；`vite.config.ts` 可能正被**另一个并发会话**修改，不要动、也不要把它未完成的改动捎带提交。**提交前先 `git status --porcelain` 核对暂存清单里只有预期文件。**
8. **依赖面与 Rust 边界**：除 Phase 5 / Phase 6 按本文设计改 `src-tauri` 外，其余 Phase **一律不动 Rust**；**任何 Phase 都不新增第三方依赖**（Phase 5 刻意不用 XML 解析库，会话 ② 刻意不引入 vitest）。要验证纯逻辑时，用"临时脚本 + 仓库自带 esbuild 转译后跑 Node，用完即删"的办法（会话 ② 用它给 `src/undo.ts` 跑了 27 条断言）。

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

> 🛠 **会话 ② 落地时的更正（以本节为准）**：上面这段的两处前提都错了 ——
> ① `main.tsx` **有** `<React.StrictMode>`（第 8-10 行），所以"现状可接受"不成立，去重是**必须**的；
> ② 替代方案（把旧值读到 updater 外面）**已否决**，正确解法是 `useScanner` 内的 `ratingsRef` 同步镜像 ——
> 不让 `setRating` 依赖 `ratings`，既不破坏 memo，又能让"同一 tick 连按两次 Ctrl+Z"各自读到最新值。
> 实际实现见 `src/undo.ts`（`pushPatch` 栈顶去重）与 `src/useScanner.ts`（`recordPatch` / `undo` / `redo`），
> 去重只比较稳定原语且**先做无变化早退**，故只会命中 StrictMode 双调用、绝不会吞掉真实的连续操作
> （27 条断言覆盖，含"两个真实连续操作必须入栈两条"）。细节见文末会话 ② 决策日志。

> 备选实现（**不采纳**）：把 `undoStack`/`redoStack` 放 `useRef`。文档本节要求同时导出
> `canUndo`/`canRedo`，而 ref 不触发渲染 → 这两个值会永远停留在首次渲染的 `false`（谎报）。
> 会话 ② 改用 `useState<{undo, redo}>` 原子更新，`canUndo`/`canRedo` 由 `useMemo` 派生。


2. 勾选变更同理：`handlePhotoClick`（[useScanner.ts:73-101](../src/useScanner.ts:73)）、`selectAll`、`clearSelection` 三处都要记录 `prev/next` 的路径数组。
   > 补充（会话 ② 核实）：`handlePhotoClick` 内部有**三个**分支（Ctrl 切换 / Shift 范围 / 单击累积）都要记；
   > 且查看器的勾选框（viewer.tsx:436）与空格键（viewer.tsx:370）也走 `App.toggleSelect → handlePhotoClick`，
   > 即"记录点 3 处、覆盖 5 条入口"。另外 `loadFolder` 里直接 `setSelectedPaths(new Set())` 也要走同一个
   > wrapper，否则选择集镜像会过期、补丁的 `prev` 记错（会话 ② 实现为 `selectPaths` 唯一写入口）。

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

> 🛠 会话 ② 的实现与两处补充：
> - 撤销键**合并进 Ctrl+A 那个 effect**（两者是同一份输入框豁免判断，省一个 window 监听器），依赖数组必须是 `[viewerIndex, undo, redo, t]` —— 漏了 `viewerIndex` 就会出现"App 与 viewer 双重撤销，一次 Ctrl+Z 退两步"（与 [App.tsx:193](../src/App.tsx:193) 同一条坑，交接 §5-7）；
> - 只有真的撤到/重做到东西才 `preventDefault()`，空栈时把 `Ctrl+Z` 让给浏览器（否则"什么都没撤还把原生撤销吃掉"）；
> - `showToast` 的 `const` 声明必须在 effect **之前**，否则依赖数组急切求值会 TDZ 崩溃（同 `viewerIndex` 的纪律）；会话 ② 把它从 App 中段上移。

**C. `src/viewer.tsx`**：查看器的 keydown handler 里也要处理 `Ctrl+Z`（否则在查看器内撤销无效）。或者更干净：把撤销键放在**唯一的 window 级 handler** 里，并让查看器的 handler 对它早退。

> 🛠 会话 ② 选了前者（**新增 viewer 分支 + App 侧早退**），理由：把撤销收进唯一 window handler 需要把
> `photos/cur/navigateTo/autoNext` 全部提到 App 或 ref 透传，改动面远大于"两处各加 4 行 + 一处早退"，
> 且要动查看器四条加载分支的闭包链。键盘分工：**查看器打开时 App 的 handler 整体早退**（`viewerIndex !== null`），
> 查看器内由 viewer 自己的 handler 处理 —— 两套监听因此不会双触发。
> 两条行为约定：① 撤销前先把画面**调回补丁里的那张**（自动前进可能已经把人带到下一张了），用户才看得见撤了什么；
> ② 撤销路径**不调 `autoNext()`**，也不碰 `scale/offset/rotation/pixelView` —— 撤销不是"做完一次决策"。

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

- **快捷键（会话 ③ 定稿）**：`Ctrl+1`–`Ctrl+5` 打标、`Ctrl+0` 清除（正好与"`1`–`5` 是星级"形成记忆对照）；**同一组键的修饰键由用户在设置里选：Ctrl 或 Alt**（新增设置项：state 放 `useScanner`，key `imagefilter-label-modifier`，只有显式 `"alt"` 才算 Alt，缺省/损坏一律当 Ctrl；链路 `useScanner` → `App` → [title-bar.tsx](../src/components/title-bar.tsx) → [settings-dialog.tsx](../src/components/settings-dialog.tsx)，与 `autoAdvance` 同路，控件复用语言那对分段按钮）。
  - **为什么可配**：Tauri 的 WebView2 有可能把 `Ctrl+数字` / `Ctrl+0` 当成浏览器加速键吃掉（切标签页 / 重置缩放）。实机第一条就要验 `Ctrl+2` 有没有反应；没反应就在设置里切到 Alt，**不用改代码、不用重启**（`AreBrowserAcceleratorKeysEnabled` 属 Rust 红线，不碰）。
  - 键盘只做"赋值"，不做"再按一次取消"（与键盘 `1`–`5` 一致）。取消的三个入口：查看器点亮的色点再点一次、右键菜单"清除颜色标签"、`Ctrl+0` / `Alt+0`。
  - **易写错的一处**：查看器里的分支顺序 —— 新的组合键分支必须放在 plain `0` 与 `1`-`5` 分支**之前**（[viewer.tsx:389-400](../src/viewer.tsx:389)），否则 `Ctrl+2` 会先被星级分支吃掉、变成打星。
  - **打标不触发自动前进**（`autoNext()` 只跟评分走：打标是二次分拣，一前进就看不见刚打的标了）。
  - 帮助对话框那一行写 `Ctrl/Alt+1-5`（两种都列出来），避免为了显示"当前是哪个"把 `labelModifier` 一路透传到 `help-content.tsx`。
  - 纠正文中旧说法：**"`6`–`9` 被占用"不成立** —— 全仓库只有查看器用了 `0`（重置视图），6–9 无任何绑定（已 grep 确认）。
  - 已否决：`F1`–`F5`（F1 帮助 / **F5 会刷新 WebView 页面**）、`Shift+1`–`5`（`e.key` 变成 `!@#$%`，受键盘布局影响）、`Alt` 写死（就是本条要解决的问题）、只走右键菜单不加键（culling 的键盘流会断）。
- **展示**：卡片左上角徽标行末尾加一个色点（[photo-card.tsx:69-76](../src/components/photo-card.tsx:69)）；查看器顶部工具栏加一排色点按钮（[viewer.tsx:355-360](../src/viewer.tsx:355) 星条旁边）。
- **存放位置**：类型与五色常量（`Label`、`LABEL_ORDER`、`LABEL_BG` 色类）放新增的 `src/labels.ts`，`useScanner.ts` 只 `export type { Label }` 转发 —— **展示组件不许 import `useScanner`**，那会把 `@tauri-apps/api` 拖进纯展示组件。
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
必须选前者并**在 UI 上显式提示**。会话 ③ 把触发条件钉成四条，**同时成立才显示**：
1. `flagFilter !== "all"`（不看分析就不提示）；
2. `photos.length > 0`（文件夹里有照片）；
3. `!analyzing`（分析进行中 N 会乱跳，且此时工具栏按钮已经是"停止"）；
4. `pendingCount > 0`（`pendingCount` = scope 内还没分析过的张数）。

**`scope` 一份定义、三处共用**：`selectedPaths ∩ photos` 非空 → 用它；否则 → `photos` 全部。工具栏按钮的 `(N)`、提示里的 N、提示按钮要分析的对象**全部用这一份**，数字才会永远对得上；顺带挡住"`selectedPaths` 里可能留着上一个设备的残留路径"（`browseDrive` 不清选择集是既有行为，本 Phase 不改它，只在 scope 里做一次交集过滤）。

提示挂在滚动容器内、网格**上方**（非 sticky，避免遮挡第一行），按钮在 `analyzing` 时置灰。这条提示直接把 Phase 4 和"分析只作用于选区"的需求串起来了。

**Chips 布局**：工具栏 [photo-toolbar.tsx:58-66](../src/components/photo-toolbar.tsx:58) 那一行已经比较满（全选/取消/已选/排序/6 个星级/列数/分析/收起）。建议把"标签 + 分析筛选"收进一个**筛选下拉面板**（`CollapsibleBar` 已有折叠能力，可直接复用其模式），而不是硬塞进这一行。

### 4.3 顺带修的两个相关小问题
- **分析只作用于选区**：工具栏按钮现在是 `photos.map(...)`（[App.tsx:506](../src/App.tsx:506)）。改成：`selectedPaths.size > 0 ? [...selectedPaths] : photos.map(...)`，并在按钮文案上体现（`AI 分析 (N)`）。这一条让"选 10 张只分析 10 张"成为可能，是 Phase 4 的主要收益之一。⚠️ **必须同时修 4.4-B**：`runAnalysis` 现在一进门就清空全部分析结果，不动它就变成"只分析这 3 张 + 把其余几百张的结果抹掉"。
- **`handlePhotoClick` 的 `lastClicked` 改 `useRef`**（[useScanner.ts:68,101](../src/useScanner.ts:68)）：这是交接文档 §6 P2-6 的老问题——`lastClicked` 在依赖数组里让所有卡片 `onToggle` 每次点击都换引用，双层 memo 失效。既然 Phase 4 要动筛选与卡片，顺手改成 `lastClickedRef`（Shift 范围选只用 `photoPaths` 和 ref，不依赖 state），**注意改完要专门验收 Shift 范围选**。⚠️ 但**只改它并不够**：验收第 6 条还有第二个、而且更大的原因（菜单 props），见 4.4-A —— 只改一半会让这条验收**假过**。

### 4.4 落地时必须一起修的两处现状问题（会话 ③ 走查发现，已定为 Phase 4 内容）

原文没写这两条，是读代码时发现的。不修的话，4.1–4.3 的验收会**看起来通过、其实没达成**。

- **A. `menuItems` 让"点击不重渲染其它卡片"做不到（验收第 6 条假过的真正原因）**
  现状：`photoMenuItems` 依赖 `selectedPaths`（[App.tsx:288](../src/App.tsx:288)），每次勾选都生成**新数组**，而它作为 `menuItems` prop 传给**每一张**卡片（[App.tsx:592](../src/App.tsx:592)）→ 一次点击让所有卡片重渲染。所以 `lastClicked` 改 ref 只解决了两个原因里的一个。
  做法：把"每张卡片各包一个 `PixelMenu`"改成**整块网格共用一个**：
  - `PhotoGridItem` 去掉 `menuItems` prop 与 `PixelMenu` 包裹；右键仍由 `PhotoCard.onContextMenu → onCtx(photo)` 设 `ctxTarget`（时间点从 `onOpenChange` 提前到 `contextmenu`，更稳）。
  - 网格那层 `items={emptyMenuItems}`（[App.tsx:577](../src/App.tsx:577)）改成 `items={ctxTarget ? photoMenuItems : emptyMenuItems}`；空白处右键用 `(e.target).closest("[data-photo-path]") === null` 判定后清 `ctxTarget`（卡片自己的 handler 先跑、只置不清，靠 DOM 判定保证不会误清）。
  - **新鲜度不变式（要写进代码注释）**：菜单项只依赖"会触发 App 重渲染的状态"（`ctxTarget` / `selectedPaths` / `labels` / 当前照片），所以"同一个 `ctxTarget` 再右键一次"也不会显示旧菜单。
  - 静态收益：2000 张卡片从 2000 个 Radix `ContextMenu.Root` 降到 1 个。
  - 已否决备选：保留每卡片菜单、把 `menuItems` 换成"打开时求值"的稳定 getter —— 要改 [contextmenu.tsx](../src/contextmenu.tsx) 的 API 并从 `useScanner` 暴露 ref/getter，改动面更大，且一旦"求值时机"写错就会出现**菜单文案/动作用的是过期选择集**（点"导入 3 张"却导入别的集合）这种静默 bug。
- **B. `runAnalysis` 开头的 `setAnalysis({})` 会把"只分析选区"变成破坏性操作**
  现状 [useScanner.ts:447](../src/useScanner.ts:447) 一进门就清空全部分析结果：勾 3 张点 `AI 分析 (3)` → 其余几百张的徽标全部消失、"还有 N 张未分析"的 N 直接跳到总数 —— 这不是"只分析这 3 张"，是"把其余的抹了"。
  做法：改成**增量合并** —— 开始时只删掉本次要分析的那些 path 的旧结果，完成时把新结果并进旧 map；用 `analysisRef` 镜像同步（与 `ratingsRef` / `selectedPathsRef` 同款纪律，因为要读"当前"map）。
  - 已知语义（写进「遗留」）：`find_duplicates` 只在**本次传入的集合内**判重，所以选区分析时"重复"只在选区内成立、"最佳"也可能与全量结果不同。这是后端命令的既有语义，**不改 Rust**。

### 4.5 会话 ③ 的实现约定（细节，改这里就等于改实现）

- **标签清除 = 删键**（不是留 `null`、更不是留 `0`）：`applyLabelPatch` 的幂等判据用 `(current[path] ?? null) === 目标值`（把 `undefined`/`null` 归一），目标为"无标签"时解构删除。读取时做**一层合法性校验**（非五种合法值丢弃）。理由：标签稀疏，且 Phase 5 要直接拿这份 map 写 `xmp:Label`，不能带脏值/半成品键过去。**与 `ratings` 保留 `path: 0` 的语义刻意不同**（那是既有行为，本 Phase 不改）。
- **`sortDir` 的 `asc` 定义为"今天的观感"**：`name`/`type` = A→Z，`date` = **新→旧**（现行比较器 `b.modifiedAt - a.modifiedAt` 不动），`desc` = 反转。UI 只画 ↑/↓ + tip，**不写"升序/降序"**。理由：若把 asc 字面理解成"旧→新"，用户第一次切到日期排序就会觉得"排序反了"，那是自己造回归；用"相对自然序"换来零观感变化是有意的取舍（写进代码注释 + 决策日志）。
- **筛选状态不落盘、不入撤销栈**：`labelFilter` / `flagFilter` / `sortDir` 与 `starFilter` 同待遇（纯视图状态，`Ctrl+Z` 不会去动筛选）。
- **卡片色点只读**：它是展示，不新增"点了会不会勾选"的第五个交互面；鼠标入口 = 右键"颜色标签"子菜单 + 查看器色点按钮（点亮的再点一次 = 清除）。

### 验收
1. `Ctrl+2` 给几张打红标 → 卡片显示色点 → 勾选"红"筛选 → 只剩红标。
2. 星级 ≥3 + 红标 叠加 → 结果是交集。
3. 未分析时选"只看模糊" → 出现"还有 N 张未分析"的提示而不是空白网格；点提示里的按钮 → 恰好分析这 N 张。
4. 分析完成后选"只看重复" → 只剩有 `duplicateGroup` 的；"只看最佳"只剩 `isBestInGroup`。
5. 勾选 3 张 → 点"AI 分析" → **只分析这 3 张**（看进度条张数），且**其余照片原有的分析徽标不消失**（4.4-B）。
6. Shift 点击范围选仍正确（四种情形：前向范围 / 后向范围 / 先 Shift 再单击再 Shift / `Ctrl+点击` 之后 Shift）；连续点击 10 张，React DevTools 里其它卡片**不重渲染** —— ⚠️ 这一条同时依赖 `lastClickedRef`（4.3）与菜单单例化（4.4-A），**只做一半必定假过**。
7. 设置里把修饰键从 Ctrl 切成 Alt → `Alt+2` 能打标、`Ctrl+2` 不再打标（也不产生副作用）；重开 App 后设置保持。
8. 打标**不触发自动前进**；在查看器里打标后 `Ctrl+Z` → 画面跳回被撤的那张、色点回退、1:1/旋转不被重置。
9. 撤销栈里能同时看到"打标 / 打星 / 勾选"三种补丁：连做三步再连按 `Ctrl+Z`，按 LIFO 依次回退。

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

### 会话 ② · Phase 2（2026-09-27）

- **代码状态**：起点 HEAD `4622622` · 起点工作区干净（仅 `task-7-review-package.decoded.txt` 未跟踪，不属本项目；`src-tauri/Cargo.toml` 有非本会话的**行尾符噪音**，已刻意不 `add`）· 提交 `5e4e865 feat(undo): 评分/勾选撤销与重做(Ctrl+Z / Ctrl+Shift+Z)` · 本会话 HEAD `dbaeb11`（仅文档）· `npx tsc --noEmit` 两次均 exit 0 · `npx vite build` exit 0 · i18n 静态校验 exit 0（zh/en 各 **169** 个叶子 key 完全对齐、源码 169 个 `t("…")` 全部可解析、无死 key）· `src/undo.ts` 纯函数冒烟 27 条断言全绿（esbuild 转译后跑 Node，临时脚本用完即删）
- **已定决定**：
  - **补丁栈放 `useState<{undo, redo}>`（原子更新）而不是文档建议的 `useRef`**：文档同时要求导出 `canUndo`/`canRedo`，而 ref 不触发渲染会让这两个值永远停在首次渲染的 `false`（谎报）；`canUndo`/`canRedo` 由 `useMemo` 派生，三者同一次渲染内一致；
  - **新增 `src/undo.ts`** 承载全部纯逻辑（`Patch` 类型、`pushPatch` 栈顶去重、`popUndo`/`popRedo`、`applyRatingPatch`/`applySelectionPatch`）；每处决策写进代码注释（决策日志纪律 1），文件头写死三条不变式；
  - **StrictMode 去重是真的必需**：核对 `main.tsx` 第 8-10 行**确有** `<React.StrictMode>`，文档原话"未使用 StrictMode 所以现状可接受"已失效（正文已更正）；去重判据 = **调用方无变化早退**（评分 `before === stars`）+ `pushPatch` 只比较稳定原语，故只可能命中双调用，**不会吞掉真实连续操作**（断言 2a-2c 专门钉死这条失败方向）；
  - **用 `ratingsRef` / `selectedPathsRef` 同步镜像**：`undo()` 在同一 tick 内被连按两次时，读闭包 state 会两次拿到同一份旧值、静默丢掉第二条补丁；镜像在 updater 内赋值（与既有 `localStorage.setItem` 同款写法）。**顺带解掉文档的两难**：`setRating` 不必依赖 `ratings`，卡片 `onRate` 引用稳定、双层 memo 不失效；
  - **`selectPaths` 成为选择集的唯一写入口**（含 `loadFolder` 里那次清空）：直接调 `setSelectedPaths` 会让镜像过期、补丁的 `prev` 记错 —— 这是自查时发现并修掉的真实缺陷，不是预防性改动；
  - **撤销键合并进 Ctrl+A 那个 effect**（同一份输入框豁免判断，少一个 window 监听器），依赖数组含 `viewerIndex`（防与 viewer 双重撤销）、`showToast` 从 App 中段**上移**到该 effect 之前（依赖数组急切求值会 TDZ，同 `viewerIndex` 的纪律）；
  - **空栈时不 `preventDefault()`**：把 `Ctrl+Z` 让给浏览器，避免"什么都没撤还把原生撤销吃掉"；
  - **查看器内撤销前先把画面调回补丁里的那张**（`undoTargetPath` 由 `lastUndoPath` 传入）：自动前进可能已把人带到下一张，不回跳用户就看不见撤了什么；**撤销路径不调 `autoNext()`**（撤销="我改主意"，不该立刻前进）、**不碰 `scale/offset/rotation/pixelView`**（撤销不改路径就不该动画面状态，`lastResetPathRef` 因此不会触发）；
  - **`Ctrl+Z` 的重复键只让 App 侧处理**：viewer 的 `e.repeat` 守卫排在撤销分支之前（`z` 本就在 `NON_REPEAT_KEYS` 里），所以查看器内长按 Ctrl+Z 不连撤 —— 有意为之，本 Phase 不做节流；
  - **不补 vitest，改做一次可复现的 Node 冒烟**：`src/undo.ts` 零 React 依赖，用仓库自带的 esbuild 转译后断言 27 条（StrictMode 双调用只入栈一条 / 真实连续操作必须入栈 / 栈深上限 / redo 清空与往返一致 / 应用函数幂等）。这既不动 `vite.config.ts`（另有会话可能正在改），也把交接 §6 P1-2"先测纯函数"的前置条件做实 —— 日后引入 vitest 是零改造成本；
  - **i18n 净增 6 个 key（163 → 169）**：`help.undo`(=`Ctrl+Z`，兼作 kbd 文案)、`help.undoDesc`、`toast.undo`、`toast.undoRedo`、`toast.ratingChange`、`toast.selectionChange`；**帮助对话框与欢迎页两处都加了**（否则新 key 是死 key，会话 ① 的同一条纪律）；
  - **toast 文案在 App 里生成**（`patchToast(t, patch, kind)`），不在 `useScanner` 里预格式化 —— hook 不该依赖 i18n，且文案要随语言切换即时变化；
  - **插值占位符必须用单括号**（`{prev}`，不是 i18next 默认的 `{{prev}}`）：本仓库 `i18n/index.ts:26` 把 `prefix/suffix` 改成了 `{`/`}`，写成双括号会被解析成不存在的变量 `"{prev"` 并**原样显示**（首轮实机就是"已撤销：{{prev}}★ → {{next}}★"，见提交 `07b543a`）。**新增任何带占位符的文案前，先对照既有 key 的写法**，别照 i18next 官方文档写。
- **被否决方案**：
  - 栈用 `useRef`（文档建议）→ 否决：与 `canUndo`/`canRedo` 自相矛盾（ref 不触发渲染 → 永远 `false`）；
  - 把 `ratings[path]` 挪到 updater 外读旧值（文档建议的"更稳写法"）→ 否决：`setRating` 依赖 `ratings` 会让所有卡片 `onRate` 换引用、双层 memo 失效（交接 §6 P2-6 的老问题）；正确解法是 `ratingsRef`；
  - 把撤销收进**唯一**的 window handler、让 viewer 早退（文档给的"更干净"方案）→ 否决：要把 `photos/cur/navigateTo/autoNext` 提到 App 或 ref 透传，改动面远大于"两处各 4 行 + App 一处早退"，且会碰到四条加载分支的闭包链；
  - 撤销后调用 `autoNext()` → 否决：被撤的那张会立刻滑走，用户无法复核；
  - 用 `Set` 快照比较选择集是否变化（深比 `paths`）来判断去重 → 否决：改为引用比较（选择集每次变更都新建数组，引用即版本），并在文件头写死"数组是冻结快照"的不变式；
  - `applyRatingPatch` 只在"当前值等于补丁的 `next`"时才生效（更严格的守卫）→ 否决：那会让同一补丁重复回放时仍产生新对象（不幂等）；改为"只看目标值"，既幂等又容忍"当前值不在 `{prev,next}` 里"的意外状态；
  - 引入 vitest（用户原始提示里的备选）→ 否决：需加依赖 + 改 `vite.config.ts`，而该文件正可能有并发改动，冲突风险不该由本 Phase 承担；
  - `Ctrl+Y` 作为重做别名 → 否决：文档只要求 `Ctrl+Shift+Z`，多一个键多一份误触面；
  - 查看器底部提示条再加一条 `Ctrl+Z` 文案 → 否决：提示条已有 7 条偏满，撤销属通用快捷键，写进帮助即可（也省一个 key）；
  - 长按 Ctrl+Z 连撤（把 `z` 移出 `NON_REPEAT_KEYS` 或加节流）→ 否决：手抖按住会一口气退掉几十步且不可预期，本 Phase 先不做。
- **验收结果**：**静态验收 6 项全通过**：① `npx tsc --noEmit` exit 0（改前/改后各一次）；② `npx vite build` exit 0；③ i18n 叶子 key zh/en 各 169 且零差异；④ 源码 169 个 `t("…")` 全部能解析到、无死 key；⑤ `src/undo.ts` 冒烟 27/27（含"两个真实连续操作必须入栈两条"这一最危险失败方向）；⑥ 每个 `setSelectedPaths` 调用点都经过 `selectPaths`（用 grep 逐点核对）。**实机 GUI 已开始，首测即发现并修掉 1 个真 bug**：撤销 toast 显示成 `{{prev}}★ → {{next}}★` —— 根因是插值写成了 i18next 默认的双括号，与本仓库 `prefix/suffix` 单括号配置冲突（提交 `07b543a` 修复；已用仓库真实 i18next 复现+验证，并扫过 zh/en 全部 13 个带占位符的 key）。**除该条外，清单第 1–22 项尚未逐项实机验证**（本会话未跑完整 GUI 回归），首次实机时优先做第 1、2、5、9、12 项（评分撤销、连撤 LIFO、输入框豁免、自动前进下撤销不回跳错图/不前进、1:1+旋转下撤销不重置）。
  另有一次**探针结论记录**：想用 `renderToString` 实证 "StrictMode 双调用 updater"，结果渲染期更新只调用 1 次（该路径不双调用），故未能实证 —— 去重按 React 官方文档的结论保留（它无论双调用与否都正确，且不双调用时也不会有副作用）。
- **遗留 / 本次不做**：
  - `canUndo`/`canRedo` 已导出但**当前 UI 不消费**（帮助只写静态文案）；若要加"撤销"按钮/置灰态，直接用这两个布尔值；
  - 网格里的 App Ctrl+Z handler **没有** `e.repeat` 守卫（查看器内有）：长按网格 Ctrl+Z 会连撤到栈空。风险低（撤销是幂等回退、不是静默写盘），但这是个已知的不一致；
  - 跨文件夹/跨设备撤销按文档刻意不支持：`loadFolder`/`browseDrive` 同步清空两个栈，切回原文件夹也撤不了（"刷新设备"按钮同样会清空）；
  - 撤销时的 localStorage 写入仍是 `try/catch` 静默失败（与既有 `setRating` 一致），配额/隐私模式失败时用户看不到；
  - `undoTargetPath` 的"回跳"依赖目标仍在 `photos`（未筛选全集）里：自动化流程下若目标已被移出列表，就不回跳、只回放补丁；
  - `undo`/`redo` 的副作用在 `setHistory` 的 updater 内（StrictMode 会跑两次）：已做到幂等（应用函数返回原引用即 bail out），但这是"值得在改动它时重新推一遍"的写法，未改造成外部读栈；
  - toast 停留仍是既有 1200ms，`已撤销：4★ → 3★` 这类长文案会顶到时限；本次不调（避免影响既有导入/弹出提示的手感）；
  - 会话 ① 手测清单里未验证的第 2、6、11、13 项本次也未补测（本次未碰加载/重锚逻辑，但 Phase 2 改了 viewer 的 keydown 依赖，理论无关）。

---

### 会话 ③ · Phase 4（2026-09-27）

- **代码状态**：起点 HEAD `344cdb2` · 起点工作区干净（仅 `task-7-review-package.decoded.txt` 未跟踪，不属本项目；`src-tauri/Cargo.toml` 有非本会话的**行尾符噪音**，刻意不 add）· 提交 `7e9a959 feat(label): 颜色标签与星级/标签/分析三维叠加筛选`（13 文件 +647/−61）、`docs: 会话 ③ 手测清单 + 决策日志` · `npx tsc --noEmit` 改前/改后各一次均 exit 0 · `npx vite build` exit 0 · i18n 静态校验 exit 0（zh/en 各 **191** 个叶子 key 完全对齐；源码 186 个字面 `t("…")` 全部可解析，其余 5 个由 `label.` 动态前缀覆盖；无死 key、无双括号占位符）· `src/undo.ts` 纯函数冒烟 **27/27**
- **已定决定**：
  - 标签存独立 key `imagefilter-labels`，**清除 = 删键**（与 ratings 保留 `path: 0` 刻意不同），读取时丢非法值 —— Phase 5 要拿这份 map 直写 `xmp:Label`，不能带脏值/半成品键；
  - 新增 `src/labels.ts` 承载 `Label` / `LABEL_ORDER` / `LABEL_BG` / `readLabels` / `isLabelChord`：**展示组件不许 import `useScanner`**（那会把 `@tauri-apps/api` 拖进纯展示组件），类型由 `useScanner` `export type` 转发；
  - 标签写入点唯一 = `useScanner.setLabel`（无变化早退 → `recordPatch` → `labelsRef` 镜像 → 落盘）；补丁加第三种 kind `label`，`patchPath` 对 label 也返回路径（否则查看器里撤销标签不会跳回那张）。**tsc 的判别联合把 undo / redo / patchToast / samePatch / patchPath 五个派发点全报了出来** —— 一个没漏，也没用 `any` 兜底（docs 前置约束 6 的收益）；
  - 快捷键 `Ctrl+1`–`Ctrl+5` 打标、`Ctrl+0` 清除，**修饰键可配**（设置里 Ctrl / Alt；key `imagefilter-label-modifier`，只有显式 `"alt"` 才算 Alt，缺省/损坏当 Ctrl）。判定收在 `isLabelChord` 一个纯函数里，App 与 viewer 共用；
  - 查看器里标签分支**排在 plain `0` / `1-5` 之前**（否则 `Ctrl+2` 会先被星级分支吃掉、变成打星）—— 本项最容易写错的地方，代码里留了 ⚠️ 注释；
  - 打标**不触发自动前进**（标签是二次分拣，一前进就看不见刚打的标）；键盘只赋值、**按钮**才 toggle（与星条按钮一致）；
  - 卡片色点**只读**（不新增第五个"点了会不会勾选"的交互面）；鼠标入口 = 右键"颜色标签"子菜单 + 查看器色点按钮；
  - 三维筛选在 `sortedPhotos` 里的顺序 = **星级 → 标签 → 分析 → 排序 → 方向**：三个维度都是 AND，顺序只按短路成本排（数值比较 → `Set.has` → 取对象+多字段）；排序必须在所有筛选之后；方向放最后，避免"3 键 × 2 方向 = 6 个比较器"；
  - `sortDir` 的 `asc` **定义为"今天的观感"**（date = 新→旧），`desc` = 反转比较器；UI 只画 ↑/↓ 图标，不写"升序/降序" —— 否则第一次切到日期排序就会觉得"排序反了"，那是自己造回归；
  - **4.4-A 菜单单例化**：整块网格共用 1 个 `PixelMenu`（原来每张卡片 1 个，2000 张就是 2000 个 Radix Root），右键由 `PhotoCard.onContextMenu → onCtx(photo)` 记目标、空白处用 `closest("[data-photo-path]")` 清目标。这是验收第 6 条（点击不重渲染其它卡片）真正成立的前提 —— **只改 `lastClickedRef` 会假过**；
  - **4.4-B 分析增量合并**：`runAnalysis` 不再 `setAnalysis({})`，改成"只丢掉本次要分析的那些 path 的旧结果、其余原样保留"，用 `analysisRef` 读当前 map。否则"勾 3 张只分析这 3 张"实际会把其余几百张的结果抹掉；
  - **分析范围 scope 一份定义、三处共用**（`selectedPaths ∩ photos` 非空则用它，否则整个文件夹）：工具栏按钮文案、提示里的 N、提示按钮分析的对象，数字永远对得上；顺带挡住"选择集里还留着上一台设备的残留路径"；
  - "未分析"提示四个条件（只看分析结果 + 有照片 + 不在分析中 + scope 内有未分析）；与"0 命中"提示**互斥** —— 后者已经解释了为什么是空的，两条横幅一起弹只是噪音；
  - `lastClicked` → `lastClickedRef`（依赖数组缩到 `[photos, commitSelection]`，卡片 `onToggle` 引用稳定）；并在 `loadFolder` / `browseDrive` **清空锚点**：改之前跨文件夹的 Shift 点击是"没反应的死点击"，属于顺手修掉的既有瑕疵；
  - 纯逻辑验证沿用会话 ② 的办法（临时脚本 + 仓库自带 esbuild 转译 + Node 断言，用完即删），**不引入 vitest**（避免动 `vite.config.ts`，那个文件可能有并发会话在改）；
  - i18n 净增 22 个 key（169 → 191）；`toolbar.aiSelected` 挂在按钮的 `title` 上而不是 `Tip` —— 工具栏那一层有 `overflow-hidden`，Tip 的绝对定位气泡会被裁掉。
- **被否决方案**：
  - 保留"每卡片一个右键菜单"、把 `menuItems` 换成"打开时求值"的稳定 getter → **否决**：要改 `contextmenu.tsx` 的 API 并从 `useScanner` 暴露 ref/getter，而且一旦求值时机写错就会出现"点导入 3 张却导入了别的集合"这种静默 bug（详见 4.4-A 的备选）；
  - 标签沿用评分那套"保留 `path: 0`/`null` 键" → **否决**：Phase 5 会把这份 map 直写 XMP，半成品键就是脏数据；删除键还让 `JSON.stringify` 的体积只跟"打标数量"走；
  - `F1`–`F5` / `Shift+1`–`5` / 只走右键菜单不加快捷键 → **否决**：理由见 4.1（F5 会刷新 WebView 页面、`Shift+数字` 的 `e.key` 受键盘布局影响、culling 的键盘流会断）；
  - 键盘"再按同一键取消"（toggle）→ **否决**：既有键盘 `1-5` 是幂等赋值，保持一致；取消交给按钮 toggle 与 `+0`；
  - 卡片色点做成按钮（点一下换下一个颜色）→ **否决**：新增一个"点了会不会勾选"的交互面，Phase 4 不值得为它扩大回归面；
  - "0 命中"和"还有 N 张未分析"两条提示同时弹 → **否决**：信息重复且让网格顶部变吵；改为互斥（未分析提示优先，它解释了原因）；
  - 收尾用 `git add -A` → **否决**：会把 `Cargo.toml` 行尾噪音、非本项目的 `task-7-review-package.decoded.txt`、以及可能被并发会话改动的 `vite.config.ts` 全捎带进去（docs 前置约束 7）。
- **验收结果**：**静态验收 6 项全通过**：① `npx tsc --noEmit` 改前/改后均 exit 0；② `npx vite build` exit 0；③ i18n 叶子 key zh/en 各 191、零差异；④ 186 个字面 `t("…")` 全部可解析 + 5 个 `label.*` 由动态前缀覆盖、无死 key、带占位符的 key 无一双括号；⑤ `src/undo.ts` 冒烟 27/27（含三条最危险的失败方向："两次真实连续打标必须入栈两条""清除 = 真删键""键不存在时清标签仍幂等"）；⑥ 逐点 grep 核对：`setLabels` / `imagefilter-labels` 只出现在 `labels.ts` 与 `useScanner`，`setSelectedPaths` 只出现在 `selectPaths` 内，卡片上不再有 `menuItems` prop，工具栏分析按钮已改用 `analysisScope`。
  **实机 GUI 0 项**：本会话没跑 `npx tauri dev`，下面清单 **24 项全部未实机验证**。首次实机优先做 **第 1、7、10、11、20 项**（Ctrl+数字是否被 WebView2 吞掉、未分析提示是否出现、选区分析是否真的只打 3 张、增量合并是否保住旧结果、点击是否真的只重渲染两张卡片），这五项失败概率最高。
- **遗留 / 本次不做**：
  - `find_duplicates` 只在**本次传入的集合内**判重：选区分析时"重复/最佳"只在选区内成立（后端既有语义，不改 Rust）；
  - Shift 范围选的基准仍是 `photos`（扫描顺序）而不是 `sortedPhotos`（可见顺序）：开着筛选或按日期排序时范围"看起来不对"是**既有行为**，本次只在代码注释与清单里写明，没改（要改得把可见顺序从 App 透传进 hook，属另一次重构）；
  - 筛选状态（星级/标签/分析/方向）**不落盘**，重开 App 回到默认（与既有 `starFilter` 同待遇）；
  - 网格侧只有右键子菜单能清除标签：若 `Ctrl+0`/`Alt+0` 被 WebView2 吃掉，键盘路径就没了（查看器内还有色点按钮可点）；
  - `browseDrive` 仍不清选择集（既有行为），只在分析 scope 里做了一次交集防御；
  - 直接打标**不弹 toast**（与打分一致，靠色点反馈）；toast 只在撤销/重做时出现；
  - 会话 ①/② 未实机验证的旧项本次也没补测（本次未碰查看器的四条加载分支，但改了 viewer 的 keydown 依赖，理论无关）。

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

## 附 · 会话 ② GUI 手测清单（Phase 2 · 撤销/重做）

> 状态：**已开始实机验证**（2026-09-27 用户在 `npx tauri dev` 下实测）。首测暴露出 1 个显示 bug 并已修复：
> toast 显示成 `已撤销：{{prev}}★ → {{next}}★` —— 插值写成了 i18next 默认双括号，与本仓库 `prefix/suffix` 单括号配置冲突。
> **第 8 项（toast 文案）当时是 ❌，修复（提交 `07b543a`）后待复测**；其余 21 项仍待逐项执行。
> 首次实机请优先做 **1、2、5、9、12** 项（评分撤销落地、连撤 LIFO、输入框豁免、自动前进下的撤销语义、1:1+旋转下撤销不重置画面），这五项失败概率最高。
> 执行前：`npm run tauri dev`，打开一个含 RAW 的文件夹；先按 `R` 确认旋转、按 `Z` 确认 1:1 都能正常工作。
> 每项后标注结果：✅ 通过 / ❌ 失败（附现象）/ ⏭ 跳过（附原因）。做完把结果回填进上面的决策日志"验收结果"，并同步本文档开头那段状态。

**Phase 2 基本功能**

1. 给 A 打 4 星 → `Ctrl+Z` → 网格徽标回到上一状态；**关掉 App 重开或看 DevTools 里的 `localStorage["imagefilter-ratings"]`**，值也同步回退（不能只退回 UI）。
2. 连打 5 张分（3/4/5/2/1）→ 连按 `Ctrl+Z` 5 次 → 5 张按**后进先出**依次回退；第 6 次按 `Ctrl+Z` 无事发生、无报错。
3. 撤销 2 次 → `Ctrl+Shift+Z` 重做 1 次 → 状态与"只撤销 1 次"相同；再打一次新分 → 重做栈清空（`Ctrl+Shift+Z` 无事发生）。
4. 勾选 3 张 → `Ctrl+Z` → 3 张全部取消勾选；`Ctrl+Shift+Z` → 3 张重新勾上。
5. **在目标目录/命名模板输入框里打字后按 `Ctrl+Z`** → 撤销的是**输入文字**，星级/勾选不变（输入框豁免）。
6. 点工具栏"全选" → `Ctrl+Z` → 回到全选前的选择集；"取消" → `Ctrl+Z` → 选择集恢复。
7. 在**查看器内**按空格勾选当前图 → `Ctrl+Z` → 勾选回退（这条走的是 viewer → `toggleSelect` → `handlePhotoClick` 路径，与网格不同入口）。
8. 在**查看器内**打分 → `Ctrl+Z` → 星级回退，且 toast 出现在查看器之上（`z-[200]` > 查看器 `z-50`）；`Ctrl+Shift+Z` 重做同理。
9. 切到别的文件夹后再按 `Ctrl+Z` → **无事发生**（不报错、不改任何文件夹的数据）；点设备面板"刷新"后再按 `Ctrl+Z` 同样无事发生（切设备也会清栈）。

**Phase 1 / 3 与撤销的交互（本次改动的硬指标）**

10. **开着自动前进**：查看器内连打 4 张分（每打一张自动前进）→ 连按 `Ctrl+Z` → 画面**逐张回跳**到被撤的那张，星级随之回退；**按 `Ctrl+Z` 时不触发自动前进**（不能出现"撤一下又滑走一张"）。
11. 开着**自动前进**、开着 **≥2 星筛选**：查看器内把当前图从 4 星打到 0 星（它会掉出筛选）→ 再按 `Ctrl+Z` → 星级回退，且**不跳到别的照片**；若该图确实回到筛选结果里，查看器应停在它上面。
12. **关掉自动前进**：先按 `Z` 进 1:1、按 `R` 旋转 90°、滚轮再放大一档 → 点星条打一次分 → 按 `Ctrl+Z` → **缩放/旋转/偏移全部保持不变**（`lastResetPathRef` 守卫不被撤销触发），画面**不前进**。
13. 在查看器内按 `←`/`→` 切图后按 `Ctrl+Z` → 撤销的是**刚才打过的那张**（回跳生效），不是当前这张。
14. 撤销/重做后查看器的进度文本（`cur + 1 / photos.length`）与画面一致；快速连按 `Ctrl+Z` 不闪黑帧、不出现空图（四条加载分支未被破坏）。

**老回归（历史真实回归过的地方）**

15. 查看器内按 `1`-`5` 后，网格里"选中但没在看"的那张星级**不变**（§7 双写 bug）。
16. `Ctrl+Z` 撤销评分后，查看器与网格显示的星级**一致**（不能只有一边回退）。
17. 浅色主题下撤销 toast 不出现近白字压近白底（§D8 级联层）。
18. 英文界面（设置里切语言）下：帮助对话框显示 `Ctrl+Z` / "Undo rating / selection…"、toast 显示 "Undid: …"，**无中文残留、无裸露 key**。
19. 网格里 `Ctrl+A` 仍被拦（输入框外不全选文本），且与 `Ctrl+Z` 互不干扰。
20. 长按 `Ctrl+Z` 一秒 → 最多退到栈空，不报错、不卡死（注意：网格侧无 `e.repeat` 守卫，会连撤到空 —— 这是已知行为，见决策日志遗留项；查看器侧只撤一次）。
21. Shift 范围选仍然正确（`handlePhotoClick` 的三个分支都改成走 `selectPaths`，需确认范围选后 `Ctrl+Z` 能整段回退）。
22. 连续快速切换设备 → 任务管理器 CPU 不飙升（§8.6 代次取消；本次未碰导入/扫描，抽查即可）。

---

## 附 · 会话 ③ GUI 手测清单（Phase 4 · 颜色标签 + 可叠加筛选）

> 状态：**全部未实机验证**（2026-09-27）。本会话只做了静态验收（`tsc` / `vite build` / i18n 静态校验 / `undo.ts` 冒烟 27 条），**没跑 `npx tauri dev`、没点过界面**。
> 首次实机请优先做 **第 1、7、10、11、20 项** —— 这五项失败概率最高（Ctrl+数字是否被 WebView2 吞掉、未分析提示是否出现、选区分析是否真的只打 3 张、增量合并是否保住旧结果、memo 是否真生效）。
> 执行前：`npm run tauri dev`，打开一个含 RAW 的文件夹；先按 `Z` / `R` / `1`-`5` 确认查看器老功能正常。
> 每项后标注结果：✅ 通过 / ❌ 失败（附现象）/ ⏭ 跳过（附原因）。做完把结果回填进上面的决策日志"验收结果"，并同步本文档开头那段状态。

**Phase 4 · 颜色标签**

1. `Ctrl+2` → 网格里"单击选中的那张"出现红点；`Ctrl+0` → 消失。**若按了完全没反应**，说明 Ctrl+数字被 WebView2 当成浏览器加速键吃了 → 去设置把修饰键切成 Alt，再验 `Alt+2` / `Alt+0`（不用重启、不用改代码）。这条要**第一个做**，它决定后面用哪个键位。
2. 查看器内 `Ctrl+3` → 顶部色点行第 3 个点亮；**点同一个色点** → 取消；`Ctrl+5` → 改紫。打标**不前进**（画面停在原图）。
3. 星条旁的色点与网格卡片上的色点**同时**更新；`←/→` 换图后色点跟着换到新图。
4. 右键卡片 → "颜色标签"子菜单 → 选"黄" → 卡片出黄点；再右键 → "清除颜色标签" → 消失。
5. 连续给 3 张打不同颜色 → 连按 `Ctrl+Z` → **按 LIFO 依次回退**（第 4 次无反应、无报错）；`Ctrl+Shift+Z` → 颜色依次回来。
6. **DevTools 里看 `localStorage["imagefilter-labels"]`**：打标后出现该路径；`Ctrl+Z` 或"清除颜色标签"之后**这个键真的没了**（不是留 `null`）；重开 App 后标签还在，且切文件夹不会清空它。

**Phase 4 · 可叠加筛选**

7. 展开工具栏"筛选" → 勾"红" → 只剩红标；再勾"蓝" → 红**或**蓝（并集）；点"全部" → 恢复。
8. 星级 ≥3 + 红标 → 结果是**交集**（两个条件都满足才显示）。
9. 分析筛选口径（全部已分析后）：选"只看重复" → 只剩有 `duplicateGroup` 的（**含卡片上显示"最佳"的那张**）；"只看最佳" → 只剩 `isBestInGroup`。
10. 还没分析时选"只看模糊" → 网格上方出现"还有 N 张未分析 [分析这 N 张]"（**不是纯空白网格**）；点按钮 → 恰好分析这 N 张、提示消失。选"全部"（不筛分析结果）时这条提示**不出现**。
11. 三维叠加到 0 命中 → 显示"没有照片符合当前筛选" + "清除筛选"；点它 → 三维复位、照片全回来，**排序方式与方向不变**。
12. 排序方向：点 ↑/↓ → 顺序反转；默认（文件名 + ↑）与改动前一致；**切到"日期"时默认仍是"新的在前"**（不能出现"日期排序反了"）。

**"分析只作用于选区"（本 Phase 硬指标）**

13. 勾选 3 张 → 工具栏按钮显示 `AI 分析 (3)`（悬停有"只分析选中的 3 张"）→ 点击 → **只有这 3 张**被分析；点"取消"清空勾选 → 按钮回到 `AI 分析`。
14. 紧接着上一条：**其它照片原有的分析徽标没有消失**（4.4-B 增量合并生效），"还有 N 张未分析"的 N 不会突然跳到总数。
15. 选区里 1 张已分析、2 张未分析 → 提示里的 N 与"分析这 N 张"要分析的张数**一致**（scope 一份定义）。
16. 切文件夹 / 切设备后（选择集被清）→ 按钮回到全量分析，且不会把上一个文件夹的残留路径带进来。

**与撤销 / 自动前进的交互**

17. 在查看器里打标 → `Ctrl+Z`：画面**跳回被撤的那张**、色点回退、**不自动前进**、1:1/旋转/偏移不被重置。
18. 开着自动前进：查看器内连打 4 张分（每张都前进）→ 连按 `Ctrl+Z` → 逐张回跳；**撤销本身不触发前进**。
19. 连做"打标 → 打星 → 勾选"三步 → 连按 3 次 `Ctrl+Z` → 三种补丁按 LIFO 依次回退（勾选 → 星级 → 标签），且撤销**不动**筛选 chips 的状态。
20. **React DevTools 开 "Highlight updates"**：连点 10 张不同卡片 → 只有被点的那张（与上一次选中的那张）闪；若整屏都闪 → 4.4-A 的菜单单例化没生效（或 `lastClickedRef` 又被放回了依赖数组）。
21. Shift 范围选四条：点第 3 张 → Shift+点第 7 张（前向）→ Shift+点第 2 张（回缩/反向）→ 单击第 5 张 → Shift+点第 8 张（锚点跟着单击走）；`Ctrl+点击` 之后再 Shift 应以 Ctrl 点的那张为起点。切文件夹后 Shift 点击应退化成普通点击（既不"没反应"，也不产生范围补丁）。

**老回归 / i18n**

22. 查看器内按 `1`-`5` 后，网格里"选中但没在看"的那张星级**不变**（§7 双写）；`Ctrl+1`-`5` 打标同理只改当前这张。
23. 开着红标筛选，在查看器里把当前图改成别的颜色（或清除）→ 它掉出筛选 → 查看器**关闭**回网格（与星级筛选同语义），不跳到别的照片。
24. 英文界面（设置里切语言）：筛选面板 / 提示条 / 右键菜单 / 撤销 toast **无中文残留、无裸露 key**；重点看带插值的"还有 N 张未分析"、`AI 分析 (N)`、撤销标签时的 `红 → 无标签`（必须单括号渲染，不能出现 `{{n}}`）。浅色主题下色点与"未分析"提示条不出现近白压近白；筛选行展开时工具栏不跳高。

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

### 会话 ②（Phase 2）— ✅ 会话 ② 已完成（见决策日志），下一会话请用会话 ③

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 2（撤销/重做）。

先读该文档的 Phase 2 一节和文末决策日志，再只输出改动计划：
补丁栈的数据结构、三处记录点、Ctrl+Z 在输入框内的豁免怎么处理、
以及切文件夹清空栈的落点。我确认后再动手。

约束：前端零测试的现状不变，但 Phase 2 的纯函数（补丁应用/去重）请补 vitest 或
先说明为何不补；不许改 Rust；i18n 双写。
```

### 会话 ③（Phase 4）— ✅ 会话 ③ 已完成（见决策日志），下一会话请用会话 ④

```text
接着做 docs/ImageFilter-功能实施方案.md 的 Phase 4（颜色标签 + 可叠加筛选）。

先读该文档的 Phase 4 一节和文末决策日志，再只输出改动计划：
标签存储 key、快捷键方案（文档推荐 Ctrl+1–Ctrl+5，可提出更好方案）、
筛选三维度在 sortedPhotos 里的组合顺序、"未分析"提示的触发条件、
以及 lastClicked 改 useRef 后如何保证 Shift 范围选不回归。我确认后再动手。

约束：不动 Rust、不加依赖；i18n 双写；必须包含"分析只作用于选区"这一项。
```

### 会话 ④（Phase 5）— ✅ 会话 ③ 已完成（见决策日志），现在可用

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

