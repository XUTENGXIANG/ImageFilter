# ImageFilter Mobile · M3′ 滑卡 / 撤销 / 移动分类 / 待重试 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"快速筛片"做成主要交互（滑卡），补上误操作的退路（撤销）、把入选片就地分类（移动），并让写失败的东西有个去处（待重试面板）。

**Architecture:** 全部建立在 M2′ 的 `DecisionStore` 之上。滑卡的**语义决策抽成纯函数**（`decideSwipe`），手势只负责把手势翻译成 `SwipeAction`——这样"右滑不降级"这类规矩能在 JVM 单测里钉死，而不必靠真机反复试。

**Tech Stack:** 同 M1′/M2′。**不新增任何依赖。**

## Global Constraints

沿用 M1′/M2′ 的全部约束（中文注释 + SPDX 头 + conventional commits + 只 `git add` 明确路径 + 不引 Room + 不新增依赖 + 服务端不许改）。

**本计划额外两条**：

- **滑卡的写入语义必须与桌面端 `J`/`X` 对齐**（README 快捷键表：`J` = 保留（3 星）、`X` = 废弃（0 星）），共用同一个 `xmp:Rating` 字段——这样桌面端的"≥N★ 筛选"能直接看到你在手机上的取舍。
- **"保留"绝不降级**：当前已有 ≥1 星时右滑不改星级。无条件写 3 会把桌面端已给的 4/5 星**静默降级**，属于破坏用户既有工作的数据损失。

> ⚠️ 本计划的**手势细节**（滑卡跟手度、回弹、与缩放的冲突）可能因 M2′ 的真机手感而调整。验收标准是"§8.2 的手势规则成立 + 滑卡语义正确"，不是照抄某个动画参数。

---

### Task 1: 滑卡决策栈（语义纯函数 + 手势 + 预取）

**Files:**
- Create: `android/app/src/main/kotlin/com/imagefilter/mobile/ui/Swipe.kt`、`ui/SwipeScreen.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/SwipeTest.kt`
- Modify: `ui/ViewerScreen.kt` 或新增独立的筛片路由（见 Step 4 说明）

**Interfaces:**
- Produces:
  - `enum class SwipeAction { Reject, Keep }`
  - `enum class SwipeMode { StarsOnly, LabelsOnly, Both }`
  - `data class SwipeOutcome(val rating: Int?, val label: String?, val unchanged: Boolean, val message: String?)`
  - `fun decideSwipe(current: Decision, action: SwipeAction, mode: SwipeMode): SwipeOutcome`

- [ ] **Step 1: 先写测试（这是本计划最重要的一段测试）**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.SwipeAction
import com.imagefilter.mobile.ui.SwipeMode
import com.imagefilter.mobile.ui.decideSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeTest {
    private val p = "D:\\照片\\a.jpg"

    @Test
    fun keep_on_unrated_photo_sets_three_stars() {
        val o = decideSwipe(Decision(p), SwipeAction.Keep, SwipeMode.StarsOnly)
        assertEquals(3, o.rating)
        assertFalse(o.unchanged)
    }

    @Test
    fun keep_never_downgrades_an_existing_rating() {
        // 这是本设计里最容易写错、后果最严重的一条:
        // 无条件写 3 会把桌面端给的 5 星静默降成 3 星。
        val o = decideSwipe(Decision(p, rating = 5), SwipeAction.Keep, SwipeMode.StarsOnly)
        assertEquals(5, o.rating)
        assertTrue("必须告诉用户这次滑动没有改星级", o.unchanged)
        assertTrue("且要说清保持了几星", o.message!!.contains("5"))
    }

    @Test
    fun keep_upgrades_a_previously_rejected_photo() {
        // 0 星 = 废弃。用户改主意右滑, 语义上应该变回"保留"而不是继续 0 星。
        assertEquals(3, decideSwipe(Decision(p, rating = 0), SwipeAction.Keep, SwipeMode.StarsOnly).rating)
    }

    @Test
    fun reject_sets_zero_stars_even_when_it_was_five() {
        // 左滑是**用户显式动作**, 允许降级 —— 这与"保留不降级"不矛盾:
        // 前者是明确说"我不要这张", 后者是含糊的"留着"。
        assertEquals(0, decideSwipe(Decision(p, rating = 5), SwipeAction.Reject, SwipeMode.StarsOnly).rating)
    }

    @Test
    fun labels_only_mode_never_touches_stars() {
        val keep = decideSwipe(Decision(p, rating = 4), SwipeAction.Keep, SwipeMode.LabelsOnly)
        assertEquals(4, keep.rating)
        assertNull("只写色标时不该动星级", keep.label?.let { null })  // 保留星级
        assertEquals("green", keep.label)

        val reject = decideSwipe(Decision(p, rating = 4), SwipeAction.Reject, SwipeMode.LabelsOnly)
        assertEquals(4, reject.rating)
        assertEquals("red", reject.label)
    }

    @Test
    fun both_mode_writes_both() {
        val reject = decideSwipe(Decision(p), SwipeAction.Reject, SwipeMode.Both)
        assertEquals(0, reject.rating)
        assertEquals("red", reject.label)

        val keep = decideSwipe(Decision(p, rating = 5), SwipeAction.Keep, SwipeMode.Both)
        assertEquals(5, keep.rating)          // 仍然不降级
        assertEquals("green", keep.label)     // 但色标照写
    }

    @Test
    fun stars_only_mode_never_touches_labels() {
        // 滑卡不该顺手覆盖用户精心标的色标
        assertNull(decideSwipe(Decision(p, label = "purple"), SwipeAction.Keep, SwipeMode.StarsOnly).label)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cd A:\tenent\android
.\gradlew.bat --no-daemon testDebugUnitTest --tests "*SwipeTest*"
```

- [ ] **Step 3: 实现 `Swipe.kt`**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

enum class SwipeAction { Reject, Keep }
enum class SwipeMode { StarsOnly, LabelsOnly, Both }

/**
 * 一次滑卡的结果。
 * `unchanged = true` 表示**没有产生任何写入** —— 调用方据此跳过网络请求,
 * 但仍要自动前进并显示 [message]（否则用户会以为这次滑动没生效）。
 */
data class SwipeOutcome(
    val rating: Int?,
    val label: String?,
    val unchanged: Boolean,
    val message: String? = null,
)

/**
 * 滑卡语义 —— 与桌面端 `J`/`X` 对齐（README 快捷键表: J=保留(3星), X=废弃(0星)）。
 *
 * 三条规矩, 每条都有测试盯着:
 *   1. 左滑(废弃) = 0 星, **允许降级** —— 那是用户明确说"不要这张";
 *   2. 右滑(保留) = 无星级时给 3 星, **已有 ≥1 星时不动** —— 含糊的"留着"不该
 *      把桌面端给的 5 星降成 3 星;
 *   3. 什么模式写什么字段 —— `StarsOnly` 绝不碰色标(滑卡不该覆盖用户精心标的色标),
 *      `LabelsOnly` 绝不碰星级。
 */
fun decideSwipe(current: Decision, action: SwipeAction, mode: SwipeMode): SwipeOutcome {
    val writeStars = mode != SwipeMode.LabelsOnly
    val writeLabel = mode != SwipeMode.StarsOnly

    val label = if (writeLabel) {
        if (action == SwipeAction.Keep) "green" else "red"
    } else null

    if (!writeStars) {
        return SwipeOutcome(rating = current.rating, label = label, unchanged = false)
    }

    return when (action) {
        SwipeAction.Reject -> SwipeOutcome(rating = 0, label = label, unchanged = false)
        SwipeAction.Keep -> {
            val r = current.rating
            if (r == null || r == 0) {
                SwipeOutcome(rating = 3, label = label, unchanged = false)
            } else {
                SwipeOutcome(
                    rating = r,
                    label = label,
                    unchanged = true,
                    message = "已保留 · 保持 $r★",
                )
            }
        }
    }
}
```

> 注意 `StarsOnly` 模式下 `label` 必为 `null`，而 `DecisionStore` 把 `label = null` 解释为"不动这个属性"（服务端 `DecisionWrite` 的语义）—— 这正是我们想要的。**不要把 null 当成"清空"**。

- [ ] **Step 4: 滑卡界面**

**要求**：

- 卡片栈：当前张 + **后 2 张预取**（`previewUrl(max = 2048)`，规格 §6 明确要求滑卡的手感取决于这个）
- 手势：水平拖动 → 超过阈值（约屏宽 25%）松手即决策；未过阈值回弹
- **与缩放的冲突按 §8.2 写死**：默认态单指 = 滑卡；双指缩放进入查看态，此时单指 = 平移，滑卡禁用；缩回 1x 恢复
- 决策后：`decideSwipe` → 若 `unchanged` 则**不发请求**，只自动前进并弹一句 `message`；否则 `applyLocal` + `push`
- 底部保留 1–5 星按钮与色标面板（滑卡做三态取舍，星级做细分）
- 每次决策前先把**当前 decision** 塞进撤销栈（Task 3 用）

- [ ] **Step 5: 真机验证**

1. 连滑 50 张：无明显掉帧；下一张已经就绪（不出现"空白等一下才出图"）
2. 拿一张桌面端已给 5 星的照片右滑 → **星级仍是 5**，且出现"已保留 · 保持 5★"
3. 同一张左滑 → 变 0 星（明确降级）
4. 设为"只写色标"→ 滑卡后星级不变、色标变
5. 双指放大后再单指拖动 → **平移而不是滑卡**；缩回 1x → 又能滑卡

- [ ] **Step 6: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/Swipe.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/SwipeScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/SwipeTest.kt
git commit -m "feat(android): 滑卡决策(语义纯函数: 保留不降级/不越权改色标) + 卡片栈"
```

---

### Task 2: 撤销（反向写边车）

**为什么必须有**：滑卡是**高速连续操作**，误操作的代价比星级按钮大得多。桌面端为此做了 `Ctrl+Z`/`Ctrl+Shift+Z` 与补丁栈（`undo.ts`），手机端做多步撤销即可。

**Files:**
- Create: `ui/UndoStack.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/UndoStackTest.kt`
- Modify: `ui/SwipeScreen.kt`（右上角撤销按钮 + 显示可撤销步数）

**Interfaces:**
- Produces:
  - `data class UndoEntry(val path: String, val before: Decision, val after: Decision)`
  - `class UndoStack(max: Int = 50)`：`fun push(e: UndoEntry)` / `fun pop(): UndoEntry?` / `val size: Int` / `fun clear()`

- [ ] **Step 1: 先写测试**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.UndoEntry
import com.imagefilter.mobile.ui.UndoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    @Test
    fun pops_in_reverse_order() {
        val s = UndoStack()
        s.push(UndoEntry("a", Decision("a", 1), Decision("a", 0)))
        s.push(UndoEntry("b", Decision("b", 5), Decision("b", 0)))
        assertEquals("b", s.pop()!!.path)
        assertEquals("a", s.pop()!!.path)
        assertNull(s.pop())
    }

    @Test
    fun is_bounded_so_a_long_session_cannot_grow_forever() {
        // 滑 2000 张不能把内存吃掉; 丢掉的是最早的那条, 不是最新的 ——
        // 用户按撤销时想撤回的一定是"刚刚那一下"。
        val s = UndoStack(max = 3)
        repeat(5) { s.push(UndoEntry("p$it", Decision("p$it", 1), Decision("p$it", 0))) }
        assertEquals(3, s.size)
        assertEquals("p4", s.pop()!!.path)
        assertEquals("p3", s.pop()!!.path)
        assertEquals("p2", s.pop()!!.path)
        assertNull(s.pop())
    }

    @Test
    fun clear_empties_it() {
        val s = UndoStack()
        s.push(UndoEntry("a", Decision("a", 1), Decision("a", 0)))
        s.clear()
        assertTrue(s.size == 0)
        assertNull(s.pop())
    }
}
```

- [ ] **Step 2: 实现并接线**

- 栈只存内存（改动本身要求在线，跨会话没有意义——这一点写进 KDoc）
- 撤销 = 把 `before` 写回：`applyLocal(before)` → `push(listOf(path))`
- 撤销按钮显示可撤销步数；为 0 时禁用
- **撤销本身也要可撤销吗？** 不做。撤销再撤销等于重做，而滑卡的场景里"再滑一次"更快。KDoc 里写明这个取舍。

- [ ] **Step 3: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/UndoStack.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/SwipeScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/UndoStackTest.kt
git commit -m "feat(android): 滑卡撤销(内存栈, 上限 50 步)"
```

---

### Task 3: 待重试队列（持久化）+ 重试面板

**为什么要持久化**：写失败的东西如果只存在内存里，用户切后台/重启就丢了——而它代表的是"用户已经做出的决定"，丢了就是数据损失。

**Files:**
- Create: `ui/PendingStore.kt`、`ui/PendingSheet.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/PendingStoreTest.kt`
- Modify: `data/ApiClient.kt`（无改动，复用 `writeDecisions`）、`ui/GridScreen.kt`（顶栏角标）、`ui/SwipeScreen.kt`

**Interfaces:**
- Produces:
  - `class PendingStore(context: Context)`：`suspend fun all(): Map<String, Decision>` / `suspend fun add(d: Decision)` / `suspend fun remove(path: String)` / `suspend fun clear()`
  - `@Composable fun PendingSheet(items: List<Decision>, onRetry: (List<String>) -> Unit, onRetryAll: () -> Unit, onDrop: (String) -> Unit)`
  - 纯函数 `fun mergePending(prev: Map<String, Decision>, d: Decision): Map<String, Decision>`（同路径以最新为准）

- [ ] **Step 1: 先写测试（纯逻辑部分用 JVM 测；DataStore 的读写靠真机手测）**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.ui.Decision
import com.imagefilter.mobile.ui.mergePending
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingStoreTest {
    @Test
    fun same_path_keeps_the_latest_intent() {
        // 同一张照片改了三次、最后一次失败: 待重试里应该只有最后一次的意图。
        // 保留三条会让"重试全部"发出三个互相覆盖的请求。
        var m = mergePending(emptyMap(), Decision("a", rating = 3))
        m = mergePending(m, Decision("a", rating = 0))
        m = mergePending(m, Decision("a", rating = 5, label = "green"))
        assertEquals(1, m.size)
        assertEquals(5, m["a"]!!.rating)
        assertEquals("green", m["a"]!!.label)
    }

    @Test
    fun different_paths_are_independent() {
        var m = mergePending(emptyMap(), Decision("a", rating = 3))
        m = mergePending(m, Decision("b", rating = 0))
        assertEquals(2, m.size)
    }

    @Test
    fun pending_survives_json_roundtrip() {
        // 持久化格式必须能往返 —— 重启后待重试不该变成空
        val m = mapOf("a" to Decision("a", rating = 4, label = "blue"))
        val text = com.imagefilter.mobile.data.Json.encodeToString(m)
        val back = com.imagefilter.mobile.data.Json.decodeFromString<Map<String, Decision>>(text)
        assertEquals(m, back)
    }
}
```

> 要让 `Decision` 可序列化，给它加 `@Serializable`（`data class Decision(@Serializable 字段)`）——加注解会让上面第三个测试通过。

- [ ] **Step 2: 实现 PendingStore（DataStore 存 JSON）**

- 存成 DataStore 里的一个字符串键（JSON），**不引 Room**（规格决策 #8）
- 写入时机：任何一次 `push` 返回 `Failed` → `add(d)`；`push` 成功 → `remove(path)`
- 角标：顶栏显示待重试数量（0 时隐藏）

- [ ] **Step 3: 重试面板**

**要求**：
- 列出：缩略图 + 目标值（星级/色标）+ **失败原因的可执行文案**（复用 M2′ 的 `ApiException.code` → 文案映射，**不要写第二份映射**）
- 三个动作：单项重试 / 全部重试 / 放弃本地改动（放弃前二次确认，因为它丢掉的是用户的决定）
- 全部重试要**串行**并以最后一次结果刷新列表（失败的仍在列表里）

- [ ] **Step 4: 真机验证**

1. 关掉电脑上的服务 → 手机打 3 张分 → 三张都进待重试，角标显示 3
2. **杀掉 App 再打开** → 待重试仍是 3（验证持久化）
3. 开启服务 → "全部重试" → 全部成功，角标消失；电脑上能看到这三张的星级
4. 断网状态下点"全部重试" → 仍是失败，列表不变（不产生"假成功"）

- [ ] **Step 5: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/ui/PendingStore.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/PendingSheet.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/GridScreen.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/SwipeScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/PendingStoreTest.kt
git commit -m "feat(android): 待重试队列(DataStore 持久化) + 重试面板与角标"
```

---

### Task 4: 移动分类（`POST /move`）

**三条纪律**（规格 §5.2，服务端已实现，手机端负责**如实展示**）：绝不覆盖；先照片后边车；半成功必须可见。

**Files:**
- Modify: `data/Dto.kt`（加 `MoveReqDto` / `MoveSummaryDto` / `MoveOutcomeDto`）、`data/ApiClient.kt`（加 `move()`）
- Create: `ui/MoveSheet.kt`
- Test: `android/app/src/test/kotlin/com/imagefilter/mobile/MoveTest.kt`
- Modify: `ui/GridScreen.kt`（多选 + 移动入口）

**Interfaces:**
- Produces:
  - `@Serializable data class MoveReqDto(val paths: List<String>, val destDir: String)`
  - `@Serializable data class MoveOutcomeDto(val src: String, val dest: String? = null, val status: String, val detail: String? = null)`
  - `@Serializable data class MoveSummaryDto(val moved: Int, val halfSuccess: Int, val conflict: Int, val failed: Int, val items: List<MoveOutcomeDto>)`
  - `suspend fun ApiClient.move(paths: List<String>, destDir: String): MoveSummaryDto`
  - `fun summarizeMove(s: MoveSummaryDto): String`（纯函数，可测）

- [ ] **Step 1: 先写测试**

```kotlin
// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.MoveOutcomeDto
import com.imagefilter.mobile.data.MoveSummaryDto
import com.imagefilter.mobile.ui.summarizeMove
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTest {
    @Test
    fun half_success_is_never_hidden_behind_a_success_message() {
        // "照片动了但边车没动" = 那张照片的评分与新路径脱钩了。用"移动成功"概括它
        // 会让用户永远不知道自己的评分丢了 —— 这是本设计明令不许的静默失败。
        val s = MoveSummaryDto(
            moved = 3, halfSuccess = 1, conflict = 0, failed = 0,
            items = listOf(MoveOutcomeDto("D:\\p\\a.jpg", "D:\\p\\精修\\a.jpg", "halfSuccess", "边车 a.xmp 没能跟着走")),
        )
        val text = summarizeMove(s)
        assertTrue(text, text.contains("成功 3"))
        assertTrue(text, text.contains("1"))
        assertTrue(text, text.contains("边车") || text.contains("需重试"))
    }

    @Test
    fun conflict_message_says_nothing_was_overwritten() {
        val s = MoveSummaryDto(
            moved = 0, halfSuccess = 0, conflict = 1, failed = 0,
            items = listOf(MoveOutcomeDto("a.jpg", "d\\a.jpg", "conflict", "目标位置已有同名文件, 未覆盖")),
        )
        val text = summarizeMove(s)
        assertTrue(text, text.contains("未覆盖"))
    }
}
```

- [ ] **Step 2: 实现 `move()` 与多选 UI**

- 多选：长按进入选择模式，点选切换；顶部显示已选 N 张
- 目标文件夹：从**共享根内**的目录树里挑（复用 M1′ 的 `browse`），支持新建文件夹（`Mkdir` 需要服务端支持——**若服务端没有该端点，本步只支持选已有文件夹并报告为已知限制**）
- 结果面板：按 `status` 分组显示 `moved / halfSuccess / conflict / failed`，半成功与冲突各自可操作（重试 / 改名后再试）

- [ ] **Step 3: 真机验证**

1. 选 3 张移到子文件夹 → 成功；**电脑上确认照片与 `.xmp` 都到了新位置**
2. 目标位置先手工放一个同名文件 → 移动报冲突，且原文件未被覆盖（电脑上确认）
3. 目标位置先手工建一个与边车同名的**目录** → 报半成功，且照片在新位置、边车在旧位置（这是刻意造出的失败态，用来验证它真的可见）
4. 移动后在电脑上打开新文件夹 → 评分跟着边车回来了（不是"评分丢了"）

- [ ] **Step 4: Commit**

```powershell
cd A:\tenent
git add android/app/src/main/kotlin/com/imagefilter/mobile/data/Dto.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/data/ApiClient.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/MoveSheet.kt `
        android/app/src/main/kotlin/com/imagefilter/mobile/ui/GridScreen.kt `
        android/app/src/test/kotlin/com/imagefilter/mobile/MoveTest.kt
git commit -m "feat(android): 移动分类(冲突/半成功如实可见) + 多选"
```

---

## 附录 A · 规格覆盖对照（M3′ 部分）

| 规格条目 | 由哪个任务实现 |
|---|---|
| §8.2 滑卡默认映射 + "不降级"规则 | Task 1 |
| §8.2 滑卡可配置（三档模式） | Task 1（`SwipeMode`；设置入口在 M4′ 的偏好页） |
| §8.2 写成功才前进、失败不前进 | Task 1（`unchanged` 分支不发请求；失败分支不前进） |
| §8.2 撤销（多步，内存栈上限 50） | Task 2 |
| §6 滑卡预取"当前 + 后 2 张" | Task 1 |
| §5.1 / §5.2 `POST /move` 与三条纪律 | Task 4 |
| §8.3 待重试面板（单项/全部重试/放弃） | Task 3 |
| §11 纪律 ②"半成功态显式化" | Task 4（`summarizeMove` 的测试钉住） |
| §11 纪律 ③"未同步 ≠ 已同步" | Task 3 |
| §8.1 筛选栏第三维"待重试" | Task 3（角标） |
| 只读模式 / 二维码 / 打通引导 | **不在本计划** → M4′ |

## 附录 B · 本计划刻意不做的事

| 不做 | 原因 |
|---|---|
| 重做（redo） | 滑卡场景里"再滑一次"比"撤销再重做"快；桌面端有 redo 是因为键盘操作成本不同 |
| 手机端新建文件夹 | 依赖服务端新增端点；若 M0′ 没做，就只支持选已有文件夹并如实报告限制 |
| 手机端删除文件 | 规格非目标：WebDAV/服务端删除不可逆，且会与 `import_history` 失对账 |
| 滑卡动画参数的精细调校 | 手感要在真机上试出来，不是计划里定死的数字；验收标准是手势规则成立 |
| 批量移动时的并发优化 | 服务端是单主人，串行足够；过早并发只会让半成功态更难解释 |
