mod commands;

use tauri::menu::{Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::{Emitter, Manager};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .menu(|handle| create_menu(handle))
        .on_menu_event(|app, event| {
            match event.id().as_ref() {
                "open-idml" => {
                    // IDML 파일 열기 이벤트
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.emit("menu-open-idml", ());
                    }
                }
                "open-hwpx" => {
                    // HWPX 파일 열기 이벤트 (HWPX → IDML 변환)
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.emit("menu-open-hwpx", ());
                    }
                }
                "quit" => {
                    app.exit(0);
                }
                "playground" => {
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.emit("menu-playground", ());
                    }
                }
                "extract" => {
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.emit("menu-extract", ());
                    }
                }
                "about" => {
                    // 프론트엔드에 정보 이벤트 전달
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.emit("menu-about", ());
                    }
                }
                _ => {}
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::analyze_idml,
            commands::convert_idml,
            commands::convert_hwpx_to_idml,
            commands::get_jar_path,
            commands::generate_preview,
            commands::generate_image_preview,
            commands::generate_vector_preview,
            commands::generate_master_preview,
            commands::get_text_frame_detail,
            commands::create_idml_from_masters,
            commands::extract_template_schema,
            commands::merge_idml,
            commands::read_text_file,
            commands::write_text_file,
            commands::extract_questions,
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

    // File 메뉴
    let open_idml = MenuItem::with_id(handle, "open-idml", "Open IDML...", true, Some("CmdOrCtrl+O"))?;
    let open_hwpx = MenuItem::with_id(handle, "open-hwpx", "Open HWPX...", true, Some("CmdOrCtrl+Shift+O"))?;

    #[cfg(not(target_os = "macos"))]
    let quit = MenuItem::with_id(handle, "quit", "Quit", true, Some("CmdOrCtrl+Q"))?;

    #[cfg(target_os = "macos")]
    let file_menu = Submenu::with_items(
        handle,
        "File",
        true,
        &[
            &open_idml,
            &open_hwpx,
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
            &open_idml,
            &open_hwpx,
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

    // View 메뉴
    let playground = MenuItem::with_id(handle, "playground", "Playground", true, Some("CmdOrCtrl+P"))?;
    let extract = MenuItem::with_id(handle, "extract", "문제 추출하기", true, Some("CmdOrCtrl+E"))?;

    #[cfg(debug_assertions)]
    let view_menu = {
        let devtools = MenuItem::with_id(handle, "devtools", "Toggle DevTools", true, Some("CmdOrCtrl+Shift+I"))?;
        Submenu::with_items(
            handle,
            "View",
            true,
            &[
                &playground,
                &extract,
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
            &playground,
            &extract,
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
        Submenu::with_items(
            handle,
            "Help",
            true,
            &[&about],
        )?
    };

    // 메뉴바 생성
    #[cfg(target_os = "macos")]
    let menu = Menu::with_items(
        handle,
        &[&app_menu, &file_menu, &edit_menu, &view_menu, &window_menu],
    )?;

    #[cfg(not(target_os = "macos"))]
    let menu = Menu::with_items(
        handle,
        &[&file_menu, &edit_menu, &view_menu, &help_menu],
    )?;

    Ok(menu)
}
