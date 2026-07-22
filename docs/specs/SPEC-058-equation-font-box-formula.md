# SPEC-058: 수식 폰트 박스 반응식의 hp:equation 미변환 (조사 완료, 구현 보류)

> 상태: **원인 규명 완료, 구현 보류**. 2026-07-23.
> 시도한 프리패스는 부작용(하류 hasText 술어가 equation 을 못 봄)으로 전부 되돌림.
> 본 문서는 재개용 진단 기록.

## 문제

과학 u1 p47 문제 지문에 앵커된 둥근 박스 반응식 "2H₂+O₂ → 2H₂O" 가
hp:equation 이 아니라 일반 텍스트 런으로 변환된다. 같은 계열 박스가
p16-17 등 여러 페이지에 있다 (일부는 토큰별 개별 TF 로 조판).

## 원본 구조 (실측)

- p47 박스: 문제 스토리(u3238d)에 **앵커된 TextFrame u33916(dec 211222)**,
  스토리 텍스트 = `'2 H2+O2 ?C 2 H2 O'`
  - 계수 뒤 **hair space(U+200A)** (SPEC-045 와 같은 조판 패턴)
  - 화살표는 **?C 글리프** (BT화살표, @C 변형)
  - 폰트: BT수식H-분수N/BT수식-편한글씨 (수식 전용)
- plan: `inline_text_frame_shell_slot` + PLACE_TEXT_SHELL + OWNED_BY_HWPX_TEXT
  (둥근 박스는 셸 PNG, 텍스트는 hwpx)
- p16-17 계열: 토큰별 개별 TF ("2H2"/"+O2"/"→"/"2H2O", 각자 스토리)

## 원인 사슬

1. 문단은 `MathProcessor.convertMathRunsInParagraph` 를 지나지만, **BT 폰트가
   살아있는 런은 BT 그룹 경로**로 간다. 거기서:
   - "화살표는 수식의 일부가 아니다" 정책(StoryLoader/ASTStoryConverter)이
     그룹을 화살표에서 끊고,
   - hair space 가 런을 조각내
   - 각 조각("2H₂", "+O₂", "H₂ O")이 단독으론 수식 증거 부족 →
     `flushMathGroup` 폴백으로 전부 텍스트가 된다.
2. 아이러니: 같은 TF 의 badge 셸 경로(정규화로 폰트가 벗겨진 런)는
   `collectFormulaEquationCluster`(화학식 클러스터, currentType==null 런 전용)로
   가서 **수식 변환에 성공**한다. 그러나 최종 HWPX 에 남는 것은 실패한
   TEXT_FRAME_BLOCK 경로의 출력이다.
3. 즉 "폰트가 수식 전용임"이라는 가장 강한 증거가 오히려 실패 경로로
   라우팅하는 구조.

## 방침 (사용자 결정)

- 승격 판정은 내용 휴리스틱(화살표/원소 패턴)이 아니라 **소스 폰트 증거**
  (문단의 모든 런이 BT/EH/NP 수식 전용 폰트)로 한다.
- 화살표 런은 정규화에서 폰트가 벗겨지므로 "→"/"@C"/"?C" 단독 런만 예외.

## 시도와 실패 (되돌림, 반복 금지)

`convertMathRunsInParagraph` 에 "문단 전체 수식 폰트 → 통짜 ASTEquation"
프리패스를 넣었더니:
1. **박스가 통째로 사라짐** — 문단이 ASTEquation 하나가 되면 하류의 여러
   "텍스트 있음" 술어(`hasVisibleText`/`hasSemanticText`/블록 빈판정 등,
   `InlineFrameBuilder`·`HwpxTextBoxBuilder` 계열)가 ASTTextRun 만 세서
   equation-only 문단을 빈 것으로 보고 블록/셸을 드랍한다.
2. **토큰 TF(p16-17)가 자잘한 수식 조각으로 분해** — 토큰 문단마다 별도
   equation 이 되고, 첨자 플래그가 없는 인스턴스는 `2H2O` (첨자 소실)로
   품질 저하.

## 재개 시 필요한 작업

1. **equation 가시성**: ASTEquation 을 가시 콘텐츠로 인정하도록 하류 술어
   전수 수정 (InlineFrameBuilder.shouldFlattenToParentRuns/hasSemanticText,
   HwpxTextBoxBuilder 계열 빈판정, SingleColumnTableConverter 등).
2. **프리패스 게이트 보강**: 토큰 단독 문단(수식폰트 런 2개 미만 또는 화살표
   없는 초단문)은 제외하거나, 토큰 TF 는 상위 병합 후에만 승격.
3. 첨자 보존: run.subscript 플래그가 없는 소스(첨자가 글리프/위치로만 표현)의
   script 생성 규칙.
4. 검증: p47 박스 + p16-17 토큰 박스 + 기존 69개 수식 회귀.

## 별건 (같은 조사에서 발견)

- **수식 좌변 잘림 회귀**: `H₂+O₂ → H₂O`, `CH₄+2O₂ → …`, `CaCO₃+2HCl → …` 등의
  좌변이 텍스트로 떨어져 `~ rarrow ~ H₂O` 만 수식으로 남는다. 내 변경 전
  순정(open-indd e4a32971, PR #103/#110 머지 후)에서 재현 — 머지된 PR 의
  회귀로 추정. 별도 브랜치에서 수정 예정.
- `InlineItemDispatcher.addEquationRun` 의 space→`#`(줄바꿈 토큰) 변환이
  rarrow 수식의 공백에도 적용되는 코드가 새로 들어와 있음 — 좌변 잘림과
  함께 점검할 것.
