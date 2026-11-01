// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.imagefilter.mobile.data.FolderEntryDto
import com.imagefilter.mobile.data.Pairing
import com.imagefilter.mobile.data.PairingStore
import com.imagefilter.mobile.data.ThemeMode
import com.imagefilter.mobile.data.ThemePreference
import com.imagefilter.mobile.ui.BrowseScreen
import com.imagefilter.mobile.ui.BrowseViewModel
import com.imagefilter.mobile.ui.ConnectScreen
import com.imagefilter.mobile.ui.GridScreen
import com.imagefilter.mobile.ui.components.AppCard
import com.imagefilter.mobile.ui.components.AppConfirmDialog
import com.imagefilter.mobile.ui.components.AppGroupedCard
import com.imagefilter.mobile.ui.components.AppHint
import com.imagefilter.mobile.ui.components.AppIcons
import com.imagefilter.mobile.ui.components.AppRow
import com.imagefilter.mobile.ui.components.AppSectionLabel
import com.imagefilter.mobile.ui.components.AppSegmentedControl
import com.imagefilter.mobile.ui.components.AppSheet
import com.imagefilter.mobile.ui.components.AppSheetBody
import com.imagefilter.mobile.ui.components.AppSheetHeader
import com.imagefilter.mobile.ui.theme.AppMotion
import com.imagefilter.mobile.ui.theme.AppSpacing
import com.imagefilter.mobile.ui.theme.ImageFilterTheme
import com.imagefilter.mobile.ui.theme.LocalPhotoPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边 + 透明系统栏。图标明暗先由系统深浅色决定一帧; 至于用户选的「外观」偏好,
        // 由 Compose 里的 ImageFilterTheme 在拿到 DataStore 的值后覆盖(见 Theme.kt)。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            ThemedApp()
        }
    }
}

/**
 * 读「外观」偏好并套主题。
 *
 * 偏好有三档, 落到一个布尔 [dark] 上:
 *   · 跟随系统 → `isSystemInDarkTheme()`(系统切主题时自动重组);
 *   · 浅色 → false;
 *   · 深色 → true。
 *
 * 读盘是异步的, 第一帧先按 [ThemeMode.SYSTEM] 画 —— 窗口背景(values / values-night)
 * 本来就是按系统主题给的, 所以默认路径不会闪; 只有"系统深色 + 用户选浅色"这种覆盖
 * 才会在第一帧后切换, 这是 DataStore 异步读取的固有代价。
 */
@Composable
private fun ThemedApp() {
    val context = LocalContext.current
    val themeStore = remember { ThemePreference.create(context) }
    val scope = rememberCoroutineScope()
    val themeMode by themeStore.mode.collectAsState(initial = ThemeMode.SYSTEM)

    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    ImageFilterTheme(darkTheme = dark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AppRoot(
                themeMode = themeMode,
                onThemeMode = { mode -> scope.launch { themeStore.set(mode) } },
            )
        }
    }
}

/**
 * 唯一的导航:**没配对 → 配对向导**; 已配对 → **目录浏览页**; 选了文件夹 → **照片网格**。
 *
 * 配对信息是**启动时读一次**的: [PairingStore.load] 在"只有地址没 token"或
 * "只有 token 没地址"时都回 `null`(半配对不算配对), 所以这里只要看 `null` 判断即可。
 *
 * "重新配对"入口从占位页搬进了「设置」弹窗 —— 它必须一直存在: 真机上一旦配对成功就
 * **再也回不到**配对页的话, 配对流程(恰恰是必须能反复走的那条路)就断了。
 *
 * [openedFolder] 就是"选中文件夹"这件事本身: 它在 [BrowseScreen] 里发生, 由这里换成
 * [GridScreen]。所以返回目录 = 把它设回 `null`(网格里那个「返回」与系统返回键都走这条)。
 */
@Composable
private fun AppRoot(
    /** 当前外观偏好。只在设置弹窗里用, 但必须一直传到那一层。 */
    themeMode: ThemeMode,
    /** 选中某一档外观。写入 [ThemePreference] 后整棵树立即重组。 */
    onThemeMode: (ThemeMode) -> Unit,
) {
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
    val folder = openedFolder
    // 目录树的状态必须活得比 BrowseScreen 久: 手机点一行直接进网格, 返回时不能把
    // 用户已经展开的树和已读到的层一起扔掉。所以 ViewModel 放在这里, 由两页共用。
    val browseVm = remember(current) { current?.let { BrowseViewModel(it) } }
    val screen = when {
        current == null -> AppScreen.Connect
        folder == null -> AppScreen.Browse
        else -> AppScreen.Grid
    }

    if (!loaded) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 页面转场: 前进从右侧进、后退从左侧进 —— 同一路径往返, 方向可读。
        // 时长 260/180ms: 只有空间关系要说清时才用这么长, 别的交互仍然 <300ms。
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally(animationSpec = tween(AppMotion.enterMs)) { it / 5 } +
                        fadeIn(animationSpec = tween(AppMotion.enterMs))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(AppMotion.exitMs)) { -it / 8 } +
                            fadeOut(animationSpec = tween(AppMotion.exitMs)))
                } else {
                    (slideInHorizontally(animationSpec = tween(AppMotion.enterMs)) { -it / 5 } +
                        fadeIn(animationSpec = tween(AppMotion.enterMs))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(AppMotion.exitMs)) { it / 8 } +
                            fadeOut(animationSpec = tween(AppMotion.exitMs)))
                }
            },
            label = "screen",
        ) { target ->
            when (target) {
                // 未配对 = 默认走 mDNS 自动发现(ConnectScreen); 手输地址/配对码/扫码收在它内部的
                // 「高级模式」里, 成功后仍是同一个 onPaired —— 两条路径保存的是同一种 Pairing。
                AppScreen.Connect -> ConnectScreen(onPaired = {
                    openedFolder = null
                    pairing = it
                })

                AppScreen.Browse -> if (current != null && browseVm != null) {
                    BrowseScreen(
                        pairing = current,
                        vm = browseVm,
                        onOpenFolder = { openedFolder = it },
                        onSettings = { settingsOpen = true },
                    )
                }

                // M1′ Task 5 的网格: 与 ApiClient 共用同一枚 token 的同一个 OkHttpClient
                AppScreen.Grid -> if (current != null && folder != null) {
                    GridScreen(pairing = current, folder = folder, onBack = { openedFolder = null })
                }
            }
        }

        if (settingsOpen && current != null) {
            SettingsSheet(
                pairing = current,
                themeMode = themeMode,
                onThemeMode = onThemeMode,
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

private enum class AppScreen { Connect, Browse, Grid }

/**
 * 「设置」sheet: 外观 + 连接信息 + 重新配对。**token 一个字都不显示**(它是机密)。
 *
 * 苹果风落点: 从 AlertDialog 改成底部 sheet; 外观用分段控件（互斥三档, 选中即生效,
 * 没有"保存"）; 连接信息是一张只读分组卡; 重新配对是唯一的破坏性行, **二次确认**。
 */
@Composable
private fun SettingsSheet(
    pairing: Pairing,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
    onUnpair: () -> Unit,
) {
    val p = LocalPhotoPalette.current
    var confirmUnpair by remember { mutableStateOf(false) }

    AppSheet(onDismissRequest = onDismiss) {
        AppSheetHeader(title = "设置", onClose = onDismiss)
        AppSheetBody {
            AppCard(padding = AppSpacing.lg) {
                AppSectionLabel("外观")
                AppSegmentedControl(
                    options = ThemeMode.entries,
                    selected = themeMode,
                    onSelect = onThemeMode,
                    label = { it.label() },
                )
            }

            Spacer(Modifier.height(AppSpacing.xxl))
            AppSectionLabel("这台电脑")
            AppGroupedCard {
                AppRow(
                    title = pairing.note.ifBlank { "未命名电脑" },
                    subtitle = pairing.url,
                    leadingIcon = AppIcons.Computer,
                )
            }
            Spacer(Modifier.height(AppSpacing.md))
            AppHint("配对码只存在这台手机的加密存储里（AndroidKeyStore），界面上不显示。")

            Spacer(Modifier.height(AppSpacing.xxl))
            AppSectionLabel("连接")
            AppGroupedCard {
                AppRow(
                    title = "重新配对",
                    subtitle = "清掉这台手机上的配对信息，回到配对向导",
                    leadingIcon = AppIcons.Refresh,
                    titleColor = p.error,
                    onClick = { confirmUnpair = true },
                )
            }
            Spacer(Modifier.height(AppSpacing.xxl))
        }
    }

    if (confirmUnpair) {
        AppConfirmDialog(
            title = "重新配对？",
            text = "这台手机上的配对信息会被清掉，之后要重新扫码或手输地址。" +
                "照片和电脑上已有的星级不会受影响。",
            confirmLabel = "重新配对",
            onConfirm = {
                confirmUnpair = false
                onUnpair()
            },
            onDismiss = { confirmUnpair = false },
            destructive = true,
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
