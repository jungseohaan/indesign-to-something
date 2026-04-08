use serde::{Deserialize, Serialize};
use std::path::Path;
use std::process::Stdio;
use tauri::{AppHandle, Emitter, Manager};
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

    // 리소스 디렉토리에서 config 및 font-map 경로 추가
    let mut config_found = false;
    if let Ok(resource_dir) = app.path().resource_dir() {
        // Tauri 번들에서 리소스는 _up_/_up_/ 하위에 위치할 수 있음
        let candidates = vec![
            resource_dir.clone(),
            resource_dir.join("_up_").join("_up_"),
        ];
        for dir in &candidates {
            let config_path = dir.join("conversion-config.json");
            if config_path.exists() {
                args.push("--config".to_string());
                args.push(config_path.to_string_lossy().to_string());
                config_found = true;
                break;
            }
        }
        // 사용자 지정 font_map이 없을 때만 번들된 font-mapping.json 사용
        if font_map_file.is_none() {
            for dir in &candidates {
                let font_map_path = dir.join("font-mapping.json");
                if font_map_path.exists() {
                    args.push("--font-map".to_string());
                    args.push(font_map_path.to_string_lossy().to_string());
                    break;
                }
            }
        }
    }
    // 개발 모드 폴백: 프로젝트 루트에서 config 탐색
    if !config_found {
        let dev_config = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conversion-config.json");
        if dev_config.exists() {
            let resolved = dev_config.canonicalize().unwrap_or(dev_config);
            args.push("--config".to_string());
            args.push(resolved.to_string_lossy().to_string());
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
        let code = status.code().map(|c| c.to_string()).unwrap_or_else(|| "unknown".to_string());
        return Err(format!("Conversion failed (exit code {})", code));
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

