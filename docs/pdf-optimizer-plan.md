# PDF 경량화 모듈 구현 계획

## Context

교과서 PDF 파일은 고해상도 이미지로 인해 용량이 매우 크다. 이미지 해상도를 낮춰 PDF를 경량화하는 별도 Java 모듈(콘솔 앱 겸용)을 구현한다. 기존 `hwpxlib` 프로젝트와 독립적으로 빌드/실행 가능하며, Tauri 데스크탑 앱에서도 호출한다.

## 모듈 구조

기존 `hwpxlib`는 Maven Central에 퍼블리시되는 독립 모듈이므로, multi-module 전환 없이 **`pdf-optimizer/` 디렉토리를 프로젝트 루트에 추가**한다.

```
indesign-to-something/
  pom.xml                    # 기존 hwpxlib (변경 없음)
  src/                       # 기존 hwpxlib 소스 (변경 없음)
  pdf-optimizer/             # ★ 신규 — PDF 경량화 모듈
    pom.xml
    src/main/java/kr/dogfoot/pdfoptimizer/
      PdfOptimizerCLI.java
      model/
      analyzer/
      optimizer/
  desktop/                   # Tauri 앱 (수정)
    src/components/PdfOptimizerPage.tsx   # ★ 신규
    src/stores/usePdfOptimizerStore.ts    # ★ 신규
    src-tauri/src/commands.rs              # 수정
    src-tauri/src/lib.rs                   # 수정
```

---

## Phase 1 — Java 모듈: 분석 기능

### 1-1. `pdf-optimizer/pom.xml`

```
groupId: kr.dogfoot
artifactId: pdf-optimizer
version: 1.0.0
Java: 8
```

의존성:
- `org.apache.pdfbox:pdfbox:2.0.31` (Java 8 호환, Apache License)
- `com.google.code.gson:gson:2.10.1` (기존과 동일)

maven-shade-plugin으로 `pdf-optimizer-1.0.0-cli.jar` fat JAR 생성.
mainClass: `kr.dogfoot.pdfoptimizer.PdfOptimizerCLI`

### 1-2. 패키지 구조

```
kr.dogfoot.pdfoptimizer/
  PdfOptimizerCLI.java          # CLI 진입점 (--analyze, --optimize)
  model/
    ImageSizeFilter.java         # enum: HALF, THIRD, QUARTER, ALL
    OptimizeOptions.java         # 설정 컨테이너
    PageImageInfo.java           # 이미지별 분석 결과
    PageAnalysis.java            # 페이지별 분석 결과
    PdfAnalysis.java             # 전체 문서 분석 결과
    OptimizeResult.java          # 최적화 결과 요약
  analyzer/
    PdfImageAnalyzer.java        # PDFStreamEngine 기반 이미지 추출/측정
  optimizer/
    PdfImageOptimizer.java       # 최적화 오케스트레이션
    ImageResampler.java          # 이미지 리샘플링 (Bicubic → JPEG)
  progress/
    ProgressReporter.java        # 인터페이스
    JsonProgressReporter.java    # JSON-lines 스트리밍 (기존 패턴 복제)
```

### 1-3. `PdfOptimizerCLI` — CLI 인터페이스

```bash
# 분석: 페이지별 이미지 목록 JSON 출력
java -jar pdf-optimizer-1.0.0-cli.jar --analyze <pdf-path>

# 최적화: 경량화된 PDF 출력
java -jar pdf-optimizer-1.0.0-cli.jar --optimize <input> <output> \
    --target-size <half|third|quarter|all> \
    --dpi <96|120|200> \
    [--quality <0.0-1.0>] \
    [--progress]
```

### 1-4. `PdfImageAnalyzer` — 핵심 분석 로직

PDFBox `PDFStreamEngine` 서브클래스로 각 페이지의 `Do` 오퍼레이터를 인터셉트하여:
- `PDImageXObject`에서 픽셀 크기(`getWidth()`, `getHeight()`) 추출
- CTM(Current Transformation Matrix)에서 렌더링 크기(pt) 계산
- DPI = `pixelWidth / (renderedWidthPt / 72)`
- 이미지 데이터 스트림 크기(bytes) 측정
- 페이지 면적 대비 이미지 면적 비율 계산

### 1-5. `--analyze` JSON 출력 형식

```json
{
  "filePath": "/path/to/doc.pdf",
  "fileSizeBytes": 15728640,
  "fileSizeDisplay": "15.0 MB",
  "totalPages": 24,
  "totalImages": 47,
  "totalImageBytes": 14200000,
  "pages": [
    {
      "pageNumber": 1,
      "widthPt": 595.28,
      "heightPt": 841.89,
      "widthMm": 210.0,
      "heightMm": 297.0,
      "images": [
        {
          "index": 0,
          "name": "Im0",
          "pixelWidth": 3508,
          "pixelHeight": 2480,
          "renderedWidthPt": 595.28,
          "renderedHeightPt": 420.94,
          "dpiX": 423.3,
          "dpiY": 423.8,
          "estimatedBytes": 1245678,
          "estimatedSizeDisplay": "1.2 MB",
          "format": "jpg",
          "pageAreaRatio": 0.50
        }
      ]
    }
  ]
}
```

---

## Phase 2 — Java 모듈: 최적화 기능

### 2-1. `ImageSizeFilter` — 대상 이미지 필터

```java
public enum ImageSizeFilter {
    HALF(0.5f),      // 페이지의 1/2 이상
    THIRD(1f / 3f),  // 페이지의 1/3 이상
    QUARTER(0.25f),  // 페이지의 1/4 이상
    ALL(0.0f);       // 모두

    boolean matches(float pageAreaRatio) { return pageAreaRatio >= threshold; }
}
```

### 2-2. `PdfImageOptimizer` — 최적화 알고리즘

페이지별로:
1. `PdfImageAnalyzer`로 이미지 목록 + 렌더링 크기 분석
2. 각 이미지에 대해:
   - `ImageSizeFilter`로 대상 여부 판단
   - 현재 DPI ≤ 목표 DPI면 스킵 (업스케일 방지)
   - CCITT/JBIG2 (1-bit 흑백)이면 스킵
   - 새 픽셀 크기 = `renderedSizePt / 72 * targetDpi`
   - `ImageResampler`로 리샘플링 → JPEG 인코딩
   - `PDResources.put(name, newImage)`로 교체
3. `doc.save(outputPath)`

### 2-3. `ImageResampler` — 리샘플링

```java
BufferedImage original = image.getImage();
BufferedImage scaled = new BufferedImage(targetW, targetH, TYPE_INT_RGB);
Graphics2D g = scaled.createGraphics();
g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC);
g.drawImage(original, 0, 0, targetW, targetH, null);
return JPEGFactory.createFromImage(doc, scaled, quality);
```

투명 이미지(PNG alpha)는 흰색 배경으로 플래튼 후 JPEG 변환.

### 2-4. `--optimize --progress` 스트리밍 출력

```json
{"type": "progress", "current": 1, "total": 24, "message": "페이지 1/24 처리 중..."}
{"type": "progress", "current": 2, "total": 24, "message": "페이지 2/24 처리 중..."}
...
{"type": "complete", "result": {"totalPages": 24, "totalImagesFound": 47, "imagesOptimized": 35, "imagesSkipped": 12, "originalSizeBytes": 15728640, "optimizedSizeBytes": 4718592, "savedBytes": 11010048, "reductionPercent": 70.0}}
```

---

## Phase 3 — Tauri 통합 (Rust)

### 3-1. `lib.rs` — 메뉴 항목 추가

View 메뉴에 "PDF 경량화" 항목 추가:
```rust
let pdf_optimize = MenuItem::with_id(handle, "pdf-optimize", "PDF 경량화", true, None::<&str>)?;
```

view_menu items에 separator + `&pdf_optimize` 추가.

`on_menu_event`에 `"pdf-optimize"` 핸들러 추가 → `window.emit("menu-pdf-optimize", ())`

`invoke_handler`에 3개 커맨드 등록:
- `commands::get_pdf_optimizer_jar_path`
- `commands::analyze_pdf`
- `commands::optimize_pdf`

### 3-2. `commands.rs` — 3개 커맨드 추가

**`get_pdf_optimizer_jar_path`**: `get_jar_path`와 동일 패턴, JAR 이름만 `pdf-optimizer-1.0.0-cli.jar`로 변경.

**`analyze_pdf(path, jar_path) → PdfAnalysis`**: Java 프로세스 실행 → stdout JSON 파싱.

**`optimize_pdf(app, input_path, output_path, options, jar_path) → PdfOptimizeResult`**: `convert_idml`과 동일한 스트리밍 패턴 — stderr 로그 이벤트, stdout progress JSON 파싱, Tauri 이벤트 emit.

Rust 타입 정의:
```rust
struct PdfAnalysis { file_path, file_size_bytes, total_pages, total_images, pages: Vec<PdfPageAnalysis> }
struct PdfPageAnalysis { page_number, width_pt, height_pt, images: Vec<PdfImageInfo> }
struct PdfImageInfo { index, name, pixel_width, pixel_height, dpi_x, dpi_y, estimated_bytes, format, page_area_ratio }
struct PdfOptimizeOptions { target_size: String, dpi: i32, quality: f64 }
struct PdfOptimizeResult { total_pages, images_optimized, images_skipped, original_size_bytes, optimized_size_bytes, reduction_percent }
```

---

## Phase 4 — React UI

### 4-1. `App.tsx` — 탭 추가

```typescript
type Tab = "playground" | "extract" | "converter" | "pdf-optimizer";

tabs에 추가: { key: "pdf-optimizer", label: "PDF 경량화" }

listen("menu-pdf-optimize", () => setCurrentTab("pdf-optimizer"))

{currentTab === "pdf-optimizer" && <PdfOptimizerPage />}
```

### 4-2. `usePdfOptimizerStore.ts` — Zustand 스토어

상태:
- `pdfPath`, `analysis`, `isAnalyzing` — 파일/분석
- `targetSize` (half|third|quarter|all), `targetDpi` (96|120|200) — 설정
- `isOptimizing`, `progress`, `result`, `error` — 최적화

액션:
- `selectPdf()` — Tauri dialog → `analyze_pdf` 호출
- `setTargetSize()`, `setTargetDpi()` — 설정 변경
- `startOptimize()` — 출력 경로 dialog → `optimize_pdf` 호출

### 4-3. `PdfOptimizerPage.tsx` — UI 레이아웃

```
┌─────────────────────────────────────────────────┐
│  document.pdf  15.0MB                 [PDF 열기] │
├─────────────────────────────────────────────────┤
│  24페이지 │ 47 이미지 │ 추정 이미지크기 13.5 MB  │
├─────────────────────────────────────────────────┤
│                                                 │
│  페이지 1                                        │
│    Im0  3508×2480  JPEG  423 DPI  1.2MB  (50%)  │
│    Im1  1200×800   PNG   150 DPI  0.4MB  (25%)  │
│  페이지 2                                        │
│    Im2  2400×1600  JPEG  300 DPI  0.8MB  (40%)  │
│    ...                                          │
│                                                 │
├─────────────────────────────────────────────────┤
│  대상: [페이지의 1/3 이상 ▾]  해상도: [200 ▾]    │
│                                [경량화 변환]      │
│  결과: 35개 최적화, 70% 절감 (15MB → 4.5MB)      │
└─────────────────────────────────────────────────┘
```

Tailwind CSS 기반, 기존 `ConversionPanel`/`ExtractPage` 스타일 참조.

### 4-4. `types/index.ts` — TypeScript 타입 추가

PdfAnalysis, PdfPageAnalysis, PdfImageInfo, PdfOptimizeOptions, PdfOptimizeResult 인터페이스 추가.

---

## 수정/생성 파일 목록

| 구분 | 파일 | 작업 |
|------|------|------|
| Java 신규 | `pdf-optimizer/pom.xml` | Maven 프로젝트 설정 |
| Java 신규 | `pdf-optimizer/src/.../PdfOptimizerCLI.java` | CLI 진입점 |
| Java 신규 | `pdf-optimizer/src/.../model/*.java` (6개) | 데이터 모델 |
| Java 신규 | `pdf-optimizer/src/.../analyzer/PdfImageAnalyzer.java` | PDF 분석 |
| Java 신규 | `pdf-optimizer/src/.../optimizer/PdfImageOptimizer.java` | 최적화 오케스트레이션 |
| Java 신규 | `pdf-optimizer/src/.../optimizer/ImageResampler.java` | 이미지 리샘플링 |
| Java 신규 | `pdf-optimizer/src/.../progress/*.java` (2개) | 진행률 보고 |
| Rust 수정 | `desktop/src-tauri/src/lib.rs` | 메뉴 + 커맨드 등록 |
| Rust 수정 | `desktop/src-tauri/src/commands.rs` | 3개 커맨드 추가 |
| React 신규 | `desktop/src/components/PdfOptimizerPage.tsx` | UI 페이지 |
| React 신규 | `desktop/src/stores/usePdfOptimizerStore.ts` | Zustand 스토어 |
| React 수정 | `desktop/src/App.tsx` | 탭 + 메뉴 리스너 추가 |
| React 수정 | `desktop/src/types/index.ts` | TypeScript 타입 추가 |
| 설정 수정 | `.gitignore` | `pdf-optimizer/target/` 추가 |

---

## 검증

1. **Java 빌드**: `cd pdf-optimizer && mvn clean package -DskipTests`
2. **분석 테스트**: `java -jar target/pdf-optimizer-1.0.0-cli.jar --analyze ~/test.pdf`
3. **최적화 테스트**: `java -jar target/pdf-optimizer-1.0.0-cli.jar --optimize ~/test.pdf /tmp/optimized.pdf --target-size all --dpi 200 --progress`
4. **결과 확인**: 출력 PDF 용량 감소 확인, 페이지 렌더링 정상 확인
5. **데스크탑 앱**: `cd desktop && npm run tauri dev` → "PDF 경량화" 탭 동작 확인

## 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| CCITT/JBIG2 (1-bit 흑백) | 스킵 (JPEG 변환 시 오히려 용량 증가) |
| 현재 DPI ≤ 목표 DPI | 스킵 (업스케일 방지) |
| 투명 PNG | 흰색 배경 플래튼 후 JPEG |
| 암호화된 PDF | 에러 메시지 반환 |
| Form XObject 내 이미지 | 재귀 탐색으로 처리 |
| 인라인 이미지 (content stream 내장) | 스킵 + 경고 |
| 대용량 PDF (100MB+) | `PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())` |
