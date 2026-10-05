// ═══════════════════════════════════════════════════════════════════
// Phase 7 · 与 Lightroom Classic 的衔接(模式 2: 打开导入对话框)
//
// 为什么是"打开导入对话框"而不是"静默进目录":
//   LrC 的插件 SDK **没有**暴露"把照片加入目录"这个动作(见 docs Phase 7 一节)。
//   实测(_probe/lrc-uia.txt)已确认: `Lightroom.exe "<文件夹>"` 会让 LrC 打开
//   导入对话框并把该文件夹（所在卷）作为源, 于是"一键"= 打开 + 源已就位,
//   用户在 LrC 里确认一次即可 —— 这正是模式 2 的定义。
//
// 反过来的三条实测结论(改动前先读, 否则会写出永远不生效的检测):
// 1. **主窗口标题永远是"图库"**, 导入是模态对话框、不改标题。所以
//    "靠窗口标题判断导入是否打开"这条路是死的, 本模块**不做**任何窗口/UI 探测。
// 2. **安装路径不能只扫 Program Files**。本机实测装在 `A:\lrc\Adobe Lightroom Classic`,
//    标准目录与 App Paths 全部落空, 唯一命中的是 `.lrcat` 文件关联。所以探测顺序
//    把"文件关联"放在第一位, 而不是当兜底。
// 3. **注册表里没有版本号子键**(HKCU/HKLM\SOFTWARE\Adobe\Lightroom 只有 language/Locale),
//    所以"按版本子键拼路径"也不可行。
//
// 两条不变式:
// 1. **只启动, 不驱动**。本模块绝不发送按键、绝不注入、绝不在 LrC 里做任何操作;
//    它只做一件事: CreateProcess(Lightroom.exe, "<文件夹>")。
// 2. **参数是"照片所在文件夹"** —— 传什么是调用方的决定(前端 src/lightroom.ts 负责),
//    本模块只校验它存在、是目录, 且**不校验它是不是照片目录**(调用方可能传根目录,
//    那是用户的自由)。
// ═══════════════════════════════════════════════════════════════════

use serde::Serialize;
use std::path::{Path, PathBuf};

// ── 错误分类 ────────────────────────────────────────────────────────
// 与 xmp.rs 同款: code 是给前端 i18n 用的闭集字符串(前端白名单校验后拼
// `lrc.err.<code>`), detail 只进控制台/状态行, 不进 toast。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LrcError {
    /// 找不到 Lightroom.exe
    NotFound,
    /// 要发送的文件夹不存在或不是目录
    NoFolder,
    /// **Lightroom 已经在运行** —— 这时把路径当参数传给它, Adobe 会**忽略**该参数,
    /// 导入对话框停在它上一次的源上。
    ///
    /// 依据: 实机实测(本机 LrC 15.2.1) + FastRawViewer 作者的说明
    /// (https://www.fastrawviewer.com/comment/5354): "'R' runs Lightroom.exe with
    /// selected files passed as application command line arguments … if Lightroom is
    /// already opened: opens import dialog, no files selected. The second case worked
    /// in previous Lr version, but broken in the latest update (by Adobe)."
    ///
    /// 冷启动(进程不存在时)传**文件夹**路径是有效的 —— 实测传空文件夹时对话框报
    /// "没有找到照片 / 0 张照片 / 0 字节", 传含 1 张图的文件夹时报 "1 张照片 / 120 KB"。
    AlreadyRunning,
    /// 启动失败(权限、文件损坏等)
    LaunchFailed,
    /// 非 Windows 平台(macOS 走 `open`, 尚未验证, 所以先明确报"不支持")
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    NotSupported,
    /// 未实现的模式(前端挡下的"静默导入"档)。Rust 侧不构造, 与前端闭集对照用。
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    NotImplemented,
    /// 目标里这批照片全都已存在(内容相同) → 没有新东西可给 LrC 导入。
    ///
    /// 由**前端**判定并提示(summary.imported == 0), Rust 侧不构造这个码 —— 与上一条
    /// 同理, 放在这里只是为了让错误码闭集与前端 LRC_ERR_CODES 一一对应, 便于对照。
    #[cfg_attr(target_os = "windows", allow(dead_code))]
    NoNewPhotos,
    Unknown,
}

impl LrcError {
    pub fn code(self) -> &'static str {
        match self {
            LrcError::NotFound => "notFound",
            LrcError::NoFolder => "noFolder",
            LrcError::AlreadyRunning => "alreadyRunning",
            LrcError::LaunchFailed => "launchFailed",
            LrcError::NotSupported => "notSupported",
            LrcError::NotImplemented => "notImplemented",
            LrcError::NoNewPhotos => "noNewPhotos",
            LrcError::Unknown => "unknown",
        }
    }
}

impl std::fmt::Display for LrcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.code())
    }
}

/// 诊断用的中文细节(只进 detail/控制台)。
///
/// 与 xmp.rs::detail_of 同款: **不进 toast**。toast 文案一律由前端按闭集错误码
/// 走 i18n 生成, 免得中文硬编码散在 Rust 里(xmp 会话 ④ 的既有约定)。
#[allow(dead_code)]
fn detail_of(e: LrcError) -> &'static str {
    match e {
        LrcError::NotFound => "未找到 Lightroom.exe(文件关联与常见安装目录都落空)",
        LrcError::NoFolder => "目标文件夹不存在或不是目录",
        LrcError::AlreadyRunning => "Lightroom 已在运行(传路径会被忽略), 需先关闭它",
        LrcError::LaunchFailed => "启动 Lightroom 失败",
        LrcError::NotSupported => "该平台暂不支持与 Lightroom 衔接",
        LrcError::NotImplemented => "该模式尚未实现",
        LrcError::NoNewPhotos => "选中的照片在目标里都已存在, 没有新照片",
        LrcError::Unknown => "未知错误",
    }
}

// ── 对外结构 ────────────────────────────────────────────────────────

/// probe_lightroom 的结果。与前端 src/types.ts 的 LightroomProbe 一一对应。
#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct LightroomProbe {
    /// 是否找到可执行的 Lightroom.exe
    pub found: bool,
    /// 找到的 exe 完整路径(found=false 时为 None)
    pub exe: Option<String>,
    /// 命中的探测方式, 便于排查: "classUser" | "classMachine" | "uninstall" | "appPath" | "programFiles"
    pub source: Option<String>,
    /// 是否检测到 Lightroom 正在运行(只影响提示文案, 不影响功能)
    pub running: bool,
}

// ── 路径展开 ────────────────────────────────────────────────────────

/// 展开 `%VAR%` 形式的 Windows 环境变量。
///
/// 注册表里 App Paths 之类的值常写成 `%ProgramFiles%\Adobe\...`, 而 std 没有
/// 现成的展开函数(不引入新依赖)。未知变量原样保留(展开不出来时让后面的
/// "文件是否存在"判断去兜底, 不要在这里静默吞掉)。
fn expand_env_vars(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let bytes: Vec<char> = raw.chars().collect();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == '%' {
            // 找配对的第二个 '%'
            if let Some(close_rel) = bytes[i + 1..].iter().position(|c| *c == '%') {
                let close = i + 1 + close_rel;
                if close > i + 1 {
                    let name: String = bytes[i + 1..close].iter().collect();
                    match std::env::var(&name) {
                        Ok(v) => {
                            out.push_str(&v);
                            i = close + 1;
                            continue;
                        }
                        Err(_) => {
                            // 未知变量: 原样输出这一段(含两个 %)
                            out.extend(bytes[i..=close].iter());
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

/// 去掉一层包裹的双引号(注册表命令行里很常见)
fn unquote(s: &str) -> String {
    let t = s.trim();
    if t.len() >= 2 && t.starts_with('"') && t.ends_with('"') {
        t[1..t.len() - 1].to_string()
    } else {
        t.to_string()
    }
}

/// 从一条 shell open 命令行里取出 exe 路径。
///
/// 形如 `A:\lrc\Adobe Lightroom Classic\Lightroom.exe "%1"` —— 取第一个 `"` 之前的
/// 部分(若无引号则取第一个空格之前)。**这不是通用命令行解析器**, 只处理
/// "exe + 参数"这一种我们真正会遇到的形状; 取不到就返回 None 交给下一策略。
fn exe_from_command_line(cmd: &str) -> Option<String> {
    let t = cmd.trim();
    if t.is_empty() {
        return None;
    }
    if let Some(stripped) = t.strip_prefix('"') {
        // 带引号: 取闭合引号之前
        if let Some(end) = stripped.find('"') {
            let p = &stripped[..end];
            if p.to_ascii_lowercase().ends_with("lightroom.exe") {
                return Some(p.to_string());
            }
            return Some(p.to_string());
        }
        return None;
    }
    // 不带引号: 取到第一个空格
    let end = t.find(' ').unwrap_or(t.len());
    Some(t[..end].to_string())
}

// ── Windows 注册表探测 ──────────────────────────────────────────────

#[cfg(target_os = "windows")]
pub(crate) mod win {
    use super::*;
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::ERROR_SUCCESS;
    use windows::Win32::System::Registry::{
        RegCloseKey, RegEnumKeyExW, RegOpenKeyExW, RegQueryValueExW, HKEY, HKEY_CURRENT_USER,
        HKEY_LOCAL_MACHINE, KEY_READ, REG_EXPAND_SZ, REG_SZ,
    };

    /// 读一个 REG_SZ / REG_EXPAND_SZ 值。
    ///
    /// 刻意只做"读字符串"这一件事: 本模块需要的信息(文件关联的 open 命令、
    /// 卸载项的 InstallLocation)全是字符串。类型不对就当没有。
    ///
    /// `pub(crate)`: lib.rs 的 `get_os_capabilities` 也用它读 `CurrentBuildNumber`。
    /// 读注册表字符串是 Windows 通用管道, 不值得为它再写第二份 —— 两份 unsafe 的
    /// 句柄/缓冲区处理比一次跨模块调用更容易出错。
    pub(crate) fn reg_read_string(hkey: HKEY, subkey: &str, value: Option<&str>) -> Option<String> {
        let sub_w: Vec<u16> = subkey.encode_utf16().chain(std::iter::once(0)).collect();

        let mut opened = HKEY::default();
        let rc = unsafe { RegOpenKeyExW(hkey, PCWSTR(sub_w.as_ptr()), None, KEY_READ, &mut opened) };
        if rc != ERROR_SUCCESS {
            return None;
        }

        let value_w: Vec<u16> = match value {
            Some(v) => v.encode_utf16().chain(std::iter::once(0)).collect(),
            None => vec![0u16],
        };
        let value_ptr = if value.is_some() {
            PCWSTR(value_w.as_ptr())
        } else {
            PCWSTR::null()
        };

        // 先问长度与类型
        let mut ty = windows::Win32::System::Registry::REG_VALUE_TYPE::default();
        let mut size: u32 = 0;
        let rc = unsafe {
            RegQueryValueExW(opened, value_ptr, None, Some(&mut ty), None, Some(&mut size))
        };
        if rc != ERROR_SUCCESS || size == 0 || (ty != REG_SZ && ty != REG_EXPAND_SZ) {
            unsafe { let _ = RegCloseKey(opened); }
            return None;
        }

        // size 是字节数; 多留一个 u16 给终止符(字符串可能不以 NUL 结尾)
        let mut buf = vec![0u8; size as usize + 2];
        let rc = unsafe {
            RegQueryValueExW(
                opened,
                value_ptr,
                None,
                Some(&mut ty),
                Some(buf.as_mut_ptr()),
                Some(&mut size),
            )
        };
        unsafe { let _ = RegCloseKey(opened); }
        if rc != ERROR_SUCCESS {
            return None;
        }

        let u16s: Vec<u16> = buf
            .chunks_exact(2)
            .map(|c| u16::from_ne_bytes([c[0], c[1]]))
            .take_while(|c| *c != 0)
            .collect();
        let s = String::from_utf16_lossy(&u16s);
        if s.trim().is_empty() {
            None
        } else {
            Some(s)
        }
    }

    /// 策略 1/2: 从 `.lrcat` 文件关联反查 exe。
    ///
    /// 本机实测这是**唯一命中**的方式(装在 `A:\lrc` 这种非标准目录时,
    /// Program Files 与 App Paths 都找不到)。所以它是第一策略而不是兜底。
    fn from_lrcat_association() -> Option<(String, String)> {
        // HKCU 优先(用户级关联可覆盖机器级)
        for (root, tag) in [
            (HKEY_CURRENT_USER, "classUser"),
            (HKEY_LOCAL_MACHINE, "classMachine"),
        ] {
            let Some(prog_id) = reg_read_string(root, r"SOFTWARE\Classes\.lrcat", None) else {
                continue;
            };
            let prog_id = prog_id.trim().to_string();
            if prog_id.is_empty() {
                continue;
            }
            for hive in [r"SOFTWARE\Classes\", r"SOFTWARE\WOW6432Node\Classes\"] {
                let key = format!("{}{}\\shell\\open\\command", hive, prog_id);
                if let Some(cmd) = reg_read_string(root, &key, None) {
                    if let Some(exe) = exe_from_command_line(&cmd) {
                        let exe = expand_env_vars(&exe);
                        if Path::new(&exe).is_file() {
                            return Some((exe, tag.to_string()));
                        }
                    }
                }
            }
        }
        None
    }

    /// 策略 3: 卸载项的 InstallLocation。本机实测它指向 `A:\lrc`(安装根),
    /// 而 exe 在下一层 `Adobe Lightroom Classic\` —— 所以这里要再探一层。
    fn from_uninstall() -> Option<(String, String)> {
        for (root, sub) in [
            (
                HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
            ),
            (
                HKEY_LOCAL_MACHINE,
                r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall",
            ),
            (
                HKEY_CURRENT_USER,
                r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
            ),
        ] {
            let sub_w: Vec<u16> = sub.encode_utf16().chain(std::iter::once(0)).collect();
            let mut opened = HKEY::default();
            let rc = unsafe {
                RegOpenKeyExW(root, PCWSTR(sub_w.as_ptr()), None, KEY_READ, &mut opened)
            };
            if rc != ERROR_SUCCESS {
                continue;
            }

            let mut index = 0u32;
            loop {
                let mut name_buf = vec![0u16; 512];
                let mut name_len = name_buf.len() as u32;
                let rc = unsafe {
                    RegEnumKeyExW(
                        opened,
                        index,
                        Some(windows::core::PWSTR(name_buf.as_mut_ptr())),
                        &mut name_len,
                        None,
                        None,
                        None,
                        None,
                    )
                };
                if rc != ERROR_SUCCESS {
                    break;
                }
                index += 1;
                let name = String::from_utf16_lossy(&name_buf[..name_len as usize]);
                let key_path = format!("{}\\{}", sub, name);

                // DisplayName 里含 Lightroom 才继续
                let Some(display) = reg_read_string(root, &key_path, Some("DisplayName")) else {
                    continue;
                };
                if !display.to_lowercase().contains("lightroom") {
                    continue;
                }
                let Some(loc) = reg_read_string(root, &key_path, Some("InstallLocation")) else {
                    continue;
                };
                let loc = expand_env_vars(&unquote(&loc));
                // 直接命中
                let direct = Path::new(&loc).join("Lightroom.exe");
                if direct.is_file() {
                    return Some((direct.to_string_lossy().to_string(), "uninstall".into()));
                }
                // 从安装根再找一层(本机的真实形状: InstallLocation=A:\lrc)
                if let Ok(entries) = std::fs::read_dir(&loc) {
                    let mut dirs: Vec<PathBuf> = entries
                        .flatten()
                        .map(|e| e.path())
                        .filter(|p| p.is_dir())
                        .collect();
                    dirs.sort();
                    for d in dirs {
                        let cand = d.join("Lightroom.exe");
                        if cand.is_file() {
                            return Some((cand.to_string_lossy().to_string(), "uninstall".into()));
                        }
                    }
                }
            }
            unsafe { let _ = RegCloseKey(opened); }
        }
        None
    }

    /// 策略 4: App Paths(本机未命中, 但别的机器上常见)
    fn from_app_paths() -> Option<(String, String)> {
        for (root, sub) in [
            (
                HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\Lightroom.exe",
            ),
            (
                HKEY_LOCAL_MACHINE,
                r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths\Lightroom.exe",
            ),
            (
                HKEY_CURRENT_USER,
                r"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\Lightroom.exe",
            ),
        ] {
            if let Some(v) = reg_read_string(root, sub, None) {
                let exe = expand_env_vars(&unquote(&v));
                if Path::new(&exe).is_file() {
                    return Some((exe, "appPath".into()));
                }
            }
        }
        None
    }

    /// 策略 5: 标准安装目录(最后兜底)
    fn from_program_files() -> Option<(String, String)> {
        let mut roots: Vec<PathBuf> = Vec::new();
        for var in ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"] {
            if let Ok(v) = std::env::var(var) {
                roots.push(PathBuf::from(v).join("Adobe"));
            }
        }
        for root in roots {
            let Ok(entries) = std::fs::read_dir(&root) else {
                continue;
            };
            let mut dirs: Vec<PathBuf> = entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| {
                    p.is_dir()
                        && p.file_name()
                            .map(|n| {
                                let s = n.to_string_lossy().to_lowercase();
                                s.contains("lightroom") && s.contains("classic")
                            })
                            .unwrap_or(false)
                })
                .collect();
            dirs.sort();
            dirs.reverse(); // 版本号大的优先
            for d in dirs {
                let cand = d.join("Lightroom.exe");
                if cand.is_file() {
                    return Some((cand.to_string_lossy().to_string(), "programFiles".into()));
                }
            }
        }
        None
    }

    /// 按实测确认过的优先级依次尝试。
    pub fn find_exe() -> Option<(String, String)> {
        if let Some(hit) = from_lrcat_association() {
            return Some(hit);
        }
        if let Some(hit) = from_uninstall() {
            return Some(hit);
        }
        if let Some(hit) = from_app_paths() {
            return Some(hit);
        }
        from_program_files()
    }

    /// Lightroom 是否正在运行(只用于提示文案, 不影响功能)。
    ///
    /// 用 ToolHelp 快照按 exe 名判断 —— **不能**用窗口标题: 实测 LrC 主窗口标题
    /// 永远是"图库", 导入是模态对话框、不改标题(见文件头第 1 条)。
    pub fn is_running() -> bool {        use windows::Win32::Foundation::CloseHandle;
        use windows::Win32::System::Diagnostics::ToolHelp::{
            CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
            TH32CS_SNAPPROCESS,
        };

        unsafe {
            let Ok(snap) = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) else {
                return false;
            };
            let mut entry = PROCESSENTRY32W::default();
            entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
            let mut found = false;
            if Process32FirstW(snap, &mut entry).is_ok() {
                loop {
                    let wide: Vec<u16> = entry
                        .szExeFile
                        .iter()
                        .take_while(|c| **c != 0)
                        .copied()
                        .collect();
                    let name = String::from_utf16_lossy(&wide);
                    if name.eq_ignore_ascii_case("lightroom.exe") {
                        found = true;
                        break;
                    }
                    if Process32NextW(snap, &mut entry).is_err() {
                        break;
                    }
                }
            }
            let _ = CloseHandle(snap);
            found
        }
    }

    /// Lightroom 的进程 id(取第一个匹配的)。等窗口时要知道"哪个进程的窗口才算"。
    pub fn lightroom_pid() -> Option<u32> {
        use windows::Win32::Foundation::CloseHandle;
        use windows::Win32::System::Diagnostics::ToolHelp::{
            CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
            TH32CS_SNAPPROCESS,
        };

        unsafe {
            let Ok(snap) = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) else {
                return None;
            };
            let mut entry = PROCESSENTRY32W::default();
            entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
            let mut found: Option<u32> = None;
            if Process32FirstW(snap, &mut entry).is_ok() {
                loop {
                    let wide: Vec<u16> = entry
                        .szExeFile
                        .iter()
                        .take_while(|c| **c != 0)
                        .copied()
                        .collect();
                    if String::from_utf16_lossy(&wide).eq_ignore_ascii_case("lightroom.exe") {
                        found = Some(entry.th32ProcessID);
                        break;
                    }
                    if Process32NextW(snap, &mut entry).is_err() {
                        break;
                    }
                }
            }
            let _ = CloseHandle(snap);
            found
        }
    }

    /// 是否至少有一个属于当前进程的 Lightroom 顶层窗口**已经有标题**。
    ///
    /// **为什么不能用 is_running() 代替**: 实测(本机 15.2.1, 4.9MB 目录库)
    /// 进程出现只要 **0.4 秒**, 而主窗口有标题要到 **4.6 秒**。等进程等于没等 ——
    /// 前端的"正在启动 Lightroom"提示会一闪而过(实测就是这么被用户看穿的)。
    pub fn has_main_window(pid: u32) -> bool {
        use windows::core::BOOL;
        use windows::Win32::Foundation::{HWND, LPARAM, TRUE};
        use windows::Win32::UI::WindowsAndMessaging::{
            EnumWindows, GetWindowTextLengthW, GetWindowThreadProcessId,
        };

        // EnumWindows 的回调没有"用户数据"参数, 用一层全局暂存传递 pid/结果。
        // 只在 Windows 且只有本模块用; 进程内调用是串行的(wait_until_started 一次一个)。
        struct Probe {
            pid: u32,
            found: bool,
        }
        thread_local! {
            static PROBE: std::cell::RefCell<Option<Probe>> = const { std::cell::RefCell::new(None) };
        }

        extern "system" fn cb(hwnd: HWND, _l: LPARAM) -> BOOL {
            PROBE.with(|p| {
                if let Some(probe) = p.borrow_mut().as_mut() {
                    let mut wpid = 0u32;
                    unsafe { GetWindowThreadProcessId(hwnd, Some(&mut wpid)) };
                    if wpid == probe.pid {
                        // 有标题 = 主窗口已经画出来了(标题栏为空的那一堆隐藏窗口不算)
                        let len = unsafe { GetWindowTextLengthW(hwnd) };
                        if len > 0 {
                            probe.found = true;
                            return BOOL(0); // 找到就停
                        }
                    }
                }
                TRUE
            })
        }

        PROBE.with(|p| *p.borrow_mut() = Some(Probe { pid, found: false }));
        unsafe {
            let _ = EnumWindows(Some(cb), LPARAM(0));
        }
        let found = PROBE.with(|p| p.borrow().as_ref().map(|x| x.found).unwrap_or(false));
        PROBE.with(|p| *p.borrow_mut() = None);
        found
    }
}

// ── 跨平台的"能不能用 / 关没关" ─────────────────────────────────────
//
// 这里把 `#[cfg]` 收在一处, 让下面的命令体保持平台无关(命令体里散落 cfg 很容易漏一边)。

/// 定位 Lightroom.exe。按**实测确认过的优先级**依次尝试(本机只有第一条命中):
/// `.lrcat` 文件关联 → 卸载项 InstallLocation(再探一层) → App Paths → Program Files。
/// 非 Windows 返回 None(衔接本身也不支持)。
pub fn find_exe() -> Option<String> {
    #[cfg(target_os = "windows")]
    {
        win::find_exe().map(|(exe, _)| exe)
    }
    #[cfg(not(target_os = "windows"))]
    {
        None
    }
}

/// 同时拿到 exe 与**命中的探测方式**(只给单测/排查用)。
///
/// 非测试构建里没有调用方(命令只需要路径), 但它是"哪条策略命中"的唯一观测点,
/// 排查"找不到 Lightroom"时很有用, 所以保留并显式允许 dead_code。
#[cfg(target_os = "windows")]
#[allow(dead_code)]
pub fn find_exe_with_source() -> Option<(String, String)> {
    win::find_exe()
}

/// LrC 是否正在运行。
pub fn lightroom_is_running() -> bool {
    #[cfg(target_os = "windows")]
    {
        win::is_running()
    }
    #[cfg(not(target_os = "windows"))]
    {
        // 非 Windows 暂不支持衔接, 一律报"没在运行" → 由 is_supported() 挡住
        false
    }
}

/// 本平台是否支持与 LrC 衔接(macOS 未实机验证, 先明确不支持)。
pub fn lightroom_supported() -> bool {
    cfg!(target_os = "windows")
}

/// 强制结束 Lightroom(供用户明确选择"强制关闭并继续"时调用)。
///
/// **为什么只能强制**: 实机试过三种"礼貌"的关闭方式, LrC 15.2.1 全都不吃 ——
/// `CloseMainWindow()`(等价点关闭按钮)、`taskkill /IM`(不带 /F)、给主窗口发
/// `WM_CLOSE`, 三种都报成功但 30 秒后进程仍在。所以这里直接用最强的信号,
/// 并且**只有用户在界面上明确选择"强制关闭"时才会走到这里** ——
/// 未保存的调整会丢, 这个决定必须由用户做, 不能由程序擅自做。
fn force_close_sync() -> Result<(), LrcError> {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt as _;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let mut cmd = std::process::Command::new("taskkill");
        cmd.args(["/IM", "lightroom.exe", "/F"]);
        cmd.stdin(std::process::Stdio::null());
        cmd.stdout(std::process::Stdio::null());
        cmd.stderr(std::process::Stdio::null());
        cmd.creation_flags(CREATE_NO_WINDOW);
        match cmd.status() {
            Ok(_) => Ok(()),
            Err(e) => {
                eprintln!("force_close_lightroom: taskkill 启动失败: {}", e);
                Err(LrcError::LaunchFailed)
            }
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err(LrcError::NotSupported)
    }
}

/// 等新启动的 Lightroom **主窗口**真的出现(最多 `timeout`)。
///
/// **为什么必须等**: `spawn` 只是把进程创建出来就返回(几毫秒), 而 Lightroom 要
/// 好几秒才有窗口 —— 实测本机(15.2.1, 4.9MB 目录库) **进程 0.4s / 窗口 4.6s**。
/// 等"进程"等于没等, 前端那个"正在启动 Lightroom"的提示会因为状态瞬间变回 idle
/// 而**根本没机会上屏**(实测: 用户只看到"正在导入", 然后就没了)。
///
/// 判断依据是**窗口标题非空**, 不是进程存在; 也不能用标题内容判断状态(标题里那个
/// "图库"是模块名, 导入是模态框、不改标题 —— 见文件头)。
///
/// 超时不算失败: 进程已经创建成功, 只是慢 —— 返回 true 让调用方照常报成功。
fn wait_until_started(timeout: std::time::Duration) -> bool {
    #[cfg(target_os = "windows")]
    {
        let start = std::time::Instant::now();
        while start.elapsed() < timeout {
            // 每轮重新取 pid: 首次启动时进程可能还没起来, 不能只取一次
            if let Some(pid) = win::lightroom_pid() {
                if win::has_main_window(pid) {
                    return true;
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(250));
        }
        false
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = timeout;
        true
    }
}

/// 启动 Lightroom 后最多等它出现多久。
///
/// 实测冷启动到有窗口约 6–12 秒(本机 4.9MB 目录库); 大库更久, 所以给足余量。
/// 超时不会误报失败(进程已创建), 只是提示会提前收起。
const WAIT_STARTED: std::time::Duration = std::time::Duration::from_secs(30);

/// 等在途的 Lightroom 进程真正消失(最多 `timeout`)。
///
/// 为什么必须等: 刚 `taskkill` 完立刻启动, 新进程可能被尚未退干净的旧实例吞掉
/// (Windows 的单实例机制), 于是路径参数又白传了。
fn wait_until_exited(timeout: std::time::Duration) -> bool {
    let start = std::time::Instant::now();
    while start.elapsed() < timeout {
        if !lightroom_is_running() {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_millis(300));
    }
    !lightroom_is_running()
}

// ── 命令 ────────────────────────────────────────────────────────────

/// 探测 Lightroom 是否可用。前端启动时调一次, 失败/找不到都不报错(功能整体隐藏)。
#[tauri::command]
pub async fn probe_lightroom() -> LightroomProbe {
    #[cfg(target_os = "windows")]
    {
        return tokio::task::spawn_blocking(|| {
            let hit = win::find_exe();
            let running = win::is_running();
            match hit {
                Some((exe, source)) => LightroomProbe {
                    found: true,
                    exe: Some(exe),
                    source: Some(source),
                    running,
                },
                None => LightroomProbe {
                    found: false,
                    exe: None,
                    source: None,
                    running,
                },
            }
        })
        .await
        .unwrap_or(LightroomProbe {
            found: false,
            exe: None,
            source: None,
            running: false,
        });
    }
    #[cfg(not(target_os = "windows"))]
    {
        LightroomProbe {
            found: false,
            exe: None,
            source: None,
            running: false,
        }
    }
}

/// 把"照片所在文件夹"交给 Lightroom。
///
/// **必须冷启动**: 实机确认(本机 LrC 15.2.1)在 Lightroom 已在运行时, 传进去的路径
/// 会被 Adobe **忽略**, 导入对话框停在它上一次的源上。所以:
///   · 进程存在 → 直接返回 [`LrcError::AlreadyRunning`], 由前端问用户
///     "关掉 Lightroom 重试" / "强制关闭并继续" —— **绝不擅自杀用户的进程**;
///   · 进程不存在 → 启动 `Lightroom.exe "<文件夹>"`, 导入对话框的源就是该文件夹
///     (实测: 传空文件夹报"没有找到照片 / 0 张照片 / 0 字节")。
///
/// 返回 exe 路径(前端只用于文案/排查)。
#[tauri::command]
pub async fn send_to_lightroom(folder_path: String) -> Result<String, String> {
    if folder_path.trim().is_empty() {
        return Err(LrcError::NoFolder.to_string());
    }
    if !lightroom_supported() {
        return Err(LrcError::NotSupported.to_string());
    }
    let folder = PathBuf::from(&folder_path);

    tokio::task::spawn_blocking(move || {
        // 顺序有讲究: 先查"已运行", 再校验文件夹, 最后找 exe。
        // "已运行"放第一位是因为它是用户最容易踩、且提示最具体的一个失败原因;
        // 若先报"文件夹不存在", 用户会去改路径, 而真正的问题是另一个。
        if lightroom_is_running() {
            return Err(LrcError::AlreadyRunning);
        }
        if !folder.is_dir() {
            return Err(LrcError::NoFolder);
        }
        let exe = match find_exe() {
            Some(e) => e,
            None => return Err(LrcError::NotFound),
        };

        let mut cmd = std::process::Command::new(&exe);
        cmd.arg(&folder);
        // 不继承本进程的 stdio; 也不弹控制台窗口
        cmd.stdin(std::process::Stdio::null());
        cmd.stdout(std::process::Stdio::null());
        cmd.stderr(std::process::Stdio::null());
        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt as _;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        match cmd.spawn() {
            Ok(_child) => {
                // 等它真的有窗口再返回: 否则前端的"正在启动 Lightroom"提示会因为
                // 状态瞬间回落而根本看不到(见 wait_until_started 的说明)。
                wait_until_started(WAIT_STARTED);
                Ok(exe)
            }
            Err(e) => {
                eprintln!("send_to_lightroom: spawn 失败: {}", e);
                Err(LrcError::LaunchFailed)
            }
        }
    })
    .await
    .map_err(|_| LrcError::Unknown.to_string())?
    .map_err(|e: LrcError| e.to_string())
}

/// 强制结束 Lightroom, 并**等到它真的退出**才返回。
///
/// 只在用户于界面上明确选择"强制关闭并继续"时调用 —— 未保存的调整会丢。
/// 等到退出是必须的: 旧实例没退干净时启动新实例, 路径参数会被单实例机制吞掉。
#[tauri::command]
pub async fn force_close_lightroom() -> Result<(), String> {
    tokio::task::spawn_blocking(|| {
        if !lightroom_is_running() {
            return Ok(()); // 已经不在了: 幂等, 不报错
        }
        force_close_sync()?;
        if wait_until_exited(std::time::Duration::from_secs(20)) {
            Ok(())
        } else {
            // 等不到就别假装成功 —— 前端会把 alreadyRunning 变成一句"还没关掉"
            Err(LrcError::AlreadyRunning)
        }
    })
    .await
    .map_err(|_| LrcError::Unknown.to_string())?
    .map_err(|e: LrcError| e.to_string())
}

// ── "这个文件夹是不是空的" ─────────────────────────────────────────────

/// 目录是否存在且**完全没有条目**(文件和子目录都算)。
///
/// 为什么需要它: Phase 7 的"导入后交给 LrC"要求 Lightroom 的导入页面里
/// **只有刚导入的那几张**。若目标文件夹本来就是空的, 直接导进去即可; 若里面
/// 已经有东西(旧照片、子目录、甚至用户的 Lightroom 目录库), 就必须导进一个
/// 新建的子文件夹 —— 否则 LrC 会把整个文件夹的内容都列出来。
///
/// 语义细节(都在单测里钉住):
///   · **路径不存在按"空"处理** —— 导入会自己 `create_dir_all`, 调用方不必区分
///     "还没有这个目录"和"目录是空的";
///   · **只有子目录也算非空** —— 用户自己的分类目录不该被当成空目录塞照片;
///   · 不递归: 只看这一层。
fn is_dir_empty_inner(dir: &Path) -> bool {
    match std::fs::read_dir(dir) {
        Ok(mut entries) => entries.next().is_none(),
        Err(_) => true, // 不存在 / 读不了 → 交给后续的导入去报真正的错
    }
}

/// 前端在"导入后交给 LrC"之前调一次, 决定是直接用目标文件夹还是另建子文件夹。
#[tauri::command]
pub async fn is_dir_empty(dir_path: String) -> Result<bool, String> {
    tokio::task::spawn_blocking(move || is_dir_empty_inner(Path::new(&dir_path)))
        .await
        .map_err(|e| format!("{}", e))
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_dir_empty_reports_missing_and_empty_and_nonempty() {
        let base = std::env::temp_dir().join(format!("ifx_isempty_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        std::fs::create_dir_all(&base).unwrap();

        // 不存在的路径: 按"空"处理 —— 导入会自己建目录, 调用方不必区分
        assert!(is_dir_empty_inner(&base.join("nope")));

        // 真空目录
        let empty = base.join("empty");
        std::fs::create_dir_all(&empty).unwrap();
        assert!(is_dir_empty_inner(&empty));

        // 有文件(哪怕只是一个无关文件)
        std::fs::write(empty.join("a.txt"), b"x").unwrap();
        assert!(!is_dir_empty_inner(&empty));

        // 只有子目录也算非空(用户自己的分类目录不该被塞进照片)
        let withsub = base.join("withsub");
        std::fs::create_dir_all(withsub.join("child")).unwrap();
        assert!(!is_dir_empty_inner(&withsub));

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn expand_env_vars_replaces_known_and_keeps_unknown() {
        std::env::set_var("IFX_TEST_VAR", r"C:\somewhere");
        assert_eq!(
            expand_env_vars(r"%IFX_TEST_VAR%\Adobe\Lightroom.exe"),
            r"C:\somewhere\Adobe\Lightroom.exe"
        );
        // 未知变量原样保留(不静默吞掉, 让"文件是否存在"去兜底)
        assert_eq!(
            expand_env_vars(r"%IFX_NO_SUCH_VAR%\x.exe"),
            r"%IFX_NO_SUCH_VAR%\x.exe"
        );
        // 单个 % 不是变量
        assert_eq!(expand_env_vars("100% done"), "100% done");
        // 空字符串
        assert_eq!(expand_env_vars(""), "");
    }

    #[test]
    fn exe_from_command_line_handles_quoted_and_bare() {
        assert_eq!(
            exe_from_command_line(r#""A:\lrc\Adobe Lightroom Classic\Lightroom.exe" "%1""#).as_deref(),
            Some(r"A:\lrc\Adobe Lightroom Classic\Lightroom.exe")
        );
        assert_eq!(
            exe_from_command_line(r"C:\Program Files\Adobe\Lightroom.exe %1").as_deref(),
            Some(r"C:\Program")
        );
        assert_eq!(exe_from_command_line("   "), None);
        // 只有开引号没有闭引号 → 不可解析
        assert_eq!(exe_from_command_line(r#""A:\broken"#), None);
    }

    #[test]
    fn unquote_strips_one_layer_only() {
        assert_eq!(unquote(r#""C:\a b""#), r"C:\a b");
        assert_eq!(unquote(r"C:\a b"), r"C:\a b");
        assert_eq!(unquote("  "), "");
    }

    #[test]
    fn error_codes_are_the_contract_used_by_i18n() {
        // 前端 src/lightroom.ts 的 LRC_ERR_CODES 必须与此完全一致(改这里就要改那边)
        assert_eq!(LrcError::NotFound.code(), "notFound");
        assert_eq!(LrcError::NoFolder.code(), "noFolder");
        assert_eq!(LrcError::AlreadyRunning.code(), "alreadyRunning");
        assert_eq!(LrcError::LaunchFailed.code(), "launchFailed");
        assert_eq!(LrcError::NotSupported.code(), "notSupported");
        assert_eq!(LrcError::NotImplemented.code(), "notImplemented");
        assert_eq!(LrcError::NoNewPhotos.code(), "noNewPhotos");
        assert_eq!(LrcError::Unknown.code(), "unknown");
    }

    /// `is_dir_empty` 与"等进程退出"这两个辅助函数的边界。
    #[test]
    fn wait_until_exited_returns_fast_when_not_running() {
        // 本机测试环境里 LrC 通常没开; 即便开着也只验证"它不 panic 且返回 bool"
        let start = std::time::Instant::now();
        let _ = wait_until_exited(std::time::Duration::from_millis(50));
        assert!(
            start.elapsed() < std::time::Duration::from_secs(5),
            "wait_until_exited 不应超出给定超时太多"
        );
    }

    #[test]
    fn lightroom_supported_matches_platform() {
        assert_eq!(lightroom_supported(), cfg!(target_os = "windows"));
        if !lightroom_supported() {
            assert!(find_exe().is_none(), "非 Windows 不应找到 Lightroom.exe");
        }
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn find_exe_returns_an_existing_file_when_it_hits() {
        // 本机装有 LrC(实测), 所以这里应当命中; 但 CI/无 LrC 的机器上允许落空,
        // 因此只断言"命中时路径必须真实存在" —— 不把"必须找到"写成硬断言。
        if let Some((exe, source)) = find_exe_with_source() {
            assert!(
                Path::new(&exe).is_file(),
                "find_exe 命中了不存在的路径: {} (source={})",
                exe,
                source
            );
            assert!(
                exe.to_lowercase().ends_with("lightroom.exe"),
                "命中的不是 Lightroom.exe: {}",
                exe
            );
        }
    }
}
