<#
  Copyright (c) 2026 XUTENGXIANG
  SPDX-License-Identifier: MIT

  ════════════════════════════════════════════════════════════════════════════
  图库服务契约验证(一条命令) —— 对着**真实运行中的桌面端**打一遍全部端点、错误分支、
  参数边界、共享根白名单与移动回环。
  ════════════════════════════════════════════════════════════════════════════

  这个脚本回答一个问题: **服务现在健康吗? 它守不守自己宣称的契约?**
  Rust 单测/集成测试跑的是进程内的 Router; 这个脚本跑的是**真的在 Tauri 进程里
  监听端口的那个服务** —— 那一条是 cargo test 覆盖不到的。

  ── 怎么跑(两种模式) ────────────────────────────────────────────────────────
  ① 全自动(推荐, 不需要手机、也不需要先开桌面端):
       powershell -NoProfile -ExecutionPolicy Bypass -File docs\evidence\scripts\verify-server.ps1
     · 脚本自己造一个**临时共享根**(绝不指向你的照片文件夹), 自己写一份临时配置, 自己把
       桌面端拉起来(窗口会最小化一闪), 打完全部检查, 再自己收摊。
     · 服务进程的缓存根被指到临时目录, 所以不会碰你的 %LOCALAPPDATA%\image-filter。
     · %APPDATA% 下只动 server-config.json, 而且**跑完逐字节还原**(先备份)。
     · 需要先构建过桌面端: cargo build --manifest-path src-tauri\Cargo.toml
       (找不到 exe 时脚本会直接说清楚跑哪条命令)

  ② 对接已经在跑的服务(脚本只读, 不启停服务):
       powershell ... -File docs\evidence\scripts\verify-server.ps1 `
         -BaseUrl http://127.0.0.1:8765 -Token <64位hex> -SharePath <一个已配置为共享根的临时目录>
     · -SharePath 供"共享根之内"的检查使用: 请给一个**你自己建的临时目录**, 并已加入桌面端
       共享根 —— 不要用真实照片目录。这一模式**不写**共享根里的任何东西(不建文件、不移动、
       不写边车), 唯一的写入是服务端的缓存目录(要显式加 -AllowCacheWrite 才跑那几项)。

  ── 它保证不碰什么 ──────────────────────────────────────────────────────────
  · 绝不读写 %LOCALAPPDATA%\image-filter(全自动模式: 服务端缓存根被重定向到临时目录;
    对接模式: 除非 -AllowCacheWrite, 否则不跑任何会写缓存的请求);
  · 绝不引用 F:\壁纸 或任何真实照片目录(唯一被服务的目录是脚本自己造的临时共享根);
  · %APPDATA%\<identifier>\server-config.json 先备份再改、跑完**逐字节还原**, 还原动作在
    finally 里(中途报错/Ctrl+C 也会还原)。桌面端自己的 image-filter.db 会被它正常打开一次
    (那是服务的正常启动行为, 脚本不写它)。
  · 所有会写盘的检查(browse/scan/thumb/preview/decisions 写/move)都发生在临时共享根内。

  ── 输出 ────────────────────────────────────────────────────────────────────
  每个检查一行 PASS/FAIL(FAIL 会打印**观察到的状态码与响应体**), 末尾给出统计;
  任何一项 FAIL 时退出码 = 1。SKIP 是"这一模式下不适用/不安全的项", 不算失败。

  ── 与实现的一致性(本脚本按**代码的实际契约**写, 不按计划稿的散文) ──────────
  已经核对过、并且与计划稿散文不同的三处(都按实现写):
   · thumb 的 size **不落在 {150,300,600} 且 ≥150 时向下取整**, 不是 400
     (只有 < 150 才 400)。见 src-tauri/src/server/media.rs 的 THUMBNAIL_SIZES。
   · preview 的 full **只认 true/false**(serde_urlencoded 走 str::parse::<bool>()),
     full=1 是 400 badRequest。
   · /move 的"目标同名"是 **HTTP 200 + 逐项 conflict 计数**, 不是 409;
     本 build 里没有任何请求路径会产生 409(见 routes.rs), 423 只在
     get_full_image 的 superseded 竞态里出现 —— 这两个分支脚本只做说明、不断言。
#>
[CmdletBinding()]
param(
  # 对接模式: 已经在跑的服务地址。给了它就不再自己启停服务。
  [string]$BaseUrl,
  # 对接模式必填: 64 位十六进制配对 token(桌面端设置面板里那串)。
  [string]$Token,
  # 对接模式可选: 一个"已被配置成共享根"的临时目录(正向检查用它, 脚本只读)。
  [string]$SharePath,
  # 全自动模式可选: 指定桌面端 exe; 不给则自动找 src-tauri\target\{debug,release}。
  [string]$AppExe,
  # 对接模式: 允许跑会写服务端缓存目录的那几项(默认跳过, 以免碰 %LOCALAPPDATA%\image-filter)。
  [switch]$AllowCacheWrite,
  # 出错时保留临时目录, 便于事后看现场。
  [switch]$KeepArtifacts,
  # 等服务起来的上限(秒)。
  [int]$BootTimeoutSec = 45,
  # 单次请求超时(秒)。
  [int]$TimeoutSec = 30
)

$ErrorActionPreference = 'Stop'

# ── 0. 常量 / 内建清单 ───────────────────────────────────────────────────────

# 脚本住在 docs\evidence\scripts\ 下, 三层之上是仓库根。
$RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))

# 公开端点(规格 §5.1: 只有配对用的 ping 免鉴权)。与 routes.rs 的 PUBLIC_PATHS 一致。
$PublicPaths = @('/api/v1/ping')

# 受保护端点清单。**优先从生产源码 routes.rs 里读**(防漂移: 新增端点自动进入鉴权矩阵),
# 读不到时用这份内建副本(与 HEAD 45d4d89 的 PROTECTED_PATHS 逐字一致)。
$ProtectedFallback = @(
  '/api/v1/shares'
  '/api/v1/drives'
  '/api/v1/browse'
  '/api/v1/scan'
  '/api/v1/thumb'
  '/api/v1/preview'
  '/api/v1/decisions'
  '/api/v1/move'
)

# 每条受保护端点的"正确方法 + 故意缺参数的探测 + 该探测应有的状态码"。
# 缺参数(400)是**由处理器/提取器自己**回的, 所以它同时是"用对 token 时不是 401"的正对照。
$Probe = @{
  '/api/v1/shares'    = @{ Method = 'GET';  Query = ''; Expect = 200 }
  '/api/v1/drives'    = @{ Method = 'GET';  Query = ''; Expect = 200 }
  '/api/v1/browse'    = @{ Method = 'GET';  Query = ''; Expect = 400 }
  '/api/v1/scan'      = @{ Method = 'GET';  Query = ''; Expect = 400 }
  '/api/v1/thumb'     = @{ Method = 'GET';  Query = ''; Expect = 400 }
  '/api/v1/preview'   = @{ Method = 'GET';  Query = ''; Expect = 400 }
  '/api/v1/decisions' = @{ Method = 'GET';  Query = ''; Expect = 400 }
  '/api/v1/move'      = @{ Method = 'POST'; Query = ''; Json = '{}'; Expect = 400 }
}

# 一张**真的** JPEG(760x520 四象限四色, q70, 4721 字节)。内嵌是为了零依赖:
# 不需要 System.Drawing, 也不需要在你的机器上找一张真照片。
$JpegB64 = @'
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
AAAAAAAAAAAAAAAAAAAAAH/9k=
'@
$JpegBytes = [Convert]::FromBase64String((($JpegB64 -split "`n") -join '').Trim())

# ── 1. 输出 / 断言 / HTTP 工具 ───────────────────────────────────────────────

$script:Pass = 0
$script:Fail = 0
$script:Skip = 0
$script:Warn = 0
$script:Index = 0
$script:Failures = New-Object System.Collections.ArrayList
$script:Warnings = New-Object System.Collections.ArrayList

function Write-Section {
  param([string]$Title)
  Write-Host ''
  Write-Host "── $Title " -ForegroundColor Cyan -NoNewline
  Write-Host ('─' * [Math]::Max(1, 68 - $Title.Length)) -ForegroundColor DarkCyan
}

function Write-Note {
  param([string]$Text, [string]$Color = 'DarkGray')
  Write-Host "        $Text" -ForegroundColor $Color
}

function Write-Warn {
  param([string]$Text)
  $script:Warn++
  $script:Warnings.Add($Text) | Out-Null
  Write-Host "  WARN  $Text" -ForegroundColor Yellow
}

function Check {
  param([string]$Name, [scriptblock]$Body)
  $script:Index++
  $label = '{0,3}. {1}' -f $script:Index, $Name
  try {
    $detail = & $Body
    $script:Pass++
    Write-Host "  PASS  $label" -ForegroundColor Green
    if ($detail) { Write-Note ([string]$detail) }
  } catch {
    $script:Fail++
    $msg = $_.Exception.Message
    $script:Failures.Add("$label -> $msg") | Out-Null
    Write-Host "  FAIL  $label" -ForegroundColor Red
    Write-Host "        $msg" -ForegroundColor Red
  }
}

function Check-Skipped {
  param([string]$Name, [string]$Reason)
  $script:Index++
  $script:Skip++
  Write-Host ('  SKIP  {0,3}. {1}' -f $script:Index, $Name) -ForegroundColor DarkYellow
  Write-Note $Reason 'DarkYellow'
}

function Assert-True {
  param($Condition, [string]$What)
  if (-not $Condition) { throw $What }
}

function Assert-Equal {
  param($Expected, $Actual, [string]$What)
  if ($Expected -ne $Actual) { throw "$What : 期望 [$Expected], 实得 [$Actual]" }
}

# 同一个断言的短名字(两种写法都有人用, 免得读的人以为漏了一条)。
function Assert-Eq {
  param($Expected, $Actual, [string]$What)
  Assert-Equal $Expected $Actual $What
}

function Get-BytesText {
  param([byte[]]$Bytes)
  if ($null -eq $Bytes) { return '<null>' }
  if ($Bytes.Length -eq 0) { return '<空体>' }
  $t = [System.Text.Encoding]::UTF8.GetString($Bytes)
  $t = $t -replace '\s+', ' '
  if ($t.Length -gt 300) { $t = $t.Substring(0, 300) + '…' }
  return $t
}

# 一次 HTTP 请求。**用 HttpWebRequest 而不是 Invoke-WebRequest**:
#   · Windows PowerShell 5.1 的 Invoke-WebRequest 在 4xx/5xx 上抛异常, 想读错误体还得再拆
#     WebException, 绕一圈还是回到这几个 .NET 类型;
#   · -SkipHttpErrorCheck 只有 PS7 有, 用它就把脚本锁死在 PS7;
#   · Invoke-WebRequest 对 Range 这类受限头直接拒绝, 而这里要逐字节比对二进制体。
# 于是统一走 HttpWebRequest: PS 5.1 与 PS 7 行为一致, 状态码/字节/头都拿得到。
function Invoke-Api {
  param(
    [string]$Method = 'GET',
    [string]$Path,
    [string]$UseToken,
    [string]$JsonBody,
    [string]$ContentType = 'application/json',
    [int]$Timeout = $TimeoutSec
  )
  $uri = $script:Base + $Path
  $req = [System.Net.HttpWebRequest]::Create($uri)
  $req.Method = $Method
  $req.Timeout = $Timeout * 1000
  $req.ReadWriteTimeout = $Timeout * 1000
  # 直连: 不走系统/IE 代理(localhost 也可能被代理配置牵连)。
  $req.Proxy = $null
  $req.KeepAlive = $false
  # 别让 .NET 把 4xx 变成异常 —— 契约验证要看的就是 4xx。
  $req.AllowAutoRedirect = $false
  if ($UseToken) { $req.Headers.Add('Authorization', "Bearer $UseToken") }

  # ⚠️ 判空必须用 IsNullOrEmpty: PowerShell 里**没有传**的 [string] 形参是空串 `''`,
  # 而 `'' -ne $null` 是 **true** —— 用 `$null -ne $JsonBody` 会给 GET 也挂上请求体,
  # .NET 直接报 "Cannot send a content-body with this verb-type"(实测踩过)。
  # 空串也该按"没有体"处理(探测用的空 JSON 体没有意义)。
  if (-not [string]::IsNullOrEmpty($JsonBody)) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($JsonBody)
    $req.ContentType = $ContentType
    $req.ContentLength = $bytes.Length
    $stream = $req.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()
  }

  $resp = $null
  try {
    $resp = $req.GetResponse()
  } catch [System.Net.WebException] {
    $resp = $_.Exception.Response
    if ($null -eq $resp) { throw "请求失败($Method $uri): $($_.Exception.Message)" }
  }
  $ms = New-Object System.IO.MemoryStream
  $rs = $resp.GetResponseStream()
  $rs.CopyTo($ms)
  $rs.Close()
  $body = $ms.ToArray()
  $ms.Close()
  $out = [pscustomobject]@{
    Status      = [int]$resp.StatusCode
    ContentType = [string]$resp.ContentType
    Allow       = [string]$resp.Headers['Allow']
    Bytes       = $body
    Text        = [System.Text.Encoding]::UTF8.GetString($body)
    Uri         = $uri
  }
  $resp.Close()
  return $out
}

function Show-Res {
  param($Res)
  if ($null -eq $Res) { return '<无响应>' }
  return "status=$($Res.Status) content-type=[$($Res.ContentType)] body=$(Get-BytesText $Res.Bytes)"
}

function Get-Json {
  param($Res, [string]$What)
  if ([string]::IsNullOrWhiteSpace($Res.Text)) { throw "$What : 响应体是空的($(Show-Res $Res))" }
  $obj = $null
  try { $obj = $Res.Text | ConvertFrom-Json } catch { }
  if ($null -eq $obj) { throw "$What : 响应不是合法 JSON($(Show-Res $Res))" }
  return $obj
}

# 错误体契约(规格 §5.3): 状态码对 + content-type 是 JSON + 体**恰好** {"code":"…"}。
# 三件事一起断言, 而且逐字节比 —— "顺手加个 detail 字段"会在这里当场红。
function Assert-ErrorBody {
  param($Res, [int]$Status, [string]$Code, [string]$What)
  if ($Res.Status -ne $Status) { throw "$What : 期望状态 $Status, 实得 $(Show-Res $Res)" }
  if (-not ($Res.ContentType -like 'application/json*')) {
    throw "$What : content-type 必须是 application/json, 实得 [$($Res.ContentType)]"
  }
  $want = '{"code":"' + $Code + '"}'
  $got = $Res.Text.Trim()
  if ($got -ne $want) { throw "$What : 错误体必须恰好是 $want, 实得 [$got]" }
  $obj = Get-Json $Res $What
  $n = @($obj.PSObject.Properties).Count
  if ($n -ne 1) { throw "$What : 错误体只许有 code 一个字段, 实得有 $n 个($got)" }
}

function Assert-200Json {
  param($Res, [string]$What)
  if ($Res.Status -ne 200) { throw "$What : 期望 200, 实得 $(Show-Res $Res)" }
  if (-not ($Res.ContentType -like 'application/json*')) {
    throw "$What : 期望 JSON, content-type 实得 [$($Res.ContentType)]"
  }
}

function Assert-Jpeg {
  param([byte[]]$Bytes, [string]$What)
  if ($Bytes.Length -lt 512) { throw "$What : 只有 $($Bytes.Length) 字节, 不像一张真图" }
  if ($Bytes[0] -ne 0xFF -or $Bytes[1] -ne 0xD8) {
    throw "$What : 缺 JPEG SOI(FF D8), 前四字节 $([BitConverter]::ToString($Bytes[0..3]))"
  }
  if ($Bytes[$Bytes.Length - 2] -ne 0xFF -or $Bytes[$Bytes.Length - 1] -ne 0xD9) {
    throw "$What : 缺 JPEG EOI(FF D9)"
  }
}

function Get-JpegSize {
  param([byte[]]$Bytes, [string]$What)
  Assert-Jpeg $Bytes $What
  $i = 2
  while ($i + 8 -lt $Bytes.Length) {
    if ($Bytes[$i] -ne 0xFF) { throw "$What : JPEG 结构异常(偏移 $i 不是标记)" }
    $marker = $Bytes[$i + 1]
    if ($marker -eq 0xD9 -or $marker -eq 0xDA) { break }
    if ($marker -eq 0x01 -or ($marker -ge 0xD0 -and $marker -le 0xD7)) { $i += 2; continue }
    $len = ((([int]$Bytes[$i + 2]) -shl 8) + ([int]$Bytes[$i + 3]))
    if ($len -lt 2) { break }
    $isSof = ($marker -ge 0xC0 -and $marker -le 0xCF) -and
             $marker -ne 0xC4 -and $marker -ne 0xC8 -and $marker -ne 0xCC
    if ($isSof) {
      $h = ((([int]$Bytes[$i + 5]) -shl 8) + ([int]$Bytes[$i + 6]))
      $w = ((([int]$Bytes[$i + 7]) -shl 8) + ([int]$Bytes[$i + 8]))
      return [pscustomobject]@{ Width = $w; Height = $h; LongEdge = [Math]::Max($w, $h) }
    }
    $i += 2 + $len
  }
  throw "$What : JPEG 里找不到 SOF 段, 读不出尺寸"
}

function Test-BytesEqual {
  param([byte[]]$A, [byte[]]$B)
  if ($null -eq $A -or $null -eq $B) { return $false }
  if ($A.Length -ne $B.Length) { return $false }
  return ([BitConverter]::ToString($A) -eq [BitConverter]::ToString($B))
}

function Get-UriPath {
  param([string]$Path, [hashtable]$Query)
  if (-not $Query -or $Query.Count -eq 0) { return $Path }
  $parts = @()
  foreach ($k in $Query.Keys) {
    $parts += ("{0}={1}" -f $k, [uri]::EscapeDataString([string]$Query[$k]))
  }
  return ($Path + '?' + ($parts -join '&'))
}

function Get-CacheFootprint {
  param([string]$Root)
  if (-not (Test-Path -LiteralPath $Root)) { return 'missing' }
  $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue)
  $sum = 0L
  foreach ($f in $files) { $sum += $f.Length }
  return ("{0} files / {1} bytes" -f $files.Count, $sum)
}

# ── 2. 环境准备 ──────────────────────────────────────────────────────────────

$script:Base = ''
$script:TokenGen = ''
$script:WorkDir = $null
$script:ShareRoot = $null
$script:OutsideDir = $null
$script:OutsideSibling = $null
$script:CacheDir = $null
$script:DstDir = $null
$script:Photo = $null
$script:MediaPath = $null
$script:ScanPhoto = $null
$script:ThumbBySize = @{}
$script:ConfigPath = $null
$script:ConfigBackup = $null
$script:ConfigExisted = $false
$script:Proc = $null
$script:StartedByUs = $false
$script:HostOut = $null
$script:HostErr = $null
$script:CacheBefore = $null
$LocalAppDataPrev = $env:LOCALAPPDATA
$CachePath = Join-Path $env:LOCALAPPDATA 'image-filter'
$Mode = if ($BaseUrl) { 'attach' } else { 'launch' }
$CanMutate = ($Mode -eq 'launch')     # 只有全自动模式可以在共享根里造/搬文件
$ExactMedia = ($Mode -eq 'launch')    # 只有全自动模式知道源图尺寸(760x520), 能做等值断言
$MediaAllowed = ($Mode -eq 'launch') -or $AllowCacheWrite

function New-FreePort {
  $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
  $l.Start()
  $p = $l.LocalEndpoint.Port
  $l.Stop()
  return $p
}

function New-HexToken {
  $sb = New-Object System.Text.StringBuilder
  for ($i = 0; $i -lt 64; $i++) { [void]$sb.Append('{0:x}' -f (Get-Random -Maximum 16)) }
  return $sb.ToString()
}

function Find-AppExe {
  param([string]$Explicit)
  if ($Explicit) {
    if (-not (Test-Path -LiteralPath $Explicit)) { throw "指定的 -AppExe 不存在: $Explicit" }
    return (Get-Item -LiteralPath $Explicit).FullName
  }
  $cands = @(
    (Join-Path $RepoRoot 'src-tauri\target\debug\image-filter.exe'),
    (Join-Path $RepoRoot 'src-tauri\target\release\image-filter.exe')
  ) | Where-Object { Test-Path -LiteralPath $_ }
  if (@($cands).Count -eq 0) {
    throw ('找不到桌面端 exe。请先构建: cargo build --manifest-path src-tauri\Cargo.toml ' +
           '(或用 -AppExe 指定一个已构建的 image-filter.exe)')
  }
  return (@($cands) | Sort-Object { (Get-Item -LiteralPath $_).LastWriteTime } -Descending)[0]
}

function Get-ConfigPath {
  # 配置落在 %APPDATA%\<tauri identifier>\server-config.json。identifier 从 tauri.conf.json 读,
  # 读不到就用当前值(com.imagefilter.app)。
  $id = 'com.imagefilter.app'
  $conf = Join-Path $RepoRoot 'src-tauri\tauri.conf.json'
  if (Test-Path -LiteralPath $conf) {
    try {
      $j = (Get-Content -LiteralPath $conf -Raw) | ConvertFrom-Json
      if ($j.identifier) { $id = [string]$j.identifier }
    } catch { }
  }
  return (Join-Path (Join-Path $env:APPDATA $id) 'server-config.json')
}

function Start-ServiceHost {
  $exe = Find-AppExe $AppExe
  $exeItem = Get-Item -LiteralPath $exe
  Write-Host "        桌面端: $exe" -ForegroundColor DarkGray
  Write-Host "        构建时间: $($exeItem.LastWriteTime)" -ForegroundColor DarkGray

  # "exe 比源码旧"是必须说出来的风险: 那样验的不是当前代码。
  $newest = $exeItem.LastWriteTime
  $srcDir = Join-Path $RepoRoot 'src-tauri\src'
  if (Test-Path -LiteralPath $srcDir) {
    foreach ($f in (Get-ChildItem -LiteralPath $srcDir -Recurse -File -Include *.rs)) {
      if ($f.LastWriteTime -gt $newest) { $newest = $f.LastWriteTime }
    }
  }
  $confFile = Join-Path $RepoRoot 'src-tauri\tauri.conf.json'
  if ((Test-Path -LiteralPath $confFile) -and ((Get-Item -LiteralPath $confFile).LastWriteTime -gt $newest)) {
    $newest = (Get-Item -LiteralPath $confFile).LastWriteTime
  }
  if ($newest -gt $exeItem.LastWriteTime) {
    Write-Warn ("exe($($exeItem.LastWriteTime)) 比源码($newest)旧 —— 这次验的可能不是当前代码, " +
                "建议先跑 cargo build --manifest-path src-tauri\Cargo.toml")
  }

  # 临时工作区。share 是"共享根", outside 是共享根**外**的真实目录(403 对照),
  # share-lookalike 与共享根同前缀但不是它的子目录(字符串前缀 vs 路径组件的对照)。
  $script:WorkDir = Join-Path $env:TEMP ('imagefilter-verify-' + (Get-Date -Format 'yyyyMMdd-HHmmss') +
                                         '-' + ([guid]::NewGuid().ToString('N').Substring(0, 6)))
  $script:ShareRoot = Join-Path $script:WorkDir 'share'
  $script:OutsideDir = Join-Path $script:WorkDir 'outside'
  $script:OutsideSibling = Join-Path $script:WorkDir 'share-lookalike'
  $script:CacheDir = Join-Path $script:WorkDir 'cache'
  $photos = Join-Path $script:ShareRoot 'photos'
  $script:DstDir = Join-Path $script:ShareRoot 'dst'
  foreach ($d in @($script:ShareRoot, $script:OutsideDir, $script:OutsideSibling,
                   $script:CacheDir, $photos, $script:DstDir)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
  }
  $script:Photo = Join-Path $photos 'a.jpg'
  foreach ($p in @($script:Photo, (Join-Path $photos 'b.jpg'), (Join-Path $photos 'c.jpg'),
                   (Join-Path $script:ShareRoot 'root.jpg'))) {
    [System.IO.File]::WriteAllBytes($p, $JpegBytes)
  }
  [System.IO.File]::WriteAllBytes((Join-Path $script:OutsideDir 'outside.jpg'), $JpegBytes)
  [System.IO.File]::WriteAllText((Join-Path $photos 'note.txt'), 'this is not an image')
  $script:MediaPath = $script:Photo

  $port = New-FreePort
  $script:TokenGen = New-HexToken
  $script:ConfigPath = Get-ConfigPath
  $cfgDir = Split-Path -Parent $script:ConfigPath
  if (-not (Test-Path -LiteralPath $cfgDir)) { New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null }
  if (Test-Path -LiteralPath $script:ConfigPath) {
    $script:ConfigExisted = $true
    $script:ConfigBackup = [System.IO.File]::ReadAllBytes($script:ConfigPath)
  }
  $cfgObj = [ordered]@{
    enabled = $true
    port    = $port
    token   = $script:TokenGen
    shares  = @($script:ShareRoot)
  }
  $cfgJson = ConvertTo-Json -InputObject $cfgObj -Compress
  [System.IO.File]::WriteAllText($script:ConfigPath, $cfgJson, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "        临时配置: $($script:ConfigPath)(原文件已备份, 跑完还原)" -ForegroundColor DarkGray
  Write-Host "        端口 $port / 共享根 $($script:ShareRoot)" -ForegroundColor DarkGray

  # 把服务进程的 LOCALAPPDATA 指到临时目录 → images.rs 的 cache_dir() 走临时根,
  # 于是缩略图/预览/全图缓存**不会**落进用户的 %LOCALAPPDATA%\image-filter。
  # 子进程的 stdout/stderr 也收进临时目录: 服务起不来时, 理由该由**它自己**给出,
  # 而不是让用户对着"没起来"三个字猜。
  $script:HostOut = Join-Path $script:WorkDir 'host-stdout.txt'
  $script:HostErr = Join-Path $script:WorkDir 'host-stderr.txt'
  $env:LOCALAPPDATA = $script:CacheDir
  try {
    $script:Proc = Start-Process -FilePath $exe -PassThru -WindowStyle Minimized `
      -RedirectStandardOutput $script:HostOut -RedirectStandardError $script:HostErr
  } finally {
    $env:LOCALAPPDATA = $LocalAppDataPrev
  }
  $script:StartedByUs = $true
  Write-Host "        服务进程 pid=$($script:Proc.Id)(跑完只结束这一个进程树)" -ForegroundColor DarkGray

  $script:Base = "http://127.0.0.1:$port"
  $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
  $up = $false
  $lastErr = '(还没有发出过请求)'
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    if ($script:Proc.HasExited) {
      throw ("桌面端进程在服务就绪前退出了(exit=$($script:Proc.ExitCode))。子进程输出: " + (Get-HostLogText))
    }
    # 端口被别的软件占住时 `start()` 会 +1 重试(最多 20 次) —— 那时服务**确实在监听**,
    # 只是不在我们预想的端口上。以子进程**实际**的监听端口为准(`Get-NetTCPConnection`,
    # 不去读它的日志文件: 那个文件在子进程活着的时候被它独占)。
    $childPorts = @(Get-ChildListenPorts)
    if ((@($childPorts).Count -eq 1) -and ($childPorts[0] -ne $port)) {
      Write-Note "服务实际监听在 $($childPorts[0])(预想端口 $port 被占, 服务自己 +1 重试过)" 'DarkYellow'
      $port = [int]$childPorts[0]
      $script:Base = "http://127.0.0.1:$port"
    }
    try {
      $r = Invoke-Api -Method GET -Path '/api/v1/ping' -Timeout 3
      if ($r.Status -eq 200) { $up = $true; break }
      $lastErr = "status=$($r.Status)"
    } catch { $lastErr = "$($_.Exception.GetType().Name): $($_.Exception.Message)" }
  }
  if (-not $up) {
    $ports = @(Get-ChildListenPorts)
    # 子进程的输出文件被它自己独占 —— 先把它收掉再读(反正已经起不来了), 这样
    # "为什么没起来"才有一句它自己给的理由。
    Stop-HostProcessTree
    Start-Sleep -Milliseconds 600
    throw ("等了 $BootTimeoutSec 秒, $($script:Base)/api/v1/ping 仍未就绪。最后一次请求: $lastErr ; " +
           "该进程当时监听的端口: [$(($ports) -join ', ')] ; 子进程输出: " + (Get-HostLogText))
  }
  Write-Host "        服务已就绪: $($script:Base)" -ForegroundColor DarkGray
}

# 服务进程**自己**正在监听的 TCP 端口(只查我们启动的那一个 pid; WebView2 是另外的 pid)。
function Get-ChildListenPorts {
  if (-not $script:Proc) { return @() }
  return @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
           Where-Object { $_.OwningProcess -eq $script:Proc.Id } |
           ForEach-Object { [int]$_.LocalPort } |
           Sort-Object -Unique)
}

# 子进程输出的**全部内容**(UTF-8 读 —— 不然 Windows PowerShell 会按 ANSI 读成乱码)。
# 读不出来(文件还被占着)也不许把整次运行弄崩: 那只是一句诊断。
function Get-HostLogText {
  $parts = @()
  foreach ($f in @($script:HostOut, $script:HostErr)) {
    if ($f -and (Test-Path -LiteralPath $f)) {
      $t = ''
      try { $t = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8).Trim() }
      catch { $t = "(读不出来: $($_.Exception.Message))" }
      if ($t) { $parts += ((Split-Path -Leaf $f) + ': ' + ($t -replace '\s+', ' ')) }
    }
  }
  if ($parts.Count -eq 0) { return '(子进程没有任何输出)' }
  return ($parts -join ' | ')
}

function Connect-ExistingService {
  if (-not $Token) { throw '对接模式必须给 -Token(64 位十六进制配对 token)' }
  $b = $BaseUrl.TrimEnd('/')
  if ($b -notmatch '^https?://') { throw "-BaseUrl 必须是 http(s):// 开头, 实得 [$BaseUrl]" }
  $script:Base = $b
  $script:TokenGen = $Token

  $script:WorkDir = Join-Path $env:TEMP ('imagefilter-verify-' + (Get-Date -Format 'yyyyMMdd-HHmmss') +
                                         '-' + ([guid]::NewGuid().ToString('N').Substring(0, 6)))
  $script:OutsideDir = Join-Path $script:WorkDir 'outside'
  $script:OutsideSibling = Join-Path $script:WorkDir 'share-lookalike'
  New-Item -ItemType Directory -Force -Path $script:OutsideDir | Out-Null
  New-Item -ItemType Directory -Force -Path $script:OutsideSibling | Out-Null
  [System.IO.File]::WriteAllBytes((Join-Path $script:OutsideDir 'outside.jpg'), $JpegBytes)

  if ($SharePath) {
    if (-not (Test-Path -LiteralPath $SharePath)) { throw "-SharePath 不存在: $SharePath" }
    $script:ShareRoot = (Get-Item -LiteralPath $SharePath).FullName
  }
  $r = Invoke-Api -Method GET -Path '/api/v1/ping' -Timeout 5
  if ($r.Status -ne 200) { throw "对接失败: $($script:Base)/api/v1/ping 给了 $(Show-Res $r)" }
  Write-Host "        已对接: $($script:Base)" -ForegroundColor DarkGray
  if ($script:ShareRoot) { Write-Host "        共享根(只读): $($script:ShareRoot)" -ForegroundColor DarkGray }
}

# 对接模式: 在共享根里找一张**已有的**照片当媒体检查对象(脚本绝不往共享根里写东西)。
function Find-MediaPhoto {
  param([string]$Root)
  try {
    $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = $Root }) -UseToken $script:TokenGen
    if ($r.Status -eq 200) {
      $photos = @(Get-Json $r 'scan 共享根')
      if ($photos.Count -gt 0) { return [string]$photos[0].path }
    }
    $b = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/browse' @{ path = $Root }) -UseToken $script:TokenGen
    if ($b.Status -eq 200) {
      $j = Get-Json $b 'browse 共享根'
      foreach ($sub in @($j.subfolders)) {
        $r2 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = [string]$sub.path }) -UseToken $script:TokenGen
        if ($r2.Status -eq 200) {
          $p2 = @(Get-Json $r2 'scan 子目录')
          if ($p2.Count -gt 0) { return [string]$p2[0].path }
        }
      }
    }
  } catch { }
  return $null
}

# 结束我们启动的那棵进程树(服务进程 + WebView2 子进程)。
#
# 为什么单独包一层: `$ErrorActionPreference = 'Stop'` 遇上**原生命令写 stderr** 会抛
# NativeCommandError —— taskkill 在"进程已经没了"时会写一行 ERROR 到 stderr, 于是
# "收尾"本身变成一次失败(实测踩过)。这里临时把 EAP 放回 Continue, 只把它的输出丢掉。
# 唯一值得报警的是"我们启动的那个 pid 还活着", 由调用方判断。
function Stop-HostProcessTree {
  if (-not $script:Proc) { return }
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { $null = & taskkill.exe /F /T /PID $script:Proc.Id 2>&1 } catch { }
  finally { $ErrorActionPreference = $prevEap }
  Start-Sleep -Milliseconds 400
  if (-not $script:Proc.HasExited) {
    Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 400
  }
}

function Invoke-Cleanup {
  $problems = @()
  try {
    if ($script:StartedByUs -and $script:Proc) {
      Stop-HostProcessTree
      if (Get-Process -Id $script:Proc.Id -ErrorAction SilentlyContinue) {
        $problems += "服务进程 pid=$($script:Proc.Id) 仍然活着, 请手工结束它"
      }
    }
  } catch { $problems += "停服务: $($_.Exception.Message)" }

  try {
    if ($script:ConfigPath) {
      if ($script:ConfigExisted) {
        [System.IO.File]::WriteAllBytes($script:ConfigPath, $script:ConfigBackup)
      } else {
        Remove-Item -LiteralPath $script:ConfigPath -Force -ErrorAction SilentlyContinue
      }
    }
  } catch { $problems += "还原配置: $($_.Exception.Message)" }

  if ($script:WorkDir -and -not $KeepArtifacts) {
    for ($i = 0; $i -lt 5; $i++) {
      try {
        if (-not (Test-Path -LiteralPath $script:WorkDir)) { break }
        Remove-Item -LiteralPath $script:WorkDir -Recurse -Force -ErrorAction Stop
      } catch { Start-Sleep -Milliseconds 400 }
    }
  }
  return $problems
}

# ── 3. 跑检查 ────────────────────────────────────────────────────────────────

$Fatal = $null
try {
  Write-Host ''
  Write-Host '════════════════════════════════════════════════════════════════════════' -ForegroundColor White
  Write-Host ' 图库服务契约验证 — ImageFilter 桌面端(手机端访问的 HTTP 服务)' -ForegroundColor White
  Write-Host '════════════════════════════════════════════════════════════════════════' -ForegroundColor White
  Write-Host "  模式: $(if ($Mode -eq 'launch') { '全自动(脚本自己启停服务)' } else { '对接已运行的服务(只读)' })" -ForegroundColor Gray

  if (Test-Path -LiteralPath $CachePath) { $script:CacheBefore = Get-CacheFootprint $CachePath }

  if ($Mode -eq 'launch') { Start-ServiceHost } else { Connect-ExistingService }

  Write-Host "  地址: $($script:Base)" -ForegroundColor Gray
  Write-Host "  token: $(($script:TokenGen).Substring(0,8))…(共 $($script:TokenGen.Length) 位)" -ForegroundColor Gray

  # 受保护端点清单: 优先用源码里的真清单(防漂移)。
  $Protected = $ProtectedFallback
  $routesFile = Join-Path $RepoRoot 'src-tauri\src\server\routes.rs'
  if (Test-Path -LiteralPath $routesFile) {
    $txt = Get-Content -LiteralPath $routesFile -Raw
    $m = [regex]::Match($txt, 'PROTECTED_PATHS\s*:\s*&\[&str\]\s*=\s*&\[(?<body>.*?)\];', 'Singleline')
    if ($m.Success) {
      $found = @([regex]::Matches($m.Groups['body'].Value, '"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
      if ($found.Count -gt 0) {
        $Protected = $found
        if ((@($found) -join ',') -ne (@($ProtectedFallback) -join ',')) {
          Write-Warn ('routes.rs 的 PROTECTED_PATHS 与脚本内建副本不同: 源码=' + ($found -join ', ') +
                      ' / 内建=' + ($ProtectedFallback -join ', ') +
                      ' —— 已按源码清单检查, 请把内建副本同步过去')
        }
      }
    }
  } else {
    Write-Warn "读不到 routes.rs, 受保护端点用脚本内建副本($($ProtectedFallback.Count) 条)"
  }
  foreach ($p in $Protected) {
    if (-not $Probe.ContainsKey($p)) { Write-Warn "$p 在内建探测表里没有条目(会用 GET 探测), 请补上它的正确方法" }
  }

  # ════ 1. 公开面与鉴权 ═════════════════════════════════════════════════════
  Write-Section '1. 公开面与鉴权(规格 §5.1 / §5.2)'

  Check 'ping 免 token 就 200, 且给出产品名/版本/requiresToken' {
    $r = Invoke-Api -Method GET -Path '/api/v1/ping'
    Assert-200Json $r 'ping'
    $j = Get-Json $r 'ping'
    Assert-Equal 'ImageFilter' $j.name 'ping.name'
    Assert-Equal $true $j.requiresToken 'ping.requiresToken'
    Assert-True ($j.version -and ([string]$j.version).Length -gt 0) 'ping.version 不能为空'
    Assert-True ($r.Text -notmatch '\\') 'ping 不许泄露任何路径信息(响应里不该有反斜杠)'
    "name=$($j.name) version=$($j.version) requiresToken=$($j.requiresToken)"
  }

  Check 'ping 带错 token 也照常 200(公开端点不受鉴权影响)' {
    $r = Invoke-Api -Method GET -Path '/api/v1/ping' -UseToken 'deadbeefdeadbeef'
    Assert-Equal 200 $r.Status "ping + 错 token 应是 200, 实得 $(Show-Res $r)"
    "status=$($r.Status)"
  }

  Check '未挂载路径 fail-closed: 无 token 401, 正 token 404(不向未配对方泄露端点清单)' {
    $anon = Invoke-Api -Method GET -Path '/api/v1/nope'
    Assert-ErrorBody $anon 401 'unauthorized' '无 token 访问未挂载路径'
    $authed = Invoke-Api -Method GET -Path '/api/v1/nope' -UseToken $script:TokenGen
    Assert-ErrorBody $authed 404 'notFound' '正 token 访问未挂载路径'
    '无 token → 401 {code:unauthorized}; 正 token → 404 {code:notFound}'
  }

  foreach ($p in $Protected) {
    # ⚠️ 局部变量名不许叫 `$probe`: PowerShell 的变量名**大小写不敏感**, 那会和
    # `$Probe` 这张表本身是同一个变量 —— 循环第二次就把表覆盖成单条记录了(实测踩过,
    # 症状是 /drives 也被当成"缺参数应 400")。
    $spec = if ($Probe.ContainsKey($p)) { $Probe[$p] } else { @{ Method = 'GET'; Query = ''; Expect = 400 } }
    $query = if ($spec.ContainsKey('Query')) { $spec['Query'] } else { '' }
    $method = [string]$spec['Method']
    $path = $p + $query
    $wantStatus = [int]$spec['Expect']

    Check "$p : 无 token 与错 token 都是 401 + 恰好 {code:unauthorized}" {
      $a = Invoke-Api -Method $method -Path $path
      Assert-ErrorBody $a 401 'unauthorized' "$p 不带 token"
      $b = Invoke-Api -Method $method -Path $path -UseToken 'not-the-right-token-0000'
      Assert-ErrorBody $b 401 'unauthorized' "$p 带错 token"
      '两次都是 401 + {"code":"unauthorized"}'
    }

    Check "$p : 正 token 时不是 401(缺参数的探测请求回到处理器自己手上)" {
      $json = $null
      if ($spec.ContainsKey('Json')) { $json = [string]$spec['Json'] }
      $r = Invoke-Api -Method $method -Path $path -UseToken $script:TokenGen -JsonBody $json
      if ($r.Status -eq 401) { throw "$p : 正 token 却拿到 401($(Show-Res $r))" }
      if ($r.Status -ne $wantStatus) { throw "$p : 期望 $wantStatus, 实得 $(Show-Res $r)" }
      if ($r.Status -eq 400) { Assert-ErrorBody $r 400 'badRequest' "$p 缺参数" }
      "status=$($r.Status)"
    }
  }

  # 媒体类检查需要一个共享根内**真实存在**的文件。对接模式下在共享根里找一张已有照片
  # (只读, 不往里写东西); 全自动模式下 Start-ServiceHost 已经把它设好了。
  if (-not $script:MediaPath -and $script:ShareRoot) {
    $script:MediaPath = Find-MediaPhoto $script:ShareRoot
  }

  # ════ 2. 残余非 {code} 响应的收口(405/404) ══════════════════════════════
  Write-Section '2. 错方法/未知路径的响应体收口(规格 §5.3: 一律 {code})'

  Check '已挂载端点上的错方法 → 405 + 非空 JSON 体 + 恰好 {code:badRequest} + Allow 头' {
    $cases = @(
      @{ M = 'POST';   P = '/api/v1/shares'; What = 'POST /shares' }
      @{ M = 'DELETE'; P = '/api/v1/thumb'; What = 'DELETE /thumb' }
      @{ M = 'GET';    P = '/api/v1/move'; What = 'GET /move' }
      @{ M = 'PUT';    P = '/api/v1/scan'; What = 'PUT /scan' }
    )
    $seen = @()
    foreach ($c in $cases) {
      $body = $null
      if ($c.M -eq 'POST' -or $c.M -eq 'PUT' -or $c.M -eq 'DELETE') { $body = '{}' }
      $r = Invoke-Api -Method $c.M -Path $c.P -UseToken $script:TokenGen -JsonBody $body
      Assert-ErrorBody $r 405 'badRequest' $c.What
      Assert-True ($r.Bytes.Length -gt 0) "$($c.What) : 405 的体不许是空的"
      if ($c.P -eq '/api/v1/shares') {
        Assert-True ($r.Allow -match 'GET') "$($c.What) : 405 必须保留 Allow 头(它说的是正确的方法), 实得 [$($r.Allow)]"
      }
      $seen += ("$($c.What)=$($r.Status)")
    }
    ($seen -join ', ')
  }

  Check '未挂载路径(正 token)404 也必须带 {code:notFound} 体(不能是空体)' {
    $r = Invoke-Api -Method GET -Path '/api/v1/definitely-not-mounted' -UseToken $script:TokenGen
    Assert-ErrorBody $r 404 'notFound' '未知路径'
    Assert-True ($r.Bytes.Length -gt 0) '404 的体不许是空的'
    "status=404 body=$($r.Text)"
  }

  # ════ 3. 共享根白名单 ════════════════════════════════════════════════════
  Write-Section '3. 共享根白名单(规格 §3.2: 所有吃路径的端点, 没有例外)'

  $outsideJpg = Join-Path $script:OutsideDir 'outside.jpg'

  Check '共享根外的真实路径: scan/browse/thumb/preview/decisions 一律 403 forbiddenPath' {
    $cases = @(
      @{ P = Get-UriPath '/api/v1/scan' @{ path = $script:OutsideDir }; What = 'scan' }
      @{ P = Get-UriPath '/api/v1/browse' @{ path = $script:OutsideDir }; What = 'browse' }
      @{ P = Get-UriPath '/api/v1/thumb' @{ path = $outsideJpg; size = 300 }; What = 'thumb' }
      @{ P = Get-UriPath '/api/v1/preview' @{ path = $outsideJpg; max = 1024 }; What = 'preview' }
      @{ P = Get-UriPath '/api/v1/decisions' @{ paths = $outsideJpg }; What = 'decisions' }
    )
    $seen = @()
    foreach ($c in $cases) {
      $r = Invoke-Api -Method GET -Path $c.P -UseToken $script:TokenGen
      Assert-ErrorBody $r 403 'forbiddenPath' "共享根外($($c.What))"
      $seen += $c.What
    }
    '越界被拒: ' + ($seen -join ', ') + ' ; 根外对照 = ' + $script:OutsideDir
  }

  Check '共享根外: move 的源越界 403, 且文件一个字节都没动' {
    $src = $outsideJpg
    $before = [System.IO.File]::ReadAllBytes($src)
    $dest = if ($CanMutate) { $script:DstDir } else { $script:ShareRoot }
    $body = ConvertTo-Json -InputObject @{ paths = @($src); destDir = $dest } -Compress -Depth 5
    $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody $body
    Assert-ErrorBody $r 403 'forbiddenPath' 'move 源越界'
    Assert-True (Test-Path -LiteralPath $src) '被拒之后源文件必须还在原地'
    Assert-True (Test-BytesEqual $before ([System.IO.File]::ReadAllBytes($src))) '被拒之后源文件必须逐字节不变'
    '源越界 → 403(校验先于任何文件操作), 源文件未被动过'
  }

  Check '共享根外: move 的目标目录越界也被 403(拿正 token 也不许把文件搬出去)' {
    # 目标校验是 move_photos 的**第一步**(先校验 destDir, 再逐条校验源), 所以这里用一个
    # 明确不存在的源也安全: 请求在碰到任何文件之前就已经被 403 挡下。
    $src = if ($CanMutate) { $script:Photo } else { Join-Path $script:ShareRoot 'ifverify-ghost.jpg' }
    $body = ConvertTo-Json -InputObject @{ paths = @($src); destDir = $script:OutsideDir } -Compress -Depth 5
    $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody $body
    Assert-ErrorBody $r 403 'forbiddenPath' 'move 目标越界'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $script:OutsideDir 'a.jpg'))) '共享根外不许出现被搬走的文件'
    if ($CanMutate) { Assert-True (Test-Path -LiteralPath $src) '被拒之后源文件必须还在原地' }
    '目标越界 → 403, 文件未离开共享根'
  }

  if ($script:ShareRoot) {
    Check '共享根**本身**被接受(scan 与 browse 都 200), 末尾分隔符也一样' {
      $r1 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = $script:ShareRoot }) -UseToken $script:TokenGen
      Assert-200Json $r1 'scan 共享根本身'
      $sep = [System.IO.Path]::DirectorySeparatorChar
      $r2 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = ($script:ShareRoot + $sep) }) -UseToken $script:TokenGen
      Assert-Equal 200 $r2.Status "带末尾分隔符的共享根 scan 应 200, 实得 $(Show-Res $r2)"
      $r3 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/browse' @{ path = $script:ShareRoot }) -UseToken $script:TokenGen
      Assert-200Json $r3 'browse 共享根本身'
      '根自身 / 根+分隔符 / browse 根 → 都是 200'
    }

    Check '前缀相似的兄弟目录不许被当成根内(share-lookalike)→ 403' {
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = $script:OutsideSibling }) -UseToken $script:TokenGen
      Assert-ErrorBody $r 403 'forbiddenPath' "$($script:OutsideSibling) 与共享根同前缀但不是它的子目录"
      "「$($script:OutsideSibling)」→ 403"
    }

    Check '路径大小写差异仍被接受(Windows 文件系统语义)' {
      $upper = $script:ShareRoot.ToUpperInvariant()
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = $upper }) -UseToken $script:TokenGen
      Assert-Equal 200 $r.Status "大写形式的共享根应 200, 实得 $(Show-Res $r)"
      "「$upper」→ 200"
    }
  } else {
    Check-Skipped '共享根本身/前缀相似/大小写' '对接模式未给 -SharePath, 没有可用的共享根做正向对照'
  }

  # ════ 4. 请求参数边界 ════════════════════════════════════════════════════
  Write-Section '4. 请求参数边界(规格 §5.2; 按 media.rs 的**实际**契约)'

  # ⚠️ 这里用**真实存在**的共享根内文件, 不用"共享根内但不存在"的路径:
  # `thumb`/`preview` 是**先过白名单再校验参数**的(`resolve_within_shares` 在前,
  # `media::*` 在后), 不存在的路径会在 canonicalize 那一步就变成 404 —— 于是
  # "size=0 应该 400"会被 404 挡住(实测踩过)。而这两条 400 都在**读文件之前**返回
  # (`thumbnail_size()` / `max` 区间检查), 所以用真照片也**不会**写缓存目录。
  $boundsTarget = $script:MediaPath

  if ($boundsTarget) {
    Check 'thumb size < 150 一律 400 badRequest(0/1/32/149)' {
      foreach ($s in @(0, 1, 32, 149)) {
        $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/thumb' @{ path = $boundsTarget; size = $s }) -UseToken $script:TokenGen
        Assert-ErrorBody $r 400 'badRequest' "thumb size=$s"
      }
      'size=0/1/32/149 → 400 {"code":"badRequest"}'
    }

    Check 'preview max 越界([64,5000] 之外)一律 400 badRequest(0/63/5001/100000)' {
      foreach ($m in @(0, 63, 5001, 100000)) {
        $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $boundsTarget; max = $m }) -UseToken $script:TokenGen
        Assert-ErrorBody $r 400 'badRequest' "preview max=$m"
      }
      'max=0/63/5001/100000 → 400 {"code":"badRequest"}'
    }
  } else {
    foreach ($n in @('thumb size < 150 → 400', 'preview max 越界 → 400')) {
      Check-Skipped $n '共享根里没有可用的照片(参数校验在白名单之后, 不存在的路径会先拿 404)'
    }
  }

  # 下面这三条是**提取器级**的拒绝: 它们在处理器跑起来之前就返回, 所以一个不存在的路径
  # 也够用 —— 而"路径不存在时正好给 404"恰好证明 `full=true` 被接受了、1/0/yes 被拒了,
  # 同时一个字节的缓存都不会写(对接模式也能安全跑)。
  $ghost = if ($script:ShareRoot) { Join-Path $script:ShareRoot 'ghost-not-here.jpg' } else { $null }

  if ($ghost) {
    Check 'thumb size 非数字 → 400 badRequest' {
      $r = Invoke-Api -Method GET -Path ('/api/v1/thumb?path=' + [uri]::EscapeDataString($ghost) + '&size=abc') -UseToken $script:TokenGen
      Assert-ErrorBody $r 400 'badRequest' 'thumb size=abc'
      'size=abc → 400 {"code":"badRequest"}'
    }

    Check 'preview max 非数字 → 400 badRequest' {
      $r = Invoke-Api -Method GET -Path ('/api/v1/preview?path=' + [uri]::EscapeDataString($ghost) + '&max=abc') -UseToken $script:TokenGen
      Assert-ErrorBody $r 400 'badRequest' 'preview max=abc'
      'max=abc → 400 {"code":"badRequest"}'
    }

    Check 'preview full 只认 true/false: full=true 被接受, full=1/0/yes 一律 400 badRequest' {
      $r1 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $ghost; max = 1024; full = 'true' }) -UseToken $script:TokenGen
      Assert-Equal 404 $r1.Status ("full=true 应被接受(参数合法后因文件不存在给 404), 实得 $(Show-Res $r1)")
      foreach ($v in @('1', '0', 'yes')) {
        $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $ghost; max = 1024; full = $v }) -UseToken $script:TokenGen
        Assert-ErrorBody $r 400 'badRequest' "preview full=$v"
      }
      'full=true 放行; full=1/0/yes → 400(serde_urlencoded 走 str::parse::<bool>())'
    }
  } else {
    foreach ($n in @('thumb size 非数字', 'preview max 非数字', 'preview full 只认 true/false')) {
      Check-Skipped $n '对接模式未给 -SharePath'
    }
  }

  Check '缺必填参数/坏请求体: 一律 400 + 恰好 {code:badRequest}' {
    $cases = @(
      @{ M = 'GET'; P = '/api/v1/scan'; What = 'scan 缺 path' }
      @{ M = 'GET'; P = '/api/v1/thumb'; What = 'thumb 缺 path' }
      @{ M = 'GET'; P = '/api/v1/preview'; What = 'preview 缺 path' }
      @{ M = 'GET'; P = '/api/v1/decisions'; What = 'decisions 缺 paths' }
    )
    foreach ($c in $cases) {
      $r = Invoke-Api -Method $c.M -Path $c.P -UseToken $script:TokenGen
      Assert-ErrorBody $r 400 'badRequest' $c.What
    }
    $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody '{"paths":[],"destDir":"x"}'
    Assert-ErrorBody $r 400 'badRequest' 'move 空 paths'
    $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody '{ not json'
    Assert-ErrorBody $r 400 'badRequest' 'move 坏 JSON'
    $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody '{"paths":["x"]}'
    Assert-ErrorBody $r 400 'badRequest' 'move 缺 destDir'
    $r = Invoke-Api -Method PUT -Path '/api/v1/decisions' -UseToken $script:TokenGen -JsonBody '[{"rating":3}]'
    Assert-ErrorBody $r 400 'badRequest' 'decisions 缺 path(JSON 体拒绝也走同一条收口)'
    '8 种缺参/坏体 → 400 {"code":"badRequest"}'
  }

  # ════ 5. 正常路径(共享根内) ══════════════════════════════════════════════
  Write-Section '5. 正常路径(共享根内的真数据)'

  Check '/shares 返回配置里的共享根' {
    $r = Invoke-Api -Method GET -Path '/api/v1/shares' -UseToken $script:TokenGen
    Assert-200Json $r '/shares'
    $j = Get-Json $r '/shares'
    Assert-True ($null -ne $j.shares) '/shares 必须有 shares 字段'
    if ($script:ShareRoot) {
      $hit = @(@($j.shares) | Where-Object { $_ -eq $script:ShareRoot })
      Assert-True ($hit.Count -ge 1) "shares 里应包含 $($script:ShareRoot), 实得 $(@($j.shares) -join ', ')"
    }
    'shares = ' + (@($j.shares) -join ', ')
  }

  Check '/drives 返回非空的盘符数组' {
    $r = Invoke-Api -Method GET -Path '/api/v1/drives' -UseToken $script:TokenGen
    Assert-200Json $r '/drives'
    $j = @(Get-Json $r '/drives')
    Assert-True ($j.Count -ge 1) '/drives 不许是空数组'
    "drives = $($j.Count) 个"
  }

  if ($script:ShareRoot) {
    Check '/browse 字段形状与前端一致(path/name/photoCount/hasSubdirs/subfolders)' {
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/browse' @{ path = $script:ShareRoot }) -UseToken $script:TokenGen
      Assert-200Json $r '/browse'
      $j = Get-Json $r '/browse'
      foreach ($k in @('path', 'name', 'photoCount', 'hasSubdirs', 'subfolders')) {
        Assert-True ($null -ne $j.PSObject.Properties[$k]) "/browse 缺字段 $k(实得: $($j.PSObject.Properties.Name -join ','))"
      }
      # 子目录条数是**夹具相关**的: 全自动模式自己造了 photos/dst 两个, 所以能钉死 ≥2;
      # 对接模式下共享根里有什么由用户决定(一个全是照片、没有子目录的根也合法),
      # 所以那里只断言字段在(见上面的循环)。
      if ($Mode -eq 'launch') {
        Assert-True (@($j.subfolders).Count -ge 2) "/browse 应能看到 2 个子目录, 实得 $(@($j.subfolders).Count)"
      }
      "photoCount=$($j.photoCount) subfolders=$(@($j.subfolders).Count)"
    }

    Check '/scan 字段形状(path/fileName/fileSize/isRaw/isVideo/modifiedAt/exif)' {
      $target = if ($Mode -eq 'launch') { Join-Path $script:ShareRoot 'photos' } else { $script:ShareRoot }
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = $target }) -UseToken $script:TokenGen
      Assert-200Json $r '/scan'
      $j = @(Get-Json $r '/scan')
      if ($Mode -eq 'launch') { Assert-Equal 3 $j.Count 'photos 目录里应有 3 张 jpg' }
      else { Assert-True ($j.Count -ge 1) "共享根里应有至少一张照片, 实得 $($j.Count)" }
      foreach ($k in @('path', 'fileName', 'fileSize', 'isRaw', 'isVideo', 'modifiedAt', 'exif')) {
        Assert-True ($null -ne $j[0].PSObject.Properties[$k]) "/scan 缺字段 $k(实得: $($j[0].PSObject.Properties.Name -join ','))"
      }
      Assert-Equal $false $j[0].isRaw '内嵌的 jpg 不该被当成 RAW'
      $script:ScanPhoto = [string]$j[0].path
      "$($j.Count) 张, 第一张 = $($script:ScanPhoto)"
    }

    Check '/scan 返回的 path 能**原样**送回去(手机拿它请求缩略图不许 403/404)' {
      Assert-True ($script:ScanPhoto) '前置检查没拿到 scan 的 path'
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/thumb' @{ path = $script:ScanPhoto; size = 300 }) -UseToken $script:TokenGen
      Assert-Equal 200 $r.Status "回送 scan 的 path 应 200, 实得 $(Show-Res $r)"
      Assert-Jpeg $r.Bytes '回送 path 的缩略图'
      "「$($script:ScanPhoto)」→ 200 image/jpeg($($r.Bytes.Length) 字节)"
    }
  } else {
    Check-Skipped '/browse 与 /scan 的形状' '对接模式未给 -SharePath'
    Check-Skipped '/scan 的 path 原样回送' '对接模式未给 -SharePath'
  }

  # 媒体对象在第 4 节之前就定好了(边界校验也需要它); 这里只做一次兜底。
  if (-not $script:MediaPath -and $script:ShareRoot) {
    $script:MediaPath = Find-MediaPhoto $script:ShareRoot
  }

  if ($script:MediaPath -and $MediaAllowed) {
    $media = $script:MediaPath

    Check 'thumb 档位 150/300/600 都出真 JPEG, 且长边 ≤ 档位' {
      $script:ThumbBySize = @{}
      foreach ($s in @(150, 300, 600)) {
        $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/thumb' @{ path = $media; size = $s }) -UseToken $script:TokenGen
        Assert-Equal 200 $r.Status "thumb size=$s 应 200, 实得 $(Show-Res $r)"
        Assert-True ($r.ContentType -like 'image/jpeg*') "thumb size=$s 的 content-type 应是 image/jpeg, 实得 [$($r.ContentType)]"
        Assert-Jpeg $r.Bytes "thumb size=$s"
        $dim = Get-JpegSize $r.Bytes "thumb size=$s"
        Assert-True ($dim.LongEdge -le $s) "thumb size=$s 的长边 $($dim.LongEdge) 超过了请求值"
        if ($ExactMedia) { Assert-Equal $s $dim.LongEdge "thumb size=$s 的长边(源 760x520 比档位大)" }
        $script:ThumbBySize[$s] = $r.Bytes
      }
      '150/300/600 → 200 image/jpeg, 长边分别 150/300/600'
    }

    Check 'thumb size=599/1000/5000 向下取整(字节与 300/600 **完全相同**), 绝不报错也绝不给更大的图' {
      Assert-True ($script:ThumbBySize -and $script:ThumbBySize.Count -ge 3) '前置检查(档位 150/300/600)没过, 无法比对'
      foreach ($case in @(@{ Req = 599; Want = 300 }, @{ Req = 1000; Want = 600 }, @{ Req = 5000; Want = 600 })) {
        $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/thumb' @{ path = $media; size = $case.Req }) -UseToken $script:TokenGen
        Assert-Equal 200 $r.Status "thumb size=$($case.Req) 应 200(向下取整), 实得 $(Show-Res $r)"
        $dim = Get-JpegSize $r.Bytes "thumb size=$($case.Req)"
        Assert-Equal $case.Want $dim.LongEdge "thumb size=$($case.Req) 应取整到 $($case.Want)"
        Assert-True (Test-BytesEqual $r.Bytes $script:ThumbBySize[$case.Want]) "thumb size=$($case.Req) 的字节必须与 size=$($case.Want) 完全相同(同一份缓存)"
      }
      '599→300, 1000→600, 5000→600, 字节与目标档位逐一相等'
    }

    Check 'thumb 缺 size 时用默认档 300(与 size=300 逐字节相同)' {
      Assert-True ($script:ThumbBySize -and $script:ThumbBySize.Count -ge 3) '前置检查(档位 150/300/600)没过, 无法比对'
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/thumb' @{ path = $media }) -UseToken $script:TokenGen
      Assert-Equal 200 $r.Status "thumb 缺 size 应 200, 实得 $(Show-Res $r)"
      Assert-True (Test-BytesEqual $r.Bytes $script:ThumbBySize[300]) '缺 size 的字节必须与 size=300 完全相同'
      '缺 size → 与 size=300 逐字节相同'
    }

    Check 'preview 长边 ≤ max 且绝不放大; 边界 64/5000 放行; max=64 真的降到 64' {
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $media; max = 2048 }) -UseToken $script:TokenGen
      Assert-Equal 200 $r.Status "preview max=2048 应 200, 实得 $(Show-Res $r)"
      Assert-True ($r.ContentType -like 'image/jpeg*') "preview content-type 应是 image/jpeg, 实得 [$($r.ContentType)]"
      Assert-Jpeg $r.Bytes 'preview max=2048'
      $d1 = Get-JpegSize $r.Bytes 'preview max=2048'
      Assert-True ($d1.LongEdge -le 2048) "preview max=2048 的长边 $($d1.LongEdge) 超了"
      if ($ExactMedia) { Assert-Equal 760 $d1.LongEdge '源只有 760 长边: preview 绝不放大, 长边应仍是 760' }

      # 上限那一档(5000)也必须放行 —— 它是"合法边界", 不是"越界"。
      $r3 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $media; max = 5000 }) -UseToken $script:TokenGen
      Assert-Equal 200 $r3.Status "preview max=5000 应 200, 实得 $(Show-Res $r3)"
      $d3 = Get-JpegSize $r3.Bytes 'preview max=5000'
      Assert-True ($d3.LongEdge -le 5000) "preview max=5000 的长边 $($d3.LongEdge) 超了"
      if ($ExactMedia) { Assert-Equal 760 $d3.LongEdge '5000 只是上限, 不该放大到 5000' }

      $r2 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $media; max = 64 }) -UseToken $script:TokenGen
      Assert-Equal 200 $r2.Status "preview max=64 应 200, 实得 $(Show-Res $r2)"
      $d2 = Get-JpegSize $r2.Bytes 'preview max=64'
      Assert-True ($d2.LongEdge -le 64) "preview max=64 的长边 $($d2.LongEdge) 超了"
      if ($ExactMedia) { Assert-Equal 64 $d2.LongEdge 'preview max=64 的长边必须正好 64' }
      "2048 → $($d1.Width)x$($d1.Height); 5000 → $($d3.Width)x$($d3.Height); 64 → $($d2.Width)x$($d2.Height)"
    }

    Check 'preview full=true 走全解码链也出真 JPEG(full=false 作正对照)' {
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $media; max = 1024; full = 'true' }) -UseToken $script:TokenGen
      Assert-Equal 200 $r.Status "preview full=true 应 200, 实得 $(Show-Res $r)"
      Assert-Jpeg $r.Bytes 'preview full=true'
      $r2 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $media; max = 1024; full = 'false' }) -UseToken $script:TokenGen
      Assert-Equal 200 $r2.Status "preview full=false 应 200, 实得 $(Show-Res $r2)"
      $d = Get-JpegSize $r.Bytes 'preview full=true'
      "full=true → 200($($r.Bytes.Length) 字节, $($d.Width)x$($d.Height)); full=false → 200"
    }

    Check 'decisions 读: 形状(path/rating/label/sidecar), 没有边车时 rating=null' {
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/decisions' @{ paths = $media }) -UseToken $script:TokenGen
      Assert-200Json $r '/decisions 读'
      $j = @(Get-Json $r '/decisions 读')
      Assert-True ($j.Count -ge 1) '/decisions 应逐项返回'
      foreach ($k in @('path', 'rating', 'label', 'sidecar')) {
        Assert-True ($null -ne $j[0].PSObject.Properties[$k]) "/decisions 缺字段 $k(实得: $($j[0].PSObject.Properties.Name -join ','))"
      }
      "path=$($j[0].path) rating=$($j[0].rating) label=$($j[0].label)"
    }

    if ($CanMutate) {
      Check 'decisions 写→读回环: PUT rating=4 落盘成边车, 再读回来是 4' {
        $body = ConvertTo-Json -InputObject @(
          [pscustomobject]@{ path = $media; rating = 4; label = $null; clearLabel = $false }
        ) -Compress -Depth 5
        $r = Invoke-Api -Method PUT -Path '/api/v1/decisions' -UseToken $script:TokenGen -JsonBody $body
        Assert-Equal 200 $r.Status "PUT /decisions 应 200, 实得 $(Show-Res $r)"
        $j = Get-Json $r 'PUT /decisions'
        Assert-Equal 1 $j.written "written 应为 1(响应: $($r.Text))"
        Assert-Equal 0 $j.failed "failed 应为 0(响应: $($r.Text))"
        $r2 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/decisions' @{ paths = $media }) -UseToken $script:TokenGen
        $j2 = @(Get-Json $r2 '/decisions 读回')[0]
        Assert-Equal 4 $j2.rating '读回来的 rating 必须是刚写进去的 4'
        "written=1, 读回 rating=$($j2.rating), sidecar=$($j2.sidecar)"
      }
    } else {
      Check-Skipped 'decisions 写→读回环' '对接模式只读: 不往共享根里写边车'
    }
  } else {
    $why = if (-not $script:ShareRoot) { '对接模式未给 -SharePath' }
           elseif (-not $MediaAllowed) { '对接模式默认不写服务端缓存目录(加 -AllowCacheWrite 才跑)' }
           else { '共享根里找不到可用的照片' }
    foreach ($n in @('thumb 档位 150/300/600 真 JPEG', 'thumb 向下取整字节比对', 'thumb 缺 size 默认 300',
                     'preview 尺寸/不放大', 'preview full=true', 'decisions 读形状', 'decisions 写→读回环')) {
      Check-Skipped $n $why
    }
  }

  # ════ 6. 移动回环 / 404 / 422 ════════════════════════════════════════════
  Write-Section '6. 移动回环 / 404 / 422(安全的那几条)'

  if ($CanMutate) {
    Check '/move 回环: 照片 + 同名边车一起搬到共享根内的目标目录(逐项 moved)' {
      $photos = Join-Path $script:ShareRoot 'photos'
      $src = Join-Path $photos 'm1.jpg'
      [System.IO.File]::WriteAllBytes($src, $JpegBytes)
      $sidecar = Join-Path $photos 'm1.xmp'
      [System.IO.File]::WriteAllText($sidecar, '<x:xmpmeta xmlns:x="adobe:ns:meta/"/>')
      $body = ConvertTo-Json -InputObject @{ paths = @($src); destDir = $script:DstDir } -Compress -Depth 5
      $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody $body
      Assert-Equal 200 $r.Status "move 应 200, 实得 $(Show-Res $r)"
      $j = Get-Json $r 'move'
      Assert-Equal 1 $j.moved "moved 应为 1(响应: $($r.Text))"
      Assert-Equal 0 $j.failed "failed 应为 0(响应: $($r.Text))"
      Assert-Equal 0 $j.conflict "conflict 应为 0(响应: $($r.Text))"
      Assert-Eq 'moved' ([string]$j.items[0].status) 'items[0].status'
      Assert-True (-not (Test-Path -LiteralPath $src)) '搬完之后源照片必须不在原位置'
      Assert-True (Test-Path -LiteralPath (Join-Path $script:DstDir 'm1.jpg')) '目标位置必须有照片'
      Assert-True (Test-Path -LiteralPath (Join-Path $script:DstDir 'm1.xmp')) '同名边车必须跟着走'
      Assert-True (-not (Test-Path -LiteralPath $sidecar)) '边车不该留在原位置'
      Assert-Equal 0 (@($j.items[0].pendingSidecars).Count) 'pendingSidecars 应为空数组(不许是 null)'
      "moved=1, dest=$($j.items[0].dest), pendingSidecars=[]"
    }

    Check '/move 目标同名: HTTP 200 + 逐项 conflict=1(不是 409), 且**一个字节都没覆盖**' {
      $photos = Join-Path $script:ShareRoot 'photos'
      $src = Join-Path $photos 'm2.jpg'
      [System.IO.File]::WriteAllBytes($src, $JpegBytes)
      $existing = Join-Path $script:DstDir 'm2.jpg'
      $marker = [System.Text.Encoding]::UTF8.GetBytes('EXISTING-CONTENT-MUST-NOT-BE-OVERWRITTEN')
      [System.IO.File]::WriteAllBytes($existing, $marker)

      $body = ConvertTo-Json -InputObject @{ paths = @($src); destDir = $script:DstDir } -Compress -Depth 5
      $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody $body
      Assert-Equal 200 $r.Status ('目标同名时实现给的是 200 + 逐项 conflict(计划稿散文里写的 409 在本 build 里不可达); ' +
                                  "实得 $(Show-Res $r)")
      $j = Get-Json $r 'move 冲突'
      Assert-Equal 1 $j.conflict "conflict 应为 1(响应: $($r.Text))"
      Assert-Equal 0 $j.moved "moved 应为 0(响应: $($r.Text))"
      Assert-Eq 'conflict' ([string]$j.items[0].status) 'items[0].status'
      Assert-True (Test-Path -LiteralPath $src) '冲突时源文件必须原地不动'
      Assert-True (Test-BytesEqual $marker ([System.IO.File]::ReadAllBytes($existing))) '目标文件绝不许被覆盖'
      'HTTP 200 + conflict=1 + status=conflict + 目标逐字节未变 + 源仍在'
    }

    Check '/move 边界: 目录当源 → 400 badRequest(只搬文件, 不搬目录树)' {
      $body = ConvertTo-Json -InputObject @{ paths = @($script:DstDir); destDir = (Join-Path $script:ShareRoot 'photos') } -Compress -Depth 5
      $r = Invoke-Api -Method POST -Path '/api/v1/move' -UseToken $script:TokenGen -JsonBody $body
      Assert-ErrorBody $r 400 'badRequest' 'move 目录当源'
      '目录当源 → 400 {"code":"badRequest"}'
    }
  } else {
    foreach ($n in @('/move 回环(照片+边车)', '/move 目标同名 conflict', '/move 目录当源')) {
      Check-Skipped $n '对接模式只读: 不在共享根里造/搬文件(那会动到别人真实的照片)'
    }
  }

  if ($script:ShareRoot) {
    Check '共享根内: 不存在的目录 scan → 404 notFound(不是空数组); 不存在的文件 thumb → 404' {
      $missing = Join-Path $script:ShareRoot 'no-such-dir'
      $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/scan' @{ path = $missing }) -UseToken $script:TokenGen
      Assert-ErrorBody $r 404 'notFound' '共享根内不存在的目录(必须 404, 不能是空数组)'
      $ghostFile = Join-Path $script:ShareRoot 'ghost-photo.jpg'
      $r2 = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/thumb' @{ path = $ghostFile; size = 300 }) -UseToken $script:TokenGen
      Assert-ErrorBody $r2 404 'notFound' '共享根内不存在的照片 thumb'
      '不存在的目录 → 404; 不存在的文件 → 404'
    }

    if ($MediaAllowed) {
      Check '共享根内"存在但本 build 解不了"的文件 → 422 unsupportedForm(不是 notFound, 也不是 400)' {
        $note = Join-Path $script:ShareRoot 'ifverify-note.txt'
        [System.IO.File]::WriteAllText($note, 'not an image at all')
        $r = Invoke-Api -Method GET -Path (Get-UriPath '/api/v1/preview' @{ path = $note; max = 1024 }) -UseToken $script:TokenGen
        Assert-ErrorBody $r 422 'unsupportedForm' '非图片文件的 preview(422 分支)'
        '非图片文件 → 422 {"code":"unsupportedForm"}'
      }
    } else {
      Check-Skipped '422 unsupportedForm' '对接模式默认不写服务端缓存目录(加 -AllowCacheWrite 才跑)'
    }
  } else {
    Check-Skipped '404(不存在的目录/文件)' '对接模式未给 -SharePath'
    Check-Skipped '422 unsupportedForm' '对接模式未给 -SharePath'
  }

  # ════ 7. 副作用与未触达的分支 ═══════════════════════════════════════════
  Write-Section '7. 副作用与未触达的分支'

  if ($Mode -eq 'launch') {
    Check '用户的 %LOCALAPPDATA%\image-filter 没被这次验证碰过(文件数/总字节完全不变)' {
      $after = if (Test-Path -LiteralPath $CachePath) { Get-CacheFootprint $CachePath } else { 'missing' }
      $before = if ($script:CacheBefore) { $script:CacheBefore } else { 'missing' }
      Assert-Equal $before $after "验证前后 $CachePath 的(文件数/总字节)必须不变"
      if ($before -eq 'missing') { '该目录本来就不存在, 现在仍不存在' }
      else { "$CachePath = $after(与验证前一致)" }
    }
  } else {
    Check-Skipped '用户的 %LOCALAPPDATA%\image-filter 未被触碰' '对接模式下服务端的缓存目录由被对接的进程决定, 脚本控制不了'
  }

  Write-Note '423 busy / 409 conflict 的可达性(只登记, 不断言):' 'DarkCyan'
  Write-Note '· 409 conflict: 本 build 里没有任何请求路径会产生它 —— /move 的"目标同名"是 200 + 逐项' 'DarkCyan'
  Write-Note '  conflict 计数(第 6 节验过), /decisions 的写失败在 routes.rs 里被映射成 500 internal。' 'DarkCyan'
  Write-Note '· 423 busy: 只在 get_full_image 返回 superseded 的那个竞态里出现(要并发全解码同一张 RAW);' 'DarkCyan'
  Write-Note '  没有夹具就造不出来, 强制造它要并发 + 真 RAW, 收益不抵风险 —— 所以这里只登记。' 'DarkCyan'
  Write-Note '· 空 token / 长度≠64 的 token 一律 fail-closed: 由 cargo test 的 check_auth 单测钉住。' 'DarkCyan'
}
catch {
  $Fatal = $_.Exception.Message
  Write-Host ''
  Write-Host "  FATAL $Fatal" -ForegroundColor Red
  $script:Failures.Add("FATAL -> $Fatal") | Out-Null
  $script:Fail++
}
finally {
  Write-Host ''
  Write-Host '── 清理 ' -ForegroundColor Cyan -NoNewline
  Write-Host ('─' * 60) -ForegroundColor DarkCyan
  $cleanupProblems = Invoke-Cleanup
  if ($script:ConfigPath) {
    if (Test-Path -LiteralPath $script:ConfigPath) {
      $note = if ($script:ConfigExisted) { '已按备份逐字节还原' } else { '本次新建的那份已删除' }
      Write-Note "server-config.json: $note"
    } else {
      Write-Note 'server-config.json: 本次没有改动过它'
    }
  }
  if ($script:WorkDir) {
    if ($KeepArtifacts) { Write-Note "-KeepArtifacts: 临时目录保留在 $($script:WorkDir)" }
    elseif (Test-Path -LiteralPath $script:WorkDir) { Write-Note "临时目录未能完全删除(可能有句柄占用): $($script:WorkDir)" }
    else { Write-Note "临时目录已删除: $($script:WorkDir)" }
  }
  if ($script:StartedByUs -and $script:Proc) { Write-Note "服务进程 pid=$($script:Proc.Id) 已结束" }
  if ($cleanupProblems.Count -gt 0) { foreach ($p in $cleanupProblems) { Write-Warn $p } }
}

# ── 4. 汇总 ──────────────────────────────────────────────────────────────────

Write-Host ''
Write-Host '════════════════════════════════════════════════════════════════════════' -ForegroundColor White
if ($script:Fail -eq 0) {
  Write-Host ("  结果: 全部通过 —— 通过 {0} / 失败 0 / 跳过 {1} / 警告 {2}" -f $script:Pass, $script:Skip, $script:Warn) -ForegroundColor Green
} else {
  Write-Host ("  结果: 有失败 —— 通过 {0} / 失败 {1} / 跳过 {2} / 警告 {3}" -f $script:Pass, $script:Fail, $script:Skip, $script:Warn) -ForegroundColor Red
}
foreach ($f in $script:Failures) { Write-Host "    FAIL $f" -ForegroundColor Red }
foreach ($w in $script:Warnings) { Write-Host "    WARN $w" -ForegroundColor Yellow }
Write-Host '════════════════════════════════════════════════════════════════════════' -ForegroundColor White

if ($script:Fail -gt 0) { exit 1 }
exit 0
