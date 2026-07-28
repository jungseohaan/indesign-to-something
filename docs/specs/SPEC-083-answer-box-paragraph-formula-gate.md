# SPEC-083: 빈 답란 박스 □ 흡수를 문단 단위 수식 게이트로 통일

## 문제

수학 u1 p15 "2의 양의 제곱근은 □, 음의 제곱근은 [빈박스]" — 같은 문장의
동일한 빈 답란 Rectangle 두 개가 다르게 변환된다:

- 첫 번째(u487f8): □ 로 흡수 (formulaAnswerBoxRun)
- 두 번째(u487fc): 인라인 PNG(파란 라운드 박스)로 잔존

## 원인

orcOnly 앵커 런의 □ 흡수 게이트 `isFormulaEquationClusterRun` 이
**±1 인접 런**의 수식성만 본다 (`hasFormulaNeighbor`):

- 첫 박스: 바로 뒤 `,` 가 formula operator 로 판정 → 통과
- 두 번째 박스: 양옆이 한글 텍스트(" 음의 제곱근은 ", "이고 이것을…") → 탈락

같은 문단, 같은 모양(5×7pt vector rect, PLACE_INLINE_PNG plan)의 빈칸인데
인접 문자 우연으로 결과가 갈린다.

## 목표

한 문단 안에 수식 증거가 있으면 그 문단의 빈 답란 박스는 인접 여부와
무관하게 □ 로 통일 흡수한다. (사용자 지시: "빈박스는 수식으로 흡수 통일")

## 해결 방안

`ASTMathGrouper.paragraphHasFormulaEvidence(runs)` 신설 — 문단 런 중
BT/EH/NP/GREP 수식 런(비 orcOnly)이 하나라도 있으면 true.

orcOnly 앵커 런의 □ 흡수 조건을
`formulaClusterRun || paragraphHasFormulaEvidence` 로 완화.
`isFormulaAnswerPlaceholderRun` 가드(실체 시각물 앵커 제외, SPEC-056)는
그대로 유지 — 연필+밑줄 같은 콘텐츠 PNG 는 계속 흡수 금지.
수식 증거가 전혀 없는 산문 문단의 빈 박스는 기존(pic) 유지.

가드는 SPEC-056 트랩대로 **StoryLoader 와 ASTStoryConverter 양쪽**에 적용.

## 수정 파일

1. `normalizer/ASTMathGrouper.java` — `paragraphHasFormulaEvidence` 신설 +
   `formulaAnswerBoxRun` 이 원본 박스 크기·색 반영
2. `normalizer/ASTStoryConverter.java` — □ 흡수 게이트 완화
3. `normalizer/resolved/phase3/StoryLoader.java` — 동일 게이트 완화

## □ 크기·색 반영 (사용자 지시: 하드코딩 배율 대신 원본 반영)

- 크기: 박스 벡터의 `heightPoints / 0.7` (U+25A1 사각형 ≈ 0.7em) —
  p15 박스 5mm(14.17pt) → 글자 20.24pt, 렌더 사각형이 박스 높이를 근사.
  폭 기반은 가로로 긴 답란에서 줄이 부풀어 배제 (treatAsChar 트랩)
- 색: 박스 stroke("Color/C=40 M=8 Y=0 K=0" → #8EC2DF 하늘색) 우선,
  없으면 fill (Paper 흰색은 제외 — 안 보임)
- **주의: resolved.json 의 pageItem bounds 는 원단위(mm)** — pt 로 오독하면
  박스가 5×7pt 로 보인다. IDML `heightPoints` 가 pt 정본 (SPEC-057 트랩)

## 검증

- [x] 빌드 성공
- [x] p15 두 번째 빈칸 pic → □ 통일
- [x] 수정 전 대비 diff 27건 모두 빈 박스 □ 흡수 계열 (pic 16개 흡수 +
      기존에 통째로 유실되던 빈칸 7곳 □ 복구, 흡수 대상 전부 ≤2×2(10pt) 소형)
- [x] 과학u1 기준 추출물 시그니처 PASS (연필+밑줄 답란 가드 유지 확인)
- [ ] 한글 육안 확인
