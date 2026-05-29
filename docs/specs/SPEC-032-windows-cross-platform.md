# SPEC-032: Windows 크로스 플랫폼 지원

## 상태
부분 구현 (2026-05-29) — Step 1~5 + `find_indesign_app` Windows 분기 완료. Step 6 (InDesign COM) 미완료 (Windows 머신 필요)

## 문제

현재 데스크탑 앱은 macOS 전용이다. InDesign 자동화에 `osascript`(AppleScript)를 사용하고, PNG 크롭에 `sips`, 프로세스 탐지에 `pgrep`, 파일 열기에 `open`, Java 탐지에 Homebrew 경로를 하드코딩하는 등 macOS API에 직접 의존하는 코드가 다수 존재한다.

Windows에서도 InDesign을 통한 추출 → HWPX 변환 워크플로우를 지원하려면 이 의존성들을 플랫폼별로 분기해야 한다.

## 목표

- Windows 10/11 + Adobe InDesign 2024/2025/2026 에서 `.indd → .hwpx` 변환이 동작한다
- 기존 macOS 동작은 변경 없이 유지된다
- 플랫폼 분기는 `#[cfg(target_os = ...)]` 로 컴파일 타임에 결정된다 (런타임 분기 없음)

## 범위 분석 — macOS 전용 코드 목록

| 파일 | 항목 | macOS 의존 이유 |
|------|------|----------------|
| `indesign.rs` | `osascript` 호출 | AppleScript는 macOS 전용 |
| `indesign.rs` | `pgrep -x <appname>` | macOS/Linux 유틸리티 |
| `indesign.rs` | `sips --cropOffset` | macOS 전용 이미지 도구 |
| `indesign.rs` | `/Applications/Adobe InDesign 20xx/...` | macOS 설치 경로 |
| `commands/mod.rs` | `find_java()` Homebrew 경로 | `/opt/homebrew/...` |
| `commands/mod.rs` | `/usr/libexec/java_home` | macOS 전용 유틸리티 |
| `commands/mod.rs` | `open_file()` — `Command::new("open")` | macOS `open` 명령 |
| `commands/extract.rs` | `#[cfg(unix)]` 심볼릭 링크 | Windows는 심볼릭 링크 권한 필요 |
| `extract_cache.rs` | `copy_dir_recursive` 심볼릭 링크 복사 | `std::os::unix::fs::symlink` |

## 해결 방안

### A. InDesign 자동화 — PowerShell COM 방식 (Windows)

macOS: `osascript` → AppleScript → InDesign `do script ... language javascript`
Windows: PowerShell → COM → InDesign `DoScript(path, JavaScriptLanguage)`

Windows InDesign은 COM(Component Object Model) 인터페이스를 제공한다.
PowerShell에서 아래와 같이 호출한다:

```powershell
$indd = [System.Runtime.InteropServices.Marshal]::GetActiveObject("InDesign.Application")
$indd.DoScript("C:\path\to\extract_indd.jsx", 1246973568, @("arg1","arg2",...))
```

- `1246973568` = InDesign JavaScript 언어 열거값 (`idJavaScript` = 0x4A534352)
- InDesign이 실행 중이 아닐 때: `New-Object -ComObject InDesign.Application.2026` 으로 기동
- 인수 전달: `$indd.DoScript(path, lang, @(arg0, arg1, ...))` — ArgumentList 파라미터

**구현 위치**: `indesign.rs` 내 플랫폼 분기 함수 추가

```rust
// Windows 전용 InDesign 자동화
#[cfg(target_os = "windows")]
async fn run_indesign_script_windows(
    jsx_path: &str,
    arguments: &[&str],
    output_dir: &Path,
    app_name: &str,
) -> Result<(), String> { ... }
```

PowerShell 스크립트를 임시 `.ps1` 파일로 작성 후 `powershell.exe -ExecutionPolicy Bypass -File` 로 실행한다.
(macOS의 `_extract.applescript` 파일 방식과 동일한 패턴)

### B. 프로세스 탐지 — Windows (pgrep 대체)

macOS: `pgrep -x "Adobe InDesign 2026"`
Windows: PowerShell `Get-Process -Name "InDesign"` 또는 `tasklist /FI "IMAGENAME eq InDesign.exe"`

```rust
#[cfg(target_os = "windows")]
async fn is_indesign_running_windows(app_name: &str) -> bool {
    // tasklist /FI "IMAGENAME eq InDesign.exe" /NH
    ...
}
```

### C. InDesign 설치 경로 탐지 — Windows

macOS: `/Applications/Adobe InDesign 20xx/Adobe InDesign 20xx.app`
Windows: `C:\Program Files\Adobe\Adobe InDesign 20xx\InDesign.exe`

```rust
#[cfg(target_os = "windows")]
pub fn find_indesign_app() -> Result<String, String> {
    let years = ["2026", "2025", "2024", "2023"];
    for year in years {
        let path = format!(
            r"C:\Program Files\Adobe\Adobe InDesign {}\InDesign.exe",
            year
        );
        if Path::new(&path).exists() {
            return Ok(path);
        }
    }
    // 레지스트리에서 탐지 (옵션): HKLM\SOFTWARE\Adobe\InDesign
    Err("Adobe InDesign을 찾을 수 없습니다.".into())
}
```

### D. PNG 크롭 (`sips` 대체) — image crate

macOS: `sips --cropOffset sy sx -c sh sw src --out dst`
Windows: `sips` 없음 → Rust `image` crate로 직접 크롭

`Cargo.toml`에 추가:
```toml
image = { version = "0.25", default-features = false, features = ["png"] }
```

```rust
// apply_crop_manifest 내 크롭 로직 교체
fn crop_png(src: &Path, dst: &Path, x: u32, y: u32, w: u32, h: u32) -> bool {
    use image::GenericImageView;
    let img = image::open(src).ok()?;
    let cropped = img.crop_imm(x, y, w, h);
    cropped.save(dst).is_ok()
}
```

`apply_crop_manifest` 함수를 `sips` 대신 이 함수를 사용하도록 교체 (macOS/Windows 공통).

> **주의**: `sips`는 macOS에서 PNG를 제자리(in-place)로 변환할 수 있지만 `image` crate는 decode→encode 왕복 비용이 있다. 배지 PNG 수십~수백 개 수준이므로 허용 범위.

### E. Java 탐지 — Windows 경로 추가

`commands/mod.rs`의 `find_java()`에 Windows 분기 추가:

```rust
#[cfg(target_os = "windows")]
pub(crate) fn find_java() -> String {
    let win_paths = [
        r"C:\Program Files\Eclipse Adoptium\jdk-21*\bin\java.exe",
        r"C:\Program Files\Microsoft\jdk-21*\bin\java.exe",
        r"C:\Program Files\Java\jdk-21\bin\java.exe",
        r"C:\Program Files\Java\jre-21\bin\java.exe",
    ];
    // glob 패턴 매칭 또는 JAVA_HOME 환경변수 우선 조회
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let bin = format!(r"{}\bin\java.exe", java_home);
        if Path::new(&bin).exists() { return bin; }
    }
    // 고정 경로 시도
    ...
    "java".to_string()
}
```

### F. `open_file()` — Windows `start` 명령

```rust
#[tauri::command]
pub fn open_file(path: String) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    std::process::Command::new("open").arg(&path)
        .spawn().map_err(|e| e.to_string())?;

    #[cfg(target_os = "windows")]
    std::process::Command::new("cmd")
        .args(["/C", "start", "", &path])
        .spawn().map_err(|e| e.to_string())?;

    #[cfg(target_os = "linux")]
    std::process::Command::new("xdg-open").arg(&path)
        .spawn().map_err(|e| e.to_string())?;

    Ok(())
}
```

### G. 심볼릭 링크 → Windows 정션(Junction) 또는 복사

`#[cfg(unix)]` 심볼릭 링크 코드(extract.rs × 3곳, extract_cache.rs × 1곳)에 Windows 대안 추가:

```rust
fn link_or_copy_links_dir(src: &Path, target: &Path) {
    #[cfg(unix)]
    { let _ = std::os::unix::fs::symlink(src, target); }

    #[cfg(windows)]
    {
        // junction: 권한 없이도 생성 가능 (디렉토리 전용)
        // std::os::windows::fs::symlink_dir 은 개발자 모드 필요
        // → 대안: 전체 복사 (Links 폴더는 원본 이미지이므로 대용량일 수 있음)
        // 성능 우선: junction 시도 → 실패 시 복사
        if junction::create(src, target).is_err() {
            let _ = copy_dir_recursive(src, target);
        }
    }
}
```

junction 크레이트 추가:
```toml
[target.'cfg(windows)'.dependencies]
junction = "1"
```

### H. `ensure_indesign_running` — Windows 통합

Windows에서는 `using terms from` 프로브 대신 COM 객체 생성 시도로 InDesign 준비 여부를 확인한다.

```powershell
# _probe.ps1
try {
    $app = [System.Runtime.InteropServices.Marshal]::GetActiveObject("InDesign.Application")
    Write-Host "running"
} catch {
    Write-Host "not_running"
}
```

### I. 빌드 파이프라인 — Windows CI

`tauri.conf.json`의 `"targets": "all"` 이미 설정되어 있어 `tauri build` 명령은 동일.

GitHub Actions (`.github/workflows/build-windows.yml`) 추가:
```yaml
runs-on: windows-latest
steps:
  - uses: actions/checkout@v4
  - uses: actions-rs/toolchain@v1
    with: { toolchain: stable, target: x86_64-pc-windows-msvc }
  - run: cd desktop && npm ci && npm run tauri build
```

## 수정 파일 목록

| # | 파일 | 변경 내용 |
|---|------|---------|
| 1 | `desktop/src-tauri/Cargo.toml` | `image` crate + `[target.'cfg(windows)'].dependencies]` junction 추가 |
| 2 | `desktop/src-tauri/src/indesign.rs` | `find_indesign_app`, `ensure_indesign_running`, `run_extraction`, `run_extraction_with_skip`, `run_page_hash_scan`, `apply_crop_manifest` 플랫폼 분기 |
| 3 | `desktop/src-tauri/src/commands/mod.rs` | `find_java()`, `open_file()` 플랫폼 분기 |
| 4 | `desktop/src-tauri/src/commands/extract.rs` | 심볼릭 링크 → `link_or_copy_links_dir()` 헬퍼로 교체 |
| 5 | `desktop/src-tauri/src/extract_cache.rs` | `copy_dir_recursive` 심볼릭 링크 분기 |
| 6 | `.github/workflows/build-windows.yml` | Windows CI 추가 (신규) |

## 구현 순서 (권장)

**Step 1 — 의존성 추가** (Cargo.toml)
> `image`, `junction(windows)` 추가, `cargo check --target x86_64-pc-windows-msvc` 로 컴파일 확인

**Step 2 — 플랫폼 무관 리팩토링** (extract.rs, extract_cache.rs)
> 심볼릭 링크를 `link_or_copy_links_dir()` 헬퍼로 추출. macOS 동작 변경 없음.

**Step 3 — `sips` 제거** (indesign.rs)
> `apply_crop_manifest` 내 `sips` 호출을 `image` crate로 교체. macOS에서 먼저 검증.

**Step 4 — Java 탐지** (commands/mod.rs)
> `find_java()` Windows 분기 추가.

**Step 5 — `open_file`** (commands/mod.rs)
> `open_file()` Windows 분기 추가.

**Step 6 — InDesign COM 자동화** (indesign.rs) ← 핵심, 가장 큰 변경
> Windows 전용 PowerShell COM 래퍼 구현. 실제 Windows 머신에서 InDesign 설치 후 검증 필요.

**Step 7 — CI** (.github/workflows/)
> Windows 빌드 파이프라인 추가.

## 알려진 제약 및 위험

### InDesign COM 인터페이스 제약
- COM `DoScript`의 인수 전달 방식이 AppleScript `with arguments` 와 완전히 동일하지 않을 수 있다 → ExtendScript의 `arguments` 변수 접근 방식 검증 필요
- Windows InDesign 2026 COM ProgID: `InDesign.Application.2026` (버전별로 다름, 자동 탐지 필요)
- 실행 중인 InDesign에 접근: `GetActiveObject` vs 새 인스턴스: `CreateObject`

### 심볼릭 링크 / Junction
- Windows junction은 로컬 볼륨에서만 동작. 네트워크 드라이브의 Links 폴더는 junction 불가 → 복사 폴백 필수
- junction 크레이트 의존성 추가 시 Windows 전용 conditional compile 확인

### 경로 구분자
- Rust의 `Path`/`PathBuf`는 플랫폼 네이티브 구분자 사용
- PowerShell 스크립트에 경로를 문자열로 포함할 때 `\` 이스케이프 주의
- ExtendScript(jsx)는 내부적으로 `/` 구분자 사용 — Windows ExtendScript 호환성 확인 필요

### ExtendScript `File` 객체
- `extract_indd.jsx`의 `File` 객체 경로 처리가 Windows 경로(`C:\...`)를 지원하는지 확인 필요
- ExtendScript의 `File` 클래스는 macOS/Windows 모두 지원하지만 경로 표기 차이 있음

### 테스트 환경
- macOS 없이 Windows InDesign이 설치된 별도 머신 필요
- GitHub Actions `windows-latest` 에는 InDesign이 없으므로 E2E 테스트는 수동

## 구현 결과 (2026-05-29)

**완료 (Step 1~5 + `find_indesign_app`)**:
- `Cargo.toml`: `image = "0.25"` + `[target.'cfg(windows)'].dependencies] junction = "1"`
- `extract_cache.rs`: `link_or_copy_links_dir()` pub(crate) 헬퍼 추가. `copy_dir_recursive` Windows 분기 (symlink → 복사)
- `commands/extract.rs`: 심볼릭 링크 3곳 → `link_or_copy_links_dir()` 호출로 교체
- `indesign.rs`: `apply_crop_manifest` — `sips` → `crop_png()` (`image` crate). `find_indesign_app` Windows 분기 (`C:\Program Files\Adobe\...`). `app_name_from_path` Windows InDesign.exe 처리
- `commands/mod.rs`: `find_java()` — JAVA_HOME 우선 + macOS/Windows/Linux cfg 분기. `open_file()` — macOS/Windows(`cmd /C start`)/Linux cfg 분기
- **macOS 빌드**: 에러 없음 (기존 경고 유지)

**미완료 (Step 6)**:
- `ensure_indesign_running` Windows (pgrep → tasklist)
- `run_extraction` / `run_extraction_with_skip` Windows (osascript → PowerShell COM)
- Windows 머신 + InDesign 설치 환경에서 검증 필요

## 검증

- [x] macOS 빌드 에러 없음
- [ ] `cargo check --target x86_64-pc-windows-msvc` 에러 없음 (Step 6 미완료로 일부 에러 예상)
- [ ] macOS에서 기존 변환 회귀 없음
- [ ] Windows 머신에서 InDesign으로 `extract_indd` 명령 성공
- [ ] Windows 머신에서 `.indd → .hwpx` 풀 변환 성공
- [ ] GitHub Actions Windows 빌드 성공 (NSIS 인스톨러 생성)
