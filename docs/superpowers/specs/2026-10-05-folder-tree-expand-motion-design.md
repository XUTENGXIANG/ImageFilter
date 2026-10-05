# ImageFilter 文件夹树展开动效

> 日期：2026-10-05
> 状态：待用户确认
> 目标仓库：本仓库（ImageFilter / imagefilter）

## 1. 背景与目标

左侧面板的文件夹树今天**没有任何展开动效**。更麻烦的是它中间夹着一段**静默期**：

`src/components/folder-tree-item.tsx:39` 是条件渲染 `{open && canExpand && node.children.map(...)}`，而 `src-tauri/src/scanner/browse.rs:26-32` 给每个子项的 `subfolders` 一律返回空 `vec![]` —— **子目录是一层一层异步取的**。所以点击的瞬间 `children` 还是空数组，真行要等 `loadFolder` → `browse_directory` 回来、经 `src/useScanner.ts:1046-1053` 的 `mergeChildren` 写回之后才出现。

结果：点击 → 只有箭头从 ▶ 变成 ▼ → 静默若干百毫秒 → 整块子行蹦出来。

**目标**：把这段静默期做成一次可读的展开动效；同时把树行行高从 20.5px 提到 24px（WCAG 2.5.8 目标尺寸下限）。

## 2. 需求决策（已与用户确认）

| 项 | 决定 |
|----|------|
| 动效方案 | **B：占位行 + 显式高度驱动**（对比页里的 B 列） |
| 树行行高 | **20.5px → 24px** |
| 展开/收起入口 | **不变** —— 点击行仍然同时"展开 + 选中该文件夹"（沿用现状，见 §10） |
| 占位行是否延迟出现 | **未定，见 §6**（建议 120ms） |

## 3. 现状实测

把浏览器桩改成与 `browse.rs` 相同的返回形状（只给一层子目录，子项的 `subfolders` 为空）后测得。探针：`.design-audit/_probe/mock-tree.js` + `verify-tree-expand.mjs`。

### 3.1 静默期有多长，以及它一步到位

| 模拟 `browse_directory` 往返 | 子行出现的时刻 | 记录到的中间状态 |
|---|---|---|
| 0ms | t=32ms | 0 个 |
| 250ms | t=279ms | 0 个 |
| 600ms | t=639ms | 0 个 |

三档都是**行数 7→11 一次跳完**，采样里没有任何中间帧。所以现状的问题不是"动画不好看"，是"静默一段然后瞬变"。

### 3.2 为什么不能直接"点击就起动画"

最直觉的实现是在点击时把 `grid-template-rows` 从 `0fr` 翻到 `1fr`（项目里 `src/components/collapsible-bar.tsx:21` 就是这么写的）。实测这个写法**在快卡上正常、在慢卡上静默失效**（探针：`.design-audit/_probe/verify-tree-motion.mjs`）：

| 机理 | 32ms | 250ms |
|---|---|---|
| 点击即起动画（直觉写法） | 14 台阶，平滑 ✓ | **2 台阶，跳变 ✗** |
| 数据到达后才起动画 | 14 台阶，平滑 ✓ | 14 台阶，平滑 ✓ |
| **占位行 + 显式高度（本方案）** | 15 台阶，平滑 ✓ | 26 台阶，平滑 ✓ |

原因：过渡在 t=220ms 就跑完了，而那一瞬内容还是空的（0 → 0）；数据 t=250ms 到达时容器**已经停在终值上**，只能瞬跳。

**这是一类在 SSD 上开发永远看不到、插上真卡就露馅的 bug**，所以 §4 的状态机必须按异步到达来设计，而不是按点击来设计。

### 3.3 左侧面板今天的行高（同一个面板里五种值）

| 行 | 实测高度 | 字号 |
|---|---|---|
| 面板收起按钮 ◀ | 24 | 16px |
| 刷新 | 24 | 10px |
| 设备行 `EOS_DIGITAL` | 28 | 12px |
| 根目录 | 26.5 | 11px（含 1px 边框） |
| **树行（最多、最常点的）** | **20.5** | 11px |

探针：`.design-audit/_probe/verify-treepanel-rows.mjs`。树行既是数量最多的行，又是唯一低于 24px 的行。

## 4. 状态机

每个可展开节点（`canExpand === true`）的子树容器有四个状态。**高度一律用 `inner.scrollHeight` 实测，代码里不出现任何写死的像素值**（24px 只存在于行本身的样式里，占位行复用同一个类，因此自动同高）。

```
collapsed        height: 0
   │ 点击（open = true）
   ├──── 子行在 120ms 内到达 ────────────────┐
   │                                          │
   ▼ 120ms 后子行仍未到达                      │
loading          height: 0 → inner.scrollHeight（此时内容只有 1 个占位行，即 24px）
   │ 子行到达（children.length > 0）          │
   ▼                                          ▼
expanded         height: → inner.scrollHeight（此时是全部子行的真实高度）
   │ 过渡结束（200ms + 50ms 余量）
   ▼
settled          height: auto
   ↑                                    │
   └────────── 点击（open = false）──────┘
              height: 当前像素值 → 0
```

120ms 那条分支见 §6：快卡走右侧直连（不出现占位行），慢卡走左侧（先展开一个占位行）。

关键点：

- **`settled` 必须是 `auto`。** 固定像素高度会在子分支被展开时不跟着长（父容器高度 ≠ 子行数 × 24，因为某个子是已展开的分支），内容会被裁掉。`auto` 让父容器跟随内容。实测：父容器处于 `auto` 时展开孙分支，父容器连续跟随、不跳。
- **`height: auto` 的收尾不能用 `transitionend`。** `src/index.css:342` 明确写着"项目内没有任何代码依赖 transitionend"，grep 也确认 `src/` 下没有任何 `transitionend` / `onTransitionEnd` 用法。所以用 `setTimeout(200 + 50ms)`，并配一个代次计数，避免"收尾定时器在用户已经再次点击之后才触发、把正在收起的容器又设回 auto"。
- **三个定时器都要能被作废**：120ms 的占位延迟、250ms 的 auto 收尾、以及收起时的那一帧 `requestAnimationFrame`。任一时刻再次点击，先前排队的回调全部按代次作废。
- **收起**：先取当前像素高度钉住，`requestAnimationFrame` 后设为 0（经典写法，`auto` 状态必须这样才能从当前位置起动画）。
- **等待期间用户又点了一次**：不做任何"等待-然后展开"的 promise。`children` 是通过 props 到达的，所以用一个 `useEffect` 依赖 `[open, children.length]` 派生：`open === false` 时子行到达什么都不做。没有竞态，因为状态是派生的而不是记住的。
- 子行到达时若容器正处于 0→24px 的途中，目标从 24px 改到真实高度，浏览器从**当前计算值**继续过渡（实测 26 台阶、无跳变）。

## 5. 视觉规格

| 项 | 值 | 依据 |
|---|---|---|
| 行高 | 24px | WCAG 2.5.8；与面板内已有的 24px 对齐 |
| 过渡属性 | `height` | 只动 height，不做 transform 假动画（内容是真的在变高） |
| 时长 / 缓动 | 200ms / `ease-in-out` | 与 `collapsible-bar.tsx:21` 的 `duration-300 ease-in-out` 同一套缓动；树行更小更频繁，故取 200ms 而非 300ms |
| 箭头 | 同一个 `▶` 字形加 `rotate(90deg)`，160ms | 现在用的是 **▶/▼ 换字形**，两个字符推进宽度不同，展开时会横向抖 1px。换成旋转顺带修掉；**不引入新图标**，保持与叶子的 `Folder theme="filled"` 同样的实心三角观感 |
| 占位行 | 与树行同高，内含一根 8px 高、64px 宽的圆角条，`zinc-700` 底 + 呼吸动画 | 不画假文件夹图标、不画假计数 —— 要读成"在加载"，不是"这是个空文件夹" |
| 点击瞬间反馈 | 箭头立刻旋转 + 行立刻进选中态 | 慢卡上这几百毫秒里唯一即时可见的东西 |

### 5.1 24px 只出现一次

树行与占位行共用同一条高度规则，**`24px` 在代码库里只出现这一次**：

```css
.tree-row {
  height: 24px;
}
```

占位行复用 `.tree-row`，因此自动同高；**JS 里不出现 24**（§4 的高度一律由 `inner.scrollHeight` 得出，所以占位行换真行时目标高度自动正确）。

不需要自定义属性，也不需要给树容器加类 —— 加类就得改 `src/App.tsx` 的渲染结构，而 §10 明确不改。

`.tree-row` / `.tree-kids` / `.tree-caret` / `.tree-row-skeleton` 放在 `src/index.css`，与既有的 `.badge-glass`、`.thumb-slider`、`.hit-24` 同一层（组件级 CSS 而非工具类）。行的**缩进内边距仍走行内样式**，因为缩进是逐节点的值（`depth * 12 + 8`），不是全局常量。

若以后要统一设备行（28px）与根目录行（26.5px）的高度，那时才值得把它提到 `:root`；本次不动。

## 6. 占位行的延迟阈值

**决定：延迟 120ms** —— 即"点击后 120ms 内数据还没到，才显示占位行"。

| | 32ms 快卡 | 250ms 慢卡 |
|---|---|---|
| 不延迟 | 占位行只存在约 2 帧就被真行替换 → 可能是一下灰条闪动 | t=17ms 起就在动，感知最快 |
| **延迟 120ms（采用）** | 占位行完全不出现；从点击到落地是一次干净的 0→全高展开 | t≈120ms 起开始动，总时长约 450ms |

取延迟版的理由：快卡上不闪、慢卡上仍有早期反馈。

**120ms 这个数字是本次唯一需要在真机上复核的值** —— 它取决于真实 `browse_directory` 的延迟分布，而我没有那个分布（见 §9）。它必须是代码里一个具名的常量，且改动它不应引起其他逻辑变化。

## 7. 无障碍

- **行按钮补 `aria-expanded`。** 实测现在 6 行里 **0 行**有这个属性。
- **箭头那层 span 补 `aria-hidden="true"`。** 它是纯装饰，但今天**在按钮的可访问名里** —— 整行会被读成"▶ DCIM 874"。补上之后可访问名才是本节说的"行文本 `DCIM 874`"。
- **收起的子树必须常驻 DOM**（这是 B 的前提），所以**必须补 `inert` + `aria-hidden`**，否则收起的子行会漏进 Tab 顺序和无障碍树。`src/components/collapsible-bar.tsx:25-26` 与 `src/components/panel.tsx:28-29` 已经是这个写法，照抄即可。注意：本方案下这两个属性在收起状态下是**长期**成立，不只是动画期间。
- **占位行不是按钮**，不可聚焦，`aria-hidden` 掉视觉部分。用一个 `role="status"` 的视觉隐藏文案（复用已有的 `devices.loading`，`src/i18n/zh.ts:116` = "加载中..."）向读屏说明正在加载，不新增 i18n key。已知局限：实时区域是随内容一起插入的，部分读屏不一定播报；主信号仍是父行的 `aria-expanded="true"`。
- 行按钮本身不额外加 `aria-label`（它的文本内容就是名字）。

## 8. 减少动效

`src/index.css:344-353` 的全局块用 `transition-duration: 0.01ms !important` 压掉所有过渡。因为 `!important` 胜过行内样式，本方案用行内 `style.height` 驱动的过渡同样会被压掉 —— 瞬时到位，不需要额外处理。

`height: auto` 的收尾定时器不受影响（照旧晚一点执行，无害）。

**占位行该出现还是要出现**：它是状态不是动效。开着"减少动效"的用户同样需要知道"点了，在加载"。所以延迟逻辑与动效无关，不随该偏好改变。

## 9. 已知限制 / 本次验不了的部分

- **真实卡速分布验不了。** 本机是 NTFS SSD，`browse_directory` 在一层目录上是几毫秒量级；而 `has_subdirectories`（`browse.rs:25`）对每个子目录都要探一次，真实 SD 卡上会明显更慢。§6 的 120ms 阈值需要在真机上肉眼确认，我给不出实测依据。
- **树没有 `role="tree"` 语义，也没有方向键导航**（现状如此）。本次不引入，见 §10。这意味着它读作"一串按钮"而不是"一棵树"，是既有缺口。
- 不依赖 `interpolate-size` 等新特性，所以不引入新的 WebView2 版本下限。
- 收起后子树常驻 DOM。规模由"用户实际展开过多少分支"决定（未展开的节点 `children` 为空，什么都不渲染），不随文件夹总数增长。

## 10. 明确不做

- 不引入 `role="tree"` / `treeitem` 与方向键导航（会把交互模型整个换掉，是独立议题）
- 不改"点击行 = 展开 + 选中"这个耦合（现状行为，改动它属于交互设计变更）
- 不统一设备行（28px）与根目录行（26.5px）的行高 —— §3.3 记录了这个不一致，但它们读作不同层级的元素，是否统一由用户另定
- 不做展开状态持久化、不做"全部收起"
- 不改 `src/App.tsx` 的渲染结构（`:687-708` 的树渲染点保持原样）

## 11. 改动面

| 文件 | 改动 |
|---|---|
| `src/components/folder-tree-item.tsx` | 主体：状态机、`aria-expanded`、占位行、`inert`/`aria-hidden`、箭头旋转 |
| `src/index.css` | 新增 `.tree` / `.tree-row` / `.tree-row-skeleton` / `.tree-kids`（见 §5.1），与既有的 `.badge-glass`、`.thumb-slider`、`.hit-24` 放同一层 |
| `src/i18n/*` | 无需改（复用 `devices.loading`） |
| `src/App.tsx` / `src/useScanner.ts` / Rust | 无需改 |

不改 `package.json`：不引入动画库，`tw-animate-css` 已在依赖里但本方案不需要它。

## 12. 验证方式

| 断言 | 手段 |
|---|---|
| 四种卡速下都是平滑展开（台阶数 > 10） | 已有的 `.design-audit/_probe/verify-tree-expand.mjs`，扩展一个台阶数断言 |
| 反例必须失败：点击即起动画在 250ms 卡速下台阶数 ≤ 3 | `.design-audit/_probe/verify-tree-motion.mjs`（已知会复现，作为判据的对照组） |
| 收起的子树不进 Tab 顺序 | 键盘 Tab 遍历 + `inert` 生效性检查（`.design-audit/_probe/` 下已有 focus 探针可复用） |
| 每行都有 `aria-expanded` | 无障碍枚举探针（`a11y-enumerate.mjs`） |
| 行高 24px、命中区 ≥24×24 | 几何探针（`verify-treepanel-rows.mjs` / `verify-hit-area.mjs`） |
| 嵌套展开时父容器不被裁 | 展开两层后比对容器高度与内容 `scrollHeight` |
| 减少动效下瞬时到位且占位行仍出现 | 模拟 `prefers-reduced-motion` 的探针（`verify-reduced-motion.mjs`） |
| 120ms 阈值在真机上的观感 | **验收时人工确认**（§6 / §9），探针给不出结论 |
