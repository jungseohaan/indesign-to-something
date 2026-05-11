# W3 — StoryConverter 분해 계획

> **임시 산출물** (W3-1 책임 식별 + W3-2 분리안). [improvement-roadmap.md](improvement-roadmap.md) Week 3 작업.
> 작업 종료 시 폐기 또는 archive.
> 작성: 2026-05-02

---

## 1. 현재 상태

[src/main/java/.../normalizer/resolved/phase3/StoryConverter.java](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java) — **2695 LOC**, 50+ 메서드, Phase 3 전체를 한 클래스로 처리.

진입점: `convertStories(ResolvedBuildContext ctx, List<ASTSection> sections)` (line 68).

## 2. 책임 그룹 식별 결과

메서드 시그니처와 라인 범위 분석 결과, 다음 **9개 책임 그룹**이 식별됨:

| # | 그룹 | 주요 메서드 | 라인 범위 | 추정 LOC |
|---|------|-------------|-----------|:--------:|
| 1 | **오케스트레이션** | `convertStories`, `convertStoryFromIDML` | 68~620 | ~550 |
| 2 | **Resolved fallback 빌드** | `convertStoryParagraphs` | 1316~1493 | ~180 |
| 3 | **런 빌더** | `createRunFromIDML` (×2 overload), `splitBulletRun`, `resetBulletParagraphColors`, `splitLatinVarsInMixedText`, `cloneRunWithText`, `containsLongLatinWord`, `splitIdmlRunByResolvedRuns`, `hasStyleVariation` | 624~1255 | ~630 |
| 4 | **매칭/정규화** | `findDefaultResolvedRun`, `findResolvedRun`, `eq`, `normalizeSpaces`, `findOriginalLength` | 1127, 1275~1313 | ~50 |
| 5 | **수식 처리 (BT/EH/NP)** | `convertMathRunsInParagraph`, `flushResolvedMathGroup`, `flushMathGroups`, `splitFractionPatternInText`, `isEHSqrtContent` | 1496~1693 | ~200 |
| 6 | **단락 분배** | `distributeByComposedCharRange`, `distributeParagraphs` | 1695~1935 | ~240 |
| 7 | **인라인/체인** | `orderByThreadChain`, `tryInlineFractionAsEquation`, `collectParagraphEquationText`, `convertRunsToHwpScript`, `tryInlineTextFrameAsRun`, `isAnchoredOutsideParentByTextFrame`, `isAnchoredOutsideParent`, `isOutsideParentBounds`, `createSpaceRunForEmptyAnchor`, `isEmptyContainer`, `loadInlineObject` | 1937~2466 | ~530 |
| 8 | **스타일/색상 헬퍼** | `isKoreanFontName`, `getStyleLeading/Tracking/FillColor/FontFamily/FontSize`, `resolveColorToHex`, `resolveStyleAlignment`, `isNoneColor` | 849~985, 1073~1126, 2318, 2467 | ~150 |
| 9 | **런 후처리** | `markOverlineSegments`, `splitOverlineRuns`, `convertItalicRunsToEquations`, `flushItalicMathBuf`, `isItalicMathRun` | 2481~2700 | ~220 |

> 합계 약 2750 LOC (실제 2695와 약간 차이는 클래스 헤더/필드/주석 포함 차이).

## 3. 초기 가설 vs 실제 비교

[improvement-roadmap.md](improvement-roadmap.md) W3-1의 가설은 **5개 그룹**(Story 로딩 / 단락 매칭 / 런 변환 / 수식 처리 / 스타일 상속)이었으나, 실제는 **9개**.

### 가설에 없던 그룹
- **단락 분배 (Group 6)**: composedCharRange 기반 단락을 글상자에 재분배하는 로직 — 240 LOC, 별도 책임
- **인라인/체인 (Group 7)**: 인라인 텍스트프레임을 런으로 변환, 부모 외부 위치 검사, 글상자 체인 정렬 — 530 LOC, 가장 큰 미예상 그룹
- **런 후처리 (Group 9)**: overline/italic을 별도 런으로 분리 — 220 LOC

### 가설보다 작은 그룹
- **단락 매칭 (Group 4)**: 50 LOC만. 작아서 별도 클래스 필요 없음 — 다른 그룹과 합치는 게 효율적

### 평가
가설 5개 → 실제 9개. 하지만 **분리 단위는 9개 전부 별도일 필요 없음**. 일부는 합치고, 너무 큰 그룹은 추가 분리 가능.

## 4. 분리안 (W3-2 제안)

### 권고: 7개 sub-module로 분리

| 새 클래스 | 포함 그룹 | 추정 LOC | 주요 책임 |
|-----------|-----------|:--------:|-----------|
| `StoryConverter` (잔존) | 1 (오케스트레이션) | ~150 | 진입점만, IDML/resolved 분기 결정 |
| `StoryLoader` | 1 (분기 부분) | ~250 | `convertStoryFromIDML` — IDML XML 로드 + 텍스트 길이 비교로 fallback 결정 |
| `RunBuilder` | 3 + 4 | ~680 | `createRunFromIDML` 외 런 변환 + 매칭(`findResolvedRun` 등) — 매칭은 작아서 흡수 |
| `MathProcessor` | 5 | ~200 | BT/EH/NP 수식 처리 |
| `InlineFrameHandler` | 7 | ~530 | 인라인 텍스트프레임 → 런 변환, 외부 위치 검사, 체인 정렬 |
| `ParagraphDistributor` | 2 + 6 | ~420 | resolved 단락 빌드 + composedCharRange 분배 |
| `RunPostProcessor` | 8 + 9 | ~370 | 스타일 헬퍼 + overline/italic 후처리 |

> **ParagraphDistributor에 그룹 2와 6 합치기**: 둘 다 단락 단위 작업이라 응집도 높음.
> **RunPostProcessor에 그룹 8과 9 합치기**: 스타일 조회 헬퍼는 후처리에서 자주 쓰임.

각 클래스 ~150~680 LOC. 가장 큰 RunBuilder도 700 미만으로 유지.

### 의존 관계
```
StoryConverter (진입점)
  ├─ StoryLoader        → IDML XML 로드, 분기 결정
  ├─ ParagraphDistributor → 단락 분배
  │     └─ RunBuilder   → 런 매칭/변환
  │           └─ RunPostProcessor → 스타일/후처리
  ├─ InlineFrameHandler → 인라인 객체 처리
  │     └─ (loadInlineObject는 RunBuilder에서도 호출 — 공유 의존)
  └─ MathProcessor      → 수식 변환
        └─ RunBuilder   → 수식 런도 RunBuilder 사용
```

순환 의존 위험: `RunBuilder` ↔ `MathProcessor` ↔ `InlineFrameHandler`가 서로 부를 수 있음. 해결 방안: 인터페이스 또는 ctx에 메서드 ref 주입.

## 5. 단계별 추출 순서 (W3-3)

가장 독립적 → 가장 결합된 순:

| Step | 추출 클래스 | 위험도 | 이유 |
|------|-------------|:------:|------|
| **A** | `RunPostProcessor` (Group 8 + 9) | 낮음 | 스타일 조회 + overline/italic 후처리. 호출처 적음 |
| **B** | `MathProcessor` (Group 5) | 낮음 | 수식 변환은 결합도 낮음. BT/EH/NP 분리 명확 |
| **C** | `StoryLoader` (Group 1 분기 부분) | 중 | `convertStoryFromIDML` 추출. 본체에서 호출 |
| **D** | `ParagraphDistributor` (Group 2 + 6) | 중 | 단락 분배는 본체에서 호출 |
| **E** | `InlineFrameHandler` (Group 7) | 높음 | 가장 큰 그룹. 외부 위치 검사가 다른 곳에서도 사용될 가능성 |
| **F** | `RunBuilder` (Group 3 + 4) | 높음 | 가장 핵심 로직. 다른 모든 sub-module이 의존 |
| **G** | 잔여 정리 (StoryConverter ~150 LOC) | 낮음 | 오케스트레이터만 남기기 |

각 Step은 별도 커밋. 각 단계 후 골든 변환 + 테스트로 회귀 확인.

## 6. 가드 추가 (W3-4)

분해 완료 후 `phase3/StoryConverter.java` 파일 상단에 가드 코멘트:
```java
/**
 * Phase 3 오케스트레이터.
 * 책임: IDML/resolved 분기 결정 + sub-module 호출 시퀀스.
 *
 * 매칭/변환/수식/스타일 등 구체 로직은 sub-module로 분리:
 * - StoryLoader, RunBuilder, MathProcessor, InlineFrameHandler,
 *   ParagraphDistributor, RunPostProcessor
 *
 * 신규 로직 추가 시 적절한 sub-module을 먼저 검토. 본 클래스에 직접 추가하면
 * W3 작업이 무효화됨.
 */
```

## 7. 위험 요소

| # | 위험 | 완화책 |
|---|------|--------|
| 1 | `private static` 메서드를 sub-module로 옮길 때 가시성 문제 | sub-module 메서드는 `package-private` 유지 (같은 phase3 패키지 내). external 접근 필요 시만 `public` |
| 2 | `convertStories`/`convertStoryFromIDML` 내부에 inline state(local 변수)가 많아서 추출 시 파라미터 폭증 | ctx에 일시적 헬퍼 필드 주입 또는 sub-module 인스턴스 메서드로 (정적 → 인스턴스화) |
| 3 | 메서드 간 호출이 양방향(예: RunBuilder ↔ MathProcessor) | ctx 통한 메서드 ref 주입 또는 인터페이스 |
| 4 | overload 메서드 (`createRunFromIDML` ×2) 분리 시 둘 다 옮겨야 함 | 같은 sub-module로 묶어 처리 |
| 5 | 추출 도중 사용자의 phase3 관련 작업과 충돌 | 작업 시작 전 상태 (commit a070493b) 기준. 추출 중간에 새 SPEC가 들어오면 본 작업 우선 |

## 8. 검증 전략

W2-2와 동일 패턴:
- 각 Step 후 `mvn clean package -q -DskipTests` 성공
- `mvn test -q` baseline 유지 (291/18/43)
- 대표 IDML(부속_(001-009).indd) 변환 후 출력 확인

추가 권장: Step E (`InlineFrameHandler`) 후 한글에서 시각 검증 (가장 큰 변경)

## 9. 완료 기준

- [ ] StoryConverter 2695 LOC → ~150 LOC (오케스트레이터만)
- [ ] 6개 sub-module 생성, 각 ~150~700 LOC
- [ ] 빌드/테스트 baseline 유지
- [ ] 시각 검증 0 warnings
- [ ] [improvement-roadmap.md](improvement-roadmap.md) W3 체크박스 갱신
- [ ] 본 문서를 archive로 이동 또는 폐기

---

## 다음 행동

W3-2 sub-module 클래스 골격 생성 → W3-3 Step A부터 점진 추출.

또는 사용자가 분리안 조정을 원하면 본 문서를 수정 후 진행.
