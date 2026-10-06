# ImageFilter Mobile · M2′ 查看器 / 打分回写 / 筛选 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通端到端价值点——**在手机上给一张照片打 4 星，电脑上那个文件夹的界面立刻变成 4 星**；同时补上缩放查看与按星级/色标筛选。

**Architecture:** 沿用 M1′ 的 `ApiClient` 与共用 OkHttp。查看器用 Compose 自己的 `transformable` + `graphicsLayer`（预览已被服务端降到 ≤2048px，不需要 `SubsamplingScaleImageView`）。写入走 `PUT /api/v1/decisions`，采用**乐观更新 + 失败可见**（规格 §4.3/§11 的"未同步 ≠ 已同步"纪律）。

**Tech Stack:** 同 M1′（Kotlin 2.0.21 / Compose BOM 2024.12.01 / Coil 3.0.4 / OkHttp 4.12.0）。**不新增任何依赖**。

## Global Constraints

- 沿用 M1′ 的 Global Constraints（中文注释 + SPDX 头 + conventional commits + 只 `git add` 明确路径 + 不引 Room）。
- **本计划不新增任何第三方依赖。** 若某步让你想加库，停下来报告 NEEDS_CONTEXT。
- **服务端一行都不许改**（M0′ 已经提供 `/preview` `/decisions`）。若发现服务端缺东西，报 BLOCKED 并说明。
- **写边车的永远是桌面端**：手机只发 `PUT /decisions`，不碰 `.xmp` 格式。
- **乐观更新必须可回滚**：UI 先变、请求失败要变回去并给出可执行提示（规格 §11 纪律 ③）。
- 规格出处：[2026-10-06-imagefilter-android-design.md](../specs/2026-10-06-imagefilter-android-design.md) §4.3 / §5.2 / §8.1 / §8.2 / §11。

---

## 范围说明

| | 内容 |
|---|---|
| **M2′**（本计划） | 查看器（手势 + 预取）、星级/色标写入、筛选栏、端到端验证 |
| 不在本计划 | 滑卡手势（M3′）、移动分类（M3′）、待重试队列与面板（M3′）、撤销（M3′）、二维码与打通引导（M4′） |

**依赖**：M1′ 的 Task 1–5 必须已完成（网格能看到缩略图）。M0′ 桌面端服务必须能跑且 `PUT /api/v1/decisions` 可用。

---

### Task 1: 查看器（缩放/平移 + 预览加载 + 邻张预取）

**手势规则必须在写代码前定死**（规格 §8.2）——不这么定，缩放与滑动一定会互相抢手势：

| 状态 | 单指 | 双指 |
|---|---|---|
| **决策态**（默认） | 拖动 = 切换照片（M2′ 先用水平拖动切上一张/下一张；M3′ 换成滑卡决策） | 缩放 → 进入查看态 |
| **查看态** | 平移 | 继续缩放；缩回原始比例 → 回到决策态 |

**Files:**
- Create: `android/app/src/main/kotlin/com/imagefilter/mobile/ui/ViewerScreen.kt`
- Create: `android/app/src/main/kotlin/com/imagefilter/mobile/ui/ViewerState.kt`（可 JVM 测的纯状态）
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/ViewerStateTest.kt`
- Modify: `ui/GridScreen.kt`（点击格子打开查看器）

**Interfaces:**
- Consumes: `ApiClient.previewUrl(path, max, mtime, full)`、`rememberAppImageLoader(token)`
- Produces:
  - `data class ViewerMode(val scale: Float, val offsetX: Float, val offsetY: Float)`
  - `fun clampScale(s: Float): Float`、`fun isViewingMode(m: ViewerMode): Boolean`、`fun resetView(): ViewerMode`
  - `@Composable fun ViewerScreen(photos: List<ScannedPhotoDto>, startIndex: Int, pairing: Pairing, onClose: () -> Unit, onIndexChange: (Int) -> Unit)`

- [ ] **Step 1: 先写纯状态测试（TDD）**

`ViewerStateTest.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.ViewerMode
import com.imagefilter.mobile.ui.clampScale
import com.imagefilter.mobile.ui.isViewingMode
import com.imagefilter.mobile.ui.nextIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateTest {
    @Test
    fun scale_is_clamped_to_a_sane_range() {
        assertEquals(1f, clampScale(0.1f))     // 不允许缩到比原始更小(会露出黑边)
        assertEquals(8f, clampScale(99f))      // 上界 8x: 预览只有 2048px, 再放大只是插值
        assertEquals(2.5f, clampScale(2.5f))
    }

    @Test
    fun viewing_mode_starts_above_natural_scale() {
        // 判定"是否进入查看态"的唯一依据是缩放比例 —— 这决定单指手势是切图还是平移
        assertFalse(isViewingMode(ViewerMode(1f, 0f, 0f)))
        assertTrue(isViewingMode(ViewerMode(1.01f, 0f, 0f)))
        assertTrue(isViewingMode(ViewerMode(3f, 10f, -10f)))
    }

    @Test
    fun index_navigation_is_clamped_at_both_ends() {
        // 到末尾再右滑应该"停住", 不能绕回第一张(那会让人以为照片丢了)
        assertEquals(0, nextIndex(0, -1, 10))
        assertEquals(9, nextIndex(9, +1, 10))
        assertEquals(5, nextIndex(4, +1, 10))
        assertEquals(0, nextIndex(0, +1, 0))   // 空列表不该越界
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon testDebugUnitTest --tests "*ViewerStateTest*"
```

Expected: 编译失败（`ui.ViewerMode` 等不存在）。

- [ ] **Step 3: 实现纯状态**

`ui/ViewerState.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

/** 查看器的视图状态。缩放 1f = 原始适配屏幕。 */
data class ViewerMode(val scale: Float, val offsetX: Float, val offsetY: Float)

private const val MIN_SCALE = 1f
/** 上界 8x: 预览最长边只有 2048px(服务端已降采样), 再放大只是插值, 没有新信息。 */
private const val MAX_SCALE = 8f

fun clampScale(s: Float): Float = s.coerceIn(MIN_SCALE, MAX_SCALE)

fun resetView(): ViewerMode = ViewerMode(1f, 0f, 0f)

/**
 * 是否处于"查看态"。
 *
 * 这是**唯一**决定单指手势含义的判据: 查看态里单指是平移, 否则是切图。
 * 不做成显式开关的理由: 开关会与用户的双指动作脱节(缩回 1x 却还在查看态)。
 */
fun isViewingMode(m: ViewerMode): Boolean = m.scale > 1.0001f

/** 邻张导航, 两端夹住不绕回。 */
fun nextIndex(current: Int, delta: Int, count: Int): Int {
    if (count <= 0) return 0
    return (current + delta).coerceIn(0, count - 1)
}
```

- [ ] **Step 4: 实现查看器**

**要求**（布局细节由实现者定，以下必须成立）：

- 图片源用 `previewUrl(path, max = 2048, mtime, full = false)`。**默认仍不用 `full=1`**（那会让电脑做全解码，慢），但**必须提供一个「高清」入口**：长按图片，或点右上角「高清」→ 用同一 URL 但 `full=true` 重新请求并替换显示。
  > **为什么这个入口是必需项而不是锦上添花**：M0′ Task 2 已**实测**（`F:\壁纸` 的 8 个真实样张）——ARW 的内嵌预览**全部只有 1616×1080**，索尼"已增强-降噪"的 DNG 只有 1024×683（HDR 那张 256×171），**没有一张达到 2048**。也就是说默认档下放大到 1:1 验对焦**不够**，DNG 那张连看构图都勉强。**没有这个入口，查看器对本项目的真实素材就只能"看个大概"。**
  > 进入高清态要有明确反馈（"正在让电脑解码…"），因为全解码一张 **50MB 线性 DNG** 是秒级到十几秒级；**请求失败必须能退回默认档并提示，不能白屏**。
  > **不做预取**：高清只由用户显式触发。滑卡预取永远走默认档（预取一个 50MB DNG 的全解码会明显拖慢电脑）。
- 缩放/平移：`Modifier.pointerInput { detectTransformGestures { _, pan, zoom, _ -> ... } }` 更新 `scale/offset`；用 `graphicsLayer { scaleX/Y; translationX/Y }` 应用
- **缩回 1x 时自动贴边归位**（`offset = 0`），否则图会飘在屏幕外
- 邻张预取：进查看器后，对 `index+1` 与 `index-1` 各发一次 Coil 预取（`imageLoader.enqueue(ImageRequest)`），这样左右切换是瞬时的
- 顶部显示 `文件名 · (i+1)/N`；**不要**用"欢迎页也有的提示文案"判断查看器是否打开（桌面端为此误判过两次，交接文档 §8 有记录）
- 单击返回/关闭；双击在 1x 与 2x 间切换
- **M2′ 的单指水平拖动 = 上一张/下一张**（M3′ 会换成滑卡决策，届时只改手势处理，不动这个文件的状态机）

- [ ] **Step 5: 真机验证**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon installDebug
```

打勾：
1. 点格子 → 打开查看器并显示预览图（不是缩略图放大后的糊图）
2. 双指放大 → 单指变成平移（**不会切到下一张**）；缩回 1x → 单指又能切图
3. 快速连切 30 张：无黑屏、无明显卡顿
4. 看图放大到 8x 再缩回：不崩、不飘出屏幕
5. 一张 RAW（`F:\壁纸` 里的 ARW，内嵌预览 1616×1080）：默认档能显示；**长按/点「高清」→ 电脑开始解码，手机上出现明显更清晰的图（能看清对焦）**；再试一个 **50MB 的增强型 DNG**，**记录耗时**（这条是规格附录 B #11 的实测数据来源）
6. **高清失败要能退**：把电脑上的服务关掉，再点「高清」→ 应退回默认档 + 给一句可执行提示，**不白屏**
7. 高清态下左右切图：应回到默认档加载（不要把高清态带到下一张，那会让每张都等一次全解码）

- [ ] **Step 6: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/ViewerScreen.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/ViewerState.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/GridScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/ViewerStateTest.kt
git commit -m "feat(android): 查看器(transformable 缩放/平移 + 手势规则 + 邻张预取)"
```

---

### Task 2: 星级与色标写入（乐观更新 + 失败可见）

**这是本计划的核心**：跨端同步第一次真正发生。

**Files:**
- Create: `ui/RatingBar.kt`、`ui/DecisionStore.kt`
- Modify: `ui/ViewerScreen.kt`（底部加星级与色标条）、`data/ApiClient.kt`（若 `writeDecisions` 有签名问题则修）
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/DecisionStoreTest.kt`

**Interfaces:**
- Consumes: `ApiClient.writeDecisions(List<DecisionDto>)`
- Produces:
  - `data class Decision(val path: String, val rating: Int? = null, val label: String? = null)`
  - `class DecisionStore`：`fun get(path): Decision` / `fun applyLocal(d: Decision)` / `suspend fun push(paths: List<String>): PushResult`
  - `sealed class PushResult { object Ok; data class Failed(val path: String, val code: String) }`
  - `val LABELS = listOf("red", "yellow", "green", "blue", "purple")`（与桌面端 `labels.ts` 的五色一致）

- [ ] **Step 1: 先写测试（TDD）**

`DecisionStoreTest.kt`：

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.DecisionStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DecisionStoreTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun store() = DecisionStore(
        com.imagefilter.mobile.data.ApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "t".repeat(64),
            client = OkHttpClient(),
        )
    )

    @Test
    fun local_change_is_visible_immediately() {
        val s = store()
        s.applyLocal(Decision("D:\\p\\a.jpg", rating = 4))
        // 乐观更新: 不等网络, UI 立刻读到 4
        assertEquals(4, s.get("D:\\p\\a.jpg").rating)
    }

    @Test
    fun failed_push_keeps_the_local_value_but_reports_failure() = runTest {
        // 规格 §11 纪律 ③: "未同步 ≠ 已同步" —— 本地保留 + 明确报告, 而不是静默乐观
        server.enqueue(MockResponse().setResponseCode(423).setBody("""{"code":"busy"}"""))
        val s = store()
        s.applyLocal(Decision("D:\\p\\a.jpg", rating = 5))

        val r = s.push(listOf("D:\\p\\a.jpg"))
        assertTrue(r is DecisionStore.PushResult.Failed)
        assertEquals("busy", (r as DecisionStore.PushResult.Failed).code)
        assertEquals(5, s.get("D:\\p\\a.jpg").rating)   // 没有被回滚丢掉
    }

    @Test
    fun successful_push_sends_the_expected_payload() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"written":1,"skipped":0,"failed":0,"failures":[],"degraded":false}"""))
        val s = store()
        s.applyLocal(Decision("D:\\p\\a.jpg", rating = 3, label = "green"))
        s.push(listOf("D:\\p\\a.jpg"))

        val body = server.takeRequest().body.readUtf8()
        // 字段名必须与服务端 DecisionWrite 一致; clearLabel 必须显式发出 false
        assertTrue(body, body.contains("\"path\":\"D:\\\\p\\\\a.jpg\""))
        assertTrue(body, body.contains("\"rating\":3"))
        assertTrue(body, body.contains("\"label\":\"green\""))
        assertTrue(body, body.contains("\"clearLabel\":false"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon testDebugUnitTest --tests "*DecisionStoreTest*"
```

- [ ] **Step 3: 实现 DecisionStore**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiException
import com.imagefilter.mobile.data.DecisionDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 五色标签 —— 取值与桌面端 src/labels.ts 完全一致（服务端只认这五个字符串）。 */
val LABELS = listOf("red", "yellow", "green", "blue", "purple")

data class Decision(val path: String, val rating: Int? = null, val label: String? = null)

/**
 * 本地决策镜像 + 推送。
 *
 * 三条纪律（规格 §4.3 / §11）:
 *   1. **乐观更新**: 用户点星后 UI 立刻变 —— 网络往返不该让人等;
 *   2. **失败不回滚丢弃**: 保留本地值并明确报告失败(与规格的"保留本地改动 + 标未同步 +
 *      手动重试"一致)。静默回滚会让用户以为"白打了一次分", 而静默吞掉更糟;
 *   3. **同一路径的推送串行**(Mutex): 连点 5 次星不该产生 5 个乱序请求,
 *      否则服务端可能按乱序落盘。
 */
class DecisionStore(private val api: ApiClient) {
    private val mutex = Mutex()
    private val local = LinkedHashMap<String, Decision>()

    fun get(path: String): Decision = local[path] ?: Decision(path)

    fun applyLocal(d: Decision) { local[d.path] = d }

    fun all(): List<Decision> = local.values.toList()

    sealed class PushResult {
        object Ok : PushResult()
        data class Failed(val path: String, val code: String) : PushResult()
    }

    suspend fun push(paths: List<String>): PushResult = mutex.withLock {
        val items = paths.mapNotNull { local[it] }.map {
            DecisionDto(path = it.path, rating = it.rating, label = it.label, clearLabel = false)
        }
        if (items.isEmpty()) return PushResult.Ok
        return try {
            api.writeDecisions(items)
            PushResult.Ok
        } catch (e: ApiException) {
            PushResult.Failed(paths.first(), e.code)
        } catch (e: Exception) {
            PushResult.Failed(paths.first(), "network")
        }
    }
}
```

- [ ] **Step 4: 星级与色标 UI**

**要求**：

- 查看器底部一行：`0–5` 星（0 用 "✕" 表示"废弃"）；长按或点色标图标唤出五色面板
- **键位语义对齐桌面端**（README 快捷键表）：`X` = 废弃（0 星）、`J` = 保留（3 星）。这里是**语义**对齐，不是快捷键
- 每次改动：`applyLocal` → 立即 `push(listOf(path))` → 失败时显示一条可操作的提示：
  - `busy` → "电脑上正忙，稍后重试"
  - `notXmp` / `unsupportedForm` → "这个边车不是标准 XMP，电脑上无法安全合并"（**不要**假装成功）
  - `unauthorized` → "配对码已失效，去电脑上重新复制"
  - 网络类 → "没连上电脑（它可能睡了或 ImageFilter 没在运行）"
- **失败时不要自动前进**（M2′ 单指切图的场景下即"不要自动跳到下一张"），避免"以为打过了"

- [ ] **Step 5: 端到端验证（本计划的验收线）**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon installDebug
```

1. 电脑上打开 ImageFilter，进到某个照片文件夹（**保持这个文件夹打开**）
2. 手机上打开同一张照片 → 打 4 星
3. **电脑上那张照片的星级应在 1 秒内变成 4 星**（走 `decisions-changed` → `useScanner` 的 merge）
4. 电脑上给另一张打 2 星 → 手机上重新进该文件夹 → 显示 2 星（走 `read_decisions`）
5. 电脑上把服务关掉 → 手机打分 → 出现"没连上/服务未开"的可执行提示
6. 打开电脑上生成的 `.xmp` 看一眼：`xmp:Rating="4"` 在文件里（证明落盘，而不是只改了内存）

**第 3 条不成立的话不要继续**：那是本计划存在的理由。若事件没触发，先查桌面端 `emit` 是否真的发了（M0′ Task 8 Step 4），再查手机端是否需要轮询兜底——**但先报 BLOCKED**，不要自己改成轮询。

- [ ] **Step 6: 落证据并提交**

把第 5 步 6 项的实际结果写进 `docs/evidence/android-m2/REPORT.txt`。

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/DecisionStore.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/RatingBar.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/ViewerScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/DecisionStoreTest.kt `
        docs/evidence/android-m2/REPORT.txt
git commit -m "feat(android): 星级/色标写入(乐观更新 + 失败可见) + 端到端回写验证"
```

---

### Task 3: 筛选栏（星级 + 色标）与网格角标

**为什么现在做**：打完分不能筛，这个 App 就等于没做完一半——桌面端的核心体验就是"按星级筛"。

**Files:**
- Create: `ui/Filters.kt`、`ui/FilterBar.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/FiltersTest.kt`
- Modify: `ui/GridScreen.kt`（应用筛选 + 显示角标）

**Interfaces:**
- Produces:
  - `data class Filters(val minStars: Int = 0, val labels: Set<String> = emptySet(), val onlyUnsynced: Boolean = false)`
  - `fun applyFilters(photos: List<ScannedPhotoDto>, decisions: Map<String, Decision>, f: Filters): List<ScannedPhotoDto>`
  - `@Composable fun FilterBar(f: Filters, onChange: (Filters) -> Unit, unsyncedCount: Int)`

- [ ] **Step 1: 先写测试**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ScannedPhotoDto
import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.Filters
import com.imagefilter.mobile.ui.applyFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class FiltersTest {
    private fun p(n: String) = ScannedPhotoDto(path = n, fileName = n, modifiedAt = 0, exif = com.imagefilter.mobile.data.PhotoExifDto())

    private val photos = listOf(p("a.jpg"), p("b.jpg"), p("c.jpg"))
    private val decisions = mapOf(
        "a.jpg" to Decision("a.jpg", rating = 5, label = "green"),
        "b.jpg" to Decision("b.jpg", rating = 2, label = "red"),
        // c.jpg 没有任何决策 —— 这是关键边界
    )

    @Test
    fun no_filter_returns_everything() {
        assertEquals(3, applyFilters(photos, decisions, Filters()).size)
    }

    @Test
    fun min_stars_excludes_unrated_photos() {
        // **未评分的照片不算命中** —— 与桌面端语义一致(桌面端 docs 4.2 明确写过这一点),
        // 否则"筛 ≥3 星"会把没看过的照片全带进来, 筛选就没意义了。
        val r = applyFilters(photos, decisions, Filters(minStars = 3))
        assertEquals(listOf("a.jpg"), r.map { it.fileName })
    }

    @Test
    fun label_filter_is_an_or_union() {
        assertEquals(2, applyFilters(photos, decisions, Filters(labels = setOf("green", "red"))).size)
        assertEquals(1, applyFilters(photos, decisions, Filters(labels = setOf("red"))).size)
    }

    @Test
    fun star_and_label_filters_combine_with_and() {
        val r = applyFilters(photos, decisions, Filters(minStars = 3, labels = setOf("red")))
        assertEquals(0, r.size)   // b 是红标但只有 2 星 → 两个条件同时满足的没有
    }
}
```

- [ ] **Step 2: 实现筛选（纯函数）与筛选栏**

- 三个维度：**星级下限**、**色标（多选，或关系）**、**仅看未同步**
- 星级与色标之间是**与**关系；色标内部是**或**关系（两个测试钉住了这个语义）
- 筛选栏放在网格顶部，可折叠；显示"当前显示 N / 共 M 张"
- 卡片角标：星级（右下）与色标（左上圆点）；**未同步的项加一个细边框**（M3′ 会做成正式角标）

- [ ] **Step 3: 真机验证**

1. 给几张打不同星级 → 切"≥3 星"→ 网格只剩 3 星及以上的
2. 标几个色标 → 按色标筛 → 结果正确
3. 星级与色标同时启用 → 结果是**交集**
4. 筛选后进查看器 → 左右切图只在筛选结果内切换（**不能切到被筛掉的照片**——桌面端为此专门做过"列表收缩重锚"，交接文档 §4.3）

- [ ] **Step 4: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/Filters.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/FilterBar.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/GridScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/FiltersTest.kt `
        docs/evidence/android-m2/REPORT.txt
git commit -m "feat(android): 星级/色标筛选(未评分不算命中) + 网格角标"
```

---

## 附录 A · 规格覆盖对照（M2′ 部分）

| 规格条目 | 由哪个任务实现 |
|---|---|
| §4.3 写边车契约（手机只发 PUT，服务端写盘） | Task 2 |
| §5.2 `PUT /decisions` 的 `null = 不动该属性` 语义 | Task 2（`clearLabel=false` 显式发出） |
| §5.1 `/preview`（`max=2048`） | Task 1 |
| §8.1 筛选栏（星级 / 色标 / 待重试三维） | Task 3（第三维在 M3′） |
| §8.2 手势规则（决策态/查看态、双指进入查看态） | Task 1 |
| §8.2 写成功才自动前进、失败不前进 | Task 1（切图）+ Task 2（失败不前进） |
| §9 缓存由 Coil 提供 + `?v=<mtime>` | Task 1（预览也带 `v`） |
| §11 纪律 ③"未同步 ≠ 已同步" | Task 2（`PushResult.Failed` + 可执行提示） |
| §8.2 滑卡手势 / 左滑右滑与"不降级"规则 | **不在本计划** → M3′ |
| §5.2 `POST /move` + 半成功态 | **不在本计划** → M3′ |
| §8.3 待重试面板 / 撤销 | **不在本计划** → M3′ |

## 附录 B · 本计划刻意不做的事

| 不做 | 原因 |
|---|---|
| 滑卡手势（左滑/右滑决策） | M3′。M2′ 的查看器先把"查看态/决策态"这套状态机立起来，M3′ 只替换决策态里的手势处理 |
| 待重试队列的**持久化**与面板 | M3′。M2′ 只做到"失败可见"（内存态足够验证链路） |
| 撤销（反向写边车） | M3′（与滑卡一起做，那时误操作才有代价） |
| ~~`full=1` 高清预览~~ | **已提前到 Task 1**：M0′ Task 2 实测 8/8 张内嵌预览都 < 2048 → 默认档验不了对焦，它从"打磨项"变成"查看器可用的前提" |
| 事件推送（SSE `/events`） | M2′ 靠 `decisions-changed` 的**桌面端→电脑界面**通道；手机端刷新靠"重新进文件夹"。等 M3′ 真机跑过再决定要不要给手机也加推送 |
| 给手机端做离线写队列 | 规格决策 #3/#12 明确：离线只可看不可改；写失败进待重试，不自动补写 |
