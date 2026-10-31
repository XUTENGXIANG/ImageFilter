# ImageFilter 文档索引

> 这份索引是文档的**唯一入口**。新增文档前先看「维护规则」，避免再造一份重复的现状说明。

## 权威顺序

1. [交接与上手](ImageFilter-交接与上手.md) — **当前状态、运行、代码地图、机制、必守坑位、验证工具**
2. [项目日志](ImageFilter-项目日志.md) — 按日期记录决策、事故、修复；历史证据，不是操作手册
3. [待决清单](ImageFilter-待决清单.md) — 待你决定 / 已代为决定 / 未处理风险
4. [安卓发版与签名](ImageFilter-安卓发版与签名.md) — 安卓构建、签名、CI、版本号
5. [Lightroom 衔接](ImageFilter-Phase7-Lightroom衔接.md) — Windows LrC 的深度说明
6. [更新说明](ImageFilter-更新说明.md) — 版本变更（1.1.0 起）
7. [证据索引](evidence/README.md) — 截图、脚本、逐字节证据

历史文档在 [archive/](archive/)，**不要据此改代码**。

## 目录

| 目录 | 内容 |
|---|---|
| `docs/` | 当前有效文档 |
| [docs/archive/](archive/) | 已归档的历史方案 / 走查 |
| [docs/evidence/](evidence/) | 验证证据与脚本 |
| [docs/superpowers/README.md](superpowers/README.md) | SDD 规范、specs / plans 索引与生命周期 |
| [docs/superpowers/specs/](superpowers/specs/) | SDD 设计规格（做什么、为什么） |
| [docs/superpowers/plans/](superpowers/plans/) | SDD 实施计划（怎么做、任务拆分） |
| [docs/superpowers/archive/](superpowers/archive/) | 已废弃的 SDD 计划 |
| `.superpowers/sdd/` | SDD 执行台账（本地、gitignored，不入库） |

## 维护规则

- **现状只写一处**：当前状态 / 机制 / 坑位只更新
  [交接与上手](ImageFilter-交接与上手.md)；其他文档链接过去，不要复制。
- **日志只追加**：项目日志按日期追加，不改写历史；规则性结论抽到交接文档或
  `ui-craft` skill。
- **计划是一次性的**：spec 定设计，plan 定执行；执行完在
  `.superpowers/sdd/<plan>/progress.md` 留台账，不把进度抄回 plan。
- **废弃不删除**：过时文档移到 `archive/`，顶部加「历史文档」横幅；删除只用于
  已经合并进其他文档且无独立价值的文件。
- **链接用相对路径**；移动文件后必须更新本索引和引用它的文档。
