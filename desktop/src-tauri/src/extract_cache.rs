// SPEC-011: ExtendScript 추출 캐싱
//
// INDD 파일 한 번 추출한 결과(IDML + resolved.json + preview.pdf + rendered_frames)를
// 영구 캐시 디렉토리에 보관해, 동일 INDD를 다시 변환할 때 ExtendScript 호출을 건너뛴다.
//
// 캐시 키:
//   - INDD 절대경로 + mtime + size
//   - Links 폴더 매니페스트 (파일별 mtime + size)
//   - extract_indd.jsx 파일의 mtime + size (스크립트 변경 시 자동 무효화)
//   - conversion-config.json 의 mtime + size (config 변경 시 자동 무효화)
// 위 값들을 SHA-256으로 해시한 hex 문자열.
//
// 캐시 위치: ~/Library/Caches/idml-to-hwpx/extracts/<cacheKey>/

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::indesign::InddExtractResult;

#[derive(Debug, Serialize, Deserialize)]
pub struct CacheMeta {
    pub cache_key: String,
    pub indd_path: String,
    pub created_at_unix: u64,
    pub script_version: String,
}

/// 캐시 루트 디렉토리. 없으면 생성.
pub fn cache_root() -> Result<PathBuf, String> {
    // dirs::cache_dir() = ~/Library/Caches on macOS
    let base = dirs::cache_dir()
        .ok_or_else(|| "사용자 캐시 디렉토리를 찾을 수 없습니다.".to_string())?;
    let dir = base.join("idml-to-hwpx").join("extracts");
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("캐시 디렉토리 생성 실패: {}", e))?;
    Ok(dir)
}

/// 파일의 mtime+size를 해시 입력에 추가한다. 파일이 없으면 "missing"으로 표시.
fn hash_file_meta(hasher: &mut Sha256, label: &str, path: &Path) {
    hasher.update(label.as_bytes());
    hasher.update(b":");
    if let Ok(meta) = std::fs::metadata(path) {
        let mtime = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        hasher.update(mtime.to_le_bytes());
        hasher.update(meta.len().to_le_bytes());
    } else {
        hasher.update(b"missing");
    }
    hasher.update(b"|");
}

/// Links 폴더의 매니페스트(파일별 mtime+size 정렬 목록)를 해시 입력에 추가.
fn hash_links_dir(hasher: &mut Sha256, links_dir: &Path) {
    hasher.update(b"links_dir:");
    if !links_dir.is_dir() {
        hasher.update(b"missing|");
        return;
    }
    let mut entries: Vec<(String, u128, u64)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(links_dir) {
        for entry in rd.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if let Ok(meta) = entry.metadata() {
                let mtime = meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_nanos())
                    .unwrap_or(0);
                entries.push((name, mtime, meta.len()));
            }
        }
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    for (name, mtime, size) in &entries {
        hasher.update(name.as_bytes());
        hasher.update(b":");
        hasher.update(mtime.to_le_bytes());
        hasher.update(b":");
        hasher.update(size.to_le_bytes());
        hasher.update(b"|");
    }
    hasher.update(b"//");
}

/// 캐시 키 계산.
pub fn compute_cache_key(
    indd_path: &Path,
    links_dir: Option<&Path>,
    jsx_path: &Path,
    config_path: Option<&Path>,
    spread_mode: bool,
    // SPEC-030: perf_mode 와 skip_pdf 도 캐시 키에 포함 — 모드별 PNG 해상도/PDF 유무 가 다르므로
    // 서로 다른 캐시 엔트리로 분리.
    perf_mode: &str,
    skip_pdf: bool,
) -> String {
    let mut hasher = Sha256::new();

    // INDD 절대 경로 (정규화)
    let canonical = indd_path
        .canonicalize()
        .unwrap_or_else(|_| indd_path.to_path_buf());
    hasher.update(b"indd_path:");
    hasher.update(canonical.to_string_lossy().as_bytes());
    hasher.update(b"|");

    hash_file_meta(&mut hasher, "indd", indd_path);
    hash_file_meta(&mut hasher, "jsx", jsx_path);

    if let Some(cfg) = config_path {
        hash_file_meta(&mut hasher, "config", cfg);
    } else {
        hasher.update(b"config:none|");
    }

    if let Some(links) = links_dir {
        hash_links_dir(&mut hasher, links);
    } else {
        hasher.update(b"links:none|");
    }

    hasher.update(b"spread:");
    hasher.update(if spread_mode { b"1|" } else { b"0|" });

    hasher.update(b"perfMode:");
    hasher.update(perf_mode.as_bytes());
    hasher.update(b"|skipPdf:");
    hasher.update(if skip_pdf { b"1|" } else { b"0|" });

    let digest = hasher.finalize();
    format!("{:x}", digest)
}

/// 캐시 디렉토리 경로 (디렉토리 자체는 hit 시에만 생성).
pub fn cache_entry_dir(cache_key: &str) -> Result<PathBuf, String> {
    Ok(cache_root()?.join(cache_key))
}

/// 캐시 조회. 캐시 디렉토리가 존재하고 필수 파일이 모두 있으면 결과 반환.
pub fn lookup(cache_key: &str) -> Option<InddExtractResult> {
    let dir = match cache_entry_dir(cache_key) {
        Ok(d) => d,
        Err(_) => return None,
    };
    if !dir.exists() {
        return None;
    }
    let idml = dir.join("output.idml");
    let resolved = dir.join("resolved.json");
    if !idml.exists() || !resolved.exists() {
        return None;
    }
    let preview = dir.join("preview.pdf");
    Some(InddExtractResult {
        idml_path: idml.to_string_lossy().to_string(),
        resolved_json_path: Some(resolved.to_string_lossy().to_string()),
        preview_pdf_path: if preview.exists() {
            Some(preview.to_string_lossy().to_string())
        } else {
            None
        },
        temp_dir: dir.to_string_lossy().to_string(),
    })
}

/// 추출 임시 디렉토리를 캐시 위치로 이동(또는 복사 후 삭제).
/// 이동 후 새 InddExtractResult를 반환.
pub fn store(
    cache_key: &str,
    indd_path: &str,
    temp_dir: &Path,
) -> Result<InddExtractResult, String> {
    let target = cache_entry_dir(cache_key)?;
    if target.exists() {
        // 이미 존재하면 기존 캐시 제거 후 교체
        let _ = std::fs::remove_dir_all(&target);
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("캐시 부모 디렉토리 생성 실패: {}", e))?;
    }
    // rename은 동일 파일시스템에서만 동작. 캐시는 보통 동일 볼륨이므로 시도 후 실패 시 복사.
    if std::fs::rename(temp_dir, &target).is_err() {
        copy_dir_recursive(temp_dir, &target)
            .map_err(|e| format!("캐시 복사 실패: {}", e))?;
        let _ = std::fs::remove_dir_all(temp_dir);
    }

    // 메타 파일 작성
    let meta = CacheMeta {
        cache_key: cache_key.to_string(),
        indd_path: indd_path.to_string(),
        created_at_unix: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
        script_version: env!("CARGO_PKG_VERSION").to_string(),
    };
    if let Ok(json) = serde_json::to_string_pretty(&meta) {
        let _ = std::fs::write(target.join("_cache_meta.json"), json);
    }

    let idml = target.join("output.idml");
    let resolved = target.join("resolved.json");
    let preview = target.join("preview.pdf");

    Ok(InddExtractResult {
        idml_path: idml.to_string_lossy().to_string(),
        resolved_json_path: if resolved.exists() {
            Some(resolved.to_string_lossy().to_string())
        } else {
            None
        },
        preview_pdf_path: if preview.exists() {
            Some(preview.to_string_lossy().to_string())
        } else {
            None
        },
        temp_dir: target.to_string_lossy().to_string(),
    })
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let dst_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dst_path)?;
        } else if ty.is_symlink() {
            // 심볼릭 링크는 그대로 복사 (Links 폴더용)
            #[cfg(unix)]
            {
                let target = std::fs::read_link(entry.path())?;
                let _ = std::os::unix::fs::symlink(target, &dst_path);
            }
        } else {
            std::fs::copy(entry.path(), &dst_path)?;
        }
    }
    Ok(())
}

/// 전체 캐시 디렉토리 삭제.
pub fn clear_all() -> Result<(usize, u64), String> {
    let dir = cache_root()?;
    let mut count = 0usize;
    let mut bytes = 0u64;
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for entry in rd.flatten() {
            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                bytes += dir_size(&entry.path()).unwrap_or(0);
                if std::fs::remove_dir_all(entry.path()).is_ok() {
                    count += 1;
                }
            }
        }
    }
    Ok((count, bytes))
}

/// 캐시 통계 (항목 수, 총 바이트).
pub fn stats() -> Result<(usize, u64), String> {
    let dir = cache_root()?;
    let mut count = 0usize;
    let mut bytes = 0u64;
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for entry in rd.flatten() {
            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                count += 1;
                bytes += dir_size(&entry.path()).unwrap_or(0);
            }
        }
    }
    Ok((count, bytes))
}

fn dir_size(path: &Path) -> std::io::Result<u64> {
    let mut total = 0u64;
    if let Ok(rd) = std::fs::read_dir(path) {
        for entry in rd.flatten() {
            let meta = entry.metadata()?;
            if meta.is_dir() {
                total += dir_size(&entry.path()).unwrap_or(0);
            } else {
                total += meta.len();
            }
        }
    }
    Ok(total)
}
