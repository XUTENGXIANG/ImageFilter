use inspect_path::inspect_path;
use serde::Serialize;

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DriveInfo {
    pub mount_point: String,
    pub drive_type: String,
    pub label: String,
    pub available: bool,
}

/// Get Windows volume label for a drive (e.g., "CANON_DC" for D:\)
#[cfg(target_os = "windows")]
fn volume_label(mount: &str) -> String {
    use std::ffi::OsString;
    use std::os::windows::ffi::OsStringExt;
    let path_str = format!("{}\\\\", mount);
    let path: Vec<u16> = path_str.encode_utf16().chain(std::iter::once(0)).collect();
    let mut buf = vec![0u16; 128];
    #[link(name = "kernel32")]
    extern "system" {
        fn GetVolumeInformationW(
            root: *const u16,
            name: *mut u16, name_len: u32,
            serial: *mut u32, max_len: *mut u32, flags: *mut u32,
            fs_name: *mut u16, fs_len: u32,
        ) -> i32;
    }
    unsafe {
        if GetVolumeInformationW(path.as_ptr(), buf.as_mut_ptr(), buf.len() as u32,
            std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(),
            std::ptr::null_mut(), 0) != 0
        {
            let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
            return OsString::from_wide(&buf[..len]).to_string_lossy().to_string();
        }
    }
    String::new()
}

#[cfg(target_os = "macos")]
fn volume_label(_mount: &str) -> String {
    String::new() // 卷名在 detect_drives_macos 中用目录名
}

/// Open folder in OS file manager
#[tauri::command]
pub fn open_folder(path: String) {
    #[cfg(target_os = "windows")]
    { let _ = std::process::Command::new("explorer").arg(&path).spawn(); }
    #[cfg(target_os = "macos")]
    { let _ = std::process::Command::new("open").arg(&path).spawn(); }
    #[cfg(target_os = "linux")]
    { let _ = std::process::Command::new("xdg-open").arg(&path).spawn(); }
}

/// 安全弹出可移动设备 — 完整 IOCTL 序列 (与资源管理器"弹出"一致)
/// CreateFile(GENERIC_READ|GENERIC_WRITE) → LOCK → DISMOUNT → MEDIA_REMOVAL → EJECT
#[tauri::command]
pub fn eject_drive(mount_point: String) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        use std::ffi::OsStr;
        use std::os::windows::ffi::OsStrExt;
        use windows::core::PCWSTR;

        let letter = mount_point.trim_end_matches('\\');
        let vol_path = format!("\\\\.\\{}", letter);
        let wide: Vec<u16> = OsStr::new(&vol_path).encode_wide().chain(Some(0)).collect();

        let handle = unsafe {
            windows::Win32::Storage::FileSystem::CreateFileW(
                PCWSTR::from_raw(wide.as_ptr()),
                (windows::Win32::Foundation::GENERIC_READ | windows::Win32::Foundation::GENERIC_WRITE).0,
                windows::Win32::Storage::FileSystem::FILE_SHARE_READ
                    | windows::Win32::Storage::FileSystem::FILE_SHARE_WRITE,
                None,
                windows::Win32::Storage::FileSystem::OPEN_EXISTING,
                windows::Win32::Storage::FileSystem::FILE_ATTRIBUTE_NORMAL,
                None,
            )
        }
        .map_err(|e| format!("打开卷失败: {}", e))?;
        if handle.is_invalid() {
            return Err(format!("打开卷失败: 句柄无效"));
        }

        let mut ret: u32 = 0;
        let mut ioctl = |code: u32| -> bool {
            unsafe {
                windows::Win32::System::IO::DeviceIoControl(
                    handle,
                    code,
                    None,
                    0,
                    None,
                    0,
                    Some(&mut ret),
                    None,
                )
            }
            .is_ok()
        };

        if !ioctl(0x0009_0018) { unsafe { let _ = windows::Win32::Foundation::CloseHandle(handle); } return Err("锁卷失败（设备被占用?）".into()); }
        if !ioctl(0x0009_0020) { unsafe { let _ = windows::Win32::Foundation::CloseHandle(handle); } return Err("卸载卷失败".into()); }
        let _ = ioctl(0x002D_4804);
        let ejected = ioctl(0x002D_4808);

        unsafe { let _ = windows::Win32::Foundation::CloseHandle(handle); };

        if ejected {
            Ok(())
        } else {
            Err("弹出失败（设备可能不支持热插拔）".into())
        }
    }

    #[cfg(target_os = "macos")]
    {
        // macOS: diskutil eject <卷名>
        let vol = mount_point.trim_end_matches('/');
        let name = vol.rsplit('/').next().unwrap_or("");
        match std::process::Command::new("diskutil").args(["eject", name]).status() {
            Ok(s) if s.success() => Ok(()),
            _ => Err("弹出失败".into()),
        }
    }

    #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
    {
        let _ = mount_point;
        Err("该平台暂不支持弹出设备".into())
    }
}

/// Detect all drives, removable drives first
#[tauri::command]
pub fn detect_drives() -> Vec<DriveInfo> {
    #[cfg(target_os = "windows")]
    { detect_drives_windows() }

    #[cfg(target_os = "macos")]
    { detect_drives_macos() }

    #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
    { Vec::new() }
}

#[cfg(target_os = "windows")]
fn detect_drives_windows() -> Vec<DriveInfo> {
    let mut drives = Vec::new();

    for letter in 'A'..='Z' {
        let mount = format!("{}:\\", letter);
        let path = std::path::Path::new(&mount);
        if !path.exists() {
            continue;
        }

        if let Ok(info) = inspect_path(path) {
            let drive_type = if info.is_removable() {
                "removable"
            } else if info.is_fixed() {
                "fixed"
            } else if info.is_remote() {
                "network"
            } else if info.is_cdrom() {
                "cdrom"
            } else if info.is_ramdisk() {
                "ramdisk"
            } else {
                "unknown"
            };

            let is_removable = info.is_removable();
            let vol_name = volume_label(&mount);
            let label = if vol_name.is_empty() {
                format!("{}{}", mount, if is_removable { " (可移动)" } else { "" })
            } else {
                format!("{} ({}){}", vol_name, &mount[..1], if is_removable { " 可移动" } else { "" })
            };

            drives.push(DriveInfo {
                mount_point: mount,
                drive_type: drive_type.to_string(),
                label,
                available: true,
            });
        }
    }

    drives.sort_by(|a, b| {
        let a_rem = a.drive_type == "removable";
        let b_rem = b.drive_type == "removable";
        b_rem.cmp(&a_rem).then_with(|| a.mount_point.cmp(&b.mount_point))
    });

    drives
}

/// macOS: 枚举 /Volumes 下的已挂载卷
#[cfg(target_os = "macos")]
fn detect_drives_macos() -> Vec<DriveInfo> {
    let mut drives = Vec::new();
    if let Ok(entries) = std::fs::read_dir("/Volumes") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            // 跳过隐藏卷(如 .timemachine)
            if name.starts_with('.') { continue; }
            drives.push(DriveInfo {
                mount_point: entry.path().to_string_lossy().to_string(),
                drive_type: "removable".into(), // macOS 挂载卷按可移动处理
                label: name.clone(),
                available: true,
            });
        }
    }
    drives
}
