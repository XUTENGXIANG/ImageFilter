// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.imagefilter.mobile.data.LocalCheck
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingCheck
import com.imagefilter.mobile.data.PairingField
import com.imagefilter.mobile.data.PairingInput
import com.imagefilter.mobile.data.PairingInputFormat
import com.imagefilter.mobile.data.PairingInputProblem
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.PairingScan
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.data.PairingValidator
import com.imagefilter.mobile.data.scanPairingPayload
import com.imagefilter.mobile.ui.components.AppButton
import com.imagefilter.mobile.ui.components.AppButtonStyle
import com.imagefilter.mobile.ui.components.AppCard
import com.imagefilter.mobile.ui.components.AppCheckRow
import com.imagefilter.mobile.ui.components.AppHint
import com.imagefilter.mobile.ui.components.AppIconButton
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppLargeHeader
import com.imagefilter.mobile.ui.components.AppTextField
import com.imagefilter.mobile.ui.components.AppTextButton
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 手动配对向导(规格 §8.3; 原 `PairingScreen`) —— 现在只做「高级模式」的落点。
 *
 * 默认的连接路径是 [ConnectScreen] 里的 mDNS 自动发现; 这一页保留**完整**的手输地址 +
 * 配对码 + 扫码能力, 用于: 自动发现被网络隔离挡住、电脑上没开 mDNS、或用户明确知道自己
 * 在连哪台电脑。两种模式成功后走的是**同一个** `onPaired`, 保存也仍是同一条
 * [PairingStore] 路径 —— 高级模式不是另一套配对实现。
 *
 * [onBack] 非空时在标题上方显示「‹ 自动发现」: 用户能从高级模式回到发现页。
 * 没有它(例如旧调用点)时这一页的外观与行为与拆分前完全一致。
 *
 * 状态机在 [PairingValidator] 里(纯逻辑, 有 JVM 单测); 这里只做三件事:
 * 收输入、把结论画出来、把**归一化后的**那一份交给 [PairingStore]。
 *
 * 三条纪律:
 *   · **输入一变就作废旧结论** —— 否则用户改了地址还能按"开始使用", 存下去的会是
 *     一份从没被验证过的组合;
 *   · **不提供"忽略证书错误"之类的开关**(规格 §4.3: 服务端不做 TLS, 加密边界**只有局域网**,
 *     所以不要在咖啡厅/酒店这类不可信 WiFi 上开服务) —— 那种开关等于把安全性交给一次误触;
 *   · 配对码是**机密**: 默认遮起来显示, 而且一个字都不进日志。
 */
@Composable
fun ManualPairingScreen(onPaired: (Pairing) -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember { PairingStore.create(context) }
    val validator = remember { PairingValidator() }
    val scope = rememberCoroutineScope()
    val p = LocalPhotoPalette.current

    var url by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    // 配对码刻意**不进 saved instance state**: 那是会被系统写进磁盘的一份快照, 而这是机密。
    // 代价是旋转屏幕后要重新粘贴一次 —— 相比把钥匙留在盘上, 这个代价可以接受。
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    var urlProblem by remember { mutableStateOf<PairingInputProblem?>(null) }
    var tokenProblem by remember { mutableStateOf<PairingInputProblem?>(null) }
    var check by remember { mutableStateOf<PairingCheck?>(null) }
    var verified by remember { mutableStateOf<LocalCheck.Ok?>(null) }
    var savingError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 排查清单默认收起: 它是一整段"连不上时再看"的小字, 不该占首屏。 */
    var checklistOpen by remember { mutableStateOf(false) }
    /** 每次输入变动 +1 —— 在飞的请求回来时用它判断"这份结论是不是已经过期了"。 */
    var generation by remember { mutableStateOf(0) }

    /** 任何一个框被改动 → 上一次的结论就作废(它描述的是改动前的那一份)。 */
    fun invalidate() {
        generation += 1
        check = null
        verified = null
        urlProblem = null
        tokenProblem = null
        savingError = null
        busy = false
    }

    // ── 扫码(M4′ Task 1 手机端)────────────────────────────────────────────
    //
    // ⚠️ 扫码**不是第二条配对路径**: 它只把下面那两个框填上, 校验仍然由「测试连接」走
    // 手输那条链路([PairingInputFormat] → [PairingValidator])。所以这里既不自己校验、
    // 也不自己保存 —— 唯一的差别是那两份值从哪来。
    /** 扫码的反馈(填好了 / 为什么填不了 / 权限被拒)。 */
    var scanNote by remember { mutableStateOf<String?>(null) }
    /** 上面那句话是不是"红的"(填不了/被拒), 还是"绿的"(填好了)。 */
    var scanNoteIsError by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            // 用户自己退出了扫码(相机页被返回/被系统收回): 什么都不做, **不报错** ——
            // 一次取消不是失败, 报红字会让用户以为配对出了问题。
            scanNote = null
        } else {
            when (val scan = scanPairingPayload(contents)) {
                is PairingScan.Filled -> {
                    // 先作废旧结论, 再把两个框填上 —— 否则用户能按着"已连上"的旧结论去「开始使用」
                    invalidate()
                    url = scan.url
                    token = scan.token
                    scanNoteIsError = false
                    scanNote = scan.notice ?: PairingMessages.SCAN_FILLED
                }

                is PairingScan.Rejected -> {
                    // **一个框都不动**: 扫到别的二维码不该把用户已经敲好的值冲掉
                    scanNoteIsError = true
                    scanNote = scan.message
                }
            }
        }
    }

    /** 真的拉起扫码页。拉不起来(没有相机/没有扫码组件)时给一句话, 配对照旧能手输。 */
    val launchScanner: () -> Unit = {
        try {
            scanner.launch(
                ScanOptions()
                    // 只认二维码: 配对载荷是 JSON, 扫出条形码/别的格式只会是噪音
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("对准电脑上「设置 → 图库服务」里的配对二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        } catch (e: Exception) {
            scanNoteIsError = true
            scanNote = PairingMessages.SCAN_UNAVAILABLE
        }
    }

    /** 相机权限: **先问再开**。被拒时只少一条捷径, 手输那条路一个字节都不受影响。 */
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchScanner()
        } else {
            scanNoteIsError = true
            scanNote = PairingMessages.SCAN_CAMERA_DENIED
        }
    }

    fun testConnection() {
        invalidate()
        // 本地校验先做: 不合格时**一个请求都不发**(那是最常见的失败, 不该退化成一次超时)
        when (val local = PairingInputFormat.check(PairingInput(url, token, note))) {
            is LocalCheck.Bad -> {
                when (local.field) {
                    PairingField.URL -> urlProblem = local.problem
                    PairingField.TOKEN -> tokenProblem = local.problem
                }
                check = PairingCheck.BadInput(local.field, local.problem)
            }
            is LocalCheck.Ok -> {
                val gen = generation
                scope.launch {
                    busy = true
                    val result = validator.check(PairingInput(local.url, local.token, local.note))
                    // 期间用户改了输入 → 这份结论描述的是旧输入, 丢掉而不是画出来
                    if (gen == generation) {
                        check = result
                        verified = if (result is PairingCheck.Connected) local else null
                        busy = false
                    }
                }
            }
        }
    }

    fun startUsing() {
        // 只用**被验证过的那一份**。它在字段改动时会被清掉, 所以这里不可能存下一份没验过的组合。
        val ok = verified ?: return
        scope.launch {
            busy = true
            try {
                store.save(ok.url, ok.token, ok.note)
                onPaired(Pairing(ok.url, ok.token, ok.note))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                savingError = "配对信息没能保存到这台手机上（${e.message ?: e::class.java.simpleName}）——请再试一次"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.s0)
            .bottomBarInsets(),
    ) {
        AppLargeHeader(
            title = "配对电脑",
            subtitle = "手机不保存照片，只拉缩略图与预览。",
            navigation = if (onBack != null) {
                {
                    AppIconButton(
                        icon = AppIcons.Back,
                        contentDescription = "回到自动发现",
                        onClick = onBack,
                    )
                }
            } else {
                null
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.page),
        ) {
            // 扫码(M4′ Task 1): 配对码是 64 位十六进制, 手输不现实 —— 二维码是唯一顺手的通道。
            // **手输永远在**: 拒绝相机权限、设备没有相机、扫到别的二维码时, 它都是唯一的路。
            AppButton(
                text = "扫码配对",
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) launchScanner() else permission.launch(Manifest.permission.CAMERA)
                },
                style = AppButtonStyle.Secondary,
                leadingIcon = AppIcons.Camera,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpacing.sm))
            AppHint("扫电脑设置页里的二维码，自动填好下面两项。")
            val scanNoteText = scanNote
            if (scanNoteText != null) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    text = scanNoteText.replace("**", ""),
                    // 填好了是普通色、"填不了/权限被拒"才是红的 —— 一次成功不该看着像出错
                    color = if (scanNoteIsError) p.error else p.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(AppSpacing.xl))

            AppTextField(
                value = url,
                onValueChange = { url = it; invalidate() },
                label = "电脑地址",
                placeholder = PairingMessages.URL_EXAMPLE,
                isError = urlProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                // FIX 4: 形状要说在**一直看得见**的地方 —— 占位符只在框为空且聚焦时才出现,
                // 而第一次配对的人正是看着空框在猜("localhost? 主机名? 要带端口吗?")。
                // 出错时这一行换成那句话(**一处**说一件事, 不再另起一行重复)。
                supportingText = urlProblem
                    ?.let { PairingMessages.forInputProblem(it) }
                    ?: "形如 http://192.168.3.10:8765。",
            )
            Spacer(Modifier.height(AppSpacing.md))

            AppTextField(
                value = token,
                onValueChange = { token = it; invalidate() },
                label = "配对码",
                placeholder = "64 位十六进制字符",
                isError = tokenProblem != null,
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = tokenProblem
                    ?.let { PairingMessages.forInputProblem(it) }
                    ?: "64 位十六进制；从电脑设置页复制，或直接扫码。",
            )
            Spacer(Modifier.height(AppSpacing.xs))
            AppCheckRow(
                checked = showToken,
                onCheckedChange = { showToken = it },
                label = "显示配对码",
                supporting = if (showToken) "现在能看见它" else "默认遮起来",
            )
            Spacer(Modifier.height(AppSpacing.md))

            AppTextField(
                value = note,
                onValueChange = { note = it; invalidate() },
                label = "备注（可选）",
                placeholder = "例如：家里的台式机",
            )
            Spacer(Modifier.height(AppSpacing.xl))

            AppButton(
                text = if (busy) "正在测试…" else "测试连接",
                onClick = { testConnection() },
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpacing.xl))

            when (val result = check) {
                is PairingCheck.Connected -> ConnectedCard(result, busy, ::startUsing)
                // FIX 6: 这张卡**不再**把那张四条清单抄一遍 —— 它就在下面常驻着([PairingMessages.CHECKLIST_HEAD] 那张),
                // 同一屏上说两次只会让人以为是两件事。卡里留下的仍然是**可执行的那一句**。
                is PairingCheck.Failed -> MessageCard(
                    head = "没连上",
                    body = PairingMessages.forFailureInWizard(result.code, result.unauthorizedCause),
                )
                is PairingCheck.BadInput -> MessageCard(
                    head = "请先改一下输入",
                    body = PairingMessages.forInputProblem(result.problem),
                )
                null -> Unit
            }

            val saveErr = savingError
            if (saveErr != null) MessageCard(head = "没保存成功", body = saveErr)

            Spacer(Modifier.height(AppSpacing.section))
            // §1.3: 这四条都会表现为"手机连不上", 而用户从手机这侧看不出是哪一条 —— 常驻列出。
            // 标题与失败卡里那句"按下面「…」里的四条逐一排查"取自**同一个**常量([PairingMessages.CHECKLIST_HEAD]),
            // 所以改一处两处一起变(否则卡里会指着一个屏幕上不存在的标题)。
            AppCard(padding = AppSpacing.lg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = PairingMessages.CHECKLIST_HEAD,
                        style = MaterialTheme.typography.titleSmall,
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    AppTextButton(
                        text = if (checklistOpen) "收起" else "展开",
                        onClick = { checklistOpen = !checklistOpen },
                        color = p.textSecondary,
                    )
                }
                if (checklistOpen) {
                    Spacer(Modifier.height(AppSpacing.xs))
                    for (item in PairingMessages.PREREQUISITES) {
                        Text(
                            text = "· $item",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textSecondary,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.md))
            AppHint("配对码相当于电脑上共享目录的钥匙，只存在这台手机的加密存储里（AndroidKeyStore）。")
            Spacer(Modifier.height(AppSpacing.xxl))
        }
    }
}
