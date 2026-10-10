// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.LocalCheck
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingCheck
import com.imagefilter.mobile.data.PairingField
import com.imagefilter.mobile.data.PairingInput
import com.imagefilter.mobile.data.PairingInputFormat
import com.imagefilter.mobile.data.PairingInputProblem
import com.imagefilter.mobile.data.PairingMessages
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.data.PairingValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 配对向导(规格 §8.3)。
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
fun PairingScreen(onPaired: (Pairing) -> Unit) {
    val context = LocalContext.current
    val store = remember { PairingStore.create(context) }
    val validator = remember { PairingValidator() }
    val scope = rememberCoroutineScope()

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
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("配对电脑", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "手机不保存照片：连接电脑上的 ImageFilter，只拉缩略图与预览。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; invalidate() },
            label = { Text("电脑地址") },
            placeholder = { Text("http://192.168.1.5:8765") },
            singleLine = true,
            isError = urlProblem != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        val urlErr = urlProblem
        if (urlErr != null) {
            Text(
                PairingMessages.forInputProblem(urlErr),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it; invalidate() },
            label = { Text("配对码") },
            placeholder = { Text("64 位十六进制字符") },
            singleLine = true,
            isError = tokenProblem != null,
            visualTransformation = if (showToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        val tokenErr = tokenProblem
        if (tokenErr != null) {
            Text(
                PairingMessages.forInputProblem(tokenErr),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { showToken = !showToken }) {
            Text(if (showToken) "隐藏配对码" else "显示配对码")
        }
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = note,
            onValueChange = { note = it; invalidate() },
            label = { Text("备注（可选）") },
            placeholder = { Text("例如：家里的台式机") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Row {
            Button(onClick = { testConnection() }, enabled = !busy) { Text("测试连接") }
            if (busy) {
                Spacer(Modifier.size(12.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(16.dp))

        when (val result = check) {
            is PairingCheck.Connected -> ConnectedCard(result, busy, ::startUsing)
            is PairingCheck.Failed -> MessageCard(
                head = "没连上",
                body = PairingMessages.forFailure(result.code, result.unauthorizedCause),
            )
            is PairingCheck.BadInput -> MessageCard(
                head = "请先改一下输入",
                body = PairingMessages.forInputProblem(result.problem),
            )
            null -> Unit
        }

        val saveErr = savingError
        if (saveErr != null) MessageCard(head = "没保存成功", body = saveErr)

        Spacer(Modifier.height(24.dp))
        // §1.3: 这四条都会表现为"手机连不上", 而用户从手机这侧看不出是哪一条 —— 常驻列出。
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("连不上？逐条确认", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                for (item in PairingMessages.PREREQUISITES) {
                    Text("· $item", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "配对码相当于电脑上共享目录的钥匙，只存在这台手机的加密存储里（AndroidKeyStore）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
