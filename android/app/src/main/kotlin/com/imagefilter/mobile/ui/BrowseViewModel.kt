// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiError
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.BrowseMessages
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.UnauthorizedCause
import com.imagefilter.mobile.data.apiCodeFor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

// ── 纯函数: 树与文案的推导(与 Compose 无关, 每个都有 JVM 单测) ──────────────────
//
// 这一层刻意从 Composable 里剥出来: 安卓 UI 测试在本项目里没有依赖(也不该为 M1′ 引入),
// 所以"合并成什么样、行怎么摊平、次级信息写什么"必须是**可以真的断言**的纯函数 ——
// 这也是桌面端 `mergeChildren` 的既有做法。

/**
 * 把某个节点的子目录合并进扁平索引(以 path 为键)。纯函数, 便于单测。
 *
 * **同一父节点重复合并以最新为准**(`index + (parent to …)`): 慢盘上两次响应乱序回来时,
 * 旧数据不许覆盖新的。M1′ 就用"最新覆盖"(BrowseMergeTest 钉住), 代次号等真出现乱序再加。
 *
 * 子目录**按名字排序**(不区分大小写): 用户看到的顺序必须稳定, 不许随文件系统的返回顺序抖动。
 */
fun mergeChildren(
    index: Map<String, List<FolderEntryDto>>,
    parentPath: String,
    children: List<FolderEntryDto>,
): Map<String, List<FolderEntryDto>> =
    index + (parentPath to children.sortedBy { it.name.lowercase() })

/** 已加载的那一层。没加载过就是空 —— **空不等于"没有子目录"**(见 [loadedChildren])。 */
fun childrenOf(index: Map<String, List<FolderEntryDto>>, path: String): List<FolderEntryDto> =
    index[path].orEmpty()

/**
 * 目录的**显示名** —— 只用于显示, **绝不**用它拼路径。
 *
 * 服务端给的路径是不透明的(可能带 verbatim 前缀 `\\?\`, 里面有一个字面问号),
 * 所以这里只做一件事: 在最后一个分隔符处切开取尾段。`path` 本身**原样保留**在任何
 * 发回去的查询串里([rootEntries] 把它逐字带进 DTO)。
 */
fun displayNameOf(path: String): String {
    val trimmed = path.trimEnd('\\', '/').ifEmpty { return path }
    val sep = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
    val leaf = if (sep >= 0) trimmed.substring(sep + 1) else trimmed
    return leaf.ifEmpty { trimmed }
}

/**
 * 路径的**显示形态** —— 把 Windows 的 verbatim 前缀 `\\?\` 去掉。
 *
 * 为什么非要去掉那四个字符: 它是 Windows 的实现细节(长路径 / 绕过 MAX_PATH 归一化),
 * 用户在自己的电脑上从来没见过它 —— 标题下面那行路径本来是在回答"我在看电脑上的哪个目录",
 * 而 `\\?\F:\壁纸` 只会让人以为自己连错了机器。
 *
 * ⚠️ **只用于显示**。协议里的那个形态(`\\?\F:\壁纸`)是**规范路径**, 三件事都必须逐字用它,
 * 不许用这里的返回值:
 *   · 发出去的 `path=` 查询参数([ApiClient] 的每个端点) —— 前缀里有一个**字面问号**,
 *     自己重新拼一遍就会把它弄丢(见 `ApiClient` 的 KDoc);
 *   · 决策镜像的 map 键([DecisionStore] 的 local/remote/known) —— 换一个键就是**另一张照片**,
 *     用户的评分会挂到一个谁也读不到的路径上;
 *   · 服务端逐项回报的对账([DecisionStore.applyAck] 靠路径把失败归位)。
 *
 * 所以"显示"与"传输"在这条链路上是**两个函数**: 界面调 [displayPath], 网络调原样。
 * 有一次真实教训值得写下来: 把归一化做进"路径进来时", 桌面端那座桥收到的路径就与它自己的
 * 共享根对不上, 所有请求变成 403 —— 那正是这条纪律的来历。
 *
 * 只处理两种**有等价普通写法**的形态:
 *   · `\\?\F:\壁纸` → `F:\壁纸`(盘符);
 *   · `\\?\UNC\server\share\x` → `\\server\share\x`(UNC)。
 * 其余(卷 GUID `\\?\Volume{…}\` 之类)没有等价的普通写法, 原样返回 —— 去掉前缀只会得到
 * 一个看起来更像坏掉的路径。
 */
fun displayPath(path: String): String {
    if (!path.startsWith(VERBATIM_PREFIX)) return path
    val rest = path.removePrefix(VERBATIM_PREFIX)
    if (rest.startsWith("UNC\\", ignoreCase = true)) return "\\\\" + rest.substring(4)
    // `D:\…` / `D:`: 第二个字符是冒号、第一个是字母 —— 这才是盘符形态。
    return if (rest.length >= 2 && rest[0].isLetter() && rest[1] == ':') rest else path
}

/** `\\?\`(verbatim 前缀) 本身。Kotlin 里要写四个反斜杠/问号, 所以给它一个名字。 */
private const val VERBATIM_PREFIX = "\\\\?\\"

/**
 * 共享根 → 根节点列表。
 *
 * **顺序逐字保留服务端的配置顺序**(用户在电脑上排的序, 手机不该替他重排);
 * 空路径直接丢掉(服务端不该发, 但发了一个空路径也不该变成一行点不开的东西)。
 *
 * 根节点只有 `path` 是**权威**的: [FolderEntryDto.name] 是本地推导的显示名,
 * 而 `photoCount` / `hasSubdirs` 在这一层**根本不知道**(所以保留默认值, 不许瞎填)。
 */
fun rootEntries(shares: List<String>): List<FolderEntryDto> =
    shares.filter { it.isNotBlank() }.map { FolderEntryDto(path = it, name = displayNameOf(it)) }

/**
 * 叶子文件夹的直接照片数。服务端 `browse` 现在给出**直接照片数**(不是递归总数)，
 * 所以 0 是一句诚实的话；负数(服务端不该发)按 0 处理。
 */
fun photoCountLabel(photoCount: Int): String = "${photoCount.coerceAtLeast(0)} 张"

/** "张数未统计" 仍用于筛选栏：那里的 0 确实可能意味着"这次没读到"。 */
const val NOT_COUNTED = "张数未统计"

/**
 * 列表行的次级信息: 共享根显示原路径；有子目录显示子目录数；叶子显示照片数；
 * 有视频时再补一段视频数(视频数量的显示入口保留在这里)。
 */
fun secondaryLabel(entry: FolderEntryDto, isRoot: Boolean): String {
    // 根行的第二行是路径; 显示形态去掉 `\\?\`(传输仍然用 entry.path 原样)。
    if (isRoot) return displayPath(entry.path)
    val base = if (entry.hasSubdirs) {
        "${entry.subfolderCount} 个文件夹"
    } else {
        photoCountLabel(entry.photoCount)
    }
    return if (entry.videoCount > 0) "$base · ${entry.videoCount} 视频" else base
}

/** 抽屉树里的一行。 */
data class TreeRow(val entry: FolderEntryDto, val depth: Int, val isRoot: Boolean)

/**
 * 把"共享根 + 已加载的层 + 展开状态"摊平成**可见行**序列 —— 纯函数。
 *
 * 两个刻意的约束:
 *   · **只走已展开的节点**: 展开才有 `index[path]`, 所以这条摊平不会触发任何请求
 *     (懒加载纪律: 服务端只给单层, 一次拿全这件事在契约上就不存在);
 *   · **带环保护**: 服务端若因为某个bug回了一个指回祖先的子目录, 摊平必须停下来,
 *     而不是把界面栈打爆(桌面端"点设备=整盘遍历"是同一类事故的近亲)。
 */
fun visibleRows(
    roots: List<FolderEntryDto>,
    index: Map<String, List<FolderEntryDto>>,
    expanded: Set<String>,
): List<TreeRow> {
    val out = mutableListOf<TreeRow>()

    fun walk(entries: List<FolderEntryDto>, depth: Int, isRoot: Boolean, seen: Set<String>) {
        for (entry in entries) {
            if (entry.path in seen) continue
            out += TreeRow(entry, depth, isRoot)
            if (entry.path !in expanded) continue
            walk(childrenOf(index, entry.path), depth + 1, false, seen + entry.path)
        }
    }

    walk(roots, depth = 0, isRoot = true, seen = emptySet())
    return out
}

// ── 状态 ─────────────────────────────────────────────────────────────────────

/** 某条路径上的一次失败。文案随时按 [code]/[cause] 取, 不在这里缓存句子。 */
data class FolderError(val code: String, val cause: UnauthorizedCause? = null) {
    fun text(): String = BrowseMessages.forFailure(code, cause)
}

/**
 * 目录页的全部状态。**不含任何安卓/Compose 类型** —— 所以它能在 JVM 单测里被真的走一遍。
 */
data class BrowseState(
    /** 共享根(顺序 = 电脑上的配置顺序)。 */
    val roots: List<FolderEntryDto> = emptyList(),
    val rootsLoading: Boolean = false,
    val rootsError: FolderError? = null,
    /** 已加载的层: path → 它的**直接**子目录。 */
    val index: Map<String, List<FolderEntryDto>> = emptyMap(),
    /** 已展开的节点(展开 ≠ 已加载, 但本实现里展开就发起加载)。 */
    val expanded: Set<String> = emptySet(),
    /** 正在飞的加载(UI 画占位行, 同时防重复请求)。 */
    val loading: Set<String> = emptySet(),
    val errors: Map<String, FolderError> = emptyMap(),
    /** 主区当前看着的目录。 */
    val selected: String? = null,
) {
    val hasRoots: Boolean get() = roots.isNotEmpty()

    /** 加载过这一层没有 —— `false` 与"加载过但是空的"是两件事。 */
    fun isLoaded(path: String): Boolean = index.containsKey(path)

    fun children(path: String): List<FolderEntryDto> = childrenOf(index, path)

    fun isLoading(path: String): Boolean = path in loading

    fun errorAt(path: String): FolderError? = errors[path]

    val selectedChildren: List<FolderEntryDto>
        get() = selected?.let { children(it) }.orEmpty()

    val selectedLoading: Boolean
        get() = selected?.let { isLoading(it) } ?: false

    val selectedError: FolderError?
        get() = selected?.let { errorAt(it) }

    /**
     * 选中目录对应的节点。找不到时**用路径合成一条** —— 合成项的 `name` 只是显示名,
     * 权威字段只有 `path`(进网格的 `scan()` 用的就是它, 逐字发回服务端)。
     */
    fun entryFor(path: String): FolderEntryDto =
        roots.firstOrNull { it.path == path }
            ?: index.values.asSequence().flatten().firstOrNull { it.path == path }
            ?: FolderEntryDto(path = path, name = displayNameOf(path))
}

/**
 * 目录页的**状态机**(纯逻辑, 无 Compose/Android 类型, 可 JVM 单测)。
 *
 * 刻意**不**继承 `androidx.lifecycle.ViewModel`: 那个基类的 `viewModelScope` 跑在
 * `Dispatchers.Main` 上, JVM 单测要跑它就得先摆一个主线程调度器(或引 Robolectric)。
 * 计划对这一步的要求是"树的合并抽成纯函数、逻辑能在 JVM 上覆盖", 所以这里就是一个
 * 持有 [StateFlow] 的普通类 —— 界面只做两件事: 收状态、把用户动作转成方法调用。
 *
 * 三条纪律:
 *   · **懒加载**: 只有点开节点才发 `browse()`, 且只合并**这一层**(服务端是单层语义)。
 *     绝不在启动时递归铺开整棵树 —— 桌面端为此踩过"点设备=整盘遍历"的坑;
 *   · **不调 `drives()`**: 根节点只有 `shares()`。设备/盘符是给"电脑上插了卡"用的, M4′ 再暴露;
 *   · **401 必须走消歧**: 与配对页同一条 —— 第一次 401 绝不当成"token 被轮换"。
 */
class BrowseViewModel(
    private val pairing: Pairing,
    /**
     * 注入点: 生产路径就是默认值([ApiClient] 内部用 `buildHttpClient` 装鉴权头)。
     * 覆盖它时必须同样以 `buildHttpClient` 为底, 否则"token 有没有发出去"会变成假绿。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
) {
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val api: ApiClient by lazy { clientFactory(pairing.url, pairing.token) }

    /**
     * 拉共享根当根节点。
     *
     * 失败时**保留已有的根**(一次刷新失败不该把用户眼前的列表清空), 只把错误摆出来。
     */
    suspend fun loadRoots() {
        _state.update { it.copy(rootsLoading = true, rootsError = null) }
        try {
            val shares = api.shares()
            _state.update {
                it.copy(roots = rootEntries(shares), rootsLoading = false, rootsError = null)
            }
        } catch (e: ApiException) {
            _state.update { it.copy(rootsLoading = false, rootsError = failure(e)) }
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException —— 别把取消说成网络故障
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(rootsLoading = false, rootsError = synthesized(e)) }
        }
    }

    /**
     * 点开一个节点: 选中它、展开它、并把**这一层**拉回来。
     *
     * 已经加载过的层**不再发请求**(展开是幂等的) —— 慢盘上反复点同一个目录不该变成
     * 一串重复请求。这一层失败过的话 [retry] 负责重来。
     */
    suspend fun open(path: String) {
        _state.update { it.copy(selected = path, expanded = it.expanded + path) }
        loadChildren(path)
    }

    /** 重试某一层(失败后用户按「重试」)。已缓存的层也**强制**重拉 —— 用户按它就是要新数据。 */
    suspend fun retry(path: String) {
        loadChildren(path, force = true)
    }

    private suspend fun loadChildren(path: String, force: Boolean = false) {
        val current = _state.value
        if (path in current.loading) return
        if (!force && current.isLoaded(path)) return

        _state.update { it.copy(loading = it.loading + path, errors = it.errors - path) }
        try {
            val entry = api.browse(path)
            _state.update {
                it.copy(
                    // 合并的只有**直接**子目录: 服务端是单层语义, 孙辈要等它自己被点开
                    index = mergeChildren(it.index, path, entry.subfolders),
                    loading = it.loading - path,
                    errors = it.errors - path,
                )
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = it.loading - path, errors = it.errors + (path to failure(e))) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(loading = it.loading - path, errors = it.errors + (path to synthesized(e))) }
        }
    }

    /** 只有 401 有资格触发消歧复探(Task 2 的纪律), 其余码一律不许猜成因。 */
    private suspend fun failure(e: ApiException): FolderError {
        val cause = if (e.isUnauthorized) api.classifyUnauthorized() else null
        return FolderError(e.code, cause)
    }

    private fun synthesized(e: IOException) = FolderError(ApiError.synthesized(apiCodeFor(e)).code)
}
