$WshShell = New-Object -ComObject WScript.Shell
$Shortcut = $WshShell.CreateShortcut([Environment]::GetFolderPath("Desktop") + "\PixelFlow.lnk")
$Shortcut.TargetPath = "C:\Program Files\nodejs\node.exe"
$Shortcut.Arguments = "C:\Users\11\AppData\Roaming\npm\node_modules\@tauri-apps\cli\tauri.js dev"
$Shortcut.WorkingDirectory = "A:\tenent"
$Shortcut.Save()
Write-Host "Desktop shortcut created"
