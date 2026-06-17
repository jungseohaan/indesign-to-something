mod commands;
mod extract_cache;
mod indesign;

use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::{Emitter, Manager};

/// 실행 중인 subprocess의 PID + 취소 플래그.
/// `app.manage(ProcessKillHandle::new())`로 등록, 커맨드에서 `State<ProcessKillHandle>`로 접근.
pub struct ProcessKillHandle {
    pub cancelled: std::sync::atomic::AtomicBool,
    pub pid: std::sync::Mutex<Option<u32>>,
}

impl ProcessKillHandle {
    pub fn new() -> Self {
        Self {
            cancelled: std::sync::atomic::AtomicBool::new(false),
            pid: std::sync::Mutex::new(None),
        }
    }

    /// 새 프로세스 시작 전 상태 초기화
    pub fn reset(&self) {
        self.cancelled
            .store(false, std::sync::atomic::Ordering::SeqCst);
        if let Ok(mut g) = self.pid.lock() {
            *g = None;
        }
    }

    /// 프로세스 PID 등록
    pub fn register_pid(&self, pid: u32) {
        if let Ok(mut g) = self.pid.lock() {
            *g = Some(pid);
        }
    }

    /// PID 해제 (프로세스 종료 후)
    pub fn deregister_pid(&self) {
        if let Ok(mut g) = self.pid.lock() {
            *g = None;
        }
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(std::sync::atomic::Ordering::SeqCst)
    }

    /// 취소 플래그 설정 + 현재 프로세스 SIGKILL
    pub fn cancel_and_kill(&self) {
        self.cancelled
            .store(true, std::sync::atomic::Ordering::SeqCst);
        if let Ok(g) = self.pid.lock() {
            if let Some(pid) = *g {
                #[cfg(unix)]
                {
                    let _ = std::process::Command::new("kill")
                        .args(["-9", &pid.to_string()])
                        .output();
                }
                #[cfg(windows)]
                {
                    let _ = std::process::Command::new("taskkill")
                        .args(["/PID", &pid.to_string(), "/F"])
                        .output();
                }
            }
        }
    }
}

struct SleepPreventionState {
    child: Option<std::process::Child>,
    leases: usize,
}

/// macOS idle sleep prevention for long InDesign/Java operations.
pub struct SleepPreventionHandle {
    state: std::sync::Mutex<SleepPreventionState>,
}

pub struct SleepPreventionLease<'a> {
    handle: &'a SleepPreventionHandle,
}

impl SleepPreventionHandle {
    pub fn new() -> Self {
        Self {
            state: std::sync::Mutex::new(SleepPreventionState {
                child: None,
                leases: 0,
            }),
        }
    }

    pub fn acquire(&self, reason: &str) -> SleepPreventionLease<'_> {
        self.begin(reason);
        SleepPreventionLease { handle: self }
    }

    pub fn begin(&self, reason: &str) {
        if let Ok(mut state) = self.state.lock() {
            if state.leases == 0 {
                state.child = Self::spawn_caffeinate();
                if state.child.is_some() {
                    eprintln!("[sleep] caffeinate started: {}", reason);
                } else {
                    eprintln!(
                        "[sleep] caffeinate unavailable; continuing without sleep prevention: {}",
                        reason
                    );
                }
            }
            state.leases += 1;
        }
    }

    pub fn end(&self) {
        if let Ok(mut state) = self.state.lock() {
            if state.leases > 0 {
                state.leases -= 1;
            }
            if state.leases == 0 {
                if let Some(mut child) = state.child.take() {
                    let _ = child.kill();
                    let _ = child.wait();
                    eprintln!("[sleep] caffeinate stopped");
                }
            }
        }
    }

    #[cfg(target_os = "macos")]
    fn spawn_caffeinate() -> Option<std::process::Child> {
        std::process::Command::new("/usr/bin/caffeinate")
            .args(["-dimsu"])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .ok()
    }

    #[cfg(not(target_os = "macos"))]
    fn spawn_caffeinate() -> Option<std::process::Child> {
        None
    }
}

impl Drop for SleepPreventionLease<'_> {
    fn drop(&mut self) {
        self.handle.end();
    }
}

/// 현재 실행 중인 subprocess를 즉시 종료한다.
#[tauri::command]
async fn cancel_current(app: tauri::AppHandle) -> Result<(), String> {
    let kill = app.state::<ProcessKillHandle>();
    kill.cancel_and_kill();
    Ok(())
}

#[tauri::command]
async fn begin_sleep_prevention(app: tauri::AppHandle) -> Result<(), String> {
    let sleep = app.state::<SleepPreventionHandle>();
    sleep.begin("frontend batch");
    Ok(())
}

#[tauri::command]
async fn end_sleep_prevention(app: tauri::AppHandle) -> Result<(), String> {
    let sleep = app.state::<SleepPreventionHandle>();
    sleep.end();
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(ProcessKillHandle::new())
        .manage(SleepPreventionHandle::new())
        .menu(|handle| create_menu(handle))
        .on_menu_event(|app, event| {
            // SPEC-019 M1: 메뉴 ID → 프론트엔드 이벤트 단순 라우팅.
            // 동일한 ID 패턴(menu-<id>)으로 emit하여 핸들러 중복 제거.
            let id = event.id().as_ref().to_string();
            match id.as_str() {
                "quit" => {
                    app.exit(0);
                }
                _ => {
                    if let Some(window) = app.get_webview_window("main") {
                        let event_name = format!("menu-{}", id);
                        let _ = window.emit(&event_name, ());
                    }
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::analyze_idml,
            commands::convert_idml,
            commands::convert_hwpx_to_idml,
            commands::get_default_teaching_prompt,
            commands::generate_teaching,
            commands::get_jar_path,
            commands::generate_preview,
            commands::generate_pdf_preview,
            commands::generate_pdf_page_preview,
            commands::generate_image_preview,
            commands::generate_vector_preview,
            commands::generate_master_preview,
            commands::get_text_frame_detail,
            commands::read_text_file,
            commands::write_text_file,
            commands::export_ast,
            commands::extract_indd,
            commands::clear_extract_cache,
            commands::extract_cache_stats,
            commands::read_resolved_json,
            commands::check_indesign,
            commands::open_file,
            commands::copy_file,
            commands::create_dir,
            commands::scan_indd_folder,
            commands::read_file_base64,
            cancel_current,
            begin_sleep_prevention,
            end_sleep_prevention,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn create_menu(handle: &tauri::AppHandle) -> Result<Menu<tauri::Wry>, tauri::Error> {
    // macOS 앱 메뉴
    #[cfg(target_os = "macos")]
    let app_menu = {
        let about = MenuItem::with_id(handle, "about", "About IDML to HWPX", true, None::<&str>)?;
        Submenu::with_items(
            handle,
            "IDML to HWPX",
            true,
            &[
                &about,
                &PredefinedMenuItem::separator(handle)?,
                &PredefinedMenuItem::services(handle, Some("Services"))?,
                &PredefinedMenuItem::separator(handle)?,
                &PredefinedMenuItem::hide(handle, Some("Hide"))?,
                &PredefinedMenuItem::hide_others(handle, Some("Hide Others"))?,
                &PredefinedMenuItem::show_all(handle, Some("Show All"))?,
                &PredefinedMenuItem::separator(handle)?,
                &PredefinedMenuItem::quit(handle, Some("Quit"))?,
            ],
        )?
    };

    // ────────────────────────────────────────────────────────────
    // File 메뉴 (SPEC-019 M1)
    // ────────────────────────────────────────────────────────────
    // 단축키 정리: ⌘⇧I 충돌 회피 → InDesign Open은 ⌘⌥I로 이동
    let open_indd = MenuItem::with_id(
        handle,
        "open-indd",
        "Open InDesign...",
        true,
        Some("CmdOrCtrl+Alt+I"),
    )?;
    let open_indd_folder = MenuItem::with_id(
        handle,
        "open-indd-folder",
        "Open InDesign Folder...",
        true,
        Some("CmdOrCtrl+Alt+F"),
    )?;
    let open_idml = MenuItem::with_id(
        handle,
        "open-idml",
        "Open IDML...",
        true,
        Some("CmdOrCtrl+O"),
    )?;
    let open_hwpx = MenuItem::with_id(
        handle,
        "open-hwpx",
        "Open HWPX...",
        true,
        Some("CmdOrCtrl+Shift+O"),
    )?;

    // Export 서브메뉴
    let export_hwpx =
        MenuItem::with_id(handle, "export-hwpx", "HWPX...", true, Some("CmdOrCtrl+E"))?;
    let export_submenu = Submenu::with_items(handle, "Export", true, &[&export_hwpx])?;

    let clear_cache = MenuItem::with_id(
        handle,
        "clear-extract-cache",
        "Clear Extract Cache",
        true,
        None::<&str>,
    )?;

    #[cfg(not(target_os = "macos"))]
    let quit = MenuItem::with_id(handle, "quit", "Quit", true, Some("CmdOrCtrl+Q"))?;

    #[cfg(target_os = "macos")]
    let file_menu = Submenu::with_items(
        handle,
        "File",
        true,
        &[
            &open_indd,
            &open_indd_folder,
            &open_idml,
            &open_hwpx,
            &PredefinedMenuItem::separator(handle)?,
            &export_submenu,
            &PredefinedMenuItem::separator(handle)?,
            &clear_cache,
            &PredefinedMenuItem::separator(handle)?,
            &PredefinedMenuItem::close_window(handle, Some("Close Window"))?,
        ],
    )?;

    #[cfg(not(target_os = "macos"))]
    let file_menu = Submenu::with_items(
        handle,
        "File",
        true,
        &[
            &open_indd,
            &open_indd_folder,
            &open_idml,
            &open_hwpx,
            &PredefinedMenuItem::separator(handle)?,
            &export_submenu,
            &PredefinedMenuItem::separator(handle)?,
            &clear_cache,
            &PredefinedMenuItem::separator(handle)?,
            &quit,
        ],
    )?;

    // Edit 메뉴 (기본 편집 기능)
    let edit_menu = Submenu::with_items(
        handle,
        "Edit",
        true,
        &[
            &PredefinedMenuItem::undo(handle, Some("Undo"))?,
            &PredefinedMenuItem::redo(handle, Some("Redo"))?,
            &PredefinedMenuItem::separator(handle)?,
            &PredefinedMenuItem::cut(handle, Some("Cut"))?,
            &PredefinedMenuItem::copy(handle, Some("Copy"))?,
            &PredefinedMenuItem::paste(handle, Some("Paste"))?,
            &PredefinedMenuItem::select_all(handle, Some("Select All"))?,
        ],
    )?;

    // ────────────────────────────────────────────────────────────
    // View 메뉴 (SPEC-019 M1)
    // ────────────────────────────────────────────────────────────
    let tab_converter = MenuItem::with_id(
        handle,
        "tab-converter",
        "HWPX Converter Tab",
        true,
        Some("CmdOrCtrl+1"),
    )?;
    let tab_teaching = MenuItem::with_id(
        handle,
        "tab-teaching",
        "Teaching Material Tab",
        true,
        Some("CmdOrCtrl+2"),
    )?;

    #[cfg(debug_assertions)]
    let view_menu = {
        let devtools = MenuItem::with_id(
            handle,
            "devtools",
            "Toggle DevTools",
            true,
            Some("CmdOrCtrl+Shift+I"),
        )?;
        Submenu::with_items(
            handle,
            "View",
            true,
            &[
                &tab_converter,
                &tab_teaching,
                &PredefinedMenuItem::separator(handle)?,
                &PredefinedMenuItem::fullscreen(handle, Some("Toggle Fullscreen"))?,
                &PredefinedMenuItem::separator(handle)?,
                &devtools,
            ],
        )?
    };

    #[cfg(not(debug_assertions))]
    let view_menu = Submenu::with_items(
        handle,
        "View",
        true,
        &[
            &tab_converter,
            &tab_teaching,
            &PredefinedMenuItem::separator(handle)?,
            &PredefinedMenuItem::fullscreen(handle, Some("Toggle Fullscreen"))?,
        ],
    )?;

    // Window 메뉴 (macOS용)
    #[cfg(target_os = "macos")]
    let window_menu = Submenu::with_items(
        handle,
        "Window",
        true,
        &[
            &PredefinedMenuItem::minimize(handle, Some("Minimize"))?,
            &PredefinedMenuItem::maximize(handle, Some("Zoom"))?,
            &PredefinedMenuItem::separator(handle)?,
            &PredefinedMenuItem::close_window(handle, Some("Close"))?,
        ],
    )?;

    // Help 메뉴 (non-macOS)
    #[cfg(not(target_os = "macos"))]
    let help_menu = {
        let about = MenuItem::with_id(handle, "about", "About IDML to HWPX", true, None::<&str>)?;
        Submenu::with_items(handle, "Help", true, &[&about])?
    };

    // 메뉴바 생성
    #[cfg(target_os = "macos")]
    let menu = Menu::with_items(
        handle,
        &[&app_menu, &file_menu, &edit_menu, &view_menu, &window_menu],
    )?;

    #[cfg(not(target_os = "macos"))]
    let menu = Menu::with_items(handle, &[&file_menu, &edit_menu, &view_menu, &help_menu])?;

    Ok(menu)
}
