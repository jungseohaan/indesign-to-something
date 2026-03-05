mod preview;
mod conversion;
mod extract;

// Re-export all commands for lib.rs
pub use preview::*;
pub use conversion::*;
pub use extract::*;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};
use tokio::process::Command;

/// Find Java executable path on macOS
pub(crate) fn find_java() -> String {
    // Try common Java locations on macOS
    let java_paths = [
        "/opt/homebrew/opt/java/bin/java",
        "/opt/homebrew/opt/openjdk/bin/java",
        "/opt/homebrew/opt/openjdk@17/bin/java",
        "/opt/homebrew/opt/openjdk@21/bin/java",
        "/usr/bin/java",
        "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java",
        "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java",
        "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/bin/java",
        "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/java",
    ];

    for path in &java_paths {
        if std::path::Path::new(path).exists() {
            return path.to_string();
        }
    }

    // Try to get Java home from java_home utility
    if let Ok(output) = std::process::Command::new("/usr/libexec/java_home")
        .output()
    {
        if output.status.success() {
            let java_home = String::from_utf8_lossy(&output.stdout).trim().to_string();
            let java_bin = format!("{}/bin/java", java_home);
            if std::path::Path::new(&java_bin).exists() {
                return java_bin;
            }
        }
    }

    // Fallback to just "java" and hope PATH is set
    "java".to_string()
}

// ─────────────────────────────────────────────────────────────────
// Shared Types
// ─────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct IDMLStructure {
    pub spreads: Vec<SpreadInfo>,
    #[serde(default)]
    pub master_spreads: Vec<MasterSpreadInfo>,
    pub total_text_frames: i32,
    pub total_image_frames: i32,
    pub total_vector_shapes: i32,
    pub total_tables: i32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct MasterSpreadInfo {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub page_count: i32,
    #[serde(default)]
    pub text_frame_count: i32,
    #[serde(default)]
    pub image_frame_count: i32,
    #[serde(default)]
    pub vector_count: i32,
    #[serde(default)]
    pub group_count: i32,
    #[serde(default)]
    pub applied_pages: Vec<String>,
    // Master page layout info
    #[serde(default)]
    pub page_width: f64,
    #[serde(default)]
    pub page_height: f64,
    #[serde(default)]
    pub margin_top: f64,
    #[serde(default)]
    pub margin_bottom: f64,
    #[serde(default)]
    pub margin_left: f64,
    #[serde(default)]
    pub margin_right: f64,
    #[serde(default)]
    pub column_count: i32,
    #[serde(default)]
    pub column_gutter: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SpreadInfo {
    pub id: String,
    pub page_count: i32,
    pub pages: Vec<PageInfo>,
    pub text_frame_count: i32,
    pub image_frame_count: i32,
    pub vector_count: i32,
    pub master_spread_name: Option<String>,
    // Spread layout details
    #[serde(default)]
    pub bounds_top: f64,
    #[serde(default)]
    pub bounds_left: f64,
    #[serde(default)]
    pub bounds_bottom: f64,
    #[serde(default)]
    pub bounds_right: f64,
    #[serde(default)]
    pub total_width: f64,
    #[serde(default)]
    pub total_height: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PageInfo {
    pub id: String,
    pub name: String,
    pub width: f64,
    pub height: f64,
    pub frames: Vec<FrameInfo>,
    // Page layout details
    #[serde(default)]
    pub page_number: i32,
    pub geometric_bounds: Option<Vec<f64>>,  // [top, left, bottom, right]
    pub item_transform: Option<Vec<f64>>,    // 6-element transform matrix
    #[serde(default)]
    pub margin_top: f64,
    #[serde(default)]
    pub margin_bottom: f64,
    #[serde(default)]
    pub margin_left: f64,
    #[serde(default)]
    pub margin_right: f64,
    #[serde(default)]
    pub column_count: i32,
    pub master_spread: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct FrameInfo {
    pub id: String,
    #[serde(rename = "type")]
    pub frame_type: String,
    pub label: String,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    #[serde(default)]
    pub link_path: Option<String>,
    #[serde(default)]
    pub needs_preview: bool,
    #[serde(default)]
    pub children: Option<Vec<FrameInfo>>,
    #[serde(default)]
    pub story_content: Option<StoryContentInfo>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct StoryContentInfo {
    pub story_id: String,
    pub paragraph_count: i32,
    #[serde(default)]
    pub truncated: bool,
    pub paragraphs: Vec<ParagraphSummary>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ParagraphSummary {
    #[serde(default)]
    pub index: i32,
    pub style_name: Option<String>,
    pub runs: Vec<RunSummary>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RunSummary {
    #[serde(rename = "type")]
    pub run_type: String,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub font_style: Option<String>,
    #[serde(default)]
    pub font_size: Option<f64>,
    #[serde(default)]
    pub frame_id: Option<String>,
    #[serde(default)]
    pub graphic_type: Option<String>,
    #[serde(default)]
    pub width: Option<f64>,
    #[serde(default)]
    pub height: Option<f64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ConvertOptions {
    pub spread_based: bool,
    pub vector_dpi: u32,
    pub include_images: bool,
    pub links_directory: Option<String>,
    #[serde(default)]
    pub resolved_json_path: Option<String>,
    #[serde(default)]
    pub start_page: Option<i32>,
    #[serde(default)]
    pub end_page: Option<i32>,
    #[serde(default = "default_layout_mode")]
    pub layout_mode: String,
    #[serde(default)]
    pub font_map: Option<std::collections::HashMap<String, String>>,
}

fn default_layout_mode() -> String {
    "preserve".to_string()
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ConvertResult {
    pub pages_converted: i32,
    pub frames_converted: i32,
    pub images_converted: i32,
    #[serde(default)]
    pub equations_converted: i32,
    #[serde(default)]
    pub styles_converted: i32,
    #[serde(default)]
    pub images_skipped: i32,
    #[serde(default)]
    pub images_psd: i32,
    #[serde(default)]
    pub images_ai: i32,
    #[serde(default)]
    pub images_tiff: i32,
    pub warnings: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProgressEvent {
    pub current: i32,
    pub total: i32,
    pub message: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LogEvent {
    pub message: String,
    pub timestamp: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ImagePreview {
    pub original_path: String,
    pub data_url: String,  // base64 data URL
    pub filename: String,
    pub width: i32,
    pub height: i32,
}

// ─────────────────────────────────────────────────────────────────
// Infrastructure Commands
// ─────────────────────────────────────────────────────────────────

/// Get the path to the converter JAR file
#[tauri::command]
pub async fn get_jar_path(app: AppHandle) -> Result<String, String> {
    // JAR file name pattern (cli jar has Main-Class manifest)
    let jar_names = ["idml-to-something-1.0.9-cli.jar", "hwpxlib-1.0.9-cli.jar", "hwpxlib-cli.jar", "idml-converter.jar"];

    // Try bundled resource first
    if let Ok(resource_dir) = app.path().resource_dir() {
        for name in &jar_names {
            let path = resource_dir.join(name);
            if path.exists() {
                return Ok(path.to_string_lossy().to_string());
            }
        }
    }

    // Development mode: look in parent project's target directory
    // The desktop folder is inside the main project
    let current_dir = std::env::current_dir().map_err(|e| e.to_string())?;

    // Try from desktop/src-tauri (when running cargo directly)
    let paths_to_try = [
        current_dir.join("../../target"),  // from src-tauri
        current_dir.join("../target"),     // from desktop
        current_dir.join("target"),        // from project root
    ];

    for base_path in &paths_to_try {
        for name in &jar_names {
            let path = base_path.join(name);
            if path.exists() {
                return Ok(path.canonicalize()
                    .unwrap_or(path)
                    .to_string_lossy()
                    .to_string());
            }
        }
    }

    // Also try absolute path for development (use $HOME to avoid hardcoding username)
    if let Ok(home) = std::env::var("HOME") {
        let absolute_path = std::path::PathBuf::from(&home)
            .join("works/indesign-to-something/target/idml-to-something-1.0.9-cli.jar");
        if absolute_path.exists() {
            return Ok(absolute_path.to_string_lossy().to_string());
        }
    }

    Err("Converter JAR not found".to_string())
}

/// Analyze an IDML file and return its structure
#[tauri::command]
pub async fn analyze_idml(path: String, jar_path: String) -> Result<IDMLStructure, String> {
    let java = find_java();
    let output = Command::new(&java)
        .args(["-jar", &jar_path, "--analyze", &path])
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Java process failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout).map_err(|e| format!("Failed to parse output: {}", e))
}

// ─────────────────────────────────────────────────────────────────
// File I/O Commands
// ─────────────────────────────────────────────────────────────────

/// Read a text file and return its content as a string
#[tauri::command]
pub async fn read_text_file(path: String) -> Result<String, String> {
    tokio::fs::read_to_string(&path)
        .await
        .map_err(|e| format!("Failed to read file: {}", e))
}

/// Write a text file
#[tauri::command]
pub async fn write_text_file(path: String, content: String) -> Result<(), String> {
    tokio::fs::write(&path, &content)
        .await
        .map_err(|e| format!("Failed to write file: {}", e))
}

/// resolved.json 파일 읽기.
/// InDesign 추출 시 생성된 resolved.json을 프론트엔드로 전달한다.
#[tauri::command]
pub async fn read_resolved_json(path: String) -> Result<serde_json::Value, String> {
    let content = tokio::fs::read_to_string(&path)
        .await
        .map_err(|e| format!("resolved.json 읽기 실패: {}", e))?;
    serde_json::from_str(&content)
        .map_err(|e| format!("resolved.json 파싱 실패: {}", e))
}

/// InDesign 설치 여부 확인. 설치되어 있으면 앱 경로 반환.
#[tauri::command]
pub fn check_indesign() -> Result<String, String> {
    crate::indesign::find_indesign_app()
}

/// 파일을 시스템 기본 앱으로 열기.
#[tauri::command]
pub fn open_file(path: String) -> Result<(), String> {
    std::process::Command::new("open")
        .arg(&path)
        .spawn()
        .map_err(|e| format!("파일 열기 실패: {}", e))?;
    Ok(())
}

/// 파일 복사.
#[tauri::command]
pub async fn copy_file(src: String, dst: String) -> Result<(), String> {
    tokio::fs::copy(&src, &dst)
        .await
        .map_err(|e| format!("파일 복사 실패: {}", e))?;
    Ok(())
}

/// 디렉토리 생성 (mkdir -p 상당).
#[tauri::command]
pub async fn create_dir(path: String) -> Result<(), String> {
    tokio::fs::create_dir_all(&path)
        .await
        .map_err(|e| format!("디렉토리 생성 실패: {}", e))
}

// ─────────────────────────────────────────────────────────────────
// INDD Folder Scan
// ─────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct InddFileEntry {
    pub path: String,
    pub filename: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct InddSubfolderEntry {
    pub folder_name: String,
    pub folder_path: String,
    pub indd_files: Vec<InddFileEntry>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct InddFolderScanResult {
    pub direct_files: Vec<String>,
    pub subfolder_files: Vec<InddSubfolderEntry>,
}

/// 폴더를 스캔하여 .indd 파일을 찾는다 (1 depth).
/// - direct_files: 폴더 직접 하위 .indd 경로
/// - subfolder_files: 서브폴더별 .indd 파일
#[tauri::command]
pub fn scan_indd_folder(path: String) -> Result<InddFolderScanResult, String> {
    let dir = std::path::Path::new(&path);
    if !dir.is_dir() {
        return Err("유효한 디렉토리가 아닙니다.".into());
    }

    let mut direct_files: Vec<String> = Vec::new();
    let mut subfolder_files: Vec<InddSubfolderEntry> = Vec::new();

    let entries = std::fs::read_dir(dir)
        .map_err(|e| format!("디렉토리 읽기 실패: {}", e))?;

    let mut sorted_entries: Vec<_> = entries
        .filter_map(|e| e.ok())
        .collect();
    sorted_entries.sort_by_key(|e| e.file_name());

    for entry in &sorted_entries {
        let entry_path = entry.path();
        if entry_path.is_file() {
            if let Some(ext) = entry_path.extension() {
                if ext.eq_ignore_ascii_case("indd") {
                    direct_files.push(entry_path.to_string_lossy().to_string());
                }
            }
        } else if entry_path.is_dir() {
            // 1 depth: 서브폴더 내 .indd 파일 수집
            let mut indd_files: Vec<InddFileEntry> = Vec::new();
            if let Ok(sub_entries) = std::fs::read_dir(&entry_path) {
                let mut sorted_sub: Vec<_> = sub_entries
                    .filter_map(|e| e.ok())
                    .collect();
                sorted_sub.sort_by_key(|e| e.file_name());

                for sub_entry in &sorted_sub {
                    let sub_path = sub_entry.path();
                    if sub_path.is_file() {
                        if let Some(ext) = sub_path.extension() {
                            if ext.eq_ignore_ascii_case("indd") {
                                indd_files.push(InddFileEntry {
                                    path: sub_path.to_string_lossy().to_string(),
                                    filename: sub_path.file_name()
                                        .unwrap_or_default()
                                        .to_string_lossy()
                                        .to_string(),
                                });
                            }
                        }
                    }
                }
            }
            if !indd_files.is_empty() {
                subfolder_files.push(InddSubfolderEntry {
                    folder_name: entry_path.file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .to_string(),
                    folder_path: entry_path.to_string_lossy().to_string(),
                    indd_files,
                });
            }
        }
    }

    Ok(InddFolderScanResult {
        direct_files,
        subfolder_files,
    })
}

/// 바이너리 파일을 base64로 읽기 (PDF 프리뷰 등).
#[tauri::command]
pub async fn read_file_base64(path: String) -> Result<String, String> {
    use base64::Engine;
    let bytes = tokio::fs::read(&path)
        .await
        .map_err(|e| format!("파일 읽기 실패: {}", e))?;
    Ok(base64::engine::general_purpose::STANDARD.encode(&bytes))
}
