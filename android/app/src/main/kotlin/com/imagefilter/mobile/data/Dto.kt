// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KxJson

/**
 * 与桌面端 `serde(rename_all = "camelCase")` 的输出逐字对应。
 *
 * `ignoreUnknownKeys = true` 是**刻意**的: 服务端以后加字段时, 已经装在手机上的
 * 老客户端不该因为多了个键就崩。反过来(服务端删字段)会走默认值, 所以我们给所有
 * 非必需字段都留了默认值。
 *
 * `coerceInputValues = true` 同样刻意, 它管两件真实会发生的事:
 *   · `"pendingSidecars": null` → 走默认值 `[]`(服务端契约说它**永远是数组**,
 *     但手机拿到 `null` 会当场崩在 `for (p in it.pendingSidecars)` 上 —— 见
 *     `src-tauri/src/mover.rs` 里那句实测注释, 所以这层兜底必须留着);
 *   · 未知枚举/非法值 → 走默认值, 而不是抛 [kotlinx.serialization.SerializationException]。
 *
 * `explicitNulls = false` 让"值为 null"与"键不存在"等价 —— `DecisionWrite` 的
 * `rating: Option<u8>` 本来就是这个语义(serde 对 `Option` 缺字段给 `None`),
 * 所以手机不写 `rating` 就是"不动这个属性"。
 */
val Json = KxJson {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** `GET /api/v1/ping`。**未配对也能调**, 且不泄露任何路径(规格 §5.1)。 */
@Serializable
data class PingDto(
    val name: String,
    val version: String,
    val requiresToken: Boolean = true,
)

/** `GET /api/v1/shares` —— 共享根列表。空数组是安全默认值: 谁都访问不了。 */
@Serializable
data class SharesDto(val shares: List<String> = emptyList())

/** `GET /api/v1/drives` 的数组元素(`scanner/drives.rs::DriveInfo`)。 */
@Serializable
data class DriveDto(
    val mountPoint: String = "",
    val driveType: String = "",
    val label: String = "",
    val available: Boolean = false,
)

/** `GET /api/v1/browse` —— **单层**目录(`scanner/mod.rs::FolderEntry`)。 */
@Serializable
data class FolderEntryDto(
    val path: String = "",
    val name: String = "",
    /** 直接照片文件数（不含视频、不含子目录）。 */
    val photoCount: Int = 0,
    val hasSubdirs: Boolean = false,
    val subfolders: List<FolderEntryDto> = emptyList(),
    /** 直接视频文件数；界面可单独显示。 */
    val videoCount: Int = 0,
    /** 直接子目录数；有子目录时界面显示这个。 */
    val subfolderCount: Int = 0,
)

/** 对应 `scanner/mod.rs::PhotoExif` —— 全部可空, 因为桌面端只有在能读到时才填。 */
@Serializable
data class PhotoExifDto(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: Int? = null,
    val dateTaken: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val fileSize: Long = 0,
)

/** `GET /api/v1/scan` 的数组元素。 */
@Serializable
data class ScannedPhotoDto(
    val path: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val isRaw: Boolean = false,
    val isVideo: Boolean = false,
    val modifiedAt: Long = 0,
    val exif: PhotoExifDto = PhotoExifDto(),
)

/**
 * `PUT /api/v1/decisions` 的单项。
 *
 * `null` = **不动**这个属性(不是清空) —— 与桌面端 `DecisionWrite` 同义;
 * 清空标签是显式的 `clearLabel = true`(两者必须区分, 否则撤销标签后旧值会复活)。
 */
@Serializable
data class DecisionDto(
    val path: String,
    val rating: Int? = null,
    val label: String? = null,
    val clearLabel: Boolean = false,
)

/** `GET /api/v1/decisions` 的数组元素(`xmp.rs::DecisionRead`)。 */
@Serializable
data class DecisionReadDto(
    val path: String = "",
    val rating: Int? = null,
    val label: String? = null,
    /** 实际读到的边车路径(状态行/调试用, 可能为 null)。 */
    val sidecar: String? = null,
    /**
     * **这一项的边车读到了没有** —— 服务端逐项给出的可选信号:
     *   · `false` = 边车**存在但读不动**(被别的程序占着 / 权限 / 编码 / 超限): 那么这一项里
     *     那两个 `null` **不是**"电脑上没有值"的证据, 而是"**不知道**";
     *   · `true` = 确实读到了(读到什么就是什么, `null` 就是"没有这个属性");
     *   · `null`(缺字段) = **没有这个信号** —— 与老服务端兼容时的行为: 按"读到了"处理,
     *     也就是本字段出现之前的全部行为。
     *
     * ⚠️ 为什么这一个字节要紧: "保留(右滑)"这条链路的中心保证是"绝不把电脑上的 4/5 星静默降成
     * 3 星", 而它的判据正是"服务端回报的 rating 是 `null`"。读失败也发 `null`
     * (见 `src-tauri/src/xmp.rs::read_one` 的注释), 两者混淆的代价是一次**静默的数据损失**。
     */
    val sidecarReadable: Boolean? = null,
)

/**
 * `WriteSummary.failures[]` 的一项(`xmp.rs::WriteFailure`)。
 *
 * ⚠️ **`code` 是 XMP 码集, 不是 §5.3 的 HTTP 码集** —— 查
 * [XmpErrorCodes.messageKey], **不许**查 [ApiErrorCodes.messageKey]。
 * `detail` 是诊断用的中文, 只用于排查, 不进 UI 文案。
 */
@Serializable
data class WriteFailureDto(
    val path: String = "",
    val code: String = "",
    val detail: String = "",
)

/**
 * `PUT /api/v1/decisions` 成功时的返回体(`xmp.rs::WriteSummary`), **原样透出**。
 *
 * 注意别与 `ImportSummary` 的 `{ imported, skipped, renamed, failed }` 混淆 ——
 * 那是导入归档的口径, 本服务不涉及(规格 §5.2)。
 */
@Serializable
data class WriteSummaryDto(
    val written: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val failures: List<WriteFailureDto> = emptyList(),
    /** true = 至少有一次写入退化成"非原子直写"(SMB/CIFS 不支持 rename 覆盖)。 */
    val degraded: Boolean = false,
)

/** `POST /api/v1/move` 的请求体(`routes.rs::MoveReq`)。字段名逐字一致。 */
@Serializable
data class MoveReqDto(
    val paths: List<String>,
    val destDir: String,
)

/**
 * 逐项移动结果的**闭集**状态(`mover.rs::MoveStatus`)。
 *
 * 这里**刻意只放服务端真的会产出的四档**: 加一档永远收不到的状态, 只会让
 * "服务端能给你哪几种结果"变成一句谎话(mover.rs 的注释记了同一条理由)。
 * 线上拼写是契约的一部分, 所以逐个钉在 [wire] 上。
 */
enum class MoveStatus(val wire: String) {
    MOVED("moved"),
    CONFLICT("conflict"),
    HALF_SUCCESS("halfSuccess"),
    FAILED("failed"),
    ;

    companion object {
        /** 认不出来就给 `null` —— **不许**瞎猜成某一档真实结果。 */
        fun fromWire(wire: String): MoveStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 单张照片的移动结果(`mover.rs::MoveOutcome`) —— **恰好五个字段**。
 *
 * `status` 刻意留成 `String`(而不是直接上 [MoveStatus]): 服务端把它当字符串发,
 * 而 mover.rs 的注释明说"把 `HalfSuccess` 改成 `Half` 在这边编译得过, 手机那边会
 * 安静地落进未知状态的分支" —— 所以手机必须有一条**能看见的未知分支**,
 * 用 [moveStatus] (`null` = 未知) 而不是把未知值悄悄归到某个真实结果上。
 *
 * `detail` 是**人类可读提示, 不是数据通道**(规格 §5.3 的 i18n 纪律): 绝不许解析它。
 * 机器数据在 [pendingSidecars]。
 */
@Serializable
data class MoveOutcomeDto(
    /** 规范化后的源路径(整条路径), 用来把结果对回网格里的那张照片。 */
    val src: String = "",
    /** 照片最终落到的路径; `Failed` 时是 `null` —— 不许编一个"本来想去的地方"。 */
    val dest: String? = null,
    val status: String = "",
    val detail: String? = null,
    /**
     * **还没搬走的边车**(空 = 都跟上了)。永远是数组; 服务端保证 `[]` 而不是 `null`,
     * 手机这层再用默认值兜一次底(拿到 `null` 不许崩)。
     */
    val pendingSidecars: List<String> = emptyList(),
) {
    /** `null` = 这个构建不认识的 status, UI 该说"结果未知"而不是猜。 */
    val moveStatus: MoveStatus? get() = MoveStatus.fromWire(status)

    /**
     * 半成功项的**重试通道**(规格 §5.2: `POST /api/v1/move { paths: item.pendingSidecars,
     * destDir: parent(item.dest) }`)。
     *
     * 三个"不许":
     *   · **不许推导边车命名规则** —— `paths` 逐字用服务端给的 [pendingSidecars]
     *     (`{stem}.xmp` / `{name}.xmp` 这条规则只有桌面端知道);
     *   · **不许把 `src` 再发一遍** —— 半成功项的照片**已经搬走了**, 带上它整批 404;
     *   · **不许解析 `detail`** —— 它只是给人看的一句话。
     *
     * 不是半成功、或没有待补边车、或 `dest` 缺失(拿不到目标目录)时返回 `null`。
     */
    fun pendingSidecarRetry(): MoveReqDto? {
        if (moveStatus != MoveStatus.HALF_SUCCESS) return null
        if (pendingSidecars.isEmpty()) return null
        val destDir = parentDirOf(dest ?: return null) ?: return null
        return MoveReqDto(paths = pendingSidecars, destDir = destDir)
    }
}

/**
 * `POST /api/v1/move` 的返回体(`mover.rs::MoveSummary`)。计数 + 与请求顺序对应的逐项结果。
 *
 * ⚠️ 批次级的失败(路径越界 / 不存在 / 空批次)走的是 §5.3 的 HTTP 码集;
 * 而"目标已存在"**不走** `409 conflict`, 它在这里报 `status == "conflict"`
 * (批次级 409 会丢掉同一批里成功的那些)。两处来源必须分得清。
 */
@Serializable
data class MoveSummaryDto(
    val moved: Int = 0,
    val halfSuccess: Int = 0,
    val conflict: Int = 0,
    val failed: Int = 0,
    val items: List<MoveOutcomeDto> = emptyList(),
)

/**
 * 规范路径的父目录 —— **只对服务端给出的路径取父目录, 绝不自己拼路径**。
 *
 * 为什么值得单独一个函数: 半成功的重试要 `destDir = parent(item.dest)`, 而规范路径
 * 在 Windows 上可能带 verbatim 前缀(`\\?\D:\...`, 见 `paths::ensure_within` 的
 * `canonicalize`), 那个前缀里有一个**字面问号** —— 任何"自己拼一个 D:\..."的写法都会把它弄丢。
 * 所以这里只做一件事: 在最后一个分隔符处切开。
 *
 * 返回 `null` 的情形: 没有分隔符(`a.jpg`)、或路径以分隔符结尾(那是目录, 不是照片路径)、
 * 或切出来的头是空的。**磁盘根目录要保留末尾反斜杠**(`D:\`、`\\?\D:\`)。
 */
internal fun parentDirOf(canonicalPath: String): String? {
    val sep = maxOf(canonicalPath.lastIndexOf('\\'), canonicalPath.lastIndexOf('/'))
    if (sep <= 0 || sep == canonicalPath.lastIndex) return null
    val head = canonicalPath.substring(0, sep)
    return if (head.endsWith(':')) "$head\\" else head
}
