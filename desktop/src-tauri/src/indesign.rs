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

/// 설치된 Adobe InDesign 앱 경로를 탐지한다.
/// macOS: /Applications, Windows: C:\Program Files\Adobe.
#[cfg(target_os = "macos")]
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

#[cfg(target_os = "windows")]
pub fn find_indesign_app() -> Result<String, String> {
    let years = ["2026", "2025", "2024", "2023", "2022"];
    for year in years {
        let path = format!(
            r"C:\Program Files\Adobe\Adobe InDesign {}\InDesign.exe",
            year
        );
        if Path::new(&path).exists() {
            return Ok(path);
        }
    }
    Err("Adobe InDesign이 설치되어 있지 않습니다. C:\\Program Files\\Adobe 에서 InDesign을 찾을 수 없습니다.".into())
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn find_indesign_app() -> Result<String, String> {
    Err("이 플랫폼에서는 InDesign 자동화를 지원하지 않습니다.".into())
}

/// InDesign 앱 경로에서 앱 이름을 추출한다.
/// macOS: ".../Adobe InDesign 2025.app" → "Adobe InDesign 2025"
/// Windows: "...Adobe InDesign 2025\InDesign.exe" → 부모 디렉토리명에서 추출
fn app_name_from_path(app_path: &str) -> String {
    let p = Path::new(app_path);
    // Windows: 실행파일 이름이 "InDesign.exe"이면 부모 폴더 이름을 사용
    if p.file_name().map(|n| n == "InDesign.exe").unwrap_or(false) {
        if let Some(parent) = p.parent() {
            return parent
                .file_name()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "Adobe InDesign".to_string());
        }
    }
    p.file_stem()
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

/// InDesign 2026 동적 SDEF 대응: `using terms from application "..."` 컴파일 전에
/// InDesign이 실행 중이어야 SDEF를 로드할 수 있다.
/// InDesign이 실행 중이 아니면 `activate` 스크립트로 launch하고 초기화를 기다린다.
/// 프로세스 실행 여부와 무관하게, `using terms from` 블록으로 SDEF 로드 완료를
/// 확인한다. InDesign 2026은 OSAScriptingDefinitionIsDynamic = true이므로 프로세스가
/// 살아 있어도 SDEF가 준비되지 않으면 `do script`가 -2741로 실패한다.
async fn ensure_indesign_running(app_name: &str, output_dir: &Path) {
    let already_running = tokio::process::Command::new("pgrep")
        .args(["-x", app_name])
        .output()
        .await
        .map(|o| o.status.success())
        .unwrap_or(false);

    if !already_running {
        // 표준 Apple Event (SDEF 불필요) 로 InDesign 실행
        let prelaunch = format!("tell application \"{}\" to activate", app_name);
        let prelaunch_file = output_dir.join("_prelaunch.applescript");
        if std::fs::write(&prelaunch_file, &prelaunch).is_ok() {
            let _ = tokio::process::Command::new("osascript")
                .arg(&prelaunch_file)
                .output()
                .await;
        }
        // 첫 기동은 SDEF 로드에 시간이 더 걸림 → 먼저 10초 대기
        tokio::time::sleep(Duration::from_secs(10)).await;
    }

    // SDEF 준비 확인: `using terms from` 블록은 컴파일 시 SDEF를 로드하므로
    // 이 프로브가 성공 → do script 도 성공함을 보장한다.
    // 최대 90초(10회 × 9초) 대기.
    let probe = format!(
        "using terms from application \"{n}\"\n    tell application \"{n}\" to get name\nend using terms from",
        n = app_name
    );
    let probe_file = output_dir.join("_probe.applescript");
    if std::fs::write(&probe_file, &probe).is_ok() {
        for attempt in 0..10u8 {
            let ok = tokio::process::Command::new("osascript")
                .arg(&probe_file)
                .output()
                .await
                .map(|o| o.status.success())
                .unwrap_or(false);
            if ok {
                return;
            }
            // 프로브 실패: SDEF 미준비 또는 -609. 9초 대기 후 재시도.
            let wait = if attempt == 0 { 5 } else { 9 };
            tokio::time::sleep(Duration::from_secs(wait)).await;
        }
    }
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
    // InDesign 2026 동적 SDEF: 컴파일 전에 앱이 실행 중이어야 `using terms from` 성공
    ensure_indesign_running(&app_name, output_dir).await;
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
        r#"using terms from application "{app_name}"
    tell application "{app_name}"
        activate
        with timeout of 3600 seconds
            do script POSIX file "{jsx_path}" language javascript with arguments {{"{indd_path}", "{output_dir}", "{start_page}", "{end_page}", "{spread_flag}", "0", "{config_path}", "{perf_mode}", "{skip_pdf_flag}"}}
        end timeout
    end tell
end using terms from"#,
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

    // AppleScript를 파일로 저장 후 실행 (osascript -e 방식은 InDesign 2026 동적 SDEF 로딩 실패)
    let script_file = output_dir.join("_extract.applescript");
    std::fs::write(&script_file, &applescript)
        .map_err(|e| format!("AppleScript 파일 쓰기 실패: {}", e))?;
    let mut child = Command::new("osascript")
        .arg(&script_file)
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
    // SPEC-030 B.4: phase별 stale 타임아웃 차등 적용
    // - open: 300s (대용량 파일은 열기만 수분 소요)
    // - idml: 120s
    // - pdf: 600s (48페이지 복잡한 파일 PDF 내보내기 3~4분 소요)
    // - rendered_frames/render_badge: 1800s (복잡 페이지에서 10분+ 가능)
    // - resolved_*: 300s
    // - 기타: 120s
    let stale_secs_default = 120u64;
    let started = std::time::Instant::now();
    let mut last_progress_at = std::time::Instant::now();
    let mut last_heartbeat_at = std::time::Instant::now();
    let mut current_phase_stale = stale_secs_default;

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
        if elapsed > timeout_secs || stale > current_phase_stale {
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
                format!("진행률 정체 {}초 초과", current_phase_stale)
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
                let desc = prog.get("desc").and_then(|v| v.as_str()).unwrap_or("");

                // SPEC-030 B.4: phase별 stale 타임아웃 갱신
                current_phase_stale = match step {
                    "open" => 600,
                    "idml" => 120,
                    // PDF 내보내기는 48페이지 복잡한 파일에서 3~4분 소요 가능
                    "pdf" => 600,
                    "rendered_frames" | "render_badge" | "render_frame" => 1800,
                    s if s.starts_with("resolved") => 300,
                    _ => stale_secs_default,
                };

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
                    "render_badge" if !desc.is_empty() && total > 0 => format!("배지 렌더링 중... ({}/{}) {}", current, total, desc),
                    "render_badge" if total > 0 => format!("배지 렌더링 중... ({}/{})", current, total),
                    "render_frame" if !desc.is_empty() && total > 0 => format!("프레임 렌더링 중... ({}/{}) {}", current, total, desc),
                    "render_frame" if total > 0 => format!("프레임 렌더링 중... ({}/{})", current, total),
                    "pdf" => "PDF 프리뷰 생성 중...".to_string(),
                    _ => format!("추출 중... ({})", step),
                };

                if display != last_message {
                    last_message = display.clone();
                    last_progress_at = std::time::Instant::now();
                    last_heartbeat_at = std::time::Instant::now();
                    let phase = match step {
                        "open" => "launching",
                        "pdf" => "checking",
                        _ => "exporting",
                    };
                    emit_progress(app, phase, &display);
                } else if matches!(step, "pdf" | "idml" | "open")
                    && last_heartbeat_at.elapsed().as_secs() >= 10
                {
                    let stale = last_progress_at.elapsed().as_secs();
                    let heartbeat_msg = match step {
                        "pdf" => format!("PDF 프리뷰 생성 중... ({}초 경과)", stale),
                        "idml" => format!("IDML 내보내기 중... ({}초 경과)", stale),
                        "open" => format!("문서 열기 중... ({}초 경과)", stale),
                        _ => display.clone(),
                    };
                    last_heartbeat_at = std::time::Instant::now();
                    let phase = if step == "pdf" { "checking" } else { "exporting" };
                    emit_progress(app, phase, &heartbeat_msg);
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
        // -2741: InDesign SDEF 미준비 (크래시 후 재시작 중 경합) → 재시도 1회
        if stderr.contains("-2741") {
            emit_progress(app, "launching", "InDesign 재시작 대기 중...");
            ensure_indesign_running(&app_name, output_dir).await;
            emit_progress(app, "exporting", "IDML 추출 재시도 중...");
            let mut child2 = Command::new("osascript")
                .arg(&script_file)
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .spawn()
                .map_err(|e| format!("osascript 재시도 실행 실패: {}", e))?;
            let output2 = child2.wait_with_output().await
                .map_err(|e| format!("osascript 재시도 결과 수집 실패: {}", e))?;
            if !output2.status.success() {
                let stderr2 = String::from_utf8_lossy(&output2.stderr);
                return Err(format!("InDesign 스크립트 실행 실패:\n{}", stderr2));
            }
        } else {
            return Err(format!("InDesign 스크립트 실행 실패:\n{}", stderr));
        }
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
    // InDesign 2026 동적 SDEF: 컴파일 전에 앱이 실행 중이어야 `using terms from` 성공
    ensure_indesign_running(&app_name, output_dir).await;
    let output_dir_str = output_dir.to_string_lossy().to_string();
    let spread_flag = if spread_mode { "1" } else { "0" };
    let skip_pdf_flag = if skip_pdf { "1" } else { "0" };
    let config_path = find_bundled_config(app);
    // arguments[9] = skipRenderPages JSON, arguments[10] = mode "full"
    let applescript = format!(
        r#"using terms from application "{app_name}"
    tell application "{app_name}"
        activate
        with timeout of 3600 seconds
            do script POSIX file "{jsx_path}" language javascript with arguments {{"{indd_path}", "{output_dir}", "{start_page}", "{end_page}", "{spread_flag}", "0", "{config_path}", "{perf_mode}", "{skip_pdf_flag}", "{skip_render_pages}", "full"}}
        end timeout
    end tell
end using terms from"#,
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
    let script_file = output_dir.join("_extract.applescript");
    std::fs::write(&script_file, &applescript)
        .map_err(|e| format!("AppleScript 파일 쓰기 실패: {}", e))?;
    let mut child = Command::new("osascript")
        .arg(&script_file)
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
    let mut last_heartbeat_at = std::time::Instant::now();
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
                last_heartbeat_at = std::time::Instant::now();
                if let Ok(prog) = serde_json::from_str::<serde_json::Value>(&content) {
                    let step = prog.get("step").and_then(|v| v.as_str()).unwrap_or("");
                    let cur = prog.get("current").and_then(|v| v.as_i64()).unwrap_or(0);
                    let tot = prog.get("total").and_then(|v| v.as_i64()).unwrap_or(0);
                    emit_progress(app, step, &format!("{}/{}", cur, tot));
                }
            } else if last_heartbeat_at.elapsed().as_secs() >= 10 {
                if let Ok(prog) = serde_json::from_str::<serde_json::Value>(&content) {
                    let step = prog.get("step").and_then(|v| v.as_str()).unwrap_or("");
                    if matches!(step, "pdf" | "idml" | "open") {
                        let heartbeat_msg = match step {
                            "pdf" => format!("PDF 프리뷰 생성 중... ({}초 경과)", stale),
                            "idml" => format!("IDML 내보내기 중... ({}초 경과)", stale),
                            "open" => format!("문서 열기 중... ({}초 경과)", stale),
                            _ => format!("추출 중... ({}초 경과)", stale),
                        };
                        last_heartbeat_at = std::time::Instant::now();
                        let phase = if step == "pdf" { "checking" } else { "exporting" };
                        emit_progress(app, phase, &heartbeat_msg);
                    }
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
    // InDesign 2026 동적 SDEF: 컴파일 전에 앱이 실행 중이어야 `using terms from` 성공
    ensure_indesign_running(&app_name, output_dir).await;
    let output_dir_str = output_dir.to_string_lossy().to_string();
    let config_path = find_bundled_config(app);
    // arguments[10] = "pre_scan"
    let applescript = format!(
        r#"using terms from application "{app_name}"
    tell application "{app_name}"
        activate
        with timeout of 300 seconds
            do script POSIX file "{jsx_path}" language javascript with arguments {{"{indd_path}", "{output_dir}", "0", "0", "0", "0", "{config_path}", "standard", "0", "", "pre_scan"}}
        end timeout
    end tell
end using terms from"#,
        app_name = app_name,
        jsx_path = jsx_path,
        indd_path = indd_path,
        output_dir = output_dir_str,
        config_path = config_path,
    );
    emit_progress(app, "scanning", "페이지 변경 감지 중...");
    let script_file = output_dir.join("_extract.applescript");
    std::fs::write(&script_file, &applescript)
        .map_err(|e| format!("AppleScript 파일 쓰기 실패: {}", e))?;
    let mut child = Command::new("osascript")
        .arg(&script_file)
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

/// SPEC-030 B.1: `_crop_manifest.json`을 읽어 sips로 배지 PNG를 개별 크롭한다.
/// 반환값: 크롭에 성공한 항목 수.
/// 구 JSX 버그 보정: visibleBounds가 mm 단위일 때 dpi/72 계수 사용 → 좌표가 작게 계산됨.
/// 배치 PNG 너비와 매니페스트 max(x+w)의 비율로 스케일을 자동 감지.
pub fn apply_crop_manifest(output_dir: &std::path::Path) -> usize {
    let manifest_path = output_dir.join("_crop_manifest.json");
    let data = match std::fs::read_to_string(&manifest_path) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    let entries: Vec<serde_json::Value> = match serde_json::from_str(&data) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    // src별 max(x+w) 계산
    let mut src_max_xw: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    for entry in &entries {
        let src_rel = match entry["src"].as_str() { Some(s) => s, None => continue };
        let x = entry["x"].as_i64().unwrap_or(0);
        let w = entry["w"].as_i64().unwrap_or(0);
        let xw = x + w;
        let cur = src_max_xw.entry(src_rel.to_string()).or_insert(0);
        if xw > *cur { *cur = xw; }
    }
    // src별 스케일 감지 (PNG 너비를 읽어 ratio 계산)
    let mut src_scale: std::collections::HashMap<String, f64> = std::collections::HashMap::new();
    for (src_rel, max_xw) in &src_max_xw {
        if *max_xw <= 0 { src_scale.insert(src_rel.clone(), 1.0); continue; }
        let src_path = output_dir.join(src_rel);
        let png_w = read_png_width(&src_path).unwrap_or(0);
        if png_w > 0 {
            let ratio = png_w as f64 / *max_xw as f64;
            // 1.3~4.0 범위면 구 JSX 버그 보정 필요 (mm 단위)
            let scale = if ratio >= 1.3 && ratio <= 4.0 { ratio } else { 1.0 };
            src_scale.insert(src_rel.clone(), scale);
        } else {
            src_scale.insert(src_rel.clone(), 1.0);
        }
    }
    let mut count = 0usize;
    for entry in &entries {
        let src_rel = match entry["src"].as_str() { Some(s) => s, None => continue };
        let dst_rel = match entry["dst"].as_str() { Some(s) => s, None => continue };
        let x = match entry["x"].as_i64() { Some(v) => v, None => continue };
        let y = match entry["y"].as_i64() { Some(v) => v, None => continue };
        let w = match entry["w"].as_i64() { Some(v) if v > 0 => v, _ => continue };
        let h = match entry["h"].as_i64() { Some(v) if v > 0 => v, _ => continue };
        let src = output_dir.join(src_rel);
        let dst = output_dir.join(dst_rel);
        if !src.exists() { continue; }
        let scale = *src_scale.get(src_rel).unwrap_or(&1.0);
        let sx = ((x as f64 * scale).round() as i64).max(0) as u32;
        let sy = ((y as f64 * scale).round() as i64).max(0) as u32;
        let sw = ((w as f64 * scale).round() as i64).max(1) as u32;
        let sh = ((h as f64 * scale).round() as i64).max(1) as u32;
        if crop_png(&src, &dst, sx, sy, sw, sh) {
            count += 1;
        }
    }
    // 배치 PNG + 매니페스트 정리
    let mut seen = std::collections::HashSet::new();
    for entry in &entries {
        if let Some(src_rel) = entry["src"].as_str() {
            if seen.insert(src_rel.to_string()) {
                let _ = std::fs::remove_file(output_dir.join(src_rel));
            }
        }
    }
    let _ = std::fs::remove_file(&manifest_path);
    count
}

/// `image` crate로 PNG를 크롭해 dst에 저장한다. 성공 시 true.
fn crop_png(src: &std::path::Path, dst: &std::path::Path, x: u32, y: u32, w: u32, h: u32) -> bool {
    match image::open(src) {
        Ok(img) => img.crop_imm(x, y, w, h).save(dst).is_ok(),
        Err(_) => false,
    }
}

/// PNG 파일의 너비를 IHDR 청크에서 읽는다 (외부 라이브러리 없이).
fn read_png_width(path: &std::path::Path) -> Option<u32> {
    use std::io::Read;
    let mut f = std::fs::File::open(path).ok()?;
    let mut buf = [0u8; 24];
    f.read_exact(&mut buf).ok()?;
    // PNG signature: 8 bytes, IHDR length: 4 bytes, IHDR type: 4 bytes, width: 4 bytes BE
    if &buf[0..8] != b"\x89PNG\r\n\x1a\n" { return None; }
    Some(u32::from_be_bytes([buf[16], buf[17], buf[18], buf[19]]))
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
