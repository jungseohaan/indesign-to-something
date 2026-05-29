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
    // SPEC-030 B.2: 경로 인덱스 갱신 (전체 파일 해시가 변해도 이전 캐시를 찾을 수 있게)
    update_path_index(std::path::Path::new(indd_path), cache_key);

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

// ─────────────────────────────────────────────────────────────────
// SPEC-030 B.2: 페이지 단위 부분 캐시
// ─────────────────────────────────────────────────────────────────

/// INDD 경로 → 최근 캐시 키 인덱스 파일 경로.
/// 전체 파일 해시가 변해도 이전 캐시 엔트리를 찾을 수 있게 한다.
fn path_index_file() -> Result<PathBuf, String> {
    Ok(cache_root()?.join("_path_index.json"))
}

/// INDD 절대 경로로 가장 최근 캐시 키를 조회한다.
pub fn lookup_previous_cache_key(indd_path: &Path) -> Option<String> {
    let index_path = path_index_file().ok()?;
    let content = std::fs::read_to_string(&index_path).ok()?;
    let map: std::collections::HashMap<String, String> =
        serde_json::from_str(&content).ok()?;
    let canonical = indd_path
        .canonicalize()
        .unwrap_or_else(|_| indd_path.to_path_buf());
    map.get(&canonical.to_string_lossy().to_string()).cloned()
}

/// 경로 인덱스에 최신 캐시 키를 기록한다.
pub fn update_path_index(indd_path: &Path, cache_key: &str) {
    let Ok(index_path) = path_index_file() else { return };
    let mut map: std::collections::HashMap<String, String> = std::fs::read_to_string(&index_path)
        .ok()
        .and_then(|c| serde_json::from_str(&c).ok())
        .unwrap_or_default();
    let canonical = indd_path
        .canonicalize()
        .unwrap_or_else(|_| indd_path.to_path_buf());
    map.insert(canonical.to_string_lossy().to_string(), cache_key.to_string());
    if let Ok(json) = serde_json::to_string(&map) {
        let _ = std::fs::write(&index_path, json);
    }
}

/// 캐시 엔트리에서 page_hashes.json을 읽어 반환한다.
/// 키: 1-based 페이지 인덱스 문자열 → 해시 문자열
pub fn load_page_hashes(cache_key: &str) -> Option<std::collections::HashMap<String, String>> {
    let dir = cache_entry_dir(cache_key).ok()?;
    let content = std::fs::read_to_string(dir.join("page_hashes.json")).ok()?;
    serde_json::from_str(&content).ok()
}

/// 캐시 엔트리에서 page_item_map.json을 읽어 반환한다.
/// 키: 1-based 페이지 인덱스 문자열 → item DOM ID 배열
pub fn load_page_item_map(
    cache_key: &str,
) -> Option<std::collections::HashMap<String, Vec<u64>>> {
    let dir = cache_entry_dir(cache_key).ok()?;
    let content = std::fs::read_to_string(dir.join("page_item_map.json")).ok()?;
    serde_json::from_str(&content).ok()
}

/// 두 해시 맵을 비교해 변경되지 않은 페이지 인덱스(1-based) 목록을 반환한다.
pub fn unchanged_page_indices(
    cached: &std::collections::HashMap<String, String>,
    current: &std::collections::HashMap<String, String>,
) -> Vec<u32> {
    let mut unchanged = Vec::new();
    for (page_str, curr_hash) in current {
        if let Some(cached_hash) = cached.get(page_str) {
            if cached_hash == curr_hash {
                if let Ok(idx) = page_str.parse::<u32>() {
                    unchanged.push(idx);
                }
            }
        }
    }
    unchanged.sort_unstable();
    unchanged
}

/// 이전 캐시 엔트리에서 변경되지 않은 페이지의 PNG/PDF 파일을 새 추출 디렉토리로 복사한다.
/// `rendered_frames/` 안의 파일은 page_item_map을 통해 해당 페이지 소속 아이템을 판단하고,
/// `rendered_frames/page_bg_N.*` 파일은 페이지 인덱스로 직접 매칭한다.
pub fn copy_cached_pages(
    cached_cache_key: &str,
    new_dir: &Path,
    unchanged_pages: &[u32],
    item_map: &std::collections::HashMap<String, Vec<u64>>,
) -> usize {
    if unchanged_pages.is_empty() {
        return 0;
    }

    let cached_dir = match cache_entry_dir(cached_cache_key) {
        Ok(d) => d,
        Err(_) => return 0,
    };

    // 변경되지 않은 페이지 index set (0-based for page_bg_N naming)
    let page_set: std::collections::HashSet<u32> =
        unchanged_pages.iter().copied().collect();

    // unchanged pages 의 item ID set 구축
    let mut item_id_set: std::collections::HashSet<u64> = std::collections::HashSet::new();
    for page_idx in unchanged_pages {
        let key = page_idx.to_string();
        if let Some(ids) = item_map.get(&key) {
            for id in ids {
                item_id_set.insert(*id);
            }
        }
    }

    let mut copied = 0usize;

    // 1. rendered_frames/ — page_bg_N.* 파일 (0-based page index)
    let cached_rf = cached_dir.join("rendered_frames");
    let new_rf = new_dir.join("rendered_frames");
    let _ = std::fs::create_dir_all(&new_rf);
    if let Ok(rd) = std::fs::read_dir(&cached_rf) {
        for entry in rd.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            let dst = new_rf.join(&name);
            if dst.exists() {
                continue;
            }
            // page_bg_N.png / page_bg_N.pdf — N is 0-based, pgIdx (1-based) = N+1
            if name.starts_with("page_bg_") {
                if let Some(rest) = name.strip_prefix("page_bg_") {
                    let num_str = rest.split('.').next().unwrap_or("");
                    if let Ok(n) = num_str.parse::<u32>() {
                        let one_based = n + 1;
                        if page_set.contains(&one_based) {
                            if std::fs::copy(entry.path(), &dst).is_ok() {
                                copied += 1;
                            }
                        }
                    }
                }
                continue;
            }
            // badge_ID.png / frame_ID.png / graphic_ID.png / etc — match by item ID
            let item_id = extract_item_id_from_filename(&name);
            if let Some(id) = item_id {
                if item_id_set.contains(&id) {
                    if std::fs::copy(entry.path(), &dst).is_ok() {
                        copied += 1;
                    }
                }
            }
        }
    }

    // 2. pageBackgrounds/ (page_bg_N.pdf 는 여기서도 있을 수 있음)
    let cached_pb = cached_dir.join("pageBackgrounds");
    let new_pb = new_dir.join("pageBackgrounds");
    if cached_pb.is_dir() {
        let _ = std::fs::create_dir_all(&new_pb);
        if let Ok(rd) = std::fs::read_dir(&cached_pb) {
            for entry in rd.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                let dst = new_pb.join(&name);
                if dst.exists() {
                    continue;
                }
                if name.starts_with("page_bg_") {
                    if let Some(rest) = name.strip_prefix("page_bg_") {
                        let num_str = rest.split('.').next().unwrap_or("");
                        if let Ok(n) = num_str.parse::<u32>() {
                            let one_based = n + 1;
                            if page_set.contains(&one_based) {
                                if std::fs::copy(entry.path(), &dst).is_ok() {
                                    copied += 1;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    copied
}

/// 파일명에서 item DOM ID를 추출한다 (badge_12345.png → 12345).
fn extract_item_id_from_filename(name: &str) -> Option<u64> {
    // 패턴: <prefix>_<id>.<ext>
    let without_ext = name.rsplit_once('.').map(|(l, _)| l).unwrap_or(name);
    let after_underscore = without_ext.rsplit_once('_').map(|(_, r)| r)?;
    after_underscore.parse().ok()
}

// ─────────────────────────────────────────────────────────────────
// SPEC-030: 부분 resolved.json 과 캐시된 전체 resolved.json 병합
// ─────────────────────────────────────────────────────────────────

/// 증분 추출 후 partial resolved.json 과 이전 캐시의 complete resolved.json 을 병합한다.
///
/// 병합 규칙:
/// - documentInfo, paragraphStyles, colors, fonts, fontMetrics: 새 파일 우선
/// - stories, textFrames, pages, pageItems: ID/index 기준으로 합산 (새 파일 항목 우선)
/// - renderedTextFrames, renderedFloatingItems: id 기준으로 합산 (새 파일 항목 우선)
/// - editableTextFrameIds: 합산 후 중복 제거
///
/// 변경된 페이지의 항목은 새 파일에, 변경 없는 페이지의 항목은 캐시에 있다.
pub fn merge_resolved_json(
    new_path: &Path,
    cached_cache_key: &str,
) -> Result<(), String> {
    let cached_dir = cache_entry_dir(cached_cache_key)
        .map_err(|e| format!("캐시 디렉토리 조회 실패: {}", e))?;
    let cached_path = cached_dir.join("resolved.json");

    if !cached_path.exists() {
        // 캐시에 resolved.json 없으면 병합 불필요
        return Ok(());
    }

    let new_content = std::fs::read_to_string(new_path)
        .map_err(|e| format!("new resolved.json 읽기 실패: {}", e))?;
    let cached_content = std::fs::read_to_string(&cached_path)
        .map_err(|e| format!("cached resolved.json 읽기 실패: {}", e))?;

    let mut new_val: serde_json::Value = serde_json::from_str(&new_content)
        .map_err(|e| format!("new resolved.json 파싱 실패: {}", e))?;
    let cached_val: serde_json::Value = serde_json::from_str(&cached_content)
        .map_err(|e| format!("cached resolved.json 파싱 실패: {}", e))?;

    // 배열 필드 병합 헬퍼: new 배열에 없는 ID의 cached 항목을 추가
    fn merge_array_by_field(
        new_val: &mut serde_json::Value,
        cached_val: &serde_json::Value,
        field: &str,
        id_key: &str,
    ) {
        let new_arr = match new_val.get_mut(field).and_then(|v| v.as_array_mut()) {
            Some(a) => a,
            None => return,
        };
        let cached_arr = match cached_val.get(field).and_then(|v| v.as_array()) {
            Some(a) => a,
            None => return,
        };
        // 새 배열의 ID 세트 구축
        let new_ids: std::collections::HashSet<String> = new_arr
            .iter()
            .filter_map(|item| item.get(id_key))
            .filter_map(|v| match v {
                serde_json::Value::String(s) => Some(s.clone()),
                serde_json::Value::Number(n) => Some(n.to_string()),
                _ => None,
            })
            .collect();
        // cached에만 있는 항목 추가
        for item in cached_arr {
            let item_id = item
                .get(id_key)
                .and_then(|v| match v {
                    serde_json::Value::String(s) => Some(s.clone()),
                    serde_json::Value::Number(n) => Some(n.to_string()),
                    _ => None,
                });
            if let Some(id) = item_id {
                if !new_ids.contains(&id) {
                    new_arr.push(item.clone());
                }
            }
        }
    }

    // pages는 "index" 필드 기준
    fn merge_pages(new_val: &mut serde_json::Value, cached_val: &serde_json::Value) {
        let new_arr = match new_val.get_mut("pages").and_then(|v| v.as_array_mut()) {
            Some(a) => a,
            None => return,
        };
        let cached_arr = match cached_val.get("pages").and_then(|v| v.as_array()) {
            Some(a) => a,
            None => return,
        };
        let new_indices: std::collections::HashSet<i64> = new_arr
            .iter()
            .filter_map(|item| item.get("index").and_then(|v| v.as_i64()))
            .collect();
        for item in cached_arr {
            if let Some(idx) = item.get("index").and_then(|v| v.as_i64()) {
                if !new_indices.contains(&idx) {
                    new_arr.push(item.clone());
                }
            }
        }
    }

    // editableTextFrameIds: 두 배열 합산 후 중복 제거
    fn merge_editable_ids(new_val: &mut serde_json::Value, cached_val: &serde_json::Value) {
        let new_arr = match new_val.get_mut("editableTextFrameIds").and_then(|v| v.as_array_mut()) {
            Some(a) => a,
            None => return,
        };
        let cached_arr = match cached_val.get("editableTextFrameIds").and_then(|v| v.as_array()) {
            Some(a) => a,
            None => return,
        };
        let existing: std::collections::HashSet<String> = new_arr
            .iter()
            .map(|v| v.to_string())
            .collect();
        for item in cached_arr {
            if !existing.contains(&item.to_string()) {
                new_arr.push(item.clone());
            }
        }
    }

    merge_array_by_field(&mut new_val, &cached_val, "stories", "id");
    merge_array_by_field(&mut new_val, &cached_val, "textFrames", "id");
    merge_array_by_field(&mut new_val, &cached_val, "pageItems", "id");
    merge_array_by_field(&mut new_val, &cached_val, "renderedTextFrames", "id");
    merge_array_by_field(&mut new_val, &cached_val, "renderedFloatingItems", "id");
    merge_array_by_field(&mut new_val, &cached_val, "renderedPdfFrames", "id");
    merge_array_by_field(&mut new_val, &cached_val, "renderedGraphicFrames", "id");
    merge_array_by_field(&mut new_val, &cached_val, "renderedImageFrames", "id");
    merge_pages(&mut new_val, &cached_val);
    merge_editable_ids(&mut new_val, &cached_val);

    let merged = serde_json::to_string(&new_val)
        .map_err(|e| format!("merged resolved.json 직렬화 실패: {}", e))?;
    std::fs::write(new_path, merged)
        .map_err(|e| format!("merged resolved.json 쓰기 실패: {}", e))?;

    eprintln!("[SPEC-030] resolved.json 병합 완료 (캐시: {})", cached_cache_key);
    Ok(())
}
