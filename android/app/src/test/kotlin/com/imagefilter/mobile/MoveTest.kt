// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import com.imagefilter.mobile.data.ApiClient
import com.imagefilter.mobile.data.ApiErrorCodes
import com.imagefilter.mobile.data.FailureSource
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Json
import com.imagefilter.mobile.data.MoveStatus
import com.imagefilter.mobile.data.MoveSummaryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.WriteFailure
import com.imagefilter.mobile.data.WriteMessages
import com.imagefilter.mobile.data.buildHttpClient
import com.imagefilter.mobile.ui.BrowseViewModel
import com.imagefilter.mobile.ui.MoveStage
import com.imagefilter.mobile.ui.MoveViewModel
import com.imagefilter.mobile.ui.folderPickerModel
import com.imagefilter.mobile.ui.moveRetryPlan
import com.imagefilter.mobile.ui.summarizeMove
import com.imagefilter.mobile.ui.tallyMove
import com.imagefilter.mobile.ui.toMoveRows
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 移动分类(M3′ Task 4)的**真行为**测试: JVM + MockWebServer, 走真的 HTTP + 真的 [ApiClient]。
 *
 * 这份文件专门盯住那些"看起来成了、其实没成"的写法 —— 本项目已经三次栽在
 * "测试为错误的理由变绿"上(一个循环只对着兜底句断言; 一个夹具让真正有意思的那一档走不到;
 * 断言只覆盖对称两支里的一支), 所以这里每一处都成对地钉:
 *
 *   ① **请求体形状**(paths 逐字按网格顺序 + destDir) —— 同时钉住"点选顺序不等于网格顺序";
 *   ② **五字段解析**, 含 `pendingSidecars: []`(**不是 null**)那一档;
 *   ③ **半成功项的重试只带 `pendingSidecars`**, 而且**绝不**带已经搬走的 `src`
 *      (带上它整批 404 —— 规格 §5.2 的实测定型);
 *   ④ **「全部重试」只重发仍然待补的** —— 成功的那些一个字节都不重发; 对称的一支(全成功时
 *      一个请求都不发)也钉住, 免得"永远把整批发一遍"的实现蒙混过关;
 *   ⑤ **冲突不覆盖**, 而且冲突项不提供重试(重试同一个目标只会再冲突一次);
 *   ⑥ **只能移动到已有文件夹**这条限制是**如实说出来**的, 而且选择器里长不出「新建文件夹」
 *      这种服务端根本不存在的动作(M0′ 没有 Mkdir 端点);
 *   ⑦ **批次级失败的文案复用既有表**(移动是写操作: 查 [WriteMessages]; 传输/鉴权查
 *      [PairingMessages])—— 不另写第三份映射, 这里用**逐字相等**钉住复用。
 */
class MoveTest {

    private lateinit var server: MockWebServer
    private val token = "m".repeat(64)

    private val photoA = "D:\\照片\\a.jpg"
    private val photoB = "D:\\照片\\b.jpg"
    private val destDir = "D:\\照片\\精修"
    private val sidecarA = "D:\\照片\\a.xmp"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * 把一条 Windows 路径写成**JSON 文本里**的样子。
     *
     * ⚠️ 少了这一步, 夹具自己就是坏的: `"D:\照片\a.jpg"` 里的 `\照` 是一个非法转义,
     * 于是"路径里有中文与反斜杠"这一档**根本走不到解析**(kotlinx 直接抛)——
     * 那正是本项目栽过的"夹具让真正有意思的那一档走不到"。
     */
    private fun j(path: String): String = path.replace("\\", "\\\\")

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun pairing() = Pairing(url = baseUrl(), token = token)

    /** 注入 client 一律以 [buildHttpClient] 为底 —— 鉴权头只装在那里(与其它契约测试同一条纪律)。 */
    private fun vm() = MoveViewModel(
        pairing = pairing(),
        clientFactory = { url, tok -> ApiClient(url, tok, buildHttpClient(tok)) },
    )

    /** 一个能直接塞进 MockWebServer 的移动结果体。 */
    private fun moveBody(
        moved: Int = 0,
        halfSuccess: Int = 0,
        conflict: Int = 0,
        failed: Int = 0,
        items: String,
    ) = """{"moved":$moved,"halfSuccess":$halfSuccess,"conflict":$conflict,"failed":$failed,"items":[$items]}"""

    /** 一张照片的逐项结果(逐字用服务端的五字段形状)。 */
    private fun item(
        src: String,
        dest: String?,
        status: String,
        detail: String? = null,
        pending: List<String> = emptyList(),
    ): String {
        val destJson = dest?.let { "\"${j(it)}\"" } ?: "null"
        val detailJson = detail?.let { "\"$it\"" } ?: "null"
        val pendingJson = pending.joinToString(",") { "\"${j(it)}\"" }
        return """{"src":"${j(src)}","dest":$destJson,"status":"$status","detail":$detailJson,"pendingSidecars":[$pendingJson]}"""
    }

    private suspend fun selectAndConfirm(vm: MoveViewModel, vararg photos: String) {
        vm.startSelecting()
        for (p in photos) vm.toggle(p)
        vm.openPicker()
        vm.pickFolder(FolderEntryDto(path = destDir, name = "精修"))
    }

    // ── ① 请求体形状 ─────────────────────────────────────────────────────────

    @Test
    fun move_posts_the_paths_in_grid_order_with_the_picked_dest_dir() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    moved = 2,
                    items = listOf(
                        item(photoA, "$destDir\\a.jpg", "moved"),
                        item(photoB, "$destDir\\b.jpg", "moved"),
                    ).joinToString(","),
                ),
            ),
        )

        val vm = vm()
        // 点选顺序刻意与网格顺序**相反**: 请求里的 paths 必须按网格顺序(与电脑上看到的顺序一致)
        selectAndConfirm(vm, photoB, photoA)
        vm.move(order = listOf(photoA, photoB))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/move", req.requestUrl!!.encodedPath)
        assertEquals(
            """{"paths":["D:\\照片\\a.jpg","D:\\照片\\b.jpg"],"destDir":"D:\\照片\\精修"}""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun a_move_with_nothing_selected_sends_no_request_at_all() = runTest {
        val vm = vm()
        vm.startSelecting()
        vm.openPicker()
        vm.pickFolder(FolderEntryDto(path = destDir, name = "精修"))

        assertNull(
            "一张都没选: 本地就不该发请求(服务端对空批次报 badRequest)",
            vm.move(order = listOf(photoA)),
        )
        assertEquals(0, server.requestCount)
    }

    // ── ② 五字段解析(含空的 pendingSidecars 数组) ───────────────────────────

    @Test
    fun a_moved_item_carries_an_empty_pending_sidecars_array_and_offers_no_retry() = runTest {
        val json = """{"src":"${j(photoA)}","dest":"${j("$destDir\\a.jpg")}","status":"moved","detail":null,"pendingSidecars":[]}"""
        // 夹具先自己钉一遍: 服务端发的是**恰好五个**字段(多一个少一个都是契约漂移)
        assertEquals(
            setOf("src", "dest", "status", "detail", "pendingSidecars"),
            Json.parseToJsonElement(json).jsonObject.keys,
        )
        // 而且这一条路径真的能解析出反斜杠与中文(夹具没把有意思的那一档挡在门外)
        assertEquals(photoA, Json.decodeFromString<com.imagefilter.mobile.data.MoveOutcomeDto>(json).src)

        val summary = Json.decodeFromString<MoveSummaryDto>(moveBody(moved = 1, items = json))
        val row = toMoveRows(summary.items).single()

        assertEquals(MoveStatus.MOVED, row.status)
        assertTrue("空数组不是 null: ${row.pendingSidecars}", row.pendingSidecars.isEmpty())
        assertNull("没有待补边车就没有可重试的东西", row.retryRequest)
        assertEquals(photoA, row.src)
        assertEquals("a.jpg", row.fileName)
        assertEquals("$destDir\\a.jpg", row.dest)
    }

    // ── ③ 半成功的重试通道 ───────────────────────────────────────────────────

    @Test
    fun a_half_success_retries_with_pending_sidecars_alone_and_never_with_the_dead_src() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    halfSuccess = 1,
                    items = item(
                        photoA,
                        "$destDir\\a.jpg",
                        "halfSuccess",
                        "照片已移动, 但边车 a.xmp 没能跟着走, 需要重试",
                        listOf(sidecarA),
                    ),
                ),
            ),
        )

        val vm = vm()
        selectAndConfirm(vm, photoA)
        vm.move(order = listOf(photoA))

        val row = vm.state.value.rows.single()
        assertEquals(MoveStatus.HALF_SUCCESS, row.status)
        val retry = row.retryRequest
        assertNotNull("半成功项必须能重试", retry)
        // 规格 §5.2: paths **逐字**是服务端给的 pendingSidecars(手机不推导边车命名规则)
        assertEquals(listOf(sidecarA), retry!!.paths)
        assertEquals("""{"paths":["D:\\照片\\a.xmp"],"destDir":"D:\\照片\\精修"}""", Json.encodeToString(retry))
        assertFalse("半成功项的照片已经搬走了: 再发它会整批 404", retry.paths.contains(photoA))
    }

    // ── ④ 「全部重试」只重发仍然待补的 ───────────────────────────────────────

    @Test
    fun retry_all_only_re_sends_the_pending_sidecars_and_never_the_dead_or_moved_paths() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    moved = 1,
                    halfSuccess = 1,
                    items = listOf(
                        item(photoB, "$destDir\\b.jpg", "moved"),
                        item(photoA, "$destDir\\a.jpg", "halfSuccess", "边车没能跟着走", listOf(sidecarA)),
                    ).joinToString(","),
                ),
            ),
        )
        // 重试那一批的回应: 边车这次搬过去了(它的 src 是**边车**路径, 不是照片)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(moved = 1, items = item(sidecarA, "$destDir\\a.xmp", "moved")),
            ),
        )

        val vm = vm()
        selectAndConfirm(vm, photoA, photoB)
        vm.move(order = listOf(photoA, photoB))
        server.takeRequest() // 第一批(移动)

        val report = vm.retryAll()

        assertEquals("只有那个半成功项还有待补的边车: 别的都是零重试", 1, report.attempted)
        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"paths":["D:\\照片\\a.xmp"],"destDir":"D:\\照片\\精修"}""", body)
        assertFalse("已经搬走的照片 src 一个都不许出现在重试里: $body", body.contains(j(photoA)))
        assertFalse("已经成功的项一个都不许重发: $body", body.contains(j(photoB)))
        assertEquals("总共两个请求: 一次移动 + 一次边车重试", 2, server.requestCount)

        // 边车跟上了 ⇒ 这一行不再是"半成功"(照片与边车都在目标目录里)
        val row = vm.state.value.rows.first { it.src == photoA }
        assertTrue("重试之后没有待补边车了: ${row.pendingSidecars}", row.pendingSidecars.isEmpty())
        assertNull(row.retryRequest)
        assertEquals(MoveStatus.MOVED, row.displayStatus)
        assertEquals(MoveStatus.MOVED, vm.state.value.rows.first { it.src == photoB }.displayStatus)
    }

    @Test
    fun a_retry_that_conflicts_is_never_reported_as_caught_up() = runTest {
        // 边车重试撞上同名文件: 服务端的回应里**同样没有** pendingSidecars
        // (不变量: moved/conflict/failed ⇒ []), 所以一个"没有待补边车就算成功"的实现
        // 会把这次失败说成"边车跟上了" —— 那是一次把失败说成成功的静默, 正是本任务最不能出的错。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    halfSuccess = 1,
                    items = item(photoA, "$destDir\\a.jpg", "halfSuccess", "边车没能跟着走", listOf(sidecarA)),
                ),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    conflict = 1,
                    items = item(sidecarA, "$destDir\\a.xmp", "conflict", "目标位置已有同名文件, 未覆盖"),
                ),
            ),
        )

        val vm = vm()
        selectAndConfirm(vm, photoA)
        vm.move(order = listOf(photoA))
        server.takeRequest()

        val report = vm.retryAll()

        assertEquals(1, report.attempted)
        assertTrue("冲突不是「跟上了」: $report", report.caughtUp.isEmpty())
        assertEquals(listOf(photoA), report.stillOff)
        val row = vm.state.value.rows.single()
        assertEquals("照片仍然与它的边车脱钩", MoveStatus.HALF_SUCCESS, row.displayStatus)
        assertTrue("边车还在原地: ${row.pendingSidecars}", row.pendingSidecars.isNotEmpty())
        assertNotNull("用户去电脑上把那个同名文件改名之后还能再试一次", row.retryRequest)
        val note = vm.state.value.note
        assertNotNull(note)
        assertTrue("必须说清仍然脱钩: $note", note!!.contains("仍然脱钩"))
    }

    @Test
    fun retry_all_on_a_fully_successful_move_sends_nothing_at_all() = runTest {
        // 对称的那一支: 全成功时「全部重试」必须是**零请求** —— 只钉半成功那一支的话,
        // 一个"永远把整批发一遍"的实现会照样绿。
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    moved = 2,
                    items = listOf(
                        item(photoA, "$destDir\\a.jpg", "moved"),
                        item(photoB, "$destDir\\b.jpg", "moved"),
                    ).joinToString(","),
                ),
            ),
        )

        val vm = vm()
        selectAndConfirm(vm, photoA, photoB)
        vm.move(order = listOf(photoA, photoB))
        server.takeRequest()

        val report = vm.retryAll()

        assertEquals(0, report.attempted)
        assertEquals("全成功时一个请求都不许发", 1, server.requestCount)
        val note = vm.state.value.note
        assertNotNull(note)
        assertTrue("必须说清没什么可重试的, 实际=$note", note!!.contains("没有"))
    }

    @Test
    fun retrying_a_row_that_has_no_channel_says_so_without_a_request() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    conflict = 1,
                    items = item(photoA, "$destDir\\a.jpg", "conflict", "目标位置已有同名文件, 未覆盖"),
                ),
            ),
        )

        val vm = vm()
        selectAndConfirm(vm, photoA)
        vm.move(order = listOf(photoA))
        server.takeRequest()

        val report = vm.retryOne(photoA)

        assertEquals(0, report.attempted)
        assertEquals(listOf(photoA), report.plan.notRetryable)
        assertEquals("没有可重试的东西时一个请求都不发", 1, server.requestCount)
    }

    // ── ⑤ 冲突不覆盖 ─────────────────────────────────────────────────────────

    @Test
    fun a_conflict_is_reported_as_nothing_overwritten_and_offers_no_retry() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                moveBody(
                    conflict = 1,
                    items = item(photoA, "$destDir\\a.jpg", "conflict", "目标位置已有同名文件, 未覆盖"),
                ),
            ),
        )

        val vm = vm()
        selectAndConfirm(vm, photoA)
        vm.move(order = listOf(photoA))

        val state = vm.state.value
        assertNull("200 的逐项冲突**不是**批次级失败", state.error)
        val row = state.rows.single()
        assertEquals(MoveStatus.CONFLICT, row.status)
        assertTrue("必须说清没有覆盖: ${row.text}", row.text.contains("未覆盖"))
        val note = state.note
        assertNotNull(note)
        assertTrue("批次句也要说: $note", note!!.contains("未覆盖"))
        assertNull("目标位置的同名文件还在: 重试同一个目标只会再冲突一次", row.retryRequest)
        val summary = state.summary
        assertNotNull(summary)
        assertEquals(1, tallyMove(summary!!).conflict)
        assertEquals(0, tallyMove(summary).moved)
    }

    // ── ⑥ 只能移动到已有文件夹(如实说明的限制) ──────────────────────────────

    @Test
    fun the_folder_picker_lists_only_folders_the_pc_really_has_and_says_new_ones_are_made_on_the_pc() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shares":["D:\\照片"]}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"path":"D:\\照片","name":"照片","photoCount":0,"hasSubdirs":true,
                    "subfolders":[{"path":"D:\\照片\\精修","name":"精修","photoCount":3,"hasSubdirs":false,"subfolders":[]}]}""".trimIndent(),
            ),
        )

        val browse = BrowseViewModel(pairing()) { url, tok -> ApiClient(url, tok, buildHttpClient(tok)) }
        browse.loadRoots()
        browse.open("D:\\照片")

        val model = folderPickerModel(browse.state.value)

        // 选择器里的每一个目标都来自服务端真的回了的东西(共享根 + 已展开的那一层)——
        // 它长不出一个"新建文件夹"的入口, 因为服务端根本没有 Mkdir 端点。
        assertEquals(listOf("D:\\照片", "D:\\照片\\精修"), model.rows.map { it.entry.path })
        assertFalse(
            "服务端没有 Mkdir 端点, 选择器里不许出现这种入口",
            model.rows.any { it.entry.name.contains("新建") },
        )
        // 而这条限制必须**如实**说出来: 只能选已有文件夹 + 新建去电脑上 + 手机端不能新建
        assertTrue("限制句: ${model.limitation}", model.limitation.contains("只能移动到"))
        assertTrue("必须给出「去电脑上」这条动作: ${model.limitation}", model.limitation.contains("电脑"))
        assertTrue("必须点明手机端不能新建: ${model.limitation}", model.limitation.contains("不能新建"))
        assertEquals("当前选中的目录就是可以当目标的那个", "D:\\照片", model.pickPath)
        assertEquals("照片", model.pickName)
    }

    @Test
    fun the_confirmation_says_nothing_will_be_overwritten_and_says_it_loudly_when_the_target_is_the_current_folder() {
        val normal = com.imagefilter.mobile.ui.moveConfirmText(
            count = 3,
            destName = "精修",
            destDir = destDir,
            sourceDir = "D:\\照片",
        )
        assertTrue("必须说清不会覆盖: $normal", normal.contains("不会被覆盖"))
        assertTrue("必须说清会报冲突: $normal", normal.contains("冲突"))
        assertTrue("必须说清移到哪: $normal", normal.contains("精修"))

        // 目标就是当前文件夹: 每一张都会报冲突, 那句确认必须**换一句话**(不然用户白跑一趟)
        val same = com.imagefilter.mobile.ui.moveConfirmText(
            count = 3,
            destName = "照片",
            destDir = "D:\\照片",
            sourceDir = "D:\\照片",
        )
        assertTrue("必须点明照片本来就在这里: $same", same.contains("本来就在"))
        assertTrue("必须劝用户换一个目标: $same", same.contains("换一个目标文件夹"))
    }

    // ── ⑦ 批次级失败复用既有文案表 ───────────────────────────────────────────

    @Test
    fun a_batch_level_403_is_worded_by_the_write_table_not_by_a_second_mapping() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbiddenPath"}"""))

        val vm = vm()
        selectAndConfirm(vm, photoA)
        vm.move(order = listOf(photoA))

        // 移动是**写**操作: 403 的两个成因(不在共享根 / 只读模式)在写那张表里说得最准。
        // 逐字相等 = 真的复用了(而不是又抄了一份迟早只改一处的映射)。
        val fromWriteTable = WriteMessages.forFailure(
            WriteFailure(path = "", code = ApiErrorCodes.FORBIDDEN_PATH, source = FailureSource.HTTP),
        )
        assertEquals(fromWriteTable, vm.state.value.error)
        // 而写表那一句必须点出"只读模式"这个成因(浏览页/读取页那两句都没有它)
        assertTrue("写表那一句必须提到只读模式: $fromWriteTable", fromWriteTable.contains("只读"))
        assertEquals(
            "批次级失败之后回到「挑目标」这一步, 可以换一个目录再试",
            MoveStage.Confirming,
            vm.state.value.stage,
        )
        assertTrue("没有逐项结果", vm.state.value.rows.isEmpty())
    }

    @Test
    fun a_transport_failure_is_worded_by_the_pairing_table() = runTest {
        // 连不上(端口上没有任何进程): 用真的 ConnectException 那一档, 而不是"中途断开"
        // (后者会被归到 network —— 那是另一句文案, 两档混起来这一条就测不出东西了)。
        val dead = MockWebServer().also { it.start() }
        val deadUrl = dead.url("/").toString().trimEnd('/')
        dead.shutdown()

        val vm = MoveViewModel(
            pairing = Pairing(url = deadUrl, token = token),
            clientFactory = { url, tok ->
                ApiClient(url, tok, buildHttpClient(tok).newBuilder().retryOnConnectionFailure(false).build())
            },
        )
        selectAndConfirm(vm, photoA)
        vm.move(order = listOf(photoA))

        // 传输层那一档: 用户动作与配对页/浏览页/读取页一模一样, 所以查**同一张**表。
        assertEquals(PairingMessages.forFailure(ApiErrorCodes.SERVICE_OFF), vm.state.value.error)
        assertNotEquals(
            "不许落到「网络中途断了」那一句上(那是另一档成因, 用户动作也不同)",
            PairingMessages.forFailure(ApiErrorCodes.NETWORK),
            vm.state.value.error,
        )
    }

    // ── 批次句与计数(纯函数) ────────────────────────────────────────────────

    @Test
    fun summarize_move_never_hides_a_half_success_behind_a_success_message() {
        val summary = Json.decodeFromString<MoveSummaryDto>(
            moveBody(
                moved = 3,
                halfSuccess = 1,
                items = item(photoA, "$destDir\\a.jpg", "halfSuccess", "边车没能跟着走", listOf(sidecarA)),
            ),
        )
        val text = summarizeMove(summary)
        assertTrue(text, text.contains("成功 3"))
        assertTrue(text, text.contains("1"))
        assertTrue("半成功必须在本句里说出来: $text", text.contains("边车"))
    }

    @Test
    fun summarize_move_says_a_conflict_was_not_overwritten() {
        val summary = Json.decodeFromString<MoveSummaryDto>(
            moveBody(
                conflict = 1,
                items = item(photoA, "$destDir\\a.jpg", "conflict", "目标位置已有同名文件, 未覆盖"),
            ),
        )
        val text = summarizeMove(summary)
        assertTrue(text, text.contains("未覆盖"))
        assertFalse("没有半成功就不许提「边车」: $text", text.contains("边车"))
    }

    @Test
    fun a_count_that_does_not_match_the_item_list_is_flagged_instead_of_being_trusted() {
        // 服务端给了 moved=5 而只回报了 1 项 —— 批次句可以照它说"成功 5 张"(那是电脑的说法),
        // 但**必须同时**说清只有 1 条逐项结果、有几项没被确认。少了后半句, 用户会以为
        // 另外 4 张确实搬过去了, 而它们到底动了没有**没人知道**。
        val summary = Json.decodeFromString<MoveSummaryDto>(
            moveBody(moved = 5, items = item(photoA, "$destDir\\a.jpg", "moved")),
        )
        val tally = tallyMove(summary)
        assertEquals("服务端给的计数逐字保留", 5, tally.moved)
        assertEquals("但电脑只回报了 1 条逐项结果", 1, tally.itemCount)
        assertTrue("计数与逐项结果对不上必须说出来", tally.unaccounted)
        val text = summarizeMove(summary)
        assertTrue("必须说清有几项没被确认: $text", text.contains("没被确认"))
        assertTrue("而且要说出只回了 1 条: $text", text.contains("1 条逐项结果"))
    }

    @Test
    fun an_unknown_status_is_counted_as_unknown_and_never_as_a_real_outcome() {
        val summary = Json.decodeFromString<MoveSummaryDto>(
            moveBody(items = item(photoA, null, "renamed")),
        )
        val tally = tallyMove(summary)
        assertEquals(1, tally.unknown)
        assertEquals(0, tally.moved)
        val text = summarizeMove(summary)
        assertTrue(text, text.contains("不认识"))
        assertFalse(
            "不认识的结果一个都不许被算进「成功 N 张」那一档: $text",
            Regex("""成功 \d+ 张""").containsMatchIn(text),
        )
    }

    // ── 重试计划本身(纯函数, 对称两支都钉) ─────────────────────────────────

    @Test
    fun the_retry_plan_never_contains_a_src_of_a_half_success_item() {
        val half = Json.decodeFromString<MoveSummaryDto>(
            moveBody(
                halfSuccess = 1,
                items = item(
                    photoA,
                    "$destDir\\a.jpg",
                    "halfSuccess",
                    "x",
                    listOf(sidecarA, "D:\\照片\\a.jpg.xmp"),
                ),
            ),
        )
        val plan = moveRetryPlan(toMoveRows(half.items))
        assertEquals(1, plan.requests.size)
        assertEquals(listOf(sidecarA, "D:\\照片\\a.jpg.xmp"), plan.requests.single().req.paths)
        assertFalse("死掉的 src 一个都不许进请求", plan.allPaths.contains(photoA))
        assertTrue("逐字等于服务端给的 pendingSidecars", plan.allPaths.all { it.endsWith(".xmp") })

        // 对称的一支: 没有任何半成功项时, 计划是空的(不是"把所有东西再发一遍")
        val clean = Json.decodeFromString<MoveSummaryDto>(
            moveBody(moved = 1, items = item(photoA, "$destDir\\a.jpg", "moved")),
        )
        assertTrue(moveRetryPlan(toMoveRows(clean.items)).isEmpty)
    }
}
