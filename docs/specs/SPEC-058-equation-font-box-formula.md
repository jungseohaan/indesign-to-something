# SPEC-058: 수식 폰트 박스 반응식의 hp:equation 미변환

> 상태: **구현 완료** (2차 시도, 브랜치 `spec-058-boxed-equation-v2`). 2026-07-23.
> 1차 시도는 부작용(하류 hasText 술어가 equation 을 못 봄)으로 되돌렸었음 —
> 2차에서 그 재주입 지점을 실측으로 특정해 함께 수정.

## 구현 요약 (2차)

- **`MathProcessor.promoteEquationFontReactionRanges`** (프리패스): 문단에서
  {수식폰트 런(mathTypeOf≠null) / 화살표(→) 단독 런 / 공백 런}으로만 이어진 연속
  구간을 찾아, **화살표 포함 + 화살표 양쪽에 수식폰트 런 ≥1** 게이트를 통과하면
  구간 전체를 `appendFormulaScript` 로 통짜 ASTEquation("CHEM_FORMULA")으로 승격.
  - 진짜 실패 원인은 (1차 진단의 "화살표에서 그룹이 끊김"보다 깊은) **BT/EH
    타입 교차 배열**: 원소는 BT 폰트(mathType=BT), 첨자는 charStyle
    `00_수식(첨자-하부자)`(mathType=EH), 화살표는 `00_수식(화살표)`(mathType=EH).
    타입별 그룹핑이 매 런에서 flush → 전부 1런 조각 → 텍스트 폴백. 화살표가
    mathType=EH 라 SPEC-059 클러스터(폰트 없는 런 시작)에도 진입 불가.
  - 오발화 가드: 구간 직전/직후 텍스트 런이 공백 없이 영숫자로 이어지면(수식
    본문 중간에서 구간이 시작/종료) 승격 금지 — 본문 폰트 LHS 의 첨자 숫자만
    잡혀 반쪽 수식("2 rarrow 2NH3")이 되는 셀 문단 오발화 방지.
  - ①~⑤ 문제 보기(우변 본문 폰트)는 게이트 미달로 기존 SPEC-059 경로 유지.
    화살표 없는 토큰 TF 도 자연 배제.
- **`ASTTableConverter.inlineObjectHasVisibleText`**: ASTEquation(비어있지 않은
  hwpScript)을 가시 콘텐츠로 인정. 1차 시도의 "박스 소실"의 실체가 이것 —
  `normalizeTextHiddenInlineShellCarriers` → `populateInlineShellOwnedText` 가
  equation-only 셸을 "텍스트 없음"으로 오판하고 resolved 평문 문단으로 **재주입**
  해 승격을 덮어쓰고 있었다 (박스가 사라진 게 아니라 수식이 사장된 것).

## 검증

- p47 `2H_{2}+O_{2} ~ rarrow ~ 2H_{2}O`, 표 안 `N_{2}+3H_{2} ~ rarrow ~ 2NH_{3}`
  둥근 박스(native rect+drawText) 안에 hp:equation 생성. equations 67→69.
- 골든 게이트: diff 가 의도한 수식 +2건뿐 (기존 수식 5종·표 스타일·이미지
  무회귀). 같은 PR 에서 골든 갱신.
- 한계: `N2+H2 → NH3` 박스 1개는 문단에 다른 인라인 객체가 끼어 있어 구간이
  끊겨 미승격 (기존과 동일하게 텍스트) — 필요 시 후속.

## 디버깅 방법론 메모 (재발 시)

같은 sourceId 의 셸이 **여러 경로에서 중복 빌드**되고 최종 승자는 다른 경로일
수 있다. 이번 실측: 승격이 되고도 출력이 안 바뀌면 (1) `ASTInlineObject.sourceId
setter`/`addParagraph`/`paragraphs(List)` 에 대상 id 조건 스택 덤프를 심어
생성·변이 지점을 잡고, (2) 변환기 입구(`InlineFrameBuilder.addInlineTextFrame`)에서
최종 도달 문단을 덤프해 대조한다. 이번 범인은 phase4 테이블 셀 정규화의
`populateInlineShellOwnedText` 재주입이었다.

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
