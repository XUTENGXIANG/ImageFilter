# =============================================================================
#  LrC probe script  (ImageFilter <> Lightroom Classic bridge, exploration only)
#
#  Why: settle three unknowns BEFORE writing any product code
#    1. Where LrC is installed (registry / standard dir / uninstall info /
#       App Paths / shell file association)
#    2. Whether LrC is currently running, and its window title
#    3. [requires -LaunchTest] Whether passing a FOLDER as a command line
#       argument to lightroom.exe opens the Import dialog (the one thing that
#       could not be answered by research and must be measured).
#
#  Safety: read-only registry / directory / process inspection, EXCEPT the
#          optional launch test, which starts Lightroom but never imports
#          anything automatically and by default points at an empty temp dir.
#
#  NOTE: deliberately pure ASCII (no BOM issues in Windows PowerShell 5.1) and
#        no backtick line continuations combined with -f (PS 5.1 parse bug).
#
#  Usage:
#    powershell -File .\probe-lrc.ps1                     # read-only only
#    powershell -File .\probe-lrc.ps1 -LaunchTest         # + launch test
#    powershell -File .\probe-lrc.ps1 -LaunchTest -TestDir 'F:\_lrc_probe'
# =============================================================================

[CmdletBinding()]
param(
    [switch]$LaunchTest,
    [string]$TestDir,
    [int]$WaitSeconds = 20,
    [string]$OutFile = ''
)

$ErrorActionPreference = 'Continue'

# Windows PowerShell 5.1 defaults the console to the ANSI code page, which
# silently drops non-ASCII characters (Chinese paths, Adobe version keys) when
# output is redirected. Force UTF-8 so nothing gets eaten on the way out.
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$script:report = New-Object System.Collections.Generic.List[string]

function Say([string]$text) {
    Write-Host $text
    $script:report.Add($text)
}
function Section([string]$title) {
    Say ''
    Say ('=' * 78)
    Say ('== ' + $title)
    Say ('=' * 78)
}

if (-not $OutFile) { $OutFile = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'lrc-probe-report.txt' }

Say 'LrC probe report'
Say ('time      : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
Say ('machine   : ' + $env:COMPUTERNAME)
Say ('user      : ' + $env:USERNAME)
Say ('psVersion : ' + $PSVersionTable.PSVersion.ToString())
Say ('edition   : ' + $PSVersionTable.PSEdition)
Say ('language  : ' + $ExecutionContext.SessionState.LanguageMode)
Say ('launchTest: ' + [bool]$LaunchTest)

# -----------------------------------------------------------------------------
Section '1. Is LrC running? window titles'
# -----------------------------------------------------------------------------
$lrcProcs = @(Get-Process -Name 'lightroom' -ErrorAction SilentlyContinue)
if ($lrcProcs.Count -eq 0) {
    Say 'lightroom.exe : NOT running'
} else {
    foreach ($p in $lrcProcs) {
        $title = ''
        try { $title = $p.MainWindowTitle } catch { }
        $path = ''
        try { $path = $p.Path } catch { }
        Say ('lightroom.exe : pid=' + $p.Id + " started=" + $p.StartTime + " window='" + $title + "' path=" + $path)
    }
}

foreach ($name in @('Adobe Lightroom', 'Adobe Desktop Service', 'Creative Cloud', 'node')) {
    $found = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
    if ($found.Count -gt 0 -and $name -ne 'node') {
        Say ('related proc  : ' + $name + ' x' + $found.Count)
    }
}

# -----------------------------------------------------------------------------
Section '2. Install path: Adobe registry keys'
# -----------------------------------------------------------------------------
$regRoots = @(
    'HKLM:\SOFTWARE\Adobe',
    'HKLM:\SOFTWARE\WOW6432Node\Adobe',
    'HKCU:\SOFTWARE\Adobe'
)
$regHitCount = 0
foreach ($root in $regRoots) {
    if (-not (Test-Path $root)) { Say '  (absent) ' + $root; continue }
    Say '  (exists) ' + $root
    $kids = @(Get-ChildItem $root -ErrorAction SilentlyContinue)
    foreach ($kid in $kids) {
        if ($kid.PSChildName -notmatch 'Lightroom') { continue }
        $regHitCount = $regHitCount + 1
        Say '    * ' + $kid.PSChildName
        $props = Get-ItemProperty $kid.PSPath -ErrorAction SilentlyContinue
        if ($props) {
            foreach ($prop in $props.PSObject.Properties) {
                if ($prop.Name -like 'PS*') { continue }
                if ($prop.Name -match 'path|Path|Install|install|Location|location') {
                    Say '        ' + $prop.Name + ' = ' + $prop.Value
                }
            }
        }
        $subs = @(Get-ChildItem $kid.PSPath -ErrorAction SilentlyContinue)
        foreach ($sub in $subs) {
            Say '        subkey: ' + $sub.PSChildName
            $p2 = Get-ItemProperty $sub.PSPath -ErrorAction SilentlyContinue
            if ($p2) {
                foreach ($prop in $p2.PSObject.Properties) {
                    if ($prop.Name -like 'PS*') { continue }
                    if ($prop.Name -match 'path|Path|Install|install|Location|location') {
                        Say '            ' + $prop.Name + ' = ' + $prop.Value
                    }
                }
            }
        }
    }
}
if ($regHitCount -eq 0) { Say '  (no Lightroom subkey found under the Adobe keys above)' }

# -----------------------------------------------------------------------------
Section '3. Install path: dirs + App Paths + uninstall + associations'
# -----------------------------------------------------------------------------
$candidates = New-Object System.Collections.Generic.List[string]
$lightroomExe = $null

$adobeDirs = @("$env:ProgramFiles\Adobe", "${env:ProgramFiles(x86)}\Adobe")
foreach ($d in $adobeDirs) {
    if (-not (Test-Path $d)) { continue }
    $subs = @(Get-ChildItem $d -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'Lightroom' })
    foreach ($sub in $subs) {
        Say '  adobe dir     : ' + $sub.FullName
        $exe = Join-Path $sub.FullName 'lightroom.exe'
        if (Test-Path $exe) {
            $candidates.Add($exe)
            Say '    -> exe      : ' + $exe
            $vi = (Get-Item $exe -ErrorAction SilentlyContinue).VersionInfo
            if ($vi) { Say ('       fileVer  : ' + $vi.FileVersion + ' / productVer ' + $vi.ProductVersion) }
        }
    }
}

foreach ($hive in @('HKLM:', 'HKCU:')) {
    foreach ($wow in @('', 'WOW6432Node\')) {
        $ap = $hive + '\SOFTWARE\' + $wow + 'Microsoft\Windows\CurrentVersion\App Paths\lightroom.exe'
        if (Test-Path $ap) {
            $v = (Get-ItemProperty $ap -ErrorAction SilentlyContinue).'(default)'
            Say ('  app path      : ' + $ap + ' -> ' + $v)
            if ($v) { $candidates.Add(($v -replace '"', '')) }
        }
    }
}

$uninstallRoots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
)
foreach ($root in $uninstallRoots) {
    if (-not (Test-Path $root)) { continue }
    $kids = @(Get-ChildItem $root -ErrorAction SilentlyContinue)
    foreach ($kid in $kids) {
        $p = Get-ItemProperty $kid.PSPath -ErrorAction SilentlyContinue
        if ($p -and $p.DisplayName -match 'Lightroom') {
            $loc = [string]$p.InstallLocation
            Say '  uninstall     : ' + $p.DisplayName + ' | ' + $p.DisplayVersion + ' | InstallLocation=' + $loc
            if ($loc) {
                $exe = Join-Path $loc 'lightroom.exe'
                if (Test-Path $exe) { $candidates.Add($exe) }
            }
        }
    }
}

foreach ($ext in @('.lrcat', '.lrprev', '.lrplugin', '.lrtemplate')) {
    $progId = (Get-ItemProperty ('HKLM:\SOFTWARE\Classes\' + $ext) -ErrorAction SilentlyContinue).'(default)'
    if (-not $progId) { $progId = (Get-ItemProperty ('HKCU:\SOFTWARE\Classes\' + $ext) -ErrorAction SilentlyContinue).'(default)' }
    if ($progId) {
        Say ('  assoc         : ' + $ext + ' -> ' + $progId)
        foreach ($verb in @('open', 'Open')) {
            $key = 'HKLM:\SOFTWARE\Classes\' + $progId + '\shell\' + $verb + '\command'
            $cmd = (Get-ItemProperty $key -ErrorAction SilentlyContinue).'(default)'
            if (-not $cmd) {
                $key = 'HKCU:\SOFTWARE\Classes\' + $progId + '\shell\' + $verb + '\command'
                $cmd = (Get-ItemProperty $key -ErrorAction SilentlyContinue).'(default)'
            }
            if ($cmd) { Say ('    ' + $verb + ' cmd     : ' + $cmd) }
        }
    }
}

$uniq = @($candidates | Sort-Object -Unique)
Say ''
Say ('  candidate exe count : ' + $uniq.Count)
foreach ($c in $uniq) { Say ('    [exe] ' + $c) }
foreach ($c in $uniq) { if (Test-Path $c) { $lightroomExe = $c; break } }

# -----------------------------------------------------------------------------
Section '4. Per-user Lightroom data dirs (clues for watcher / preferences)'
# -----------------------------------------------------------------------------
$userDirs = @(
    "$env:APPDATA\Adobe\Lightroom",
    "$env:LOCALAPPDATA\Adobe\Lightroom",
    "$env:USERPROFILE\Pictures\Lightroom",
    "$env:USERPROFILE\Documents\Lightroom"
)
foreach ($d in $userDirs) {
    if (Test-Path $d) {
        Say '  exists : ' + $d
        $kids = @(Get-ChildItem $d -ErrorAction SilentlyContinue | Select-Object -First 25)
        foreach ($k in $kids) { Say '           ' + $k.Name }
    } else {
        Say '  absent : ' + $d
    }
}

Say ''
Say '  --- searching readable text files in Lightroom settings for auto-import keys ---'
$settingsDirs = @("$env:APPDATA\Adobe\Lightroom\Preferences", "$env:APPDATA\Adobe\Lightroom")
$hits = 0
foreach ($sd in $settingsDirs) {
    if (-not (Test-Path $sd)) { continue }
    $files = @(Get-ChildItem $sd -File -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Length -lt 2MB })
    foreach ($f in $files) {
        $isText = $false
        try {
            $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
            if ($bytes.Length -gt 0) {
                $n = [Math]::Min(2047, $bytes.Length - 1)
                $hasNul = $false
                for ($i = 0; $i -le $n; $i++) { if ($bytes[$i] -eq 0) { $hasNul = $true; break } }
                $isText = -not $hasNul
            }
        } catch { }
        if (-not $isText) { continue }
        try {
            $content = Get-Content $f.FullName -Raw -ErrorAction SilentlyContinue
            if ($content -match 'autoImport|AutoImport|auto_import|watchFolder|WatchedFolder|WatchFolder') {
                $hits = $hits + 1
                Say '    HIT in ' + $f.FullName
            }
        } catch { }
    }
}
if ($hits -eq 0) { Say '    (no text file mentions auto-import keys; it is likely in the binary .agprefs)' }

# -----------------------------------------------------------------------------
Section '5. Live test: pass a folder to lightroom.exe'
# -----------------------------------------------------------------------------
if (-not $LaunchTest) {
    Say 'SKIPPED (no -LaunchTest)'
    Say ''
    Say 'This is the only item that must be measured, not researched:'
    Say 'does "lightroom.exe <folder>" open the Import dialog with that source?'
    Say ''
    Say 'Re-run with the switch when you are ready to launch Lightroom:'
    Say '    powershell -File .\probe-lrc.ps1 -LaunchTest'
    Say ''
    Say 'It starts Lightroom (can be slow) but never imports by itself.'
    Say 'The default target is an empty temp folder; nothing of yours is touched.'
} else {
    if (-not $lightroomExe) {
        Say 'ABORT: lightroom.exe not found; cannot run the live test.'
    } else {
        if (-not $TestDir) {
            $TestDir = Join-Path $env:TEMP ('lrc_probe_' + (Get-Random))
            New-Item -ItemType Directory -Path $TestDir -Force | Out-Null
            Set-Content -Path (Join-Path $TestDir 'lrc-probe-marker.txt') -Value 'ImageFilter LrC probe marker' -Encoding UTF8
            Say ('created empty test dir: ' + $TestDir)
        } else {
            if (-not (Test-Path $TestDir)) { New-Item -ItemType Directory -Path $TestDir -Force | Out-Null }
            Say ('using given test dir: ' + $TestDir)
        }

        $before = @(Get-Process -Name 'lightroom' -ErrorAction SilentlyContinue)
        Say ('lightroom.exe processes before: ' + $before.Count)
        Say ''

        # NOTE: use .NET Start with UseShellExecute so the argument is passed
        # exactly as a folder path, and a shell verb can be used if a plain
        # process start is refused.
        $argLine = '"' + $TestDir + '"'
        Say ('executing: Start-Process -FilePath "' + $lightroomExe + '" -ArgumentList ' + $argLine)
        $launched = $false
        try {
            $proc = Start-Process -FilePath $lightroomExe -ArgumentList $argLine -PassThru -ErrorAction Stop
            Say ('Start-Process returned pid=' + $proc.Id)
            $launched = $true
        } catch {
            Say ('Start-Process failed: ' + $_.Exception.Message)
        }
        if (-not $launched) {
            try {
                $shell = New-Object -ComObject Shell.Application
                $folder = $shell.Namespace((Split-Path -Parent $TestDir))
                $item = $folder.ParseName((Split-Path -Leaf $TestDir))
                $item.InvokeVerb('open')
                Say 'fell back to Shell.Application InvokeVerb(open)'
                $launched = $true
            } catch {
                Say ('shell fallback failed: ' + $_.Exception.Message)
            }
        }

        Say ''
        Say ('sampling lightroom.exe window titles for ' + $WaitSeconds + 's ...')
        $start = Get-Date
        $seen = @{}
        while (((Get-Date) - $start).TotalSeconds -lt $WaitSeconds) {
            Start-Sleep -Seconds 2
            $elapsed = [int]((Get-Date) - $start).TotalSeconds
            $procs = @(Get-Process -Name 'lightroom' -ErrorAction SilentlyContinue)
            foreach ($p in $procs) {
                $title = ''
                try { $title = $p.MainWindowTitle } catch { }
                if ($title) {
                    $key = [string]$p.Id + '|' + $title
                    if (-not $seen.ContainsKey($key)) {
                        $seen[$key] = $true
                        Say ('  t+' + $elapsed + 's pid=' + $p.Id + " window='" + $title + "'")
                    }
                }
            }
        }

        Say ''
        $after = @(Get-Process -Name 'lightroom' -ErrorAction SilentlyContinue)
        Say ('lightroom.exe processes after: ' + $after.Count)
        foreach ($p in $after) {
            $title = ''
            try { $title = $p.MainWindowTitle } catch { }
            Say ('  pid=' + $p.Id + " window='" + $title + "'")
        }

        Say ''
        Say '--- how to read this ---'
        Say 'window title mentions Import        -> mode 2 (command line opens Import) WORKS'
        Say 'only Library / other titles appear  -> the arg does not trigger Import; need another way'
        Say 'no window at all                    -> start failed, or an existing instance swallowed it'
        Say ''
        Say 'If the Import dialog did open, check by eye whether the source panel shows the test dir.'
        Say 'Then just Cancel it - do not click Import.'
        Say ('test dir: ' + $TestDir)
        Say ('cleanup : Remove-Item -Recurse -Force "' + $TestDir + '"')
    }
}

# -----------------------------------------------------------------------------
Section 'SUMMARY'
# -----------------------------------------------------------------------------
$exeText = '<not found>'
if ($lightroomExe) { $exeText = $lightroomExe }
Say ('lightroom.exe path : ' + $exeText)
Say ('running count      : ' + @(Get-Process -Name 'lightroom' -ErrorAction SilentlyContinue).Count)
if ($LaunchTest) { Say 'launch test        : DONE (see section 5 window titles)' } else { Say 'launch test        : SKIPPED' }

try {
    $text = ($script:report -join [Environment]::NewLine)
    [System.IO.File]::WriteAllText($OutFile, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ''
    Write-Host ('report written to: ' + $OutFile) -ForegroundColor Green
} catch {
    Write-Host ('report write failed: ' + $_.Exception.Message) -ForegroundColor Yellow
}
