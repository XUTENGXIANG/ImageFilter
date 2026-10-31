# SDD 规范与索引

> SDD = Spec-Driven Development。这里只放**设计与计划**；执行台账在
> [`../../.superpowers/sdd/`](../../.superpowers/sdd/)（本地、gitignored，不入库）。

## 生命周期

```
spec（做什么 / 为什么）
  → plan（怎么做 / 任务拆分 / 验收线）
    → progress（`.superpowers/sdd/<plan>/progress.md`：实际执行、提交区间、测试结果）
      → report（task-*-report.md：复盘、偏差、遗留项）
```

四条纪律：

1. **一个里程碑一份 spec + 一份 plan**；不要在 plan 里重写设计，也不要在 spec 里排任务。
2. **plan 是快照**：执行后不回填进度；状态写在本索引或 progress 台账里。
3. **执行台账不进 docs**：`.superpowers/sdd/` 里可以有截图、日志、数据库备份，但不要
   把二进制或敏感数据放进 `docs/`。
4. **废弃计划不删除**：顶部加「⚠️ 本计划已废弃」横幅，移到
   [archive/](archive/)，并更新本索引。

命名：`YYYY-MM-DD-<topic>.md`；同一主题的 spec 与 plan 用相同日期和 topic。

## Specs

| 日期 | 规格 | 状态 | 对应计划 |
|---|---|---|---|
| 2026-08-11 | [落地页设计](specs/2026-08-11-imagefilter-website-design.md) | 已完成（独立仓库 + Vercel） | [plan](plans/2026-08-11-imagefilter-website.md) |
| 2026-10-05 | [文件夹树展开动效](specs/2026-10-05-folder-tree-expand-motion-design.md) | 已完成 | [plan](plans/2026-10-05-folder-tree-expand-motion.md) |
| 2026-10-05 | [Mica 跟随系统默认](specs/2026-10-05-mica-os-default-design.md) | 已完成 | — |
| 2026-10-06 | [安卓设计 v2（桌面端当图库主机）](specs/2026-10-06-imagefilter-android-design.md) | 当前基线 | M0′–M4′ plans |

## Plans

| 日期 | 计划 | 状态 | 台账 |
|---|---|---|---|
| 2026-08-11 | [落地页实现](plans/2026-08-11-imagefilter-website.md) | 已完成（部署见网站仓库） | `.superpowers/sdd/2026-08-11-imagefilter-website/` |
| 2026-10-05 | [文件夹树展开动效](plans/2026-10-05-folder-tree-expand-motion.md) | 已完成 | `.superpowers/sdd/2026-10-05-folder-tree-expand-motion/` |
| 2026-10-06 | [M0′ 桌面端图库服务](plans/2026-10-06-imagefilter-android-m0-desktop-service.md) | 已完成 | `.superpowers/sdd/2026-10-06-imagefilter-android-m0-desktop-service/` |
| 2026-10-06 | [M1′ 安卓骨架 / 配对 / 浏览 / 网格](plans/2026-10-06-imagefilter-android-m1-skeleton.md) | 已完成 | `.superpowers/sdd/2026-10-06-imagefilter-android-m1-skeleton/` |
| 2026-10-06 | [M2′ 查看器 / 打分回写 / 筛选](plans/2026-10-06-imagefilter-android-m2-viewer-ratings.md) | 已完成 | `.superpowers/sdd/2026-10-06-imagefilter-android-m2-viewer-ratings/` |
| 2026-10-06 | [M3′ 滑卡 / 撤销 / 移动 / 待重试](plans/2026-10-06-imagefilter-android-m3-swipe-move.md) | 已完成 | `.superpowers/sdd/2026-10-06-imagefilter-android-m3-swipe-move/` |
| 2026-10-06 | [M4′ 配对 / 引导 / 只读 / 发布](plans/2026-10-06-imagefilter-android-m4-polish-release.md) | 已完成 | `.superpowers/sdd/2026-10-06-imagefilter-android-m4-polish-release/` |
| 2026-10-25 | [mDNS 配对](plans/2026-10-25-imagefilter-mdns-pairing.md) | 已完成 | 见项目日志 §十一 |
| 2026-10-25 | [安卓浅色主题](plans/2026-10-25-android-light-theme.md) | 已完成 | 见项目日志 §十一 |
| 2026-10-25 | [高清预览性能](plans/2026-10-25-highres-preview-perf.md) | 已完成 | 见项目日志 §十二 |
| 2026-10-06 | [M0 + M1a（v1 / NAS 前提）](archive/2026-10-06-imagefilter-android-m0-m1a.md) | **已废弃** | — |

## 当前状态去哪看

- 现在能跑什么、怎么跑、有哪些坑： [交接与上手](../ImageFilter-交接与上手.md)
- 决策与事故的历史： [项目日志](../ImageFilter-项目日志.md)
- 待决定 / 待动手： [待决清单](../ImageFilter-待决清单.md)
