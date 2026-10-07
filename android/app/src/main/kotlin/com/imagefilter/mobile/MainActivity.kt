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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.ui.BrowseScreen
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
 * 唯一的导航:**没配对 → 配对向导**; 已配对 → **目录浏览页**(M1′ Task 4)。
 *
 * 配对信息是**启动时读一次**的: [PairingStore.load] 在"只有地址没 token"或
 * "只有 token 没地址"时都回 `null`(半配对不算配对), 所以这里只要看 `null` 判断即可。
 *
 * "重新配对"入口从占位页搬进了「设置」弹窗 —— 它必须一直存在: 真机上一旦配对成功就
 * **再也回不到**配对页的话, 配对流程(恰恰是必须能反复走的那条路)就断了。
 *
 * [openedFolder] 是给 M1′ Task 5 的网格留的seam: 选中文件夹这件事在 [BrowseScreen]
 * 里发生, 但它自己不画网格(那是 Task 5)。这里如实说"网格还没接入", 而不是画一个假的。
 */
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val store = remember { PairingStore.create(context) }
    val scope = rememberCoroutineScope()

    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var openedFolder by remember { mutableStateOf<FolderEntryDto?>(null) }

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

        current == null -> PairingScreen(onPaired = {
            openedFolder = null
            pairing = it
        })

        else -> {
            val folder = openedFolder
            if (folder == null) {
                BrowseScreen(
                    pairing = current,
                    onOpenFolder = { openedFolder = it },
                    onSettings = { settingsOpen = true },
                )
            } else {
                OpenedFolderPlaceholder(folder = folder, onBack = { openedFolder = null })
            }

            if (settingsOpen) {
                PairingInfoDialog(
                    pairing = current,
                    onDismiss = { settingsOpen = false },
                    onUnpair = {
                        scope.launch {
                            store.clear()
                            settingsOpen = false
                            openedFolder = null
                            pairing = null
                        }
                    },
                )
            }
        }
    }
}

/**
 * 已选文件夹的占位页 —— **只有一条路径显示是真的**(原样来自服务端), 网格本身没有。
 * Task 5 会把这一页换成 Coil 网格; 现在这页存在的意义是让"选目录 → 进文件夹"这条
 * 链路能在真机上被走一遍, 而不是让用户点了个按钮什么都没发生。
 */
@Composable
private fun OpenedFolderPlaceholder(folder: FolderEntryDto, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(folder.name.ifBlank { folder.path }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            folder.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "缩略图网格在 M1′ Task 5 接入（Coil 3 + 同一个带鉴权头的 OkHttp）。" +
                "这一页现在只证明：文件夹是从服务端逐层点出来的。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("返回目录") }
    }
}

/** 「设置」弹窗: 连接信息 + 重新配对。**token 一个字都不显示**(它是机密)。 */
@Composable
private fun PairingInfoDialog(pairing: Pairing, onDismiss: () -> Unit, onUnpair: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已配对") },
        text = {
            Column {
                Text(pairing.note.ifBlank { "（未命名）" }, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(pairing.url, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "配对码只存在这台手机的加密存储里（AndroidKeyStore），界面上不显示。" +
                        "重新配对会清掉它，并回到配对向导。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onUnpair) { Text("重新配对") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.fillMaxWidth(),
    )
}
