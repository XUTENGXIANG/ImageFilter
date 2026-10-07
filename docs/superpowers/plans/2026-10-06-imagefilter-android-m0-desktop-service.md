# ImageFilter Mobile · M0′ 桌面端图库服务 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ImageFilter 桌面端内嵌一个 HTTP 图库服务（axum），让手机能列目录、取缩略图/预览、读写评分、移动照片——**全部逻辑复用既有实现，桌面端既有功能与测试不变**。

**Architecture:** 新增 `src-tauri/src/server/`（axum 路由 + Bearer 鉴权 + **共享根白名单** + 限流）与 `src-tauri/src/mover.rs`（移动照片+边车）。服务层**只做三件事**：校验路径在共享根内、把既有的 `#[tauri::command]` 函数当普通函数调用、把结果序列化/转成字节。**缩略图与预览的缓存逻辑一行都不重写**——三个图片 command 已经返回缓存文件路径（`images.rs:104/113/160`），服务层读到内存即可。

**Tech Stack:** Rust 2021 · axum 0.8（tokio 已是既有依赖）· `getrandom 0.2`（token）· 单测用 `tower::ServiceExt::oneshot`（不占端口、不引 HTTP 客户端）

## Global Constraints

- **桌面端基线**：`v1.1.2`。本计划**不发布**，不动版本号。每个任务的 BASE 以**它自己提交时的父提交**为准——执行期间会有并行的 docs 提交，**别把某个固定 SHA 当成永远的事实**（Task 1 就踩过：它的父提交是 `a1dcd60` 而不是开工时的 `b3ccad8`）。
- **命令一律在 `A:\tenent\src-tauri` 下执行** `cargo test --lib` / `cargo check --lib`。
- **`cargo test --lib` 每个任务后必须全绿**，且通过/忽略的测试数与基线**一致**。
  **基线已实测钉死：`passed = 67, failed = 0, ignored = 3`（共 70 个）**。Task 2 会加一个 `#[ignore]` 探针 → 届时 `ignored` 变 4 是**预期且唯一允许**的增量。
- **warning 的验收线是"相对基线无新增"，不是"零 warning"**：基线本身就有 1 个既存 warning —— `importer.rs:331` 的 `copy_one is never used`（它只被同文件里的测试调用，生产走 `copy_one_reporting`）。交接文档 §8 原先那句 `cargo check --lib # 应无警告` 是**错的**，已订正。
- **环境危险项：用户机器上可能装着并运行着正式版 ImageFilter**（`%LOCALAPPDATA%\ImageFilter\`）。它与开发构建**共用同一个 SQLite**（`%APPDATA%\com.imagefilter.app\image-filter.db`）。遇到莫名的 DB 锁 / 迁移失败，**先怀疑它**；但**绝不要擅自结束用户的进程**——那是用户的程序，不是我们的残留。
- **源码风格**：文件头两行 `// Copyright (c) 2026 XUTENGXIANG` + `// SPDX-License-Identifier: MIT`；注释与提交信息用**中文**；conventional commits。
- **改动限于规格 §6 列出的 11 个文件**。**禁止**动 `App.tsx` 布局、`viewer.tsx`、`index.css`、`analyzer.rs`、`importer.rs`、`db.rs`。
- **服务层不许另写一套**缩略图/预览/边车/扫描逻辑——只能调用既有函数。降采样与编码必须复用 `resize_max_edge`（Lanczos3，`images.rs:27`）与 `save_jpeg_quality` 拆出来的编码入口（`images.rs:58`）。
- **脚本按 PowerShell 7 写**，开头 `#Requires -Version 7`。
- 规格出处：[2026-10-06-imagefilter-android-design.md](../specs/2026-10-06-imagefilter-android-design.md) §5 / §6 / §10.2 / 附录 B。

---

## 范围说明：本计划只做 M0′

| | 内容 | 验收方式 | 计划 |
|---|---|---|---|
| **M0′**（本计划） | 桌面端 HTTP 图库服务：鉴权、共享根白名单、全部端点、移动、集成测试 | `cargo test` + `cargo test --test server_contract`（**不需要手机、不需要安卓工具链**） | 本文件 |
| M1′–M4′ | Android 工程、配对、网格、查看器、滑卡、移动、打磨 | 需要 JDK 17 + Android SDK + 真机 | 下个计划 |

理由：M0′ 完全落在可自动验证的范围，而它的第一个任务就要证掉"axum 能不能在 Tauri 进程里跑"这个**致命假设**——不成立的话后面全变。安卓侧工具链问题不该混进来淹没这个信号。

**先做 Task 1 与 Task 3**：前者证技术可行性，后者是安全红线。其余任务都建立在它们之上。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src-tauri/Cargo.toml` | 改 | +`axum = "0.8"`、+`getrandom = "0.2"`；dev-deps +`tower = { version = "0.5", features = ["util"] }` |
| `src-tauri/src/server/mod.rs` | 新建 | 服务生命周期（启停/绑定端口/状态）+ `ServerState` |
| `src-tauri/src/server/config.rs` | 新建 | `server-config.json` 的读写（enabled / port / token / shares） |
| `src-tauri/src/server/auth.rs` | 新建 | token 生成/轮换/常量时间比较 + Bearer 中间件 |
| `src-tauri/src/server/paths.rs` | 新建 | **共享根白名单** `ensure_within`（安全红线） |
| `src-tauri/src/server/media.rs` | 新建 | 缩略图/预览：调既有 command 拿缓存路径 → 读字节 / 降采样 |
| `src-tauri/src/server/routes.rs` | 新建 | 全部端点与错误码映射 |
| `src-tauri/src/mover.rs` | 新建 | 移动照片 + 同名边车；绝不覆盖；半成功标记 |
| `src-tauri/src/scanner/images.rs` | 改 | 三处 `pub(crate)` 放宽 + 拆出 `encode_jpeg_quality`（行为等价） |
| `src-tauri/src/lib.rs` | 改 | 注册服务启停命令、`setup` 里按配置决定是否拉起服务 |
| `src-tauri/tests/server_contract.rs` | 新建 | 集成测试：真 Router + `oneshot`，覆盖全部端点与错误分支 |
| `src/components/settings-dialog.tsx` | 改 | 服务面板（开关/地址/token/二维码/共享根/已连接/排查清单） |
| `src/useScanner.ts` | 改 | 订阅 `decisions-changed` → 复用既有 merge 函数 |
| `src/i18n/zh.ts` + `src/i18n/en.ts` | 改 | 新增文案，两边必须齐 |

---

### Task 1: 钉死测试基线 + 证明 axum 能在这个进程里跑

**为什么先做**：规格附录 B #2 是致命假设——axum 与 Tauri 既有 tokio 运行时若冲突，"内嵌服务"这条路就要换实现（独立 runtime 或换更小的 HTTP 库）。这一步用最小代价证掉它。

**Files:**
- Modify: `src-tauri/Cargo.toml`
- Create: `src-tauri/src/server/mod.rs`
- Modify: `src-tauri/src/lib.rs`（只加 `mod server;`，其它留到 Task 8）
- Modify: `docs/ImageFilter-交接与上手.md`（§7 第一段、§9.1 第一条）

**Interfaces:**
- Produces: `server::router() -> axum::Router`（只含 `/api/v1/ping`）；基线三元组 **BASELINE = (passed, failed, ignored)**

- [ ] **Step 1: 跑基线**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

把实际三个数字记作 **BASELINE**。首次会编译整个依赖树，可能几分钟。

- [ ] **Step 2: 订正交接文档里互相矛盾的测试数**

`docs/ImageFilter-交接与上手.md` 的 §7 "26 活跃 + 3 忽略" 与 §9.1 "66 活跃 + 3 忽略" 统一为实测值，并在 §8 代码块上方加一行：
`> 基线(<日期>)：<passed> 活跃 + <ignored> 忽略 —— 改动后必须与此一致。`

- [ ] **Step 3: 加依赖**

`src-tauri/Cargo.toml` 的 `[dependencies]` 里 `serde_json = "1"` 之后加：

```toml
# 图库服务（手机端访问）。只加 HTTP 框架与随机数, 不引 HTTP 客户端。
axum = "0.8"
getrandom = "0.2"
```

文件末尾加：

```toml
[dev-dependencies]
# 契约测试直接打 Router, 不占端口、不引 HTTP 客户端
tower = { version = "0.5", features = ["util"] }
```

- [ ] **Step 4: 写最小服务与它的测试（TDD：先写测试）**

创建 `src-tauri/src/server/mod.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 手机端访问的图库服务。
//!
//! 三条纪律（规格 §3.2）:
//!   1. 服务层只做"校验路径 → 调既有函数 → 序列化", 不许另写一套缩略图/预览/边车逻辑;
//!   2. 所有路径必须过 `paths::ensure_within`（共享根白名单）, 没有例外;
//!   3. 不许绕过既有的并发闸门（交接文档 §4.2）。

use axum::{routing::get, Json, Router};
use serde_json::json;

/// 组装路由。Task 4–8 会往这里挂更多端点。
pub fn router() -> Router {
    Router::new().route("/api/v1/ping", get(ping))
}

/// 配对与"测试连接"用。**不泄露任何路径信息**。
async fn ping() -> Json<serde_json::Value> {
    Json(json!({
        "name": "ImageFilter",
        "version": env!("CARGO_PKG_VERSION"),
        "requiresToken": true,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    #[tokio::test]
    async fn ping_returns_product_and_version_without_token() {
        let res = router()
            .oneshot(Request::builder().uri("/api/v1/ping").body(Body::empty()).unwrap())
            .await
            .expect("router 应能处理请求");
        assert_eq!(res.status(), StatusCode::OK);

        let body = axum::body::to_bytes(res.into_body(), 64 * 1024).await.unwrap();
        let v: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(v["name"], "ImageFilter");
        assert_eq!(v["requiresToken"], true);
        // 不泄露路径: 整个响应里不该出现盘符或反斜杠
        assert!(!String::from_utf8_lossy(&body).contains('\\'));
    }
}
```

在 `src-tauri/src/lib.rs` 的 `mod scanner;` 一行附近加 `mod server;`（其余接线留到 Task 8）。

- [ ] **Step 5: 跑测试，确认基线只多 1 个且全绿**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: `passed` = BASELINE.passed + 1，`failed = 0`，`ignored` 不变。

- [ ] **Step 6: 真机（本机）验证"服务能在 Tauri 进程里起来"**

这一步**不能用单测替代**，因为要证的是"在 Tauri 的进程与运行时里"。

临时在 `lib.rs` 的 `setup` 闭包里加：

```rust
// 临时验证用（Task 8 会换成正式的服务生命周期）
tauri::async_runtime::spawn(async move {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:8765").await.unwrap();
    println!("[devserve] listening on 127.0.0.1:8765");
    axum::serve(listener, server::router()).await.unwrap();
});
```

```powershell
cd A:\tenent
npx tauri dev
# 另开一个终端（PowerShell 7）:
curl.exe -s http://127.0.0.1:8765/api/v1/ping
```

Expected: 打印 `{"name":"ImageFilter","version":"1.1.2","requiresToken":true}`，且应用窗口正常可用、无运行时 panic。

**关键判断**：若这里 panic 或窗口起不来 → 规格附录 B #2 不成立，**停下来改设计**（改用独立 runtime 或换 `tiny_http`），不要继续往下做。

- [ ] **Step 7: 撤掉临时验证代码**

删掉 Step 6 加的那段（Task 8 会以正式形态加回来）。确认 `git diff src-tauri/src/lib.rs` 只剩 `mod server;` 一行。

- [ ] **Step 8: Commit**

```powershell
cd A:\tenent
git add src-tauri/Cargo.toml src-tauri/Cargo.lock src-tauri/src/lib.rs src-tauri/src/server/mod.rs docs/ImageFilter-交接与上手.md
git commit -m "feat(server): 内嵌 axum 图库服务骨架 + /ping, 并证其与 Tauri 运行时共存"
```

---

### Task 2: RAW 内嵌预览分辨率分布探针（画质上界）

**为什么必须有**：规格 §5.2 写明"默认预览的画质上界 = 相机塞进 RAW 的内嵌 JPEG 的分辨率"，并特别点出 Sony ARW 常见只有 1616×1080。这个探针把"猜"变成"实测"，且**不需要手机**。

**Files:**
- Modify: `src-tauri/src/scanner/images.rs`（`mod tests` 末尾追加 `#[ignore]` 探针）
- Create: `docs/evidence/raw-embedded-preview/REPORT.txt`

**Interfaces:**
- Consumes: 同文件内的 `extract_largest_preview_full(&Path) -> Option<(u32, u32, Vec<u8>)>`
- Produces: `REPORT.txt` 含每个扩展名的分辨率与"是否有 ≥2048px 的内嵌预览"结论

- [ ] **Step 1: 追加探针**

在 `src-tauri/src/scanner/images.rs` 的 `mod tests { … }` 末尾追加：

```rust
    /// 探针(M0′, 默认忽略): RAW **内嵌预览的分辨率分布**。
    ///
    /// 它回答的不是"手机能不能读 RAW"（解码在电脑上），而是
    /// **"手机看到的预览有多大"** —— 即画质上界。规格 §5.2 明示:
    /// 默认预览的画质上界就是这张内嵌 JPEG, 不是 RAW 的画质。
    /// Sony ARW 的最大内嵌预览常见仅 1616×1080, 放大到 1:1 验对焦是不够的。
    ///
    /// 运行: $env:IMAGEFILTER_PROBE_DIR="D:\样张"; cargo test --lib probe_embedded_preview -- --ignored --nocapture
    #[test]
    #[ignore = "M0' probe — 需要真实 RAW 样张目录"]
    fn probe_embedded_preview() {
        let dir = match std::env::var("IMAGEFILTER_PROBE_DIR") {
            Ok(v) if !v.is_empty() => v,
            _ => {
                println!("未设置 IMAGEFILTER_PROBE_DIR, 跳过");
                return;
            }
        };

        // 按扩展名汇总: (张数, 命中数, 最大长边, 长边>=2048 的张数)
        let mut by_ext: std::collections::BTreeMap<String, (usize, usize, u32, usize)> =
            std::collections::BTreeMap::new();
        println!("{:<32} {:>7} {:>11} {:>8} {:>6}", "file", "MB", "embedded", "jpegMB", "ms");

        for entry in walkdir::WalkDir::new(&dir).into_iter().filter_map(|e| e.ok()) {
            if !entry.file_type().is_file() {
                continue;
            }
            let p = entry.path();
            let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
            if !crate::scanner::RAW_EXTENSIONS.contains(&ext.as_str()) {
                continue;
            }
            let size = std::fs::metadata(p).map(|m| m.len()).unwrap_or(0);
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            let t0 = std::time::Instant::now();
            let got = extract_largest_preview_full(p);
            let ms = t0.elapsed().as_millis();

            let slot = by_ext.entry(ext).or_insert((0, 0, 0, 0));
            slot.0 += 1;
            match got {
                Some((w, h, bytes)) => {
                    let long = w.max(h);
                    // 两条**与样本集无关**的真断言: 它们不判断"这个格式支不支持内嵌预览",
                    // 只判断"扫描逻辑有没有坏"。所以这个探针既保留了测量用途, 又能在
                    // 扫描回归时自己失败 —— 而不是打印一屏 MISS 让人以为是样张问题。
                    // （没有断言的话, 它在评审规则里就是"断言为空的测试", 属于缺陷。）
                    assert!(
                        bytes.len() >= 4 && bytes[0] == 0xFF && bytes[1] == 0xD8,
                        "{} 提取出的东西不是 JPEG(缺 SOI) —— 扫描逻辑出了问题", name
                    );
                    assert!(w > 0 && h > 0, "{} 解析出非法尺寸 {}x{}", name, w, h);
                    slot.1 += 1;
                    slot.2 = slot.2.max(long);
                    if long >= 2048 {
                        slot.3 += 1;
                    }
                    println!(
                        "{:<32} {:>7.1} {:>11} {:>8.2} {:>6}",
                        name, size as f64 / 1_048_576.0,
                        format!("{}x{}", w, h),
                        bytes.len() as f64 / 1_048_576.0, ms
                    );
                }
                None => println!(
                    "{:<32} {:>7.1} {:>11} {:>8} {:>6}",
                    name, size as f64 / 1_048_576.0, "MISS", "-", ms
                ),
            }
        }

        // 目录给错了、或样本扩展名不在 RAW_EXTENSIONS 里时, 下面那张表会全空 ——
        // 与其让人对着空表猜, 不如直接失败。
        assert!(
            !by_ext.is_empty(),
            "IMAGEFILTER_PROBE_DIR={} 下没找到任何 RAW 文件 —— 目录给错了, 或样本扩展名不在 RAW_EXTENSIONS 里",
            dir
        );

        println!("\n按扩展名汇总:");
        println!("{:<8} {:>6} {:>6} {:>10} {:>12}", "ext", "总数", "命中", "最大长边", ">=2048 张数");
        for (ext, (n, hit, maxlong, big)) in &by_ext {
            println!("{:<8} {:>6} {:>6} {:>10} {:>12}", ext, n, hit, maxlong, big);
        }
        println!(
            "\n结论提示: 若某扩展名的「最大长边」或「>=2048 张数」偏低, 该格式在手机上放大到 1:1 会不够看, 需要走 full=1。"
        );
    }
```

- [ ] **Step 2: 确认它默认不跑，且 ignored 只 +1**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: `passed` = BASELINE.passed + 1（Task 1 的 ping 测试），`ignored` = BASELINE.ignored + 1。

- [ ] **Step 3: 问用户要样张目录**

**这一步需要用户提供信息。** 问：本机哪个目录放着真实拍过的 RAW（**各机型各来几张最好**：ARW / CR3 / RAF / NEF / DNG）。不要用应用缓存里的解码产物当样本。

- [ ] **Step 4: 跑探针并落证据**

```powershell
cd A:\tenent\src-tauri
$env:IMAGEFILTER_PROBE_DIR = "<用户给的目录>"
cargo test --lib probe_embedded_preview -- --ignored --nocapture 2>&1 |
  Tee-Object -FilePath ..\docs\evidence\raw-embedded-preview\REPORT.txt
```

- [ ] **Step 5: 在 REPORT.txt 顶部写结论（三行以内）**

```
# M0′ RAW 内嵌预览分辨率分布 · <日期> · 样张来源 <目录>
结论: <按扩展名列出「最大长边」；标出哪些格式普遍 >=2048>
影响: 长边 < 2048 的格式在手机上放大到 1:1 会不够看 → 这些格式的「高清查看」必须走 full=1
```

- [ ] **Step 6: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/scanner/images.rs docs/evidence/raw-embedded-preview/REPORT.txt
git commit -m "test(probe): RAW 内嵌预览分辨率分布探针(M0'), 用于确定手机端预览画质上界"
```

---

### Task 3: 共享根白名单 + Bearer 鉴权（安全红线）

**为什么单独成任务**：没有白名单，任何拿到 token 的人就能读整块磁盘；这是本设计**唯一**的严重安全面。必须 TDD，且测试要覆盖 `..`、前缀相似目录、符号链接三种逃逸。

**Files:**
- Create: `src-tauri/src/server/paths.rs`
- Create: `src-tauri/src/server/auth.rs`
- Create: `src-tauri/src/server/config.rs`
- Modify: `src-tauri/src/server/mod.rs`（挂上 `mod` 声明）
- Create: `src-tauri/tests/server_contract.rs`（本任务先建文件并放白名单测试）

**Interfaces:**
- Produces:
  - `paths::ensure_within(shares: &[PathBuf], requested: &Path) -> Result<PathBuf, ApiError>`
  - `auth::new_token() -> String`、`auth::eq_constant_time(a: &str, b: &str) -> bool`
  - `config::ServerConfig { enabled: bool, port: u16, token: String, shares: Vec<PathBuf> }` + `load(dir)` / `save(dir)`
  - `ApiError` 枚举（Task 4 起被所有端点使用）

- [ ] **Step 1: 先写会失败的测试**

创建 `src-tauri/tests/server_contract.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 图库服务的契约测试。直接打 Router（`tower::ServiceExt::oneshot`），
//! 不占端口、不引 HTTP 客户端 —— 快且不会因端口占用而闪断。
// 这里**不要**写 `use std::path::PathBuf;` —— 路径类型一律用全限定名, 否则会产生
// unused import 警告, 与"相对基线无新增 warning"直接冲突(Task 3 实测踩到)。

#[test]
fn ensure_within_accepts_paths_inside_a_share() {
    let root = std::env::temp_dir().join(format!("ifshare_ok_{}", std::process::id()));
    std::fs::create_dir_all(root.join("sub")).unwrap();
    let f = root.join("sub").join("a.jpg");
    std::fs::write(&f, b"x").unwrap();

    let got = image_filter_lib::server::paths::ensure_within(&[root.clone()], &f).unwrap();
    assert_eq!(got, f.canonicalize().unwrap());
    std::fs::remove_dir_all(&root).ok();
}

#[test]
fn ensure_within_rejects_parent_escape() {
    let root = std::env::temp_dir().join(format!("ifshare_up_{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let outside = root.parent().unwrap().join("ifshare_secret.txt");
    std::fs::write(&outside, b"secret").unwrap();

    // root/../ifshare_secret.txt 在字符串前缀上"看起来"在 root 附近, 必须拒绝
    let sneaky = root.join("..").join("ifshare_secret.txt");
    assert!(
        image_filter_lib::server::paths::ensure_within(&[root.clone()], &sneaky).is_err(),
        ".. 逃逸必须被拒绝"
    );
    std::fs::remove_dir_all(&root).ok();
    std::fs::remove_file(&outside).ok();
}

#[test]
fn ensure_within_rejects_prefix_lookalike_directory() {
    let base = std::env::temp_dir().join(format!("ifshare_pre_{}", std::process::id()));
    let root = base.join("photos");
    let lookalike = base.join("photos2"); // 字符串前缀相同, 但 Path::starts_with 按组件比较
    // **共享根必须真的存在**。最初的版本只建了 photos2, 于是 root.canonicalize() 失败 →
    // 走 `continue` 跳过 → 那次拒绝其实来自"共享根不存在", 而不是"按组件比较"。
    // 那种写法把 starts_with 换成字符串前缀也照样绿 —— 测试因错误的原因通过。
    // (实测确认于提交 cf05524)
    std::fs::create_dir_all(&root).unwrap();
    std::fs::create_dir_all(&lookalike).unwrap();
    let f = lookalike.join("a.jpg");
    std::fs::write(&f, b"x").unwrap();

    assert!(
        image_filter_lib::server::paths::ensure_within(&[root], &f).is_err(),
        "photos2 不是 photos 的子目录, 必须拒绝(字符串前缀写法会在这里出错)"
    );
    std::fs::remove_dir_all(&base).ok();
}

> **以文件为准**：提交进去的 `src-tauri/tests/server_contract.rs` 比上面这个块**多 3 条** —— `ensure_within_rejects_an_existing_path_outside_every_share`、`ensure_within_survives_a_missing_share_root`，以及 lookalike 那条里的**正对照断言**（同一共享根内的文件必须被接受）。**本块与文件不一致时，以文件为准**（见提交 `cf05524`）；不要照本块重写文件，否则会把那 3 条覆盖掉。

#[cfg(target_os = "windows")]
#[test]
fn ensure_within_rejects_symlink_escape() {
    let base = std::env::temp_dir().join(format!("ifshare_ln_{}", std::process::id()));
    let root = base.join("photos");
    let outside = base.join("outside");
    std::fs::create_dir_all(&root).unwrap();
    std::fs::create_dir_all(&outside).unwrap();
    std::fs::write(outside.join("a.jpg"), b"x").unwrap();

    // Windows 上创建符号链接需要开发者模式或管理员权限; 拿不到就跳过(不能因此让测试失败)
    if std::os::windows::fs::symlink_dir(&outside, root.join("link")).is_err() {
        println!("跳过: 本机无创建符号链接的权限");
        std::fs::remove_dir_all(&base).ok();
        return;
    }

    let via_link = root.join("link").join("a.jpg");
    assert!(
        image_filter_lib::server::paths::ensure_within(&[root.clone()], &via_link).is_err(),
        "符号链接逃逸必须被拒绝(canonicalize 后已不在共享根内)"
    );
    std::fs::remove_dir_all(&base).ok();
}

// 注意: 这里**不**放"token 是 64 位 hex / 常量时间比较"的测试。
// 鉴权原语的单测放在 auth.rs 自己的 `mod tests` 里（见 Step 4）—— 同一段断言在两个
// 文件里各写一份，在评审规则里就是"逐字重复的逻辑块"，属于缺陷。
// 集成测试只负责"必须用真实文件系统才能验"的那部分：越界判定。
```

**注意**：这个集成测试要求 lib 是 `image_filter_lib`（`Cargo.toml` 里已是这个 `[lib] name` ✓），并且 `server` 模块与 `paths`/`auth` 子模块必须是 `pub`。

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract
```

Expected: 编译失败，`could not find server in image_filter_lib` / `no function ensure_within`。

- [ ] **Step 3: 实现 `paths.rs`**

创建 `src-tauri/src/server/paths.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! **共享根白名单** —— 本设计唯一的安全红线。
//!
//! 服务默认关闭、token 强制, 但这些还不够: 没有白名单, 任何拿到 token 的人
//! 就能读整块磁盘。所有端点(含缩略图/预览/移动)都必须先过 `ensure_within`。

use std::path::{Path, PathBuf};

/// 路径必须落在某个共享根**之内**。返回 canonicalize 后的路径。
///
/// 两个必须记住的点:
///   1. **先 canonicalize 再比**: 否则 `root/../secret` 与符号链接都能逃逸;
///   2. **用 `Path::starts_with` 而不是字符串前缀**: 后者会让 `D:\photos2`
///      被当成 `D:\photos` 的子目录（`starts_with` 按路径组件比较, 不会）。
pub fn ensure_within(shares: &[PathBuf], requested: &Path) -> Result<PathBuf, super::ApiError> {
    let canon = requested
        .canonicalize()
        .map_err(|_| super::ApiError::NotFound)?;

    for root in shares {
        let Ok(root_canon) = root.canonicalize() else {
            continue; // 共享根自己不存在(盘没插/被删) → 跳过而不是整体报错
        };
        if canon == root_canon || canon.starts_with(&root_canon) {
            return Ok(canon);
        }
    }
    Err(super::ApiError::ForbiddenPath)
}
```

- [ ] **Step 4: 实现 `auth.rs`**

创建 `src-tauri/src/server/auth.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 配对 token 的生成与校验。
//!
//! 刻意不引 base64/hex/rand 之外的依赖: token 就是 32 字节随机数的十六进制表示,
//! 手写 hex 只需要 5 行, 不值得为它加一个 crate。

/// 生成 32 字节随机 token（64 个十六进制字符）。
pub fn new_token() -> String {
    let mut buf = [0u8; 32];
    getrandom::getrandom(&mut buf).expect("系统随机数不可用");
    let mut s = String::with_capacity(64);
    for b in buf {
        use std::fmt::Write as _;
        let _ = write!(s, "{:02x}", b);
    }
    s
}

/// 常量时间比较。长度不同直接返回 false —— token 长度是固定且公开的, 不算泄露。
pub fn eq_constant_time(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_shape_and_compare() {
        let t = new_token();
        assert_eq!(t.len(), 64);
        assert!(t.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(eq_constant_time(&t, &t));
        assert!(!eq_constant_time(&t, &new_token()));
        assert!(!eq_constant_time(&t, ""));
    }
}
```

- [ ] **Step 5: 实现 `config.rs`**

创建 `src-tauri/src/server/config.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 服务配置持久化: `<app_data_dir>/server-config.json`。
//!
//! 刻意**不落进 SQLite**（`db.rs`）: 那会动到与本案无关的文件,
//! 而这里只有四个字段, 一个 JSON 文件足够。

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const CONFIG_FILE: &str = "server-config.json";
/// 默认端口。被占用时由服务层自增（见 `mod.rs`）。
pub const DEFAULT_PORT: u16 = 8765;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerConfig {
    /// **默认 false**: 服务必须由用户显式开启。规格 §4.3/§11。
    pub enabled: bool,
    pub port: u16,
    pub token: String,
    /// 共享根白名单。**空 = 谁都访问不了**（这是安全的默认值, 不是 bug）。
    pub shares: Vec<PathBuf>,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self { enabled: false, port: DEFAULT_PORT, token: super::auth::new_token(), shares: Vec::new() }
    }
}

impl ServerConfig {
    pub fn load(app_data_dir: &Path) -> Self {
        let p = app_data_dir.join(CONFIG_FILE);
        std::fs::read_to_string(&p)
            .ok()
            .and_then(|s| serde_json::from_str::<Self>(&s).ok())
            .filter(|c| c.token.len() == 64) // token 坏了就重新生成, 不要让服务起不来
            .unwrap_or_default()
    }

    pub fn save(&self, app_data_dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(app_data_dir).map_err(|e| e.to_string())?;
        let s = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(app_data_dir.join(CONFIG_FILE), s).map_err(|e| e.to_string())
    }
}
```

- [ ] **Step 6: 在 `mod.rs` 里挂上子模块、`ApiError`、并把 `server` 声明为 `pub`**

在 Task 1 的 `src-tauri/src/server/mod.rs` 上**合并**成下面这样。

⚠️ **注意这是"追加"不是"重写"**：Task 1 的 `ping` 函数与它的测试 `ping_returns_product_and_version_without_token` **必须原样保留**——Task 3 Step 8 的验收线要求它还在。若把它删了，`passed` 会比预期少 1，属于本任务失败。

```rust
pub mod auth;
pub mod config;
pub mod paths;

use axum::{http::StatusCode, response::{IntoResponse, Response}, routing::get, Json, Router};
use serde_json::json;

/// 闭集错误码（规格 §5.3）。**每条都必须落到"用户下一步做什么"**。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApiError {
    Unauthorized,
    ForbiddenPath,
    NotFound,
    Conflict,
    Busy,
    DiskFull,
    TooLarge,
    NotXmp,
    UnsupportedForm,
    BadRequest,
    Internal,
}

impl ApiError {
    pub fn code(self) -> &'static str {
        match self {
            ApiError::Unauthorized => "unauthorized",
            ApiError::ForbiddenPath => "forbiddenPath",
            ApiError::NotFound => "notFound",
            ApiError::Conflict => "conflict",
            ApiError::Busy => "busy",
            ApiError::DiskFull => "diskFull",
            ApiError::TooLarge => "tooLarge",
            ApiError::NotXmp => "notXmp",
            ApiError::UnsupportedForm => "unsupportedForm",
            ApiError::BadRequest => "badRequest",
            ApiError::Internal => "internal",
        }
    }

    fn status(self) -> StatusCode {
        match self {
            ApiError::Unauthorized => StatusCode::UNAUTHORIZED,
            ApiError::ForbiddenPath => StatusCode::FORBIDDEN,
            ApiError::NotFound => StatusCode::NOT_FOUND,
            ApiError::Conflict => StatusCode::CONFLICT,
            ApiError::Busy => StatusCode::LOCKED,
            ApiError::DiskFull => StatusCode::INSUFFICIENT_STORAGE,
            ApiError::TooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            ApiError::NotXmp | ApiError::UnsupportedForm => StatusCode::UNPROCESSABLE_ENTITY,
            ApiError::BadRequest => StatusCode::BAD_REQUEST,
            ApiError::Internal => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
}

/// 把既有的 XMP 闭集错误码映射到 HTTP 层 —— 实现见 Step 7。
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = Json(json!({ "code": self.code() }));
        (self.status(), body).into_response()
    }
}

/// 对外沿用 `server::ServerConfig` 这个名字（集成测试与 `into_state` 都用它）。
pub use config::ServerConfig;

/// 组装路由。Task 4–8 会往这里挂更多端点。
pub fn router() -> Router {
    Router::new().route("/api/v1/ping", get(ping))
}

/// 配对与"测试连接"用。**不泄露任何路径信息**。
async fn ping() -> Json<serde_json::Value> {
    Json(json!({
        "name": "ImageFilter",
        "version": env!("CARGO_PKG_VERSION"),
        "requiresToken": true,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    // Task 1 的 ping_returns_product_and_version_without_token 必须**保留**在本模块里,
    // 不要因为合并文件而删掉（Task 3 Step 8 的验收线要求它还在）。

    #[test]
    fn every_error_code_is_unique_and_nonempty() {
        let all = [
            ApiError::Unauthorized, ApiError::ForbiddenPath, ApiError::NotFound,
            ApiError::Conflict, ApiError::Busy, ApiError::DiskFull, ApiError::TooLarge,
            ApiError::NotXmp, ApiError::UnsupportedForm, ApiError::BadRequest, ApiError::Internal,
        ];
        let mut codes: Vec<&str> = all.iter().map(|e| e.code()).collect();
        codes.sort_unstable();
        let n = codes.len();
        codes.dedup();
        assert_eq!(codes.len(), n, "错误码必须互不重复");
        assert!(codes.iter().all(|c| !c.is_empty()));
    }
}
```

并把 `src-tauri/src/lib.rs` 里的 `mod server;` 改成 `pub mod server;`（集成测试要用）。

- [ ] **Step 7: 正确实现 `XmpError → ApiError` 的映射**

在 `src-tauri/src/server/mod.rs` 的 `impl ApiError` 之后加（**不要**用 Step 6 里那行占位）：

```rust
/// 把既有的 XMP 闭集错误码映射到 HTTP 层。**码值原样对应**, 前端只需认一套码。
impl From<crate::xmp::XmpError> for ApiError {
    fn from(e: crate::xmp::XmpError) -> Self {
        use crate::xmp::XmpError::*;
        match e {
            NotXmp => ApiError::NotXmp,
            UnsupportedForm => ApiError::UnsupportedForm,
            TooLarge => ApiError::TooLarge,
            DiskFull => ApiError::DiskFull,
            Busy => ApiError::Busy,
            // 其余(WriteProtect/ReadOnly/Permission/Encoding/PathTooLong/Unknown)
            // 对手机端都表现为"这个位置写不了" → 归到 Internal 会让用户无从下手,
            // 因此统一映射为 Conflict: "换位置/改名/看详情" 是用户能做的动作。
            _ => ApiError::Conflict,
        }
    }
}
```

- [ ] **Step 8: 跑测试，全绿**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result:"
```

Expected:
- `cargo test --lib`：BASELINE + Task 1 的 ping 测试 + `auth::tests::token_shape_and_compare` + `every_error_code_is_unique_and_nonempty`
- `cargo test --test server_contract`：5 个测试全过（符号链接那个在无权限时会打印跳过但**仍算通过**）

- [ ] **Step 9: 手工确认三条逃逸都真的被拒**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract ensure_within -- --nocapture
```

Expected: 4 个 `ensure_within_*` 测试通过；符号链接那条要么通过要么打印跳过。

- [ ] **Step 10: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server src-tauri/src/lib.rs src-tauri/tests/server_contract.rs
git commit -m "feat(server): 共享根白名单 + Bearer token 与闭集错误码(含 .. / 前缀相似 / 符号链接三种逃逸测试)"
```

---

### Task 4: 缩略图与预览端点（含降采样、方向规则、缓存）

**这是画质契约的落地处**，也是唯一新增 CPU 活的地方。两个必须记住的坑：
1. **不许另写编码**：把 `save_jpeg_quality`（`images.rs:58`）拆出写任意 `Write` 的版本，服务端编进内存。
2. **方向只能上一次**：`get_preview_image` 对 RAW 已经在缓存里物理校正过方向（`images.rs:145-147`）；非 RAW 它返回**原图路径**（没校正）。服务端要按"源路径是否就是请求路径"来判断，否则会出现转 90°（重复旋转）。

**Files:**
- Modify: `src-tauri/src/scanner/images.rs`（3 处 `pub(crate)` + 拆 `encode_jpeg_quality`）
- Create: `src-tauri/src/server/media.rs`
- Modify: `src-tauri/src/server/mod.rs`（挂 `pub mod media;`）
- Modify: `src-tauri/tests/server_contract.rs`（加 media 测试）

**Interfaces:**
- Consumes: `images::get_thumbnail_path`、`get_preview_image`、`get_full_image`（都返回**缓存文件路径**的 `#[tauri::command]`，可直接当普通 async fn 调）
- Produces:
  - `images::encode_jpeg_quality<W: std::io::Write>(img: &DynamicImage, w: W, quality: u8) -> Result<(), String>`
  - `images::resize_max_edge` / `images::apply_exif_orientation` 变为 `pub(crate)`
  - `media::thumbnail_bytes(path: &str, size: u32) -> Result<Vec<u8>, ApiError>`
  - `media::preview_bytes(path: &str, max: u32, full: bool) -> Result<Vec<u8>, ApiError>`

- [ ] **Step 1: 先写会失败的测试**

在 `src-tauri/tests/server_contract.rs` 末尾追加：

```rust
/// 造一张真 JPEG（指定尺寸），并可选地写入 EXIF Orientation=6（旋转 90°）。
fn write_test_jpeg(path: &std::path::Path, w: u32, h: u32, orientation: Option<u16>) {
    let img = image::RgbImage::from_fn(w, h, |x, y| image::Rgb([(x % 256) as u8, (y % 256) as u8, 90]));
    let mut buf: Vec<u8> = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut buf, 90)
        .encode_image(&img)
        .unwrap();
    // 简易 EXIF(仅 Orientation): APP1 段
    if let Some(o) = orientation {
        let mut app1: Vec<u8> = Vec::new();
        app1.extend_from_slice(b"Exif\0\0");
        app1.extend_from_slice(b"MM\x00\x2a"); // 大端 TIFF
        app1.extend_from_slice(&8u32.to_be_bytes()); // IFD0 偏移
        app1.extend_from_slice(&1u16.to_be_bytes()); // 1 个条目
        app1.extend_from_slice(&0x0112u16.to_be_bytes()); // Orientation
        app1.extend_from_slice(&3u16.to_be_bytes()); // SHORT
        app1.extend_from_slice(&1u32.to_be_bytes()); // count
        app1.extend_from_slice(&o.to_be_bytes());
        app1.extend_from_slice(&0u16.to_be_bytes()); // padding 到 4 字节
        app1.extend_from_slice(&0u32.to_be_bytes()); // next IFD = 0
        let mut out = vec![0xFFu8, 0xD8];
        out.extend_from_slice(&[0xFF, 0xE1]);
        out.extend_from_slice(&((app1.len() + 2) as u16).to_be_bytes());
        out.extend_from_slice(&app1);
        out.extend_from_slice(&buf[2..]); // 去掉 SOI 后拼接
        buf = out;
    }
    std::fs::write(path, &buf).unwrap();
}

#[test]
fn preview_downsamples_to_max_and_never_upscales() {
    let dir = std::env::temp_dir().join(format!("ifmedia_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let big = dir.join("big.jpg");
    write_test_jpeg(&big, 4000, 3000, None);
    let small = dir.join("small.jpg");
    write_test_jpeg(&small, 800, 600, None);

    let got = image_filter_lib::server::media::preview_bytes(big.to_str().unwrap(), 2048, false).unwrap();
    let (w, h) = image_dimensions_from_bytes(&got).unwrap();
    assert_eq!(w.max(h), 2048, "超限必须降到 max");
    assert_eq!((w, h), (2048, 1536), "必须保持宽高比");

    let got2 = image_filter_lib::server::media::preview_bytes(small.to_str().unwrap(), 2048, false).unwrap();
    let (w2, h2) = image_dimensions_from_bytes(&got2).unwrap();
    assert_eq!((w2, h2), (800, 600), "小于 max 时绝不放大");

    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn preview_applies_exif_orientation_exactly_once() {
    let dir = std::env::temp_dir().join(format!("iforient_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join("rot.jpg");
    write_test_jpeg(&p, 1000, 500, Some(6)); // Orientation=6 → 应旋转成 500x1000

    let a = image_filter_lib::server::media::preview_bytes(p.to_str().unwrap(), 2048, false).unwrap();
    let (w1, h1) = image_dimensions_from_bytes(&a).unwrap();
    assert_eq!((w1, h1), (500, 1000), "Orientation=6 必须让宽高互换(只转一次)");

    // 再请求一次（走同一条路径）: 结果必须完全一致, 证明没有累积旋转
    let b = image_filter_lib::server::media::preview_bytes(p.to_str().unwrap(), 2048, false).unwrap();
    let (w2, h2) = image_dimensions_from_bytes(&b).unwrap();
    assert_eq!((w1, h1), (w2, h2), "重复请求不能再次旋转");

    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn thumbnail_returns_jpeg_bytes() {
    let dir = std::env::temp_dir().join(format!("ifthumb_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join("t.jpg");
    write_test_jpeg(&p, 2000, 1500, None);

    let bytes = image_filter_lib::server::media::thumbnail_bytes(p.to_str().unwrap(), 300).unwrap();
    assert_eq!(&bytes[..2], &[0xFF, 0xD8], "必须是 JPEG(SOI)");
    let (w, h) = image_dimensions_from_bytes(&bytes).unwrap();
    assert!(w.max(h) <= 300, "缩略图长边不得超过请求的 size");

    std::fs::remove_dir_all(&dir).ok();
}
```

> **关于尺寸断言**：当前 `image` 版本没有"从字节读尺寸"的公开 API。请在测试文件顶部加一个本地助手，后面所有断言都用它（**不要**写 `image::image_dimensions_from_bytes`，那会编译不过）：

```rust
/// 测试专用: 从字节解出宽高（测试里不在乎这次解码开销）。
fn image_dimensions_from_bytes(b: &[u8]) -> Option<(u32, u32)> {
    image::load_from_memory(b).ok().map(|i| i.dimensions())
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract preview_ 2>&1 | Select-String -Pattern "error|test result"
```

Expected: 编译失败（`server::media` 不存在）。

- [ ] **Step 3: `images.rs` 的三处放宽与编码拆分**

在 `src-tauri/src/scanner/images.rs`：

1. `fn resize_max_edge(...)` → `pub(crate) fn resize_max_edge(...)`
2. `fn apply_exif_orientation(...)` → `pub(crate) fn apply_exif_orientation(...)`
3. 把 `save_jpeg_quality` 拆成两半（**行为等价**：原来怎么写现在还怎么写）：

```rust
fn save_jpeg_quality(
    img: &image::DynamicImage,
    path: &std::path::Path,
    quality: u8,
) -> Result<(), String> {
    let file = std::fs::File::create(path).map_err(|e| format!("Create: {}", e))?;
    encode_jpeg_quality(img, file, quality)
}

/// 把 JPEG 编进任意 `Write`（服务端要编进内存, 而不是落盘）。
/// 抽出来的目的只有一个: **编码参数只允许有一处**——否则服务端另写一套,
/// 就会出现"手机上的预览比电脑上的缩略图还糙"这种找不到原因的差异。
pub(crate) fn encode_jpeg_quality<W: std::io::Write>(
    img: &image::DynamicImage,
    w: W,
    quality: u8,
) -> Result<(), String> {
    let encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(w, quality);
    img.write_with_encoder(encoder).map_err(|e| format!("Encode: {}", e))
}
```

- [ ] **Step 4: 实现 `media.rs`**

创建 `src-tauri/src/server/media.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 缩略图与预览的字节化。
//!
//! 缓存逻辑**一行都不重写**: `get_thumbnail_path` / `get_preview_image` /
//! `get_full_image` 已经返回缓存文件路径（`images.rs:104/113/160`），
//! 这里只负责"读字节"或"读字节 + 降采样"。
//!
//! 画质契约(规格 §5.2): Lanczos3(`resize_max_edge`) + 显式 q88(`encode_jpeg_quality`)。

use crate::server::ApiError;
use std::path::Path;

/// 服务端预览的编码质量。**必须是显式值** —— `img.save()` 会走 JPEG 默认质量 ≈75。
const PREVIEW_JPEG_QUALITY: u8 = 88;

/// 缩略图字节。直接复用桌面端的 `thumbnails_v2` 缓存。
pub async fn thumbnail_bytes(path: &str, size: u32) -> Result<Vec<u8>, ApiError> {
    let size = size.clamp(64, 1024); // 防止手机要一张 20000px 的"缩略图"
    let cached = crate::scanner::images::get_thumbnail_path(path.to_string(), size)
        .await
        .map_err(|_| ApiError::NotFound)?;
    tokio::task::spawn_blocking(move || std::fs::read(&cached))
        .await
        .map_err(|_| ApiError::Internal)?
        .map_err(|_| ApiError::NotFound)
}

/// 预览字节。`full = true` 时走全解码链（`get_full_image`）。
pub async fn preview_bytes(path: &str, max: u32, full: bool) -> Result<Vec<u8>, ApiError> {
    let max = max.clamp(256, 5000);
    let requested = path.to_string();

    // 1) 借既有命令拿到"源图路径"（RAW → 缓存过的内嵌 JPEG; 非 RAW → 原图路径）
    let source = if full {
        crate::scanner::images::get_full_image(requested.clone())
            .await
            .map_err(|_| ApiError::NotFound)?
    } else {
        crate::scanner::images::get_preview_image(requested.clone())
            .await
            .map_err(|_| ApiError::NotFound)?
    };

    // spawn_blocking 要求闭包 'static: `requested` 后面还要用来比对, 所以显式克隆一份带进去
    // （直接借用 `&requested` 过不了编译）。
    let requested_for_block = requested.clone();

    // 2) 解码 → 降采样 → 编进内存。**重活必须离开 async 线程。**
    tokio::task::spawn_blocking(move || {
        let img = image::open(&source).map_err(|_| ApiError::NotFound)?;

        // 方向只能上一次（规格 §5.2 / 交接文档 §5 第 10 条）:
        //   · source == requested  → 拿到的是**原图**, 方向还没物理校正 → 这里校;
        //   · source != requested  → 拿到的是**缓存**(RAW 路径已在 images.rs:145-147 校过)
        //                            → 绝不能再校, 否则转 90°。
        let img = if source == requested_for_block {
            crate::scanner::images::apply_exif_orientation(Path::new(&requested_for_block), img)
        } else {
            img
        };

        let img = crate::scanner::images::resize_max_edge(img, max);
        let mut out = Vec::with_capacity(256 * 1024);
        crate::scanner::images::encode_jpeg_quality(&img, &mut out, PREVIEW_JPEG_QUALITY)
            .map_err(|_| ApiError::Internal)?;
        Ok(out)
    })
    .await
    .map_err(|_| ApiError::Internal)?
}
```

**不要**在这里加"缩略图兜底转码"之类的函数：`thumb_single` 永远把缓存写成 `.jpg`（`images.rs:480` 的缓存名就带 `.jpg`），所以没有需要兜底的格式。写了就是死代码，review 会（正确地）当缺陷提出来。

在 `server/mod.rs` 顶部加 `pub mod media;`。

- [ ] **Step 5: 跑测试**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result|panicked|FAILED"
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: 集成测试全过（含方向只转一次、不放大、缩略图 ≤ size）；`cargo test --lib` 与上一步一致。

- [ ] **Step 6: 手工看一张真图，确认观感**

```powershell
cd A:\tenent\src-tauri
$env:IMAGEFILTER_PROBE_DIR = "<有 RAW 的目录>"
cargo test --lib probe_embedded_preview -- --ignored --nocapture 2>&1 | Select-Object -First 12
```

**说明**：真正的端到端看图要等 Task 8 起服务 + Task 9 的集成测试。这一步只确认 Task 2 的探针仍在跑、没有被本任务的改动弄坏。

- [ ] **Step 7: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server src-tauri/src/scanner/images.rs src-tauri/tests/server_contract.rs
git commit -m "feat(server): 缩略图/预览字节化(Lanczos3 + 显式 q88), 方向只校正一次"
```

---

### Task 5: 浏览类端点（shares / drives / browse / scan）

**要点**：`scan_directory(dir_path) -> Vec<ScannedPhoto>` **没有 `Result`**（`browse.rs:190`），对不存在的目录返回空 Vec 而不是错误。所以服务端必须**自己做** `is_dir` 检查与共享根校验，不能依赖它报错。

**Files:**
- Modify: `src-tauri/src/server/routes.rs`（新建）
- Modify: `src-tauri/src/server/mod.rs`（挂 `routes`，并让 `router()` 用 `ServerState`）
- Modify: `src-tauri/tests/server_contract.rs`

**Interfaces:**
- Consumes: `paths::ensure_within`、`config::ServerConfig`、`auth::eq_constant_time`
- Produces:
  - `#[derive(Clone)] pub struct ServerState { pub config: Arc<RwLock<ServerConfig>> }`
  - `routes::router(state: ServerState) -> Router`
  - 端点 `GET /shares`、`GET /drives`、`GET /browse?path=`、`GET /scan?path=`

- [ ] **Step 1: 先写会失败的测试**

追加到 `src-tauri/tests/server_contract.rs`：

```rust
use axum::body::Body;
use axum::http::{Request, StatusCode};
use tower::ServiceExt;

/// 建一个测试用 state：一个临时共享根 + 固定 token。
async fn test_app() -> (axum::Router, String, std::path::PathBuf) {
    let root = std::env::temp_dir().join(format!("ifroute_{}_{}", std::process::id(), rand_suffix()));
    std::fs::create_dir_all(root.join("sub")).unwrap();
    std::fs::write(root.join("a.jpg"), b"jpegbytes").unwrap();
    std::fs::write(root.join("sub").join("b.jpg"), b"jpegbytes").unwrap();
    std::fs::write(root.join("note.txt"), b"notaphoto").unwrap();

    let cfg = image_filter_lib::server::ServerConfig {
        enabled: true,
        port: 0,
        token: "t".repeat(64),
        shares: vec![root.clone()],
    };
    (image_filter_lib::server::routes::router(cfg.into_state()), "t".repeat(64), root)
}

fn rand_suffix() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64
}

#[tokio::test]
async fn requires_bearer_token_on_protected_endpoints() {
    let (app, token, root) = test_app().await;

    let res = app.clone().oneshot(Request::builder().uri("/api/v1/shares").body(Body::empty()).unwrap()).await.unwrap();
    assert_eq!(res.status(), StatusCode::UNAUTHORIZED, "无 token 必须 401");

    let res = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/shares")
                .header("Authorization", format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    std::fs::remove_dir_all(&root).ok();
}

#[tokio::test]
async fn scan_filters_non_photos_and_rejects_outside_paths() {
    let (app, token, root) = test_app().await;
    let auth = |uri: String| {
        Request::builder()
            .uri(uri)
            .header("Authorization", format!("Bearer {}", token))
            .body(Body::empty())
            .unwrap()
    };

    // 共享根内: 只应看到 a.jpg, 不该有 note.txt
    let uri = format!("/api/v1/scan?path={}", urlencode(root.to_str().unwrap()));
    let res = app.clone().oneshot(auth(uri)).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let v: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let arr = v.as_array().unwrap();
    assert_eq!(arr.len(), 1, "note.txt 不该出现");
    assert!(arr[0]["fileName"].as_str().unwrap().eq_ignore_ascii_case("a.jpg"));

    // 共享根外: 必须 403
    let outside = std::env::temp_dir().join("ifroute_outside.txt");
    std::fs::write(&outside, b"x").unwrap();
    let uri = format!("/api/v1/scan?path={}", urlencode(std::env::temp_dir().to_str().unwrap()));
    let res = app.clone().oneshot(auth(uri)).await.unwrap();
    assert_eq!(res.status(), StatusCode::FORBIDDEN, "共享根外必须 403");

    std::fs::remove_dir_all(&root).ok();
    std::fs::remove_file(&outside).ok();
}

/// 最小百分号编码（只处理反斜杠与空格, 够 Windows 路径用）。
fn urlencode(s: &str) -> String {
    s.replace('\\', "%5C").replace(' ', "%20").replace(':', "%3A")
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract requires_bearer 2>&1 | Select-String -Pattern "error|test result"
```

Expected: 编译失败（`routes` / `ServerConfig` / `into_state` 不存在）。

- [ ] **Step 3: 实现 `routes.rs` 与 state**

创建 `src-tauri/src/server/routes.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 端点实现。**每个处理器只做三件事**: 校验路径 → 调既有函数 → 序列化。

use super::{auth, config::ServerConfig, media, paths, ApiError, ServerState};
use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::Deserialize;
// 这里**不要**写 `use std::path::PathBuf;` —— 路径类型一律用全限定名, 否则会产生
// unused import 警告, 与"相对基线无新增 warning"直接冲突(Task 3 实测踩到)。

/// Bearer 校验。规格 §5.2: token 长度固定且公开, 长度不同直接拒不算泄露。
fn check_auth(state: &ServerState, headers: &HeaderMap) -> Result<(), ApiError> {
    let cfg = state.config.read().map_err(|_| ApiError::Internal)?;
    let got = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .unwrap_or("");
    if auth::eq_constant_time(got, &cfg.token) {
        Ok(())
    } else {
        Err(ApiError::Unauthorized)
    }
}

fn shares_of(state: &ServerState) -> Result<Vec<PathBuf>, ApiError> {
    Ok(state.config.read().map_err(|_| ApiError::Internal)?.shares.clone())
}

pub fn router(state: ServerState) -> Router {
    Router::new()
        .route("/api/v1/ping", get(super::ping))
        .route("/api/v1/shares", get(shares))
        .route("/api/v1/drives", get(drives))
        .route("/api/v1/browse", get(browse))
        .route("/api/v1/scan", get(scan))
        .with_state(state)
}

/// 手机只能在这几个根里活动（规格 §5.1）。
async fn shares(State(st): State<ServerState>, headers: HeaderMap) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let list: Vec<String> = shares_of(&st)?
        .into_iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect();
    Ok(Json(serde_json::json!({ "shares": list })).into_response())
}

/// 盘符/设备列表。`detect_drives` 是**同步**函数（`drives.rs:143`）。
async fn drives(State(st): State<ServerState>, headers: HeaderMap) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let d = crate::scanner::drives::detect_drives();
    Ok(Json(d).into_response())
}

#[derive(Deserialize)]
struct PathQ {
    path: String,
}

async fn browse(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Query(q): Query<PathQ>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let dir = paths::ensure_within(&shares_of(&st)?, std::path::Path::new(&q.path))?;
    let entry = crate::scanner::browse::browse_directory(dir.to_string_lossy().to_string())
        .await
        .map_err(|_| ApiError::NotFound)?;
    Ok(Json(entry).into_response())
}

async fn scan(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Query(q): Query<PathQ>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let dir = paths::ensure_within(&shares_of(&st)?, std::path::Path::new(&q.path))?;
    // `scan_directory` 没有 Result（`browse.rs:190`）: 对不存在的目录返回空 Vec。
    // 所以这里必须自己挡 —— 否则手机分不清"空文件夹"与"路径不存在"。
    if !dir.is_dir() {
        return Err(ApiError::NotFound);
    }
    let photos = crate::scanner::browse::scan_directory(dir.to_string_lossy().to_string()).await;
    Ok(Json(photos).into_response())
}

/// 图片响应统一带 JPEG content-type 与 no-store（缓存交给手机端 Coil）。
fn jpeg(bytes: Vec<u8>) -> Response {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "image/jpeg")],
        Body::from(bytes),
    )
        .into_response()
}

#[derive(Deserialize)]
struct ThumbQ {
    path: String,
    #[serde(default = "default_thumb_size")]
    size: u32,
}
fn default_thumb_size() -> u32 {
    300
}

async fn thumb(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Query(q): Query<ThumbQ>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let p = paths::ensure_within(&shares_of(&st)?, std::path::Path::new(&q.path))?;
    let bytes = media::thumbnail_bytes(&p.to_string_lossy(), q.size).await?;
    Ok(jpeg(bytes))
}

#[derive(Deserialize)]
struct PreviewQ {
    path: String,
    #[serde(default = "default_preview_max")]
    max: u32,
    #[serde(default)]
    full: bool,
}
fn default_preview_max() -> u32 {
    2048
}

async fn preview(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Query(q): Query<PreviewQ>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let p = paths::ensure_within(&shares_of(&st)?, std::path::Path::new(&q.path))?;
    let bytes = media::preview_bytes(&p.to_string_lossy(), q.max, q.full).await?;
    Ok(jpeg(bytes))
}
```

并在 `routes::router` 里把 `thumb` / `preview` 也 `route` 上（本任务先只挂浏览类，Task 6 再挂 decisions/move；**thumb/preview 本任务一并挂上**，因为它们不需要额外契约）。

- [ ] **Step 4: `mod.rs` 里加 `ServerState` 与 `ping` 的可见性**

`server/mod.rs` 顶部加 `pub mod routes;`，并加：

```rust
use std::sync::{Arc, RwLock};

/// 服务运行期状态。`Arc<RwLock<_>>` 是为了让"改共享根/轮换 token"能立刻生效,
/// 而不必重启服务。
#[derive(Clone)]
pub struct ServerState {
    pub config: Arc<RwLock<config::ServerConfig>>,
}

impl config::ServerConfig {
    pub fn into_state(self) -> ServerState {
        ServerState { config: Arc::new(RwLock::new(self)) }
    }
}
```

> **⚠️ 已被 Task 5 的修复轮推翻，读到这里请注意**：`server::router()`（只含 ping、**不带鉴权层**的那个公开入口）已被**删除**。原因是它构成一个后门——任何往它上面挂的端点都会**完全不带 token 地应答**，而"没人调用的第二个入口"没有任何测试能抓住（实测：把它加回来并挂上 `/shares`，无 `Authorization` 头也返回 200，且两套测试全绿）。
>
> **全仓库唯一的入口是 `routes::router(state)`**（鉴权层包住整张路由表；`endpoint_routes()` 是私有辅助）。后续任务（尤其 Task 8 接线）**必须**用它，**不许再长出第二个 `Router`**。下面 Task 1 的代码块是历史记录，其中的 `server::router()` 形态**不要照抄**。

把 `async fn ping` 的可见性**保持私有**即可（`routes` 是 `server` 的子模块，`super::ping` 对私有项同样可达——实测无需放宽）。**`server::router()` 已在 Task 5 修复轮删除**，唯一入口是 `routes::router(state)`。

- [ ] **Step 5: 跑测试**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result|FAILED"
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: 全绿；`requires_bearer_token_on_protected_endpoints` 与 `scan_filters_non_photos_and_rejects_outside_paths` 通过。

- [ ] **Step 6: 手工确认 `browse_directory` 的字段名与前端一致**

```powershell
cd A:\tenent
Select-String -Pattern "photoCount|hasSubdirs|subfolders" src\types.ts
```

Expected: 与 `server/routes.rs` 直接序列化 `FolderEntry` 的产物一致（`mod.rs:53-56` 的 `serde(rename_all = "camelCase")` 已经保证）。若不一致，说明序列化属性丢了——**以 `types.ts` 为准**。

- [ ] **Step 7: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server src-tauri/tests/server_contract.rs
git commit -m "feat(server): 浏览类端点 shares/drives/browse/scan/thumb/preview + Bearer 鉴权"
```

---

### Task 6: 决策端点（读写边车）

**要点**：写边车的**永远是桌面端**；服务端调既有的 `write_decisions`，因此 `changed=false` 不写、`XmpError` 闭集码、`WriteSummary` 形状全部自动继承。**唯一要新增的是同一路径的串行化**——`apply_decision` 是读-改-写，两个并发请求会交错。

**Files:**
- Modify: `src-tauri/src/server/routes.rs`
- Modify: `src-tauri/tests/server_contract.rs`

**Interfaces:**
- Consumes: `crate::xmp::read_decisions(Vec<String>)`、`crate::xmp::write_decisions(Vec<DecisionWrite>)`（都是 `pub async fn`，可直接调）
- Produces: `GET /api/v1/decisions?paths=a&paths=b`、`PUT /api/v1/decisions`

- [ ] **Step 1: 先写会失败的测试**

```rust
#[tokio::test]
async fn decisions_write_then_read_roundtrip() {
    let (app, token, root) = test_app().await;
    let photo = root.join("a.jpg");
    let photo_q = urlencode(photo.to_str().unwrap());

    // 写 4 星
    let body = serde_json::json!([{ "path": photo.to_string_lossy(), "rating": 4, "label": null, "clearLabel": false }]);
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/decisions")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .body(Body::from(serde_json::to_vec(&body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let b = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let summary: serde_json::Value = serde_json::from_slice(&b).unwrap();
    assert_eq!(summary["written"], 1, "必须报告 written=1");
    assert_eq!(summary["failed"], 0);

    // 边车必须真的出现在共享根里, 且带 xmp:Rating="4"
    let sidecar = root.join("a.xmp");
    assert!(sidecar.is_file(), "边车必须由服务端写出");
    let text = std::fs::read_to_string(&sidecar).unwrap();
    assert!(text.contains("xmp:Rating=\"4\""), "实际内容: {}", text);

    // 读回来
    let res = app
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/decisions?paths={}", photo_q))
                .header("Authorization", format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let b = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let v: serde_json::Value = serde_json::from_slice(&b).unwrap();
    assert_eq!(v[0]["rating"], 4);

    std::fs::remove_dir_all(&root).ok();
}

#[tokio::test]
async fn decisions_reject_paths_outside_shares() {
    let (app, token, root) = test_app().await;
    let outside = std::env::temp_dir().join("ifdec_outside.jpg");
    std::fs::write(&outside, b"x").unwrap();

    let body = serde_json::json!([{ "path": outside.to_string_lossy(), "rating": 5, "label": null, "clearLabel": false }]);
    let res = app
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/api/v1/decisions")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .body(Body::from(serde_json::to_vec(&body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::FORBIDDEN, "共享根外写边车必须 403");
    assert!(!outside.with_extension("xmp").exists(), "绝不能在共享根外写出边车");

    std::fs::remove_dir_all(&root).ok();
    std::fs::remove_file(&outside).ok();
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract decisions_ 2>&1 | Select-String -Pattern "error|test result"
```

Expected: 失败——`/api/v1/decisions` 未挂载（404）。

- [ ] **Step 3: 实现端点（含串行化）**

在 `server/routes.rs` 里加：

```rust
use crate::xmp::{DecisionRead, DecisionWrite, WriteSummary};
use std::collections::HashSet;
use std::sync::{Mutex, OnceLock};

/// 同一路径的写必须串行 —— `apply_decision` 是**读-改-写**, 并发会交错覆盖。
/// （`xmp.rs` 里已有一个进程内闸门 `WRITING`, 但那是防同一目标并发;
///  这里再加一层按路径的串行, 让"两个手机同时打同一张"也排队。）
static WRITE_LOCKS: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn lock_set() -> &'static Mutex<HashSet<String>> {
    WRITE_LOCKS.get_or_init(|| Mutex::new(HashSet::new()))
}

/// 极简按路径互斥: 拿不到就返回 423(Busy), 不阻塞等待 —— 手机端会重试。
fn try_acquire(path: &str) -> Result<(), ApiError> {
    let mut set = lock_set().lock().map_err(|_| ApiError::Internal)?;
    if set.contains(path) {
        return Err(ApiError::Busy);
    }
    set.insert(path.to_string());
    Ok(())
}

fn release(path: &str) {
    if let Ok(mut set) = lock_set().lock() {
        set.remove(path);
    }
}

#[derive(Deserialize)]
struct DecisionsQ {
    #[serde(default)]
    paths: Vec<String>,
}

async fn decisions_get(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Query(q): Query<DecisionsQ>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let shares = shares_of(&st)?;
    let mut ok: Vec<String> = Vec::with_capacity(q.paths.len());
    for p in &q.paths {
        let canon = paths::ensure_within(&shares, std::path::Path::new(p))?;
        ok.push(canon.to_string_lossy().to_string());
    }
    let out: Vec<DecisionRead> = crate::xmp::read_decisions(ok).await.map_err(|_| ApiError::Internal)?;
    Ok(Json(out).into_response())
}

async fn decisions_put(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Json(items): Json<Vec<DecisionWrite>>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let shares = shares_of(&st)?;

    // 先全量校验路径, 再动手 —— 绝不允许"写了一半才发现有越界路径"
    let mut checked: Vec<DecisionWrite> = Vec::with_capacity(items.len());
    for it in items {
        let canon = paths::ensure_within(&shares, std::path::Path::new(&it.path))?;
        checked.push(DecisionWrite { path: canon.to_string_lossy().to_string(), ..it });
    }

    // 按路径串行; 任何一条拿不到锁 → 整体 423, 由手机端重试
    let mut held: Vec<String> = Vec::new();
    for it in &checked {
        if let Err(e) = try_acquire(&it.path) {
            for p in &held {
                release(p);
            }
            return Err(e);
        }
        held.push(it.path.clone());
    }

    let result = crate::xmp::write_decisions(checked).await;
    for p in &held {
        release(p);
    }

    let summary: WriteSummary = result.map_err(ApiError::from)?;
    Ok(Json(summary).into_response())
}
```

在 `router()` 里挂上：

```rust
        .route("/api/v1/decisions", get(decisions_get).put(decisions_put))
```

并把 `pub mod routes;` 需要的类型放开：`crate::xmp::DecisionRead/DecisionWrite/WriteSummary` 都已是 `pub` ✓。

- [ ] **Step 4: 跑测试**

```powershell
cd A:\tenent\src-tauri
cargo test --test server_contract decisions_ 2>&1 | Select-String -Pattern "test result|FAILED"
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: 两个 decisions 测试通过（边车真的落在共享根里、共享根外 403 且不产生边车）。

- [ ] **Step 5: 手工确认边车内容与桌面端一致**

```powershell
cd A:\tenent\src-tauri
# 用 Task 9 的集成测试留下的临时目录不行(会清掉), 这里手写一个最小场景:
cargo test --test server_contract decisions_write_then_read_roundtrip -- --nocapture
```

Expected: 通过。边车内容断言里的 `xmp:Rating="4"` 就是桌面端 `xmp.rs` 的格式——**同一个实现，不可能不一致**。

- [ ] **Step 6: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server/routes.rs src-tauri/tests/server_contract.rs
git commit -m "feat(server): 决策端点(读写边车, 复用既有 write_decisions) + 按路径串行化"
```

---

### Task 7: `mover.rs` —— 移动照片 + 边车随行

**三条纪律**（规格 §5.2）：绝不静默覆盖；先移照片再移边车；半成功必须显式返回。

**Files:**
- Create: `src-tauri/src/mover.rs`
- Modify: `src-tauri/src/lib.rs`（加 `mod mover;`）
- Modify: `src-tauri/src/server/routes.rs`（挂 `POST /api/v1/move`）
- Modify: `src-tauri/tests/server_contract.rs`

**Interfaces:**
- Consumes: `crate::xmp::sidecar_candidates(&Path) -> Vec<PathBuf>`（既有，`xmp.rs:681`）
- Produces:
  - `mover::plan_dest(dest_dir: &Path, file_name: &OsStr) -> Result<PathBuf, MoveError>`（纯函数，可测）
  - `mover::move_one(src: &Path, dest_dir: &Path) -> MoveOutcome`
  - `mover::move_many(paths: &[String], dest_dir: &Path) -> MoveSummary`
  - `MoveOutcome { src, dest: Option<PathBuf>, status: MoveStatus }`、`MoveStatus::{Moved, Renamed, Conflict, HalfSuccess, Failed}`

- [ ] **Step 1: 先写会失败的测试（`mover` 是纯文件操作，用单测就够）**

在 `src-tauri/src/mover.rs` 里直接写测试：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("ifmove_{}_{}", tag, std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn moves_photo_and_its_sidecar_together() {
        let root = tmp("with_sidecar");
        let src = root.join("IMG_1.ARW");
        std::fs::write(&src, b"raw").unwrap();
        std::fs::write(root.join("IMG_1.xmp"), b"<x:xmpmeta/>").unwrap();
        let dest = root.join("精修");
        std::fs::create_dir_all(&dest).unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::Moved);
        assert!(dest.join("IMG_1.ARW").is_file(), "照片必须被移动");
        assert!(dest.join("IMG_1.xmp").is_file(), "**边车必须跟着走**");
        assert!(!src.exists(), "源不应残留");
        assert!(!root.join("IMG_1.xmp").exists(), "源边车不应残留");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn refuses_to_overwrite_existing_target() {
        let root = tmp("no_overwrite");
        let src = root.join("a.jpg");
        std::fs::write(&src, b"new").unwrap();
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();
        std::fs::write(dest.join("a.jpg"), b"existing").unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::Conflict, "目标已存在必须报冲突");
        assert_eq!(std::fs::read(dest.join("a.jpg")).unwrap(), b"existing", "绝不覆盖");
        assert!(src.is_file(), "冲突时源必须原地不动");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn sidecar_move_failure_is_reported_as_half_success() {
        let root = tmp("half");
        let src = root.join("b.jpg");
        std::fs::write(&src, b"new").unwrap();
        std::fs::write(root.join("b.xmp"), b"<x:xmpmeta/>").unwrap();
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();

        // 目标位置放一个**同名目录**当边车 → 移动边车必然失败
        std::fs::create_dir_all(dest.join("b.xmp")).unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::HalfSuccess, "照片动了边车没动 → 必须报半成功");
        assert!(dest.join("b.jpg").is_file(), "照片应已移动");
        assert!(root.join("b.xmp").is_file(), "边车应留在原地(不能让评分与新路径脱钩还静默)");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn photo_without_sidecar_is_a_plain_move() {
        let root = tmp("nosidecar");
        let src = root.join("c.jpg");
        std::fs::write(&src, b"x").unwrap();
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();

        assert_eq!(move_one(&src, &dest).status, MoveStatus::Moved);
        std::fs::remove_dir_all(&root).ok();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test --lib mover 2>&1 | Select-String -Pattern "error|test result"
```

Expected: 编译失败（`mod mover` 不存在）。

- [ ] **Step 3: 实现 `mover.rs`**

创建 `src-tauri/src/mover.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 移动照片到子文件夹（手机端的"分类归档"）。
//!
//! 三条纪律（规格 §5.2），每一条都有测试盯着:
//!   1. **绝不静默覆盖** —— 目标已存在就报冲突, 让用户改名或跳过
//!      （与 `importer.rs` 的既有纪律一致）;
//!   2. **先移照片, 再移同名边车** —— 反过来出半成功时, 用户在旧路径看到的是
//!      "评分丢了", 比孤儿边车更难解释;
//!   3. **半成功必须显式返回**, 不许静默吞掉 —— 否则评分与新路径脱钩而无人知道。

use serde::Serialize;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum MoveStatus {
    Moved,
    /// 目标同名但内容不同 → 仍然报冲突（本设计不做自动改名, 名字由用户决定）
    Renamed,
    Conflict,
    /// 照片动了、边车没动 —— 必须在界面上可见并可重试
    HalfSuccess,
    Failed,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MoveOutcome {
    pub src: String,
    pub dest: Option<String>,
    pub status: MoveStatus,
    /// 给用户看的短原因（不是技术堆栈）
    pub detail: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MoveSummary {
    pub moved: u32,
    pub half_success: u32,
    pub conflict: u32,
    pub failed: u32,
    pub items: Vec<MoveOutcome>,
}

/// 移动一张（含边车）。**同步函数** —— 调用方负责 `spawn_blocking`。
pub fn move_one(src: &Path, dest_dir: &Path) -> MoveOutcome {
    let name = src.file_name().map(|n| n.to_os_string());
    let Some(name) = name else {
        return MoveOutcome { src: src.to_string_lossy().to_string(), dest: None, status: MoveStatus::Failed, detail: Some("路径没有文件名".into()) };
    };
    let dest = dest_dir.join(&name);

    if dest.exists() {
        return MoveOutcome {
            src: src.to_string_lossy().to_string(),
            dest: Some(dest.to_string_lossy().to_string()),
            status: MoveStatus::Conflict,
            detail: Some("目标位置已有同名文件, 未覆盖".into()),
        };
    }
    if std::fs::rename(src, &dest).is_err() {
        return MoveOutcome {
            src: src.to_string_lossy().to_string(),
            dest: None,
            status: MoveStatus::Failed,
            detail: Some("移动失败（可能跨卷或被占用）".into()),
        };
    }

    // 边车随行: 名字规则**复用 xmp.rs 的 sidecar_candidates**, 不在这里另写一套
    for cand in crate::xmp::sidecar_candidates(src) {
        if !cand.is_file() {
            continue;
        }
        let Some(sc_name) = cand.file_name() else { continue };
        let sc_dest = dest_dir.join(sc_name);
        if sc_dest.exists() || std::fs::rename(&cand, &sc_dest).is_err() {
            return MoveOutcome {
                src: src.to_string_lossy().to_string(),
                dest: Some(dest.to_string_lossy().to_string()),
                status: MoveStatus::HalfSuccess,
                detail: Some(format!("照片已移动, 但边车 {} 没能跟着走, 需要重试", sc_name.to_string_lossy())),
            };
        }
    }

    MoveOutcome {
        src: src.to_string_lossy().to_string(),
        dest: Some(dest.to_string_lossy().to_string()),
        status: MoveStatus::Moved,
        detail: None,
    }
}

/// 批量移动。**每个进入的路径都已由调用方做过共享根校验。**
pub fn move_many(paths: &[PathBuf], dest_dir: &Path) -> MoveSummary {
    let mut s = MoveSummary { moved: 0, half_success: 0, conflict: 0, failed: 0, items: Vec::new() };
    for p in paths {
        let out = move_one(p, dest_dir);
        match out.status {
            MoveStatus::Moved | MoveStatus::Renamed => s.moved += 1,
            MoveStatus::HalfSuccess => s.half_success += 1,
            MoveStatus::Conflict => s.conflict += 1,
            MoveStatus::Failed => s.failed += 1,
        }
        s.items.push(out);
    }
    s
}
```

在 `src-tauri/src/lib.rs` 加 `mod mover;`。

- [ ] **Step 4: 跑 `mover` 单测**

```powershell
cd A:\tenent\src-tauri
cargo test --lib mover 2>&1 | Select-String -Pattern "test result|FAILED"
```

Expected: 4 个测试全过（含"边车目标放同名目录"造出的半成功）。

- [ ] **Step 5: 挂 `POST /api/v1/move`**

在 `server/routes.rs` 加：

```rust
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MoveReq {
    paths: Vec<String>,
    dest_dir: String,
}

async fn move_photos(
    State(st): State<ServerState>,
    headers: HeaderMap,
    Json(req): Json<MoveReq>,
) -> Result<Response, ApiError> {
    check_auth(&st, &headers)?;
    let shares = shares_of(&st)?;
    // 目标目录也必须校验（可在共享根内新建, 但 canonicalize 要求它已存在）
    let dest = paths::ensure_within(&shares, std::path::Path::new(&req.dest_dir))?;
    if !dest.is_dir() {
        return Err(ApiError::NotFound);
    }
    let mut srcs: Vec<std::path::PathBuf> = Vec::with_capacity(req.paths.len());
    for p in &req.paths {
        srcs.push(paths::ensure_within(&shares, std::path::Path::new(p))?);
    }

    let summary = tokio::task::spawn_blocking(move || crate::mover::move_many(&srcs, &dest))
        .await
        .map_err(|_| ApiError::Internal)?;
    Ok(Json(summary).into_response())
}
```

并在 `router()` 里加 `.route("/api/v1/move", axum::routing::post(move_photos))`。

- [ ] **Step 6: 加集成测试（走 HTTP 层）**

```rust
#[tokio::test]
async fn move_endpoint_moves_photo_and_reports_conflict() {
    let (app, token, root) = test_app().await;
    let dest = root.join("sub");
    let photo = root.join("a.jpg");

    let req = serde_json::json!({ "paths": [photo.to_string_lossy()], "destDir": dest.to_string_lossy() });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/move")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .body(Body::from(serde_json::to_vec(&req).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let b = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let v: serde_json::Value = serde_json::from_slice(&b).unwrap();
    assert_eq!(v["moved"], 1);
    assert!(dest.join("a.jpg").is_file());

    std::fs::remove_dir_all(&root).ok();
}
```

- [ ] **Step 7: 跑全部测试**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result|FAILED"
```

Expected: 全绿。

- [ ] **Step 8: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/mover.rs src-tauri/src/lib.rs src-tauri/src/server/routes.rs src-tauri/tests/server_contract.rs
git commit -m "feat(server): 移动照片端点(边车随行/绝不覆盖/半成功显式返回)"
```

---

### Task 8: 服务生命周期、配置持久化与 Tauri 接线

**Files:**
- Modify: `src-tauri/src/server/mod.rs`（启停、端口自增、`ServerHandle`）
- Modify: `src-tauri/src/lib.rs`（注册命令、`setup` 里按配置拉起）

**Interfaces:**
- Produces（供 Task 10 的前端调用）：
  - `get_server_status() -> ServerStatus { running, port, url, token, shares, enabled }`
  - `set_server_enabled(enabled: bool) -> Result<ServerStatus, String>`
  - `rotate_server_token() -> Result<ServerStatus, String>`
  - `add_share(path: String) -> Result<Vec<String>, String>` / `remove_share(path: String)`
  - 事件 `decisions-changed`（payload: `{ paths: Vec<String> }`）

> **启动路径绝不许 panic（用户的首要约束：别把桌面端弄坏）**
>
> **先说时态，别被吓到也别忽略**：这个隐患今天是**潜伏的、不可达的** —— `ServerConfig::load()` / `default()` 目前在 `src-tauri` 里**零调用点**（`lib.rs` 只声明了 `pub mod server`，`setup` 还没接服务）。它会在**本任务 Step 3 把 `load()` 接进 Tauri 的 setup 闭包的那一刻变成活的**。所以这条必须在本任务内一起处理，不能推到以后。
>
> 机制：`auth::new_token()` 用 `getrandom(...).expect(...)`；`ServerConfig::load()` 走 `.unwrap_or_default()`，配置缺失/损坏时落到 `Default`，而 `Default` 会调 `new_token()` —— 于是**一次随机数失败就把整个桌面端崩在启动路径上**，而不是让服务降级。概率极低，但失败模式不可接受（用户的首要约束是"别把桌面端弄坏"）。
>
> 做法（**注意别改出更糟的问题**）：
> 1. 加一个**可失败**的构造函数，例如 `auth::try_new_token() -> Result<String, String>`，内部不再 `expect`。**`new_token()` 的签名保持不动** —— `auth.rs` 自己的单测和既有调用点依赖 `-> String`。
> 2. `ServerConfig::default()` **不再生成 token**（`token: String::new()`），改为**在启服务时生成并持久化**，失败作为 `Err` 往上抛：服务不启动 + 界面给一句提示，**应用照常可用**。
> 3. ⚠️ **空 token 必须 fail-closed —— 这一条绝不能漏**：`auth::eq_constant_time("", "")` 返回 `true`。如果鉴权中间件只是把请求头与 `cfg.token` 比较，那么 **`Authorization: Bearer `（空 token）就能通过鉴权**。所以：`start()` 见到空 token 必须返回 `Err`，**并且**中间件必须显式拒绝空 token（`cfg.token.is_empty() || presented.is_empty()` → 401）。**这条要有测试钉住**，否则"修掉一个 panic"就换来"多了一个未授权入口"。
> 4. 生成+持久化要挂在**开启服务的那条路径**（`set_server_enabled`）上。同时注意 `config.rs` 现有的 `.filter(|t| t.len() == 64)`（"token 不合法就重新生成"）这条保证会因为 `Default` 不再生成 token 而改变语义，**别默默丢掉它**。

- [ ] **Step 1: 实现 `ServerHandle` 与端口自增**

`server/mod.rs` 追加：

```rust
use std::sync::atomic::{AtomicU16, Ordering};
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub running: bool,
    pub port: u16,
    pub url: String,
    pub token: String,
    pub shares: Vec<String>,
    pub enabled: bool,
}

static RUNNING_PORT: AtomicU16 = AtomicU16::new(0);

/// 起服务。**用 Tauri 的运行时 spawn**（Task 1 已验证可行），不另建 runtime。
pub async fn start(state: ServerState) -> Result<u16, String> {
    let cfg = state.config.read().map_err(|e| e.to_string())?.clone();

    // 端口被占用就 +1 重试, 最多 20 次 —— 用户机器上 8765 被别的软件占了很常见
    let mut last_err = String::new();
    for offset in 0..20u16 {
        let port = cfg.port.saturating_add(offset);
        match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
            Ok(listener) => {
                RUNNING_PORT.store(port, Ordering::SeqCst);
                let app = routes::router(state.clone());
                tauri::async_runtime::spawn(async move {
                    if let Err(e) = axum::serve(listener, app).await {
                        eprintln!("[server] 服务结束: {}", e);
                    }
                });
                return Ok(port);
            }
            Err(e) => last_err = e.to_string(),
        }
    }
    Err(format!("20 个端口都绑定失败: {}", last_err))
}

pub fn running_port() -> Option<u16> {
    match RUNNING_PORT.load(Ordering::SeqCst) {
        0 => None,
        p => Some(p),
    }
}

/// 用本机 IPv4 拼一个手机可填的地址。检测不到就回落到 localhost。
pub fn primary_url(port: u16) -> String {
    format!("http://{}:{}", local_ipv4().unwrap_or_else(|| "127.0.0.1".into()), port)
}

/// 取本机第一个非回环 IPv4。**不引第三方 crate** —— 用 UDP connect 技巧拿默认出口地址。
fn local_ipv4() -> Option<String> {
    use std::net::UdpSocket;
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("223.5.5.5:80").ok()?; // 不真的发包, 只为让系统选路
    Some(s.local_addr().ok()?.ip().to_string())
}
```

- [ ] **Step 2: 注册 Tauri 命令**

在 `lib.rs` 里加（并在 `invoke_handler` 里注册）：

```rust
#[tauri::command]
async fn get_server_status(app: tauri::AppHandle, state: tauri::State<'_, ServerState>) -> Result<server::ServerStatus, String> {
    server::status_of(&app, &state)
}

#[tauri::command]
async fn set_server_enabled(app: tauri::AppHandle, state: tauri::State<'_, ServerState>, enabled: bool) -> Result<server::ServerStatus, String> {
    { state.config.write().map_err(|e| e.to_string())?.enabled = enabled; }
    server::persist(&app, &state)?;
    if enabled { server::start(state.inner().clone()).await?; }
    server::status_of(&app, &state)
}

#[tauri::command]
async fn rotate_server_token(app: tauri::AppHandle, state: tauri::State<'_, ServerState>) -> Result<server::ServerStatus, String> {
    { state.config.write().map_err(|e| e.to_string())?.token = server::auth::new_token(); }
    server::persist(&app, &state)?;
    server::status_of(&app, &state)
}

#[tauri::command]
async fn add_share(app: tauri::AppHandle, state: tauri::State<'_, ServerState>, path: String) -> Result<Vec<String>, String> {
    let p = std::path::PathBuf::from(&path);
    if !p.is_dir() { return Err("不是目录".into()); }
    {
        let mut cfg = state.config.write().map_err(|e| e.to_string())?;
        if !cfg.shares.iter().any(|s| s == &p) { cfg.shares.push(p); }
    }
    server::persist(&app, &state)?;
    Ok(server::share_strings(&state)?)
}

#[tauri::command]
async fn remove_share(app: tauri::AppHandle, state: tauri::State<'_, ServerState>, path: String) -> Result<Vec<String>, String> {
    { state.config.write().map_err(|e| e.to_string())?.shares.retain(|s| s.to_string_lossy() != path); }
    server::persist(&app, &state)?;
    Ok(server::share_strings(&state)?)
}
```

配套：`server/mod.rs` 里加下面四个小助手（Tauri 命令与状态查询都要用），并给 `ServerState` 加上可选的 `app` 句柄：

```rust
#[derive(Clone)]
pub struct ServerState {
    pub config: Arc<RwLock<config::ServerConfig>>,
    /// 测试里是 `None`; 正式运行时由 `into_state_with` 带上, 用于向桌面端 UI 发事件。
    pub app: Option<tauri::AppHandle>,
}

impl config::ServerConfig {
    /// 测试用（不带 app）。
    pub fn into_state(self) -> ServerState {
        ServerState { config: Arc::new(RwLock::new(self)), app: None }
    }
    /// 正式运行用: 带上 app 句柄, 才能在手机改动后通知桌面端 UI 刷新。
    pub fn into_state_with(self, app: tauri::AppHandle) -> ServerState {
        ServerState { config: Arc::new(RwLock::new(self)), app: Some(app) }
    }
}

pub fn app_data_dir(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    use tauri::Manager as _;
    app.path().app_data_dir().map_err(|e| e.to_string())
}

pub fn persist(app: &tauri::AppHandle, st: &ServerState) -> Result<(), String> {
    let cfg = st.config.read().map_err(|e| e.to_string())?.clone();
    cfg.save(&app_data_dir(app)?)
}

pub fn share_strings(st: &ServerState) -> Result<Vec<String>, String> {
    Ok(st
        .config
        .read()
        .map_err(|e| e.to_string())?
        .shares
        .iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect())
}

pub fn status_of(app: &tauri::AppHandle, st: &ServerState) -> Result<ServerStatus, String> {
    let _ = app; // 目前不需要 app, 留着是为了将来加"已连接来源"
    let cfg = st.config.read().map_err(|e| e.to_string())?.clone();
    let running = running_port();
    let port = running.unwrap_or(cfg.port);
    Ok(ServerStatus {
        running: running.is_some(),
        port,
        url: primary_url(port),
        token: cfg.token.clone(),
        shares: cfg.shares.iter().map(|p| p.to_string_lossy().to_string()).collect(),
        enabled: cfg.enabled,
    })
}
```

- [ ] **Step 3: `setup` 里按配置决定是否拉起，并 manage 状态**

在 `lib.rs` 的 `setup` 闭包里、`db::init_db` 之后加：

```rust
// 图库服务: 默认关闭; 配置里 enabled=true 才在启动时拉起
let app_data = app.path().app_data_dir().expect("failed to resolve app data dir");
let cfg = server::config::ServerConfig::load(&app_data);
let want_start = cfg.enabled;
let st = cfg.into_state();
app_handle.manage(st.clone());
if want_start {
    let st2 = st.clone();
    tauri::async_runtime::spawn(async move {
        match server::start(st2).await {
            Ok(p) => println!("[server] 已监听 {}", server::primary_url(p)),
            Err(e) => eprintln!("[server] 启动失败: {}", e),
        }
    });
}
```

- [ ] **Step 4: 加 `decisions-changed` 事件**

在 `decisions_put` 里，**在 `checked` 被 move 进 `write_decisions` 之前**先留一份路径；写成功之后发事件：

```rust
    // （放在 write_decisions 调用之前）
    let touched: Vec<String> = checked.iter().map(|i| i.path.clone()).collect();

    let result = crate::xmp::write_decisions(checked).await;
    for p in &held {
        release(p);
    }

    let summary: WriteSummary = result.map_err(ApiError::from)?;

    // 桌面端正在看同一个文件夹时让它立刻刷新 —— 否则手机上打了分,
    // 电脑上界面还是旧的, 用起来像 bug。
    // Tauri v2 的 `emit` 定义在 `Emitter` trait 上: **不 use 就编译不过**。
    if let Some(app) = st.app.as_ref() {
        use tauri::Emitter as _;
        let _ = app.emit("decisions-changed", serde_json::json!({ "paths": touched }));
    }

    Ok(Json(summary).into_response())
```

这要求 `ServerState` 里带一个可选的 `app`（Task 8 Step 2 已给出 `into_state` / `into_state_with`）。
**测试必须继续能用不带 app 的 state**：`into_state()` 把 `app` 设为 `None`，事件分支直接跳过。

- [ ] **Step 5: 跑全部测试**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result|FAILED"
cargo check --lib
```

Expected: 全绿，`cargo check` 无新增 warning。

- [ ] **Step 6: 真机（本机）冒烟：起服务、用脚本验证**

```powershell
cd A:\tenent
npx tauri dev
```

在桌面端 UI 里**还没有**开关（Task 10 才做），所以本步先用一个临时办法：把 `%APPDATA%\com.imagefilter.app\server-config.json` 的 `enabled` 改成 `true` 并加一个 `shares`，重启应用。然后：

```powershell
# 从 json 里取出 token 与 port 后用 Task 9 的脚本打一遍
pwsh docs/evidence/scripts/verify-server.ps1 -BaseUrl http://127.0.0.1:8765 -Token <token>
```

Expected: 脚本报全部通过。**若服务在 Tauri 里起不来 → 回看 Task 1 Step 6 的结论。**

- [ ] **Step 7: Commit**

```powershell
cd A:\tenent
git add src-tauri/src/server src-tauri/src/lib.rs
git commit -m "feat(server): 服务生命周期/配置持久化/Tauri 命令接线 + decisions-changed 事件"
```

---

### Task 9: 契约验证脚本（PowerShell 7）+ 端到端集成测试收口

**Files:**
- Create: `docs/evidence/scripts/verify-server.ps1`
- Modify: `src-tauri/tests/server_contract.rs`（补全错误分支：401/403/404/409/423）

- [ ] **Step 1: 写脚本**

创建 `docs/evidence/scripts/verify-server.ps1`：

```powershell
# ── PS7 自举（**不要**改用 #Requires -Version 7，见下方说明）─────────
# 实测：本 harness 的 shell 写死为 C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe，
# 即使已安装 PS7 并写了用户 PATH，当前会话也不会刷新。所以 #Requires 会让脚本直接失败；
# 正确做法是自己切到 PS7 再跑（-File 通路实测可用，脚本内引号不会丢）。
if ($PSVersionTable.PSVersion.Major -lt 7) {
    $pwsh = Join-Path $env:LOCALAPPDATA 'Programs\PowerShell\7\pwsh.exe'
    if (-not (Test-Path -LiteralPath $pwsh)) {
        throw "需要 PowerShell 7，但未找到：$pwsh"
    }
    # 原样转发参数（含命名参数）后退出
    $fwd = foreach ($k in $PSBoundParameters.Keys) {
        $v = $PSBoundParameters[$k]
        if ($v -is [switch]) { if ($v.IsPresent) { "-$k" } } else { "-$k"; "$v" }
    }
    & $pwsh -NoProfile -File $PSCommandPath @fwd
    exit $LASTEXITCODE
}

# Copyright (c) 2026 XUTENGXIANG
# SPDX-License-Identifier: MIT
<#
图库服务契约验证 —— 对着**真实运行中的桌面端**打一遍全部端点与错误分支。
集成的自动化覆盖在 src-tauri/tests/server_contract.rs; 这个脚本用来验
"服务真的能在 Tauri 进程里起来并对外服务"（那一条单测覆盖不到）。

用法:
  pwsh docs/evidence/scripts/verify-server.ps1 -BaseUrl http://127.0.0.1:8765 -Token <64位hex>
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [Parameter(Mandatory = $true)][string]$Token,
  [string]$SharePath
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$h = @{ Authorization = "Bearer $Token" }
$pass = 0; $fail = 0

function Check {
  param([string]$Name, [scriptblock]$Body)
  try {
    & $Body
    Write-Host "  PASS  $Name" -ForegroundColor Green
    $script:pass++
  } catch {
    Write-Host "  FAIL  $Name -> $($_.Exception.Message)" -ForegroundColor Red
    $script:fail++
  }
}

Write-Host "=== 图库服务契约验证: $base ==="

Check 'ping 无需 token' {
  $r = Invoke-RestMethod -Uri "$base/api/v1/ping" -TimeoutSec 10
  if ($r.name -ne 'ImageFilter') { throw "name=$($r.name)" }
  if ($r.requiresToken -ne $true) { throw 'requiresToken 应为 true' }
}

Check '无 token 访问受保护端点返回 401' {
  $r = Invoke-WebRequest -Uri "$base/api/v1/shares" -TimeoutSec 10 -SkipHttpErrorCheck
  if ($r.StatusCode -ne 401) { throw "期望 401, 实得 $($r.StatusCode)" }
}

Check '错误 token 返回 401' {
  $r = Invoke-WebRequest -Uri "$base/api/v1/shares" -Headers @{ Authorization = 'Bearer deadbeef' } -TimeoutSec 10 -SkipHttpErrorCheck
  if ($r.StatusCode -ne 401) { throw "期望 401, 实得 $($r.StatusCode)" }
}

Check '带 token 能读共享根' {
  $r = Invoke-RestMethod -Uri "$base/api/v1/shares" -Headers $h -TimeoutSec 10
  if ($null -eq $r.shares) { throw 'shares 字段缺失' }
  Write-Host "        共享根: $($r.shares -join ', ')" -ForegroundColor DarkGray
}

Check '共享根外路径返回 403' {
  $outside = if ($IsWindows) { 'C:\Windows' } else { '/etc' }
  $r = Invoke-WebRequest -Uri "$base/api/v1/scan?path=$([uri]::EscapeDataString($outside))" -Headers $h -TimeoutSec 15 -SkipHttpErrorCheck
  if ($r.StatusCode -ne 403) { throw "期望 403, 实得 $($r.StatusCode)" }
}

if ($SharePath) {
  Check 'scan 共享根内目录返回照片数组' {
    $r = Invoke-RestMethod -Uri "$base/api/v1/scan?path=$([uri]::EscapeDataString($SharePath))" -Headers $h -TimeoutSec 30
    Write-Host "        照片数: $($r.Count)" -ForegroundColor DarkGray
  }
  Check 'thumb 返回 JPEG 字节' {
    $first = (Invoke-RestMethod -Uri "$base/api/v1/scan?path=$([uri]::EscapeDataString($SharePath))" -Headers $h -TimeoutSec 30)[0]
    if (-not $first) { throw '该目录没有照片, 跳过此项请换目录' }
    $bytes = Invoke-WebRequest -Uri "$base/api/v1/thumb?path=$([uri]::EscapeDataString($first.path))&size=300" -Headers $h -TimeoutSec 60
    $u8 = $bytes.Content
    if ($u8.Length -lt 4 -or $u8[0] -ne 0xFF -or $u8[1] -ne 0xD8) { throw '返回的不是 JPEG(缺 SOI)' }
    Write-Host "        缩略图字节数: $($u8.Length)" -ForegroundColor DarkGray
  }
  Check 'preview 长边不超过 max' {
    $first = (Invoke-RestMethod -Uri "$base/api/v1/scan?path=$([uri]::EscapeDataString($SharePath))" -Headers $h -TimeoutSec 30)[0]
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $bytes = Invoke-WebRequest -Uri "$base/api/v1/preview?path=$([uri]::EscapeDataString($first.path))&max=2048" -Headers $h -TimeoutSec 120
    $sw.Stop()
    Write-Host "        预览字节数: $($bytes.Content.Length), 耗时 $($sw.ElapsedMilliseconds) ms" -ForegroundColor DarkGray
  }
} else {
  Write-Host "  (未给 -SharePath, 跳过 scan/thumb/preview 三项)" -ForegroundColor Yellow
}

Write-Host "`n=== 通过 $pass / 失败 $fail ==="
if ($fail -gt 0) { exit 1 }
```

> **本步要顺带收掉 Task 3 评审提出的 4 条 Minor**（它们都属于"错误分支与白名单的正向保证"，正好落在本任务的职责里）：
>
> 1. **白名单的 3 条正向测试**（现在只有代码保证，没有测试）：
>    ① **共享根本身**必须被接受 —— 但要**如实描述理由**：`canon == root_canon` 那一半其实是**冗余**的，`Path::starts_with` **自反**（`Path::new("/a").starts_with("/a")` 即为 true），所以删掉它行为不变，**"列出共享根"不会因此变成 403**。真正的问题是**这条路径从没有被测试走过**。要么保留那一半并注明它只是可读性别名，要么删掉、让 `starts_with` 独自承担 —— 但**不要再声称它是"列表根能用"的保证**（那种说法会把冗余当成功劳）；
>    ② **大小写差异**（`D:\Photos` vs `d:\photos`）—— **必须加 `#[cfg(target_os = "windows")]` 门**：在区分大小写的文件系统上该路径根本不存在，`canonicalize` 会失败并返回 `notFound`，测试在非 Windows 上必挂；
>    ③ **末尾分隔符**（`D:\photos\`）。
> 2. **`XmpError::Unknown` 的映射改成 `BadRequest`**（现在是 `Conflict`）。`Unknown` 也是 `validate_photo_path` 对"非绝对路径 / 不支持的扩展名 / 空 stem"的返回（`xmp.rs:714-733`）——那是**坏请求**，告诉客户端"改名/跳过"会让它拿同一个坏路径重试。改完顺带让 `BadRequest` 这个变体不再是死码（否则闭集里宣传了一个任何路径都产生不出来的码）。
> 3. 这两条改动都要**配测试**：`Unknown` 的映射用一条断言钉住；白名单三条各自一条测试。
> 4. 错误体契约：规格 §5.3 已按实测改成 **`{ code }`**（原稿写 `{ code, detail }` 是错的；`src-tauri/src/server/mod.rs` 的 `IntoResponse` 实测只序列化 `code` 一个字段）。**不要**为了对齐旧文案去加 `detail` —— 那等于把中文文案硬编码进 Rust。（另：规格里"手机端也只消费 `code`"是**前向要求**，此刻 `android/` 里还没有 `ApiError` 类型。）

- [ ] **Step 2: 补全集成测试里的错误分支**

在 `src-tauri/tests/server_contract.rs` 追加：

```rust
#[tokio::test]
async fn not_found_for_missing_directory_inside_share() {
    let (app, token, root) = test_app().await;
    let missing = root.join("nope");
    let res = app
        .oneshot(
            Request::builder()
                .uri(format!("/api/v1/scan?path={}", urlencode(missing.to_str().unwrap())))
                .header("Authorization", format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND, "目录不存在必须 404, 而不是空数组");
    std::fs::remove_dir_all(&root).ok();
}

#[tokio::test]
async fn move_conflict_returns_conflict_status_per_item() {
    let (app, token, root) = test_app().await;
    let dest = root.join("sub");
    std::fs::write(dest.join("a.jpg"), b"existing").unwrap();

    let req = serde_json::json!({ "paths": [root.join("a.jpg").to_string_lossy()], "destDir": dest.to_string_lossy() });
    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/move")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .body(Body::from(serde_json::to_vec(&req).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();
    let b = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
    let v: serde_json::Value = serde_json::from_slice(&b).unwrap();
    assert_eq!(v["conflict"], 1);
    assert_eq!(v["moved"], 0);
    assert_eq!(std::fs::read(dest.join("a.jpg")).unwrap(), b"existing", "绝不覆盖");
    std::fs::remove_dir_all(&root).ok();
}
```

- [ ] **Step 3: 跑全部测试与脚本**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result|FAILED"
cd A:\tenent
# 需先 npx tauri dev 且服务已开启(参考 Task 8 Step 6)
pwsh docs/evidence/scripts/verify-server.ps1 -BaseUrl http://127.0.0.1:8765 -Token <token> -SharePath "<一个共享根>"
```

Expected: 测试全绿；脚本 `失败 0`。

- [ ] **Step 4: 落证据**

把脚本输出存进 `docs/evidence/server-contract/REPORT.txt`，顶部写三行结论（通过数 / 预览耗时 / 发现的问题）。

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add docs/evidence/scripts/verify-server.ps1 docs/evidence/server-contract src-tauri/tests/server_contract.rs
git commit -m "test(server): 契约验证脚本(PS7) + 补全 404/409 错误分支, 并归档实测结果"
```

---

### Task 10: 桌面端服务面板 + 事件订阅 + i18n

**这一任务需要 GUI 手测**（唯一无法自动化的部分）。

**Files:**
- Modify: `src/components/settings-dialog.tsx`（服务面板）
- Modify: `src/useScanner.ts`（订阅 `decisions-changed`）
- Modify: `src/i18n/zh.ts` + `src/i18n/en.ts`

- [ ] **Step 1: `useScanner.ts` 订阅事件**

在 `useScanner.ts` 里，紧挨既有的 `mergeRemoteRatings` / `mergeRemoteLabels`（约 1020 行的 `loadFolder` 用法）之后加一个 effect：

```ts
  // 手机端改了评分/色标 → 服务端发事件 → 这里合并。
  // 复用 loadFolder 用的同一对 merge 函数, 所以撤销栈的既有不变式不受影响
  // （**不许**逐个 path 调 setRating: 2000 张会把 100 深的撤销栈冲爆，
  //   这条理由原样写在 applyMergedRatings 的注释里）。
  useEffect(() => {
    let unlisten: (() => void) | undefined;
    void (async () => {
      const { listen } = await import("@tauri-apps/api/event");
      unlisten = await listen<{ paths: string[] }>("decisions-changed", async (e) => {
        const paths = e.payload?.paths ?? [];
        if (paths.length === 0) return;
        const remotes = await invoke<DecisionRead[]>("read_decisions", { filePaths: paths });
        if (remotes.length === 0) return;
        const r = mergeRemoteRatings(ratingsRef.current, remotes);
        if (r.changed.length > 0) applyMergedRatings(r.map);
        const l = mergeRemoteLabels(labelsRef.current, remotes);
        if (l.changed.length > 0) applyMergedLabels(l.map);
      });
    })();
    return () => unlisten?.();
  }, [applyMergedLabels, applyMergedRatings]);
```

- [ ] **Step 2: `settings-dialog.tsx` 加服务面板**

在设置对话框里加一块"图库服务"（开关 + 监听地址 + token 复制/轮换 + 共享根增删 + 已连接提示 + 四条排查清单）。**不要动 `App.tsx` 的布局**——面板全部长在设置对话框里。

关键文案（中文，英文同步）：
- `server.title` 图库服务（手机端）
- `server.warn` 开启后，同一网络下**持有配对码的设备**可以浏览你共享的目录。**不要把它直接暴露到公网。**
- `server.sleepTip` 远程访问时电脑不能睡眠：设置 → 系统 → 电源 → 屏幕和睡眠 → 设为"从不"
- `server.firewallTip` 首次开启时 Windows 会弹出防火墙询问，必须允许"专用网络"
- `server.appRunningTip` 手机连不上时先确认：① 电脑没睡眠 ② ImageFilter 正在运行 ③ 服务已开启 ④ 手机与电脑在同一网络（或都加入了同一个 EasyTier/Tailscale 网络）
- `server.shareEmpty` 还没共享任何目录——**在添加之前，服务对所有路径都返回"无权限"**（这是有意的安全默认）

- [ ] **Step 3: i18n 双写并跑对齐检查**

```powershell
cd A:\tenent
node docs/evidence/scripts/i18n-parity.cjs
```

Expected: 报 zh/en 叶子 key 数一致（交接文档 §9.2 提到基线是各 273；本次会同时增加两边）。

```powershell
npx tsc --noEmit
```

Expected: 无类型错误。

- [ ] **Step 4: GUI 手测清单（必须真机/本机跑一遍）**

```powershell
cd A:\tenent
npx tauri dev
```

逐项打勾：
1. 设置里能看到"图库服务"，默认**关闭**，且警告文案可见
2. 添加一个共享根 → 刷新设置能看到它
3. 开启服务 → 显示监听地址与 token；点复制能拿到 64 位 hex
4. 用 `docs/evidence/scripts/verify-server.ps1` 打一遍 → 全通过
5. 轮换 token → 旧 token 立刻 401
6. 关闭服务 → 端口不再响应
7. 在服务开着时，用脚本 `PUT /api/v1/decisions` 给**当前正在浏览的文件夹**里的一张照片打分 → **桌面端界面上的星级立刻变化**（验证 Task 8 的事件链路）
8. 撤销栈仍然正常（`Ctrl+Z` 能撤销第 7 步之后自己做的操作，不会把第 7 步的合并撤掉——这正是 `applyMergedRatings` 注释里那条不变式）

- [ ] **Step 5: 版本号与门禁**

本计划不发布，所以**不改版本号**。跑一遍全部门禁：

```powershell
cd A:\tenent
npx tsc --noEmit
npx vite build
cd src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cargo test --test server_contract 2>&1 | Select-String -Pattern "test result"
```

- [ ] **Step 6: Commit**

```powershell
cd A:\tenent
git add src/components/settings-dialog.tsx src/useScanner.ts src/i18n/zh.ts src/i18n/en.ts
git commit -m "feat(settings): 图库服务面板(开关/地址/配对码/共享根/排查清单) + 手机改动实时刷新"
```

---

## 附录 A · 规格覆盖对照

| 规格条目 | 由哪个任务实现 |
|---|---|
| §5.1 `ping`/`shares`/`drives`/`browse`/`scan` | Task 1、Task 5 |
| §5.1 `thumb`/`preview` | Task 4、Task 5 |
| §5.1 `decisions`（读/写） | Task 6 |
| §5.1 `move` | Task 7 |
| §5.2 鉴权（Bearer、常量时间、轮换） | Task 3、Task 8 |
| §5.2 **共享根白名单** | Task 3 |
| §5.2 缩略图/预览复用既有缓存 | Task 4 |
| §5.2 画质契约（Lanczos3 + 显式 q88 + 不放大 + 方向只校一次） | Task 4 |
| §5.2 移动纪律（绝不覆盖、边车随行、半成功） | Task 7 |
| §5.3 闭集错误码 | Task 3（定义）、Task 5/6/7（使用） |
| §6 桌面端文件清单（11 个） | Task 1/3/4/5/8/10 |
| §10 验证（Rust 单测 + 契约脚本） | Task 9 |
| §10.2 M0′ 里程碑验收线 | 全部任务的 Step "跑全部测试" |
| §12 Token 纪律（脚本按 PS7 写） | Task 9 |
| 附录 B #2 axum 与 tokio 共存 | Task 1 Step 6 |
| 附录 B #10 RAW 内嵌预览分辨率 | Task 2 |
| 附录 B #1 降采样耗时 | Task 9 Step 1（脚本打印耗时） |
| §8.3 桌面端服务面板与事件 | Task 10 |
| **M1′–M4′（Android）** | **不在本计划**，见范围说明 |

## 附录 B · 本计划刻意不做的事

| 不做 | 原因 |
|---|---|
| Android 工程 / Kotlin / Gradle | M1′ 起，另开计划（需要 JDK 17 + Android SDK + 真机） |
| 二维码渲染 | Task 10 先给地址与配对码文本；二维码是 M4′ 的打磨项（要决定用 JS 库还是 Rust 库，属独立决策） |
| SSE `/events`（可选端点） | 手机端刷新目前靠"每次进页面拉一次"，够用；等 M3′ 真机验证后再决定 |
| `/clients` 已连接来源 | M4′ 打磨项 |
| 只读模式 | M4′ 打磨项 |
| 打通引导与自动探测（检测本机是否装了 EasyTier / Tailscale 并列出虚拟网地址） | M4′ 打磨项。它是规格 §4.2 的 L1 能力，**桌面端侧**的工作，但要在手机端配对向导联调时才有意义，因此不放在 M0′ |
| 分析结果落 SQLite / AI 筛选 | 规格决策日志 #13 明确排除 |
| 改版本号、发版 | 本计划不产生用户可见变化 |
