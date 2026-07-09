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
    // SPEC-030: 성능 옵션. perf_mode = "fast" | "standard" | "high" (기본 "standard").
    // skip_pdf = preview.pdf 생성 스킵. 기본 false이며 fast도 PDF는 생성/캐시한다.
    perf_mode: Option<String>,
    skip_pdf: Option<bool>,
    // extractor mode. 기본은 spread_chunks이며, 필요 시 "full"로 legacy 단일 범위 추출을 강제할 수 있다.
    extract_mode: Option<String>,
    // 분할 추출: chunk_size > 0 이면 해당 페이지 수 단위로 청크 추출.
    // debug page range(start_page/end_page)가 지정된 경우 무시.
    chunk_size: Option<i32>,
) -> Result<crate::indesign::InddExtractResult, String> {
    let sleep = app.state::<crate::SleepPreventionHandle>();
    let _sleep_lease = sleep.acquire("extract_indd");

    let sp = start_page.unwrap_or(0);
    let ep = end_page.unwrap_or(0);
    let debug_range = sp > 0 || ep > 0;
    let pm = perf_mode.unwrap_or_else(|| "standard".to_string());
    let pm_normalized = pm.to_lowercase();
    let sk = skip_pdf.unwrap_or(false);
    let extract_mode_normalized = extract_mode
        .unwrap_or_else(|| "spread_chunks".to_string())
        .to_lowercase();

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
                    extraction_plan_path: None,
                    extraction_results_path: None,
                    temp_dir: indd_parent.to_string_lossy().to_string(),
                    extract_stats: None,
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
        &pm_normalized,
        sk,
        &extract_mode_normalized,
    );

    if !debug_range {
        if let Some(cached) = extract_cache::lookup(&cache_key) {
            crate::indesign::emit_progress_pub(
                &app,
                "cached",
                "캐시된 추출 결과 사용 중... (ExtendScript 건너뜀)",
            );
            // 캐시 디렉토리에 Links 링크가 없으면 다시 만들어둔다
            if let Some(src_links) = links_dir.as_deref() {
                let target_links = std::path::Path::new(&cached.temp_dir).join("Links");
                if !target_links.exists() {
                    crate::extract_cache::link_or_copy_links_dir(
                        std::path::Path::new(src_links),
                        &target_links,
                    );
                }
            }
            return Ok(cached);
        }
    }

    // 5. 캐시 미스 → SPEC-030 B.2: 이전 캐시 엔트리가 있으면 per-page 부분 추출 시도
    let indesign_app_path = crate::indesign::find_indesign_app()?;
    let output_dir = crate::indesign::create_extraction_temp_dir()?;

    // B.2: 같은 INDD 경로의 이전 캐시 엔트리를 찾고 page hash 비교
    let partial_result = if !debug_range && extract_mode_normalized != "spread_chunks" {
        try_partial_extraction(
            &app,
            &indd_path,
            &output_dir,
            &jsx_path,
            &indesign_app_path,
            sp,
            ep,
            sm,
            &pm_normalized,
            sk,
        )
        .await
    } else {
        None
    };

    let result = if let Some(r) = partial_result {
        r
    } else {
        // 분할 추출 모드: chunk_size > 0 이고 debug page range 없으면 legacy 청크 추출.
        // spread_chunks는 JSX 내부에서 같은 InDesign 세션으로 chunk-local Stage 0~4를 실행한다.
        let cs = chunk_size.unwrap_or(0);
        if !debug_range && cs > 0 && extract_mode_normalized != "spread_chunks" {
            crate::indesign::run_extraction_chunked(
                &app,
                &indd_path,
                &output_dir,
                &jsx_path,
                &indesign_app_path,
                sm,
                &pm_normalized,
                sk,
                cs,
            )
            .await
            .map_err(|e| {
                let _ = std::fs::remove_dir_all(&output_dir);
                e
            })?
        } else {
            crate::indesign::run_extraction(
                &app,
                &indd_path,
                &output_dir,
                &jsx_path,
                &indesign_app_path,
                sp,
                ep,
                sm,
                &pm_normalized,
                sk,
                &extract_mode_normalized,
                debug_range,
            )
            .await
            .map_err(|e| {
                // 추출 실패 시 임시 디렉토리 정리
                let _ = std::fs::remove_dir_all(&output_dir);
                e
            })?
        }
    }; // end partial_result else branch

    // SPEC-030 B.1: 배치 export 크롭 매니페스트 처리 (sips)
    {
        let cropped = crate::indesign::apply_crop_manifest(&output_dir);
        if cropped > 0 {
            eprintln!("[B.1] sips 크롭 완료: {}개", cropped);
        }
    }

    // 6. 원본 INDD 파일 옆 Links/ 폴더를 temp 디렉토리에 링크
    if let Some(indd_parent) = std::path::Path::new(&indd_path).parent() {
        let source_links = indd_parent.join("Links");
        let target_links = output_dir.join("Links");
        if source_links.is_dir() && !target_links.exists() {
            crate::extract_cache::link_or_copy_links_dir(&source_links, &target_links);
        }
    }

    // 7. 캐시에 저장 (이동). 디버그 페이지 범위는 캐시에 저장하지 않는다.
    if debug_range {
        return Ok(result);
    }
    match extract_cache::store(&cache_key, &indd_path, &output_dir) {
        Ok(moved) => {
            extract_cache::write_extractor_fingerprint(&cache_key, std::path::Path::new(&jsx_path));
            // 캐시 이동 성공 시 Links 링크 다시 연결
            if let Some(src_links) = links_dir.as_deref() {
                let target_links = std::path::Path::new(&moved.temp_dir).join("Links");
                if !target_links.exists() {
                    crate::extract_cache::link_or_copy_links_dir(
                        std::path::Path::new(src_links),
                        &target_links,
                    );
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
    let stderr = String::from_utf8_lossy(&output.stderr);
    parse_ast_json_from_stdout(&stdout, &stderr)
}

fn parse_ast_json_from_stdout(stdout: &str, stderr: &str) -> Result<serde_json::Value, String> {
    let trimmed = stdout.trim_start_matches('\u{feff}').trim_start();
    if let Ok(value) = serde_json::from_str::<serde_json::Value>(trimmed) {
        if is_ast_json(&value) {
            return Ok(value);
        }
    }

    for (idx, ch) in stdout.char_indices() {
        if ch != '{' {
            continue;
        }
        let candidate = &stdout[idx..];
        let mut stream =
            serde_json::Deserializer::from_str(candidate).into_iter::<serde_json::Value>();
        if let Some(Ok(value)) = stream.next() {
            if is_ast_json(&value) {
                return Ok(value);
            }
        }
    }

    let stdout_preview = preview_text(stdout);
    let stderr_preview = preview_text(stderr);
    Err(format!(
        "Failed to parse AST JSON: JSON object with AST fields was not found. stdout_prefix={:?}, stderr_prefix={:?}",
        stdout_preview, stderr_preview
    ))
}

fn is_ast_json(value: &serde_json::Value) -> bool {
    value
        .as_object()
        .map(|obj| {
            obj.contains_key("sections")
                || obj.contains_key("stories")
                || obj.contains_key("sourceFormat")
        })
        .unwrap_or(false)
}

fn preview_text(text: &str) -> String {
    let normalized = text.replace('\n', "\\n").replace('\r', "\\r");
    normalized.chars().take(500).collect()
}

// ─────────────────────────────────────────────────────────────────
// SPEC-030 B.2: 부분 캐시 추출 헬퍼
// ─────────────────────────────────────────────────────────────────

/// 이전 캐시 엔트리를 이용해 변경된 페이지만 재추출한다.
/// 성공하면 InddExtractResult를 반환하고, 이전 캐시가 없거나 pre-scan 실패 시 None 반환.
async fn try_partial_extraction(
    app: &tauri::AppHandle,
    indd_path: &str,
    output_dir: &std::path::Path,
    jsx_path: &str,
    indesign_app_path: &str,
    start_page: i32,
    end_page: i32,
    spread_mode: bool,
    perf_mode: &str,
    skip_pdf: bool,
) -> Option<crate::indesign::InddExtractResult> {
    // 1. 이전 캐시 키 조회
    let indd = std::path::Path::new(indd_path);
    let prev_key = extract_cache::lookup_previous_cache_key(indd)?;
    if !extract_cache::is_extractor_fingerprint_current(&prev_key, std::path::Path::new(jsx_path)) {
        eprintln!("[B.2] extractor 변경 감지 → 부분 캐시 재사용 생략");
        return None;
    }
    let cached_hashes = extract_cache::load_page_hashes(&prev_key)?;
    let cached_item_map = extract_cache::load_page_item_map(&prev_key)?;

    // 2. pre-scan: 현재 페이지 해시 계산 (경량 InDesign 실행)
    let scan_dir = crate::indesign::create_extraction_temp_dir().ok()?;
    let scan_ok =
        crate::indesign::run_page_hash_scan(app, indd_path, &scan_dir, jsx_path, indesign_app_path)
            .await;
    if scan_ok.is_err() {
        let _ = std::fs::remove_dir_all(&scan_dir);
        return None;
    }

    // 3. 현재 해시 읽기
    let current_hashes: std::collections::HashMap<String, String> =
        std::fs::read_to_string(scan_dir.join("page_hashes.json"))
            .ok()
            .and_then(|c| serde_json::from_str(&c).ok())?;
    let _ = std::fs::remove_dir_all(&scan_dir);

    // 4. 변경되지 않은 페이지 계산
    let unchanged = extract_cache::unchanged_page_indices(&cached_hashes, &current_hashes);
    if unchanged.is_empty() {
        // 모든 페이지 변경 → 일반 전체 추출로 폴백
        return None;
    }

    // pre_scan이 경량화(~2s)되었으므로 10% 이상 스킵 가능하면 부분 재추출 진행
    let total_pages = current_hashes.len();
    if unchanged.len() * 10 < total_pages {
        return None;
    }

    eprintln!(
        "[B.2] 부분 재추출: {}/{} 페이지 스킵 (변경: {})",
        unchanged.len(),
        total_pages,
        total_pages - unchanged.len()
    );

    // 5. 스킵 목록 JSON 직렬화
    let skip_json = serde_json::to_string(&unchanged).ok()?;

    // 6. 변경된 페이지만 포함한 부분 추출 실행
    let result = crate::indesign::run_extraction_with_skip(
        app,
        indd_path,
        output_dir,
        jsx_path,
        indesign_app_path,
        start_page,
        end_page,
        spread_mode,
        perf_mode,
        skip_pdf,
        &skip_json,
    )
    .await
    .ok()?;

    // 7. 이전 캐시에서 변경되지 않은 페이지 PNG 복사
    let copied =
        extract_cache::copy_cached_pages(&prev_key, output_dir, &unchanged, &cached_item_map);
    eprintln!("[B.2] 캐시에서 {} 파일 복사", copied);

    // 8. SPEC-030: partial resolved.json + 캐시된 complete resolved.json 병합
    let resolved_path = output_dir.join("resolved.json");
    if resolved_path.exists() {
        match extract_cache::merge_resolved_json(&resolved_path, &prev_key) {
            Ok(()) => eprintln!("[SPEC-030] resolved.json 병합 성공"),
            Err(e) => eprintln!("[SPEC-030] resolved.json 병합 실패 (무시): {}", e),
        }
    }

    Some(result)
}
