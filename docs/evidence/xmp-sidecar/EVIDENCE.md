# ImageFilter 实机验证证据（XMP 边车 · 星级写入 + 其它字段保留）

日期：2026-10-05 00:19–00:26 (+08:00)
被测进程：`image-filter` pid=16492，窗口标题 `ImageFilter`，hwnd=5179968，窗口矩形 26,26,1216,809
活动目录：`F:\壁纸`（16 张照片 + 3 个既有 .xmp）
目标照片：`F:\壁纸\20251115-DSC07500-已增强-降噪.JPG`（界面原值 ★★★★★）
应用设置：`localStorage["imagefilter-xmp-mode"]` = `on`（leveldb 读出）

## 0. 动手前快照

```
$ gui.ps1 shot -> 01-app.png                      # 界面：16 张、卡 1 显示 ★★★★★
$ Get-FileHash 'F:\壁纸\20251115-DSC07500-已增强-降噪.JPG'      -> 57647CD6B0E6A948D8574EB599199C88
$ Get-FileHash 'F:\壁纸\20251115-DSC07500-已增强-降噪.xmp'      -> A583493C3DC078FFC7C2D77D3E019DE9 (320 B, mtime 2026-10-05T00:19:16)
目录 19 个文件 → snap-before.txt
```

原始边车（字节级备份 `orig-07500.xmp`）：
```
<?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="ImageFilter">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
   xmlns:xmp="http://ns.adobe.com/xap/1.0/"
   xmp:Rating="5"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
```

## 1. 手工塞入自定义属性（`stage-ifx.ps1`，UTF-8 无 BOM 写回）

staged 内容（320 B → 366 B，md5 `491DA78A436E9A3F9F4A47B5F87F7FC9`，备份 `staged-07500.xmp`）：
```
   xmlns:xmp="http://ns.adobe.com/xap/1.0/"
   xmp:Rating="5"
   xmlns:ifx="urn:test"
   ifx:note="keep-me"/>
```

## 2. 界面点击第 3 颗星（元素树定位，非像素猜）

```
$ gui.ps1 uia-state -Proc image-filter -Name "★" -Exact -Index 2
Type=Button Name=★ Id= Class=text-[10px] text-amber-400
Enabled=True Offscreen=False Focusable=True Focused=False Rect=316,342,9,15
Patterns: InvokePatternIdentifiers.Pattern, ScrollItemPatternIdentifiers.Pattern

$ gui.ps1 uia-click -Proc image-filter -Name "★" -Exact -Index 2
target: Button | ★ | 316,342,9,15
invoked via InvokePattern
```

3 秒后：边车 366 B，md5 `7C7333D602F8D1D9215CDC56F3F85ACB`（`after-rating3-07500.xmp`）。

**逐字节 diff（staged → after）**：
```
staged bytes=366  after bytes=366
diff count=1
offset 271 : 35 -> 33  ('5' -> '3')
```
即：**只有 `xmp:Rating` 的值这 1 个字节被改写**，`xmlns:ifx="urn:test"` 与 `ifx:note="keep-me"` 原样在位，其余 364 字节一字未动。

界面同时变成 3 星（截图 `04-after-3star.png` / `05-after-3star-zoom.png`）：
```
$ gui.ps1 uia-dump -Filter "★" -Max 400 | head -1
Text | ★★★ | 475,345,25,13          # 卡 1
```

## 3. 复原

```
$ gui.ps1 uia-click -Name "★" -Exact -Index 4   -> target: Button | ★ | 336,342,9,15
after: md5=491DA78A436E9A3F9F4A47B5F87F7FC9     # 与 staged 完全一致（往返字节确定）
界面：Text | ★★★★★ | 459,345,41,13
```
再把 `orig-07500.xmp` 覆盖回去（去掉我加的 ifx 两行）：
```
restored: len=320 mtime=2026-10-05T00:19:16 md5=A583493C3DC078FFC7C2D77D3E019DE9
          = 原始值，逐字节复原
```

## 4. 目录级副作用面

```
before/after 目录各 19 个文件（无新增、无删除、无 .tmp 残留、无 .imagefilter-xmp-probe 残留）
size|mtime 多重集差异只有一项：
  <= 320|2026-10-05T00:19:16   (原)
  => 366|2026-10-05T00:24:56   (我加了两行 ifx 后的结果)
16 张 JPG 的 size+mtime 全部不变；目标 JPG md5 仍为 57647CD6B0E6A948D8574EB599199C88
```

## 产物清单（均在 A:\tenent\_probe\）

| 文件 | 说明 |
|---|---|
| `orig-07500.xmp` | 原始边车逐字节备份（复原来源） |
| `staged-07500.xmp` | 加了 ifx 两行的边车（写入前基线） |
| `after-rating3-07500.xmp` | 应用写入 3 星后的边车 |
| `after-restore5-07500.xmp` | 应用写回 5 星后的边车（= staged） |
| `snap-before.txt` / `snap-after.txt` | 目录快照 |
| `01-app.png` / `04-after-3star.png` / `05-after-3star-zoom.png` | 界面截图 |
| `stage-ifx.ps1` | 手工改边车的脚本（UTF-8 **带 BOM**，PS 5.1 才读得对） |
