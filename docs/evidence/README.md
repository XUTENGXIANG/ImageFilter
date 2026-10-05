# 实机验证证据

这里放的是**轻量、且无法从代码里重新读出来**的验证痕迹。截图之类的大体积产物没有入库（下面有说明）。

## `xmp-sidecar/` — Phase 5：XMP 边车写入的逐字节证据

证明"只改 `xmp:Rating`、其它字段一个字节都不动"这个契约在**真机上**成立（不是单测里的合成数据）。

| 文件 | 是什么 |
|---|---|
| `EVIDENCE.md` | 完整记录：操作步骤、逐字节 diff 结果、目录级副作用面 |
| `orig-07500.xmp` | 原始边车（写之前的基线，320 B） |
| `staged-07500.xmp` | 手工塞入 `xmlns:ifx` + `ifx:note="keep-me"` 后的边车（366 B，写入前基线） |
| `after-rating3-07500.xmp` | 应用改成 3 星之后（366 B） |
| `after-restore5-07500.xmp` | 应用改回 5 星之后（= `staged`，证明往返字节确定） |
| `snap-before.txt` / `snap-after.txt` | 目录快照（文件数 / size+mtime 多重集） |

关键结论（细节见 `EVIDENCE.md`）：`staged → after` 的逐字节 diff **只有 1 个字节不同**（offset 271，`'5'` → `'3'`），其余 364 字节未动。

未入库的截图（当时用 `_probe/gui.ps1 shot` 抓的，已删）：`01-app.png`、`02-card1-rating.png`、`03-card1-bottom.png`、`04-after-3star.png`、`05-after-3star-zoom.png`。

## `phase7-lightroom/` — Phase 7：Lightroom Classic 探路的原始输出

做设计之前对这台机器的 LrC（15.2.1）实测的原始记录。几条结论都是反直觉的，所以留原始输出备查。

| 文件 | 关键结论 |
|---|---|
| **`lrc-reg.txt`** | 安装路径的**唯一命中**方式：`.lrcat` 文件关联（本机装在 `A:\lrc`，标准目录与 App Paths 全落空） |
| **`lrc-uia.txt`** | `Lightroom.exe "<文件夹>"` 会打开导入对话框（UIA 抓到「导入 / 源 / 完成 / 取消」） |
| `lrc-prefs.txt` | `.agprefs` 是**纯文本**（首 4 字节 `pref`）；且 **Auto Import 相关键 0 命中** → 这台机器从未配过自动导入 |
| `lrc-sub-uia.txt` | 传**子文件夹**路径能精确进到那一层 |
| `lrc-probe-report.txt` / `lrc-user.txt` / `lrc-reg2.txt` | 探测汇总、用户目录、确认没有版本号子键 |
| `lrc-sel.txt` / `lrc-import-dialog.txt` / `lrc-filearg.txt` / `lrc-filearg-uia.txt` | 主窗口类名 `AgWinMainFrame`；传文件路径时的行为 |
| `lrc-catalog-check.txt` / `lrc-teardown.txt` / `lrc-sub-teardown.txt` | 收尾核对：**目录库里没有混入任何探针文件**、LrC 已关、测试目录已删 |

> ⚠️ **一个易踩的坑**：上面那些 UIA dump 里出现的「1 张照片 / 120 KB」「文件夹 : 待修2026.7.23 / 44 张照片」是**主窗口元数据面板**的内容，不是导入对话框的源面板 —— 曾据此误判"源定位成功"。真正能分辨的信号是"传空文件夹 → 对话框报 `没有找到照片 / 0 张照片 / 0 字节`"。

未入库的截图：`lrc-after-launch.png`。

## `scripts/` — 可复用的验证脚本

| 文件 | 用途 | 怎么跑 |
|---|---|---|
| `probe-lrc.ps1` | 探测 LrC 安装位置 / 是否运行；可选实测"传目录给它" | `powershell -File probe-lrc.ps1`（**用 Windows PowerShell 5.1，本机没有 pwsh**） |
| `stage-ifx.ps1` | 往 `.xmp` 里手工塞自定义属性，用来验证"其它字段不被改动" | 见 `EVIDENCE.md` |
| `lrc-logic.test.ts` | `src/lightroom.ts` 的纯逻辑断言（17 条） | 两行见文件头注释（esbuild + node，不引 vitest —— 项目既有约定） |
| `lrc-logic.mjs` | 上面那份转译后的产物 | `node lrc-logic.mjs` |
| `i18n-parity.cjs` | 校验 zh/en 叶子 key 完全对齐 | `node i18n-parity.cjs` |
| `tree-motion.test.ts` | `src/components/folder-tree-motion.ts` 的阶段判定断言（10 条） | 见 `folder-tree-motion/EVIDENCE.md` §9 |
| `verify-tree-motion.mjs` | 文件夹树展开的**容器高度轨迹**（判据：台阶数 > 3 且单帧跨度 ≤ 总行程 35%；退出码 0/1） | `node verify-tree-motion.mjs <卡速ms>`，需先跑 `npm run tauri dev` |
| `verify-tree-inert.mjs` | 收起后的子树是否真的不可 Tab 到，以及嵌套展开有没有被裁 | 同上 |
| `verify-tree-row-geometry.mjs` | 树行 24px / 箭头同字形靠 transform 旋转 / `aria-expanded` 的有无 / 可访问名不含箭头 | 同上 |
| `tree-mock.js` | 上面四个脚本共用的 Tauri IPC 桩（`browse_directory` 只返回一层子目录，与 Rust 一致；也提供 `get_os_capabilities`） | 由脚本读取，`?gap=N` 模拟卡速、`?win10=1` / `?osfail=1` 模拟系统能力 |
| `os-default-glass.test.ts` | `src/os-capability.ts` 的纯逻辑断言（17 条：stored × 五种系统能力） | 见 `mica-os-default/EVIDENCE.md` §7 |
| `verify-os-default.mjs` | 玻璃开关默认值的端到端（4 场景：Win11 / 模拟 Win10 / 老用户已存值 / 探测失败） | 同上，需先跑 `npm run tauri dev` |
| `verify-collapse-anim.mjs` | 高级选项 / 筛选面板展开动画的高度轨迹（双向 + 收起时 inert） | 同上 |
| `verify-dropdown.mjs` | 4 个自定义下拉：无原生 select、圆角 10px、入场过渡、能选中、可访问名称、关闭后不可达、**弹层没被盖住** | 同上；`APP_URL` 可覆盖地址、`SHOT=<path>` 按需截图 |
| `verify-floating-bars.mjs` | 上下两条栏"真浮窗"布局：网格铺满高度、CSS 变量与栏真实高度一致（折叠/展开）、两端留白 ≥24px 且末行不在控件下面 | 同上 |
| `update-check.test.ts` | `src/updater.ts` 的纯逻辑断言（40 条：版本号解析、比大小的边界、五态与失败分队、真超时） | 两行见文件头注释（esbuild + node，产物落 `.design-audit/_probe/`，不入库） |
| `verify-update-check.mjs` | 设置面板「版本号 + 检查更新」：版本号取自二进制（`getVersion`）/ 放弃兜底、行几何与 24px 命中区、五态字面与悬停、文字对比度、键盘焦点环、点「有新版本」真的把 URL 交给系统浏览器、英文长文案不折行 | `node verify-update-check.mjs`，需先跑 dev server |

> ⚠️ **检查更新依赖 CSP 放行**：它走 WebView 自己的 `fetch` 打 `api.github.com`，
> 所以 `src-tauri/tauri.conf.json` 的 `connect-src` 里必须有 `https://api.github.com`。
> 少了这一条**不会报错**，只会永远显示"检查失败"（浏览器控制台里才看得到 CSP 拦截）。
> 改完这个文件要重启 `npm run tauri dev`（CSP 在 Rust 侧生效，HMR 不管它）。

跑浏览器脚本时注意路径：它们把 `playwright-core` 按本机 DSH profile 解析，换机器用 `PW_BASE` 环境变量覆盖。

> ⚠️ **同时只跑一个 dev server。** 实测开两个 Vite 实例（比如另起一个 `vite --port 1421` 专供探针）时，
> 其中一个会把某些模块**服务成旧版本**——连 F5 都没用，因为它重新下载的还是那份旧模块。
> 表现出来是"改了代码、界面只变一半"（例如新 `App.tsx` 配旧子组件 → 布局变了但滚动失效）。
> 排查手法：直接抓服务出来的模块比对特征字符串，别靠肉眼看界面。
> `curl http://localhost:1420/src/components/xxx.tsx` 然后 grep 你刚加的那个标识。


`verify-os-default.mjs` 会在自己旁边写出 `os-win11.png` / `os-win10.png` 两张截图（就是设置面板里那一行），按本仓库约定**不入库** —— 跑完看到这两个未跟踪文件是正常的，可以直接删。

> 跑 `lrc-logic.test.ts` / `i18n-parity.cjs` 时注意路径：它们按仓库根在 `A:/tenent` 写的，换机器要改 `import`/读文件路径。
