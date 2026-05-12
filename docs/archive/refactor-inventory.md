# Refactor Inventory — normalizer/ 루트 클래스 사용처 매트릭스

> **임시 산출물** (W1-3, [improvement-roadmap.md](improvement-roadmap.md)). W2-1 패키지 분리 결정의 근거 자료.
> 작업 종료 시 폐기 또는 archive 가능.
> 작성: 2026-05-02 (브랜치 `open-indd`, ctx 캐싱 PR 직후 상태)

---

## 분류 정의

- **A. SHARED 직접**: 신 파이프라인 (`resolved/phase*/`)이 직접 import
- **B. SHARED 간접**: A 분류 클래스가 import → 신 파이프라인이 간접 의존
- **C. LEGACY-only**: `IDMLNormalizer`/`Stage1/2/4_*` 또는 `IDMLToHwpxConverter`의 `!isNewPipeline` 분기에서만 사용
- **D. DEAD**: 어디에서도 사용 안 됨

---

## 매트릭스

| # | 클래스 | LOC* | 분류 | 핵심 사용처 | 비고 |
|---|--------|:----:|:----:|-------------|------|
| 1 | `ResolvedToASTBuilder` | 422 | (신 진입점) | `IDMLToHwpxConverter`(`isNewPipeline`) | Phase 0~7 오케스트레이터 |
| 2 | `legacy/IDMLNormalizer` | 127 | (구 진입점) | `IDMLToHwpxConverter`(LEGACY 분기), `ConverterCLI` | 3-stage 진입. **W2-1로 `legacy/` 이동 완료** |
| 3 | `StylePropertyResolver` | — | **A** | `ResolvedToASTBuilder`, `phase3/StoryConverter`, `ResolvedBuildContext` | 스타일 BasedOn resolve |
| 4 | `RunPropertyResolver` | — | **A** | `phase3/StoryConverter` (+ `ast/DebugMeta`) | SPEC-012 우선순위 |
| 5 | `ASTRunConverter` | — | **A** | `phase3/StoryConverter` | SHARED but LEGACY 헬퍼 다수 의존 |
| 6 | `ASTMathGrouper` | — | **A** | `phase3/StoryConverter` | SHARED but `ASTPageProcessor` 정적 메서드 사용 |
| 7 | `ASTTableConverter` | — | **A** | `phase3/StoryConverter`, `phase4/TableBuilder` | SHARED |
| 8 | `MatchConfidence` | — | **A** | `phase3/StoryConverter` (+ `RunPropertyResolver`) | enum/value class |
| 9 | `ASTPageProcessor` | — | **B** | `ASTMathGrouper`(A), `ASTRunConverter`(A) — 정적 메서드 (`stripACEPlaceholders`, `shouldDeferInlineFrame`) | LEGACY 본체이지만 정적 헬퍼 일부가 SHARED 경로에서 사용 |
| 10 | `MathPatternDetector` | — | **B** | `ASTMathGrouper`(A), `ASTStoryConverter`(C) | A 경로로 간접 SHARED |
| 11 | `FlatObject` | — | **B** | `ASTPageProcessor`(B), Stage1/2(C), `IDMLNormalizer`(C), `FlattenedObjectPool` | 데이터 구조 — A→B 경로로 간접 SHARED |
| 12 | `FlattenedObjectPool` | — | **B** | `ASTPageProcessor`(B), Stage1/2/4(C), AST*들 | 데이터 구조 — 간접 SHARED |
| 13 | `ASTOverlayBuilder` | — | **B** | `ASTRunConverter`(A), `ASTInlineObjectBuilder`(C) | SHARED 경로의 정적 메서드 |
| 14 | `ASTInlineObjectBuilder` | — | **B** | `ASTRunConverter`(A), 다수 LEGACY | SHARED 경로 의존 |
| 15 | `ASTStoryConverter` | — | **B** | `ASTPageProcessor`(B), 다수 LEGACY | LEGACY이지만 B 체인에 존재 |
| 16 | `ASTFigureBuilder` | — | **C** | `ASTPageProcessor`(B) | B 경로지만 정적 메서드만 사용 — 분류는 보수적으로 C 유지 |
| 17 | `ASTMathFlushHelper` | — | **C** | `ASTStoryConverter`(B) | B의 LEGACY 본체에서만 |
| 18 | `legacy/Stage1_Flatten` | 166 | **C** ✓ 이동 | `legacy/IDMLNormalizer` | W2-1 |
| 19 | `legacy/Stage2_InlineDetect` | 121 | **C** ✓ 이동 | `legacy/IDMLNormalizer` | W2-1 |
| 20 | `legacy/Stage3_BuildAST` | 99 | **C** ✓ 이동+rename | `legacy/IDMLNormalizer` | W2-1 (구 `Stage4_BuildAST`) |
| 21 | `legacy/ASTMetadataBuilder` | — | **C** ✓ 이동 | `legacy/Stage3_BuildAST` | W2-1 |
| 22 | `ASTTextWrapSimulator` | — | **C** | `Stage4_BuildAST`, `ASTPageProcessor`(B) | LEGACY 본체에서만 |
| 23 | `legacy/FloatingImageMerger` | — | **C** ✓ 이동 | `IDMLToHwpxConverter` (`!isNewPipeline` 블록) | W2-1 |
| 24 | `NormalizerContext` | 30 | **D** | (없음) | **DEAD** — 즉시 제거 가능 |

\* LOC는 사용 가능한 것만. 없는 것은 W3 작업 시 측정.

---

## 핵심 발견

### 1. SHARED 클래스가 LEGACY 헬퍼에 의존
`ASTRunConverter`(A)는 `ASTOverlayBuilder`/`ASTInlineObjectBuilder`/`FlattenedObjectPool`/`ASTStoryConverter`/`ASTMathGrouper`를 import. `ASTMathGrouper`(A)는 `ASTPageProcessor`(LEGACY 본체)의 정적 메서드를 호출.

→ **신 파이프라인은 LEGACY 클래스 13개+에 간접 의존**. "SHARED만 분리"는 단순 이동으로 불가.

### 2. 진짜 LEGACY-only (안전하게 `legacy/`로 이동 가능)
- `Stage1_Flatten`, `Stage2_InlineDetect`, `Stage4_BuildAST`
- `IDMLNormalizer` (외부 노출 진입점이지만 LEGACY 분기에서만 호출)
- `ASTMetadataBuilder`
- `FloatingImageMerger`

→ 6개. 패키지 이동 시 `IDMLToHwpxConverter`/`ConverterCLI`/`ResolvedPageItemEnricher`의 import 갱신만 필요.

### 3. 진짜 DEAD code
- `NormalizerContext` — JavaDoc은 "Stage4 정규화 파이프라인의 공유 의존성 번들"이라 적혀있지만 어디에서도 import/생성/호출 없음. 30 LOC.

→ **즉시 제거**.

### 4. B 분류 (경계 클래스)는 W3에서 처리
`ASTPageProcessor`, `MathPatternDetector`, `FlatObject`, `FlattenedObjectPool`, `ASTOverlayBuilder`, `ASTInlineObjectBuilder`, `ASTStoryConverter`는 SHARED 경로에서 정적 메서드 또는 데이터 구조로 사용되지만 본체는 LEGACY 성격. W3 StoryConverter 분해 시 SHARED에서 필요한 정적 헬퍼만 추출하고, LEGACY 본체는 `legacy/`로 격리하는 안.

---

## W2-1 패키지 분리 권고 (수정안)

원래 로드맵의 단순한 `shared/`/`legacy/` 분리는 의존성 그래프 때문에 어렵다. 대안:

**Tier 1 (즉시 가능, W1)** ✓ 완료 (2026-05-02):
- `NormalizerContext` 제거 (D)

**Tier 2 (W2-1, 안전)** ✓ 완료 (2026-05-02): `legacy/` 패키지로 이동
- `Stage1_Flatten`, `Stage2_InlineDetect` → `legacy/`
- **`Stage4_BuildAST` → `legacy/Stage3_BuildAST`** (rename + 이동)
- `IDMLNormalizer` → `legacy/`
- `ASTMetadataBuilder` → `legacy/`
- `FloatingImageMerger` → `legacy/`
- 부수 변경: `ASTPageProcessor`/`ASTTextWrapSimulator`/`ASTStoryConverter` 클래스 + 일부 정적 메서드 `public` 노출 (legacy/에서 normalizer/ 호출 가능하도록)

**Tier 3 (W3 이후)**: B 분류 클래스 정리
- `ASTPageProcessor` 정적 헬퍼(`stripACEPlaceholders`, `shouldDeferInlineFrame` 등)를 `shared/StringHelpers.java` 등으로 추출
- 추출 후 LEGACY 본체를 `legacy/`로 이동

**Tier 4 (W3 본격 분해 후)**:
- `ASTRunConverter`, `ASTTableConverter`, `ASTMathGrouper`, `MathPatternDetector` 같은 SHARED 클래스의 LEGACY 의존을 정리하면서 phase3 sub-module로 흡수

---

## 진행 이력

- **2026-05-02 W1-3**: 매트릭스 작성, `NormalizerContext` (D) 제거
- **2026-05-02 W2-1 Tier 2**: 6개 LEGACY-only 클래스를 `legacy/` 패키지로 이동. `Stage4_BuildAST` → `Stage3_BuildAST` rename. `ASTPageProcessor`/`ASTTextWrapSimulator`/`ASTStoryConverter` visibility를 `public`으로 노출. 빌드/테스트 baseline 유지.

## 보류 작업

- **Tier 3** (W3 이후): B 분류 클래스 정리 — `ASTPageProcessor` 정적 헬퍼 추출
- **Tier 4** (W3 이후): SHARED 클래스의 LEGACY 의존을 phase3 sub-module로 흡수
