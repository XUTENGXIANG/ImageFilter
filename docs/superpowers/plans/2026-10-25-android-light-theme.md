# Android 浅色主题 + UI 打磨 · 实施计划

> 在 mDNS 配对工作流集成通过后执行。使用项目 skill `.agents/skills/ui-craft`。
> **Goal:** 安卓端增加浅色主题（跟随系统 / 浅色 / 深色三档），并用 ui-craft 清单打磨
> 现有页面与新「连接电脑」页；查看器与滑卡保持近黑画布。
> **Architecture:** 用 `PhotoPalette` CompositionLocal 承载两套 token；
> `ImageFilterTheme(darkTheme)` 选 `lightColorScheme` / `darkColorScheme`；
> 根页面按偏好+系统选主题，查看器/滑卡强制 `darkTheme=true`。
> **Tech Stack:** Kotlin + Compose + Material3 + DataStore。

## Global Constraints

- 不新增依赖。
- 现有 **540+ 个 JVM 测试**必须全绿；`assembleDebug` 通过。
- 查看器与滑卡的画布/chrome **始终深色**（与桌面 `viewer.tsx` 的 `bg-black/95` 一致）；
  目录、网格、连接、设置、移动、待重试跟主题。
- 浅色下正文对比度 ≥ 4.5:1；发丝线是黑 6%，不是实心灰边。
- 星级 amber 在浅色下用更深的 amber（`#B45309`），深色仍 `#FFB900`。
- 不做 Material You 动态取色；不做 Cupertino 皮肤。
- 主题偏好存 DataStore；默认 `SYSTEM`。

## Token 表

| 语义 | 深色 | 浅色 |
|---|---|---|
| `s0` 画布 | `#09090B` | `#FAFAFA` |
| `s1` 面板 | `#121214` | `#F4F4F5` |
| `s2` 卡片 | `#18181B` | `#FFFFFF` |
| `s3` 输入/弹层 | `#27272A` | `#FFFFFF` |
| `hairline` | `#14FFFFFF`（白 8%） | `#0F000000`（黑 6%） |
| `textPrimary` | `#F4F4F5` | `#18181B` |
| `textSecondary` | `#9F9FA9` | `#52525B` |
| `textMuted` | `#71717A` | `#71717A` |
| `accent` 主色 | `#00BC7D` | `#009966` |
| `accentText` | `#00D492` | `#009966` |
| `onAccent` | `#00120B` | `#FFFFFF` |
| `star` | `#FFB900` | `#B45309` |
| `pending` 未同步 | `#FFB900` | `#B45309` |
| `error` | `#FF6467` | `#E7000B` |
| `labelRing`（色点描边） | `#33FFFFFF` | `#26000000` |

五色标保持 Tailwind 500：`#FB2C36 / #F0B100 / #00C950 / #2B7FFF / #AD46FF`。

M3 `lightColorScheme` 映射：

```
background/surface = #FAFAFA
surfaceContainerLow = #F4F4F5
surfaceContainer/High/Highest = #FFFFFF
surfaceVariant = #E4E4E7
onSurface = #18181B
onSurfaceVariant = #52525B
outline = #D4D4D8
outlineVariant = #0F000000
primary = #009966
onPrimary = #FFFFFF
primaryContainer = #D1FAE5
onPrimaryContainer = #064E3B
secondaryContainer = #E4E4E7
onSecondaryContainer = #18181B
error = #E7000B
errorContainer = #FEE2E2
onErrorContainer = #7F1D1D
surfaceTint = #FFFFFF
```

## 文件所有权（单个 agent F）

- 拥有：`android/app/src/main/kotlin/com/imagefilter/mobile/ui/**`、
  `MainActivity.kt`、`data/ThemePreference.kt`（新建）、
  `res/values/themes.xml`、`res/values-night/themes.xml`（新建）。
- 不碰：`data/` 里 C 的文件（除新建 ThemePreference）、`src-tauri/**`、`src/**`。
- 不 commit。

## Task F1：主题核心

**Files:** `ui/theme/Theme.kt`（改）、`ui/theme/PhotoPalette.kt`（新建）

- [ ] 建 `@Immutable data class PhotoPalette(...)`，含上表全部字段 + `isDark`；
      `DarkPalette` / `LightPalette`；`LocalPhotoPalette = staticCompositionLocalOf { DarkPalette }`。
- [ ] `ImageFilterTheme(darkTheme: Boolean = isSystemInDarkTheme(), content)`：
      `MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, ...)` +
      `CompositionLocalProvider(LocalPhotoPalette provides ...)`。
- [ ] `LightColors = lightColorScheme(...)` 按上表；`DarkColors` 沿用现有。
- [ ] 保留 `PhotoColors.Label*` 五色；其余颜色改从 palette 取。
- [ ] 编译；把编译器报错当作重构清单。

## Task F2：主题偏好 + 系统栏

**Files:** `data/ThemePreference.kt`（新建）、`MainActivity.kt`

- [ ] `enum class ThemeMode { SYSTEM, LIGHT, DARK }`；DataStore `theme.preferences_pb`；
      `flow` + `set`；默认 `SYSTEM`；文件坏时回 `SYSTEM`，不崩。
- [ ] `AppRoot` 收集偏好；`val dark = when (mode) { SYSTEM -> isSystemInDarkTheme(); LIGHT -> false; DARK -> true }`；
      `ImageFilterTheme(darkTheme = dark) { ... }`。
- [ ] 系统栏：`enableEdgeToEdge` 改为 `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { res -> ... }`
      或用 `SideEffect` 设置 `isAppearanceLightStatusBars = !dark`；导航栏同理。
- [ ] `values/themes.xml`：浅色父主题 `android:Theme.Material.Light.NoActionBar`、
      `windowBackground #FAFAFA`、`windowLightStatusBar true`。
- [ ] `values-night/themes.xml`：深色父主题、`#09090B`、`windowLightStatusBar false`。

## Task F3：把 UI 颜色接到 palette

**Files:** 所有 `grep -rl "PhotoColors\." android/.../ui` 命中的文件（约 13 个）

- [ ] 把 `PhotoColors.S0/S1/S2/S3/Hairline/TextPrimary/TextSecondary/TextMuted/Accent/
      AccentText/OnAccent/Pending/Error` 替换为 `LocalPhotoPalette.current.*` 或对应 M3 角色。
      `drawBehind`/`drawWithContent` 里先 `val p = LocalPhotoPalette.current` 再进 lambda。
- [ ] `QuietText` 默认色取 palette；链接色取 `palette.accentText`。
- [ ] `RatingBar`/`LABEL_COLORS`：五色静态；色点加 `palette.labelRing` 1dp 描边。
- [ ] `ViewerScreen` / `SwipeScreen` 根：包一层 `ImageFilterTheme(darkTheme = true)`，
      保证浅色主题下这两页仍是深色 chrome + 黑画布。
- [ ] `GridCards` 的星级角标文字继续白色（压在黑 chip 上）；未同步边用 `palette.pending`。

## Task F4：设置里的外观开关

**Files:** `MainActivity.kt`（PairingInfoDialog / AppRoot）

- [ ] 设置弹窗增加「外观」三档：跟随系统 / 浅色 / 深色；选中即写入 ThemePreference。
- [ ] 三档用现有 M3 `FilterChip` 或分段按钮，圆角 10dp；不做新页面。
- [ ] 切换时 `ImageFilterTheme` 立即重组；系统栏图标同步。

## Task F5：ui-craft 打磨 + 验收

- [ ] 按 `.agents/skills/ui-craft/references/audit.md` 过一遍：
      发丝线、同心圆角、单边框、按钮 10dp、48dp 触达、状态色（未知/未同步/错误）。
- [ ] 重点看新「连接电脑」页：搜索/设备/等待/高级模式在浅色下的对比度。
- [ ] `JAVA_HOME=... ./gradlew :app:testDebugUnitTest`（540+）
- [ ] `JAVA_HOME=... ./gradlew :app:assembleDebug`
- [ ] 模拟器截图（深色 + 浅色各一组）：连接页、目录、网格、设置外观开关、查看器（应保持深色）。
- [ ] 记录未验证：真机浅色户外可读性、系统栏在 OEM 上的表现。

## Agent F 派发

> 读本计划全文 + `.agents/skills/ui-craft/SKILL.md` 与 `references/{tokens,audit}.md`。
> 先读 `ui/theme/Theme.kt`、`MainActivity.kt`、`ui/{ConnectScreen,ConnectCards,GridScreen,
> FilterBar,BrowseScreen,PairingScreen,ViewerScreen,SwipeScreen,RatingBar,PendingSheet}.kt`。
> 只改本计划「文件所有权」列出的文件。按 F1→F5 顺序做；每步用编译错误驱动。
> 不 commit；报告改动文件、测试/构建结果、深浅截图、未验证项。
