use tauri::{AppHandle, Manager};
use tokio::process::Command;

use super::find_java;

// ─────────────────────────────────────────────────────────────────
// InDesign (.indd) Extraction
// ─────────────────────────────────────────────────────────────────

/// Extract IDML + resolved.json from an InDesign (.indd) file.
/// InDesign Desktop이 설치되어 있어야 한다 (macOS).
/// 흐름: osascript → InDesign do javascript → ExtendScript → IDML + resolved.json
#[tauri::command]
pub async fn extract_indd(
    app: AppHandle,
    indd_path: String,
    _jar_path: String,
    spread_mode: Option<bool>,
) -> Result<crate::indesign::InddExtractResult, String> {
    // 0. INDD 옆에 이미 IDML + resolved.json이 있으면 InDesign 추출 스킵
    let indd = std::path::Path::new(&indd_path);
    if let Some(indd_parent) = indd.parent() {
        let sibling_idml = indd_parent
            .join(indd.file_stem().unwrap_or_default())
            .with_extension("idml");
        let sibling_resolved = indd_parent.join("resolved.json");

        if sibling_idml.exists() && sibling_resolved.exists() {
            crate::indesign::emit_progress_pub(&app, "cached", "기존 IDML/resolved 사용 중...");
            return Ok(crate::indesign::InddExtractResult {
                idml_path: sibling_idml.to_string_lossy().to_string(),
                resolved_json_path: Some(sibling_resolved.to_string_lossy().to_string()),
                preview_pdf_path: None,
                temp_dir: indd_parent.to_string_lossy().to_string(),
            });
        }
    }

    // 1. INDD 파일 존재 확인
    if !std::path::Path::new(&indd_path).exists() {
        return Err(format!("INDD 파일을 찾을 수 없습니다: {}", indd_path));
    }

    // 2. InDesign 설치 확인
    let indesign_app_path = crate::indesign::find_indesign_app()?;

    // 3. 임시 디렉토리 생성
    let output_dir = crate::indesign::create_extraction_temp_dir()?;

    // 4. ExtendScript 경로 찾기
    let jsx_path = crate::indesign::find_extendscript(&app)?;

    // 5. 추출 실행 (타임아웃은 run_extraction 내부에서 처리 — 600초)
    let sm = spread_mode.unwrap_or(false);
    let result = crate::indesign::run_extraction(
        &app,
        &indd_path,
        &output_dir,
        &jsx_path,
        &indesign_app_path,
        0,
        0,
        sm,
    )
    .await
    .map_err(|e| {
        // 추출 실패 시 임시 디렉토리 정리
        let _ = std::fs::remove_dir_all(&output_dir);
        e
    })?;

    // 5. 원본 INDD 파일 옆 Links/ 폴더를 temp 디렉토리에 심볼릭 링크
    //    → 이미지 프리뷰/변환 시 IDML 옆 Links/ 폴더를 자동으로 찾을 수 있도록
    if let Some(indd_parent) = std::path::Path::new(&indd_path).parent() {
        let source_links = indd_parent.join("Links");
        let target_links = output_dir.join("Links");
        if source_links.is_dir() && !target_links.exists() {
            #[cfg(unix)]
            {
                let _ = std::os::unix::fs::symlink(&source_links, &target_links);
            }
        }
    }

    Ok(result)
}

// ─────────────────────────────────────────────────────────────────
// AST Export
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

// ─────────────────────────────────────────────────────────────────
// Question Extraction (Python-based)
// ─────────────────────────────────────────────────────────────────

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
