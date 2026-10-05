# 文件夹树展开动效 · 实测证据

日期：2026-10-05 · 分支 `feat/folder-tree-expand-motion`

## 1. 前置事实（决定了这个功能为什么不能"点一下就开始播动画"）

文件夹树的子目录是**一层一层异步取的**。`src-tauri/src/scanner/browse.rs:26-32` 给每个子项的
`subfolders` 一律返回空数组，只带一个 `has_subdirs` 标志；真正的子行要等用户展开后再发一次
`browse_directory`、经 `src/useScanner.ts` 的 `mergeChildren` 写回之后才存在。

所以点击那一刻，`children` 还是空数组。把动画挂在"点击"上，就是在给一个空盒子做动画。

## 2. 改动前的实测：根本不是"动画不好看"，是"静默一段然后整块蹦出来"

桩改成与 Rust 相同的返回形状（只给一层）后测得（探针 `verify-tree-motion.mjs`，参数是模拟的
`browse_directory` 往返延迟）：

| 模拟卡速 | 子行出现的时刻 | 记录到的中间状态 |
|---|---|---|
| 0ms | t=32ms | **0 个** |
| 250ms | t=279ms | **0 个** |
| 600ms | t=639ms | **0 个** |

三档都是行数一步跳完，采样里没有任何中间帧。

## 3. 反例：把动画挂在"点击"上，只在快盘上看着是对的

`docs/evidence/scripts/verify-tree-motion-mechanisms.mjs` 之外的独立机理测量（`.design-audit/_probe/tree-motion.html`，
四方案对照）给出的结论：

| 实现方式 | 32ms 快卡 | 250ms 慢卡 |
|---|---|---|
| 点击就 arm 过渡（直觉写法） | 14 台阶，平滑 ✅ | **2 台阶，跳变 ❌** |
| 数据到达后才 arm 过渡 | 14 台阶，平滑 ✅ | 14 台阶，平滑 ✅ |
| 占位行 + 显式高度（**本方案采用**） | 15 台阶，平滑 ✅ | 26 台阶，平滑 ✅ |

原因：过渡在 t=200ms 就跑完了，而那一瞬间内容还是空的（0 → 0）；数据在 t=250ms 到达时，
容器**已经停在终值上**，只能瞬跳。**这是一类在 SSD 上开发永远看不到、插上真实存储卡才暴露的 bug。**

## 4. 改动后的实测（判据：台阶数 > 3，且单帧最大跨度 ≤ 总行程的 35%）

跑 `node docs/evidence/scripts/verify-tree-motion.mjs <卡速ms>`：

| 模拟卡速 | 台阶数 | 首次有高度 | 退出码 |
|---|---|---|---|
| 0ms | 13 | t=67ms | 0 |
| 32ms | 14 | t=73ms | 0 |
| 250ms | 22 | t=161ms | 0 |
| 600ms | 27 | t=161ms | 0 |

250ms 那一档的完整高度轨迹（单位 px）：

```
0 → 0.14 → 0.92 → 2.44 → 3.83 → 6.53 → 9.72 → 12.02 → 16.92 → 17.69 → 20.70
  → 26.13 → 31.02 → 40.28 → 50.98 → 62.31 → 72.98 → 82.13 → 89.05 → 93.58 → 95.27 → 96
单帧最大跨度 11.33px / 总行程 96px = 11.8%
```

四档都平滑，**慢卡不再失效**。

## 5. 顺带改掉的行高：面板里唯一低于无障碍下限的行

改动前后左侧面板里各行的实测高度：

| 行 | 改动前 | 改动后 |
|---|---|---|
| 面板收起按钮 / 刷新 | 24 | 24（未动） |
| 设备行 `EOS_DIGITAL` | 28 | 28（未动） |
| 根目录 | 26.5 | 26.5（未动） |
| **树行** | **20.5** | **24** |

树行是数量最多、最常点的那一行，也是唯一低于 WCAG 2.5.8 目标尺寸下限（24px）的一行。

## 6. 无障碍：两处改动都有实测断言

- **每一行都有 `aria-expanded`**（可展开的行是 `true`/`false`，叶子行**完全没有这个属性**，
  不是 `"false"`）。改动前实测 6 行里 0 行有这个属性。
- **箭头字形不在可访问名里。** 改动前整行会被读成 `▼DCIM874`；加了 `aria-hidden="true"` 之后
  是 `DCIM874`。断言的做法是：克隆行、摘掉 `aria-hidden` 的子树、再看剩下的文本 —— 与可访问名
  的计算规则一致。
- **收起的子树常驻 DOM（高度动画的前提），所以收起时必须 `inert` + `aria-hidden`**，否则
  收起的子行会漏进 Tab 顺序与无障碍树。见 `verify-tree-inert.mjs`。

## 7. 已知限制（诚实列出）

1. **`PLACEHOLDER_DELAY_MS = 120` 没有真实卡速分布做依据。** 本机是 NTFS SSD，
   一层目录的读取是几毫秒量级；而 `has_subdirectories` 对每个子目录都要探一次，真实存储卡上会
   明显更慢。这个数是全案唯一只有人工观感依据的值。改动它必须同步改单测（那里有断言）。
2. **嵌套展开时父容器可能被短暂裁掉。** 容器在过渡结束后才切成 `height: auto`；在那之前若
   展开孙分支，父容器会被 `overflow: hidden` 裁到旧高度，直到收尾定时器（250ms）触发后自愈。
   触发条件是在 250ms 内连点两层。
3. **`verify-tree-motion.mjs` 的 transform 判据是钉死的字符串**（`matrix(0, 1, -1, 0, 0, 0)`），
   绑在 Chromium 对 `rotate(90deg)` 的序列化形式上。换一个 Chromium 版本可能变成等价但不同的
   写法而误报失败。要长期用应改成解析角度。
4. **树没有 `role="tree"` 语义，也没有方向键导航**（既有缺口，本次不引入），读作"一串按钮"。
5. **收起后子树常驻 DOM。** 规模由"用户实际展开过多少分支"决定 —— 未展开过的节点 `children`
   为空，不产生 DOM。

## 8. 未入库的产物

截图与一次性探针留在 gitignore 的 `.design-audit/_probe/`（本机路径，未入库）：
`tree-row-after.png`（改动后的树）、`caret-collapsed-branch.png` / `caret-expanded-DCIM.png`
（8 倍放大的箭头，用来确认旋转方向与光学居中）、`tree-expand-compare-250.png`（四方案对比页）、
`tree-motion-d250.png` / `tree-motion-d32.png`（四机理的高度轨迹对照）。

## 9. 跑法

```
先决条件: npm run tauri dev 已在跑 (Vite 在 localhost:1420; 只绑 IPv6, 不能用 127.0.0.1)
         docs/evidence/scripts/tree-mock.js 与本脚本同目录 (Tauri IPC 桩)

node docs/evidence/scripts/verify-tree-motion.mjs 250        # 展开动效, 输出台阶数与退出码
node docs/evidence/scripts/verify-tree-inert.mjs              # 收起后的 inert 与嵌套不裁
node docs/evidence/scripts/verify-tree-row-geometry.mjs       # 行高 / 箭头 / aria-expanded / 可访问名

另有纯逻辑单测(不需要 dev server):
  npx esbuild docs/evidence/scripts/tree-motion.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/tree-motion.mjs
  node .design-audit/_probe/tree-motion.mjs
```

三个浏览器脚本的退出码都是 0 = 通过、1 = 失败，可直接用于自动化。
`playwright-core` 的解析路径默认按本机 DSH profile 写，换机器可用 `PW_BASE` 环境变量覆盖。

## 10. 追加：设备栏的"按下"反馈 + 设备树入场

同一天追加的两处，起因是"设备栏的点击动画也要"。

### 10.1 按下反馈（改动前设备行、刷新、根目录、树行全都没有）

原先这些行**只有 hover、没有任何按下状态** —— 按下去一点回应都没有，而项目里 shadcn 那套按钮
本来就有 active 反馈（`src/components/ui/button.tsx` 的 `active:not-aria-[haspopup]:translate-y-px`）。

统一成一档更暗的底色而不是位移：列表行整行下沉 1px 在密集列表里显得像掉出来了，色块变化没有布局副作用。
`.press-row` / `.press-solid` 定义在 `src/index.css`。

实测（真的把鼠标按住再读计算样式，而不是只看截图）：

| 状态 | 背景色 |
|---|---|
| 静置 | `rgba(0, 0, 0, 0)` |
| 悬停 | 透明（Tailwind v4 的 `hover:` 变体被包在 `@media (hover: hover)` 里，无头环境报 no-hover；`:active` 不受这层门控，所以触屏/手写笔上按下反馈照样生效） |
| **按下（未选中）** | `rgba(63, 63, 70, 0.7)` |
| **按下（选中）** | `rgba(6, 95, 70, 0.65)` |

注意颜色是写字面量的，没写成 `var(--color-emerald-800)` —— Tailwind v4 只为"实际用到"的颜色生成
`--color-*`，emerald-800 在本项目里没有任何地方用到，写 `var()` 会解析成空值、按下时直接没反应
（这个坑 `.badge-glass` 那段已经踩过一次）。

### 10.2 设备树入场（改动前是"扫描中…"闪一下，然后整棵树一次性蹦出来）

与文件夹树当初同一个毛病，只是发生在"点设备"这一层。给树容器加了 `.tree-enter`
（`animation: tree-enter 180ms ease-out both`，从 `opacity: 0` + `translateY(-4px)` 起）。

用 `animation` 而不是 `transition`：它是挂载时的一次性事件，没有"变化前"的状态可供过渡。
`fill-mode: both` 是必要的 —— 减少动效偏好把 `animation-duration` 压到 0.01ms 时，元素要停在终态
（可见），不能因为动画被压掉就留在 `opacity: 0` 上。

实测 opacity 轨迹（不是只截一张"已经好了"的图）：

```
0 → 0.051 → 0.1 → 0.149 → 0.197 → … → 0.981 → 1      （31 个中间帧，首帧 transform 带 -4px）
```

### 10.3 这次没归档的探针

`verify-device-press.mjs`（按下状态的量法与树入场的 opacity 轨迹量法那个脚本）留在
gitignore 的 `.design-audit/_probe/` 里，未入库 —— 这两处是新加的便捷反馈，不是会被后续改动
反复回归的核心行为。若以后要常态化回归，再按 §1 的做法把它相对化后收进来。

