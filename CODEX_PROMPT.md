# 给 Codex 的代码优化任务提示词

> 用法:把本文件内容完整粘贴给 Codex,让它在本项目上执行。

---

## 任务

对 a:\tenent 的 PixelFlow 项目(Tauri 2 桌面应用)做**代码优化与冗余删除**。
核心目标:**代码更简洁、更可维护,但功能、UI、性能、行为必须零改变,运行绝对稳定。**

## 项目背景

- 技术栈:React 19 + TypeScript + Tailwind v4(前端);Rust + Tauri 2(后端)
- 当前 HEAD:`abb75f5`(本地 master,与 GitHub 远端已分叉,不要 push)
- 先读:`HANDOFF.md`(交接文档,含运行方式/代码结构/关键坑)和 `PROJECT_LOG.md`(项目日志,含协作约定)
- 已知代码结构问题(优化目标):
  - `src/App.tsx` 约 1050 行,装下标题栏/设置/帮助/设备面板/照片网格/PhotoCard/ExifPanel/欢迎页/导入栏等全部组件
  - `src-tauri/src/scanner.rs` 约 950 行,混设备检测/IOCTL/浏览/扫描/EXIF/缩略图/预览/全解码 8 个功能域
  - 存在重复代码(缓存键计算 3 处、缩放+存图 5 处、EXIF 读取样板 2 处等)

## 硬性约束(违反即失败)

1. **功能零改变**:所有按钮、菜单、快捷键、对话框、动画、右键菜单行为必须与现状完全一致。不得新增/删除/改名任何命令、功能或交互
2. **UI 零改变**:界面文字、布局、颜色、字号、间距、动画效果不得有任何可见变化(含 i18n 结构,zh/en 切换必须保持)
3. **性能不降**:缩略图加载、查看器切换、RAW 解码链路的任何改动不得降低性能
4. **以下文件/逻辑禁止改动**(历史上修 bug 修出来的,动即回归):
   - `src/viewer.tsx` 的加载状态机(首次260ms延迟/切换立即/快速切换120ms debounce/full 600ms debounce/邻居预取)——每个分支都是修"闪黑/卡顿"的成果
   - `src-tauri/tauri.conf.json` 的 CSP(必须保留 `http://asset.localhost`,删了安装版图片全黑)
   - DNG 独立解码链路(rawler 完整显影优先 → tinydng → WIC)
   - `win_wic.rs` 的 `CoInitializeEx` 初始化
   - 缓存目录结构与命名(`%LOCALAPPDATA%\pixel-flow\thumbnails_v2|preview_v3|full_v3`)
   - 键盘快捷键体系(主窗口 + 查看器的分工)
5. **分阶段执行、每阶段可独立验证**:拆分成多个小提交(如 P0 清理死代码 → P1 消除重复 → P2 拆分文件),每阶段编译+运行验证通过后再进入下一阶段。禁止一个巨型提交
6. 每个阶段开始前用 `git stash`/提交先确认工作区干净,设好回退点

## 工作规范(必须遵守的 skill 级流程)

1. **先计划再动手**:开始前把分阶段计划写清楚(改动文件、每阶段目标、风险),先给用户确认
2. **verification-before-completion(先验证再声称完成)**:每个阶段完成时,必须实际运行 `npx tsc --noEmit`、`cd src-tauri && cargo check`,以及 `npx tauri dev` 启动应用人工确认关键路径,全部通过才允许说"完成",并把命令输出作为证据
3. **systematic-debugging(系统化调试)**:遇到任何 bug/异常,先查根因(读代码、查文档),禁止猜测性修改;禁止用"试试看"式乱改
4. **doubt-driven-development(存疑即验证)**:任何你不确定"删了会不会有影响"的代码,默认不删,或先验证再删
5. **保持代码风格**:与现有代码一致(注释用中文、IconPark 图标约定、Tailwind 类命名),不要引入新依赖,不要引入新库
6. **禁止的行为**:禁止格式化重构整个文件(会淹没真实 diff);禁止把删除的代码用注释保留(直接删);禁止改动 import 顺序等纯风格变化;禁止 eslint/prettier 自动修整

## 冗余代码范围(可安全处理)

- 定义了但从未调用的函数、组件、prop、state、import
- 注释掉的死代码
- 相同的逻辑片段重复出现 2 次以上(提取为共享函数/常量,签名保持)
- Cargo.toml 中确认无引用的依赖(删除前必须 grep 验证,注意 windows crate 的 feature 是 API 门控,删除前必须编译验证——之前就误删过 `Win32_Security` 导致 `CreateFileW` 编译失败)
- 单个文件过大时的模块拆分(App.tsx 组件拆文件、scanner.rs 拆子模块),拆完必须保证 `#[tauri::command]` 路径、`crate::scanner::xxx` 引用全部正确

## 项目日志要求(用户明确要求)

**每个改动完成后,必须在 `PROJECT_LOG.md` 的"最近开发"顶部追加一条日志条目**(格式:日期 + 主题 + 内容列表),再提交代码。这条约定写在 PROJECT_LOG.md 的关键约定第一条。

## 提交规范

- 每个阶段一个独立提交,提交信息格式:`refactor(Pn): 阶段描述`
- 不要 push(远端已分叉,需要用户提供 token 后统一处理)
- 提交信息用中文

## 验证清单(每个阶段收尾必须全过)

- [ ] `npx tsc --noEmit` 零错误
- [ ] `cd src-tauri && cargo check` 零错误
- [ ] `npx tauri dev` 启动应用,人工验证:设备识别、文件夹浏览、缩略图加载、双击打开查看器(动画/切换/渐进加载/旋转/星级)、右键菜单、AI 分析、导入、弹出设备、中英切换、浅色主题
- [ ] 与优化前行为逐项对比,无任何差异
- [ ] PROJECT_LOG.md 已更新
- [ ] 提交完成
