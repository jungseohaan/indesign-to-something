# InDesign → HWPX 변환기 — 아키텍처

> **현재 구현(브랜치 `open-indd`, v1.0.9)을 기준으로 한 문서.**
> 향후 비전(다중 포맷 출력 등)은 §13에 분리 기록.

---

## 1. 개요

Adobe InDesign 문서(`.indd` / `.idml`)를 한글(`.hwpx`)로 변환한다.

| 항목 | 내용 |
|------|------|
| 출력 포맷 | HWPX 단일 |
| Java | 8 (lambda OK, var 불가) |
| 빌드 | Maven, fat JAR `target/idml-to-something-1.0.9-cli.jar` |
| 데스크탑 앱 | Tauri 2.0 (Rust) + React 18 + Zustand + Tailwind |
| InDesign 추출 | ExtendScript (`.jsx`) — UXP 아님 |
| HWPX 라이브러리 | `kr.dogfoot.hwpxlib` (이 저장소 내 동거) |

핵심 설계는 **AST-First** — `ASTDocument`가 단일 진실 공급원이고, 모든 입력 처리(IDML 파싱, resolved.json 보강, ExtendScript 렌더 통합)는 AST를 채우기 위해 수렴하며, HWPX 생성은 AST를 소비한다.

---

## 2. 시스템 구조

```
┌─ Desktop App (Tauri + React) ─────────────────────────┐
│                                                        │
│  React Webview (desktop/src/)                         │
│  ├─ stores/useAppStore.ts     앱 상태(파일/변환/INDD) │
│  ├─ stores/useAstStore.ts     AST 뷰어                │
│  └─ components/                                        │
│         │ Tauri invoke                                 │
│         ▼                                              │
│  Rust Bridge (desktop/src-tauri/src/)                  │
│  ├─ commands/             Tauri 커맨드 핸들러         │
│  ├─ indesign.rs           InDesign 제어 (osascript)   │
│  └─ extract_cache.rs      추출 캐시                   │
│         │ subprocess (stdout JSON lines)               │
│         ▼                                              │
└────────┬───────────────────────────────────────────────┘
         │
┌────────▼───────────────────────────────────────────────┐
│  Java CLI (target/idml-to-something-1.0.9-cli.jar)     │
│  └─ kr.dogfoot.hwpxlib.tool.idmlconverter.ConverterCLI │
│         │                                               │
│  ┌──────┴───────────────────────────────────────────┐ │
│  │  IDMLToHwpxConverter (파사드)                    │ │
│  │   ├─ ResolvedToASTBuilder  ← 새 파이프라인       │ │
│  │   │     (Phase 0~6 + Stage 3)                    │ │
│  │   ├─ IDMLNormalizer        ← 레거시 파이프라인   │ │
│  │   │     (Stage 1~3)                              │ │
│  │   └─ ASTToHwpxConverter   AST → HWPX            │ │
│  └──────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 데이터 흐름

```
.indd ──[ExtendScript]──┬─→ output.idml         (구조)
                        ├─→ resolved.json       (계산된 속성 + 좌표 + 렌더 PNG 메타)
                        ├─→ pageBackgrounds/    (페이지 배경 PNG)
                        ├─→ renderedFloating/   (인라인 객체 PNG)
                        └─→ Links/              (원본 이미지)
                            │
                            ▼
                   IDMLToHwpxConverter.convert()
                            │
                            ▼
                       ASTDocument
                            │
                            ▼
                          .hwpx
```

---

## 3. 입력 추출 — ExtendScript

### 3.1 위치

```
scripts/
├── extract_indd.jsx        # 메인 추출기: IDML + resolved.json + 렌더 PNG
├── export_pdf_bg.jsx       # 페이지 배경을 PDF→PNG로 내보내기
├── analyze_eh_fonts.py     # EH 수식 폰트 분석 (개발 도구)
└── measure_indesign_fonts.py
```

데스크탑 앱은 Rust(`indesign.rs`)에서 osascript로 `extract_indd.jsx`를 실행한다.

### 3.2 산출물 구조

```
indd-extract-{timestamp}/
├── output.idml             # InDesign IDML export
├── resolved.json           # 추출된 모든 속성 + 좌표 + 메타
├── pageBackgrounds/        # 페이지별 배경 PNG (벡터/이미지 합성)
├── renderedFloating/       # 인라인/플로팅 그래픽 PNG
└── (Links 폴더는 원본 위치)
```

### 3.3 resolved.json 주요 항목

**문서 단위:**
- `paragraphStyles[]` — 스타일 정의 (정렬, 들여쓰기 등)
- `characterStyles[]`
- `colorSwatches[]`
- `fontMetrics[]` — 폰트별 advance width / ascent / descent
- `viewPreferences` — 측정 단위(mm/in/pt)

**페이지 단위:**
- `pages[]` — 각 페이지의 `pageItems[]` (geometricBounds, contentType, etc.)
- `pageBackgroundPng` — 페이지 배경 PNG 경로
- `renderedFloatingItems[]` — ExtendScript가 PNG로 렌더한 인라인/플로팅 객체 목록

**Story 단위:**
- `stories[]` — 단락/런 텍스트 + 적용된 스타일 + 좌표

**중요:** `renderedFloatingItems`의 존재 여부가 **새 파이프라인 vs 레거시** 분기 조건이다. ([§5](#5-변환-파이프라인))

### 3.4 좌표 단위 정규화

InDesign DOM은 문서 측정 단위(mm/in/pt)로 좌표를 반환한다. IDML은 항상 pt다. 두 좌표를 합치려면 정규화가 필요하다.

`ResolvedData.normalizeToPoints(idmlPageWidthPt)` — IDML 페이지 폭과 resolved 페이지 폭을 비교해 `scaleFactor`(mm→pt 등)를 자동 계산. 이 팩터는 `ResolvedToASTBuilder` 생성자에 주입되어 모든 좌표 변환에 사용된다.

> ExtendScript에서 `viewPreferences.horizontalMeasurementUnits = MeasurementUnits.POINTS` 변경은 효과 없음. 정규화는 Java에서 수행.

---

## 4. AST 모델

`src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/`

### 4.1 노드 계층

```
ASTDocument                    루트
├── ASTPageLayout              페이지 크기/마진
├── ASTFontDef[]               폰트 정의
├── ASTStyleDef[]              스타일 정의 (paragraph + character)
├── ASTSection[]               페이지 단위 섹션
│   ├── ASTPageBackground      배경 PNG
│   └── blocks: ASTBlock[]     본문 블록 (z-order 정렬)
│       ├── ASTTextFrameBlock  글상자
│       │   └── ASTParagraph[] 단락
│       │       ├── ASTTextRun[]      텍스트 런
│       │       ├── ASTInlineObject[] 인라인 객체 (이미지/도형)
│       │       ├── ASTInlineItem[]   특수 항목 (탭/공백/심볼)
│       │       ├── ASTBreak          개행
│       │       └── ASTEquation       수식
│       ├── ASTTable           표
│       │   └── ASTTableRow[]
│       │       └── ASTTableCell[]    (단락 재귀 포함)
│       └── ASTFigure          이미지 (플로팅)
└── idmlZOrders               IDML selfId → z-order 맵
```

### 4.2 직렬화

| 클래스 | 역할 |
|--------|------|
| `ASTSerializer` | AST → JSON |
| `ASTDeserializer` | JSON → AST |
| `ASTBundleWriter` / `ASTBundleReader` | AST + 리소스(이미지)를 ZIP 번들로 |
| `DebugMeta` | 각 블록에 `createdAt` (어느 phase에서 생성됐는지) — `--debug-ast` 플래그 |

### 4.3 좌표 단위

AST에 저장되는 모든 좌표는 **HWPUNIT** (1pt = 100 hwpunit, 1inch = 7200 hwpunit). resolved의 mm/pt는 빌드 시점에 모두 변환된다.

```
resolved (mm) ─[scaleFactor]─→ pt ─[CoordinateConverter.pointsToHwpunits()]─→ HWPUNIT
```

---

## 5. 변환 파이프라인

`IDMLToHwpxConverter.convert()`(파사드)는 두 파이프라인을 분기한다:

```java
// IDMLToHwpxConverter.java:115
if (resolvedData != null && !resolvedData.allRenderedFloatingItems().isEmpty()) {
    // 새 파이프라인 (IDML-Free)
    astDoc = new ResolvedToASTBuilder(resolvedData, idmlDoc.tempDir(), pngExportDpi)
                .debugAst(...).tableQualityGate(...).build();
} else {
    // 레거시 파이프라인
    astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName, resolvedData, reporter);
    // + ResolvedMerger / ResolvedFrameDistributor / ResolvedOverlayEnricher 후처리
}
```

분기 조건: ExtendScript가 `renderedFloatingItems`를 생성했는가. 데스크탑 앱이 만든 추출은 항상 새 파이프라인을 탄다.

### 5.1 새 파이프라인 — `ResolvedToASTBuilder` (Phase 0~6 + Stage 3)

`src/main/java/.../normalizer/ResolvedToASTBuilder.java` — 슬림한 오케스트레이터(430줄). 각 phase는 별도 클래스에 위임.

```
                     resolved.json + IDML 보조
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 0  InfraSetup                          │
       │           IDML 폰트/스타일/색상 정의 복사     │
       │           + StylePropertyResolver 초기화      │
       │           + resolved alignment 보강           │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 1  PageLayoutBuilder                   │
       │           페이지/섹션 빌드                    │
       │           pageDocOffset → section index 매핑  │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 2  FramePlacer                         │
       │           TextFrame 분류 (editable/decoration)│
       │           Y-gap split, nested 검사            │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 3  StoryConverter                      │
       │           Story → ASTParagraph → ASTTextRun   │
       │           텍스트 기반 resolved run 매칭        │
       │           + EH/BT/NP 수식 폰트 변환           │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 4  TableBuilder                        │
       │           IDML Story XML에서 테이블 파싱      │
       │           ASTTable + 셀 단락 재귀             │
       │           품질 게이트(SPEC-017)               │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 4.5  BulletInserter                    │
       │           불릿 스타일 자동 삽입               │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Phase 5  WrapPhase5                          │
       │           textwrap 글상자 분할                │
       │           (composedLine wrapIndent 기반)      │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Stage 2.5  시각 ownership refine             │
       │            (ObjectPlan 권위 확정)             │
       └───────────────────────────────────────────────┘
                                │
                                ▼
       ┌───────────────────────────────────────────────┐
       │  Stage 3  VisualBuilder                       │
       │           모든 시각 배치 (배경/플로팅/배지)   │
       │           내부: BackgroundInjector.inject     │
       │           + stage3/Visual* 헬퍼               │
       │           (구 Phase 6 + Phase 7 통합, SPEC-035)│
       └───────────────────────────────────────────────┘
                                │
                                ▼
                         ASTDocument
```

#### Phase 클래스 위치

| Phase | 클래스 | LOC | 설명 |
|-------|--------|-----|------|
| 0 | `normalizer/resolved/phase0/InfraSetup.java` | 211 | IDML 정의 복사 + 스타일 정렬 보강 |
| 1 | `normalizer/resolved/phase1/PageLayoutBuilder.java` | 66 | 페이지/섹션 |
| 2 | `normalizer/resolved/phase2/FramePlacer.java` | 679 | TextFrame 분류/배치 |
| 3 | `normalizer/resolved/phase3/StoryConverter.java` | 383 | 메인 오케스트레이터 (W3로 분해, 구 2695) |
| 3a | `normalizer/resolved/phase3/StoryLoader.java` | 465 | IDML Story XML 로딩 + 단락 변환 |
| 3b | `normalizer/resolved/phase3/RunBuilder.java` | 715 | 런 빌드 + 매칭 + 스타일 헬퍼 |
| 3c | `normalizer/resolved/phase3/InlineFrameHandler.java` | 681 | 인라인 객체 + 체인 + 외부 위치 검사 |
| 3d | `normalizer/resolved/phase3/ParagraphDistributor.java` | 257 | 단락 분배 (연결 글상자 체인) |
| 3e | `normalizer/resolved/phase3/MathProcessor.java` | 227 | BT/EH/NP 수식 변환 |
| 3f | `normalizer/resolved/phase3/RunPostProcessor.java` | 241 | overline/italic 후처리 |
| 4 | `normalizer/resolved/phase4/TableBuilder.java` | 525 | 테이블 |
| 4.5 | `normalizer/resolved/phase4_5/BulletInserter.java` | 99 | 불릿 |
| 5 | `normalizer/resolved/phase5/WrapPhase5.java` | 391 | textwrap 분할 |
| Stage 3 | `normalizer/resolved/stage3/VisualBuilder.java` | 30 | 시각 배치 진입점 (BackgroundInjector.inject 브리지) |
| Stage 3 | `normalizer/resolved/phase6/BackgroundInjector.java` | 994 | 시각 배치 실행 본체 (SPEC-036: dead 779 삭제 + 실행 653 Stage 3 이동, 구 3182) |
| Stage 3 | `normalizer/resolved/stage3/VisualTextEmphasisAbsorber.java` | 544 | ABSORB_TEXT_STYLE 실행 (SPEC-036 분리) |
| Stage 3 | `normalizer/resolved/stage3/VisualTfInlineCompositor.java` | 171 | TF inline 자식 PNG 합성 (SPEC-036 분리) |
| Stage 3 | `normalizer/resolved/stage3/Visual*.java` (14개) | — | 배치 plan/실행/z-순서/크롭/오버플로우 헬퍼 |
| — | `normalizer/resolved/shared/ParagraphTextHelpers.java` | — | phase 공유 헬퍼 |
| — | `normalizer/resolved/ResolvedBuildContext.java` | — | phase 간 공유 컨텍스트 |

#### 좌표 흐름

```
resolved.json (page-relative, mm)
   │
   ├─ scaleFactor 적용 → points (page-relative)
   │
   ├─ CoordinateConverter.pointsToHwpunits() → HWPUNIT
   │
   └─ AST 저장 (최종)
```

AST에는 **HWPUNIT만** 저장. mm/pt는 build() 내부에서만 사용.

#### IDML 보조 사용처

새 파이프라인은 "IDML-Free"가 목표지만, 다음 경우 IDML이 보조로 필요:

- **테이블 셀 인라인 객체**: resolved에 셀 내부 인라인 객체 정보가 부족 → IDML Story XML에서 `<Group>`/`<Rectangle>` 추출 (Phase 4)
- **빈칸 RuleBelow 감지**: resolved에 RuleBelow 정보 없음 → IDML ParagraphStyle 정의 조회 (SPEC-024)
- **수식 폰트 매핑**: BT/EH/NP 폰트 글리프 맵 (Phase 3)

이 경로는 `ctx.ensureIdmlInfra.run()` 호출로 lazy 초기화. `ctx.idmlDocumentSupplier.get()` 호출 전에 반드시 `ensureIdmlInfra` 먼저 호출 (idempotent).

#### 성능

- 변환 속도: 84초 → **4.5초** (Java 래스터화 제거 효과)
- ExtendScript가 PNG를 미리 만들기 때문에 Java는 합성/리사이즈만 수행

### 5.2 레거시 파이프라인 — `IDMLNormalizer` (Stage 1~3)

resolved.json에 `renderedFloatingItems`가 없을 때 (예: CLI에서 IDML만 단독 변환).

```
IDML
  │
  ├─ Stage 1  Stage1_Flatten             컨테이너 평탄화
  │              Group/Frame/Rectangle 계층 제거 → FlattenedObjectPool
  │
  ├─ Stage 2  Stage2_InlineDetect         인라인 감지
  │              ParentStory + AnchoredObjectSetting으로 인라인/플로팅 분류
  │
  └─ Stage 3  Stage3_BuildAST             AST 구축 (★)
                 스토리 우선 빌드 + resolved 좌표 결합
                 → ASTDocument
```

> 레거시 클래스는 모두 [normalizer/legacy/](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/legacy/) 패키지에 격리. (W2-1 작업으로 `Stage4_BuildAST` → `Stage3_BuildAST` rename, 옛 4단계 명명 청산.)

#### 후처리 (레거시 전용)

`IDMLNormalizer.normalize()` 후, `IDMLToHwpxConverter`가 다음을 순차 호출:

| Phase | 클래스 | 역할 |
|-------|--------|------|
| 2.5 | `ResolvedMerger` | resolved 텍스트/스타일 보강 |
| 2.5 | `ResolvedFrameDistributor` | 프레임별 문단 재배치 (텍스트 기반 매핑) |
| 2.6 | `ResolvedOverlayEnricher` | 오버레이 좌표 보강 |
| 2.7 | `FloatingImageMerger` | 플로팅 이미지 → 인라인 머지 (textWrap) |
| 2.8 | `replaceInlineRenderedTextFrames` | 인라인 앵커 텍스트 프레임 → 렌더 이미지 |
| 2.9 | `replaceFloatingRenderedTextFrames` | 플로팅 렌더 텍스트 프레임 → 이미지 |
| 2.10 | `injectOrphanRenderedGraphics` | orphan 렌더 그래픽 주입 |

새 파이프라인은 이 모든 단계를 Phase 0~6 + Stage 3 안에 통합했다.

---

## 6. HWPX 출력 — `ASTToHwpxConverter`

`src/main/java/.../converter/ASTToHwpxConverter.java`

### 6.1 변환 전략

```
1. BlankFileMaker로 기본 HWPX 골격 생성
2. FontRegistry/StyleRegistry로 폰트·스타일 등록
3. 섹션별 SecPr (페이지 레이아웃)
4. 블록별 변환 (z-order 정렬):
     ASTTextFrameBlock → Rectangle + DrawText
     ASTTable          → Table + Tr + Tc
     ASTFigure         → Picture
5. 페이지 배경 PNG → Picture (z-order 최하위)
```

### 6.2 Builder 패턴

| Builder | 역할 | 진입점 | LOC |
|---------|------|--------|----:|
| `HwpxParagraphBuilder` | 단락/런 → Para/Run (메인) | (필드 주입) | 327 |
| ↳ `LineSpacingResolver` | 단락 높이 + 줄간격 자동 보정 | (W4-2 Step A) | 177 |
| ↳ `ParaPrFactory` | ParaPr 생성 + override | (W4-2 Step B) | 259 |
| ↳ `CharPrFactory` | TextRun/CharPr + 공백 분리 + 폰트 스타일 | (W4-2 Step C) | 346 |
| ↳ `InlineItemDispatcher` | 인라인 객체 dispatch + Break + Equation | (W4-2 Step D) | 242 |
| `HwpxTextBoxBuilder` | 글상자 변환 (메인) | `convertTextFrameBlock` | 984 |
| ↳ `TextBoxLayoutHelpers` | 단락/열 분배 정적 헬퍼 | (W4 Step A) | 196 |
| ↳ `PageOverlayBuilder` | 페이지 오버레이 1×1 테이블 | (W4 Step B) | 202 |
| ↳ `InlineFrameBuilder` | 인라인 텍스트프레임 | (W4 Step C) | 276 |
| ↳ `SingleColumnTableConverter` | 단일 컬럼 1×1 테이블 | (W4 Step D) | 240 |
| ↳ `FrameTransformations` | 회전/라운드 변형 분기 | (W4 Step E) | 284 |
| `HwpxTableBuilder` | 표 변환 | `convertTable(framePara, astTable)` | — |
| `HwpxImageBuilder` | 이미지/배경 변환 | `convertFigure(...)`, `addBackgroundImage(...)` | — |

상호 의존: `ASTToHwpxConverter`가 `ParagraphBuilder`를 만들고, 나머지 3개 Builder에 주입한다.
`HwpxParagraphBuilder`와 `HwpxTextBoxBuilder`는 W4 작업으로 각각 4개/5개 sub-module로 분해됨.

### 6.3 지원 모듈

| 모듈 | 위치 | 역할 |
|------|------|------|
| `HwpxConverterContext` | `converter/` | 변환 중 공유 상태 (옵션, ID 카운터, 폰트맵) |
| `CoordinateConverter` | `converter/` | pt ↔ HWPUNIT |
| `FontMapper` | `converter/` | 3계층 폰트 매핑 (config + font-mapping.json + IDML 메트릭) |
| `FontMetricsAnalyzer` | `converter/` | 폰트 advance width 비교 |
| `HwpxEnumMapper` | `converter/` | IDML enum → HWPX enum |
| `HwpxUtil` | `converter/` | ID 생성기 (`nextParaId()`, `nextShapeId()`) |
| `ASTImageLoader` | `converter/` | 이미지 리소스 로드 |
| `IDMLPageRenderer` | `converter/` | (레거시) 페이지 래스터화 |
| `PdfPageRenderer` | `converter/` | (옵션) PDF 페이지 렌더링 |
| `SvgGenerator` | `converter/` | (옵션) SVG 생성 |
| `TextFrameGridMerger` | `converter/` | Grid TextFrame → ASTTable |
| `FontRegistry` / `StyleRegistry` / `CharPrBuilder` | `converter/registry/` | HWPX 정의 등록 |

### 6.4 폰트 매퍼

3계층 우선순위:

1. **`ConversionConfig.fontMappings`** — 사용자 명시 매핑
2. **`font-mapping.json`** — 외부 매핑 파일 (자동 발견: cwd, jar 인근)
3. **IDML `fontMetrics` + 매핑 폰트 메트릭 비교** — advance width 유사성 기반 자동 매핑 (SPEC-014)

매칭 실패 시 fallback: SUBSTITUTE → IMAGE_RENDER → DROP.

---

## 7. 데스크탑 앱

### 7.1 디렉토리 구조

```
desktop/
├── src/                        React Webview
│   ├── App.tsx
│   ├── stores/
│   │   ├── useAppStore.ts      앱 상태 (파일/변환/INDD 추출)
│   │   └── useAstStore.ts      AST 뷰어
│   ├── components/
│   ├── types/
│   └── utils/
└── src-tauri/src/              Rust Bridge
    ├── main.rs
    ├── lib.rs                  Tauri 앱 설정
    ├── commands/               Tauri 커맨드 핸들러
    ├── indesign.rs             InDesign 제어 (osascript)
    └── extract_cache.rs        추출 캐시
```

### 7.2 통신 방식

```
React  ──[Tauri invoke]──→  Rust  ──[subprocess]──→  Java CLI
                              │
                              ├─ stdout: JSON lines (진행률)
                              ├─ stderr: 로그/경고
                              └─ exit code: 0(성공)/1(실패)

Rust  ──[event emit]──→  React  (진행률 → UI 업데이트)
```

### 7.3 InDesign 제어 (macOS)

```rust
// indesign.rs
// 1. open -g -a "Adobe InDesign 2024"  (백그라운드)
// 2. osascript로 ExtendScript 전달:
//    tell application "Adobe InDesign 2024"
//        do script POSIX file "/path/to/extract_indd.jsx" language javascript
//    end tell
```

Headless InDesign Server가 아닌 **데스크탑 인스턴스 백그라운드 실행**. UI가 잠깐 뜰 수 있지만 안정성 트레이드오프.

### 7.4 Java 탐색 우선순위

1. 번들 JRE (`resources/jre/bin/java`)
2. `JAVA_HOME`
3. `PATH`의 `java`
4. macOS Homebrew: `/opt/homebrew/opt/openjdk/bin/java`

---

## 8. CLI 인터페이스

### 8.1 명령어

```bash
# 기본 변환 (resolved.json 권장)
java -jar target/idml-to-something-1.0.9-cli.jar \
     --convert input.idml output.hwpx \
     --resolved resolved.json \
     --links-directory /path/to/Links

# 페이지 범위 지정
java -jar ... --convert input.idml output.hwpx --pages 5-10

# 폰트 매핑 명시
java -jar ... --convert ... --font-map font-mapping.json

# 디버그 (각 블록에 createdAt 메타 부여)
java -jar ... --convert ... --debug-ast

# AST 추출 (HWPX 변환 없이)
java -jar ... --to-json input.idml output.ast.json
```

### 8.2 진행률 프로토콜

stdout으로 JSON lines 출력:

```json
{"type":"progress","current":5,"total":100,"message":"AST 빌드 중..."}
{"type":"progress","current":50,"total":100,"message":"페이지 12/24 변환 중"}
{"type":"warning","message":"폰트 'Foo' 매핑 없음"}
{"type":"complete","outputPath":"output.hwpx","warnings":3}
```

`JsonProgressReporter`가 직렬화. Tauri Rust가 라인 단위로 파싱해 React로 forward.

---

## 9. 특수문자 매핑

### 9.1 공백 문자

| Unicode | 설명 | AST | HWPX |
|---------|------|-----|------|
| U+00A0 | 줄바꿈없는 공백 | `space:nonBreaking` | `<hp:nbSpace/>` |
| U+2002 | 반각 공백 (EN) | `space:en` | `<hp:enSpace/>` |
| U+2003 | 전각 공백 (EM) | `space:em` | `<hp:emSpace/>` |
| U+2005 | 1/4 공백 | `space:fourPerEm` | `<hp:qSpace/>` |
| U+2006 | 1/6 공백 | `space:sixPerEm` | `<hp:sixthSpace/>` |
| U+2009 | 가는 공백 | `space:thin` | `<hp:thinSpace/>` |
| U+200A | 머리카락 공백 | `space:hair` | `<hp:hairSpace/>` |

### 9.2 제어/서식 문자

| Unicode | 설명 | AST | HWPX |
|---------|------|-----|------|
| U+0009 | 탭 | `tab` | `<hp:tab/>` |
| U+2028 | 줄구분자 | `lineBreak` | `<hp:lineBreak/>` |
| U+200C | 제로폭비결합 | `symbol:zwnj` | (제거) |
| U+2011 | 줄바꿈없는 하이픈 | `symbol:nbHyphen` | `<hp:nbHyphen/>` |
| U+00AD | 소프트 하이픈 | `symbol:softHyphen` | `<hp:softHyphen/>` |

### 9.3 구두점/기호

| Unicode | 설명 | AST | HWPX |
|---------|------|-----|------|
| U+2018 / U+2019 | 작은따옴표 | text `'` | `'` |
| U+201C / U+201D | 큰따옴표 | text `"` | `"` |
| U+2022 | 불릿 | `symbol:bullet` | `•` |
| U+2026 | 말줄임표 | text `…` | `…` |
| U+2032 | 프라임 | `symbol:prime` | `′` |

---

## 10. 좌표 시스템

### 10.1 단위

| 단위 | 환산 |
|------|------|
| HWPUNIT | 1 pt = **100** hwpunit, 1 inch = **7200** hwpunit |
| pt | 1 inch = 72 pt |
| mm | 1 inch ≈ 25.4 mm |

### 10.2 변환 경로

```
resolved (mm or pt, document-relative or page-relative)
        │
        ├── ResolvedData.normalizeToPoints(idmlPageWidthPt)
        │   → scaleFactor 계산 (예: mm 입력이면 ≈ 2.835)
        │
        ├── 좌표 × scaleFactor → points (page-relative)
        │
        └── CoordinateConverter.pointsToHwpunits()
            → HWPUNIT (long)
```

### 10.3 ID 형식

| 형식 | 예시 | 변환 |
|------|------|------|
| IDML self ID | `u1735` | hex string with `u` prefix |
| InDesign DOM ID | `5941` | decimal integer |
| 변환 | `parseInt("1735", 16) = 5941` | `Integer.toHexString(5941) = "1735"` |

---

## 11. 알려진 제약 & 트레이드오프

### 11.1 한글 연결 글상자
한글은 연결 글상자 체인의 후속 프레임에 명시적 콘텐츠가 있어도 무시한다. `ResolvedFrameDistributor`가 분배한 프레임은 `linkListIDRef=0`으로 링크를 해제한다.

### 11.2 문단 인덱스 불일치
IDML(AST)의 단락 수와 InDesign DOM(resolved)의 단락 수가 다를 수 있다 (수식, 인라인 객체 처리 차이). 인덱스 매칭 대신 **텍스트 기반 매핑**을 사용 (`ResolvedFrameDistributor.buildIndexMapping`, `StoryConverter`의 텍스트 매처).

### 11.3 Group bounds 과대
InDesign DOM의 Group `geometricBounds`는 비가시 자식까지 포함한다. **벡터 그룹은 resolved 대신 IDML 폴백 사용**. 개별 도형만 resolved 경로 사용.

### 11.4 lazy IDMLDocument 초기화
`ctx.idmlDocumentSupplier.get()`는 `ctx.ensureIdmlInfra.run()` **이후에만** 호출 가능 (idempotent). 누락 시 IDMLDocument=null이라 ParagraphStyle/CharacterStyle 정의 조회 실패. (SPEC-024 회귀 사례)

### 11.5 ExtendScript JSON 제어 문자
ExtendScript는 JSON 출력 시 제어 문자를 이스케이프하지 못한다. Gson **lenient 모드** 필수 (`ResolvedDataReader`).

### 11.6 매핑 폰트 워드 간격 과대
매핑된 폰트의 space glyph가 원본 InDesign 폰트보다 16~39% 넓은 경우가 있어 글상자 폭이 어긋난다. 자동 보정 미구현 — `docs/specs/word-spacing-issue.md` 참고.

---

## 12. SPEC 기반 개발

### 12.1 워크플로우

1. **SPEC 작성** → `docs/specs/SPEC-NNN-feature-name.md`
   - 문제 정의, 영향 범위, 해결 방안, 수정 파일 목록
2. **리뷰** → SPEC 검토 후 구현 시작
3. **구현** → SPEC의 수정 파일 목록 순서대로
4. **검증** → `mvn clean package -q -DskipTests` 빌드, CLI 테스트
5. **결과** → SPEC에 완료 상태 기록 + 한글 커밋 메시지

### 12.2 SPEC 템플릿

`docs/specs/TEMPLATE.md` 참고:

```markdown
# [기능명]

## 문제
## 목표
## 해결 방안
## 수정 파일
## 검증
- [ ] 빌드 성공
- [ ] 테스트 케이스
```

### 12.3 최근 진행 SPEC (요약)

| SPEC | 주제 |
|------|------|
| SPEC-013 | ResolvedToASTBuilder 모듈화 (phase0~7 분리) |
| SPEC-014 | 폰트 자동 매핑 (advance width 비교) |
| SPEC-015 | AST 디버그 가시성 (`createdAt` 메타) |
| SPEC-016 | resolved 선택적 오버라이드 + 매칭 신뢰도 카운트 |
| SPEC-017 | 테이블 셀 품질 게이트 v2 |
| SPEC-018 | Semantic 추출 (M3 진행 중, `--extract-semantics`) |
| SPEC-021/022 | 중첩 뱃지 추출 + 외곽선 데코 병합 |
| SPEC-023 | 외곽선 텍스트 배지 라운드 배경 병합 |
| SPEC-024 | 빈칸 RuleBelow → underscore 변환 |

전체 목록: [docs/specs/](specs/)

---

## 13. 향후 계획 (참고)

> 이 섹션은 **현재 미구현**된 비전. 구현 시 §1~12로 이동.

### 13.1 멀티 포맷 출력
HWPX 외에 HTML/PPTX/EPUB 출력. AST를 단일 IR로 두고 Exporter 플러그인 구조로 확장. Canonical AST 단계에서 시맨틱 역할(`semanticRole`)을 부여하면 포맷 중립적 변환 가능.

### 13.2 Semantic Layer (SPEC-018, M3 진행 중)
`semantic/` 패키지에 SemanticExtractor 골격 존재. 문서 구조(문제집의 Problem, 교과서의 Activity 등)를 인식해 도메인 블록으로 변환. CLI `--extract-semantics` 옵션.

### 13.3 Structure Recognizer
문서 유형 자동 감지 (문제집 vs 교과서) + 유형별 시맨틱 매핑.

### 13.4 Decoration Classifier
배경 도형/장식 자동 분류 → HWPX 변환 시 텍스트 흐름과 분리.

### 13.5 입력 포맷 확장
PDF 입력 (PdfImageExtractor 골격 존재), Word(.docx) 입력 등.

---

## 참고 자료

- [docs/ast-schema.md](ast-schema.md) — AST JSON 스키마 상세
- [docs/resolved-json-spec.md](resolved-json-spec.md) — resolved.json 스펙
- [docs/intermediate-schema.json](intermediate-schema.json) — JSON Schema
- [docs/4종콘텐츠비교.md](4종콘텐츠비교.md) — 분석 대상 4종 비교
- [docs/semantic-format.md](semantic-format.md) — Semantic Layer 포맷 (SPEC-018)
- [docs/pdf-export-preferences.md](pdf-export-preferences.md) — PDF 내보내기 설정
- [docs/specs/](specs/) — SPEC 디렉토리
- [docs/archive/](archive/) — 구버전 문서

---

**문서 갱신:** 이 문서는 현재 구현(브랜치 `open-indd`)을 반영. 구조 변경 시 함께 갱신할 것.
