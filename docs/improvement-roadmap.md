# 코드 개선 로드맵 — 리팩토링 집중 (2~4주)

> 대상: 브랜치 `open-indd`, v1.0.9 기준
> 범위: **리팩토링 only** (동작 변경 없이 구조 개선). 기능 추가/버그 수정은 별도 SPEC.
> 작성: 2026-05-02

---

## 1. 목표 & 비-목표

### 목표
- 거대 모듈 분해 (StoryConverter 2695 LOC, HwpxTextBoxBuilder 1619 LOC, HwpxParagraphBuilder 1093 LOC)
- 신/구 코드 경계 명확화 (Stage4_BuildAST 등 유산 명명, 미사용 클래스 식별)
- 반복 비용 제거 (`buildPhaseContext()` 11회 호출 등)
- 문서·코드 동기화 (CLAUDE.md "4단계" 표현 등)

### 비-목표 (이번 라운드 외)
- ❌ CI/CD 구축 — 별도 트랙
- ❌ 통합/스냅샷 테스트 인프라 신규 — 별도 트랙 (단, 본 작업의 회귀 검증용 골든 페어는 만든다)
- ❌ 미해결 기능 (EH 수식 괄호+지수, 워드 간격, Page 6, Oval 클리핑) — 별도 SPEC
- ❌ 멀티 포맷 출력 / 시멘틱 M3 완성

### 성공 기준
1. **변환 결과 동등성**: 골든 IDML 셋(아래 §3)에 대해 본 작업 전후 HWPX 출력 바이트 단위 동일 (또는 무의미 차이만)
2. **빌드 성공**: `mvn clean package -q -DskipTests`
3. **CLI 스모크 테스트**: 대표 IDML 1개 변환 + 한글에서 열어 시각 확인

---

## 2. 현재 코드 부채 진단

### 2.1 거대 클래스 (LOC 상위)
| 클래스 | LOC | 부채 |
|--------|-----|------|
| `phase3/StoryConverter.java` | 2695 | 단락 매칭 + 런 빌드 + 수식 처리 + 스타일 상속 + IDML/resolved fallback이 한 클래스에 |
| `converter/HwpxTextBoxBuilder.java` | 1619 | 글상자 변환 + 인라인 객체 + 폴리곤 폴백 + 오버레이 모두 |
| `converter/HwpxParagraphBuilder.java` | 1093 | 단락 빌드 + 줄간격 자동 확장 + 인라인 객체 분기 |
| `converter/HwpxImageBuilder.java` | 642 | 이미지 + 배경 + 비-사각 배경 |
| `phase2/FramePlacer.java` | 679 | 분류 + Y-gap split + nested 검사 |

### 2.2 명명·구조 잔존물
| 항목 | 현실 | 문서/명명 |
|------|------|-----------|
| 레거시 정규화 단계 수 | **3단계** | CLAUDE.md "4단계", 옛 클래스명 `Stage4_BuildAST` |
| `Stage3_CollapseInlines` | **삭제됨** | 일부 주석/문서에 잔존 언급 |
| 신 파이프라인 phase 분리 | 클래스명 (`InfraSetup`, `PageLayoutBuilder`, …) | "Phase N"이라는 직관적 매핑이 클래스명에는 없음 |
| 레거시 normalizer 클래스들 | 신 phase에서도 일부 재사용 (`ASTRunConverter`, `ASTMathGrouper`, `ASTTableConverter` 등) | "레거시"로 단정 못 함 — 공유 헬퍼 |

> 확인: [phase3/StoryConverter.java](src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java)는 `ASTRunConverter`, `ASTMathGrouper`, `ASTOverlayBuilder`, `ASTInlineObjectBuilder` 등을 직접 import. 단순 삭제 불가. **이동 또는 명명 정리** 대상.

### 2.3 반복 비용
`ResolvedToASTBuilder.buildPhaseContext()`는 **build() 내에서 11회** 호출되어 매번 새 `ResolvedBuildContext` 인스턴스 생성. 코드 내 주석:
> "후속 단계에서 build() 시작 시 한 번만 만들고 재사용하도록 바뀔 예정."

→ 이번 로드맵에서 이행.

### 2.4 미사용 코드 후보
- `// FontMapper import removed (unused in new pipeline)` 같은 주석 잔존
- `Stage1_Flatten.java`/`Stage2_InlineDetect.java`/`Stage4_BuildAST.java`는 레거시 분기에서만 사용 → 사용 빈도 측정 필요

---

## 3. 회귀 검증 전략 (Phase 0, 사전 작업)

리팩토링 동안 동작 변경 없음을 보장하려면 **변환 결과 비교**가 필요. 통합 테스트 프레임워크를 새로 만들지는 않고, **수동 골든 페어 + 비교 스크립트**로 시작.

### 3.1 골든 IDML 셋 (3~5개)
- 단순: 풀수록 수학 1 페이지
- 중간: 중3영어교과서 u1 첫 페이지 (테이블 + 인라인 + 배지)
- 복잡: 중3-1국어 페이지 (수식 + 그림 + 다단)
- 회귀: 최근 SPEC 영향 케이스 (SPEC-022/023/024 픽스 대상)

각 케이스에 대해:
- `output.idml`, `resolved.json`, `Links/`, 추출된 `pageBackgrounds/`, `renderedFloating/`을 `testFile/golden/<case>/` 에 보존
- 기준 HWPX (`expected.hwpx`) 와 변환 시점 메타 (`info.txt`) 보존

### 3.2 비교 도구
간단 셸 스크립트 `scripts/golden_diff.sh`:
```bash
# 1. 각 골든 케이스를 변환
# 2. HWPX (ZIP)을 풀어 XML 파일별 정렬 → diff
# 3. 차이가 있으면 stdout에 출력, 없으면 ✓
```
- `Contents/section0.xml` 등 핵심 파일 비교
- 무의미 차이(타임스탬프, ID 카운터 시작값) 제외 필터

### 3.3 운영 규칙
- **각 작업 단위 완료 시 골든 diff 실행**
- diff가 발생하면: 의도된 변경인지 검토 → 의도면 expected.hwpx 갱신 + 이유 기록, 아니면 롤백
- 작업 시작 전 한 번 baseline 캡처

> 인프라 부족이라는 약점은 인정하되, **이 로드맵 자체가 인프라 마련의 첫 걸음**.

---

## 4. 단계별 계획

### Week 1 — 저위험 정리 (3~5일)

#### W1-1: 문서·명명 동기화
- [x] **CLAUDE.md 갱신** (2026-05-02): 이중 분기 다이어그램, Phase 0~7 명시, `Stage3_CollapseInlines` 언급 제거, 파일 트리 업데이트, 알려진 트랩 확장
- [ ] ~~**`Stage4_BuildAST` rename**~~ — W2-1로 미룸. shared/legacy 패키지 분리와 함께 처리
- [x] **미사용 import 정리** (2026-05-02): `ResolvedToASTBuilder`에서 14개 dead import 제거 (W1-2 작업 중 표면화된 것)

#### W1-2: ResolvedBuildContext 캐싱 ✓ 완료 (2026-05-02)
- [x] **`ResolvedToASTBuilder.build()` 시작 시 ctx 1회만 생성**
  - `private ResolvedBuildContext ctx;` 필드 추가
  - `build()` 진입 시 `this.ctx = newContext()` 1회
  - 11개 호출처 모두 `this.ctx`로 교체
  - `buildPhaseContext()` → `newContext()` rename (1회 호출 의미 반영)
  - 결과: ctx 인스턴스 11회 → 1회. 빌드/테스트 baseline 유지 (291/18/43 동일)

#### W1-3: 레거시 클래스 인벤토리 ✓ 완료 (2026-05-02)
- [x] **사용처 매트릭스 생성** → [docs/refactor-inventory.md](refactor-inventory.md)
  - 23개 normalizer/ 루트 클래스를 A(SHARED 직접) / B(SHARED 간접) / C(LEGACY-only) / D(DEAD)로 분류
  - **핵심 발견**: SHARED 클래스(`ASTRunConverter`, `ASTMathGrouper` 등)가 LEGACY 헬퍼에 깊이 의존 → 단순 패키지 이동 불가. W2-1 권고안 4-tier로 수정
- [x] **순수 dead code 제거**: `NormalizerContext.java` 삭제 (30 LOC, 어디에서도 사용 안 됨)

**Week 1 산출물 + 게이트**: CLAUDE.md PR + rename PR + ctx 캐싱 PR + 인벤토리 문서. 골든 diff 통과.

---

### Week 2 — Phase 모듈 경계 정리 (5~7일)

#### W2-1 Tier 2: 안전한 LEGACY-only 클래스 이동 ✓ 완료 (2026-05-02)
- [x] 6개 클래스를 `normalizer/legacy/`로 이동:
  - `Stage1_Flatten`, `Stage2_InlineDetect`
  - **`Stage4_BuildAST` → `Stage3_BuildAST`** (rename, 옛 4단계 명명 청산)
  - `IDMLNormalizer`, `ASTMetadataBuilder`, `FloatingImageMerger`
- [x] 외부 consumer import 갱신: `IDMLToHwpxConverter`, `ConverterCLI`, `SampleIDMLToHwpxConvertWriter`(test)
- [x] visibility 노출: `ASTPageProcessor`, `ASTTextWrapSimulator`, `ASTStoryConverter` 클래스 + 일부 정적 메서드를 `public`으로 (legacy/에서 normalizer/ 호출 위해)
- [x] 부산물: 미사용 import 정리 (`Stage1_Flatten`의 `CoordinateConverter`, `Stage2_InlineDetect`의 `java.util.List`)
- [x] 빌드/테스트 baseline 유지 (291/18/43 동일)

#### W2-1 Tier 3: B 분류 클래스 정리 (보류)
- [ ] `ASTPageProcessor` 정적 헬퍼(`stripACEPlaceholders`, `shouldDeferInlineFrame` 등)를 `shared/StringHelpers.java` 등으로 추출
- [ ] 추출 후 `ASTPageProcessor` 본체를 `legacy/`로 이동
- [ ] W3 StoryConverter 분해 후 결정 (의존성 그래프가 변할 가능성)

#### W2-1 Tier 4: SHARED 클래스의 LEGACY 의존 정리 (보류)
- [ ] `ASTRunConverter`, `ASTTableConverter`, `ASTMathGrouper`, `MathPatternDetector`의 LEGACY 의존을 phase3 sub-module로 흡수
- [ ] W3 StoryConverter 분해와 함께 진행

#### W2-2: ResolvedToASTBuilder 슬림화 ✓ 완료 (2026-05-02)
- [x] **wrapper 메서드 10개 제거**: `copyIDMLDefinitions`, `enrichStyleAlignmentFromResolved`, `buildSections`, `placeTextFrames`, `splitByWrapIndent`, `placeTablesFromIDML`, `convertStories`, `insertBulletsForBulletStyles`, `placeRenderableFrames`, `injectPageBackgrounds`
- [x] **`build()` 메서드를 phase 호출 시퀀스로 단순화** — 각 phase가 한 줄
- [x] **dead code 제거**: `findSectionIndex()` (호출처 없음)
- [x] **섹션 헤더 주석 8개 제거** + 부수 코드 정리(`loadIDMLStory`의 중복 hex 변환, `lastMatchResult` final)
- [x] **결과**: 422 LOC → **247 LOC** (-41%). 빌드/테스트 baseline 유지 (291/18/43)
- 시그니처 일관성 검토: 의도적으로 다양 — Phase 0(`InfraSetup`)는 `(ctx)`, Phase 1(`PageLayoutBuilder`)은 `(ctx)` 반환 + sections 변경, Phase 2~7은 `(ctx, sections)` void. 변경 불필요

**Week 2 산출물**: shared/legacy 패키지 분리 PR + ResolvedToASTBuilder 슬림화 PR. 골든 diff 통과.

---

### Week 3 — StoryConverter 분해 (5~7일, 가장 큰 작업)

[StoryConverter.java](src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java) 2695 LOC를 책임 단위로 분리.

#### W3-1: 책임 식별
StoryConverter 내부 그룹(메서드 시그니처 기반 추정):
1. **Story 로딩 & 분기**: IDML 우선 / resolved fallback 결정 (텍스트 길이/단락 수 비교)
2. **단락 매칭**: IDML 단락 ↔ resolved 단락 텍스트 기반 매칭
3. **런 변환**: `createRunFromIDML`, `findResolvedRun`, 속성 우선순위(SPEC-012)
4. **수식 처리**: BT/EH/NP 폰트 그룹화, EHGrep 분수 변환
5. **스타일 상속**: BasedOn 체인 resolve, EH/BT 한국어 필터

#### W3-2: 분리안 (제안)
- `phase3/StoryConverter.java` — **오케스트레이터만 유지** (~300 LOC 목표). 진입점 `convertStories(ctx, sections)`
- `phase3/StoryLoader.java` — Story 로딩 & 분기 결정
- `phase3/ParagraphMatcher.java` — 단락 매칭 (텍스트 기반)
- `phase3/RunBuilder.java` — 런 변환 + 속성 우선순위 + 신뢰도 카운트 (SPEC-016)
- `phase3/MathProcessor.java` — 수식 폰트 그룹화 + 변환 (BT/EH/NP)
- `phase3/StyleInheritance.java` — 스타일 상속 헬퍼 (이미 별도인 `StylePropertyResolver`와 별개라면)

> 초기 가설일 뿐. **W3-1 결과에 따라 조정**. 실제 분리 단위는 책임 식별 후 결정.

#### W3-3: 단계별 추출
하나씩 추출하고 각 단계 후 골든 diff. 실제 분리 순서는 [W3 plan](w3-storyconverter-plan.md) 참조 (W3-1 분석 결과).

- [x] **Step A**: `RunPostProcessor` 추출 (Group 9) ✓ 완료 (2026-05-09)
  - StoryConverter 2695 → 2478 LOC (-217), RunPostProcessor 241 LOC
  - HEAD baseline과 byte-identical 변환 확정 무손실
  - Group 8(스타일 헬퍼)은 ctx 결합 때문에 보류 → 다른 sub-module 추출 시 함께 처리
- [x] **Step B**: `MathProcessor` 추출 (Group 5) ✓ 완료 (2026-05-11)
  - StoryConverter 2478 → 2279 LOC (-199), MathProcessor 227 LOC
  - HEAD baseline byte-identical (무손실 확정)
  - convertMathRunsInParagraph + flushResolvedMathGroup + flushMathGroups + splitFractionPatternInText + isEHSqrtContent
- [ ] **Step C**: `StoryLoader` 추출 — **보류 (2026-05-11 차단 발견)**
  - `convertStoryFromIDML` (421 LOC 단일 메서드)이 잔존 헬퍼 20+개에 깊이 의존
  - Group 4 매칭, Group 7 인라인, Group 8 스타일 헬퍼 모두 사용
  - 추출 시 모든 의존성을 package-private 노출해야 — 비용 > 이익
  - **권고**: Step F(RunBuilder) 또는 Step E(InlineFrameHandler) 먼저 추출 후 의존성 줄여 재시도
- [x] **Step D**: `ParagraphDistributor` 추출 (Group 6만) ✓ 완료 (2026-05-11)
  - StoryConverter 2279 → 2037 LOC (-242), ParagraphDistributor 263 LOC
  - HEAD baseline byte-identical (무손실 확정)
  - distributeByComposedCharRange + distributeParagraphs. orderByThreadChain visibility 노출
  - Group 2 (convertStoryParagraphs)는 Step C와 유사한 차단 — 분리 보류
- [x] **Step E**: `InlineFrameHandler` 추출 (Group 7) ✓ 완료 (2026-05-11)
  - StoryConverter 2037 → 1505 LOC (-532), InlineFrameHandler 578 LOC
  - HEAD baseline byte-identical (무손실 확정)
  - 11개 메서드: orderByThreadChain, tryInlineFractionAsEquation, collectParagraphEquationText, convertRunsToHwpScript, tryInlineTextFrameAsRun, createSpaceRunForEmptyAnchor, isEmptyContainer, isAnchoredOutsideParent(+ByTextFrame), isOutsideParentBounds, loadInlineObject, isNoneColor
  - resolveColorToHex visibility 노출, ParagraphDistributor 호출 갱신
- [ ] **Step F**: `RunBuilder` 추출 (Group 3 + 4) — **보류 (2026-05-11)**
  - Group 4 단독: 4개 메서드 ~40 LOC, 효과 미미
  - Group 3+4 큰 단위: createRunFromIDML 본문 240 LOC + 잔존 헬퍼 의존 다수 → 추출 위험 매우 큼
  - StoryConverter 분리는 W3 plan 가설보다 응집도가 매우 높아 의미 있는 추출이 제한적
- [ ] **Step G**: 잔여 정리 + Group 8 처리

각 step은 별도 커밋. 문제 발생 시 step 단위 롤백.

#### W3-4: 가드 추가
- [ ] StoryConverter에 클래스 LOC 가드 코멘트:
  ```java
  // 책임: Phase 3 오케스트레이션. 매칭/변환/수식/스타일은 sub-module로 분리.
  // 신규 로직 추가 시 적절한 sub-module을 먼저 검토. 본 클래스에 직접 추가하면 W3 작업이 무효화됨.
  ```

**Week 3 산출물**: StoryConverter 5~6개 클래스로 분리, 각 ~300~600 LOC. 골든 diff 통과.

---

### Week 4 — Builder 슬림화 + 정리 (3~5일, 시간 허락 시)

3주차에서 시간이 남거나 Builder 작업까지 욕심내는 경우. 우선순위 낮음.

#### W4-1: HwpxTextBoxBuilder 분리 ✓ 완료 (2026-05-10)

실제 시작 시점 LOC: **1980** (당초 계획 1619에서 사용자 SPEC 작업으로 증가). 상세 분석은 [docs/w4-textboxbuilder-plan.md](w4-textboxbuilder-plan.md).

- [x] **Step A**: `TextBoxLayoutHelpers` 추출 (196 LOC) — 단락 높이/열 분배 정적 헬퍼 6개
- [x] **Step B**: `PageOverlayBuilder` 추출 (202 LOC) — `addPageLevelOverlay` + `createOverlayBorderFill`
- [x] **Step C**: `InlineFrameBuilder` 추출 (276 LOC) — `addInlineTextFrame` 인라인 텍스트프레임
- [x] **Step D**: `SingleColumnTableConverter` 추출 (240 LOC) — 단일 컬럼 1×1 테이블 변환
- [x] **Step E**: `FrameTransformations` 추출 (284 LOC) — 회전/라운드 변형 분기
- [x] HEAD baseline byte-identical 검증 (5개 step 모두 무손실)
- [x] 빌드/테스트 baseline 유지 (291/18/43)

**결과**: HwpxTextBoxBuilder **1980 → 984 LOC (-50%)**. 잔존 클래스에 메인 변환 + 다단/래퍼/`createTextFrameBorderFill`/`addSpacerRect` 포함.

**계획 수정**: 당초 `NonRectBackgroundBuilder`는 W4 plan의 잘못된 가설 — `convertNonRectBackground`는 `HwpxImageBuilder`에 있어 본 작업과 무관.

**발견 사항**:
- `convertSingleColumnTable(7-arg)` overload은 dead overload (8-arg에 위임만, 외부 호출 없음). 정리 가능하지만 별도 SPEC
- `ASTPageProcessor`/`ASTStoryConverter`/`ASTTextWrapSimulator` 등의 visibility를 W2-1 작업에서 이미 노출시킨 패턴을 동일하게 사용 (잔존 헬퍼들을 package-private으로)

#### W4-2: HwpxParagraphBuilder 분리 ✓ 완료 (2026-05-11)

실제 시작 시점 LOC: **1206** (당초 계획 1093에서 사용자 SPEC 작업으로 증가). 상세 분석은 [docs/w4-paragraphbuilder-plan.md](w4-paragraphbuilder-plan.md).

- [x] **Step A**: `LineSpacingResolver` 추출 (177 LOC) — 단락 높이 추정 + 줄간격 보정 (Group 2+4). dead `clampLineSpacingForMixedFontSizes` 식별
- [x] **Step B**: `ParaPrFactory` 추출 (259 LOC) — ParaPr 생성/override + 단락 속성 (Group 5). 6개 delegate 메서드 유지 (외부 호출자 5+개)
- [x] **Step C**: `CharPrFactory` 추출 (346 LOC) — TextRun/CharPr + 공백 분리 + 폰트 스타일 (Group 6+7). 6개 delegate 메서드 유지
- [x] **Step D**: `InlineItemDispatcher` 추출 (242 LOC) — 인라인 객체 dispatch + Break + Equation (Group 3+8). 4중 Builder 의존 해결: builder 필드를 package-private으로 노출 → `paragraphBuilder.textBoxBuilder.X()` 늦은 바인딩
- [x] **Step E**: 잔여 정리 — dead `clampLineSpacingForMixedFontSizes` 제거, 미사용 import 6개 제거, 가드 코멘트 추가
- [x] HEAD baseline byte-identical 검증 (5개 step 모두 무손실)
- [x] 빌드/테스트 baseline 유지 (291/18/43)

**결과**: HwpxParagraphBuilder **1206 → 327 LOC (-73%)**. 목표 -67% 초과 달성. 잔존 클래스에 메인 진입점 + delegate + Tiny 헬퍼 + LineSeg 유틸 포함.

#### W4-3: 최종 청소
- [ ] dead code 제거 (Week 1 인벤토리 결과)
- [ ] [docs/architecture.md](docs/architecture.md) 갱신: 변경된 패키지 구조 반영
- [ ] [CLAUDE.md](CLAUDE.md) 변경 사항 반영 (코딩 컨벤션 갱신 시)

**Week 4 산출물**: 시간 허락 시 Builder 분리 PR 1~2개. 최종 문서 동기화.

---

## 5. 위험 & 완화책

| 위험 | 가능성 | 완화책 |
|------|--------|--------|
| 골든 diff에 의도하지 않은 변경 발생 | 중 | 각 step 단위 커밋 → 이진 탐색으로 원인 식별 |
| 분리한 클래스 간 순환 의존 | 중 | StoryConverter 분리 시 ctx로 데이터 전달, 상호 호출 금지. 컴파일 시 발견 |
| 패키지 이동으로 외부 도구(데스크탑/Tauri) 깨짐 | 낮음 | 외부 노출 진입점은 `IDMLToHwpxConverter`/`ConverterCLI`만. Rust는 CLI 호출만 함 → 내부 리팩토링 영향 없음 |
| 골든 셋이 변환 영역의 일부만 커버 | 높음 | "회귀 검증의 1차 그물" 정도로 인정. 추가 검증은 수동 한글 열기 + 시각 확인. **장기적으로 통합 테스트 트랙 필요** |
| 작업 도중 EH 수식/워드 간격 등 별도 이슈가 코드 분리에 영향 | 중 | 본 작업과 별도 SPEC으로 분리. 분리 후 머지 충돌이 생기면 본 작업이 우선 |
| StoryConverter 분리 도중 발견된 책임 경계가 가설과 다름 | 높음 | W3-1을 분리 작업 전 별도 단계로. 가설 검증 후 분리 시작 |

---

## 6. 우선순위 매트릭스

| 작업 | 영향 | 위험 | 노력 | 우선순위 |
|------|:---:|:---:|:---:|:---:|
| W1-1 문서·명명 동기화 | 중 | 낮음 | 0.5d | ★★★ |
| W1-2 ctx 캐싱 | 낮음 | 낮음 | 0.5d | ★★★ |
| W1-3 레거시 인벤토리 | 중 | 낮음 | 1d | ★★★ |
| W2-1 Tier 2 패키지 분리 (legacy/) | 중 | 중 | 1d | ★★★ ✓ |
| W2-1 Tier 3/4 (B/SHARED 정리) | 중 | 높음 | 3d | ★ (W3 후로 보류) |
| W2-2 ResolvedToASTBuilder 슬림화 | 중 | 낮음 | 1d | ★★★ |
| W3 StoryConverter 분해 | **높음** | **중** | 5~7d | ★★★ |
| W4-1 HwpxTextBoxBuilder 분리 | 중 | 중 | 3d | ★ |
| W4-2 HwpxParagraphBuilder 분리 | 중 | 중 | 3d | ★ |

★★★ = 4주 안에 반드시. ★★ = 가능하면. ★ = 시간 남으면.

---

## 7. 보류 / 후속 작업 (별도 트랙)

본 로드맵에 포함하지 않은 것:

### 별도 SPEC 후보
- **CI/CD 구축**: GitHub Actions로 `mvn package` + Tauri build smoke test + golden diff 자동화
- **통합 테스트 프레임워크**: 골든 페어 인프라 정식화. JUnit + 변환 결과 비교 유틸. 본 로드맵의 `golden_diff.sh`를 발전
- **EH 수식 괄호+지수**: [docs/specs/eh-equation-remaining.md](docs/specs/eh-equation-remaining.md)
- **워드 간격 보정**: [docs/specs/word-spacing-issue.md](docs/specs/word-spacing-issue.md)
- **Page 6 TextFrame 위치**: [docs/specs/page6-textframe-position.md](docs/specs/page6-textframe-position.md)
- **Oval 클리핑**: [docs/specs/oval-clipping.md](docs/specs/oval-clipping.md)

### 더 큰 리팩토링 (이번 라운드 외)
- 레거시 파이프라인 deprecation: 데스크탑 앱이 항상 새 파이프라인을 쓴다면, 1~2 릴리스 후 레거시 분기·후처리 7단계·`Stage1_Flatten`/`Stage2_InlineDetect`/`LegacyStage3_BuildAST` 제거 검토
- HwpxImageBuilder/FramePlacer 분리
- 시멘틱 레이어 M3 완성 (SPEC-018)

---

## 8. 일일 체크리스트 템플릿

각 작업 단위 시작·종료 시:

```
□ 작업 시작 전: golden_diff.sh 통과 확인 (baseline)
□ 작업 후: golden_diff.sh 통과 확인
□ mvn clean package -q -DskipTests 성공
□ 대표 IDML 1개 CLI 변환 → 한글에서 시각 확인 (가능한 경우)
□ 커밋 메시지: 한글, "리팩토링: <범위> — <변화>" 형식
□ 작업 단위가 끝나면 본 로드맵 체크박스 갱신
```

---

## 9. 참고

- [docs/architecture.md](architecture.md) — 현재 아키텍처
- [docs/specs/](specs/) — 기존 SPEC들
- [CLAUDE.md](../CLAUDE.md) — 프로젝트 컨벤션
- [src/main/java/.../normalizer/ResolvedToASTBuilder.java](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ResolvedToASTBuilder.java) — 신 파이프라인 진입점
- [src/main/java/.../normalizer/resolved/phase3/StoryConverter.java](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryConverter.java) — W3 대상

---

**문서 갱신 규칙**: 각 작업 완료 시 §4의 체크박스 갱신. 작업 도중 가설이 빗나가면 §5에 기록. 4주 종료 시 회고를 마지막에 추가하고 본 문서를 `docs/archive/`로 이동.
