# CLAUDE.md — indesign-to-something

> **새 에이전트/계정이라면**: 먼저 [ONBOARDING.md](ONBOARDING.md)를 10분 안에 훑어 진입점을 잡고, 이 문서로 돌아오세요.

## 프로젝트 개요

IDML(Adobe InDesign) → HWPX(한글) 변환기. Java 백엔드 + Tauri(Rust) 데스크탑 앱 + React 프론트엔드.

## 활성 작업 (2026-07-19 기준)

- **브랜치**: `open-indd` — EH 재작성(PR #35)·SPEC-041(PR #36)·SPEC-042(chemical-eq, PR #41) 머지됨. main 은 PR #40 시점까지 반영
- **진행 중 SPEC**:
  - [SPEC-041](docs/specs/SPEC-041-anchored-edge-label-floating.md) (앵커 가장자리 라벨 셀 흡수) — **구현 완료, 한글 육안 확인 대기** (p187 pill 위치)
  - source ownership policy — Tier A.5/A.6/B 구현 완료, **미테스트** (InDesign 재추출 필요). 잔여: A.1.5/A.4/A.8/Tier B 효과
  - [SPEC-027](docs/specs/SPEC-027-badge-scribble-outline-png.md) (배지 scribble 외곽선 PNG 폴백) — 신규(2026-05-20). 일러스트 톤 배지 외곽선만 PNG, 텍스트는 HWPX. **데이터 조사 단계**
- **기타 Active SPEC**: SPEC-012 (속성 우선순위), SPEC-014 (폰트 자동 매핑), SPEC-015 (AST 디버깅), SPEC-018 (시멘틱 M3)
- **전체 SPEC 인덱스**: [docs/specs/INDEX.md](docs/specs/INDEX.md)

## 빌드 & 실행

```bash
# Java (Maven) — JAR 빌드
mvn clean package -q -DskipTests
# 결과: target/idml-to-something-1.0.9-cli.jar (fat JAR)

# CLI 실행 (macOS)
/opt/homebrew/opt/openjdk/bin/java -jar target/idml-to-something-1.0.9-cli.jar --convert input.idml output.hwpx

# Desktop 앱 빌드
cd desktop && npm run tauri build

# Desktop 개발 모드
cd desktop && npm run tauri dev
```

## 아키텍처

### 변환 파이프라인 (이중 분기)

`IDMLToHwpxConverter.convert()`가 `resolvedData.allRenderedFloatingItems()` 유무로 분기. 데스크탑 앱이 만든 추출은 항상 새 파이프라인.

```
.indd → [ExtendScript: extract_indd.jsx]
        ├─ output.idml
        ├─ resolved.json
        ├─ pageBackgrounds/
        └─ renderedFloating/
               ↓
         IDMLLoader + ResolvedDataReader
               ↓
   resolvedData.normalizeToPoints()  ← 단위 정규화 (mm/pt)
               ↓
         ┌─────┴─────┐
         │ 분기      │
         └─────┬─────┘
               │
     renderedFloatingItems 있음 ──── YES ───┐
               │ NO                         │
               ▼                            ▼
   ┌─ 레거시 파이프라인 ─┐    ┌─ 신 파이프라인 (IDML-Free) ─┐
   │ legacy/             │    │ ResolvedToASTBuilder        │
   │  IDMLNormalizer    │    │  Phase 0 InfraSetup         │
   │  Stage1_Flatten    │    │  Phase 1 PageLayoutBuilder  │
   │  Stage2_InlineDetect│   │  Phase 2 FramePlacer        │
   │  Stage3_BuildAST   │    │  Phase 3 StoryConverter     │
   │  ResolvedMerger    │    │  Phase 4 TableBuilder       │
   │  FrameDistributor  │    │  Phase 4.5 BulletInserter   │
   │  OverlayEnricher   │    │  Phase 5 WrapPhase5         │
   │  FloatingImage…    │    │  Stage 2.5 VisualRefine     │
   │  …등                │    │  Stage 3 VisualBuilder      │
   └────────┬───────────┘    └────────┬─────────────────────┘
            └──────┬─────────────────┘
                   ▼
              ASTDocument
                   ▼
           ASTToHwpxConverter
            ├─ HwpxParagraphBuilder
            ├─ HwpxTextBoxBuilder
            ├─ HwpxTableBuilder
            └─ HwpxImageBuilder
                   ▼
                .hwpx 출력
```

> 레거시는 **3단계**: Stage1 → Stage2 → Stage3_BuildAST. (구 `Stage4_BuildAST`는 W2-1에서 `Stage3_BuildAST`로 rename + `legacy/`로 이동.)

### 핵심 모듈

| 모듈 | 위치 | 역할 |
|------|------|------|
| IDML 파서 | `idml/` | IDML ZIP 로딩, XML 파싱 |
| 신 파이프라인 | `normalizer/ResolvedToASTBuilder.java` + `normalizer/resolved/phase0~6/` + `stage3/` | resolved.json 우선 빌드 (Phase 0~6 텍스트/레이아웃 + Stage 3 시각 배치) |
| 레거시 파이프라인 | `normalizer/legacy/IDMLNormalizer.java` + `Stage1/2/3_*.java` | IDML 우선 3단계 빌드 + 후처리 7단계 |
| AST | `ast/` | 중간 표현 (ASTDocument, ASTSection, ASTBlock...) |
| Resolved | `resolved/` | resolved.json 데이터 모델 + 레거시 후처리 |
| 변환기 | `converter/` | AST → HWPX 변환, Builder 패턴 |
| 수식 변환 | `equationconverter/` | BT/EH/NP 폰트 글리프 → HWP 수식 |
| 시멘틱 (SPEC-018) | `semantic/` | AST → 시멘틱 노드/관계 추출 (M3 진행 중) |
| CLI | `ConverterCLI.java` | 명령행 인터페이스 |

### Desktop 앱 구조

| 레이어 | 기술 | 위치 |
|--------|------|------|
| Frontend | React 18 + Zustand + Tailwind | `desktop/src/` |
| Bridge | Tauri 2.0 (Rust) | `desktop/src-tauri/src/` |
| Backend | Java CLI (subprocess) | `src/main/java/...` |
| ExtendScript | InDesign DOM 추출 | `scripts/extract_indd.jsx` |

## 주요 파일 경로

```
src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/
├── ConverterCLI.java              # CLI 엔트리포인트
├── IDMLToHwpxConverter.java       # 변환 파사드 (파이프라인 분기)
├── ConvertOptions.java            # 호출별 옵션
├── ConversionConfig.java          # 글로벌 설정 (conversion-config.json)
├── ast/
│   ├── ASTDocument.java           # AST 루트
│   ├── ASTSection.java            # 페이지 섹션
│   ├── ASTTextFrameBlock.java     # 글상자
│   ├── ASTParagraph.java          # 단락
│   ├── ASTTextRun.java            # 텍스트 런
│   ├── ASTInlineObject.java       # 인라인 객체 (이미지/도형)
│   ├── ASTTable.java              # 표
│   ├── ASTFigure.java             # 플로팅 이미지
│   ├── ASTEquation.java           # 수식
│   ├── ASTPageBackground.java     # 페이지 배경 PNG
│   ├── ASTSerializer.java         # AST → JSON
│   └── ASTDeserializer.java       # JSON → AST
├── normalizer/
│   ├── ResolvedToASTBuilder.java  # ★ 신 파이프라인 진입점 (오케스트레이터)
│   ├── StylePropertyResolver.java # 스타일 BasedOn 체인 resolve
│   ├── RunPropertyResolver.java   # 런 속성 우선순위 (SPEC-012)
│   ├── ASTRunConverter.java       # 런 변환 헬퍼 (신/구 공유)
│   ├── ASTMathGrouper.java        # 수식 폰트 그룹핑 (신/구 공유)
│   ├── ASTTableConverter.java     # 표 변환 헬퍼 (신/구 공유)
│   ├── ASTPageProcessor.java      # 페이지 프로세싱 헬퍼 (신/구 공유)
│   ├── MatchConfidence.java       # 매칭 신뢰도 enum (SPEC-016)
│   ├── legacy/                    # 레거시 3단계 파이프라인
│   │   ├── IDMLNormalizer.java    # 레거시 진입점
│   │   ├── Stage1_Flatten.java    # 객체 평탄화
│   │   ├── Stage2_InlineDetect.java # 인라인 감지
│   │   ├── Stage3_BuildAST.java   # AST 빌드 (구 Stage4_BuildAST)
│   │   ├── ASTMetadataBuilder.java # 폰트/스타일/색상 메타
│   │   └── FloatingImageMerger.java # 플로팅 이미지 후처리
│   └── resolved/                  # 신 파이프라인 phase 디렉토리
│       ├── ResolvedBuildContext.java       # phase 간 공유 컨텍스트
│       ├── phase0/InfraSetup.java          # IDML 정의 복사 + 스타일 보강
│       ├── phase1/PageLayoutBuilder.java   # 페이지/섹션 빌드
│       ├── phase2/FramePlacer.java         # TextFrame 분류/배치
│       ├── phase3/                       # Phase 3: Story 변환 (W3로 6개 모듈 분리)
│       │   ├── StoryConverter.java       # ★ 메인 오케스트레이터 (386 LOC, 구 2695)
│       │   ├── StoryLoader.java          # IDML Story XML 로딩 + 단락 변환
│       │   ├── RunBuilder.java           # 런 빌드 + 매칭 + 스타일 헬퍼
│       │   ├── InlineFrameHandler.java   # 인라인 객체/체인/외부 위치 검사
│       │   ├── ParagraphDistributor.java # 단락 분배 (연결 글상자 체인)
│       │   ├── MathProcessor.java        # BT/EH/NP 수식 변환
│       │   └── RunPostProcessor.java     # overline/italic 후처리
│       ├── phase4/TableBuilder.java        # 테이블 변환
│       ├── phase4_5/BulletInserter.java    # 불릿 자동 삽입
│       ├── phase5/WrapPhase5.java          # textwrap 글상자 분할
│       ├── phase6/BackgroundInjector.java  # 시각 배치 실행 본체(994 LOC, 구 3182). inject 오케스트레이터+crop/page-intersection. VisualBuilder가 호출
│       ├── stage3/                       # Stage 3 시각 배치 (구 phase6+phase7 통합, codex 리팩터)
│       │   ├── VisualBuilder.java        # ★ 시각 배치 진입점 (현재 BackgroundInjector.inject 브리지)
│       │   ├── VisualPlacementPlan(Builder/Executor).java # 배치 plan/실행
│       │   ├── VisualZOrderPlanner.java / VisualOverlapZOrderPlanner.java # z-순서 (zOrder 결정 권위, BI dead 복사본 삭제됨)
│       │   ├── VisualCropper.java / VisualOverflowPlacer.java # 크롭/오버플로우
│       │   ├── VisualTextEmphasisAbsorber.java # ABSORB_TEXT_STYLE 실행 (source ownership policy, 구 BI tryAbsorbTextEmphasisBackdrop)
│       │   ├── VisualTfInlineCompositor.java   # TF inline 자식 PNG 합성 (source ownership policy, 구 BI compositeTfInlineVisuals)
│       │   └── VisualLayeringRules.java / VisualSyntheticLinePlacer.java / VisualPngHeader.java 등
│       └── shared/ParagraphTextHelpers.java # phase 공유 헬퍼
│       # ※ phase7/RenderableFramePlacer 는 제거됨 → 로직이 BackgroundInjector.inject + stage3/Visual* 로 흡수 (source ownership policy)
├── converter/                     # HWPX 출력 (W4로 9개 모듈 분리)
│   ├── ASTToHwpxConverter.java    # HWPX 변환 메인
│   ├── HwpxConverterContext.java  # 변환 공유 상태
│   ├── HwpxParagraphBuilder.java  # 단락 빌더 메인 (327 LOC, 구 1206)
│   │   # ↳ W4-2로 분리: LineSpacingResolver, ParaPrFactory, CharPrFactory, InlineItemDispatcher
│   ├── LineSpacingResolver.java   # 단락 높이 + 줄간격 보정 (W4-2 Step A)
│   ├── ParaPrFactory.java         # ParaPr 생성/override (W4-2 Step B)
│   ├── CharPrFactory.java         # TextRun/CharPr + 공백/폰트 (W4-2 Step C)
│   ├── InlineItemDispatcher.java  # 인라인 객체 + Break + Equation (W4-2 Step D)
│   ├── HwpxTextBoxBuilder.java    # 글상자 빌더 메인 (986 LOC, 구 1980)
│   │   # ↳ W4로 분리: TextBoxLayoutHelpers, PageOverlayBuilder, InlineFrameBuilder, SingleColumnTableConverter, FrameTransformations
│   ├── TextBoxLayoutHelpers.java  # 단락/열 분배 정적 헬퍼 (W4 Step A)
│   ├── PageOverlayBuilder.java    # 페이지 오버레이 1×1 테이블 (W4 Step B)
│   ├── InlineFrameBuilder.java    # 인라인 텍스트프레임 (W4 Step C)
│   ├── SingleColumnTableConverter.java # 단일 컬럼 1×1 테이블 (W4 Step D)
│   ├── FrameTransformations.java  # 회전/라운드 변형 분기 (W4 Step E)
│   ├── HwpxTableBuilder.java      # 표 빌더
│   ├── HwpxImageBuilder.java      # 이미지 빌더
│   ├── FontMapper.java            # 3계층 폰트 매핑
│   ├── CoordinateConverter.java   # pt ↔ HWPUNIT
│   ├── TextFrameGridMerger.java   # Grid TextFrame → ASTTable
│   └── registry/
│       ├── FontRegistry.java
│       └── StyleRegistry.java
├── resolved/
│   ├── ResolvedData.java          # 최상위 컨테이너 + normalizeToPoints()
│   ├── ResolvedDataReader.java    # JSON 파서 (Gson lenient)
│   ├── ResolvedStory.java         # 스토리 모델
│   ├── ResolvedTextFrame.java     # 프레임 모델 (composedLines, nextFrameId)
│   ├── ResolvedMerger.java        # (레거시) AST 보강
│   ├── ResolvedFrameDistributor.java # (레거시) 연결 글상자 재배치
│   └── ResolvedOverlayEnricher.java  # (레거시) 오버레이 좌표 보강
├── equationconverter/idml/        # BT/EH/NP 수식 변환
├── semantic/                      # SPEC-018 시멘틱 추출 (M3 진행 중)
├── font/FontCandidateMatcher.java # SPEC-014 폰트 자동 매핑
└── idml/
    ├── IDMLLoader.java            # IDML ZIP 로더
    ├── IDMLDocument.java          # IDML 문서 모델
    └── IDMLStoryParser.java       # Story XML 파서

scripts/
├── extract_indd.jsx               # ★ ExtendScript 추출기 (메인)
├── export_pdf_bg.jsx              # PDF 배경 내보내기
├── analyze_eh_fonts.py            # EH 폰트 분석 (개발 도구)
└── measure_indesign_fonts.py      # 폰트 메트릭 측정

desktop/src-tauri/src/
├── main.rs / lib.rs               # Tauri 앱 부트
├── commands/                      # Tauri 커맨드 핸들러 (디렉토리)
├── indesign.rs                    # osascript + ExtendScript 호출
└── extract_cache.rs               # SPEC-011 추출 캐시

desktop/src/
├── stores/
│   ├── useAppStore.ts             # 앱 상태 (파일/변환/INDD/배치)
│   ├── useAstStore.ts             # AST 뷰어 상태
│   └── useSemanticStore.ts        # 시멘틱 레이어 상태 (SPEC-018)
├── components/
└── App.tsx

packages/semantic-schemas/schemas/ # SPEC-018 SSOT (Maven 리소스로 포함)
```

## 코딩 컨벤션

- **Java 8** 호환 (람다 OK, var 불가)
- **접근자 패턴**: `field()` getter, `field(value)` setter (JavaBean 스타일 아님)
- **단위**: HWPUNIT (1pt = 100 hwpunit, 1inch = 7200 hwpunit)
- **ID 형식**: IDML `u` + hex (`u1735`), InDesign DOM decimal (`5941`), 변환: `parseInt("1735", 16) = 5941`
- **커밋 메시지**: 한글, 기능 설명 중심 (예: "resolved.json 보강 파이프라인, 다단 N개 글상자 변환")
- **브랜치**: `main` (릴리스), 기능 브랜치에서 개발

## SPEC 기반 개발 워크플로우

### 새 기능/수정 시

1. **SPEC 작성** → `docs/specs/<feature-name>.md`
   - 문제 정의, 영향 범위, 해결 방안, 수정 파일 목록
2. **리뷰** → SPEC 검토 후 구현 시작
3. **구현** → SPEC의 수정 파일 목록 순서대로
4. **검증** → `mvn clean package -q -DskipTests` 빌드, CLI 테스트
5. **결과** → SPEC에 완료 상태 기록

### SPEC 템플릿

```markdown
# [기능명]

## 문제
현재 상황과 문제점

## 목표
기대하는 결과

## 해결 방안
기술적 접근 방법

## 수정 파일
1. `path/to/File.java` — 변경 내용
2. `path/to/File2.java` — 변경 내용

## 검증
- [ ] 빌드 성공
- [ ] 테스트 케이스
```

## 알려진 이슈 & 주의사항

### 데이터 파이프라인
- **ExtendScript JSON**: 제어 문자를 이스케이프하지 못함 → Gson lenient 모드 필수
- **resolved 좌표 단위 불일치**: InDesign DOM은 문서 측정 단위(mm/in/pt)로 반환, IDML은 항상 pt → `ResolvedData.normalizeToPoints(idmlPageWidthPt)`로 scaleFactor 자동 계산. ExtendScript `viewPreferences` 변경은 효과 없음
- **resolved Group bounds 과대**: DOM의 Group `geometricBounds`는 비가시 자식까지 포함 → 벡터 그룹은 IDML 폴백, 개별 도형만 resolved 사용
- **문단 인덱스 불일치**: IDML(AST)과 InDesign DOM(resolved)의 문단 수가 다름 → 텍스트 기반 매핑 사용
- **HWPX 연결 글상자**: 한글은 연결 글상자 체인에서 후속 프레임의 명시적 콘텐츠를 무시 → distributed 프레임은 `linkListIDRef=0`으로 해제

### 신 파이프라인 (Phase 0~6 + Stage 3) 구현 트랩
- **lazy IDMLDocument 초기화 순서**: `ctx.idmlDocumentSupplier.get()` 호출 전 반드시 `ctx.ensureIdmlInfra.run()` 먼저 (idempotent). 누락 시 `IDMLDocument=null` → ParagraphStyle/CharacterStyle 정의 조회 실패 (SPEC-024 회귀 사례)
- **hex/decimal ID 변환**: IDML `u` + hex (`u1735`), InDesign DOM decimal (`5941`), 변환은 `parseInt("1735", 16) = 5941`
- **Phase 3 텍스트 매칭**: `lastMatchResult[0]` 인덱스 캐시로 O(n) 가속. 인라인 수식으로 텍스트 길이 차이 시 next() 재탐색 → O(n²) 위험
- **수식 폰트 한국어 오적용 방지**: BT/EH/NP 폰트 필터가 한국어 텍스트 보호. 단, 단일 라틴 문자는 통과 → 혼합 텍스트 경계 케이스 잔존
- **text-range-shell 이 화학식 첫 글자 오분리**: `OwnershipPlanner.leadingStyledStoryRunRanges` 는 Group(편집TF+빈셸)에서 선두 런을 다음 런과 스타일 경계가 있으면 셸로 뗀다(배지/라벨용). 화학식 H₂O 는 H→2(첨자) 경계 때문에 첫 글자 H 가 58pt rect 로 오분리되고 원자리엔 빈 charPr=0 H 가 남아 레이아웃이 세로로 깨진다 (SPEC-046, p17 파랑 박스). 증상이 "글상자 세로 깨짐"이어도 lineWrap 이 아니라 sourceId=`text_range_shell_*` 인 rect 분리를 의심할 것 → `isChemicalFormulaParagraph` 가드로 화학식 문단 제외. **판정 원천은 추출기**(`source_index.jsx` `_sourceIndexLeadingStyledStoryRunRanges`)에도 있어 Java 가드만으론 배경 PNG 분리(말풍선 배경 누락)를 못 막는다 — 문장 중 화학식 진입(한글→BT/EH/NP 폰트) 경계는 추출기에서 range 생성 자체를 막아야 함 (SPEC-047, 재추출 필요). Java(SPEC-046)와 추출기(SPEC-047) 양쪽에 같은 가드가 필요
- **resolved 첨자 위치 흘림 → 계수 오첨자**: resolved DOM 이 화학식 "2H₂O"의 첨자 위치를 계수·hair space(U+200A)까지 SUBSCRIPT 로 흘리면, splitChemical 로 분리된 계수 2 가 `findResolvedRun`(텍스트 contains 매칭)에서 첨자 2 에 오매칭돼 계수가 아래첨자로 깨진다 (SPEC-045, pg25/26/28). IDML 원본은 Position=None + charStyle 로만 첨자 표기 → `RunBuilder.applyPositionStyle`/`resolvedCharacterStyleRef` 가 "IDML 비첨자 증거(position=null + 비첨자 charStyle)"면 resolved 첨자를 버린다. 진짜 첨자(H₂)는 IDML charStyle=하부자라 무영향
- **CharPr 캐시 키는 스타일 인자 전부 포함**: `CharPrFactory` 계열 캐시 키에 CharPrBuilder.build 가 소비하는 인자(특히 subscript/superscript/fontStyle)가 하나라도 빠지면, 다른 문단이 만든 CharPr 을 물려받아 첨자가 이웃 글자로 전이된다 (SPEC-042 p47 사례: H↔2 첨자 스왑). AST 계측이 침묵인데 HWPX 에 속성이 있으면 CharPr 캐시/공유 층을 의심할 것
- **resolved DOM 의 EH 폰트 과대 보고**: InDesign DOM 이 √ 글리프 뒤 한국어 문장까지 EH상부자 폰트로 보고할 수 있다 (IDML 은 [No character style]). resolved 셀 경로가 폰트만 보고 수식 그룹에 넣으면 lexSubSup 미매핑 스킵으로 한국어가 통째로 유실 → `MathProcessor.splitEHKoreanMixedTextRuns` 가 첫 한국어 문자에서 분리 (p20 사례, 36c2d24c)

### 환경
- **Java 경로**: macOS Homebrew `/opt/homebrew/opt/openjdk/bin/java`
- **데스크탑 추출 경로**: `/var/folders/.../T/indd-extract-{timestamp}/`
- **추출 캐시 (SPEC-011)**: `~/Library/Caches/idml-to-hwpx/extracts/<sha256>/`

## CLI 변환 테스트 가이드

```bash
# 풀 변환 (resolved.json은 input.idml 옆에서 자동 탐지)
java -jar target/idml-to-something-1.0.9-cli.jar \
     --convert input.idml output.hwpx \
     --links-directory /path/to/Links

# 진행률 JSON lines 출력
java -jar ... --convert ... --progress

# 디버그 모드 (각 블록에 createdAt phase 메타)
java -jar ... --convert ... --debug-ast
```

데스크탑 앱 추출 결과로 테스트 시: 최신 `indd-extract-*/` 디렉토리에서 `output.idml` + `resolved.json` + 원본 INDD 옆 `Links/` 사용.

## 변경 시 문서 동기화 규칙

코드 변경이 다음에 해당하면 반드시 함께 갱신:

| 변경 종류 | 갱신 대상 |
|----------|----------|
| Phase 추가/제거/이름 변경 | [README.md](README.md), [docs/architecture.md](docs/architecture.md), 본 문서의 파이프라인 다이어그램 |
| 모듈 LOC가 두 배 이상 변동 또는 sub-module 분리 | 본 문서의 모듈 표 + [docs/architecture.md](docs/architecture.md) Phase 클래스 LOC 표 |
| SPEC 시작 | [docs/specs/INDEX.md](docs/specs/INDEX.md)에 Active로 등록 |
| SPEC 완료 | [docs/specs/INDEX.md](docs/specs/INDEX.md)에서 Done으로 이동, 본 문서 "활성 작업" 섹션 갱신 |
| 새로운 트랩/주의사항 발견 | "알려진 이슈" 섹션 추가 (재발 방지) |
| 브랜치 머지/생성 | "활성 작업" 섹션의 브랜치명 갱신 |

> SPEC 본문이 "구현 완료" 상태로 머무는 동안 문서가 옛 상태로 남으면, 새 에이전트는 그 SPEC을 다시 작업하려고 시도할 수 있다. 종료 시점에 INDEX.md를 갱신하는 게 가장 비용이 낮다.
