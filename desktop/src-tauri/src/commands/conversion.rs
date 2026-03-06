use serde::{Deserialize, Serialize};
use std::process::Stdio;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

use super::{find_java, ConvertOptions, ConvertResult, LogEvent, ProgressEvent};

/// Convert an IDML file to HWPX with progress reporting
#[tauri::command]
pub async fn convert_idml(
    app: AppHandle,
    input_path: String,
    output_path: String,
    options: ConvertOptions,
    jar_path: String,
) -> Result<ConvertResult, String> {
    let input_path_ref = input_path.clone();
    let mut args = vec![
        "-jar".to_string(),
        jar_path,
        "--convert".to_string(),
        input_path,
        output_path,
        "--progress".to_string(),
    ];

    if options.spread_based {
        args.push("--spread-mode".to_string());
    }

    args.push("--vector-dpi".to_string());
    args.push(options.vector_dpi.to_string());

    if options.include_images {
        args.push("--include-images".to_string());
    }

    if options.layout_mode != "preserve" {
        args.push("--layout-mode".to_string());
        args.push(options.layout_mode.clone());
    }

    if let Some(resolved) = &options.resolved_json_path {
        args.push("--resolved".to_string());
        args.push(resolved.clone());
    }

    if let Some(links_dir) = &options.links_directory {
        args.push("--links-directory".to_string());
        args.push(links_dir.clone());
    }

    // 사용자 지정 폰트 매핑 → temp JSON 파일로 전달
    let mut font_map_file: Option<std::path::PathBuf> = None;
    if let Some(ref map) = options.font_map {
        if !map.is_empty() {
            let temp_dir = std::env::temp_dir();
            let path = temp_dir.join("idml_font_map.json");
            let json = serde_json::to_string(map)
                .map_err(|e| format!("Failed to serialize font map: {}", e))?;
            std::fs::write(&path, &json)
                .map_err(|e| format!("Failed to write font map file: {}", e))?;
            args.push("--font-map".to_string());
            args.push(path.to_string_lossy().to_string());
            font_map_file = Some(path);
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

    // font map temp 파일 정리
    if let Some(path) = font_map_file {
        let _ = std::fs::remove_file(&path);
    }

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
