$ErrorActionPreference = 'Stop'
$d  = 'F:\壁纸'
$sc = Join-Path $d '20251115-DSC07500-已增强-降噪.xmp'

# 1) keep a byte-exact copy of the original sidecar
Copy-Item -LiteralPath $sc -Destination 'A:\tenent\_probe\orig-07500.xmp' -Force

$orig = [System.IO.File]::ReadAllText($sc, (New-Object System.Text.UTF8Encoding($false)))
$needle = '   xmp:Rating="5"/>'
$repl   = '   xmp:Rating="5"' + "`n" + '   xmlns:ifx="urn:test"' + "`n" + '   ifx:note="keep-me"/>'

if ($orig.IndexOf($needle) -lt 0) { Write-Host 'NEEDLE NOT FOUND'; exit 1 }
$new = $orig.Replace($needle, $repl)
if ($new -eq $orig) { Write-Host 'NO CHANGE'; exit 1 }

[System.IO.File]::WriteAllText($sc, $new, (New-Object System.Text.UTF8Encoding($false)))
Copy-Item -LiteralPath $sc -Destination 'A:\tenent\_probe\staged-07500.xmp' -Force

Write-Host "orig len=$($orig.Length)  new len=$($new.Length)"
Write-Host "orig md5=$((Get-FileHash -LiteralPath 'A:\tenent\_probe\orig-07500.xmp' -Algorithm MD5).Hash)"
Write-Host "staged md5=$((Get-FileHash -LiteralPath $sc -Algorithm MD5).Hash)"
Write-Host '--- staged content ---'
Write-Host ([System.IO.File]::ReadAllText($sc, (New-Object System.Text.UTF8Encoding($false))))
Write-Host '--- staged content end ---'
$b = [System.IO.File]::ReadAllBytes($sc)
Write-Host "staged bytes=$($b.Length) last6=$(($b[($b.Length-6)..($b.Length-1)] | ForEach-Object { $_.ToString('x2') }) -join ' ')"
