mod analyzer;
mod db;
mod exif_common;
mod importer;
mod scanner;
mod tinydng;
mod win_wic;

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
            db::get_import_history,
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
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
