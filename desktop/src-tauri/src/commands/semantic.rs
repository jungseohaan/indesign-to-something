use serde::{Deserialize, Serialize};

// ─────────────────────────────────────────────────────────────────
// Semantic Layer File I/O Commands
// ─────────────────────────────────────────────────────────────────

/// 시멘틱 레이어 JSON을 파일에 저장.
#[tauri::command]
pub async fn save_semantic_layer(path: String, json: String) -> Result<(), String> {
    tokio::fs::write(&path, json.as_bytes())
        .await
        .map_err(|e| format!("시멘틱 레이어 저장 실패: {}", e))
}

/// 시멘틱 레이어 JSON을 파일에서 로드.
#[tauri::command]
pub async fn load_semantic_layer(path: String) -> Result<String, String> {
    tokio::fs::read_to_string(&path)
        .await
        .map_err(|e| format!("시멘틱 레이어 로드 실패: {}", e))
}

/// .pptx 바이너리를 파일에 저장 (base64로 전달받음).
#[tauri::command]
pub async fn save_pptx(path: String, base64_data: String) -> Result<(), String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&base64_data)
        .map_err(|e| format!("base64 디코딩 실패: {}", e))?;
    tokio::fs::write(&path, &bytes)
        .await
        .map_err(|e| format!("PPTX 저장 실패: {}", e))
}

/// 스키마 JSON을 파일에 저장.
#[tauri::command]
pub async fn save_schema(path: String, json: String) -> Result<(), String> {
    tokio::fs::write(&path, json.as_bytes())
        .await
        .map_err(|e| format!("스키마 저장 실패: {}", e))
}

/// 스키마 JSON을 파일에서 로드.
#[tauri::command]
pub async fn load_schema_file(path: String) -> Result<String, String> {
    tokio::fs::read_to_string(&path)
        .await
        .map_err(|e| format!("스키마 로드 실패: {}", e))
}

/// 사용자 스키마 디렉토리 목록 조회.
/// ~/.its-schemas/ 디렉토리에서 *.schema.json 파일 목록 반환.
#[tauri::command]
pub async fn list_user_schemas() -> Result<Vec<SchemaFileEntry>, String> {
    let schema_dir = get_user_schema_dir()?;
    if !schema_dir.exists() {
        return Ok(vec![]);
    }

    let mut entries = Vec::new();
    let mut read_dir = tokio::fs::read_dir(&schema_dir)
        .await
        .map_err(|e| format!("스키마 디렉토리 읽기 실패: {}", e))?;

    while let Some(entry) = read_dir.next_entry().await.map_err(|e| e.to_string())? {
        let path = entry.path();
        if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
            if name.ends_with(".schema.json") {
                let content = tokio::fs::read_to_string(&path)
                    .await
                    .unwrap_or_default();
                entries.push(SchemaFileEntry {
                    path: path.to_string_lossy().to_string(),
                    filename: name.to_string(),
                    content,
                });
            }
        }
    }

    entries.sort_by(|a, b| a.filename.cmp(&b.filename));
    Ok(entries)
}

/// 사용자 스키마 디렉토리에 스키마 저장.
#[tauri::command]
pub async fn save_user_schema(filename: String, json: String) -> Result<String, String> {
    let schema_dir = get_user_schema_dir()?;
    tokio::fs::create_dir_all(&schema_dir)
        .await
        .map_err(|e| format!("스키마 디렉토리 생성 실패: {}", e))?;

    let path = schema_dir.join(&filename);
    tokio::fs::write(&path, json.as_bytes())
        .await
        .map_err(|e| format!("스키마 저장 실패: {}", e))?;

    Ok(path.to_string_lossy().to_string())
}

/// 사용자 스키마 삭제.
#[tauri::command]
pub async fn delete_user_schema(filename: String) -> Result<(), String> {
    let schema_dir = get_user_schema_dir()?;
    let path = schema_dir.join(&filename);
    if path.exists() {
        tokio::fs::remove_file(&path)
            .await
            .map_err(|e| format!("스키마 삭제 실패: {}", e))?;
    }
    Ok(())
}

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct SchemaFileEntry {
    pub path: String,
    pub filename: String,
    pub content: String,
}

// ─────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────

fn get_user_schema_dir() -> Result<std::path::PathBuf, String> {
    let home = std::env::var("HOME").map_err(|_| "HOME 환경변수를 찾을 수 없습니다")?;
    Ok(std::path::PathBuf::from(home).join(".its-schemas"))
}
