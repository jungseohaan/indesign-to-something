use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use std::process::Stdio;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

/// Find Java executable path on macOS
fn find_java() -> String {
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
    pub start_page: Option<i32>,
    #[serde(default)]
    pub end_page: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ConvertResult {
    pub pages_converted: i32,
    pub frames_converted: i32,
    pub images_converted: i32,
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

/// Get the path to the converter JAR file
#[tauri::command]
pub async fn get_jar_path(app: AppHandle) -> Result<String, String> {
    // JAR file name pattern (cli jar has Main-Class manifest)
    let jar_names = ["hwpxlib-1.0.9-cli.jar", "hwpxlib-cli.jar", "idml-converter.jar"];

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
            .join("works/indesign-to-something/target/hwpxlib-1.0.9-cli.jar");
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

/// Generate a preview PNG for PSD/AI/EPS images using macOS sips/qlmanage
#[tauri::command]
pub async fn generate_preview(
    idml_path: String,
    link_path: String,
    _jar_path: String,
) -> Result<ImagePreview, String> {
    // IDML의 링크 경로에서 실제 파일 경로 구성
    // 링크 경로가 상대 경로일 수 있으므로 IDML 파일 위치 기준으로 해석
    let idml_dir = std::path::Path::new(&idml_path)
        .parent()
        .ok_or("Invalid IDML path")?;

    // 링크 경로 정규화 (URL 디코딩)
    let decoded_link = urlencoding::decode(&link_path)
        .map_err(|e| format!("URL decode error: {}", e))?
        .into_owned();

    // 파일 경로 구성
    let source_path = if decoded_link.starts_with('/') {
        std::path::PathBuf::from(&decoded_link)
    } else {
        // Links 폴더 내에 있는 경우
        let links_dir = idml_dir.join("Links");
        if links_dir.exists() {
            let filename = std::path::Path::new(&decoded_link)
                .file_name()
                .ok_or("Invalid link path")?;
            links_dir.join(filename)
        } else {
            idml_dir.join(&decoded_link)
        }
    };

    if !source_path.exists() {
        return Err(format!("Source file not found: {}", source_path.display()));
    }

    // 미리보기 저장 디렉토리 생성
    let preview_dir = idml_dir.join(".previews");
    std::fs::create_dir_all(&preview_dir)
        .map_err(|e| format!("Failed to create preview directory: {}", e))?;

    // 출력 파일명 생성
    let filename = source_path
        .file_stem()
        .ok_or("Invalid filename")?
        .to_string_lossy()
        .to_string();
    let preview_filename = format!("{}.png", filename);
    let preview_path = preview_dir.join(&preview_filename);

    // macOS sips 명령으로 PNG 변환 (투명도 유지)
    let output = Command::new("sips")
        .args([
            "-s", "format", "png",
            "-s", "formatOptions", "best",
            source_path.to_str().ok_or("Invalid source path")?,
            "--out",
            preview_path.to_str().ok_or("Invalid preview path")?,
        ])
        .output()
        .await
        .map_err(|e| format!("Failed to run sips: {}", e))?;

    if !output.status.success() {
        // sips 실패 시 qlmanage로 폴백
        let ql_output = Command::new("qlmanage")
            .args([
                "-t",
                "-s", "1024",
                "-o", preview_dir.to_str().ok_or("Invalid preview dir")?,
                source_path.to_str().ok_or("Invalid source path")?,
            ])
            .output()
            .await
            .map_err(|e| format!("Failed to run qlmanage: {}", e))?;

        if !ql_output.status.success() {
            return Err("Failed to generate preview".to_string());
        }

        // qlmanage는 파일명.png 형식으로 저장
        let ql_preview = preview_dir.join(format!("{}.png", source_path.file_name().unwrap().to_string_lossy()));
        if ql_preview.exists() && ql_preview != preview_path {
            std::fs::rename(&ql_preview, &preview_path)
                .map_err(|e| format!("Failed to rename preview: {}", e))?;
        }
    }

    // 이미지 크기 가져오기
    let size_output = Command::new("sips")
        .args(["-g", "pixelWidth", "-g", "pixelHeight", preview_path.to_str().unwrap()])
        .output()
        .await
        .map_err(|e| format!("Failed to get image size: {}", e))?;

    let size_str = String::from_utf8_lossy(&size_output.stdout);
    let mut width = 0i32;
    let mut height = 0i32;

    for line in size_str.lines() {
        if line.contains("pixelWidth") {
            if let Some(w) = line.split(':').nth(1) {
                width = w.trim().parse().unwrap_or(0);
            }
        } else if line.contains("pixelHeight") {
            if let Some(h) = line.split(':').nth(1) {
                height = h.trim().parse().unwrap_or(0);
            }
        }
    }

    // 이미지 파일을 읽어서 base64 인코딩
    let image_bytes = std::fs::read(&preview_path)
        .map_err(|e| format!("Failed to read preview file: {}", e))?;
    let base64_data = STANDARD.encode(&image_bytes);
    let data_url = format!("data:image/png;base64,{}", base64_data);

    Ok(ImagePreview {
        original_path: link_path,
        data_url,
        filename: format!("{}.png", filename),
        width,
        height,
    })
}

/// Generate a preview PNG for image frames with transform and clipping applied
#[tauri::command]
pub async fn generate_image_preview(
    idml_path: String,
    frame_id: String,
    jar_path: String,
) -> Result<ImagePreview, String> {
    let java = find_java();

    // IDML 파일의 디렉토리를 links-directory로 사용
    let idml_dir = std::path::Path::new(&idml_path)
        .parent()
        .map(|p| p.to_string_lossy().to_string());

    let mut args = vec![
        "-jar".to_string(),
        jar_path,
        "--render-image".to_string(),
        idml_path.clone(),
        frame_id.clone(),
    ];

    // Links 디렉토리 추가
    if let Some(dir) = idml_dir {
        let links_dir = std::path::Path::new(&dir).join("Links");
        if links_dir.exists() {
            args.push("--links-directory".to_string());
            args.push(links_dir.to_string_lossy().to_string());
        }
    }

    let output = Command::new(&java)
        .args(&args)
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Image rendering failed: {}", stderr));
    }

    // Java에서 base64 PNG 데이터를 JSON으로 반환
    let stdout = String::from_utf8_lossy(&output.stdout);
    let json: serde_json::Value = serde_json::from_str(&stdout)
        .map_err(|e| format!("Failed to parse image preview output: {}", e))?;

    // 에러 체크
    if let Some(error) = json.get("error").and_then(|v| v.as_str()) {
        return Err(error.to_string());
    }

    let data_url = json
        .get("data_url")
        .and_then(|v| v.as_str())
        .ok_or("Missing data_url in response")?
        .to_string();
    let width = json
        .get("width")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let height = json
        .get("height")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let filename = json
        .get("filename")
        .and_then(|v| v.as_str())
        .unwrap_or("image.png")
        .to_string();

    Ok(ImagePreview {
        original_path: format!("image:{}", frame_id),
        data_url,
        filename,
        width,
        height,
    })
}

/// Generate a preview PNG for vector shapes using Java converter
#[tauri::command]
pub async fn generate_vector_preview(
    idml_path: String,
    frame_id: String,
    jar_path: String,
) -> Result<ImagePreview, String> {
    let java = find_java();

    // Java converter를 사용하여 벡터를 PNG로 렌더링
    let output = Command::new(&java)
        .args([
            "-jar",
            &jar_path,
            "--render-vector",
            &idml_path,
            &frame_id,
        ])
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Vector rendering failed: {}", stderr));
    }

    // Java에서 base64 PNG 데이터를 JSON으로 반환
    let stdout = String::from_utf8_lossy(&output.stdout);
    let json: serde_json::Value = serde_json::from_str(&stdout)
        .map_err(|e| format!("Failed to parse vector preview output: {}", e))?;

    let data_url = json
        .get("data_url")
        .and_then(|v| v.as_str())
        .ok_or("Missing data_url in response")?
        .to_string();
    let width = json
        .get("width")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let height = json
        .get("height")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let filename = json
        .get("filename")
        .and_then(|v| v.as_str())
        .unwrap_or("vector.png")
        .to_string();

    Ok(ImagePreview {
        original_path: format!("vector:{}", frame_id),
        data_url,
        filename,
        width,
        height,
    })
}

/// Generate a preview PNG for a master spread page
#[tauri::command]
pub async fn generate_master_preview(
    idml_path: String,
    master_id: String,
    jar_path: String,
) -> Result<ImagePreview, String> {
    let java = find_java();

    let idml_dir = std::path::Path::new(&idml_path)
        .parent()
        .map(|p| p.to_string_lossy().to_string());

    let mut args = vec![
        "-jar".to_string(),
        jar_path,
        "--render-master-spread".to_string(),
        idml_path.clone(),
        master_id.clone(),
    ];

    // Links 디렉토리 추가
    if let Some(dir) = idml_dir {
        let links_dir = std::path::Path::new(&dir).join("Links");
        if links_dir.exists() {
            args.push("--links-directory".to_string());
            args.push(links_dir.to_string_lossy().to_string());
        }
    }

    let output = Command::new(&java)
        .args(&args)
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Master spread rendering failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let json: serde_json::Value = serde_json::from_str(&stdout)
        .map_err(|e| format!("Failed to parse master preview output: {}", e))?;

    if let Some(error) = json.get("error").and_then(|v| v.as_str()) {
        return Err(error.to_string());
    }

    let data_url = json
        .get("data_url")
        .and_then(|v| v.as_str())
        .ok_or("Missing data_url in response")?
        .to_string();
    let width = json
        .get("width")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let height = json
        .get("height")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let filename = json
        .get("filename")
        .and_then(|v| v.as_str())
        .unwrap_or("master.png")
        .to_string();

    Ok(ImagePreview {
        original_path: format!("master:{}", master_id),
        data_url,
        filename,
        width,
        height,
    })
}

// ─────────────────────────────────────────────────────────────────
// Text Frame Detail Types
// ─────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct TextFrameDetail {
    pub frame_id: String,
    pub story_id: String,
    pub frame_properties: FrameProperties,
    pub paragraphs: Vec<ParagraphInfo>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct FrameProperties {
    pub fill_color: Option<String>,
    pub stroke_color: Option<String>,
    pub stroke_weight: f64,
    pub corner_radius: f64,
    pub corner_radii: Option<Vec<f64>>,
    pub fill_tint: f64,
    pub stroke_tint: f64,
    pub width: f64,
    pub height: f64,
    // Column properties
    pub column_count: i32,
    pub column_gutter: f64,
    pub column_type: String,
    pub column_fixed_width: f64,
    pub column_widths: Option<Vec<f64>>,
    // Vertical justification
    pub vertical_justification: String,
    // Text wrap
    pub ignore_wrap: bool,
    // Column rule
    pub use_column_rule: bool,
    pub column_rule_width: f64,
    pub column_rule_type: String,
    pub column_rule_color: Option<String>,
    pub column_rule_tint: f64,
    pub column_rule_offset: f64,
    pub column_rule_inset_width: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ParagraphInfo {
    pub style_name: String,
    pub style_ref: String,
    pub style: ParagraphStyle,
    #[serde(rename = "inline")]
    pub inline_props: ParagraphInline,
    pub shading: ParagraphShading,
    pub runs: Vec<CharacterRun>,
    pub text: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ParagraphStyle {
    pub font_family: Option<String>,
    pub font_size: Option<f64>,
    pub text_alignment: Option<String>,
    pub first_line_indent: Option<f64>,
    pub left_indent: Option<f64>,
    pub space_before: Option<f64>,
    pub space_after: Option<f64>,
    pub leading: Option<f64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ParagraphInline {
    pub justification: Option<String>,
    pub first_line_indent: Option<f64>,
    pub left_indent: Option<f64>,
    pub right_indent: Option<f64>,
    pub space_before: Option<f64>,
    pub space_after: Option<f64>,
    pub leading: Option<f64>,
    pub tracking: Option<f64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ParagraphShading {
    pub on: bool,
    pub color: Option<String>,
    pub tint: Option<f64>,
    pub width: Option<String>,
    pub offset_left: Option<f64>,
    pub offset_right: Option<f64>,
    pub offset_top: Option<f64>,
    pub offset_bottom: Option<f64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CharacterRun {
    pub text: String,
    pub char_style: Option<String>,
    pub font_family: Option<String>,
    pub font_size: Option<f64>,
    pub font_style: Option<String>,
    pub fill_color: Option<String>,
    pub anchors: Vec<String>,
}

/// Get text frame detail (paragraphs with styles)
#[tauri::command]
pub async fn get_text_frame_detail(
    idml_path: String,
    frame_id: String,
    jar_path: String,
) -> Result<TextFrameDetail, String> {
    let java = find_java();
    let output = Command::new(&java)
        .args(["-jar", &jar_path, "--text-frame-detail", &idml_path, &frame_id])
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Java process failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);

    // Filter out [DEBUG] lines
    let json_output: String = stdout
        .lines()
        .filter(|line| !line.starts_with("[DEBUG]"))
        .collect::<Vec<_>>()
        .join("\n");

    serde_json::from_str(&json_output).map_err(|e| format!("Failed to parse output: {} - {}", e, json_output))
}

/// Convert an IDML file to HWPX with progress reporting
#[tauri::command]
pub async fn convert_idml(
    app: AppHandle,
    input_path: String,
    output_path: String,
    options: ConvertOptions,
    jar_path: String,
) -> Result<ConvertResult, String> {
    let mut args = vec![
        "-jar".to_string(),
        jar_path,
        "--convert".to_string(),
        input_path,
        output_path,
        "--progress".to_string(),
        "--event-stream".to_string(),
    ];

    if options.spread_based {
        args.push("--spread-mode".to_string());
    }

    args.push("--vector-dpi".to_string());
    args.push(options.vector_dpi.to_string());

    if options.include_images {
        args.push("--include-images".to_string());
    }

    if let Some(links_dir) = options.links_directory {
        args.push("--links-directory".to_string());
        args.push(links_dir);
    }

    if let Some(start) = options.start_page {
        if start > 0 {
            args.push("--start-page".to_string());
            args.push(start.to_string());
        }
    }

    if let Some(end) = options.end_page {
        if end > 0 {
            args.push("--end-page".to_string());
            args.push(end.to_string());
        }
    }

    println!("Convert args: {:?}", args);
    println!("spread_based option: {}", options.spread_based);

    let java = find_java();
    let mut child = Command::new(&java)
        .args(&args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("Failed to start Java process: {}", e))?;

    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
    let stderr = child.stderr.take().ok_or("Failed to capture stderr")?;

    let mut stdout_reader = BufReader::new(stdout).lines();
    let mut stderr_reader = BufReader::new(stderr).lines();

    // Spawn task to read stderr and emit log events
    let app_clone = app.clone();
    let stderr_task = tokio::spawn(async move {
        while let Ok(Some(line)) = stderr_reader.next_line().await {
            let timestamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;
            let _ = app_clone.emit("conversion-log", LogEvent {
                message: line,
                timestamp,
            });
        }
    });

    let mut final_result: Option<ConvertResult> = None;

    while let Some(line) = stdout_reader.next_line().await.map_err(|e| e.to_string())? {
        // Try to parse as JSON progress message
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
            if let Some(msg_type) = json.get("type").and_then(|v| v.as_str()) {
                match msg_type {
                    "progress" => {
                        if let (Some(current), Some(total), Some(message)) = (
                            json.get("current").and_then(|v| v.as_i64()),
                            json.get("total").and_then(|v| v.as_i64()),
                            json.get("message").and_then(|v| v.as_str()),
                        ) {
                            let _ = app.emit(
                                "conversion-progress",
                                ProgressEvent {
                                    current: current as i32,
                                    total: total as i32,
                                    message: message.to_string(),
                                },
                            );
                        }
                    }
                    "complete" => {
                        if let Some(result) = json.get("result") {
                            final_result = serde_json::from_value(result.clone()).ok();
                        }
                    }
                    "error" => {
                        if let Some(msg) = json.get("message").and_then(|v| v.as_str()) {
                            return Err(msg.to_string());
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    // Wait for stderr task to complete
    let _ = stderr_task.await;

    let status = child.wait().await.map_err(|e| e.to_string())?;

    if !status.success() {
        return Err("Conversion failed".to_string());
    }

    final_result.ok_or_else(|| "No result received".to_string())
}

/// Convert a HWPX file to IDML
#[tauri::command]
pub async fn convert_hwpx_to_idml(
    app: AppHandle,
    input_path: String,
    output_path: String,
    jar_path: String,
) -> Result<ConvertResult, String> {
    let args = vec![
        "-jar".to_string(),
        jar_path,
        "--hwpx-to-idml".to_string(),
        input_path,
        output_path,
        "--progress".to_string(),
    ];

    let java = find_java();
    let mut child = Command::new(&java)
        .args(&args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("Failed to start Java process: {}", e))?;

    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
    let stderr = child.stderr.take().ok_or("Failed to capture stderr")?;

    let mut stdout_reader = BufReader::new(stdout).lines();
    let mut stderr_reader = BufReader::new(stderr).lines();

    // Spawn task to read stderr and emit log events
    let app_clone = app.clone();
    let stderr_task = tokio::spawn(async move {
        while let Ok(Some(line)) = stderr_reader.next_line().await {
            let timestamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;
            let _ = app_clone.emit("conversion-log", LogEvent {
                message: line,
                timestamp,
            });
        }
    });

    let mut final_result: Option<ConvertResult> = None;

    while let Some(line) = stdout_reader.next_line().await.map_err(|e| e.to_string())? {
        // Try to parse as JSON progress message
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
            if let Some(msg_type) = json.get("type").and_then(|v| v.as_str()) {
                match msg_type {
                    "progress" => {
                        if let (Some(current), Some(total), Some(message)) = (
                            json.get("current").and_then(|v| v.as_i64()),
                            json.get("total").and_then(|v| v.as_i64()),
                            json.get("message").and_then(|v| v.as_str()),
                        ) {
                            let _ = app.emit(
                                "conversion-progress",
                                ProgressEvent {
                                    current: current as i32,
                                    total: total as i32,
                                    message: message.to_string(),
                                },
                            );
                        }
                    }
                    "complete" => {
                        if let Some(result) = json.get("result") {
                            final_result = serde_json::from_value(result.clone()).ok();
                        }
                    }
                    "error" => {
                        if let Some(msg) = json.get("message").and_then(|v| v.as_str()) {
                            return Err(msg.to_string());
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    // Wait for stderr task to complete
    let _ = stderr_task.await;

    let status = child.wait().await.map_err(|e| e.to_string())?;

    if !status.success() {
        return Err("HWPX to IDML conversion failed".to_string());
    }

    final_result.ok_or_else(|| "No result received".to_string())
}

// ─────────────────────────────────────────────────────────────────
// Template Schema Extraction & Data Merge
// ─────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct TemplateSchema {
    pub version: String,
    pub source: String,
    pub layout: SchemaLayout,
    #[serde(rename = "masterSpreads")]
    pub master_spreads: Vec<SchemaMasterSpread>,
    #[serde(rename = "groupTemplate")]
    pub group_template: Option<GroupTemplate>,
    #[serde(rename = "itemFields")]
    pub item_fields: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SchemaLayout {
    #[serde(rename = "pageWidth")]
    pub page_width: f64,
    #[serde(rename = "pageHeight")]
    pub page_height: f64,
    pub margins: SchemaMargins,
    pub columns: SchemaColumns,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SchemaMargins {
    pub top: f64,
    pub bottom: f64,
    pub left: f64,
    pub right: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SchemaColumns {
    pub count: i32,
    pub gutter: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SchemaMasterSpread {
    pub id: String,
    pub name: String,
    #[serde(rename = "pageCount")]
    pub page_count: i32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct GroupTemplate {
    pub width: f64,
    #[serde(rename = "totalHeight")]
    pub total_height: f64,
    pub children: Vec<GroupChild>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct GroupChild {
    #[serde(rename = "type")]
    pub child_type: String,
    pub role: String,
    #[serde(default)]
    pub bounds: Option<ChildBounds>,
    #[serde(default)]
    pub fields: Option<Vec<FieldDef>>,
    #[serde(rename = "autoSize", default)]
    pub auto_size: Option<String>,
    #[serde(rename = "contentType", default)]
    pub content_type: Option<String>,
    #[serde(rename = "fillColor", default)]
    pub fill_color: Option<String>,
    #[serde(rename = "strokeWeight", default)]
    pub stroke_weight: Option<f64>,
    #[serde(rename = "strokeColor", default)]
    pub stroke_color: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ChildBounds {
    pub top: f64,
    pub left: f64,
    pub width: f64,
    pub height: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct FieldDef {
    pub style: String,
    pub name: String,
}

/// Extract template schema from an IDML file
#[tauri::command]
pub async fn extract_template_schema(
    source_path: String,
    jar_path: String,
) -> Result<TemplateSchema, String> {
    let java = find_java();
    let output = Command::new(&java)
        .args(["-jar", &jar_path, "--extract-schema", &source_path])
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Schema extraction failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout)
        .map_err(|e| format!("Failed to parse schema output: {}", e))
}

/// Merge template IDML with data items to create a new IDML
#[tauri::command]
pub async fn merge_idml(
    source_path: String,
    data_json: String,
    output_path: String,
    validate: bool,
    jar_path: String,
) -> Result<CreateIdmlResult, String> {
    let java = find_java();

    // Write data JSON to a temp file
    let temp_dir = std::env::temp_dir();
    let data_file = temp_dir.join("idml_merge_data.json");
    std::fs::write(&data_file, &data_json)
        .map_err(|e| format!("Failed to write temp data file: {}", e))?;

    let mut args = vec![
        "-jar".to_string(),
        jar_path,
        "--merge".to_string(),
        source_path,
        data_file.to_string_lossy().to_string(),
        output_path,
    ];

    if validate {
        args.push("--validate".to_string());
    }

    let output = Command::new(&java)
        .args(&args)
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    // Clean up temp file
    let _ = std::fs::remove_file(&data_file);

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("IDML merge failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout)
        .map_err(|e| format!("Failed to parse merge output: {}", e))
}

// ─────────────────────────────────────────────────────────────────
// Playground: Create IDML from Master Spreads
// ─────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateIdmlResult {
    pub success: bool,
    pub master_count: i32,
    #[serde(default)]
    pub page_count: i32,
    pub page_size: PageSize,
    #[serde(default)]
    pub warnings: Vec<String>,
    pub validation: Option<ValidationResult>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PageSize {
    pub width: f64,
    pub height: f64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ValidationResult {
    pub valid: bool,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
}

/// Create a new IDML file by copying master spreads from a source IDML
#[tauri::command]
pub async fn create_idml_from_masters(
    source_path: String,
    output_path: String,
    master_ids: Option<Vec<String>>,
    page_specs: Option<Vec<String>>,
    text_frame_specs: Option<Vec<String>>,
    inline_count: Option<i32>,
    tf_mode: Option<String>,
    validate: bool,
    jar_path: String,
) -> Result<CreateIdmlResult, String> {
    let java = find_java();

    let mut args = vec![
        "-jar".to_string(),
        jar_path,
        "--create-from-masters".to_string(),
        source_path,
        output_path,
    ];

    if let Some(ids) = master_ids {
        if !ids.is_empty() {
            args.push("--masters".to_string());
            args.push(ids.join(","));
        }
    }

    if let Some(specs) = page_specs {
        if !specs.is_empty() {
            args.push("--pages".to_string());
            args.push(specs.join(","));
        }
    }

    if let Some(tf_specs) = text_frame_specs {
        if !tf_specs.is_empty() {
            args.push("--text-frames".to_string());
            args.push(tf_specs.join(","));
        }
    }

    if let Some(count) = inline_count {
        if count > 0 {
            args.push("--inline-count".to_string());
            args.push(count.to_string());
        }
    }

    if let Some(mode) = tf_mode {
        if mode != "master" {
            args.push("--tf-mode".to_string());
            args.push(mode);
        }
    }

    if validate {
        args.push("--validate".to_string());
    }

    let output = Command::new(&java)
        .args(&args)
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Create from masters failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout).map_err(|e| format!("Failed to parse output: {}", e))
}

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

// ─────────────────────────────────────────────────────────────────
// Question Extraction (Python-based)
// ─────────────────────────────────────────────────────────────────

/// Export AST (intermediate representation) as JSON
#[tauri::command]
pub async fn export_ast(idml_path: String, jar_path: String) -> Result<serde_json::Value, String> {
    let java = find_java();
    let output = Command::new(&java)
        .args(["-jar", &jar_path, "--export-ast", &idml_path])
        .output()
        .await
        .map_err(|e| format!("Failed to execute Java: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("AST export failed: {}", stderr));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout)
        .map_err(|e| format!("Failed to parse AST JSON: {}", e))
}

/// Find Python 3 executable path
fn find_python() -> String {
    let python_paths = [
        "/opt/homebrew/bin/python3",
        "/usr/local/bin/python3",
        "/usr/bin/python3",
    ];

    for path in &python_paths {
        if std::path::Path::new(path).exists() {
            return path.to_string();
        }
    }

    // Try `which python3`
    if let Ok(output) = std::process::Command::new("which")
        .arg("python3")
        .output()
    {
        if output.status.success() {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !path.is_empty() {
                return path;
            }
        }
    }

    "python3".to_string()
}

/// Find the extract_physics.py script
fn find_extract_script(app: &AppHandle) -> Result<String, String> {
    // Try bundled resource first
    if let Ok(resource_dir) = app.path().resource_dir() {
        let path = resource_dir.join("extract_physics.py");
        if path.exists() {
            return Ok(path.to_string_lossy().to_string());
        }
    }

    // Development mode: search relative paths from current working directory
    if let Ok(current_dir) = std::env::current_dir() {
        let paths_to_try = [
            current_dir.join("../../extract_physics.py"),
            current_dir.join("../extract_physics.py"),
            current_dir.join("extract_physics.py"),
        ];

        for path in &paths_to_try {
            if path.exists() {
                return Ok(path.canonicalize()
                    .unwrap_or(path.clone())
                    .to_string_lossy()
                    .to_string());
            }
        }
    }

    // Absolute fallback for development
    if let Ok(home) = std::env::var("HOME") {
        let path = std::path::PathBuf::from(&home)
            .join("works/indesign-to-something/extract_physics.py");
        if path.exists() {
            return Ok(path.to_string_lossy().to_string());
        }
    }

    Err("extract_physics.py를 찾을 수 없습니다.".to_string())
}

/// Extract question items from an IDML file using the Python extraction script
#[tauri::command]
pub async fn extract_questions(
    app: AppHandle,
    idml_path: String,
    spreads: Vec<String>,
) -> Result<serde_json::Value, String> {
    let python = find_python();
    let script = find_extract_script(&app)?;

    // 1. Create temp directory for IDML extraction
    let temp_dir = std::env::temp_dir().join(format!(
        "idml-extract-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
    ));
    std::fs::create_dir_all(&temp_dir)
        .map_err(|e| format!("임시 디렉토리 생성 실패: {}", e))?;

    // 2. Unzip IDML to temp dir
    let temp_dir_str = temp_dir.to_string_lossy().to_string();
    let unzip_output = Command::new("unzip")
        .args(["-o", "-q", &idml_path, "-d", &temp_dir_str])
        .output()
        .await
        .map_err(|e| format!("IDML 압축 해제 실패: {}", e))?;

    if !unzip_output.status.success() {
        let stderr = String::from_utf8_lossy(&unzip_output.stderr);
        let _ = std::fs::remove_dir_all(&temp_dir);
        return Err(format!("IDML 압축 해제 실패: {}", stderr));
    }

    // 3. Determine Links directory (same parent as IDML file + "/Links")
    let idml_parent = std::path::Path::new(&idml_path)
        .parent()
        .ok_or("잘못된 IDML 경로")?;
    let links_dir = idml_parent.join("Links");
    let links_dir_str = links_dir.to_string_lossy().to_string();

    // 4. Join spread filenames with commas
    let spreads_arg = spreads.join(",");

    // 5. Run Python script
    let output = Command::new(&python)
        .args([
            &script,
            "--idml-dir", &temp_dir_str,
            "--links-dir", &links_dir_str,
            "--spreads", &spreads_arg,
        ])
        .output()
        .await
        .map_err(|e| format!("Python 실행 실패: {}", e))?;

    // 6. Clean up temp directory
    let _ = std::fs::remove_dir_all(&temp_dir);

    // 7. Check for errors
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("문제 추출 실패:\n{}", stderr));
    }

    // 8. Parse stdout JSON
    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(&stdout)
        .map_err(|e| format!("추출 결과 파싱 실패: {}\n출력: {}", e, &stdout[..stdout.len().min(500)]))
}
