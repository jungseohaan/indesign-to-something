# SPEC-082: floating 텍스트 셸의 수식 글리프 평문 누출 (p15 √a → 'a)

## 문제

수학 u1 p15 "양의 제곱근: √a, 음의 제곱근: -√a" 박스가 한글에서
`양의 제곱근: 'a, 음의 제곱근: -'a` 로 렌더된다. EH분수대문자 폰트의
근호 글리프(`'` = √)가 수식으로 변환되지 않고 raw 문자 그대로 노출된 것.

- 대상 프레임: TF 356373 (storyId 356376, 그룹 356403), p15/pageIndex 5
- v64 추출부터 이 그룹이 `pass.decoration_groups` 의
  `PLACE_TEXT_SHELL + OWNED_BY_HWPX_TEXT` 로 승격되어 편집 텍스트가 됨
  (v62 까지는 통PNG 라 증상이 숨어 있었음)
- 같은 유형 누출 3곳 추가: `'16  '29  0.2H8  4-'2` (√16 √29 등), 단독 `'`

## 원인

`InlineFrameHandler.buildFloatingTextShellBlocks` (floating 배지/텍스트 셸)가
**유일하게** `frameVisibleText` 통짜 문자열 + 첫 런 스타일(`applyFirstRunStyle`)로
단일 ASTTextRun 을 만든다. 이 경로는:

1. resolved 스토리 런의 글자속성이 SPEC-067 이후 비어 있는데
   (`fontFamily/charStyle=null`, IDML 주입 전제) IDML 속성 주입을 안 거침
2. `MathProcessor.convertMathRunsInParagraph` 를 안 태움

→ EH/BT/NP 수식 폰트 런 판별이 불가능해 수식 글리프가 평문 누출.

인라인 셸 경로는 이미 `buildSourceStructuredShellTextParagraphs`
(TextFlowUnit=IDML 속성 백업 우선 → story 폴백) +
`postprocessShellTextParagraphs`(MathProcessor + overline 분리)로 처리한다.

## 목표

floating 텍스트 셸도 구조화 스토리 변환 + 수식 파이프라인을 타서
√a 류가 `sqrt{a}` hp:equation 으로 나온다.

## 해결 방안

`buildFloatingTextShellBlocks` 의 TF 루프에서:

- `shouldUseResolvedParagraphsForInlineShell(story)` (복수 런/문단/장문)이면
  `buildSourceStructuredShellTextParagraphs(ctx, tf)` 결과를 사용
- 비어 있거나 단순 배지(단일 런 라벨 칩)는 기존 단일 런 경로 유지
  (배지 중앙정렬·스타일 회귀 방지)

## 수정 파일

1. `normalizer/resolved/phase3/InlineFrameHandler.java` —
   `buildFloatingTextShellBlocks` 구조화 경로 분기 추가

## 검증

- [x] 빌드 성공
- [x] 동일 추출물(v64) 재변환에서 p15 셀이 `sqrt{a}`/`-sqrt{a}` 수식으로 변환
- [x] `'` 누출: 4곳 → 1곳. p15 √a 2곳 + √16·√29 박스 해소. 잔여 1곳(`48√5cm³` 본문)은
      어제 출력에도 있던 기존 별건 (floating 셸 경로 아님)
- [x] 수정 전 JAR 대비 diff 8건 모두 의도한 변화(두 박스 수식 승격)뿐
- [x] 과학u1 기준 추출물 재변환: 수정 전 JAR와 구조 시그니처 완전 일치 (PASS)
- [ ] 한글 육안 확인

## 남은 후속 (별건)

1. `0.2H8` — EH약물 `H`(순환마디점 dot)가 수식 그룹에서 탈락해 `H8` 평문 잔존.
   H 런의 ASTTextRun 에 charStyleRef(약물)가 전파되지 않아 `isEHTextRun` 미인식.
   TextFlow 원자 → ASTTextRun 스타일 전파 경로 조사 필요
2. `48√5cm³` 본문 — `'` 근호가 본문 스토리 경로에서 평문 잔존 (v62 시절부터 존재)
