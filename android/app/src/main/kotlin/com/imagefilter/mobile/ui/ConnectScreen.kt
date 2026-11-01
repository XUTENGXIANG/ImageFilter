// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.DeviceIdentity
import com.imagefilter.mobile.data.DiscoveredDevice
import com.imagefilter.mobile.data.DiscoveryEvent
import com.imagefilter.mobile.data.DiscoverySource
import com.imagefilter.mobile.data.HttpPairingApi
import com.imagefilter.mobile.data.NsdDiscoverySource
import com.imagefilter.mobile.data.PairRequestResult
import com.imagefilter.mobile.data.PairStatusResult
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingApi
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.ui.components.AppHint
import com.imagefilter.mobile.ui.components.AppLargeHeader
import com.imagefilter.mobile.ui.components.AppLoadingRow
import com.imagefilter.mobile.ui.components.AppSectionLabel
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import kotlinx.coroutines.delay

/**
 * 未配对时的**默认**页面: 在同一 Wi-Fi 里发现电脑, 点一台, 在电脑上点「允许」。
 *
 * 这一页刻意很安静: 标题 + **一行**状态 + 设备行。所有长说明、地址、配对码、扫码、
 * 四条排查清单都收在「高级模式」里的 [ManualPairingScreen] —— 默认路径不该要求用户
 * 先读懂一套排障手册才能连上电脑。
 *
 * 状态全部来自 [ConnectViewModel](C 的纯状态机, JVM 单测覆盖); 这里只做三件事:
 * 收状态、把用户动作转成方法调用、把文案画出来。所以这个文件里**没有一条业务判断**,
 * 包括 60 秒超时、去重、保存成功才 Approved 这些规则。
 *
 * 生命周期注意: [ConnectViewModel] **不是** `androidx.lifecycle.ViewModel`, 它自己不会
 * 收到 `onCleared()`。所以这里用 `DisposableEffect` 在离开组合时显式转发 —— 否则
 * `NsdManager` 的监听器会一直占着组播 socket(计划 C4 的硬要求)。
 *
 * @param onPaired 配对成功**且已保存**后调用, 与高级模式手输配对走同一个回调。
 */
@Composable
fun ConnectScreen(onPaired: (Pairing) -> Unit) {
    // 高级模式是这一页的内部状态: 展开时整个发现页(连同 ViewModel)离开组合,
    // 回来时重新建一个 —— 不会出现"人在填地址, 后台还在轮询旧请求"的两条并行路径。
    var advanced by rememberSaveable { mutableStateOf(false) }

    if (advanced) {
        ManualPairingScreen(onPaired = onPaired, onBack = { advanced = false })
    } else {
        DiscoveryPane(onPaired = onPaired, onOpenAdvanced = { advanced = true })
    }
}

@Composable
private fun DiscoveryPane(onPaired: (Pairing) -> Unit, onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 演示模式只在 debug 构建 + 启动 Intent 显式带 demoDiscovery=true 时生效(见文件末尾)。
    val demo = remember(context) { demoDiscoveryEnabled(context) }

    val vm = remember(scope, demo) {
        if (demo) {
            ConnectViewModel(
                source = DemoDiscoverySource(),
                api = DemoPendingPairingApi,
                identity = DeviceIdentity.create(context),
                saver = PairingStore.create(context).asPairingSaver(),
                scope = scope,
            )
        } else {
            ConnectViewModel(
                NsdDiscoverySource(context),
                HttpPairingApi.create(context),
                DeviceIdentity.create(context),
                PairingStore.create(context).asPairingSaver(),
                scope,
            )
        }
    }
    val state by vm.state.collectAsState()

    LaunchedEffect(vm) { vm.start() }
    DisposableEffect(vm) { onDispose { vm.onCleared() } }

    // 电脑批准 + 保存成功之后才走到这里(状态机保证)。先把这一帧画成"正在进入…",
    // 再由主界面把整页换掉。
    val pairing = (state as? ConnectState.Approved)?.pairing
    LaunchedEffect(pairing) { if (pairing != null) onPaired(pairing) }

    ConnectPage(
        state = state,
        onSelect = vm::select,
        onRetry = vm::retry,
        onCancel = vm::cancel,
        onAdvanced = onOpenAdvanced,
    )
}

/** 纯展示: 给定 [ConnectState] 画什么。没有任何副作用(除了"搜索久了显示空态"的计时)。 */
@Composable
private fun ConnectPage(
    state: ConnectState,
    onSelect: (DiscoveredDevice) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onAdvanced: () -> Unit,
) {
    // draw/paint 之外的普通文本也走同一份 palette —— 这个页面在浅色下是白卡 + 深字。
    val p = LocalPhotoPalette.current
    val searching = state is ConnectState.Searching

    // 一开始画"正在搜索…"是诚实的; 但搜了一会儿仍一台没有时, 必须把"没找到"和
    // 下一步动作(重新搜索/高级模式)说出来 —— 否则用户会盯着一个转圈一直等下去。
    var showEmptyCard by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (!searching) {
            showEmptyCard = false
            return@LaunchedEffect
        }
        delay(EMPTY_HINT_DELAY_MS)
        showEmptyCard = true
    }

    val status = statusLineFor(state)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.s0)
            .bottomBarInsets(),
    ) {
        AppLargeHeader(
            title = "连接电脑",
            subtitle = "在同一 Wi-Fi 下找到电脑，点一下即可配对。",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.page),
        ) {
            // 全屏**只有这一行**状态。每个分支自己的说明都在下面那张卡里, 不重复。
            ConnectStatusLine(status = status.text, busy = status.busy)
            Spacer(Modifier.height(AppSpacing.xxl))

            when (state) {
                ConnectState.Searching -> if (showEmptyCard) {
                    NoDeviceCard(onRescan = onRetry, onAdvanced = onAdvanced)
                }

                is ConnectState.Devices -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        for (device in state.devices) {
                            DeviceCard(device = device, onClick = { onSelect(device) })
                        }
                    }
                }

                is ConnectState.Waiting -> WaitingCard(
                    device = state.device,
                    onCancel = onCancel,
                )

                // 只可能停留一帧: LaunchedEffect 随后把整个页面换成已配对的主界面。
                is ConnectState.Approved -> AppLoadingRow("配对成功，正在进入…")

                is ConnectState.Denied -> ConnectErrorCard(
                    head = "配对没成功",
                    message = state.message,
                    onRetry = onRetry,
                    onAdvanced = onAdvanced,
                )

                is ConnectState.Error -> ConnectErrorCard(
                    head = "连接没成功",
                    message = state.message,
                    onRetry = onRetry,
                    onAdvanced = onAdvanced,
                )
            }

            Spacer(Modifier.height(AppSpacing.xxl))
        }

        // 底部入口: 设备列表态常驻; 搜索刚开始时也在(用户可能一眼就知道自己要用高级模式);
        // 空态/错误态卡里已经有同名按钮, Waiting/Approved 时不该有出路。
        val showBottomAdvanced = state is ConnectState.Devices ||
            (state is ConnectState.Searching && !showEmptyCard)
        if (showBottomAdvanced) ConnectAdvancedToggle(onClick = onAdvanced)
        Spacer(Modifier.height(AppSpacing.sm))
    }
}

private data class StatusLine(val text: String, val busy: Boolean)

private fun statusLineFor(state: ConnectState): StatusLine = when (state) {
    ConnectState.Searching -> StatusLine("正在搜索同一 Wi-Fi 下的电脑…", busy = true)
    is ConnectState.Devices -> StatusLine("找到 ${state.devices.size} 台电脑", busy = false)
    is ConnectState.Waiting -> StatusLine("等待电脑确认…", busy = true)
    is ConnectState.Approved -> StatusLine("配对成功，正在进入…", busy = true)
    // 终态: 失败原因写在下面的卡里(含 message), 这一行只说下一步 —— 否则同一句
    // "连接没成功"会在状态行和卡头各出现一次, 看起来像两个问题。
    is ConnectState.Denied -> StatusLine("可以重试，或使用高级模式", busy = false)
    is ConnectState.Error -> StatusLine("可以重试，或使用高级模式", busy = false)
}

/** 搜索多久之后把"没找到 + 下一步"亮出来。太短会让「正在搜索」一闪而过。 */
private const val EMPTY_HINT_DELAY_MS = 4_000L

// ── 仅 debug 的演示路径(计划 §5 D3)───────────────────────────────────────────
//
// 用途: 模拟器过不了 mDNS 的 NAT(计划 §8), 但截图验收要看到「设备列表 / 等待确认」。
// 启动方式:
//   adb shell am start -n com.imagefilter.mobile/.MainActivity --ez demoDiscovery true
//
// 为什么用 ApplicationInfo.FLAG_DEBUGGABLE 而不是 BuildConfig.DEBUG:
// AGP 8 默认不生成 BuildConfig(见 `PairingApi.kt` 的说明), 打开它要改 `build.gradle.kts` ——
// 那不在本工作流的文件范围内。debug 变体的 FLAG_DEBUGGABLE 与 BuildConfig.DEBUG 等价,
// release 变体里这个标志为 false, 所以 release 行为一个字节都不变; 也没有任何生产菜单入口。

private const val EXTRA_DEMO_DISCOVERY = "demoDiscovery"

private fun demoDiscoveryEnabled(context: Context): Boolean {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current.intent?.getBooleanExtra(EXTRA_DEMO_DISCOVERY, false) == true
        }
        current = current.baseContext
    }
    return false
}

private val DEMO_DEVICES = listOf(
    DiscoveredDevice(
        serviceName = "ImageFilter DEMO-DESKTOP._imagefilter._tcp.",
        name = "DEMO-DESKTOP",
        host = "192.168.1.20",
        port = 8765,
        pairOpen = true,
        desktopId = "0123456789abcdef0123456789abcdef",
    ),
    DiscoveredDevice(
        serviceName = "ImageFilter DEMO-LAPTOP._imagefilter._tcp.",
        name = "DEMO-LAPTOP",
        host = "192.168.1.21",
        port = 8765,
        pairOpen = false,
        desktopId = "fedcba9876543210fedcba9876543210",
    ),
)

/** 过几秒后"发现"两台假电脑 —— 让「搜索中」也有机会被截到。 */
private class DemoDiscoverySource : DiscoverySource {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ((DiscoveryEvent) -> Unit)? = null

    private val emitDevices = Runnable {
        val cb = callback ?: return@Runnable
        DEMO_DEVICES.forEach { cb(DiscoveryEvent.Found(it)) }
    }

    override fun start(onEvent: (DiscoveryEvent) -> Unit) {
        callback = onEvent
        handler.removeCallbacks(emitDevices)
        handler.postDelayed(emitDevices, DEMO_DISCOVERY_DELAY_MS)
    }

    override fun stop() {
        callback = null
        handler.removeCallbacks(emitDevices)
    }
}

private const val DEMO_DISCOVERY_DELAY_MS = 3_000L

/** 永远 pending: 「等待电脑确认…」能停在屏幕上被截图, 且绝不会替用户存下一份假配对。 */
private object DemoPendingPairingApi : PairingApi {
    override suspend fun request(
        device: DiscoveredDevice,
        deviceId: String,
        deviceName: String,
    ): PairRequestResult = PairRequestResult.Ok(
        requestId = "demo-request",
        expiresAt = System.currentTimeMillis() + 5 * 60_000L,
    )

    override suspend fun status(device: DiscoveredDevice, requestId: String): PairStatusResult =
        PairStatusResult.Pending
}
