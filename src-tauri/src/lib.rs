// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
mod analyzer;
mod db;
mod exif_common;
mod importer;
mod lightroom;
mod scanner;
mod tinydng;
mod win_wic;
mod xmp;

use tauri::Manager;
use tauri::window::{Effect, EffectsBuilder};

/// 扩展 asset 协议访问范围 — 浏览设备/文件夹/选择目标目录时由前端调用,
/// 只允许用户实际浏览的路径, 代替 tauri.conf.json 中的全盘通配 scope
#[tauri::command]
fn allow_asset_dir(app: tauri::AppHandle, dir_path: String) -> Result<(), String> {
    use tauri::Manager;
    app.asset_protocol_scope()
        .allow_directory(dir_path, true)
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn set_glass_bg(app: tauri::AppHandle, enabled: bool, dark: bool) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        let Some(window) = app.get_webview_window("main") else {
            return Ok(());
        };
        if enabled {
            // 一律使用 Mica(失焦时系统自动回退为纯色, 不再额外处理)
            let effect = if dark { Effect::MicaDark } else { Effect::MicaLight };
            window
                .set_effects(EffectsBuilder::new().effect(effect).build())
                .map_err(|e| e.to_string())?;
        } else {
            window
                .set_effects(None::<tauri::utils::config::WindowEffectsConfig>)
                .map_err(|e| e.to_string())?;
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        // macOS/Linux: Mica 不可用, 前端自动降级为不透明背景
        let _ = (app, enabled, dark);
    }
    Ok(())
}

/// Mica 是 Windows 11(build >= 22000)才有的效果。
///
/// 边界核对: Win10 22H2 = 19045 ✓排除 · Windows Server 2022 = 20348 ✓排除(无 Mica)
///          · Win11 21H2 = 22000 ✓纳入 · 本机 26200 ✓纳入
///
/// 为什么抽成纯函数: 本机是 Win11, "Win10 上会怎样"这条分支**没法实机验证**,
/// 抽出来至少能把边界用单测钉死。
pub fn mica_supported_by_build(build: u32) -> bool {
    build >= 22000
}

/// 系统能力探测结果。字段名与前端 `OsCapabilities` 一一对应(serde camelCase)。
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OsCapabilities {
    /// "windows" / "macos" / "linux"
    platform: &'static str,
    /// Windows 构建号。读不到时为 None —— 前端据此判定"探测失败", 从而**不改动**默认值。
    windows_build: Option<u32>,
    supports_mica: bool,
}

/// 启动时问一次: 这台机器的系统支持 Mica 吗?
///
/// 判据必须用**构建号**, 不能用 `ProductName`: Win11 出于兼容性不更新 ProductName
/// (本机实测 ProductName = "Windows 10 Pro for Workstations", 而真值是 Win11 25H2 / 26200),
/// 而 `navigator.userAgent` 对 Win10/Win11 都报 "Windows NT 10.0"。
///
/// 注意 `windows_build: None` 的语义: 它表示**探测失败**, 不是"不支持"。
/// 前端只在 windowsBuild 非 null 时才拿 supports_mica 当默认值 —— 否则一次注册表读取
/// 失败就会把 Win11 用户的玻璃默认关掉, 还把设置里的开关置灰。
#[tauri::command]
fn get_os_capabilities() -> OsCapabilities {
    #[cfg(target_os = "windows")]
    {
        use windows::Win32::System::Registry::HKEY_LOCAL_MACHINE;
        let build = crate::lightroom::win::reg_read_string(
            HKEY_LOCAL_MACHINE,
            r"SOFTWARE\Microsoft\Windows NT\CurrentVersion",
            Some("CurrentBuildNumber"),
        )
        .and_then(|s| s.trim().parse::<u32>().ok());

        OsCapabilities {
            platform: "windows",
            windows_build: build,
            supports_mica: build.map(mica_supported_by_build).unwrap_or(false),
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        // macOS/Linux: Mica 不存在。platform 非 "windows", 前端因此保持今天的行为
        // (默认开、不置灰) —— 见 docs/superpowers/specs/...-mica-os-default-design.md §6
        OsCapabilities {
            platform: std::env::consts::OS,
            windows_build: None,
            supports_mica: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::mica_supported_by_build;

    #[test]
    fn mica_boundary() {
        // Win10 22H2 / Server 2022: 无 Mica
        assert!(!mica_supported_by_build(19045));
        assert!(!mica_supported_by_build(20348));
        // 边界两侧
        assert!(!mica_supported_by_build(21999));
        assert!(mica_supported_by_build(22000));
        // Win11 23H2 / 25H2
        assert!(mica_supported_by_build(22621));
        assert!(mica_supported_by_build(26200));
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let app_handle = app.handle().clone();

            // asset 协议默认 scope 为空([]), 启动时只放行缩略图/预览/全图缓存目录
            // (images.rs 的缓存目录: %LOCALAPPDATA%\image-filter 或 ~/.cache/image-filter)
            {
                use tauri::Manager;
                let cache_root = std::env::var("LOCALAPPDATA")
                    .map(std::path::PathBuf::from)
                    .ok()
                    .or_else(|| {
                        std::env::var("XDG_CACHE_HOME")
                            .ok()
                            .map(std::path::PathBuf::from)
                            .or_else(|| {
                                std::env::var("HOME").ok().map(|h| {
                                    std::path::PathBuf::from(h).join(".cache")
                                })
                            })
                    })
                    .unwrap_or_else(std::env::temp_dir)
                    .join("image-filter");
                let _ = app.asset_protocol_scope().allow_directory(cache_root, true);
            }

            let db_path = app
                .path()
                .app_data_dir()
                .expect("failed to resolve app data dir")
                .join("image-filter.db");

            // Ensure parent directory exists
            if let Some(parent) = db_path.parent() {
                std::fs::create_dir_all(parent)?;
            }

            let pool = tauri::async_runtime::block_on(async {
                db::init_db(&db_path).await
            })?;

            app_handle.manage(db::DbState { pool });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            allow_asset_dir,
            set_glass_bg,
            get_os_capabilities,
            db::get_import_history,
            db::count_import_history,
            db::get_rules,
            db::save_rule,
            scanner::drives::detect_drives,
            scanner::drives::open_folder,
            scanner::drives::eject_drive,
            scanner::browse::browse_directory,
            scanner::browse::count_folders,
            scanner::browse::scan_directory,
            scanner::exif::get_exif,
            scanner::images::get_thumbnail_path,
            scanner::images::get_full_image,
            scanner::images::get_preview_image,
            scanner::images::batch_thumbnails,
            importer::import_photos,
            analyzer::analyze_photos,
            analyzer::find_duplicates,
            analyzer::stop_analysis,
            // Phase 5 · XMP 边车(评分/色标跟着文件走)。三个命令见 src/xmp.rs 文件头。
            xmp::read_decisions,
            xmp::write_decisions,
            xmp::probe_xmp_target,
            // Phase 7 · Lightroom Classic 衔接(模式 2: 打开导入对话框)。
            // 只探测与启动, 不驱动 LrC —— 见 src/lightroom.rs 文件头。
            lightroom::probe_lightroom,
            lightroom::send_to_lightroom,
            lightroom::force_close_lightroom,
            lightroom::is_dir_empty,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
