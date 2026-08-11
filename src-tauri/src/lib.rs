mod analyzer;
mod db;
mod exif_common;
mod importer;
mod scanner;
mod tinydng;
mod win_wic;

use tauri::Manager;
use tauri::window::{Effect, EffectsBuilder};

#[tauri::command]
fn greet(name: String) -> String {
    format!("Hello, {}! PixelFlow is running.", name)
}

#[tauri::command]
fn set_glass_bg(
    app: tauri::AppHandle,
    enabled: bool,
    dark: bool,
    focused: bool,
) -> Result<(), String> {
    let Some(window) = app.get_webview_window("main") else {
        return Ok(());
    };
    if enabled {
        if focused {
            #[cfg(target_os = "windows")]
            if let Ok(hwnd) = raw_hwnd(&window) {
                let _ = clear_swca(hwnd);
            }
            let effect = if dark { Effect::MicaDark } else { Effect::MicaLight };
            window
                .set_effects(EffectsBuilder::new().effect(effect).build())
                .map_err(|e| e.to_string())?;
        } else {
            window
                .set_effects(None::<tauri::utils::config::WindowEffectsConfig>)
                .map_err(|e| e.to_string())?;
            #[cfg(target_os = "windows")]
            apply_swca_acrylic(raw_hwnd(&window)?, dark)?;
        }
    } else {
        #[cfg(target_os = "windows")]
        if let Ok(hwnd) = raw_hwnd(&window) {
            let _ = clear_swca(hwnd);
        }
        window
            .set_effects(None::<tauri::utils::config::WindowEffectsConfig>)
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

// 失焦时使用传统 Acrylic，保留毛玻璃且不会像 Mica 一样回退成中性灰
#[cfg(target_os = "windows")]
fn apply_swca_acrylic(
    hwnd: windows::Win32::Foundation::HWND,
    dark: bool,
) -> Result<(), String> {
    let color = if dark { 0x80_1F_1F_1Fu32 } else { 0x80_FF_FF_FFu32 };
    set_swca(hwnd, 4, 0, color)
}

#[cfg(target_os = "windows")]
fn clear_swca(hwnd: windows::Win32::Foundation::HWND) -> Result<(), String> {
    set_swca(hwnd, 0, 0, 0)
}

#[cfg(target_os = "windows")]
fn raw_hwnd(
    window: &tauri::WebviewWindow,
) -> Result<windows::Win32::Foundation::HWND, String> {
    let hwnd = window.hwnd().map_err(|e| e.to_string())?;
    Ok(windows::Win32::Foundation::HWND(hwnd.0))
}

#[cfg(target_os = "windows")]
fn set_swca(
    hwnd: windows::Win32::Foundation::HWND,
    state: i32,
    flags: i32,
    color: u32,
) -> Result<(), String> {
    use windows::core::PCSTR;
    use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryA};

    #[repr(C)]
    struct AccentPolicy {
        accent_state: i32,
        accent_flags: i32,
        gradient_color: u32,
        animation_id: u32,
    }

    #[repr(C)]
    struct WindowCompositionAttribData {
        attrib: u32,
        pv_data: *mut std::ffi::c_void,
        cb_data: usize,
    }

    unsafe {
        let user32 =
            LoadLibraryA(PCSTR(b"user32.dll\0".as_ptr())).map_err(|e| e.to_string())?;
        let proc = GetProcAddress(user32, PCSTR(b"SetWindowCompositionAttribute\0".as_ptr()))
            .ok_or_else(|| "SetWindowCompositionAttribute not found".to_string())?;
        let set_window_composition_attribute: unsafe extern "system" fn(
            windows::Win32::Foundation::HWND,
            *mut WindowCompositionAttribData,
        ) -> i32 = std::mem::transmute(proc);

        let mut policy = AccentPolicy {
            accent_state: state,
            accent_flags: flags,
            gradient_color: color,
            animation_id: 0,
        };
        let mut data = WindowCompositionAttribData {
            attrib: 0x13,
            pv_data: (&mut policy as *mut AccentPolicy).cast(),
            cb_data: std::mem::size_of::<AccentPolicy>(),
        };
        if set_window_composition_attribute(hwnd, &mut data) == 0 {
            return Err("SetWindowCompositionAttribute failed".to_string());
        }
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
            let db_path = app
                .path()
                .app_data_dir()
                .expect("failed to resolve app data dir")
                .join("pixel-flow.db");

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
            greet,
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
