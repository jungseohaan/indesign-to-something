use tauri::{AppHandle, Manager};
use tokio::process::Command;

use super::find_java;
use crate::extract_cache;

// ─────────────────────────────────────────────────────────────────
// InDesign (.indd) Extraction
// ─────────────────────────────────────────────────────────────────

/// Extract IDML + resolved.json from an InDesign (.indd) file.
/// InDesign Desktop이 설치되어 있어야 한다 (macOS).
/// 흐름: osascript → InDesign do javascript → ExtendScript → IDML + resolved.json
///
/// SPEC-011: 캐시 우선 조회. INDD/Links/스크립트/config 모두 변경 없으면
/// 이전 추출 결과를 ~/Library/Caches 에서 즉시 반환.
#[tauri::command]
pub async fn extract_indd(
    app: AppHandle,
    indd_path: String,
    _jar_path: String,
    spread_mode: Option<bool>,
    start_page: Option<i32>,
    end_page: Option<i32>,
) -> Result<crate::indesign::InddExtractResult, String> {
    let sp = start_page.unwrap_or(0);
    let ep = end_page.unwrap_or(0);
    let debug_range = sp > 0 || ep > 0;

    // 0. INDD 옆에 이미 IDML + resolved.json이 있으면 InDesign 추출 스킵 (기존 동작 유지)
    //    단, 디버그 페이지 범위가 지정되면 항상 신규 추출.
    let indd = std::path::Path::new(&indd_path);
    if !debug_range {
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
    }

    // 1. INDD 파일 존재 확인
    if !std::path::Path::new(&indd_path).exists() {
        return Err(format!("INDD 파일을 찾을 수 없습니다: {}", indd_path));
    }

    // 2. InDesign 설치 확인 (캐시 적중이어도 동일성 검증용으로 미리 필요하진 않음 →
    //    캐시 조회 후 필요 시에만 호출)

    // 3. ExtendScript 경로 (캐시 키 계산에 필요)
    let jsx_path = crate::indesign::find_extendscript(&app)?;
    let config_path = crate::indesign::find_bundled_config_pub(&app);
    let sm = spread_mode.unwrap_or(false);

    // Links 디렉토리 탐지
    let links_dir = indd
        .parent()
        .map(|p| p.join("Links"))
        .filter(|p| p.is_dir());

    // 4. 캐시 키 계산 및 조회 (디버그 페이지 범위면 캐시 우회)
    let cache_key = extract_cache::compute_cache_key(
        indd,
        links_dir.as_deref(),
        std::path::Path::new(&jsx_path),
        if config_path.is_empty() {
            None
        } else {
            Some(std::path::Path::new(&config_path))
        },
        sm,
    );

    if !debug_range {
        if let Some(cached) = extract_cache::lookup(&cache_key) {
            crate::indesign::emit_progress_pub(
                &app,
                "cached",
                "캐시된 추출 결과 사용 중... (ExtendScript 건너뜀)",
            );
            // 캐시 디렉토리에 Links 심볼릭 링크가 없으면 다시 만들어둔다
            if let Some(src_links) = links_dir.as_deref() {
                let target_links = std::path::Path::new(&cached.temp_dir).join("Links");
                if !target_links.exists() {
                    #[cfg(unix)]
                    {
                        let _ = std::os::unix::fs::symlink(src_links, &target_links);
                    }
                }
            }
            return Ok(cached);
        }
    }

    // 5. 캐시 미스 → 실제 추출 실행
    let indesign_app_path = crate::indesign::find_indesign_app()?;
    let output_dir = crate::indesign::create_extraction_temp_dir()?;

    let result = crate::indesign::run_extraction(
        &app,
        &indd_path,
        &output_dir,
        &jsx_path,
        &indesign_app_path,
        sp,
        ep,
        sm,
    )
    .await
    .map_err(|e| {
        // 추출 실패 시 임시 디렉토리 정리
        let _ = std::fs::remove_dir_all(&output_dir);
        e
    })?;

    // 6. 원본 INDD 파일 옆 Links/ 폴더를 temp 디렉토리에 심볼릭 링크
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

    // 7. 캐시에 저장 (이동). 디버그 페이지 범위는 캐시에 저장하지 않는다.
    if debug_range {
        return Ok(result);
    }
    match extract_cache::store(&cache_key, &indd_path, &output_dir) {
        Ok(moved) => {
            // 캐시 이동 성공 시 Links 심볼릭 링크 다시 연결
            if let Some(src_links) = links_dir.as_deref() {
                let target_links = std::path::Path::new(&moved.temp_dir).join("Links");
                if !target_links.exists() {
                    #[cfg(unix)]
                    {
                        let _ = std::os::unix::fs::symlink(src_links, &target_links);
                    }
                }
            }
            Ok(moved)
        }
        Err(e) => {
            eprintln!("[extract_cache] 캐시 저장 실패(무시): {}", e);
            Ok(result)
        }
    }
}

/// 추출 캐시 전체 비우기.
#[tauri::command]
pub fn clear_extract_cache() -> Result<(usize, u64), String> {
    extract_cache::clear_all()
}

/// 추출 캐시 통계 (항목 수, 총 바이트).
#[tauri::command]
pub fn extract_cache_stats() -> Result<(usize, u64), String> {
    extract_cache::stats()
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

