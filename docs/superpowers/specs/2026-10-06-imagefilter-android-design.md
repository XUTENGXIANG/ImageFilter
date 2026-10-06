# ImageFilter Mobile（Android）设计规格

> **状态**：待评审（brainstorming 阶段产物，尚未实施）
> **日期**：2026-10-06 · **桌面端基线** `v1.1.2` / HEAD `4d21481`
> **上游约束**：[ImageFilter-交接与上手.md](../ImageFilter-交接与上手.md) §4.1（缓存版本化）§4.4（导入契约）§5（必守坑位）§9.2（版本号 4 处）
> 本文件只描述**设计**，不含实施步骤；实施计划由 writing-plans 另出。

---

## 0. 30 秒速览

| 维度 | 事实 |
|---|---|
| 是什么 | 桌面端 ImageFilter 的**远程筛片客户端**：图库在 NAS，手机经 WebDAV 直读，星级/色标写回 `.xmp` 边车 |
| 为什么这样能同步 | 桌面端在 `xmpMode === "on"` 时，**每次打开文件夹**都会 `read_decisions` 并合并（`useScanner.ts:1020-1036`）→ **桌面端零改动** |
| 硬前提 | 桌面端 设置 →「评分/颜色标签写入 .xmp」必须 = **写入**；否则手机写的一切桌面端永远读不到 |
| 形态 | 原生 Kotlin + Jetpack Compose；Rust 纯逻辑经 UniFFI 复用（`crates/imagefilter-core`）；侧载 APK |
| 不做 | AI 分析、导入归档、删除文件、服务端/中继、多人选片、视频、手机端 RAW 全解码 |
| 远程通路 | 手机直连 NAS 的 WebDAV：`PROPFIND` / `GET(Range)` / `PUT` / `MOVE`。**项目不提供任何服务器** |
| 离线行为 | 半离线：已缓存的**可看**，**改必须在线**；在线但写失败 → 保留本地改动、标"未同步"、手动重试 |
| 核心手势 | 滑卡筛片（左滑/右滑）+ 星级打分；滑卡语义**可配置**，默认对齐桌面端 `J`/`X` |
| 性能目标 | 网格滚动与滑卡跟手度优先；**性能结论只认真机**，模拟器不作为依据 |
| 验证方式 | Rust `cargo test` + Kotlin JVM 单测（本机/CI）+ **本机可控 WebDAV 故障注入** + 真机手测 |
| Token 纪律 | §10：可单测面积最大化、桌面端改动最小化、禁止读大文档全文 |

---

## 1. 目标与非目标

### 1.1 目标

1. 人在外面时，用手机浏览 NAS 上的照片（JPEG + RAW 内嵌预览），完成初筛。
2. 初筛结果（星级 0–5、五色标签）落到 `.xmp` 边车；回工位打开桌面端**自动合并**，接着走原有的导入归档链。
3. 支持把入选片**移动到子文件夹**（如 `精修/`），在手机上完成一次粗分类，且边车随行。
4. 已看过的内容离线可看（缩略图 + 预览图本地缓存）。

### 1.2 非目标（已逐条确认，不要"顺手加上"）

| 不做 | 理由 |
|---|---|
| 手机端 AI 分析（模糊/过曝/重复） | 明确否决。代价：手机上无法自动挑废片。**且桌面端的分析结果只存内存、不写边车**（`analyzer.rs` 的 `AnalysisResult` 未落盘），所以手机也"读不到"电脑的分析结论 |
| 导入归档 / MD5 校验 | 留在桌面端；`importer.rs` 的"绝不静默覆盖 + 双端 MD5"契约不在手机上重造 |
| 删除远端文件 | WebDAV 删除不可逆，且会与桌面端 `import_history` 失去对账 |
| 服务端 / 中继 / 账号体系 | 用户已有 NAS 与 WebDAV，不引入运维成本 |
| 多人选片（客户选片） | 是另一个产品，权限与隐私模型完全不同 |
| 视频预览 | 缩略图链与播放器会显著拉大体积 |
| 手机端 RAW 全解码 / 1:1 原图 | 只看内嵌预览。1:1 看到的是内嵌预览的真实像素——这是**明示接受的代价** |
| 勾选（`selectedPaths` 的等价物） | 桌面端勾选是纯内存态（`useScanner.ts:89`），物理上无法跨端传递；手机端用**色标**表达取舍 |

### 1.3 硬前提（必须在首启引导页显式给出操作路径）

桌面端 `设置 →「评分/颜色标签写入 .xmp」` 必须是 **写入**。代码门在 `useScanner.ts:1020`：只有 `xmpModeRef.current === "on"` 才会调用 `read_decisions`。档位为「关闭」，或「询问」而未作答时，**手机端写入的边车永远不会被桌面端读到**。

这是全案最容易踩的坑：手机端一切正常、桌面端毫无反应。首启引导必须直接给出"去电脑上把那个开关打开"的步骤，不能只在文档里写。

---

## 2. 技术选型

### 2.1 结论

**原生 Kotlin + Jetpack Compose**（UI 与全部 IO）+ **Rust 纯逻辑经 UniFFI 复用**。

### 2.2 依据（可核对）

**"动画流程性能优先"是本案的首选约束，它直接排除 Tauri 2 Android。** WebView 的图像解码链路由 Chromium 代管，App 拿不到 `inSampleSize`、硬件位图、分块解码的控制权；而本 App 的掉帧主要来自**图片解码**，不是动画本身。

生态事实：Google Play 检出的技术栈中 Kotlin 约 79%，跨平台里 Flutter 26%、React Native 19%（口径为"被检出的技术栈占比"、可以重叠，[来源](https://cloud.tencent.com.cn/developer/article/2615321)）。Tauri 不在该榜内——它在安卓端不属于主流，定位是自用/内部工具。

复用价值集中在两处，且都是纯逻辑、跨平台无分支：

| 资产 | 位置 | 手机端用途 |
|---|---|---|
| 边车合并语义 | `xmp.rs`（51KB） | **唯一跨端通道**；含原子写、网络盘写降级、闭集错误码、幂等（`changed=false` 不写） |
| 内嵌预览提取 | `images.rs:317 extract_largest_preview_full`、`images.rs:377 jpeg_dimensions` | 手机端 RAW 预览的**唯一**来源（纯 Rust，无平台依赖） |
| EXIF | `scanner/exif.rs` + `kamadak-exif` | 展示拍摄参数（Android 的 `ExifInterface` 对 RAW 基本无效） |

**明确不可复用**：Windows Shell 缩略图 / WIC / `drives.rs` / Mica / `lightroom.rs` / `importer.rs` / `analyzer.rs`；桌面端 `App.tsx` 三栏浮窗布局（48KB，手机要重做）与 `viewer.tsx` 的滚轮鼠标手势（30KB，手机换双指）。

### 2.3 被否决的方案

| 方案 | 否决理由 |
|---|---|
| Tauri 2 Android（同仓库） | 与"性能优先"直接冲突。它的优点（Rust 零胶水、UI 可在本机浏览器验证）不足以抵消 |
| Capacitor 包 Web 层 | 同时吃下 WebView 性能上限与两套语言；Rust 核心仍需 NDK 才能拿到，收益不明显 |
| 纯 PWA 直连 WebDAV | **硬阻塞**：NAS 的 WebDAV 一般不返回 CORS 头，浏览器会拦掉 |
| Flutter + flutter_rust_bridge | 动画性能与原生同级、Rust FFI 最顺，但为性能引入第三门语言（Dart）不划算。**若后续要上 iOS，应重新评估此项** |
| 自带公网中继服务 | 用户已有 WebDAV，不值得为之买服务器、做运维与安全 |

---

## 3. 架构

### 3.1 分层

```
┌──────────────── Android App (Kotlin + Compose) ────────────────┐
│ ui/    目录树抽屉 · 网格(LazyVerticalGrid) · 滑卡栈 · 查看器     │
│        筛选栏 · 未同步面板 · 连接向导/设置                       │
│ data/  Room(远端索引 / 本地决策镜像 / 待同步集合)                │
│        磁盘缩略图缓存 · 磁盘预览图缓存 · 凭据(Keystore)          │
│ net/   WebDAV：OkHttp — PROPFIND / GET(Range) / PUT / MOVE      │
│        认证 · 重试 · 超时 · 错误码映射                          │
│ rust/  UniFFI 绑定 → libimagefilter_mobile.so                  │
└────────────────────────────┬───────────────────────────────────┘
                      UniFFI（7 个纯函数，见 §3.5）
┌────────────────────────────▼───────────────────────────────────┐
│ Rust: crates/imagefilter-core（无网络、无平台分支、可单测）        │
│  xmp        : read_decision_from_text / apply_decision /        │
│               minimal_template / sidecar 命名规则               │
│  raw_preview: extract_largest_preview(&[u8]) / jpeg_dimensions  │
│  exif       : 拍摄参数                                          │
└────────────────────────────▲───────────────────────────────────┘
                 同一 crate 被桌面端以 path 依赖复用
```

### 3.2 边界纪律（四条，实施时不许破）

1. **Rust 不碰网络**。全部 IO 归 Kotlin。Rust 只做"文本/字节进 → 文本/字节出"。
2. **Rust 不碰平台 API**。core crate 内**不允许出现 `cfg(target_os)`**。
3. **JPEG 缩略图解码归 Kotlin**（`ImageDecoder` + `inSampleSize`，能吃到硬件位图与系统解码优化）；Rust 只做**容器扫描**——因为只有它能从 RAW 里挖出内嵌 JPEG。
4. **认证、重试、超时、错误映射全在 Kotlin**。

理由：最易出错的合并语义保持纯函数形态 → UniFFI 面极小 → 桌面端与手机端**共用同一份语义**，不会出现"手机写的边车桌面端认不出"。

### 3.3 共享代码怎么组织（本设计仅有的重构，已压到最小）

- 新增 `crates/imagefilter-core`，把 `src-tauri/src/xmp.rs` 的**全部内容**（除 3 个 `#[tauri::command]` 包装，位于 `xmp.rs:908/923/953`）**原样搬入，逻辑零改动**。
- `src-tauri/src/xmp.rs` 退化为 **shim**：`pub use imagefilter_core::xmp::*;` + 那 3 个命令包装。这样 `lib.rs` 注册面、`importer.rs`、前端契约**全都不用动**。
- 内嵌预览提取搬入 core 时**只做一次形态改造**：扫描逻辑从"接收 `&Path` 并 mmap"改为"接收 `&[u8]`"，`src-tauri` 侧保留约 10 行的 mmap 包装调用它。这样手机能把 `Range` 取回的字节直接喂进去，不必先落 16MB 盘。
- `RAW_EXTENSIONS` / `SUPPORTED_EXTENSIONS`（现位于 `scanner/mod.rs:19`）一并搬入 core，`scanner/mod.rs` 改为 `pub use` 转发——这样 `browse.rs` / `images.rs` 里的 `use super::RAW_EXTENSIONS` **一行都不用改**，而手机端也不必再复制第三份扩展名清单（对应 §3.5 的 `raw_extensions()`）。
- **不在仓库根建 `[workspace]`**：那会把 target 目录搬到根，让桌面端既有增量产物全部作废（该仓库有过 `cargo clean` 清掉 11GiB 的前科，交接文档 §1），并连带影响 README 与 CI 里写死的 `src-tauri/target/...` 路径。core 作为 `path` 依赖被 `src-tauri` 引用即可；安卓侧用独立 `CARGO_TARGET_DIR`，两边互不干扰。
- core 依赖只有 `serde` 与 `kamadak-exif`。`memmap2`、`image`、`zune-jpeg`、`rawler`、`tokio` 都**不进 core**。
- **验收线**：搬迁后 `cargo test --lib` 全绿，且**测试与断言数与搬迁前一致**（搬迁不得顺手改逻辑）。
  ⚠️ 交接文档 §7 记"26 活跃"、§9.1 记"66 活跃 + 3 忽略"，**两处不一致**；动工前先跑一次 `cargo test --lib` 把真实基线钉死，并顺手订正文档。

### 3.4 Android 工程结构

```
android/                                  # 新增：Gradle 工程
  app/src/main/kotlin/com/imagefilter/mobile/{ui,data,net}/
  app/build.gradle.kts
crates/
  imagefilter-core/                       # 纯逻辑：桌面端 + 手机端共用
  imagefilter-mobile/                     # cdylib + UniFFI 绑定（仅手机端）
```

`imagefilter-mobile` 用 `cargo-ndk` 编译，产物 ABI：**`arm64-v8a` + `x86_64`**。后者不是给手机用的——**是为了让 x86_64 模拟器能装**（理由见 §9 风险 6）。

### 3.5 UniFFI 接口面（7 个函数）

```
raw_extensions() -> Vec<String>                          // 扩展名清单的唯一来源
sidecar_names(file_name: String) -> Vec<String>          // 边车候选名（stem 优先, full-name 兜底）
write_target_index(has_first: bool, has_second: bool) -> u8  // 该写哪一个候选（跟随用户既有习惯）
read_decision_from_text(text: String) -> Decision        // Decision { rating: Option<u8>, label: Option<String> }
apply_decision(existing: Option<String>, rating: Option<u8>,
               label: Option<String>, clear_label: bool)
    -> Result<Applied, XmpErrorCode>                     // Applied { text: String, changed: bool }
extract_largest_preview(data: &[u8]) -> Option<Preview>  // Preview { width, height, jpeg: Vec<u8> }
```

外加 `read_exif(path: String) -> ExifRecord`（本地缓存文件路径——文件已由 Kotlin 下载落盘，所以 Rust 侧读本地文件不算"碰网络"）。

其中 `raw_extensions()` 与 `sidecar_names()` 是**必须由 Rust 提供**的，因为：

- `raw_extensions()`：这个仓库已有"同一套规则在两处写死"的教训——`importer.rs:229/741` 与 `xmp.rs:681 sidecar_candidates` 两条命名规则是靠**注释**维持一致的（见 `xmp.rs:681` / `importer.rs:741` 的对照注释）。手机端不要再复制第三份。
- `sidecar_names()`：把 `sidecar_candidates` 的命名规则抽成纯函数供两端共用。实施时 `sidecar_candidates` / `write_target` 应改为调用它们（**搬迁之外只允许三处行为等价改造：① 内嵌预览改成 `&[u8]` 入口；② 本条命名规则抽成纯函数；③ `read_one` / `write_one` / `Outcome` / `detail_of` 可见性放宽为 `pub`（为了让三个 Tauri 命令包装留在 `src-tauri`）。三处都必须有测试覆盖，③ 由现有测试直接覆盖**）。

`write_target_index()` 是拆出来的一半规则：桌面端 `write_target` 有"已经有 `full.xmp` 就写它、否则写 `stem.xmp`"的习惯跟随逻辑，手机端必须用同一条，否则会写出第二个边车——而"两个边车各说各话"正是 `xmp.rs:701-702` 明确要避免的事。

---

## 4. 数据流

### 4.1 连接与浏览

```
连接向导(地址/账号/密码/根目录) → OPTIONS+PROPFIND depth=1 探测
  → 凭据进 Android Keystore（EncryptedSharedPreferences）
  → 逐层展开目录树（与桌面端 browse_directory 的「单层」语义一致）
  → 进文件夹: PROPFIND depth=1 列文件
      · 过滤用 raw_extensions()（不再本地复制一份）
      · 单次拿回: href / getcontentlength / getlastmodified / getetag / resourcetype
  → 落 Room 索引 → 触发缩略图流水线
```

**目录树不做全量递归**。桌面端在这条上有明确教训：`count_folders` 对固定/网络盘跳过递归（`is_bulk_volume`），否则点一下就是整盘遍历（实测 `C:\Users` 18.5s）。手机上同理——`PROPFIND depth=1` 逐层即可，**不做 depth=infinity**（很多服务端也不支持）。

### 4.2 缩略图与缓存（性能关键路径，90% 的手感来源）

按格式分两条，**这个分工是刻意的**：

| 格式 | 做法 |
|---|---|
| JPEG | `GET` 整张（或 Range 取足够解码的前缀）→ Kotlin `ImageDecoder` + `inSampleSize` 降采样到 300px。**Rust 不参与** |
| RAW | `Range GET` 只取**文件头 16MB**（沿用 `images.rs:316` 的 `MAX_JPEG_LOOKAHEAD` 语义）→ Rust `extract_largest_preview(&[u8])` 扫出内嵌 JPEG → **原文落盘当预览图**（查看器直接用，避免二次下载）→ 再由 Kotlin 降采样出 300px 缩略图 |

**缓存键 = ETag**（服务端不给时才回落 `size + mtime`）。选 ETag 不只是为了准确：**文件被 MOVE 之后 ETag 通常不变，所以缓存不失效**。而"移动分类"是本 App 的常规动作——若用纯路径做键，每移动一次就重下一遍全部缩略图。

**缓存目录带版本号**（沿用桌面端 `thumbnails_v2` / `preview_v4` / `full_v3` 的纪律，交接文档 §4.1 列为必守坑位）：`thumbs_v1` / `previews_v1`。**改缓存格式或校验逻辑必须升版本**，否则坏条目会被长期复用。

### 4.3 查看器与打分（写边车契约）

查看器显示已落盘的预览图（长边通常 1600–6000px）。缩放选型建议 **`SubsamplingScaleImageView`**（专为大图分块解码，正好匹配这个尺寸区间；自己用 `Matrix` + `ScaleGestureDetector` 也能做，但边界回弹与内存换页要自己调）。

**写边车契约（每一步都有出处）**：

```
1. 读: 按 sidecar_names() 顺序 GET 候选边车（stem 优先 → full-name 兜底）
      404 = 不存在 → existing = None
2. 算: apply_decision(existing, rating, label, clear_label)
      · Err(NotXmp / UnsupportedForm) → **不写**，提示"该边车无法安全合并"（沿用桌面端闭集错误码）
      · changed == false → **不写**（桌面端"不白摸一次卡"的幂等纪律，xmp.rs 测试里有断言）
3. 写: PUT 目标路径，带 If-Match: <读到的 ETag>（有则带）
      · 412 → "冲突"分支（见 §7）
      · 2xx → 成功
4. 失败 → 本地改动保留，标"未同步"，进 §5 的未同步面板
```

**关于原子性**：桌面端 `atomic_write`（`xmp.rs:757-800`）做的是"临时文件 + rename，网络盘不支持 rename 覆盖时降级为直写"。手机端**先做最简单的**：直接 `PUT` 目标路径（HTTP PUT 本身是覆盖写，服务端通常内部就是临时文件 + rename）。不做"PUT 临时文件 + MOVE"两步法——那会引入"临时文件残留"这一新失败态。**此取舍列入 §12 M0 待验证清单**。

### 4.4 滑卡筛片（核心交互）

**默认映射：对齐桌面端的 `J`/`X` 语义**（README 快捷键表：`J` = 保留（3 星）、`X` = 废弃（0 星）），所以共用同一个 `xmp:Rating` 字段，**桌面端零改动**，且桌面端的"≥N★ 筛选"能直接看到你在手机上的取舍。

| 手势 | 默认写入 | 规则 |
|---|---|---|
| 左滑（废弃） | `rating = 0` | 直接写 |
| 右滑（保留） | `rating = 3` | **仅当当前无星级（空或 0）时才写 3**；若已有 ≥1 星则**不改星级**，只在卡片上提示"已保留 · 保持 N★" |

第二行那条限制是**必须的**：无条件写 3 会把手机关联到的、桌面端已给的 4/5 星**降级**，属于破坏用户既有工作的静默数据损失。

命中"不降级"那一支时**不产生任何写入**，因此不进未同步逻辑；但**仍自动前进**，并在卡片上给一句明确反馈（"已保留 · 保持 5★"）——否则用户会以为这次滑动没生效。

**可配置（设置项「滑卡写入」）**：
- `只写星级`（默认，即上表）
- `只写色标`（左滑 = 红标、右滑 = 绿标，完全不碰星级）
- `星级 + 色标都写`

**配套规则**：
- 写成功后**自动前进**到下一张（沿用桌面端默认开启的"评分后自动前进"）；**失败则不前进**，留在原张并标未同步——避免"以为打过了"。
- **撤销**：右上角，多步（内存栈，上限 50 步）。撤销 = 反向写边车。栈只存内存（因为改动本身要求在线，无跨会话意义）。
- 星级按钮 1–5（点击即写、同样自动前进）与滑卡共存：滑卡用于快速三态取舍，星级用于细分。
- 色标（长按卡片唤出）是**独立动作**，不与滑卡绑定——避免滑卡顺手覆盖用户精心标的色标。

### 4.5 移动到子文件夹

```
多选 → 选目标文件夹（可新建）→ 逐张：
  1. MOVE 照片 → 目标
       · 带 Overwrite: F  → 409 = 目标已存在 → **报冲突让用户改名，绝不覆盖**
  2. MOVE 同名边车（按 sidecar_names() 探测，存在才动）
  3. 更新 Room 索引（路径变更；缓存键含 ETag，故缓存不失效）
```

**半成功态必须显式处理**：第 1 步成功、第 2 步失败会留下**孤儿边车**（评分与新路径脱钩）。它进"未同步/待处理"清单，提供重试；**绝不静默吞掉**。

桌面端这边不用改：旧路径的评分在 `localStorage` 里变成孤儿键（无害），新路径的评分会在下次打开文件夹时从边车读回来。

**顺序纪律**：先动照片再动边车。反过来（先动边车）出现半成功时，边车在新路径、照片在旧路径，用户在旧路径看到的会是"评分丢了"——比孤儿边车更难解释。

---

## 5. 交互与信息架构

底部三 Tab：**浏览** / **筛片** / **未同步**（有待处理项时显示角标，无则隐藏）。

### 5.1 浏览

- 左侧抽屉：目录树（对应桌面端左栏）
- 主区：`LazyVerticalGrid` 缩略图网格，列数可配 2–6（对应桌面端 `-cols`）
- 顶栏：当前文件夹名 / 张数 / 当前筛选条件摘要
- 筛选栏：**只有三个维度**——星级、色标、未同步。桌面端的"分析标记"维度**在手机端不存在**（因为不做 AI，这是非目标的直接推论）
- 卡片角标：星级、色标、未同步

### 5.2 筛片（滑卡）

- 卡片栈：当前张 + 后 2 张预取
- 底部操作条：1–5 星、色标面板、撤销（显示可撤销步数）
- **手势规则必须写死，否则实现时一定打架**：
  - **默认态（决策态）**：单指拖动 = 滑卡决策
  - **双指缩放 → 进入查看态**：单指拖动 = 平移，滑卡手势**禁用**
  - 双指缩回 → 回到决策态
  - 长按 = 唤出色标面板
  - 这是相册类应用的通行做法；不这么定，缩放与滑卡会互相抢手势

### 5.3 未同步

列出"本地已改、远端没写成"的项：缩略图 + 目标值（星级/色标）+ 失败原因 + 单张重试 / 全部重试 / 放弃本地改动。

### 5.4 首启引导

连接向导（地址 / 账号 / 密码 / 根目录 / **测试连接**）+ **桌面端 XMP 档位提示**（§1.3）。测试连接必须真的做一次 `PROPFIND` 并报告服务端能力（是否给 ETag、是否支持 Range），把 §12 的探测结果直接反馈给用户。

---

## 6. 离线与缓存

**三层缓存**

| 层 | 内容 | 上限 |
|---|---|---|
| Room | 远端索引（路径 / ETag / size / mtime / 宽高 / 是否 RAW）+ 本地决策镜像 + 待同步集合 | 无（元数据很小） |
| 磁盘缩略图 | 300px，LRU | 默认 1GB，可配 |
| 磁盘预览图 | RAW 内嵌 JPEG 原文，LRU（体积大户） | 默认 2GB，可配 |

**离线边界**：离线时网格显示已缓存缩略图、查看器显示已缓存预览图；**打分 / 色标 / 滑卡 / 移动全部禁用**，顶部常驻横幅说明原因。**这份状态必须显式显示**——不能让用户打完分才发现白打。

**预取**：网格按可见区预取（对应桌面端 `IntersectionObserver` 可见区预加载 + 查看器 ±1 邻居预取）。**筛片模式另有一条硬要求**：当前张 + 后 2 张的预览图必须提前就绪——滑卡的手感有一半取决于这个，而不是取决于动画框架。

---

## 7. 错误处理

延续这个仓库的**闭集错误码**文化（桌面端已有 `XMP_ERR_CODES`、`LRC_ERR_CODES`）。定义 `MobileErr`，**每条码必须落到"用户下一步做什么"**，不允许出现纯技术错误弹窗。

| 码 | 场景 | 用户可执行动作 |
|---|---|---|
| `AuthFailed` (401) | 凭据失效 | 重新输入账号/密码；检查 NAS 是否开了访问控制 |
| `Forbidden` (403) | 无权限 | 提示去 NAS 侧检查该共享文件夹权限 |
| `NotFound` (404) | 边车不存在 | **不是错误**：按新建模板处理（`apply_decision(None, …)`） |
| `Conflict` (409) | MOVE 目标已存在 | 改名 / 跳过；**绝不覆盖** |
| `PreconditionFailed` (412) | 边车被别的客户端改过 | 冲突分支：以我的为准 / 以远端为准 / 稍后处理 |
| `Locked` (423) | 文件被锁 | 重试 |
| `InsufficientStorage` (507) | NAS 空间不足 | 停止当前批量并提示 |
| `Timeout` / `Network` | 超时、断网 | 进未同步，手动重试 |
| `CertUntrusted` | 自签证书 | 显示证书指纹，允许用户**显式信任该指纹**（TOFU），设置里可见可撤销。**不提供"永远忽略证书错误"开关** |
| `NotXmp` / `UnsupportedForm` | 边车不是 XMP / 是元素形式 | **不写**，提示该边车无法安全合并（直接透传 Rust 的 `XmpError` 码，不新造） |
| `PathInvalid` | 路径含越界组件 | 拒绝操作并提示 |

**三条纪律**

1. **绝不静默覆盖**：写边车带 `If-Match`，`MOVE` 带 `Overwrite: F`。这不是多余设计——桌面端与手机端**可能同时改同一张**，LrC 也会改同一个边车。与桌面端 `importer.rs` 的"绝不静默覆盖 + MD5 双向校验"是同一条纪律的延续。
2. **半成功态显式化**：孤儿边车进待处理清单并可重试。
3. **未同步 ≠ 已同步**：任何"本地已改、远端没写成"的状态都必须在 UI 上可见（角标 + 清单），不允许静默乐观。

---

## 8. 测试与验证

现实约束：**本机没有安卓构建环境，Agent 也不便跑模拟器**。所以策略是**把可验证面积最大化，并明确承认哪一小块必须真机**。

| 层 | 手段 | 在哪跑 |
|---|---|---|
| Rust core | `cargo test`：沿用搬来的测试 + 新增坏文件用例（无 EOI / 假 SOF / 截断）、`extract_largest_preview(&[u8])`、边车幂等与冲突 | 本机 + CI，**全自动** |
| Kotlin 数据层 | JVM 单测 + `MockWebServer`：PROPFIND 解析、Range 分段、`If-Match`→412、MOVE→409、超时重试、证书失败；Room 索引与筛选；LRU 与缓存键 | 本机 + CI，**全自动** |
| 端到端 | **本机可控 WebDAV**（`rclone serve webdav` 或 Python `wsgidav`）+ 模拟器经 `10.0.2.2` 访问宿主 → 列目录→出缩略图→打分→写边车→回读校验 | 本机 |
| UI 与手势 | 真机手测（见下"必须真机"清单） | 只有真机 |
| 打包 | GitHub Actions 出 APK（签名走 secret） | CI |

### 8.1 本机可控 WebDAV 的价值（本设计新增的关键手段）

它不只让端到端在本机跑通（不必把 NAS 搬到手边），更关键的是**故障可注入**：故意返回 `412` / `409` / `423` / `507` / 超时 / **不给 ETag** / **不支持 Range** / 返回非 XMP 内容，可把 §7 的**每一条错误码变成可复现的测试用例**。这正好补上"错误处理设计得对但没法验"的空洞。

### 8.2 模拟器清单

| 类别 | 选项 | 备注 |
|---|---|---|
| **官方，首选** | Android Studio 自带 AVD / Android Emulator | Windows 上需 **WHPX(Hyper-V) 或 AEHD 驱动**；x86_64 镜像、`adb` 完整可用，是能自动化的前提 |
| **CI** | `avdmanager` + `emulator -no-window` + [`reactivecircus/android-emulator-runner`](https://github.com/ReactiveCircus/android-emulator-runner) | ubuntu runner 走 KVM（x86_64 镜像）/ macOS runner 为 arm64；两者都要求已出对应 ABI，适合主分支冒烟 |
| 第三方（国内常用，游戏向） | 雷电 / 夜神 / MuMu / BlueStacks / MEmu | 都支持 `adb connect`，**但 API 版本常偏旧、系统行为被改动过**。端口随版本与多开实例变化，以实际 `adb devices` 为准。适合"装上去点一遍"，**不适合作验收环境** |
| 虚拟机路线 | Genymotion Desktop（VirtualBox，个人免费版，快照/GPS 模拟较规范） | 第三方里最像开发工具的 |
| 已废弃 | WSA（Windows Subsystem for Android） | 微软已宣布 2025-03-05 起停止支持（[Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/5870185/the-windows-subsystem-for-android-app-does-not-ope)）；社区续命包不作为方案 |
| 云真机 | 腾讯 WeTest / 阿里云 EMAS / 百度 MTC / Testin；Firebase Test Lab / AWS Device Farm / BrowserStack | 唯一能拿到**真机**手感数据的路子，按分钟计费 |

### 8.3 模拟器能验什么、不能验什么（必须分清）

| | 模拟器 | 真机 |
|---|---|---|
| 功能正确性（列目录 / 出缩略图 / 写边车 / 移动分类 / 错误码分支 / 布局溢出） | ✅ 可信 | ✅ |
| 离线与半离线行为、未同步面板 | ✅ 可信 | ✅ |
| **滑卡跟手度、双指缩放手感、大图换页内存** | ❌ 帧率与触摸采样都不是真的 | ✅ 唯一可信 |
| **性能预算（120Hz 掉不掉帧）** | ❌ 宿主 GPU/CPU 与手机差太远 | ✅ |

**必须真机的清单（写死，不要假装能自动化）**：① 滑卡跟手度与回弹；② 双指缩放与查看态切换是否打架；③ 连续翻 100 张的内存曲线；④ 硬件位图与大图换页是否闪黑；⑤ 离线/在线切换时的横幅与入口禁用。验证工具：**scrcpy**（投屏 + 注入，可截图/录屏）+ `adb shell input swipe`（手势回放）+ `adb exec-out screencap`（截图）+ `screenrecord`（数掉帧）+ 开发者选项里的**指针位置**与 **GPU 渲染模式分析**。

### 8.4 里程碑（每步都有可复现产出）

| # | 内容 | 验收线 |
|---|---|---|
| **M0** | **探路 spike**：对真实 NAS 跑能力探测（见 §12） | 探测结果记入 `docs/evidence/`；**结论会直接改设计**，必须先做 |
| **M1a** | 抽核：`crates/imagefilter-core` + 桌面端 shim + `&[u8]` 入口 + 边车命名纯函数 | `cargo test --lib` 全绿且断言数与基线一致；桌面端手测"评分→写边车→重开文件夹读回"与"RAW 预览/分析正常"。**不碰 UI、不需要安卓工具链** |
| **M1b** | `crates/imagefilter-mobile`（UniFFI cdylib）+ Android 工程骨架 + `cargo-ndk` | `cargo ndk` 出 so、Gradle 出 APK、真机/模拟器能调到 7 个函数 |
| **M2** | 数据层：WebDAV 客户端 + Room + 缓存 | MockWebServer 单测全绿；真机"连上 NAS 列出目录并出缩略图" |
| **M3** | 筛片闭环：网格 + 查看器 + 星级/色标 + 写边车 | **桌面端真的能读到**（端到端价值点） |
| **M4** | 滑卡 + 移动分类：滑卡栈 + 撤销 + 未同步面板 + MOVE 边车随行 + 孤儿清单 | 真机手测 §8.3 清单 |

排期顺序刻意如此：**M1/M2 全部落在可单测范围（便宜、可自动回归），M3 才碰 UI（贵、需真机）**。这与 §10 的 Token 纪律是同一件事。

---

## 9. 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| 1 | **RAW 内嵌预览覆盖率是最大未知**。交接文档 §6 明写：CR3(ISOBMFF)/RAF 能否取到 EXIF **未验证、无真实样本**。桌面端还有 WIC/rawler 全解码兜底，**手机端没有兜底**——它几乎完全押在内嵌预览上，风险显著高于桌面端 | M0 用你机型的真实样张验证；扫不到内嵌 JPEG 时显示占位并提供"下载原图"，或明确标记该格式不支持，**不要显示空白让人以为坏了** |
| 2 | **本机不能验证 UI** → 最大风险是"写完不知道对不对" | M1/M2 全部留在可单测范围；§8.3 真机清单写死 |
| 3 | **WebDAV 服务端差异**（群晖/威联通/Nextcloud 能力不一致） | M0 探测；结果入库为证据 |
| 4 | **要碰 51KB 的 `xmp.rs`** | **一步一个 commit**：先纯搬移（逻辑零改动、测试全绿），再做 `&[u8]` 入口 / 边车命名纯函数 / 可见性放宽这三处等价改造（见 M1a）；外加桌面端手测"评分→写边车→重开文件夹读回" |
| 5 | **版本号纪律从 4 处变 5 处**（交接文档 §9.2 规定 4 处，安卓还要加 `build.gradle.kts` 的 versionName/versionCode） | 首次发版**手工同步 5 处**；"改成单一来源生成"作为独立小步另议（见 §11），不与本案混在一起 |
| 6 | **ABI 与模拟器冲突**：x86_64 宿主**跑不了 arm64 系统镜像**（QEMU 直接 `PANIC: Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host`，[android-emulator-runner #386](https://github.com/ReactiveCircus/android-emulator-runner/issues/386)）；反过来 x86_64 镜像跑只有 arm64-v8a 的 so 要靠 ARM 转译，不可靠 | **ABI 出 `arm64-v8a` + `x86_64`**：debug 两个都要（模拟器能装），release 侧载包可只留 arm64 |
| 7 | **模拟器不可作为性能验收依据** | 性能结论只来自真机 + `screenrecord` 掉帧统计 + GPU 渲染条形图 |
| 8 | **单文件夹 1000+ 张的 PROPFIND 响应**可能很慢或返回超大 XML | M0 实测；必要时分页/流式解析（`XmlPullParser`），不要一次性 build 完整 DOM |

---

## 10. Token 纪律（工程约定，为后续会话省成本）

AI 协作下，**token 成本 ≈ 需要人读的代码量 + 需要设备验证的循环次数**。以下约定把它压到最低：

1. **单文件 ≲ 300 行**。超了就拆。这也是本仓库既有风格：文件变大说明职责变多。
2. **可单测面积最大化**。任何逻辑优先放 core（`cargo test`）或 Kotlin JVM 单测；只有"必须真机"的部分才进 UI 层。**跑测试的输出是短的，跑模拟器截图 + logcat 的输出是贵的**——这条是省钱的核心。
3. **桌面端改动不超过 4 个文件**：`src-tauri/src/xmp.rs`（shim）、`src-tauri/src/scanner/mod.rs`（扩展名清单改 `pub use` 转发）、`src-tauri/src/scanner/images.rs`（改调用）、`src-tauri/Cargo.toml`（加 path 依赖）。**禁止顺手重构 `App.tsx`（48KB）/ `useScanner.ts`（65KB）/ `index.css`（19KB）**。
4. **禁止在实施会话里读大文档全文**：`ImageFilter-功能实施方案.md`（167KB）与 `ImageFilter-架构与机制走查.md`（50KB）只按需 grep 小节；本规格 + 交接文档 §0/§4/§5 已足够。
5. **i18n 用单文件**：手机端字符串放一个 `strings.kt`（中英并列在同一处），**不复制**桌面端 zh/en 双文件各 273 key 的对齐机制。
6. **主题用单文件**：把手机端要用的少量色值固化成一份 `Theme.kt`，**不移植** `index.css`。
7. **一个里程碑一个会话**（实施计划需按此拆成可独立执行的单元），避免单会话累积历史。
8. **证据输出要短**：验证类命令只回传断言结果与关键数字，不要 dump 整页 UI 树 / 完整 XML。

---

## 11. 交付与发布

- 新增 CI job：JDK 17 + Android SDK + NDK + `cargo-ndk` → 构建 APK → 作为 Release asset 上传（与现有 Windows/macOS 构建流程并列）。
- **签名必须固定**：即使是自用侧载，也必须用**同一个 release keystore**（存 CI secret）。用 debug 签名或不固定签名，新版本会因签名不一致**无法覆盖安装**，只能卸载重装（会丢本地缓存与未同步项）。
- 版本号：首次发版手工同步 **5 处**（交接文档 §9.2 的 4 处 + `build.gradle.kts` 的 versionName/versionCode）。"单一来源生成"作为独立小步另议，不与本案混做。

---

## 12. 附录 A · M0 待验证假设（探路清单）

**必须先做 M0，因为其中任何一条不成立都会直接改设计。**

| # | 待验证 | 不成立时的影响 |
|---|---|---|
| 1 | NAS 的 WebDAV 是否返回 `getetag`；粒度如何 | 缓存键退回 `size + mtime`；MOVE 后缓存失效，需要额外策略 |
| 2 | 是否支持 `Range` 请求 | RAW 只能整张下载（50MB/张），缩略图体验不可接受 → 需改设计或放弃 RAW |
| 3 | `MOVE` 的 `Overwrite: F` 是否真的拒绝覆盖（**有的服务端忽略该头**） | 必须改为"先 PROPFIND 查目标是否存在"的两步法 |
| 4 | 是否支持 `If-Match`（能否拿到 412） | 冲突检测降级为"写前重读比对 ETag"，仍有竞态窗口，须在 UI 上说明 |
| 5 | `PUT` 是否内部临时文件 + rename（并发安全） | 需要考虑 §4.3 的两步法与其"临时文件残留"新失败态 |
| 6 | 真实 ARW（你的机型）能否扫出内嵌 JPEG；尺寸多少 | 若不能 → RAW 在手机上不可用，需回落"下载原图"或明确不支持 |
| 7 | CR3(ISOBMFF) / RAF 能否取到内嵌预览与 EXIF（交接文档 §6 列为未验证） | 同上；且决定 §7 的错误文案 |
| 8 | 单文件夹 1000+ 张时 `PROPFIND depth=1` 的响应时间与体积 | 需要分页 / 流式解析 / 目录内分批加载 |
| 9 | 自签证书情况与证书链 | 决定 §7 `CertUntrusted` 的 TOFU 流程是否必须 |
| 10 | 目录列表里中文/特殊字符文件名的编码（NFC / NFD） | 不处理会造成"路径对不上"的隐形 bug：能列出来但读不到、写不回 |
| 11 | 单文件真实体积分布 | 决定 Range 前缀大小（现定 16MB）、缓存上限、预取激进度 |

---

## 13. 附录 B · 决策日志

| # | 决策 | 被否决的选项 | 理由 |
|---|---|---|---|
| 1 | 场景 = 摄影师**自己**远程筛片 | 客户在线选片 / 相机直连 / 云盘 | 与桌面端配套；客户选片是另一个产品 |
| 2 | 直读现有 WebDAV，**不跑任何服务** | 桌面端当图库主机 / 自建公网中继 | 零运维；且边车通道让桌面端零改动 |
| 3 | 格式 = JPEG + RAW **内嵌预览** | RAW 全解码 / 仅 JPEG / 含视频 | 内嵌预览是纯 Rust 跨平台；全解码在手机上代价与耗电都不划算 |
| 4 | **半离线**：离线可看不可改 | 离线优先（待写队列） / 必须在线 | 用户选择。避免待写队列的冲突合并复杂度 |
| 5 | 允许**移动到子文件夹**，不允许删除 | 只写边车 / 允许删除 / 都要 | 用户选择。移动可回退，删除不可逆 |
| 6 | **不做**手机端 AI 分析 | 复用桌面 analyzer / 只做重复检测 | 用户选择。副作用：手机的取舍不受"分析标记"筛选帮助 |
| 7 | 侧载 APK，走 GitHub Release | Google Play / 国内商店 | 自用工具，不值得背负审核与隐私政策 |
| 8 | 技术栈 = **原生 Kotlin + Compose + Rust(UniFFI)** | Tauri 2 Android / Capacitor / 纯 PWA / Flutter | "动画性能优先"是首选约束，它排除 WebView 方案；Flutter 性能达标但多引入一门语言 |
| 9 | 缩略图解码归 Kotlin，Rust 只做容器扫描 | 全部解码进 Rust | 原生能吃到硬件位图与 `inSampleSize`；Rust 全解码只会更慢 |
| 10 | 滑卡语义**可配置**，默认左 0 星 / 右 3 星且**不降级** | 一律设 3 星 / 只写色标 | 用户选择。默认避免把桌面端 5 星静默降成 3 星 |
| 11 | 写失败 → 保留本地改动、标未同步、手动重试 | 回滚 / 弹窗追问 | 用户选择。避免网络抖动时"白打一次分" |
| 12 | ABI 出 arm64-v8a + x86_64 | 只出 arm64 | 查证后发现 x86_64 宿主跑不了 arm64 镜像，只出 arm64 会断掉模拟器验证路径 |
| 13 | 不建仓库根 `[workspace]` | 建 workspace 统一管理 | 会搬走 target 目录、作废 11GiB 增量产物，并牵连 README/CI 写死路径 |
| 14 | 桌面端改动限制在 4 个文件 | 顺手重构 `App.tsx` / `useScanner.ts` | Token 纪律：大文件读写极贵，且与本案无关 |
