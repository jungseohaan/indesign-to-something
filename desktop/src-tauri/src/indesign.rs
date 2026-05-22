use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tokio::process::Command;
use tokio::time::sleep;

/// 번들 리소스에서 conversion-config.json 경로를 찾는다.
fn find_bundled_config(app: &AppHandle) -> String {
    // 리소스 디렉토리에서 찾기
    if let Ok(resource_dir) = app.path().resource_dir() {
        eprintln!("[config] resource_dir: {:?}", resource_dir);
        let config = resource_dir.join("conversion-config.json");
        if config.exists() {
            eprintln!("[config] found: {:?}", config);
            return config.to_string_lossy().to_string();
        }
        // Tauri 번들: _up_/_up_/ 구조
        let config2 = resource_dir.join("_up_/_up_/conversion-config.json");
        if config2.exists() {
            eprintln!("[config] found (bundle): {:?}", config2);
            return config2.to_string_lossy().to_string();
        }
    }
    // 앱 데이터 디렉토리에서 찾기
    if let Ok(data_dir) = app.path().app_data_dir() {
        let config = data_dir.join("conversion-config.json");
        if config.exists() {
            eprintln!("[config] found (app_data): {:?}", config);
            return config.to_string_lossy().to_string();
        }
    }
    // 개발 모드: 프로젝트 루트에서 찾기
    let dev_config = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conversion-config.json");
    if dev_config.exists() {
        let resolved = dev_config.canonicalize().unwrap_or(dev_config);
        eprintln!("[config] found (dev): {:?}", resolved);
        return resolved.to_string_lossy().to_string();
    }
    eprintln!("[config] NOT FOUND — using defaults");
    String::new()
}

/// InDesign 추출 결과
#[derive(Debug, Serialize, Deserialize)]
pub struct InddExtractResult {
    pub idml_path: String,
    pub resolved_json_path: Option<String>,
    pub preview_pdf_path: Option<String>,
    pub temp_dir: String,
}

/// 추출 진행률 이벤트
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExtractionProgress {
    pub phase: String,
    pub message: String,
}

/// macOS에서 설치된 Adobe InDesign 앱 경로를 탐지한다.
/// 2025 → 2024 → 2023 순으로 검색.
pub fn find_indesign_app() -> Result<String, String> {
    let years = ["2026", "2025", "2024", "2023", "2022"];
    for year in years {
        let path = format!(
            "/Applications/Adobe InDesign {}/Adobe InDesign {}.app",
            year, year
        );
        if Path::new(&path).exists() {
            return Ok(path);
        }
    }

    // CC 버전 (이전 명명 규칙)
    let cc_paths = [
        "/Applications/Adobe InDesign CC 2019/Adobe InDesign CC 2019.app",
        "/Applications/Adobe InDesign CC 2018/Adobe InDesign CC 2018.app",
    ];
    for path in cc_paths {
        if Path::new(path).exists() {
            return Ok(path.to_string());
        }
    }

    Err("Adobe InDesign이 설치되어 있지 않습니다. /Applications 에서 Adobe InDesign을 찾을 수 없습니다.".into())
}

/// InDesign 앱 경로에서 앱 이름을 추출한다.
/// 예: "/Applications/Adobe InDesign 2025/Adobe InDesign 2025.app" → "Adobe InDesign 2025"
fn app_name_from_path(app_path: &str) -> String {
    Path::new(app_path)
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "Adobe InDesign".to_string())
}

/// 추출용 임시 디렉토리를 생성한다.
pub fn create_extraction_temp_dir() -> Result<PathBuf, String> {
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let temp = std::env::temp_dir().join(format!("indd-extract-{}", timestamp));
    std::fs::create_dir_all(&temp)
        .map_err(|e| format!("임시 디렉토리 생성 실패: {}", e))?;
    Ok(temp)
}

/// .done 시그널 파일의 내용
#[derive(Debug, Deserialize)]
struct DoneSignal {
    status: String,
    message: Option<String>,
}

/// osascript를 통해 InDesign ExtendScript를 실행하고 완료를 대기한다.
///
/// 흐름:
/// 1. AppleScript로 InDesign의 `do javascript` 호출
/// 2. ExtendScript가 IDML + resolved.json + preview.pdf 생성
/// 3. .done 시그널 파일로 완료 감지
pub async fn run_extraction(
    app: &AppHandle,
    indd_path: &str,
    output_dir: &Path,
    jsx_path: &str,
    indesign_app_path: &str,
    start_page: i32,
    end_page: i32,
    spread_mode: bool,
    perf_mode: &str,
    skip_pdf: bool,
) -> Result<InddExtractResult, String> {
    let app_name = app_name_from_path(indesign_app_path);
    let output_dir_str = output_dir.to_string_lossy().to_string();

    // 진행률: InDesign 실행 중
    emit_progress(app, "launching", "InDesign을 실행하는 중...");

    // AppleScript 생성
    // - `do script ... language javascript`: InDesign ExtendScript 실행
    // - `with arguments`: ExtendScript의 arguments 변수로 전달
    // - arguments[2] = startPage (0=전체), arguments[3] = endPage (0=전체)
    // - arguments[4] = spreadMode ("1"=스프레드 PDF, "0"=페이지별 PDF)
    // - arguments[5] = pdfOnly ("0"=기본)
    // - arguments[6] = configPath (conversion-config.json 경로, 빈 문자열이면 기본값)
    // - arguments[7] = perfMode ("fast"|"standard"|"high", SPEC-030)
    // - arguments[8] = skipPdf ("0"|"1", SPEC-030)
    let spread_flag = if spread_mode { "1" } else { "0" };
    let skip_pdf_flag = if skip_pdf { "1" } else { "0" };
    // config 파일: 번들 리소스에서 찾거나 빈 문자열
    let config_path = find_bundled_config(&app);
    // 디버그 로그: config 경로를 추출 디렉토리에 기록
    let _ = std::fs::write(
        output_dir.join("_config_debug.log"),
        format!("config_path={}\nperfMode={}\nskipPdf={}\n", config_path, perf_mode, skip_pdf),
    );
    let applescript = format!(
        r#"tell application "{app_name}"
    activate
    with timeout of 3600 seconds
        do script (read POSIX file "{jsx_path}") language javascript with arguments {{"{indd_path}", "{output_dir}", "{start_page}", "{end_page}", "{spread_flag}", "0", "{config_path}", "{perf_mode}", "{skip_pdf_flag}"}}
    end timeout
end tell"#,
        app_name = app_name,
        jsx_path = jsx_path,
        indd_path = indd_path,
        output_dir = output_dir_str,
        start_page = start_page,
        end_page = end_page,
        spread_flag = spread_flag,
        config_path = config_path,
        perf_mode = perf_mode,
        skip_pdf_flag = skip_pdf_flag,
    );

    // 진행률: 추출 실행 중
    emit_progress(app, "exporting", "IDML 추출 중...");

    // osascript를 직접 스폰 — 타임아웃 시 kill 가능하도록 Child 핸들 유지
    let mut child = Command::new("osascript")
        .args(["-e", &applescript])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("osascript 실행 실패: {}", e))?;

    // .progress 파일 폴링
    // - 절대 타임아웃: 3600초 (1시간)
    // - 정체 타임아웃: 600초 동안 진행률 업데이트가 없으면 중단
    let progress_path = output_dir.join(".progress");
    let done_path = output_dir.join(".done");
    let mut last_message = String::new();
    let timeout_secs = 3600u64;
    // rendered_frames 단계에서 PDF/PNG export 가 복잡한 페이지(많은 객체 + 고DPI)에서
    // 한 페이지에 10분 넘게 걸리는 경우 발생 → stale 1800s (30분) 으로 완화.
    let stale_secs = 1800u64;
    let started = std::time::Instant::now();
    let mut last_progress_at = std::time::Instant::now();

    loop {
        // 프로세스 완료 확인
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {} // 아직 실행 중
            Err(_) => break,
        }

        // 타임아웃 확인 (절대 또는 정체)
        let elapsed = started.elapsed().as_secs();
        let stale = last_progress_at.elapsed().as_secs();
        if elapsed > timeout_secs || stale > stale_secs {
            // osascript 프로세스 강제 종료
            let _ = child.kill().await;
            let last_step = if last_message.is_empty() {
                "시작 중".to_string()
            } else {
                last_message.clone()
            };
            let reason = if elapsed > timeout_secs {
                format!("절대 타임아웃 {}초 초과", timeout_secs)
            } else {
                format!("진행률 정체 {}초 초과", stale_secs)
            };
            return Err(format!(
                "InDesign 추출 중단 ({}). 마지막 단계: {}",
                reason, last_step
            ));
        }

        // .progress 파일에서 상세 진행률 읽기
        if let Ok(content) = std::fs::read_to_string(&progress_path) {
            if let Ok(prog) = serde_json::from_str::<serde_json::Value>(&content) {
                let step = prog.get("step").and_then(|v| v.as_str()).unwrap_or("");
                let current = prog.get("current").and_then(|v| v.as_i64()).unwrap_or(0);
                let total = prog.get("total").and_then(|v| v.as_i64()).unwrap_or(0);

                let display = match step {
                    "open" => "문서 열기 중...".to_string(),
                    "idml" if total > 0 => format!("IDML 내보내기 중... ({}페이지)", total),
                    "idml" => "IDML 내보내기 중...".to_string(),
                    "resolved" if total > 0 => format!("resolved 수집 중... ({}페이지)", total),
                    "resolved" => "resolved 수집 중...".to_string(),
                    "resolved_styles" if total > 0 => format!("스타일/색상 수집 중... ({}페이지)", total),
                    "resolved_styles" => "스타일/색상 수집 중...".to_string(),
                    "resolved_stories" if current > 0 && total > 0 => format!("스토리 수집 중... ({}/{})", current, total),
                    "resolved_stories" => "스토리 수집 중...".to_string(),
                    "resolved_frames" => "텍스트프레임 수집 중...".to_string(),
                    "resolved_items" => "페이지 아이템 수집 중...".to_string(),
                    "rendered_frames" if current > 0 && total > 0 => format!("배경/도형 렌더링 중... ({}/{})", current, total),
                    "rendered_frames" => "배경/도형 렌더링 중...".to_string(),
                    "pdf" => "PDF 프리뷰 생성 중...".to_string(),
                    _ => format!("추출 중... ({})", step),
                };

                if display != last_message {
                    last_message = display.clone();
                    last_progress_at = std::time::Instant::now();
                    let phase = match step {
                        "open" => "launching",
                        "pdf" => "checking",
                        _ => "exporting",
                    };
                    emit_progress(app, phase, &display);
                }
            }
        }

        sleep(Duration::from_millis(300)).await;
    }

    // osascript 결과 수집
    let output = child.wait_with_output().await
        .map_err(|e| format!("osascript 결과 수집 실패: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("InDesign 스크립트 실행 실패:\n{}", stderr));
    }

    // 진행률: 완료 확인 중
    emit_progress(app, "checking", "추출 결과 확인 중...");

    // .done 시그널 파일 확인
    if done_path.exists() {
        let done_content = std::fs::read_to_string(&done_path)
            .map_err(|e| format!(".done 파일 읽기 실패: {}", e))?;
        let done_signal: DoneSignal = serde_json::from_str(&done_content)
            .map_err(|e| format!(".done 파일 파싱 실패: {}", e))?;

        if done_signal.status == "error" {
            let msg = done_signal.message.unwrap_or_else(|| "알 수 없는 오류".to_string());
            return Err(format!("InDesign 추출 오류: {}", msg));
        }
    } else {
        for _ in 0..10 {
            sleep(Duration::from_millis(500)).await;
            if done_path.exists() {
                break;
            }
        }
        if done_path.exists() {
            let done_content = std::fs::read_to_string(&done_path)
                .map_err(|e| format!(".done 파일 읽기 실패: {}", e))?;
            let done_signal: DoneSignal = serde_json::from_str(&done_content)
                .map_err(|e| format!(".done 파일 파싱 실패: {}", e))?;

            if done_signal.status == "error" {
                let msg = done_signal.message.unwrap_or_else(|| "알 수 없는 오류".to_string());
                return Err(format!("InDesign 추출 오류: {}", msg));
            }
        }
    }

    // 출력 파일 확인
    let idml_path = output_dir.join("output.idml");
    if !idml_path.exists() {
        return Err("IDML 파일 생성 실패: output.idml이 생성되지 않았습니다.".into());
    }

    let resolved_json_path = output_dir.join("resolved.json");
    let preview_pdf_path = output_dir.join("preview.pdf");

    // PDF 배경은 extract_indd.jsx에서 통합 생성 (editable 판별 통일)
    emit_progress(app, "done", "추출 완료");

    // 추출 완료 후 데스크탑 앱으로 포커스 복귀
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.set_focus();
    }

    Ok(InddExtractResult {
        idml_path: idml_path.to_string_lossy().to_string(),
        resolved_json_path: if resolved_json_path.exists() {
            Some(resolved_json_path.to_string_lossy().to_string())
        } else {
            None
        },
        preview_pdf_path: if preview_pdf_path.exists() {
            Some(preview_pdf_path.to_string_lossy().to_string())
        } else {
            None
        },
        temp_dir: output_dir_str,
    })
}

/// SPEC-030 B.2: 스킵 페이지 목록을 포함한 부분 추출.
/// skip_render_pages: 렌더링을 건너뛸 1-based 페이지 인덱스 배열 (JSON 문자열, 예: "[1,3,5]")
pub async fn run_extraction_with_skip(
    app: &AppHandle,
    indd_path: &str,
    output_dir: &std::path::Path,
    jsx_path: &str,
    indesign_app_path: &str,
    start_page: i32,
    end_page: i32,
    spread_mode: bool,
    perf_mode: &str,
    skip_pdf: bool,
    skip_render_pages_json: &str,
) -> Result<InddExtractResult, String> {
    let app_name = app_name_from_path(indesign_app_path);
    let output_dir_str = output_dir.to_string_lossy().to_string();
    let spread_flag = if spread_mode { "1" } else { "0" };
    let skip_pdf_flag = if skip_pdf { "1" } else { "0" };
    let config_path = find_bundled_config(app);
    // arguments[9] = skipRenderPages JSON, arguments[10] = mode "full"
    let applescript = format!(
        r#"tell application "{app_name}"
    activate
    with timeout of 3600 seconds
        do script (read POSIX file "{jsx_path}") language javascript with arguments {{"{indd_path}", "{output_dir}", "{start_page}", "{end_page}", "{spread_flag}", "0", "{config_path}", "{perf_mode}", "{skip_pdf_flag}", "{skip_render_pages}", "full"}}
    end timeout
end tell"#,
        app_name = app_name,
        jsx_path = jsx_path,
        indd_path = indd_path,
        output_dir = output_dir_str,
        start_page = start_page,
        end_page = end_page,
        spread_flag = spread_flag,
        config_path = config_path,
        perf_mode = perf_mode,
        skip_pdf_flag = skip_pdf_flag,
        skip_render_pages = skip_render_pages_json,
    );
    emit_progress(app, "exporting", "부분 재추출 중 (변경 페이지만)...");
    let mut child = Command::new("osascript")
        .args(["-e", &applescript])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("osascript 실행 실패: {}", e))?;
    // run_extraction과 동일한 대기 루프 — 공통 헬퍼로 추출하지 않고 인라인 복사
    let done_path = output_dir.join(".done");
    let progress_path = output_dir.join(".progress");
    let mut last_message = String::new();
    let timeout_secs = 3600u64;
    let stale_secs = 1800u64;
    let started = std::time::Instant::now();
    let mut last_progress_at = std::time::Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {}
            Err(_) => break,
        }
        let elapsed = started.elapsed().as_secs();
        let stale = last_progress_at.elapsed().as_secs();
        if elapsed > timeout_secs || stale > stale_secs {
            let _ = child.kill().await;
            return Err(format!(
                "부분 추출 타임아웃. 마지막 단계: {}",
                if last_message.is_empty() { "시작 중".to_string() } else { last_message.clone() }
            ));
        }
        if let Ok(content) = std::fs::read_to_string(&progress_path) {
            if content != last_message {
                last_message = content.clone();
                last_progress_at = std::time::Instant::now();
                if let Ok(prog) = serde_json::from_str::<serde_json::Value>(&content) {
                    let step = prog.get("step").and_then(|v| v.as_str()).unwrap_or("");
                    let cur = prog.get("current").and_then(|v| v.as_i64()).unwrap_or(0);
                    let tot = prog.get("total").and_then(|v| v.as_i64()).unwrap_or(0);
                    emit_progress(app, step, &format!("{}/{}", cur, tot));
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    let _ = child.wait().await;
    if done_path.exists() {
        let done_content = std::fs::read_to_string(&done_path).unwrap_or_default();
        if let Ok(sig) = serde_json::from_str::<DoneSignal>(&done_content) {
            if sig.status == "error" {
                return Err(format!("부분 추출 오류: {}", sig.message.unwrap_or_default()));
            }
        }
    }
    let idml_path = output_dir.join("output.idml");
    if !idml_path.exists() {
        return Err("부분 추출 실패: output.idml이 생성되지 않았습니다.".into());
    }
    let resolved_json_path = output_dir.join("resolved.json");
    let preview_pdf_path = output_dir.join("preview.pdf");
    emit_progress(app, "done", "부분 추출 완료");
    Ok(InddExtractResult {
        idml_path: idml_path.to_string_lossy().to_string(),
        resolved_json_path: if resolved_json_path.exists() {
            Some(resolved_json_path.to_string_lossy().to_string())
        } else {
            None
        },
        preview_pdf_path: if preview_pdf_path.exists() {
            Some(preview_pdf_path.to_string_lossy().to_string())
        } else {
            None
        },
        temp_dir: output_dir_str,
    })
}

/// SPEC-030 B.2: 경량 pre-scan — 해시/아이템맵만 계산하고 종료.
/// 결과 page_hashes.json + page_item_map.json 을 output_dir에 생성한다.
/// 타임아웃은 120초 (렌더링 없음).
pub async fn run_page_hash_scan(
    app: &AppHandle,
    indd_path: &str,
    output_dir: &std::path::Path,
    jsx_path: &str,
    indesign_app_path: &str,
) -> Result<(), String> {
    let app_name = app_name_from_path(indesign_app_path);
    let output_dir_str = output_dir.to_string_lossy().to_string();
    let config_path = find_bundled_config(app);
    // arguments[10] = "pre_scan"
    let applescript = format!(
        r#"tell application "{app_name}"
    activate
    with timeout of 300 seconds
        do script (read POSIX file "{jsx_path}") language javascript with arguments {{"{indd_path}", "{output_dir}", "0", "0", "0", "0", "{config_path}", "standard", "0", "", "pre_scan"}}
    end timeout
end tell"#,
        app_name = app_name,
        jsx_path = jsx_path,
        indd_path = indd_path,
        output_dir = output_dir_str,
        config_path = config_path,
    );
    emit_progress(app, "scanning", "페이지 변경 감지 중...");
    let mut child = Command::new("osascript")
        .args(["-e", &applescript])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("pre-scan osascript 실행 실패: {}", e))?;
    let done_path = output_dir.join(".done");
    let timeout_secs = 300u64;
    let started = std::time::Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {}
            Err(_) => break,
        }
        if started.elapsed().as_secs() > timeout_secs {
            let _ = child.kill().await;
            return Err("pre-scan 타임아웃".into());
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    let _ = child.wait().await;
    if !output_dir.join("page_hashes.json").exists() {
        return Err("pre-scan 실패: page_hashes.json 미생성".into());
    }
    let _ = done_path; // 경고 방지
    Ok(())
}

/// 추출 진행률 이벤트를 프론트엔드로 emit한다 (공개 버전).
pub fn emit_progress_pub(app: &AppHandle, phase: &str, message: &str) {
    emit_progress(app, phase, message);
}

/// `find_bundled_config`의 공개 래퍼 (캐시 키 계산용).
pub fn find_bundled_config_pub(app: &AppHandle) -> String {
    find_bundled_config(app)
}

/// 추출 진행률 이벤트를 프론트엔드로 emit한다.
fn emit_progress(app: &AppHandle, phase: &str, message: &str) {
    let _ = app.emit(
        "indd-extraction-progress",
        ExtractionProgress {
            phase: phase.to_string(),
            message: message.to_string(),
        },
    );
}

/// 문자열을 JavaScript 유니코드 이스케이프로 변환한다.
/// ASCII는 그대로, 비ASCII(한글 등)는 \uXXXX 형태로 변환.
/// macOS NFD 정규화(자모 분해)를 NFC(완성형)로 변환 후 이스케이프.
fn escape_to_js_unicode(s: &str) -> String {
    // NFC 정규화: macOS 파일 경로의 NFD 한글을 완성형으로 변환
    let normalized = unicode_normalization_nfc(s);
    let mut result = String::new();
    for c in normalized.chars() {
        if c.is_ascii() {
            match c {
                '\\' => result.push_str("\\\\"),
                '"' => result.push_str("\\\""),
                _ => result.push(c),
            }
        } else {
            let mut buf = [0u16; 2];
            for u in c.encode_utf16(&mut buf) {
                result.push_str(&format!("\\u{:04x}", u));
            }
        }
    }
    result
}

/// 간단한 NFC 정규화 (한글 자모 조합).
/// macOS는 파일 경로를 NFD(분해형)로 저장하므로, NFC(완성형)로 변환해야
/// JavaScript에서 올바른 유니코드 이스케이프(\uAC00 등)가 생성된다.
fn unicode_normalization_nfc(s: &str) -> String {
    // 한글 자모 범위: 초성 0x1100-0x1112, 중성 0x1161-0x1175, 종성 0x11A8-0x11C2
    let chars: Vec<char> = s.chars().collect();
    let mut result = String::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        // 한글 초성 체크
        if ('\u{1100}'..='\u{1112}').contains(&c) && i + 1 < chars.len() {
            let v = chars[i + 1];
            if ('\u{1161}'..='\u{1175}').contains(&v) {
                let cho = c as u32 - 0x1100;
                let jung = v as u32 - 0x1161;
                // 종성 체크
                if i + 2 < chars.len() && ('\u{11A8}'..='\u{11C2}').contains(&chars[i + 2]) {
                    let jong = chars[i + 2] as u32 - 0x11A7;
                    let syllable = 0xAC00 + (cho * 21 + jung) * 28 + jong;
                    result.push(char::from_u32(syllable).unwrap_or(c));
                    i += 3;
                } else {
                    let syllable = 0xAC00 + (cho * 21 + jung) * 28;
                    result.push(char::from_u32(syllable).unwrap_or(c));
                    i += 2;
                }
                continue;
            }
        }
        result.push(c);
        i += 1;
    }
    result
}

/// PDF 배경 내보내기 스크립트 경로를 찾는다.
fn find_pdf_bg_script(app: &AppHandle) -> Result<String, String> {
    let dev_paths = [
        "../../scripts/export_pdf_bg.jsx",
        "../../../scripts/export_pdf_bg.jsx",
        "scripts/export_pdf_bg.jsx",
    ];
    for rel_path in dev_paths {
        let path = Path::new(rel_path);
        if path.exists() {
            return Ok(
                path.canonicalize()
                    .map_err(|e| format!("경로 해석 실패: {}", e))?
                    .to_string_lossy()
                    .to_string(),
            );
        }
    }
    Err("export_pdf_bg.jsx를 찾을 수 없습니다".into())
}

/// ExtendScript 파일 경로를 찾는다.
/// 1. 개발 경로: ../../scripts/extract_indd.jsx (프로젝트 루트 기준) — 소스 수정 즉시 반영
/// 2. 번들 리소스: resources/scripts/extract_indd.jsx
pub fn find_extendscript(app: &AppHandle) -> Result<String, String> {
    // 1. 개발 경로 우선 (src-tauri에서 상위 2단계 → 프로젝트 루트)
    let dev_paths = [
        "../../scripts/extract_indd.jsx",
        "../../../scripts/extract_indd.jsx",
        "scripts/extract_indd.jsx",
    ];
    for rel_path in dev_paths {
        let path = Path::new(rel_path);
        if path.exists() {
            return Ok(
                path.canonicalize()
                    .map_err(|e| format!("경로 해석 실패: {}", e))?
                    .to_string_lossy()
                    .to_string(),
            );
        }
    }

    // 2. 번들 리소스 경로 (빌드된 앱에서 사용)
    if let Ok(resource_dir) = app.path().resource_dir() {
        let bundled = resource_dir.join("scripts").join("extract_indd.jsx");
        if bundled.exists() {
            return Ok(bundled.to_string_lossy().to_string());
        }
        let bundled_flat = resource_dir.join("extract_indd.jsx");
        if bundled_flat.exists() {
            return Ok(bundled_flat.to_string_lossy().to_string());
        }
        let bundled_up = resource_dir
            .join("_up_")
            .join("_up_")
            .join("scripts")
            .join("extract_indd.jsx");
        if bundled_up.exists() {
            return Ok(bundled_up.to_string_lossy().to_string());
        }
    }

    Err("ExtendScript 파일을 찾을 수 없습니다: extract_indd.jsx".into())
}
