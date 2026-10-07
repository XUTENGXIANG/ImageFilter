// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
mod analyzer;
mod db;
mod exif_common;
mod importer;
mod lightroom;
mod mover;
mod scanner;
pub mod server;
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

// ── 图库服务(手机端访问) · Task 8 的 Tauri 接线 ─────────────────────────────
//
// 五条命令的共同纪律: **失败一律是 `Err(String)`, 绝不许 panic**。服务起不来
// (端口被占、token 不可用、随机数不可用、配置写不进去)只该让界面显示一句理由,
// 桌面端本身必须照常可用 —— 用户的首要约束是"别把桌面端弄坏"。

/// 状态快照。前端轮询/进设置页时调它。
#[tauri::command]
async fn get_server_status(
    app: tauri::AppHandle,
    state: tauri::State<'_, server::ServerState>,
) -> Result<server::ServerStatus, String> {
    server::status_of(&app, &state)
}

/// 开/关图库服务。**开启路径上的一切失败都只回 `Err`**, 桌面端不受影响。
///
/// 真正做事的顺序(含"落盘失败时端口必须照样放开"那一条)在 `server::set_enabled`:
/// 它只认一个目录, 于是那条路径能被单测**真的走一遍**(测试把配置路径指到一个写不进去的
/// 位置), 而不是靠读代码相信。这里只做一件事: 把 Tauri 的 app 数据目录翻译成一个 `&Path`。
#[tauri::command]
async fn set_server_enabled(
    app: tauri::AppHandle,
    state: tauri::State<'_, server::ServerState>,
    enabled: bool,
) -> Result<server::ServerStatus, String> {
    server::set_enabled(state.inner(), &server::app_data_dir(&app)?, enabled).await
}

/// 轮换配对 token。已有手机需要重新配对(规格 §5.2); 服务不用重启 —— 端点每次请求
/// 现读配置(`ServerState` 的注释)。
#[tauri::command]
async fn rotate_server_token(
    app: tauri::AppHandle,
    state: tauri::State<'_, server::ServerState>,
) -> Result<server::ServerStatus, String> {
    // 用可失败的那一版: 随机数不可用只该变成一句 `Err`, 不该 panic(见 `auth::try_new_token`)。
    let fresh = server::auth::try_new_token()?;
    state.config.write().map_err(|e| e.to_string())?.token = fresh;
    server::persist(&app, &state)?;
    server::status_of(&app, &state)
}

/// 加入一个共享根。**只接受已存在的目录**(空 = 谁都访问不了, 是安全默认值)。
#[tauri::command]
async fn add_share(
    app: tauri::AppHandle,
    state: tauri::State<'_, server::ServerState>,
    path: String,
) -> Result<Vec<String>, String> {
    let p = std::path::PathBuf::from(&path);
    if !p.is_dir() {
        return Err("不是目录".into());
    }
    {
        let mut cfg = state.config.write().map_err(|e| e.to_string())?;
        if !cfg.shares.iter().any(|s| s == &p) {
            cfg.shares.push(p);
        }
    }
    server::persist(&app, &state)?;
    server::share_strings(&state)
}

/// 移除一个共享根(按用户看到的那个字符串比较)。
#[tauri::command]
async fn remove_share(
    app: tauri::AppHandle,
    state: tauri::State<'_, server::ServerState>,
    path: String,
) -> Result<Vec<String>, String> {
    {
        let mut cfg = state.config.write().map_err(|e| e.to_string())?;
        cfg.shares.retain(|s| s.to_string_lossy() != path);
    }
    server::persist(&app, &state)?;
    server::share_strings(&state)
}

/// `decisions-changed` 的发送端 —— **只有这里知道 Tauri**。
///
/// 为什么绕这一层(这是 Task 8 实测出来的, 不是洁癖): 单测与 `tests/` 的集成测试都会把
/// `server` 编进各自的**测试 exe**, 而测试 exe **没有** Tauri 给正式程序嵌的
/// `Microsoft.Windows.Common-Controls` v6 清单。`tauri::AppHandle` 一旦真的出现在测试可达的
/// 代码里(哪怕只是被 `drop`), 链接器就会把 Tauri 的窗口/WebView 那一层拉进来 —— 它 import 了
/// `comctl32.dll` 的 `TaskDialogIndirect` / `SetWindowSubclass` 这些**只有 v6 才导出**的函数,
/// 于是加载期直接 `STATUS_ENTRYPOINT_NOT_FOUND (0xC0000139)`, **连 `cargo test` 都跑不起来**
/// (实测: 保留 `Option<AppHandle>` 字段 → 两个测试 exe 都起不来; 类型擦除后正常)。
/// 详见 `server::Notify`。
///
/// 这个函数只在 `run()` 的 setup 里被引用一次, 所以测试 exe 根本不会链接到它。
fn decisions_notifier(app: tauri::AppHandle) -> server::Notify {
    std::sync::Arc::new(move |paths: &[String]| {
        // Tauri v2 的 `emit` 定义在 `Emitter` trait 上: **不 use 就编译不过**。
        use tauri::Emitter as _;
        let _ = app.emit("decisions-changed", serde_json::json!({ "paths": paths }));
    })
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

            let app_data_dir = app
                .path()
                .app_data_dir()
                .expect("failed to resolve app data dir");
            let db_path = app_data_dir.join("image-filter.db");

            // Ensure parent directory exists
            if let Some(parent) = db_path.parent() {
                std::fs::create_dir_all(parent)?;
            }

            let pool = tauri::async_runtime::block_on(async {
                db::init_db(&db_path).await
            })?;

            app_handle.manage(db::DbState { pool });

            // ── 图库服务(手机端访问): **默认关闭**, 只有配置里 enabled=true 才在启动时拉起 ──
            //
            // 这一段**绝不能**让 setup 失败、变慢或 panic（用户第一约束: 别把桌面端弄坏）:
            //   · `load` 只有回落, 没有 unwrap/expect —— 配置缺失/损坏 = 一份默认配置(关闭);
            //   · 默认配置**不含 token**, 所以这条路径根本不碰随机数(`getrandom` 失败点已移走);
            //   · 关闭时连服务任务都不 spawn: `wants_start` 为 false 就到此为止;
            //   · 开启时只在后台任务里尽力起服务, 失败留下一句可见的理由 + 一行 stderr，
            //     setup 立刻返回 —— 启动快慢与端口绑定的快慢无关。
            let server_state = server::config::ServerConfig::load(&app_data_dir)
                .into_state_with_notify(decisions_notifier(app_handle.clone()));
            let start_service_now = server::wants_start(&server_state);
            app_handle.manage(server_state.clone());
            if start_service_now {
                let dir = app_data_dir.clone();
                tauri::async_runtime::spawn(async move {
                    match server::boot(server_state, &dir).await {
                        Ok(Some(p)) => println!("[server] 已监听 {}", server::primary_url(p)),
                        Ok(None) => {}
                        // 失败**只**留日志与 `ServerStatus.lastError`: 桌面端照常可用。
                        Err(e) => eprintln!("[server] 启动失败(桌面端不受影响): {}", e),
                    }
                });
            }

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
            // Phase 8 · 图库服务(手机端访问)的生命周期与配置。**默认关闭**,
            // 用户显式开启后才监听(见 `server` 模块头与 `setup` 里的接线)。
            get_server_status,
            set_server_enabled,
            rotate_server_token,
            add_share,
            remove_share,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
