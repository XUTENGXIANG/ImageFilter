# 高清预览加载性能修复 · 实施计划

**Goal:** 降低安卓查看器「高清」的首屏等待，不改变画质契约（只降不升 / q88 / 方向只校正一次）。

**诊断：**
- 安卓高清 = `max=2048` + `full=true`（[ViewerState.kt:480](../../../android/app/src/main/kotlin/com/imagefilter/mobile/ui/ViewerState.kt#L480)）。
- 服务端 `preview_bytes` 对 `full=true` 直接 `get_full_image`；DNG 分支无条件全解码
  （[images.rs:212](../../../src-tauri/src/scanner/images.rs#L212)），完全不看内嵌预览。
- 非 DNG 也要内嵌预览长边 ≥3000 才走快路径（[images.rs:278](../../../src-tauri/src/scanner/images.rs#L278)），
  而手机只要 2048。
- 全解码 → 5000 缓存 → 再缩到 2048，**两次缩放**；预览/全图共用 `Semaphore(1)`
  （[media.rs:86](../../../src-tauri/src/server/media.rs#L86)），全解码会堵住下一张默认预览。

**Architecture:** 把「高清」定义为“在请求尺寸下取最好的可用源”：
内嵌预览够大就用内嵌预览（快），不够才全解码。全解码路径保留，但加计时日志；
缩放换 SIMD 实现。

## 任务 G1：请求感知的源选择

**Files:** `src-tauri/src/server/media.rs`

- [ ] 在 `preview_bytes` 的 `full=true` 分支，先 `get_preview_image`；用
      `image::image_dimensions` 读尺寸；若 `max(w,h) * 10 >= max * 9`（≥90% 请求尺寸），
      直接用这个源；否则回退 `get_full_image`。
- [ ] 把判断抽成纯函数并单测：
      `prefer_embedded(preview_max_edge, requested_max) -> bool`：
      2048 请求 + 1920 预览 = true；1600 = false；256 = false；非 full 不调用。
- [ ] 注意：**不放大**；输出长边仍 ≤ `max`；q88 编码器不动。
- [ ] 单测覆盖：小图（<max）不触发 resize 时字节仍与共享编码器一致；
      大图走新路径后尺寸正确。

## 任务 G2：计时日志（debug 构建）

**Files:** `src-tauri/src/server/media.rs`、`src-tauri/src/scanner/images.rs`

- [ ] `#[cfg(debug_assertions)]` 打一行：
      `[preview] path=… max=… full=… source=embedded|full dims=WxH open_ms=… encode_ms=… total_ms=… bytes=…`
- [ ] 在 `get_full_image` 的 DNG 分支给 `rawler / tinydng / WIC` 各打一行耗时，
      方便判断 50MB 线性 DNG 到底卡在哪一步。
- [ ] release 构建不输出（`#[cfg(debug_assertions)]`）。

## 任务 G3：SIMD 缩放（P1）

**Files:** `src-tauri/Cargo.toml`、`src-tauri/src/server/media.rs`

- [ ] 加 `fast_image_resize`（Apache-2.0，Rust SIMD；仓库 `Cykooz/fast_image_resize`）。
- [ ] 只在 `encode_preview` 需要缩放时用它（长边 > max）；不改变 `resize_max_edge`
      在桌面缩略图/全图缓存里的行为，避免影响桌面端输出。
- [ ] 保持 Lanczos3 语义；输出尺寸与 `resize_max_edge` 一致。
- [ ] 如果 crate 拉取失败或 API 不兼容，报告并跳过，不伪造。

## 任务 G4：闸门拆分（P2，可后置）

**Files:** `src-tauri/src/server/media.rs`

- [ ] `preview_sem=2`（内嵌预览/轻路径）、`full_sem=1`（全解码）；
      `MediaGate::full()`；`preview_bytes` 按 `full` 选闸门。
- [ ] 更新/新增闸门测试：一个 full 解码在飞时，preview 仍能拿到许可；
      缩略图不受影响。
- [ ] 如果测试改动过大，先不做，保留单闸门；在报告里说明。

## 验收

- `cargo test --lib`、`cargo test --test server_contract` 全绿。
- 用一张真实慢 DNG/RAW 跑一次（用户提供路径或桌面端复现），
  从 `[preview]` 日志确认：
  - 源是 `embedded` 还是 `full`；
  - open / encode / total 各多少 ms；
  - 如果仍是 `full`，DNG 分支哪一步最慢。
- 记录改动前后同一张图的 total_ms 与字节数。
- 不做安卓 UI 改动（高清文案另改）；不 commit。

## Agent G 派发

> 读本计划 + `src-tauri/src/server/media.rs`、`src-tauri/src/scanner/images.rs`、
> `src-tauri/Cargo.toml`、`src-tauri/tests/server_contract.rs`。
> 只改 `src-tauri/**`。按 G1→G4 顺序；测试保持全绿。报告文件、命令、结果、
> 计时日志样例、是否拉到 `fast_image_resize`、以及需要用户复现的下一步。
