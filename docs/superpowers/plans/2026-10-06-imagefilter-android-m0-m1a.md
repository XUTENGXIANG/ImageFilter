# ImageFilter Mobile · M0 探路 + M1a 抽核 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用真实数据验证手机端最容易失败的假设（RAW 内嵌预览覆盖率、NAS 的 WebDAV 能力），并把 `xmp.rs` 与 RAW 内嵌预览提取抽成桌面端/手机端共用的纯逻辑 crate，**桌面端行为零变化、测试全绿**。

**Architecture:** 新增 `crates/imagefilter-core`（纯逻辑，无网络、无 `cfg(target_os)`、不依赖 tauri/tokio）。`src-tauri/src/xmp.rs` 退化为 `pub use` 转发 + 3 个 tauri 命令包装；`scanner/mod.rs` 的扩展名清单改为从 core 转发。**不建仓库根 `[workspace]`**（会把 target 目录搬到根，作废桌面端既有增量产物）。

**Tech Stack:** Rust 2021（core 只依赖 `serde`）；验证靠 `cargo test --lib` 与两个探针（Rust `#[ignore]` 测试 + PowerShell 脚本）。

## Global Constraints

- **桌面端基线**：`v1.1.2`，HEAD 从 `61e455a` 起。本计划**不发布**，不动任何版本号。
- **本计划全部命令在 `A:\tenent\src-tauri` 下执行**（`cargo test --lib`、`cargo check --lib`）；探针脚本在仓库根执行。
- **`cargo test --lib` 必须在每个任务后全绿**，且通过/忽略的测试数与 Task 1 钉死的基线一致。数字不一致就是失败，不许"顺手修一下测试"。
- **源码风格**（沿用仓库现状，逐字照做）：
  - 文件头两行：`// Copyright (c) 2026 XUTENGXIANG` 与 `// SPDX-License-Identifier: MIT`
  - 注释与提交信息用**中文**；提交信息用 conventional commits（现状如 `refactor(xmp): ...`）
- **core crate 依赖只允许 `serde`**。**禁止**出现 `cfg(target_os)`，**禁止**依赖 `tauri` / `tokio` / `memmap2` / `image` / `rawler` / `zune-jpeg`。
- **桌面端改动文件不超过 4 个**：`src-tauri/src/xmp.rs`、`src-tauri/src/scanner/mod.rs`、`src-tauri/src/scanner/images.rs`、`src-tauri/Cargo.toml`。**禁止**顺手改 `App.tsx` / `useScanner.ts` / `index.css` / `analyzer.rs` / `importer.rs`。
- **搬迁不许改逻辑**。本计划只允许三处"行为等价改造"，且每处都必须有测试覆盖：
  1. 内嵌预览入口由 `&Path` 改为 `&[u8]`（Task 5）
  2. 边车命名规则抽成纯函数（Task 6）
  3. 4 个项的**可见性**由私有放宽为 `pub`（Task 4，为了让命令包装留在 `src-tauri`）
- 规格出处：[2026-10-06-imagefilter-android-design.md](../specs/2026-10-06-imagefilter-android-design.md) §3.3 / §4.2 / §8.4 / §12。

---

## 范围说明：本计划只做 M0 + M1a

规格里的 M1 在本计划中拆成两半，因为验收条件完全不同、且可分别否决：

| | 内容 | 验收方式 | 在哪 |
|---|---|---|---|
| **M1a**（本计划） | core crate + 桌面端 shim + `&[u8]` 入口 + 边车命名纯函数 | `cargo test --lib`（**不需要安卓工具链**） | Task 4–6 |
| M1b（下个计划） | `crates/imagefilter-mobile`（UniFFI cdylib）+ Android 工程骨架 + `cargo-ndk` | `cargo ndk` 出 so、Gradle 出 APK、真机/模拟器调到 | 下个计划 Task 1 |

理由：M1b 需要 NDK + Gradle，本机现在没有安卓工具链；把它混进来会让"桌面端有没有被改坏"这个最关键的信号被工具链问题淹没。**故意不写 M1b 的代码**——UniFFI 的宏形态必须用真实编译器与真实 bindgen 输出锁死，而不是照记忆写。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `crates/imagefilter-core/Cargo.toml` | 创建 | 只有 `serde` 一个依赖 |
| `crates/imagefilter-core/src/lib.rs` | 创建 | crate 根与三条纪律的文档 |
| `crates/imagefilter-core/src/extensions.rs` | 创建 | 扩展名清单的**唯一来源**（Task 4） |
| `crates/imagefilter-core/src/xmp.rs` | 创建（`git mv` 而来） | 边车解析/合并/写入（原 `src-tauri/src/xmp.rs`） |
| `crates/imagefilter-core/src/raw_preview.rs` | 创建（Task 5） | 内嵌 JPEG 提取 + `jpeg_dimensions`（纯字节运算） |
| `src-tauri/src/xmp.rs` | 改写为 shim | `pub use` 转发 + 3 个 tauri 命令 |
| `src-tauri/src/scanner/mod.rs` | 修改 | 扩展名清单改为 `pub(crate) use` 转发 |
| `src-tauri/src/scanner/images.rs` | 修改 | 内嵌预览只留 mmap 包装；新增 M0-A 探针（Task 2） |
| `src-tauri/Cargo.toml` | 修改 | 加 core 的 path 依赖 |
| `docs/evidence/scripts/probe-webdav.ps1` | 创建（Task 3） | M0-B：NAS 能力探测 |
| `docs/evidence/raw-embedded-preview/REPORT.txt` | 创建（Task 2 产出） | M0-A 实测结果 |
| `docs/evidence/webdav-probe/REPORT.txt` | 创建（Task 3 产出） | M0-B 实测结果 |

---

### Task 1: 钉死测试基线并订正文档

**为什么先做这个**：后面每个任务的验收线都是"测试数与基线一致"。而交接文档自己就不一致（§7 写"26 活跃 + 3 忽略"、§9.1 写"66 活跃 + 3 忽略"），必须先有实测数字，否则"一致"无从判断。

**Files:**
- Modify: `docs/ImageFilter-交接与上手.md`（§7 第一段、§9.1 第一条勾选项）

**Interfaces:**
- Produces: 基线三元组 `(<passed>, <failed>, <ignored>)`，Task 4/5/6 全部引用它。

- [ ] **Step 1: 跑基线（首次会编译整个依赖树，可能几分钟）**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: 形如 `test result: ok. 66 passed; 0 failed; 3 ignored; 0 measured; 0 filtered out`。
把实际三个数字抄下来，下面记作 **BASELINE = (<passed>, <failed>, <ignored>)**。

- [ ] **Step 2: 核对 `#[test]` 总数与基线自洽**

```powershell
cd A:\tenent
(Get-ChildItem src-tauri\src -Recurse -Filter *.rs | Select-String -Pattern '#\[test\]' -AllMatches).Count
```

Expected: 等于 `passed + ignored`。若不等，说明有 `#[cfg(test)]` 下的测试被条件排除——记下来，但**不要为了对齐而改代码**。

- [ ] **Step 3: 把实测数字写进交接文档**

把 `docs/ImageFilter-交接与上手.md` 里两处数字统一为实测值：
- §7 末尾"单测最终分布（**26 活跃 + 3 忽略**）"→ 改成实测
- §9.1 第一行"`cargo test --lib`（**66 活跃 + 3 忽略**）"→ 改成实测

同时在该文件 §8 的代码块上方加一行：`> 基线(<日期>)：<passed> 活跃 + <ignored> 忽略 —— 改动后必须与此一致。`

- [ ] **Step 4: 确认文档改动没有把别的说法带歪**

```powershell
cd A:\tenent
git diff --stat docs/ImageFilter-交接与上手.md
```

Expected: 只有该文件，插入/删除行数都在个位数。

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add docs/ImageFilter-交接与上手.md
git commit -m "docs: 钉死单测基线数字(交接文档 §7 与 §9.1 此前互相矛盾)"
```

---

### Task 2: M0-A · RAW 内嵌预览覆盖率探针

**为什么必须有**：手机端不做 RAW 全解码，预览的**唯一**来源就是内嵌 JPEG。桌面端还有 WIC/rawler 兜底，**手机端没有**（规格 §9 风险 1）。交接文档 §6 已把 CR3/RAF 列为"未验证、无真实样本"。这个探针回答"这条路在你的真实样张上到底走不走得通"，**结论会直接改设计**。

**Files:**
- Modify: `src-tauri/src/scanner/images.rs`（在文件末尾 `mod tests` 内追加一个 `#[ignore]` 探针）
- Create: `docs/evidence/raw-embedded-preview/REPORT.txt`（实测输出）

**Interfaces:**
- Consumes: 同文件内的私有函数 `extract_largest_preview_full(&Path) -> Option<(u32, u32, Vec<u8>)>`（**当前签名仍是 `&Path`**，Task 5 才改成 `&[u8]`）
- Produces: `REPORT.txt`，其中必须含一行 `内嵌预览覆盖率: N/M (P%)`

- [ ] **Step 1: 追加探针测试**

在 `src-tauri/src/scanner/images.rs` 的 `mod tests { ... }` 末尾（现有 `jpeg_dimensions_rejects_non_jpeg_and_tiny_input` 之后、模块右花括号之前）追加：

```rust
    /// 探针(M0-A, 默认忽略): RAW **内嵌预览覆盖率**实测。
    ///
    /// 为什么必须先做: 手机端不做 RAW 全解码, 预览的唯一来源就是内嵌 JPEG
    /// (桌面端还有 WIC/rawler 兜底, 手机端没有)。这个探针回答"这条路在你的
    /// 真实样张上到底走不走得通"。
    ///
    /// 运行: $env:IMAGEFILTER_PROBE_DIR="D:\样张"; cargo test --lib probe_embedded_preview -- --ignored --nocapture
    #[test]
    #[ignore = "M0 probe — 需要真实 RAW 样张目录"]
    fn probe_embedded_preview() {
        let dir = match std::env::var("IMAGEFILTER_PROBE_DIR") {
            Ok(v) if !v.is_empty() => v,
            _ => {
                println!("未设置 IMAGEFILTER_PROBE_DIR, 跳过");
                return;
            }
        };

        let mut total = 0usize;
        let mut found = 0usize;
        println!(
            "{:<32} {:>7} {:>5} {:>11} {:>8} {:>6}",
            "file", "MB", "raw", "embedded", "jpegMB", "ms"
        );

        for entry in walkdir::WalkDir::new(&dir).into_iter().filter_map(|e| e.ok()) {
            if !entry.file_type().is_file() {
                continue;
            }
            let p = entry.path();
            let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
            let is_raw = crate::scanner::RAW_EXTENSIONS.contains(&ext.as_str());
            // RAW 是主角, JPEG 一起跑一遍作对照(JPEG 不该走内嵌提取这条路)
            if !is_raw && !matches!(ext.as_str(), "jpg" | "jpeg") {
                continue;
            }
            total += 1;
            let size = std::fs::metadata(p).map(|m| m.len()).unwrap_or(0);
            let name = p
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            let t0 = std::time::Instant::now();
            let got = extract_largest_preview_full(p);
            let ms = t0.elapsed().as_millis();
            match got {
                Some((w, h, bytes)) => {
                    found += 1;
                    println!(
                        "{:<32} {:>7.1} {:>5} {:>11} {:>8.2} {:>6}",
                        name,
                        size as f64 / 1_048_576.0,
                        if is_raw { "yes" } else { "no" },
                        format!("{}x{}", w, h),
                        bytes.len() as f64 / 1_048_576.0,
                        ms
                    );
                }
                None => println!(
                    "{:<32} {:>7.1} {:>5} {:>11} {:>8} {:>6}",
                    name,
                    size as f64 / 1_048_576.0,
                    if is_raw { "yes" } else { "no" },
                    "MISS",
                    "-",
                    ms
                ),
            }
        }

        let pct = if total == 0 { 0.0 } else { found as f64 * 100.0 / total as f64 };
        println!("\n内嵌预览覆盖率: {}/{} ({:.0}%)", found, total, pct);
    }
```

- [ ] **Step 2: 确认探针能编译，且默认不跑**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: 仍是 BASELINE（新探针带 `#[ignore]`，`ignored` 数 +1）。若 `ignored` 没变，说明 `#[ignore]` 没生效，检查注解位置。

- [ ] **Step 3: 让用户指定样张目录**

**这一步需要用户提供信息。** 问用户：本机哪个目录放着你**真实拍过的 RAW**（越杂越好：ARW / CR3 / RAF / NEF 各来几张；再放几张 JPEG 作对照）。**不要**用应用缓存目录里的解码产物当样本——那测不出容器格式的问题。

- [ ] **Step 4: 跑探针并落证据**

```powershell
cd A:\tenent\src-tauri
$env:IMAGEFILTER_PROBE_DIR = "<用户给的样张目录>"
cargo test --lib probe_embedded_preview -- --ignored --nocapture 2>&1 | Tee-Object -FilePath ..\docs\evidence\raw-embedded-preview\REPORT.txt
```

Expected: 打印每张文件的 `embedded` 列与末行覆盖率。**若覆盖率不是 100%，把 `MISS` 的文件名与扩展名单独记进 REPORT.txt 顶部**——那正是设计要改的地方。

- [ ] **Step 5: 在 REPORT.txt 顶部写结论（三行以内）**

格式固定，便于后来人一眼看到：

```
# M0-A RAW 内嵌预览覆盖率 · <日期> · 样张来源 <目录>
结论: <覆盖率> / 是否支持各格式 / 单张耗时中位数
影响: <若 <100%, 列出不支持的扩展名, 并写明"这些格式在手机端会显示占位而不是预览">
```

- [ ] **Step 6: 确认探针不影响正常测试，然后提交**

```powershell
cd A:\tenent\src-tauri
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cd A:\tenent
git add src-tauri/src/scanner/images.rs docs/evidence/raw-embedded-preview/REPORT.txt
git commit -m "test(probe): 加 RAW 内嵌预览覆盖率探针(M0-A) 并归档实测结果"
```

---

### Task 3: M0-B · WebDAV 能力探测脚本

**为什么必须有**：规格附录 A 的 11 条假设全部只有真 NAS 能回答，其中 #2（Range）、#3（`MOVE` 的 `Overwrite: F` 是否真被遵守）、#1（有无 `getetag`）不成立就会**改数据流设计**。

**Files:**
- Create: `docs/evidence/scripts/probe-webdav.ps1`
- Create: `docs/evidence/webdav-probe/REPORT.txt`（用户跑完后入库）

**Interfaces:**
- Produces: `REPORT.txt`，必须逐条回答规格附录 A 的 #1–#5、#8–#11

- [ ] **Step 1: 写探测脚本**

创建 `docs/evidence/scripts/probe-webdav.ps1`：

```powershell
# Copyright (c) 2026 XUTENGXIANG
# SPDX-License-Identifier: MIT
<#
探测 WebDAV 服务端能力 —— 对应 docs/superpowers/specs/2026-10-06-imagefilter-android-design.md 附录 A。

默认**只读**: 只发 OPTIONS / PROPFIND / 带 Range 的 GET, 不往 NAS 写任何东西。
加 -WriteTest 才会创建临时文件(验证 PUT / If-Match / MOVE 覆盖 / 中文名),
并且在 finally 里删掉自己创建的每一项。

用法:
  $c = Get-Credential
  pwsh docs/evidence/scripts/probe-webdav.ps1 -Url https://nas:5006/photos -Credential $c
  pwsh docs/evidence/scripts/probe-webdav.ps1 -Url https://nas:5006/photos -Credential $c -WriteTest
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Url,
  [System.Management.Automation.PSCredential]$Credential,
  [string]$User,
  [string]$Password,
  [string]$SamplePath,          # 相对 $Url 的一个文件, 用于 Range 测试; 不填则自动挑第一个
  [switch]$WriteTest,
  [switch]$SkipCertificateCheck
)

$ErrorActionPreference = 'Stop'
$base = $Url.TrimEnd('/')

if ($Credential) {
  $plain = $Credential.GetNetworkCredential().Password
  $who   = $Credential.UserName
} else {
  if (-not $User) { throw "需要 -Credential 或 -User/-Password" }
  $plain = $Password
  $who   = $User
}
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$who`:$plain"))

$common = @{
  Headers             = @{ Authorization = $auth }
  TimeoutSec          = 40
  SkipHttpErrorCheck  = $true
  MaximumRedirection  = 0
}
if ($SkipCertificateCheck) { $common['SkipCertificateCheck'] = $true }

function Invoke-Dav {
  param([string]$Method, [string]$Path = '', [hashtable]$Headers = @{}, [string]$Body, [string]$ContentType)
  $p = @{} + $common
  $p['Uri']    = if ($Path) { "$base/$($Path.TrimStart('/'))" } else { $base }
  $p['Method'] = $Method
  foreach ($k in $Headers.Keys) { $p['Headers'][$k] = $Headers[$k] }
  if ($Body) { $p['Body'] = $Body }
  if ($ContentType) { $p['ContentType'] = $ContentType }
  $t0 = Get-Date
  try {
    $r = Invoke-WebRequest @p
    return [pscustomobject]@{
      Status = [int]$r.StatusCode
      Headers = $r.Headers
      Content = $r.Content
      Ms     = [int]((Get-Date) - $t0).TotalMilliseconds
      Bytes  = if ($r.RawContentLength -ge 0) { $r.RawContentLength } else { 0 }
      Error  = $null
    }
  } catch {
    return [pscustomobject]@{
      Status = 0; Headers = @{}; Content = ''; Bytes = 0
      Ms     = [int]((Get-Date) - $t0).TotalMilliseconds
      Error  = $_.Exception.Message
    }
  }
}

$created = New-Object System.Collections.Generic.List[string]
function Cleanup {
  foreach ($p in $created) {
    try { Invoke-Dav -Method DELETE -Path $p | Out-Null; Write-Host "  已清理 $p" } catch { }
  }
}

$propfindBody = @'
<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop>
  <D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:getetag/>
</D:prop></D:propfind>
'@

Write-Host "=== 目标: $base ==="
try {

  # ── 1. OPTIONS: DAV 头与允许的方法 ──────────────────────────────
  $r = Invoke-Dav -Method OPTIONS
  Write-Host "`n[1] OPTIONS"
  Write-Host "    status      : $($r.Status)   ($($r.Ms) ms)   $($r.Error)"
  Write-Host "    DAV         : $($r.Headers['DAV'])"
  Write-Host "    Allow       : $($r.Headers['Allow'])"

  # ── 2. PROPFIND depth=1: 属性、耗时、响应体积 ───────────────────
  $r = Invoke-Dav -Method PROPFIND -Headers @{ Depth = '1' } -Body $propfindBody -ContentType 'application/xml; charset=utf-8'
  $nResp = ([regex]::Matches($r.Content, '<[^>]*:?response[\s>]')).Count
  Write-Host "`n[2] PROPFIND depth=1"
  Write-Host "    status      : $($r.Status)   ($($r.Ms) ms)"
  Write-Host "    条目数      : $nResp"
  Write-Host "    响应体积    : $([math]::Round($r.Bytes / 1024, 1)) KB"
  foreach ($prop in 'getetag', 'getlastmodified', 'getcontentlength') {
    Write-Host "    含 $prop : $($r.Content -match $prop)"
  }

  # ── 3. 挑一个样张文件 ───────────────────────────────────────────
  $sample = $SamplePath
  if (-not $sample) {
    $m = [regex]::Matches($r.Content, '<[^>]*:?href>([^<]+)</[^>]*:?href>')
    $cand = foreach ($x in $m) {
      $h = [System.Uri]::UnescapeDataString($x.Groups[1].Value)
      if ($h -match '\.(jpg|jpeg|arw|cr2|cr3|nef|dng|orf|rw2|raf|pef|srw)$') { $h }
    }
    $sample = @($cand)[0]
  }
  Write-Host "`n[3] Range 测试样例: $sample"

  # ── 4. Range: RAW 取文件头是手机端预览的生命线 ──────────────────
  if ($sample) {
    $r = Invoke-Dav -Method GET -Path $sample -Headers @{ Range = 'bytes=0-262143' }
    Write-Host "`n[4] GET with Range: bytes=0-262143"
    Write-Host "    status      : $($r.Status)  (期望 206; 200 = 不支持 Range, 会整张下载)"
    Write-Host "    Content-Range: $($r.Headers['Content-Range'])"
    Write-Host "    Content-Length: $($r.Headers['Content-Length'])"
  }

  # ── 5. 写测试(可选) ────────────────────────────────────────────
  if ($WriteTest) {
    $stamp = (Get-Date -Format 'yyyyMMdd_HHmmss')
    $tmp   = ".imagefilter-probe-$stamp.txt"
    $tmp2  = ".imagefilter-probe-$stamp-moved.txt"
    $cn    = ".imagefilter-probe-中文名-$stamp.txt"

    $r = Invoke-Dav -Method PUT -Path $tmp -Body 'if' -ContentType 'text/plain'
    Write-Host "`n[5] PUT 新文件"
    Write-Host "    status      : $($r.Status)  (期望 201/204)"
    if ($r.Status -in 200, 201, 204) { $created.Add($tmp) }

    $r = Invoke-Dav -Method PUT -Path $tmp -Body 'if2' -ContentType 'text/plain' -Headers @{ 'If-Match' = '"does-not-match"' }
    Write-Host "`n[6] PUT 带假 If-Match"
    Write-Host "    status      : $($r.Status)  (期望 412; 200/204 = 不校验 If-Match → 冲突检测要降级)"

    $r = Invoke-Dav -Method PUT -Path $cn -Body 'cn' -ContentType 'text/plain'
    Write-Host "`n[7] PUT 中文文件名"
    Write-Host "    status      : $($r.Status)"
    if ($r.Status -in 200, 201, 204) { $created.Add($cn) }
    $r = Invoke-Dav -Method PROPFIND -Headers @{ Depth = '0' } -Body $propfindBody -ContentType 'application/xml; charset=utf-8' -Path $cn
    $href = if ($r.Content -match '<[^>]*:?href>([^<]+)<') { $Matches[1] } else { '(未取到)' }
    Write-Host "    href 原文    : $href"
    Write-Host "    → 若 href 里中文是 %XX 百分号编码则为 NFC/UTF-8 正常; 若是乱码则需要额外处理"

    # MOVE 撞已有文件: 拷贝一份自己当障碍
    $r = Invoke-Dav -Method MOVE -Path $tmp -Headers @{ Destination = "$base/$tmp2"; Overwrite = 'F' }
    Write-Host "`n[8] MOVE → 新名字 (Overwrite: F)"
    Write-Host "    status      : $($r.Status)  (期望 201/204)"
    if ($r.Status -in 200, 201, 204) { $created.Add($tmp2) }

    $r = Invoke-Dav -Method MOVE -Path $tmp2 -Headers @{ Destination = "$base/$tmp"; Overwrite = 'F' }
    Write-Host "`n[9] MOVE → 已存在的目标 (Overwrite: F)"
    Write-Host "    status      : $($r.Status)  (期望 412 或 409; 201/204 = 服务端忽略该头 → 必须先 PROPFIND 查目标是否存在)"
    if ($r.Status -in 200, 201, 204) { $created.Add($tmp) }
  } else {
    Write-Host "`n[5-9] 跳过写测试(未加 -WriteTest)。这四项对应规格附录 A 的 #3/#4/#5/#10, 必须补跑。"
  }

} finally {
  Cleanup
}

Write-Host "`n=== 逐条对照规格附录 A #1-#5、#8-#11 填写结论 ==="
```

- [ ] **Step 2: 静态检查脚本语法**

```powershell
cd A:\tenent
$null = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path docs\evidence\scripts\probe-webdav.ps1), [ref]$null, [ref]$errs)
$errs
```

Expected: 无输出（无语法错误）。有输出就先修语法。

- [ ] **Step 3: 先对本地假服务端跑一遍（不需要 NAS）**

验证脚本本身没写错，用一个本地 WebDAV 服务当靶子。若本机没有 `rclone`，装 Python 版：

```powershell
python -m pip install --quiet wsgidav cheroot
mkdir -Force A:\tenent\.scratch\webdav-root | Out-Null
Start-Job -Name dav -ScriptBlock { python -m wsgidav.server.server_cli --host=127.0.0.1 --port=18080 --root=A:\tenent\.scratch\webdav-root --auth=anonymous }
Start-Sleep -Seconds 3
pwsh docs/evidence/scripts/probe-webdav.ps1 -Url http://127.0.0.1:18080 -User x -Password y -WriteTest
```

Expected: 每项都有输出（status 有值、不是 0）。`[4]` 的 status 可能是 200（wsgidav 对 Range 的处理与 NAS 不同，属正常）。**这一步只验证脚本能跑通，不记录结论。**

```powershell
Stop-Job -Name dav; Remove-Job -Name dav
```

- [ ] **Step 4: 让用户填 NAS 信息并对真机跑**

**这一步需要用户提供信息。** 问用户：WebDAV 的完整地址（含端口与根目录）、账号，以及一个可用来做 Range 测试的文件相对路径。**先不加 `-WriteTest`**（只读）跑一次。

```powershell
cd A:\tenent
$c = Get-Credential
pwsh docs/evidence/scripts/probe-webdav.ps1 -Url <用户给的地址> -Credential $c 2>&1 | Tee-Object -FilePath .scratch\webdav-ro.txt
```

- [ ] **Step 5: 经用户同意后加 `-WriteTest` 跑一次**

必须**先明确告诉用户**这次会在共享目录里创建并以 `.imagefilter-probe-` 开头的临时文件（脚本会在 finally 里删掉），得到同意再跑。NAS 上可能有同步任务，写测试不是无副作用的。

```powershell
pwsh docs/evidence/scripts/probe-webdav.ps1 -Url <地址> -Credential $c -WriteTest 2>&1 | Tee-Object -FilePath .scratch\webdav-rw.txt
```

- [ ] **Step 6: 把两次输出合成 REPORT.txt 并逐条填结论**

创建 `docs/evidence/webdav-probe/REPORT.txt`：先贴两次原始输出，再用一张表逐条回答规格附录 A：

```
# M0-B NAS WebDAV 能力探测 · <日期> · 目标 <地址(去掉账号)>
| 规格附录A | 问题 | 实测 | 结论/影响 |
|---|---|---|---|
| #1 | 有 getetag? 粒度 | | |
| #2 | 支持 Range? | | 不支持 → RAW 只能整张下载, 需改设计 |
| #3 | MOVE Overwrite:F 真拒绝? | | 忽略 → 必须先 PROPFIND 查目标 |
| #4 | 支持 If-Match(412)? | | 不支持 → 冲突检测降级 |
| #5 | PUT 是否临时文件+rename | 无法直接观测, 记为"未知" | |
| #8 | 大目录 PROPFIND 耗时/体积 | | 太大 → 需分页或流式解析 |
| #9 | 自签证书? | | |
| #10 | 中文名 href 编码 | | 乱码 → 需额外规范化 |
| #11 | 单文件体积分布 | | |
```

- [ ] **Step 7: Commit**

```powershell
cd A:\tenent
# .scratch 不入库(见 .gitignore), 所以只提交脚本与报告
git add docs/evidence/scripts/probe-webdav.ps1 docs/evidence/webdav-probe/REPORT.txt
git commit -m "test(probe): 加 NAS WebDAV 能力探测脚本(M0-B) 并归档实测结果"
```

---

### Task 4: 建 core crate + 搬 xmp.rs 与扩展名清单（桌面端行为不变）

**范围说明**：扩展名清单**必须和 `xmp.rs` 同一步搬**。原因：`xmp.rs` 里 `validate_photo_path` 第 723 行用的是 `crate::scanner::SUPPORTED_EXTENSIONS`，`xmp.rs` 一离开 `src-tauri` 这条路径就失效，编译直接断。

**Files:**
- Create: `crates/imagefilter-core/Cargo.toml`
- Create: `crates/imagefilter-core/src/lib.rs`
- Create: `crates/imagefilter-core/src/extensions.rs`
- Create: `crates/imagefilter-core/src/xmp.rs`（由 `git mv` 而来）
- Modify: `src-tauri/Cargo.toml`
- Modify: `src-tauri/src/xmp.rs`（整体替换为 shim）
- Modify: `src-tauri/src/scanner/mod.rs:12-21`（两个 const → 转发）

**Interfaces:**
- Consumes: 无（本任务不依赖前三个任务的代码，只依赖 Task 1 的基线数字）
- Produces:
  - `imagefilter_core::extensions::{SUPPORTED_EXTENSIONS, RAW_EXTENSIONS}`
  - `imagefilter_core::xmp::*`（与今天 `crate::xmp` 的同名同签名，含 `pub fn read_one` / `pub fn write_one` / `pub enum Outcome` / `pub fn detail_of` 四处可见性放宽）
  - `src-tauri/src/xmp.rs` 仍导出 `read_decisions` / `write_decisions` / `probe_xmp_target` 三个 `#[tauri::command]`

- [ ] **Step 1: 建目录并整文件移动（保住 git 历史）**

```powershell
cd A:\tenent
mkdir -Force crates\imagefilter-core\src | Out-Null
git mv src-tauri/src/xmp.rs crates/imagefilter-core/src/xmp.rs
```

- [ ] **Step 2: 在移过来的文件里删掉 3 个 tauri 命令**

打开 `crates/imagefilter-core/src/xmp.rs`，删除从 `// ── 命令 ──` 那行注释（原第 905 行）开始，到 `probe_xmp_target` 函数结束（原第 958 行）的**整段**，即 `read_decisions` / `write_decisions` / `probe_xmp_target` 三个函数。`// ── 测试 ──` 及其后的 `mod tests` **原样保留**。

- [ ] **Step 3: 在 core 里放宽 4 处可见性（本任务唯一允许的改动类型）**

在 `crates/imagefilter-core/src/xmp.rs` 中，把下列 4 项由私有改为 `pub`（**只加 `pub`，不动任何一行实现**）：

| 原 | 改为 |
|---|---|
| `fn detail_of(e: XmpError) -> &'static str` | `pub fn detail_of(e: XmpError) -> &'static str` |
| `fn read_one(photo: &Path) -> DecisionRead` | `pub fn read_one(photo: &Path) -> DecisionRead` |
| `fn write_one(item: &DecisionWrite) -> Result<Outcome, XmpError>` | `pub fn write_one(item: &DecisionWrite) -> Result<Outcome, XmpError>` |
| `enum Outcome { ... }` | `pub enum Outcome { ... }` |

并把 `validate_photo_path` 里的 `crate::scanner::SUPPORTED_EXTENSIONS` 改成 `crate::extensions::SUPPORTED_EXTENSIONS`：

```rust
    if !crate::extensions::SUPPORTED_EXTENSIONS.contains(&ext.as_str()) {
        return Err(XmpError::Unknown);
    }
```

- [ ] **Step 4: 建 `extensions.rs`（扩展名清单的唯一来源）**

创建 `crates/imagefilter-core/src/extensions.rs`，内容就是把 `src-tauri/src/scanner/mod.rs:12-21` 的两个 const **原样**搬过来（值一个字都不许改，注释也带上）：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 扩展名清单 —— **唯一来源**。桌面端与手机端都从这里取, 不许在别处再写一份。

/// 能被收进网格的文件(照片 + 视频)。
/// 注意: 边车写入只接受这里的扩展名(不接受任意路径), 这是 Phase 5 的路径红线。
pub const SUPPORTED_EXTENSIONS: &[&str] = &[
    "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp",
    "arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "raf", "pef", "srw", "raw",
    "mp4", "mov", "avi", "mkv",
    "heic", "heif",
];

/// 需要走"内嵌 JPEG 提取"而不是直接解码的扩展名。
pub const RAW_EXTENSIONS: &[&str] = &[
    "arw", "cr2", "cr3", "nef", "dng", "orf", "rw2", "raf", "pef", "srw",
];
```

- [ ] **Step 5: 建 crate 根与 Cargo.toml**

`crates/imagefilter-core/src/lib.rs`：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 桌面端与手机端共用的纯逻辑。
//!
//! 三条纪律(见 docs/superpowers/specs/2026-10-06-imagefilter-android-design.md §3.2):
//!   1. 不碰网络 —— 网络 IO 全在调用方(桌面端 tauri / 手机端 Kotlin OkHttp);
//!   2. 不出现 `cfg(target_os)` —— 任何平台分支都不许进这个 crate;
//!   3. 不依赖 tauri / tokio —— 依赖只有 serde。
pub mod extensions;
pub mod xmp;
```

`crates/imagefilter-core/Cargo.toml`：

```toml
[package]
name = "imagefilter-core"
version = "1.1.2"
edition = "2021"
license = "MIT"

[dependencies]
serde = { version = "1", features = ["derive"] }
```

- [ ] **Step 6: `src-tauri/Cargo.toml` 加 path 依赖**

在 `[dependencies]` 段末尾（`serde_json = "1"` 之后）追加：

```toml
# 手机端与桌面端共用的纯逻辑。
# 刻意**不建仓库根 [workspace]**: 那会把 target 目录搬到根, 作废桌面端既有增量产物。
# 见 docs/superpowers/specs/2026-10-06-imagefilter-android-design.md §3.3
imagefilter-core = { path = "../crates/imagefilter-core" }
```

- [ ] **Step 7: `scanner/mod.rs` 改为转发**

把 `src-tauri/src/scanner/mod.rs` 第 10–21 行（`SUPPORTED_EXTENSIONS` 与 `RAW_EXTENSIONS` 两个 const 及其注释）替换为：

```rust
/// 扩展名清单已搬进 `imagefilter-core`(手机端与桌面端共用, 不许再写第二份)。
/// 这里只做转发, 所以 `browse.rs` / `images.rs` 里的 `use super::RAW_EXTENSIONS` 一行都不用改。
pub(crate) use imagefilter_core::extensions::{RAW_EXTENSIONS, SUPPORTED_EXTENSIONS};
```

（原来那段"pub(crate) 而不是私有: xmp.rs 要用它断言…"的解释搬到 `extensions.rs` 的文档注释里，已在 Step 4 体现。）

- [ ] **Step 8: 把 `src-tauri/src/xmp.rs` 整体替换为 shim**

因为文件已被 `git mv` 走，现在这个路径不存在，直接新建：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 边车(评分/色标)的 **Tauri 命令面**。
//!
//! 纯逻辑已搬进 `imagefilter-core` —— 手机端与桌面端共用同一份语义, 不会出现
//! "手机写的边车桌面端认不出"。见
//! docs/superpowers/specs/2026-10-06-imagefilter-android-design.md §3.2/§3.3。
//!
//! 本文件只做两件事: ① 转出 core 的全部公共项 ② 三个 tauri 命令包装。
//! **命令体逐字照搬搬迁前的实现**, 唯一的外来项是 core 里放宽为 pub 的
//! `read_one` / `write_one` / `Outcome` / `detail_of`(纯可见性放宽, 无逻辑改动)。

pub use imagefilter_core::xmp::*;

use std::path::Path;

/// 批量读。N 次 IPC 没有收益: 一次 spawn_blocking + 一份列表, 前端只做一次合并。
#[tauri::command]
pub async fn read_decisions(file_paths: Vec<String>) -> Result<Vec<DecisionRead>, String> {
    tokio::task::spawn_blocking(move || {
        file_paths
            .iter()
            .map(|p| read_one(Path::new(p)))
            .collect::<Vec<DecisionRead>>()
    })
    .await
    .map_err(|e| e.to_string())
}

/// 批量写。队列按 path 合并后提交(撤销风暴 = 一次 IPC 写多项)。
/// 单项失败**不影响其它项**, 全部收进 Summary.failures, 由前端按 code 决定
/// "整卷不可写就降级 off / 单文件问题就只标记不降级"。
#[tauri::command]
pub async fn write_decisions(items: Vec<DecisionWrite>) -> Result<WriteSummary, String> {
    tokio::task::spawn_blocking(move || {
        let mut summary = WriteSummary::default();
        for item in &items {
            match write_one(item) {
                Ok(Outcome::Written { degraded }) => {
                    summary.written += 1;
                    if degraded {
                        summary.degraded = true;
                    }
                }
                Ok(Outcome::Skipped) => summary.skipped += 1,
                Err(e) => {
                    summary.failed += 1;
                    summary.failures.push(WriteFailure {
                        path: item.path.clone(),
                        code: e.code().to_string(),
                        detail: detail_of(e).to_string(),
                    });
                }
            }
        }
        summary
    })
    .await
    .map_err(|e| e.to_string())
}

/// 写卡前必须确认目标可写(§5.3 红线)。前端只在 mode 变 on / 切到新目录时调一次。
#[tauri::command]
pub async fn probe_xmp_target(dir_path: String) -> Result<XmpProbe, String> {
    tokio::task::spawn_blocking(move || probe_dir(Path::new(&dir_path)))
        .await
        .map_err(|e| e.to_string())
}
```

- [ ] **Step 9: 编译并跑测试，与基线逐项对齐**

```powershell
cd A:\tenent\src-tauri
cargo check --lib
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected:
- `cargo check --lib` 无 error、无新增 warning
- `test result` 与 **BASELINE 完全一致**（`passed` / `failed` / `ignored` 三个数都不变）

若 `passed` 变少：说明 core 里的测试没被跑到。跑 `cargo test -p imagefilter-core --lib` 确认它们在那里，并且**把两个 crate 的数字合起来**与基线比对。

- [ ] **Step 10: 确认桌面端前端契约没被碰到**

```powershell
cd A:\tenent
git diff --stat
```

Expected: 只有 5 个文件（`crates/**` 新增、`src-tauri/Cargo.toml`、`src-tauri/src/scanner/mod.rs`、`src-tauri/src/xmp.rs`）。**`src/` 下一个文件都没有**，`lib.rs` 也没动。

- [ ] **Step 11: Commit**

```powershell
cd A:\tenent
git add crates/imagefilter-core src-tauri/Cargo.toml src-tauri/src/scanner/mod.rs src-tauri/src/xmp.rs
git commit -m "refactor(core): xmp 边车与扩展名清单搬入 imagefilter-core(桌面端行为不变)"
```

---

### Task 5: 内嵌预览提取搬入 core，并加 `&[u8]` 入口

**为什么改形态**：桌面端是"mmap 一个文件再扫"，手机端拿到的是一段已经下载好的字节。改成接收 `&[u8]` 之后两端都能用，且手机端不必先把 16MB 落盘。

**Files:**
- Create: `crates/imagefilter-core/src/raw_preview.rs`
- Modify: `crates/imagefilter-core/src/lib.rs`（加一行 `pub mod raw_preview;`）
- Modify: `src-tauri/src/scanner/images.rs`（删掉搬走的 3 个函数与 3 个测试；保留 mmap 包装；新增 1 个字节级测试）

**Interfaces:**
- Consumes: `imagefilter_core::extensions::RAW_EXTENSIONS`（Task 4 产出）
- Produces:
  - `imagefilter_core::raw_preview::{extract_largest_preview_full, extract_largest_preview_bytes, jpeg_dimensions}`，三个都接收 `&[u8]`
  - `src-tauri/src/scanner/images.rs` 内保留私有 `extract_largest_preview_full(&Path)`（**同名同签名**，内部改为 mmap 后调 core），因此 `images.rs` 里 261 行与 355 行的两个调用点**不用改**
  - 供 Task 6 之后、以及下个计划里手机构建 `Preview` 用的字节级入口

- [ ] **Step 1: 先写会失败的测试（core 侧）**

创建 `crates/imagefilter-core/src/raw_preview.rs`，只写文件头、常量与测试：

```rust
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! RAW 容器内嵌 JPEG 的提取 —— **纯字节运算**: 无 IO、无平台依赖。
//!
//! 桌面端先 mmap 再喂进来(`src-tauri/src/scanner/images.rs`), 手机端把 `Range`
//! 取回的文件头字节直接喂进来, 不需要先落盘 16MB。

/// 内层 EOI 扫描的上限。无 EOI 时跳过该 SOI 签名, 避免坏文件退化成 O(n²)。
pub const MAX_JPEG_LOOKAHEAD: usize = 16 * 1024 * 1024;

#[cfg(test)]
mod tests {
    use super::*;

    /// 造一个最小的合法 JPEG(带真 SOF0), 用来验证扫描能挑出它并读出宽高。
    fn tiny_jpeg(w: u16, h: u16) -> Vec<u8> {
        let mut v = vec![0xFF, 0xD8]; // SOI
        v.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x11, 0x08]); // SOF0, 段长 17, 精度 8
        v.extend_from_slice(&h.to_be_bytes());
        v.extend_from_slice(&w.to_be_bytes());
        v.extend_from_slice(&[0x03]); // 3 个分量
        v.extend_from_slice(&[0u8; 9]); // 分量描述, 填够段长
        v.extend_from_slice(&[0xFF, 0xD9]); // EOI
        v
    }

    #[test]
    fn jpeg_dimensions_reads_encoded_jpeg() {
        let buf = tiny_jpeg(64, 48);
        assert_eq!(jpeg_dimensions(&buf), Some((64, 48)));
    }

    #[test]
    fn jpeg_dimensions_rejects_non_jpeg_and_tiny_input() {
        assert_eq!(jpeg_dimensions(&[]), None);
        assert_eq!(jpeg_dimensions(&[0xFF, 0xD8]), None);
        assert_eq!(jpeg_dimensions(b"\x89PNG\r\n\x1a\n"), None);
    }

    /// 回归护栏: 样本取自真实损坏缓存文件 `preview_v3/b19755bbbe7f2164_prev.jpg` 的开头字节
    /// (`ff d8 ff 7d ...`)。这个流里没有合法的段结构, 但**深处藏着一组像 SOF 的字节** ——
    /// 旧的"扫到 0xFF 就当段头"实现会把它当成 SOF 并解析出假的宽高, 于是坏数据被当成
    /// 合法预览缓存下来(查看器空白)且被分析器静默跳过。严格遍历必须拒绝。
    #[test]
    fn jpeg_dimensions_rejects_corrupt_stream_with_plausible_sof() {
        let mut buf = vec![0u8; 32 * 1024];
        let header = [
            0xFFu8, 0xD8, 0xFF, 0x7D, 0x60, 0x64, 0x4E, 0x4C, 0x2D, 0x20, 0x92, 0xAA,
        ];
        buf[..header.len()].copy_from_slice(&header);

        let fake_sof_at = 25_000;
        buf[fake_sof_at] = 0xFF;
        buf[fake_sof_at + 1] = 0xC0;
        buf[fake_sof_at + 2] = 0x00;
        buf[fake_sof_at + 3] = 0x11;
        buf[fake_sof_at + 4] = 0x08;
        buf[fake_sof_at + 5] = 0x10;
        buf[fake_sof_at + 6] = 0x00;
        buf[fake_sof_at + 7] = 0x20;
        buf[fake_sof_at + 8] = 0x00;

        assert_eq!(
            jpeg_dimensions(&buf),
            None,
            "损坏的 JPEG 流不应解析出宽高(否则会被当成合法预览缓存)"
        );
    }

    /// 新增(手机端特有路径): 从字节里挑出**分辨率最大**的那张内嵌 JPEG,
    /// 并且小图不达标时 `extract_largest_preview_bytes` 要返回 None(>=600px 才要)。
    #[test]
    fn extract_preview_picks_largest_and_enforces_min_edge() {
        let small = tiny_jpeg(320, 240);
        let big = tiny_jpeg(1600, 1200);

        let mut blob = Vec::new();
        blob.extend_from_slice(b"II*\0\x08\0\0\0"); // 假装是 TIFF 头
        blob.extend_from_slice(&small);
        blob.extend_from_slice(&[0u8; 32]);
        blob.extend_from_slice(&big);

        let (w, h, bytes) = extract_largest_preview_full(&blob).expect("应能挑出内嵌 JPEG");
        assert_eq!((w, h), (1600, 1200));
        assert_eq!(bytes, big, "返回的应是那张大图本身的字节");

        assert!(extract_largest_preview_bytes(&blob).is_some());

        let only_small = {
            let mut b = Vec::new();
            b.extend_from_slice(&small);
            b
        };
        assert!(
            extract_largest_preview_bytes(&only_small).is_none(),
            "长边 < 600 应返回 None(宁可小图不空白的前提是它得够大)"
        );
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test -p imagefilter-core --lib
```

Expected: 编译失败，报 `cannot find function `jpeg_dimensions`` / `extract_largest_preview_full`` / `extract_largest_preview_bytes``。

- [ ] **Step 3: 从 `images.rs` 搬实现过来**

把 `src-tauri/src/scanner/images.rs` 的这三段**逐字搬**进 `crates/imagefilter-core/src/raw_preview.rs`（放在 `MAX_JPEG_LOOKAHEAD` 之后、`mod tests` 之前），只改入口形态：

1. `fn extract_largest_preview_full(path: &std::path::Path)`（原 317–351 行）→ 改签名为 `pub fn extract_largest_preview_full(data: &[u8]) -> Option<(u32, u32, Vec<u8>)>`，删掉开头三行 mmap 代码（`File::open` / `Mmap::map` / `let data = &map[..];`），其余逐字不动（含那句"无 EOI（坏文件）→ 跳过该签名"的注释）。
2. `fn extract_largest_preview_bytes(path: &std::path::Path)`（原 354–358 行）→ `pub fn extract_largest_preview_bytes(data: &[u8]) -> Option<Vec<u8>>`，体内调用改为 `extract_largest_preview_full(data)?`。
3. `fn jpeg_dimensions(data: &[u8])`（原 377–433 行）→ 加 `pub`，**函数体逐字不动**（那段"为什么不能扫到 0xFF 就当段头"的注释必须保留）。
4. 顺手把 `MAX_JPEG_LOOKAHEAD` 从 `images.rs:316` 删掉（已由本文件提供）。

- [ ] **Step 4: 跑 core 的测试确认通过**

```powershell
cd A:\tenent\src-tauri
cargo test -p imagefilter-core --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected: 全绿。此时应有 4 个测试（原 3 个 + 新增 1 个）在本文件里。

- [ ] **Step 5: `lib.rs` 导出模块**

`crates/imagefilter-core/src/lib.rs` 的 `pub mod xmp;` 之后加一行：

```rust
pub mod raw_preview;
```

- [ ] **Step 6: `images.rs` 只留一个 mmap 包装**

在 `src-tauri/src/scanner/images.rs` 里，删掉原 317–358 行与 377–433 行（已搬走），并在原位置留下包装：

```rust
/// 桌面端入口: mmap 之后再交给 core 扫 —— 手机端没有文件, 所以扫描逻辑本身
/// 接收 `&[u8]`(见 imagefilter_core::raw_preview)。签名与搬迁前一致, 调用点不用改。
fn extract_largest_preview_full(path: &std::path::Path) -> Option<(u32, u32, Vec<u8>)> {
    let file = std::fs::File::open(path).ok()?;
    let map = unsafe { memmap2::Mmap::map(&file).ok()? };
    imagefilter_core::raw_preview::extract_largest_preview_full(&map[..])
}

/// 预览用: 任意内嵌JPEG都返回（>=600px即可, 宁可小图不空白）
/// **逐字保留搬迁前的实现**: 它调本文件的 `extract_largest_preview_full`, 只开一次文件。
fn extract_largest_preview_bytes(path: &std::path::Path) -> Option<Vec<u8>> {
    let (w, h, bytes) = extract_largest_preview_full(path)?;
    if w.max(h) < 600 { return None; }
    Some(bytes)
}
```

（桌面端这两个包装加起来就是全部改动：**函数体只换了被调用者**。core 里那个接收 `&[u8]` 的 `extract_largest_preview_bytes` 供手机端使用，与桌面端这个同名不同参数，注意别混。）

同时删掉 `images.rs` 里搬走的 3 个测试（原 641–692 行的 `jpeg_dimensions_reads_encoded_jpeg`、`jpeg_dimensions_rejects_corrupt_stream_with_plausible_sof`、`jpeg_dimensions_rejects_non_jpeg_and_tiny_input`），它们已在 core 里。

- [ ] **Step 7: 处理 `images.rs` 的 `image` 适配（若编译报错）**

`load_analysis_image`（原 361 行）与 `extract_raw_preview`（原 532 行）仍在用 `extract_largest_preview_bytes(path)`，签名没变，**不用改**。若编译器还报出别的引用点，逐个改成 `imagefilter_core::raw_preview::jpeg_dimensions(...)`，**不要新增本地副本**。

- [ ] **Step 8: 前后端测试全绿**

```powershell
cd A:\tenent\src-tauri
cargo check --lib
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
cargo test -p imagefilter-core --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected:
- `cargo check --lib` 无 error
- **两个 crate 的 `passed` 之和 = BASELINE.passed**，`failed = 0`，`ignored` 不变（Task 2 的探针 +1）

- [ ] **Step 9: 桌面端手测一遍 RAW 预览（这一条不能省）**

```powershell
cd A:\tenent
npx tauri dev
```

手测：插卡或指向有 RAW 的目录 → 点进文件夹 → ① 网格能出缩略图；② 双击 RAW 能打开查看器并显示大图；③ 挑一张 RAW 打开"分析"，确认不报错。
（理由：`extract_largest_preview_full` 是 RAW 预览与分析的公共入口，编译过不代表路径没断。）

- [ ] **Step 10: Commit**

```powershell
cd A:\tenent
git add crates/imagefilter-core/src/raw_preview.rs crates/imagefilter-core/src/lib.rs src-tauri/src/scanner/images.rs
git commit -m "refactor(core): 内嵌预览提取搬入 imagefilter-core 并改为接收 &[u8]"
```

---

### Task 6: 边车命名规则抽成纯函数

**为什么**：手机端要自己决定"读哪个边车、写哪个边车"。今天这套规则同时以**两处代码 + 两条注释**存在于 `xmp.rs:681` 与 `importer.rs:741`，再复制第三份到 Kotlin 就是必然分叉。抽成纯函数后两端共用一个实现。

**Files:**
- Modify: `crates/imagefilter-core/src/xmp.rs`（新增 2 个 pub 函数 + 2 个测试；`sidecar_candidates` / `write_target` 改为调用它们）

**Interfaces:**
- Consumes: `imagefilter_core::xmp::*`（Task 4 产出）
- Produces:
  - `pub fn sidecar_names(file_name: &str) -> Vec<String>`
  - `pub fn write_target_index(has_first: bool, has_second: bool) -> u8`
  - `sidecar_candidates(&Path) -> Vec<PathBuf>` 与 `write_target(&Path) -> Result<PathBuf, XmpError>` **签名不变**（`importer.rs` 与既有测试不受影响）

- [ ] **Step 1: 先写会失败的测试**

在 `crates/imagefilter-core/src/xmp.rs` 的 `mod tests { ... }` 末尾追加：

```rust
    /// 边车命名规则 —— 手机端与桌面端共用的**唯一**来源。
    #[test]
    fn sidecar_names_follows_stem_then_full_name() {
        assert_eq!(
            sidecar_names("IMG_1234.ARW"),
            vec!["IMG_1234.xmp".to_string(), "IMG_1234.ARW.xmp".to_string()]
        );
        // 无扩展名: stem == 整个名字, 两个候选重合 —— 与搬迁前 sidecar_candidates 的行为一致
        assert_eq!(
            sidecar_names("noext"),
            vec!["noext.xmp".to_string(), "noext.xmp".to_string()]
        );
    }

    /// 写目标下标: 跟随用户既有习惯 —— 已经有 `full.xmp` 就写它, 否则写 `stem.xmp`。
    /// 绝不两个都写(否则别家软件只看其中一个, 会出现"两个边车各说各话")。
    #[test]
    fn write_target_index_prefers_existing_full_name_sidecar() {
        assert_eq!(write_target_index(false, true), 1);
        assert_eq!(write_target_index(true, false), 0);
        assert_eq!(write_target_index(true, true), 0);
        assert_eq!(write_target_index(false, false), 0);
    }
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\src-tauri
cargo test -p imagefilter-core --lib sidecar_names
```

Expected: 编译失败，`cannot find function `sidecar_names``。

- [ ] **Step 3: 实现两个纯函数**

在 `crates/imagefilter-core/src/xmp.rs` 里 `sidecar_candidates` 之前插入：

```rust
/// 边车候选名(按优先级): ①`stem.xmp`(RAW 约定, 也是我们写的那份)
/// ②`file_name.xmp`(Lightroom 对非 RAW 的写法)。
///
/// 抽成**纯字符串**函数是为了让手机端共用同一套规则 —— 这套规则今天已经以
/// "代码 + 对照注释"的形式存在于 xmp.rs 与 importer.rs 两处, 不许再有第三处。
pub fn sidecar_names(file_name: &str) -> Vec<String> {
    let mut v = Vec::new();
    if let Some(stem) = Path::new(file_name).file_stem() {
        v.push(format!("{}.xmp", stem.to_string_lossy()));
    }
    v.push(format!("{}.xmp", file_name));
    v
}

/// 写目标的下标: 跟随用户既有习惯 —— 已经有 ② 且没有 ① 就写 ②, 否则写 ①。
/// 绝不两个都写(否则别家软件只看其中一个, 会出现"两个边车各说各话")。
pub fn write_target_index(has_first: bool, has_second: bool) -> u8 {
    if has_second && !has_first { 1 } else { 0 }
}
```

- [ ] **Step 4: 让 `sidecar_candidates` / `write_target` 走纯函数**

把现有 `sidecar_candidates`（原 681–694 行）整体替换为：

```rust
/// 边车候选: ①`stem.xmp`(RAW 约定, 也是我们写的那份) ②`file_name.xmp`
/// (Lightroom 对非 RAW 的写法)。与 importer.rs 的 `sidecar_source` 是同一套规则
/// —— 规则本体在 `sidecar_names`, 这里只负责拼目录。
pub fn sidecar_candidates(photo: &Path) -> Vec<PathBuf> {
    let (Some(parent), Some(name)) = (photo.parent(), photo.file_name()) else {
        return Vec::new();
    };
    sidecar_names(&name.to_string_lossy())
        .into_iter()
        .map(|n| parent.join(n))
        .collect()
}
```

把现有 `write_target`（原 703–712 行）整体替换为：

```rust
/// 写: 跟随用户既有习惯 —— 已经有 `full.xmp` 就写它, 否则写 `stem.xmp`。
/// 绝不两个都写(否则别家软件只看其中一个, 会出现"两个边车各说各话")。
pub fn write_target(photo: &Path) -> Result<PathBuf, XmpError> {
    let cands = sidecar_candidates(photo);
    let Some(first) = cands.first() else {
        return Err(XmpError::PathTooLong);
    };
    if cands.len() > 1 {
        let idx = write_target_index(first.exists(), cands[1].is_file());
        return Ok(cands[idx as usize].clone());
    }
    Ok(first.clone())
}
```

- [ ] **Step 5: 跑测试，含既有的回归护栏**

```powershell
cd A:\tenent\src-tauri
cargo test -p imagefilter-core --lib 2>&1 | Select-String -Pattern "test result:"
cargo test --lib 2>&1 | Select-String -Pattern "test result:"
```

Expected:
- core 全绿，新增 2 个测试
- **`sidecar_candidates_and_write_target`（原有测试）必须仍然通过** —— 它就是这次重构的回归护栏。它挂了就说明 `write_target` 的规则被改变了，改回去。

- [ ] **Step 6: 桌面端手测边车读写（数据安全，不能省）**

```powershell
cd A:\tenent
npx tauri dev
```

手测（对应交接文档 §4.4/§5 的边车纪律）：
1. 设置 →「评分/颜色标签写入 .xmp」设为**写入**
2. 在一个有照片的目录里给一张打 4 星、标一个颜色
3. 确认旁边生成了边车文件；**只生成一个**（不是两个）
4. 关掉再打开那个文件夹 → 星级与色标仍在
5. 打开生成的 `.xmp` 看一眼：`xmp:Rating="4"` 与颜色标签在

- [ ] **Step 7: Commit**

```powershell
cd A:\tenent
git add crates/imagefilter-core/src/xmp.rs
git commit -m "refactor(core): 边车命名规则抽成纯函数 sidecar_names/write_target_index"
```

---

## 附录 A · 规格覆盖对照

| 规格条目 | 由哪个任务实现 |
|---|---|
| §3.3 共享代码组织 · 建 core crate、不建 workspace | Task 4 |
| §3.3 `xmp.rs` 退化为 shim、3 个命令包装 | Task 4 Step 8 |
| §3.3 内嵌预览改 `&[u8]` 入口 | Task 5 |
| §3.3 搬迁零逻辑改动 + 测试数一致 | Task 1（基线）+ 每个任务的验收线 |
| §3.5 `extract_largest_preview` 纯函数 | Task 5 |
| §3.5 扩展名清单唯一来源 | Task 4 Step 4/7 |
| §3.5 边车命名规则唯一来源 | Task 6 |
| §4.2 `MAX_JPEG_LOOKAHEAD` 16MB 语义 | Task 5 Step 3 |
| §8.4 M0 探路 | Task 2（内嵌预览）+ Task 3（WebDAV） |
| §8.4 M1 验收"测试全绿且断言数一致" | Task 4/5/6 各自的验收步骤 |
| §9 风险 4（分两个 commit 搬 xmp） | Task 4 一个 commit、Task 5/6 各自一个 commit |
| §12 附录 A #6/#7（RAW 内嵌预览） | Task 2 |
| §12 附录 A #1–#5、#8–#11（NAS 能力） | Task 3 |
| §8.4 M1 的"UniFFI 能被最小 Kotlin 工程调到" | **不在本计划**，见"范围说明"（M1b 放下个计划） |

## 附录 B · 本计划刻意不做的事

| 不做 | 原因 |
|---|---|
| UniFFI / `imagefilter-mobile` / `cargo-ndk` / Gradle | 需要安卓工具链；宏形态要用真编译器锁死（M1b） |
| 任何 Kotlin / Compose 代码 | M2 起 |
| 动 `App.tsx` / `useScanner.ts` / `index.css` | Token 纪律（规格 §10 第 3 条） |
| 改版本号、发版 | 本计划不产生用户可见变化 |
| 修交接文档 §6 的 P1/P2 遗留问题 | 与本案无关 |
