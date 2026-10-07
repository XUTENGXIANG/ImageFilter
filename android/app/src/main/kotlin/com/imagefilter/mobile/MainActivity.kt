// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.ui.PairingScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

/**
 * 唯一的导航:**没配对 → 配对向导**; 已配对 → 占位页。
 *
 * 配对信息是**启动时读一次**的: [PairingStore.load] 在"只有地址没 token"或
 * "只有 token 没地址"时都回 `null`(半配对不算配对), 所以这里只要看 `null` 判断即可。
 *
 * 已配对那一支现在是**占位**: M1′ Task 4 会把它换成 `BrowseScreen`。这里先给出
 * "重新配对"按钮不是顺手加的 —— 没有它, 真机上一旦配对成功就**再也回不到**配对页,
 * 而配对流程恰恰是必须能反复走的那条路(Task 3 的设备清单要验的就是它)。
 */
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val store = remember { PairingStore.create(context) }
    val scope = rememberCoroutineScope()

    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 读失败(DataStore 文件坏了这类极罕见情形)**当作未配对**, 让用户重新走一遍配对 ——
        // 卡在转圈上是一个没有出路的状态。真有问题时下一步的 save() 会如实报错
        // ("配对信息没能保存到这台手机上"), 所以这里不吞掉用户唯一的反馈机会。
        pairing = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        loaded = true
    }

    val current = pairing
    when {
        !loaded -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }

        current == null -> PairingScreen(onPaired = { pairing = it })

        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("已配对", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            // token **不显示**: 它是机密, 屏幕上没有它的位置。
            val label = current.note.ifBlank { "（未命名）" }
            Text("$label  ·  ${current.url}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "目录树与缩略图网格在 M1′ 的后续任务里接入。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                scope.launch {
                    store.clear()
                    pairing = null
                }
            }) {
                Text("重新配对")
            }
        }
    }
}
