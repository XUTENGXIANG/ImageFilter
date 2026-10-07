// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
//! 移动照片到另一个文件夹（手机端的"分类归档"）。
//!
//! 三条纪律（规格 §5.2 的"移动"一节），每一条都有测试盯着:
//!   1. **绝不静默覆盖** —— 目标已存在就报冲突, 让用户改名或跳过
//!      （与 `importer.rs` 的既有纪律一致）;
//!   2. **先移照片, 再移同名边车** —— 反过来出半成功时, 用户在旧路径看到的是
//!      "评分丢了", 比孤儿边车更难解释;
//!   3. **半成功必须显式返回**, 不许静默吞掉 —— 否则评分与新路径脱钩而无人知道。
//!
//! 本模块只做文件操作, **不做白名单校验**（那是 `server::paths::ensure_within` 的职责）:
//! 进来的 `src` 与 `dest_dir` 都已由调用方校验过, 是共享根内**已存在**的真实路径。
//! 于是本模块唯一"造出新路径"的地方就是 [`plan_dest`] —— 它是目标侧唯一需要盯着的地方,
//! 理由写在它的注释里。
//!
//! 一处**已知限制**（刻意的, 不是遗漏）: 用的是 `std::fs::rename`, 所以**跨卷移动会失败**
//! （Windows 上 `ERROR_NOT_SAME_DEVICE`）, 逐项报 `Failed`。不做"复制 + 删源"的退化路径,
//! 因为那等于在本服务里长出一个删除操作 —— 这个项目刻意**没有删除**（规格 §8.2 / M3′ 附录 B）。

use serde::Serialize;
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

/// 单张照片的移动结果。**闭集** —— 手机端按它本地化文案（M3′ 的 `MoveOutcomeDto.status`）。
///
/// 闭集里**只有服务端真的会产出的四档**。计划稿的接口清单里还有一档 `Renamed`（"目标同名但
/// 内容不同、自动改名成 `_1`"）, 本设计**刻意不做自动改名**（名字由用户决定 —— 手机端的结果
/// 面板给的是"改名后再试", 不是服务端替他改）, 所以那一档在这里**删掉了**, 两个理由:
///   · 它会让 `cargo check` 报出本任务不允许的新警告（`variant is never constructed`）;
///   · 更实质的是: 手机端是按 `status` 分组的闭集映射, 放一档永远收不到的状态进去, 只会让
///     "服务端能给你哪几种结果"这件事变成一句谎话。
/// 将来谁真的实现了自动改名, 加回来时请**同时**改手机端的映射表。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum MoveStatus {
    /// 照片与它的边车（如果有）都到了新位置。
    Moved,
    /// 目标位置已有同名文件/目录 —— **一个字节都没覆盖**。
    Conflict,
    /// 照片动了、边车没动: 评分与新路径脱钩了, 必须在界面上可见并可重试。
    HalfSuccess,
    /// 照片就没动成（跨卷、被占用、没有文件名…）。
    Failed,
}

/// 单张照片的逐项结果（与 M3′ 手机端 `MoveOutcomeDto` 逐字同形: `src`/`dest`/`status`/
/// `detail`/`pendingSidecars` **五个**字段）。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MoveOutcome {
    /// 规范化后的源路径。手机靠它把结果对回网格里的那张照片, 所以**是整条路径**。
    pub src: String,
    /// 照片最终落到的路径; 没落成（`Failed`）时为 `None` —— 不许编一个"本来想去的地方"。
    pub dest: Option<String>,
    pub status: MoveStatus,
    /// 给用户看的短原因（不是技术堆栈）。**只在真需要解释时才给**: 成功是 `None`。
    /// **不是机器可读字段** —— 谁来解析这句话, 谁就把中英文案钉进了协议（规格 §5.3 的
    /// i18n 纪律）。机器数据在 [`Self::pending_sidecars`]。
    pub detail: Option<String>,
    /// **还没搬走的边车**（空 = 都跟上了）。机器可读, 也是这五个字段里唯一"专为重试而生"的。
    ///
    /// 为什么必须是**列表**、还必须由服务端给出: 半成功项的 `src` **已经不在原位置了**
    /// （纪律②先移照片的直接后果, 见 `move_reports_a_half_success_when_the_sidecar_cannot_
    /// follow` 实测钉住的那条事实）, 所以"把这一项原样重发"必然 404, 能补救的只有**把留在
    /// 原地的边车本身当 `src` 再发一次**。而边车的名字由公开规则从照片名推出
    /// （`{stem}.xmp` / `{name}.xmp`, `xmp.rs::sidecar_candidates`）—— 让手机去推这条规则
    /// 正是规格 §3.2 纪律①**明令禁止**的事（手机不许猜路径/命名规则）。于是这条规则由
    /// **桌面端**在响应里直接给出结果:
    ///
    /// ```text
    /// POST /api/v1/move  { "paths": item.pendingSidecars, "destDir": parent(item.dest) }
    /// ```
    ///
    /// 不需要新端点, 也不需要任何推导（`dest` 是照片的规范最终路径, 它的父目录就是目标目录）。
    ///
    /// **永远是数组**: 没有待搬边车时是 `[]`, 不是 `null`（响应, 不需要缺字段容忍）。
    /// 每一项都是**源侧**的真实路径（它正是"没跟走"的那一份, 所以白名单/存在性两关都过）。
    /// 逐项**可能多于一个**: 两种命名习惯同时存在时两份都要列出来 —— 见 [`move_one`] 的循环。
    pub pending_sidecars: Vec<String>,
}

/// 逐项计数 + 逐项结果（与 M3′ 手机端 `MoveSummaryDto` 逐字同形）。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MoveSummary {
    /// 真的搬过去的张数。
    pub moved: u32,
    /// 照片搬走了、边车没跟上的张数（⊆ `moved` 之外的独立一档, 可重试）。
    pub half_success: u32,
    /// 目标同名、一个字节都没覆盖的张数。
    pub conflict: u32,
    pub failed: u32,
    /// 与请求顺序一一对应（手机按 src 合并, 不依赖顺序, 但服务端不该乱序）。
    pub items: Vec<MoveOutcome>,
}

/// [`plan_dest`] 唯一会拒绝的输入。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoveError {
    /// 没有文件名组件（例如 `D:\`、`.` 或以 `..` 结尾的路径）。
    NoFileName,
}

/// 目标路径的**唯一**构造点。
///
/// 为什么值得单独成函数、并把"目标侧怎么校验"这件事钉在这里:
/// 移动的**目标**是一个**尚不存在**的路径, 而本仓库的安全闸门
/// `server::paths::ensure_within` 只能校验**已存在**的路径（它必须 `canonicalize`
/// 才能证明包含关系 —— 规格 §5.2 的实测约束）。所以服务端校验的是**目标目录**
/// （手机只能从 `browse` 的目录树里挑一个已有文件夹）, 而目标文件路径只能是
/// "已校验的目录 + 一个文件名组件", 就是本函数:
///   · `file_name` 只可能来自 `Path::file_name()` 或 `xmp::sidecar_candidates` 造出的
///     `{stem}.xmp` / `{name}.xmp` —— 都是**单组件**, 不含分隔符;
///   · `Path::file_name()` 对 `..`、`.`、根路径返回 `None`（调用方转成 `Failed`）,
///     所以这里拼不出 `..\..`;
///   · **永远不许**把调用方传来的字符串当文件名交给这里: `Path::join` 遇到绝对路径或
///     带前缀的输入会**替换**整条路径, 那样 `D:\Windows` 就能逃出已校验的目录。
pub fn plan_dest(dest_dir: &Path, file_name: &OsStr) -> Result<PathBuf, MoveError> {
    if file_name.is_empty() {
        return Err(MoveError::NoFileName);
    }
    Ok(dest_dir.join(file_name))
}

/// 目标是否已被占用。
///
/// 用 `symlink_metadata` 而不是 `Path::exists()`: 后者**跟随**符号链接, 一个悬空链接会被
/// 当成"不存在", 于是 `rename` 会把它悄悄顶掉。纪律①是"绝不静默覆盖" —— 被覆盖的那个
/// 条目是不是常规文件并不重要。
fn path_taken(p: &Path) -> bool {
    p.symlink_metadata().is_ok()
}

/// 给用户看的名字: 优先只给文件名（整条路径太长）, 没有文件名时退回整条路径。
fn display_name(p: &Path) -> String {
    p.file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| p.to_string_lossy().to_string())
}

/// 把一份边车挪到 `dest_dir`, 返回它是否真的跟过去了。
///
/// 三种"没能跟过去"（没有文件名 / 拼不出目标 / 目标已被占用或 rename 失败）在这里被压成
/// 一个 `false`, 由调用方转成 `HalfSuccess` —— 它们对用户是同一件事（**评分没跟过来**）,
/// 所以逐项状态只有一档; 具体是哪一份边车由 `detail` 里的名字说清。
fn move_sidecar(cand: &Path, dest_dir: &Path) -> bool {
    let Some(name) = cand.file_name() else {
        return false;
    };
    let Ok(sc_dest) = plan_dest(dest_dir, name) else {
        return false;
    };
    // 与照片同一个纪律: 目标已有同名条目（哪怕是个目录）就**绝不覆盖**。
    !path_taken(&sc_dest) && std::fs::rename(cand, &sc_dest).is_ok()
}

/// 移动一张照片（含同名边车）。**同步函数** —— 调用方负责 `spawn_blocking`。
///
/// 顺序是刻意的（纪律②）: **先照片, 后边车**。反过来出半成功时, 用户在旧路径看到的是
/// "照片还在但评分没了"; 按这个顺序则是"照片走了, 评分留在原地" —— 后者能用
/// `HalfSuccess` + 重试讲清楚, 前者会让用户以为评分被抹掉了。
///
/// **边车循环是"全部尝试"而不是"第一个失败就返回"**: 两种命名习惯
/// （`{stem}.xmp` 与 `{name}.xmp`）可能**同时**存在, 而第一个失败之后照片**已经走了** ——
/// 早退就等于把第二个候选永远留在旧位置, 且没有任何未来调用能把它搬过去（"照片 + 边车"
/// 那一项已经不存在了）。所以每一份**存在**的候选都被尝试一遍, 没搬成的全部记进
/// [`MoveOutcome::pending_sidecars`], 让手机一次就能把该补的都补上。
pub fn move_one(src: &Path, dest_dir: &Path) -> MoveOutcome {
    let src_text = src.to_string_lossy().to_string();

    // 没有文件名 → 什么都拼不出来。这不是"半成功"（照片一步都没动）, 是彻底失败。
    let Some(name) = src.file_name() else {
        return MoveOutcome {
            src: src_text,
            dest: None,
            status: MoveStatus::Failed,
            detail: Some("路径没有文件名".into()),
            pending_sidecars: Vec::new(),
        };
    };
    let Ok(dest) = plan_dest(dest_dir, name) else {
        return MoveOutcome {
            src: src_text,
            dest: None,
            status: MoveStatus::Failed,
            detail: Some("路径没有文件名".into()),
            pending_sidecars: Vec::new(),
        };
    };
    let dest_text = dest.to_string_lossy().to_string();

    // ① 绝不静默覆盖: 目标已存在就原地不动, 让用户改名或跳过。
    if path_taken(&dest) {
        return MoveOutcome {
            src: src_text,
            dest: Some(dest_text),
            status: MoveStatus::Conflict,
            detail: Some("目标位置已有同名文件, 未覆盖".into()),
            // 照片一步都没动, 边车也就"该留在旧位置"——没有任何待补的移动。
            pending_sidecars: Vec::new(),
        };
    }

    // ② 先移照片。失败时 `dest` 如实给 `None`: 什么都没落到那个位置。
    if std::fs::rename(src, &dest).is_err() {
        return MoveOutcome {
            src: src_text,
            dest: None,
            status: MoveStatus::Failed,
            detail: Some("移动失败（可能跨卷或被占用）".into()),
            pending_sidecars: Vec::new(),
        };
    }

    // ③ 再把边车带过去。名字规则**复用 `xmp.rs` 的 `sidecar_candidates`**, 不在这里另写一套
    //    （本仓库吃过"同一套规则在两处写死"的亏: `xmp.rs:681` 与 `importer.rs:741`）。
    //    注意这里用的是**源**路径: `sidecar_candidates` 只做路径字符串推导, 照片已经搬走了
    //    也不影响它算出旧位置的那两份候选（`{stem}.xmp` / `{name}.xmp`）。
    //    **两份都试, 不许在第一次失败处早退**（理由见本函数的文档注释）。
    let mut pending: Vec<String> = Vec::new();
    for cand in crate::xmp::sidecar_candidates(src) {
        if !cand.is_file() {
            continue; // 没有这份候选 —— 正常情况（两种命名习惯通常只存在一种）
        }
        let cand_text = cand.to_string_lossy().to_string();
        // 没有扩展名的照片会让两个候选**重合**（`photo` → `photo.xmp` 两次）。去重, 否则
        // 重试时同一个路径会发两遍, 第二遍必然报错（源已经被第一遍搬走了）。
        if pending.contains(&cand_text) {
            continue;
        }
        if !move_sidecar(&cand, dest_dir) {
            pending.push(cand_text);
        }
    }

    if !pending.is_empty() {
        // `detail` 只给用户看（人名/文件名），机器数据在 `pending_sidecars`。
        let names: Vec<String> = pending
            .iter()
            .map(|p| display_name(Path::new(p)))
            .collect();
        return MoveOutcome {
            src: src_text,
            dest: Some(dest_text),
            status: MoveStatus::HalfSuccess,
            detail: Some(format!(
                "照片已移动, 但边车 {} 没能跟着走, 需要重试",
                names.join("、")
            )),
            pending_sidecars: pending,
        };
    }

    MoveOutcome {
        src: src_text,
        dest: Some(dest_text),
        status: MoveStatus::Moved,
        detail: None,
        pending_sidecars: Vec::new(),
    }
}

/// 批量移动。**每个进入的路径都已由调用方做过共享根校验**（并且保证它存在且是文件 ——
/// 见 `server::routes::move_photos`）。本函数不做任何路径合法性判断, 也就不可能"边校验边搬":
/// 校验全部发生在这里的第一次 `rename` 之前。
pub fn move_many(paths: &[PathBuf], dest_dir: &Path) -> MoveSummary {
    let mut summary = MoveSummary {
        moved: 0,
        half_success: 0,
        conflict: 0,
        failed: 0,
        items: Vec::with_capacity(paths.len()),
    };
    for p in paths {
        let out = move_one(p, dest_dir);
        match out.status {
            MoveStatus::Moved => summary.moved += 1,
            MoveStatus::HalfSuccess => summary.half_success += 1,
            MoveStatus::Conflict => summary.conflict += 1,
            MoveStatus::Failed => summary.failed += 1,
        }
        summary.items.push(out);
    }
    summary
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 一个**真建出来**的临时根。先删再建: 上一次跑挂留下的同名目录会让"目标已存在"
    /// 这类断言以完全错误的原因成立（本仓库吃过一次"夹具目录不存在导致测试假绿"的亏）。
    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("ifmove_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        assert!(d.is_dir(), "夹具前提: 临时根必须真的建出来");
        d
    }

    fn write_file(p: &Path, bytes: &[u8]) {
        std::fs::write(p, bytes).unwrap();
        assert!(p.is_file(), "夹具前提: {} 必须真的存在", p.display());
    }

    #[test]
    fn plan_dest_joins_exactly_one_component_and_rejects_an_empty_name() {
        let d = Path::new("dest");
        assert_eq!(plan_dest(d, OsStr::new("a.jpg")).unwrap(), d.join("a.jpg"));
        assert_eq!(plan_dest(d, OsStr::new("精修.xmp")).unwrap(), d.join("精修.xmp"));
        assert_eq!(
            plan_dest(d, OsStr::new("")).unwrap_err(),
            MoveError::NoFileName,
            "空文件名必须被拒, 而不是拼出一个指向目录本身的路径"
        );
    }

    #[test]
    fn moves_photo_and_its_sidecar_together() {
        let root = tmp("with_sidecar");
        let src = root.join("IMG_1.ARW");
        write_file(&src, b"raw");
        write_file(&root.join("IMG_1.xmp"), b"<x:xmpmeta/>");
        let dest = root.join("精修");
        std::fs::create_dir_all(&dest).unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::Moved);
        let moved_to = dest.join("IMG_1.ARW").to_string_lossy().to_string();
        assert_eq!(out.dest.as_deref(), Some(moved_to.as_str()));
        assert!(out.detail.is_none(), "成功不需要解释: {:?}", out.detail);
        assert!(
            out.pending_sidecars.is_empty(),
            "全都跟上了就没有待补项(线上是 `[]`): {:?}",
            out
        );
        assert!(dest.join("IMG_1.ARW").is_file(), "照片必须被移动");
        assert!(dest.join("IMG_1.xmp").is_file(), "**边车必须跟着走**");
        assert_eq!(std::fs::read(dest.join("IMG_1.xmp")).unwrap(), b"<x:xmpmeta/>");
        assert!(!src.exists(), "源不应残留");
        assert!(!root.join("IMG_1.xmp").exists(), "源边车不应残留");

        std::fs::remove_dir_all(&root).ok();
    }

    /// 第二种边车命名习惯（Lightroom 对非 RAW 写 `{文件名}.xmp`）也必须跟着走 ——
    /// `sidecar_candidates` 给两个候选, 这里证明第二个候选同样被处理。
    #[test]
    fn moves_the_full_name_sidecar_too() {
        let root = tmp("fullname_sidecar");
        let src = root.join("b.jpg");
        write_file(&src, b"jpeg");
        write_file(&root.join("b.jpg.xmp"), b"<x:xmpmeta/>");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();

        assert_eq!(move_one(&src, &dest).status, MoveStatus::Moved);
        assert!(dest.join("b.jpg.xmp").is_file(), "`{{name}}.xmp` 形式的边车必须跟着走");
        assert!(!root.join("b.jpg.xmp").exists(), "源边车不应残留");

        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn refuses_to_overwrite_existing_target() {
        let root = tmp("no_overwrite");
        let src = root.join("a.jpg");
        write_file(&src, b"new");
        write_file(&root.join("a.xmp"), b"<x:xmpmeta/>");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();
        write_file(&dest.join("a.jpg"), b"existing");

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::Conflict, "目标已存在必须报冲突");
        assert_eq!(
            std::fs::read(dest.join("a.jpg")).unwrap(),
            b"existing",
            "绝不覆盖"
        );
        assert!(src.is_file(), "冲突时源必须原地不动");
        assert!(
            root.join("a.xmp").is_file(),
            "冲突时边车也不许被搬走(照片还在旧位置, 评分必须跟着旧位置)"
        );
        assert!(
            !dest.join("a.xmp").exists(),
            "冲突是**整体**失败, 不许只把边车搬过去"
        );
        assert_eq!(
            out.detail.as_deref(),
            Some("目标位置已有同名文件, 未覆盖"),
            "冲突要给用户一句能读懂的原因(M3′ 的结果面板直接显示它)"
        );

        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn sidecar_move_failure_is_reported_as_half_success() {
        let root = tmp("half");
        let src = root.join("b.jpg");
        write_file(&src, b"new");
        write_file(&root.join("b.xmp"), b"<x:xmpmeta/>");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();

        // 目标位置放一个**同名目录**当边车 → 移动边车必然失败
        std::fs::create_dir_all(dest.join("b.xmp")).unwrap();
        assert!(dest.join("b.xmp").is_dir(), "夹具前提: 挡路的必须真的是个目录");

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::HalfSuccess, "照片动了边车没动 → 必须报半成功");
        assert!(dest.join("b.jpg").is_file(), "照片应已移动");
        assert!(
            root.join("b.xmp").is_file(),
            "边车应留在原地(不能让评分与新路径脱钩还静默)"
        );
        assert_eq!(
            std::fs::read(root.join("b.xmp")).unwrap(),
            b"<x:xmpmeta/>",
            "留在原地的边车必须逐字节原样"
        );
        let detail = out.detail.as_deref().unwrap_or("");
        assert!(detail.contains("边车"), "半成功的原因必须点名边车: {}", detail);
        assert_eq!(
            out.pending_sidecars,
            vec![root.join("b.xmp").to_string_lossy().to_string()],
            "机器可读的待补边车必须就是留在原地的那一份: {:?}",
            out
        );

        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn photo_without_sidecar_is_a_plain_move() {
        let root = tmp("nosidecar");
        let src = root.join("c.jpg");
        write_file(&src, b"x");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();

        assert_eq!(move_one(&src, &dest).status, MoveStatus::Moved);
        std::fs::remove_dir_all(&root).ok();
    }

    /// 照片一步都没动成时（这里用"目标目录不存在"造出 `rename` 失败 —— 服务端不会走到
    /// 这一步, 因为 `ensure_within` 要求目标目录已存在, 但 `move_one` 自己的契约必须成立）:
    /// 照片**与边车**都必须留在原地, 且不许谎报 `dest`。
    #[test]
    fn a_failed_photo_move_leaves_the_photo_and_its_sidecar_in_place() {
        let root = tmp("photo_fail");
        let src = root.join("d.jpg");
        write_file(&src, b"new");
        write_file(&root.join("d.xmp"), b"<x:xmpmeta/>");
        let missing_dest = root.join("not_there");

        let out = move_one(&src, &missing_dest);
        assert_eq!(out.status, MoveStatus::Failed);
        assert!(out.dest.is_none(), "没落成就不能编一个目标路径: {:?}", out.dest);
        assert!(src.is_file() && root.join("d.xmp").is_file(), "失败时一步都不许动");
        assert!(out.detail.is_some(), "失败必须给一句原因");

        std::fs::remove_dir_all(&root).ok();
    }

    /// 逐项计数: 一批三张（搬成 / 目标同名 / 目标边车挡路）必须落在三个不同的桶里,
    /// 且 `items` 与输入**顺序一一对应** —— 手机的"待重试"面板是按 src 认领结果的。
    #[test]
    fn move_many_counts_every_item_into_its_own_bucket() {
        let root = tmp("counts");
        let src_dir = root.join("src");
        std::fs::create_dir_all(&src_dir).unwrap();
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();

        let ok = src_dir.join("ok.jpg");
        write_file(&ok, b"ok");
        write_file(&src_dir.join("ok.xmp"), b"<x:xmpmeta/>");

        let clash = src_dir.join("clash.jpg");
        write_file(&clash, b"new");
        write_file(&dest.join("clash.jpg"), b"existing");

        let half = src_dir.join("half.jpg");
        write_file(&half, b"half");
        write_file(&src_dir.join("half.xmp"), b"<x:xmpmeta/>");
        std::fs::create_dir_all(dest.join("half.xmp")).unwrap();

        let s = move_many(&[ok.clone(), clash.clone(), half.clone()], &dest);
        assert_eq!(
            (s.moved, s.half_success, s.conflict, s.failed),
            (1, 1, 1, 0),
            "三个桶必须各落一项: {:?}",
            s.items
        );
        assert_eq!(s.items.len(), 3);
        assert_eq!(s.items[0].src, ok.to_string_lossy().as_ref());
        assert_eq!(s.items[0].status, MoveStatus::Moved);
        assert_eq!(s.items[1].src, clash.to_string_lossy().as_ref());
        assert_eq!(s.items[1].status, MoveStatus::Conflict);
        assert_eq!(s.items[2].src, half.to_string_lossy().as_ref());
        assert_eq!(s.items[2].status, MoveStatus::HalfSuccess);

        std::fs::remove_dir_all(&root).ok();
    }

    /// **状态的线上拼写是闭集契约**（手机端按字符串分组/本地化）。
    ///
    /// 手机端拿到的是一个**字符串**, 不是 Rust 的枚举: 把 `HalfSuccess` 改成 `Half`（camelCase
    /// 后是 `"half"`）在这边编译得过, 而手机那边会安静地落进"未知状态"的分支(不崩溃, 只是
    /// 半成功那一档**看不见了** —— 正好是纪律②最怕的那种静默)。所以这里逐个钉住拼写。
    #[test]
    fn every_status_has_a_stable_wire_spelling() {
        for (status, want) in [
            (MoveStatus::Moved, "\"moved\""),
            (MoveStatus::Conflict, "\"conflict\""),
            (MoveStatus::HalfSuccess, "\"halfSuccess\""),
            (MoveStatus::Failed, "\"failed\""),
        ] {
            assert_eq!(serde_json::to_string(&status).unwrap(), want);
        }
    }

    /// **响应形状逐字段对一遍 M3′ 手机端的 `MoveSummaryDto` / `MoveOutcomeDto`。**
    ///
    /// `rename_all = "camelCase"` 掉一个字段名（例如 `half_success` 没转成 `halfSuccess`）
    /// 在 Rust 侧编译得过, 但手机那边会安静地解析成 0/未知 —— 这条就是那种改动的靶子。
    /// 同时钉住 `dest: None` 序列化成 `null`（不是缺字段, 也不是空串）。
    #[test]
    fn the_summary_wire_shape_matches_the_phone_dto() {
        let summary = MoveSummary {
            moved: 1,
            half_success: 2,
            conflict: 3,
            failed: 4,
            items: vec![MoveOutcome {
                src: "D:\\p\\a.jpg".into(),
                dest: None,
                status: MoveStatus::Conflict,
                detail: Some("目标位置已有同名文件, 未覆盖".into()),
                pending_sidecars: Vec::new(),
            }],
        };
        let v: serde_json::Value = serde_json::to_value(&summary).unwrap();
        assert_eq!(v["moved"], 1);
        assert_eq!(v["halfSuccess"], 2, "{}", v);
        assert_eq!(v["conflict"], 3);
        assert_eq!(v["failed"], 4);
        assert_eq!(v["items"][0]["src"], "D:\\p\\a.jpg");
        assert_eq!(v["items"][0]["dest"], serde_json::Value::Null);
        assert_eq!(v["items"][0]["status"], "conflict");
        assert_eq!(v["items"][0]["detail"], "目标位置已有同名文件, 未覆盖");
        // `pendingSidecars` **必须是数组**且没有待补时是 `[]`（不是 `null`、不是缺字段）:
        // 手机端拿到 `null` 会当场崩在 `for (p in it.pendingSidecars)` 上。
        assert_eq!(
            v["items"][0]["pendingSidecars"],
            serde_json::json!([]),
            "{}",
            v["items"][0]
        );
        // 逐项项恰好这五个字段(与 M3′ 的 `MoveOutcomeDto` 同形, 一个不多一个不少)。
        assert_eq!(
            v["items"][0].as_object().map(|o| o.len()),
            Some(5),
            "{}",
            v["items"][0]
        );
    }

    /// **`pendingSidecars` 的线上形状**: 半成功项里它是**路径字符串数组**, 每一项都是源侧
    /// 真实存在的边车（手机直接把它当 `paths` 重发就能补上, 不必推导命名规则）。
    #[test]
    fn half_success_serializes_pending_sidecars_as_a_path_array() {
        let out = MoveOutcome {
            src: "D:\\p\\a.jpg".into(),
            dest: Some("D:\\p\\dst\\a.jpg".into()),
            status: MoveStatus::HalfSuccess,
            detail: Some("照片已移动, 但边车 a.xmp 没能跟着走, 需要重试".into()),
            pending_sidecars: vec!["D:\\p\\a.xmp".into(), "D:\\p\\a.jpg.xmp".into()],
        };
        let v: serde_json::Value = serde_json::to_value(&out).unwrap();
        assert_eq!(
            v["pendingSidecars"],
            serde_json::json!(["D:\\p\\a.xmp", "D:\\p\\a.jpg.xmp"]),
            "{}",
            v
        );
        assert_eq!(v.as_object().map(|o| o.len()), Some(5), "{}", v);
    }

    /// **两种命名习惯同时存在、且第一份被挡住时, 第二份仍必须被尝试。**
    ///
    /// 这是"早退"最贵的那个洞: 照片已经走了, 早退意味着第二份候选**永远**留在旧位置 ——
    /// 没有任何未来调用能补救它（`{照片+边车}` 那一项已经不存在了）。
    #[test]
    fn a_blocked_sidecar_does_not_stop_the_other_convention_from_following() {
        let root = tmp("two_conv_first_blocked");
        let src = root.join("c.jpg");
        write_file(&src, b"photo");
        let stem_sc = root.join("c.xmp");
        write_file(&stem_sc, b"<x:xmpmeta rating=\"1\"/>");
        let name_sc = root.join("c.jpg.xmp");
        write_file(&name_sc, b"<x:xmpmeta rating=\"2\"/>");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();
        // 挡住**第一个**候选（`{stem}.xmp`）: 目标位置放一个同名目录
        std::fs::create_dir_all(dest.join("c.xmp")).unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::HalfSuccess, "{:?}", out);
        assert_eq!(
            out.pending_sidecars,
            vec![stem_sc.to_string_lossy().to_string()],
            "只许列出**真的**没搬走的那一份: {:?}",
            out
        );
        assert!(
            dest.join("c.jpg.xmp").is_file(),
            "第一份失败不许把第二份候选跳过(早退会让它永远留在旧位置)"
        );
        assert!(!name_sc.exists(), "第二份必须真的离开旧位置");
        assert!(
            stem_sc.is_file(),
            "失败的那一份必须逐字节留在原地, 等用户清掉障碍再重试"
        );
        let detail = out.detail.as_deref().unwrap_or("");
        assert!(detail.contains("c.xmp"), "半成功的原因必须点名边车: {}", detail);

        std::fs::remove_dir_all(&root).ok();
    }

    /// **两份候选都被挡住 → `pendingSidecars` 必须把两份都列出来。**
    ///
    /// 这正是"标量字段会孤儿化的那一份": 早退(或只留一个名字)只能报出第一个, 第二份既没被
    /// 尝试、也不在任何字段里 —— 照片+两份边车的原始状态已经不可重建, 那份评分就真的丢了。
    #[test]
    fn both_sidecar_conventions_are_listed_when_neither_can_follow() {
        let root = tmp("two_conv_both_blocked");
        let src = root.join("c.jpg");
        write_file(&src, b"photo");
        let stem_sc = root.join("c.xmp");
        write_file(&stem_sc, b"<x:xmpmeta rating=\"1\"/>");
        let name_sc = root.join("c.jpg.xmp");
        write_file(&name_sc, b"<x:xmpmeta rating=\"2\"/>");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();
        std::fs::create_dir_all(dest.join("c.xmp")).unwrap();
        std::fs::create_dir_all(dest.join("c.jpg.xmp")).unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::HalfSuccess, "{:?}", out);
        assert_eq!(
            out.pending_sidecars,
            vec![
                // 顺序 = `sidecar_candidates` 的顺序(`{stem}.xmp` 先), 手机不依赖顺序但服务端不该乱序。
                stem_sc.to_string_lossy().to_string(),
                name_sc.to_string_lossy().to_string(),
            ],
            "两份没搬走的候选都必须列出来(标量字段会漏掉第二份): {:?}",
            out
        );
        assert!(
            stem_sc.is_file() && name_sc.is_file(),
            "两份都必须留在原地"
        );

        std::fs::remove_dir_all(&root).ok();
    }

    /// 没有扩展名的照片会让 `sidecar_candidates` 给出**同一个路径两次**
    /// （`photo` → `photo.xmp` / `photo.xmp`）。`pendingSidecars` 里不许出现重复项:
    /// 重试时同一个路径发两遍, 第二遍会因为"源已经被第一遍搬走"而报错。
    #[test]
    fn pending_sidecars_never_repeats_the_same_path() {
        let root = tmp("no_ext_dup");
        let src = root.join("photo");
        write_file(&src, b"photo");
        let sc = root.join("photo.xmp");
        write_file(&sc, b"<x:xmpmeta rating=\"1\"/>");
        let dest = root.join("d");
        std::fs::create_dir_all(&dest).unwrap();
        std::fs::create_dir_all(dest.join("photo.xmp")).unwrap();

        let out = move_one(&src, &dest);
        assert_eq!(out.status, MoveStatus::HalfSuccess, "{:?}", out);
        assert_eq!(
            out.pending_sidecars,
            vec![sc.to_string_lossy().to_string()],
            "两个候选重合时只许报一次: {:?}",
            out
        );

        std::fs::remove_dir_all(&root).ok();
    }
}
