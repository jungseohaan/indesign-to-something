# Desktop App 아키텍처 명세

## 개요

Tauri 2.0 기반 데스크탑 앱. React 18 + Zustand + Tailwind CSS 프론트엔드, Rust 브릿지, Java CLI 백엔드, InDesign ExtendScript 연동.

### 탭 구조

| 탭 | 컴포넌트 | 용도 |
|----|----------|------|
| HWPX 내보내기 | `FileSelector` + `ASTTreePanel` + `ASTDetailPanel` + `ConversionPanel` | IDML/INDD → HWPX 변환, AST 분석 |
| Playground | `PlaygroundPage` | 마스터 스프레드 기반 템플릿 생성/병합 |
| 문제 추출하기 | `ExtractPage` | IDML 스프레드에서 문항 추출 |

---

## 프론트엔드 컴포넌트

### 핵심 컴포넌트 (`desktop/src/components/`)

| 파일 | 용도 |
|------|------|
| `FileSelector.tsx` | INDD/IDML 파일 선택, 드롭다운 버튼 (파일/폴더), 문서 통계 표시 |
| `ASTTreePanel.tsx` | AST 트리 뷰어 (접기/펼치기, 검색, 필터) |
| `ASTDetailPanel.tsx` | 선택 노드 상세 정보 (속성, 스타일, resolved 데이터) |
| `ConversionPanel.tsx` | 변환 옵션 (스프레드, DPI, 레이아웃, 페이지, 폰트 매핑) + 변환 실행 |
| `PdfPreviewPanel.tsx` | InDesign 추출 PDF 미리보기 |
| `InddBatchModal.tsx` | 배치 처리 모달 (파일 선택 → 진행률 → 결과) |
| `PageRangeModal.tsx` | 페이지 범위 선택 모달 (IDML 직접 열기 시) |
| `FontMappingModal.tsx` | 폰트 매핑 편집 모달 |
| `PlaygroundPage.tsx` | 템플릿 생성/병합 페이지 |
| `ExtractPage.tsx` | 문항 추출 페이지 |

### 상태 관리 (`desktop/src/stores/`)

#### useAppStore — 메인 앱 상태

**파일/분석 상태:**
- `jarPath` — Java JAR 경로
- `idmlPath`, `inddPath`, `sourceType` — 열린 파일 정보
- `structure: IDMLStructure` — 문서 구조 (스프레드, 페이지, 프레임)
- `resolvedJsonPath`, `previewPdfPath` — InDesign 추출 부산물

**선택 상태:**
- `selectedSpread`, `selectedPage`, `selectedImage`, `selectedTextFrame`, `selectedMaster`

**변환 상태:**
- `isConverting`, `progress`, `result`, `error`, `conversionLogs`
- 옵션: `spreadBased`, `vectorDpi`, `layoutMode`, `startPage`, `endPage`, `fontMappings`

**배치 처리 상태:**
- `showBatchModal`, `batchScanResult`, `batchBasePath`
- `batchResults: BatchFileResult[]`, `batchCurrentIndex`, `isBatchProcessing`, `batchCancelled`

**기억 경로:**
- `lastOpenDir`, `lastExportDir` — localStorage 영속화

**주요 액션:**

| 액션 | 설명 |
|------|------|
| `initJarPath()` | JAR + InDesign 경로 초기화 |
| `selectFile()` | IDML 파일 선택 → 분석 → AST 로드 |
| `selectInddFile()` | INDD 파일 선택 → 배치 모달로 자동 처리 |
| `selectInddFolder()` | 폴더 선택 → 스캔 → 배치 모달 |
| `startBatch(paths)` | 출력 폴더 선택 → 순차 추출+변환+열기 |
| `startConversion()` | 수동 HWPX 변환 (AST 디버깅용) |

#### useAstStore — AST 뷰어 상태

- `astDoc` — 파싱된 AST 트리
- `resolvedData` — resolved.json 원본
- `resolvedStoryMap`, `resolvedFontMap`, `resolvedColorMap`, `resolvedPStyleMap`, `resolvedCStyleMap`, `resolvedFrameMap` — 룩업 맵
- `loadAST()`, `loadResolved()`, `clearResolved()` — 데이터 로드

#### usePlaygroundStore — 템플릿 생성 상태

- `schema` — 추출된 템플릿 스키마
- `pages` — 페이지 스펙 배열
- `items` — 병합 데이터 아이템
- `createIdml()`, `mergeIdml()`, `exportIdml()` — 템플릿 작업

#### useExtractStore — 문항 추출 상태

- `selectedSpreads` — 추출 대상 스프레드
- `extractionResult` — 추출된 문항 목록
- `extract()`, `exportJson()` — 추출/내보내기

---

## Rust 백엔드 (`desktop/src-tauri/src/`)

### 커맨드 목록

#### 파일 분석 (`commands/mod.rs`)

| 커맨드 | 인자 | 반환 | 설명 |
|--------|------|------|------|
| `get_jar_path` | — | `String` | JAR 파일 경로 탐색 |
| `check_indesign` | — | `String` | InDesign 설치 확인 |
| `analyze_idml` | `path, jarPath` | `IDMLStructure` | IDML 구조 분석 |
| `scan_indd_folder` | `path` | `InddFolderScanResult` | 폴더 1depth .indd 스캔 |
| `open_file` | `path` | — | 시스템 기본 앱으로 열기 |
| `copy_file` | `src, dst` | — | 파일 복사 |
| `create_dir` | `path` | — | 디렉토리 생성 (mkdir -p) |
| `read_text_file` | `path` | `String` | 텍스트 파일 읽기 |
| `write_text_file` | `path, content` | — | 텍스트 파일 쓰기 |
| `read_resolved_json` | `path` | `serde_json::Value` | resolved.json 로드 |
| `read_file_base64` | `path` | `String` | 바이너리 파일 base64 인코딩 |

#### INDD 추출 (`commands/extract.rs`)

| 커맨드 | 인자 | 반환 | 설명 |
|--------|------|------|------|
| `get_indd_pages` | `inddPath` | `InddPagesResult` | 페이지 목록 (경량) |
| `extract_indd` | `inddPath, jarPath, startPage, endPage` | `InddExtractResult` | IDML + resolved + PDF 추출 |
| `export_ast` | `idmlPath, jarPath` | `ASTDocument` | AST JSON 내보내기 |
| `extract_questions` | `idmlPath, spreads` | `ExtractionResult` | 문항 추출 |

#### 변환 (`commands/conversion.rs`)

| 커맨드 | 인자 | 반환 | 설명 |
|--------|------|------|------|
| `convert_idml` | `inputPath, outputPath, options, jarPath` | `ConvertResult` | HWPX 변환 (진행률 스트리밍) |

**ConvertOptions:**
```typescript
{
  spread_based: boolean,
  vector_dpi: number,       // 96 | 150
  include_images: boolean,
  links_directory: string | null,
  resolved_json_path: string | null,
  start_page: number | null,
  end_page: number | null,
  layout_mode: "preserve" | "editable",
  font_map: Record<string, string> | null,
}
```

#### 미리보기 (`commands/preview.rs`)

| 커맨드 | 인자 | 반환 | 설명 |
|--------|------|------|------|
| `generate_preview` | `idmlPath, linkPath, jarPath` | `ImagePreview` | 링크 이미지 미리보기 |
| `generate_image_preview` | `idmlPath, frameId, jarPath` | `ImagePreview` | 이미지 프레임 미리보기 |
| `generate_vector_preview` | `idmlPath, frameId, jarPath` | `ImagePreview` | 벡터 프레임 미리보기 |
| `generate_master_preview` | `idmlPath, masterId, jarPath` | `ImagePreview` | 마스터 스프레드 미리보기 |

#### Playground (`commands/extract.rs`)

| 커맨드 | 인자 | 반환 | 설명 |
|--------|------|------|------|
| `extract_template_schema` | `idmlPath, jarPath` | `TemplateSchema` | 템플릿 스키마 추출 |
| `create_idml_from_masters` | `sourcePath, outputPath, masterIds, pageSpecs, ...` | `CreateIdmlResult` | 마스터 기반 IDML 생성 |
| `merge_idml` | `sourcePath, dataJson, outputPath, jarPath` | `CreateIdmlResult` | 데이터 병합 |

### InDesign 연동 (`indesign.rs`)

- `find_indesign_app()` — InDesign 2022~2026 탐색
- `run_extraction()` — osascript → ExtendScript 실행, `.progress` 파일 폴링
- `run_get_pages()` — 페이지 정보만 경량 추출
- 진행률: `indd-extraction-progress` 이벤트 → 프론트엔드

### 메뉴 구조 (`lib.rs`)

| 메뉴 | 항목 | 단축키 |
|------|------|--------|
| File | Open IDML... | `Cmd+O` |
| | Open InDesign... | `Cmd+Shift+I` |
| | Open InDesign Folder... | `Cmd+Shift+F` |
| | Open HWPX... | `Cmd+Shift+O` |
| View | Playground | `Cmd+P` |
| | 문제 추출하기 | `Cmd+E` |

---

## ExtendScript (`scripts/`)

### extract_indd.jsx

InDesign DOM에서 IDML + resolved.json + preview PDF 추출.

**입력:** `[inddPath, outputDir, startPage, endPage]`

**추출 단계:**
1. `open` — 문서 열기
2. `idml` — IDML 내보내기
3. `resolved_styles` — 단락/문자 스타일, 색상, 폰트 수집
4. `resolved_stories` — 스토리별 문단/런 텍스트 수집
5. `resolved_frames` — 텍스트 프레임 메타데이터
6. `resolved_items` — 페이지 아이템 좌표
7. `pdf` — 미리보기 PDF 생성

**출력:**
- `output.idml` — IDML 파일
- `resolved.json` — InDesign DOM 계산값
- `preview.pdf` — 레이아웃 미리보기

### get_indd_pages.jsx

페이지 목록만 빠르게 추출 (문서는 열어둔 채 유지).

**출력:** `pages.json` — `{ pages: [{ name, index }] }`

---

## 데이터 흐름

### INDD 배치 변환 (주요 흐름)

```
FileSelector "INDD 열기" 클릭
  → selectInddFile() / selectInddFolder()
  → [파일: 단일 파일 배치 모달] / [폴더: scan_indd_folder → 배치 모달]
  → InddBatchModal: 파일 선택 체크박스
  → "변환 시작" 클릭
  → startBatch(selectedPaths)
    → 출력 폴더 선택 다이얼로그 (lastExportDir 기억)
    → for each inddPath:
      ├─ extract_indd() → IDML + resolved.json + preview.pdf
      ├─ convert_idml() → .hwpx 생성
      ├─ copy_file() → .pdf 복사
      ├─ open_file() → 한컴 한글 + PDF 뷰어 자동 열기
      └─ 상태: pending → extracting → converting → done/error
  → 완료 (성공 N개, 실패 M개)
```

### IDML 직접 분석 (디버깅 흐름)

```
File 메뉴 "Open IDML"
  → selectFile()
  → analyze_idml() → IDMLStructure
  → loadAST() → ASTDocument (트리 뷰어)
  → ConversionPanel → startConversion() → .hwpx
```

### 폴더 구조 보존

```
입력:  패키지/1단원/book.indd
       패키지/2단원/book.indd
출력:  내보내기폴더/1단원/book.hwpx + book.pdf
       내보내기폴더/2단원/book.hwpx + book.pdf
```

---

## 타입 정의 (`desktop/src/types/index.ts`)

### 문서 구조

```typescript
IDMLStructure → SpreadInfo[] → PageInfo[] → FrameInfo[]
                                            └─ StoryContentInfo → ParagraphSummaryItem[]
```

### INDD 추출

```typescript
InddExtractResult    // idml_path, resolved_json_path, preview_pdf_path, temp_dir
InddFolderScanResult // direct_files[], subfolder_files[]
BatchFileResult      // path, filename, status, error?
```

### Resolved 데이터

```typescript
ResolvedData
  ├─ paragraphStyles: ResolvedParagraphStyle[]
  ├─ characterStyles: ResolvedCharacterStyle[]
  ├─ colors: ResolvedColor[]
  ├─ fonts: ResolvedFont[]
  ├─ stories: ResolvedStory[] → ResolvedStoryParagraph[] → ResolvedRun[]
  ├─ textFrames: ResolvedTextFrame[]
  └─ documentInfo: { pageWidth, pageHeight, facingPages, ... }
```

### 변환

```typescript
ConvertOptions  // spread_based, vector_dpi, layout_mode, font_map, ...
ConvertResult   // pages_converted, frames_converted, images_converted, warnings[]
```

---

## 영속화

| 항목 | 저장 위치 | 키 |
|------|----------|-----|
| 마지막 열기 폴더 | localStorage | `lastOpenDir` |
| 마지막 내보내기 폴더 | localStorage | `lastExportDir` |

## 상태: 현행
