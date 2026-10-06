# ⚠️ 本计划已废弃（仅保留历史）

原计划（**M0 探路 + M1a 抽核**）建立在规格 **v1** 之上：图库在 NAS、手机直读 WebDAV。
因此它规划的是三件事：

1. RAW 内嵌预览覆盖率探针（因为 v1 下手机要自己从 RAW 里挖内嵌 JPEG）
2. NAS 的 WebDAV 能力探测脚本（Range / `getetag` / `If-Match` / `MOVE Overwrite`）
3. 把 `xmp.rs` 与内嵌预览提取抽成 `crates/imagefilter-core`，经 **UniFFI** 给手机端复用（要 NDK）

**v1 的前提已不成立**（没有 NAS），而且不满足"别人下载下来也能用"。设计已在
[2026-10-06-imagefilter-android-design.md](../specs/2026-10-06-imagefilter-android-design.md)
就地改为 **v2：桌面端自己当图库主机**，转向理由见该文附录 A #4/#5/#7。

v2 之下，本计划的绝大部分内容**不再需要**：

| 原任务 | v2 下 |
|---|---|
| M0-A RAW 内嵌预览探针 | **不需要** —— 解码在电脑上，手机只拿 JPEG |
| M0-B NAS WebDAV 能力探测 | **不需要** —— 不再有 WebDAV |
| `crates/imagefilter-core` 抽取 / UniFFI / NDK | **不需要** —— 手机端不再有 Rust |
| 边车命名纯函数搬给手机 | **不需要** —— 手机不碰边车格式 |

**唯一仍然有效的一条**：动工前先跑 `cargo test --lib` 把测试基线钉死——因为交接文档 §7 记
"26 活跃"、§9.1 记"66 活跃 + 3 忽略"，两处互相矛盾，不先钉死就没有"测试数一致"这条验收线。
这一条已并入 v2 的实施计划。

完整原文见 git 历史（提交 `8cbac68` 时的内容）。
