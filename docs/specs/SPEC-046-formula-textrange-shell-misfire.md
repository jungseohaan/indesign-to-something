# SPEC-046: 화학식 첫 글자의 text-range-shell 오분리

## 문제

과학 u1 p17 파랑 `H₂O` 박스가 세로로 깨져 보인다. 원인 조사 결과 lineWrap·첨자
크기가 아니라, 첫 글자 `H` 가 **거대한 인라인 rect(58pt)로 분리**되고 원래
자리엔 스타일 없는 빈 `H`(charPr=0, 10pt 검정)만 남는 것이었다. 큰 rect 가
`2`·`O` 를 밀어내 세로로 흩어져 보였다. `2H₂O` 는 첫 계수 `2` 가 같은 방식으로
분리됐다.

## 원인 (조사 완료, 2026-07-20)

**text-range-shell 기능(PR #46 "Preserve text range shells as inline source
images")의 오발동**이다. sourceId 가 `text_range_shell_186407` 인 것이 결정적
증거였다.

- `OwnershipPlanner.leadingStyledStoryRunRanges` 는 Group 이 편집 텍스트프레임 +
  텍스트 없는 빈 셸을 함께 가질 때, 텍스트프레임의 **선두 런을 다음 런과의
  스타일 경계(`hasSourceStyleBoundary`)가 있으면 별도 셸 슬롯으로 이동**시킨다
  (원래 배지/라벨용).
- 화학식 `H₂O` 는 `H`(charStyle=[없음], position=NORMAL) → `2`(charStyle=
  00_수식(첨자-하부자), position=SUBSCRIPT)로 **정상적인 첨자 스타일 경계**가
  있어 이 판정에 걸린다. 그래서 첫 글자 `H` 가 셸로 오분리됐다.
- 큰 반응식(단순 텍스트 경로)은 Group+빈셸 구조가 아니라 무영향(사용자 확인:
  "이 반응식들은 잘 해냈다").

디버깅: KEEP/SQUEEZE(lineWrap) 변경은 렌더에 무효였다(두 빌드 육안 동일) —
문제는 셀 폭·줄바꿈이 아니라 첫 글자의 rect 분리였다. HWPX 셀 구조에서
`run charPr=0 → rect(중첩) → 안에 진짜 H` 형태를 발견하고, rect 생성 지점
(`InlineFrameBuilder.addInlineExtractedShellTextFrame`)에 계측을 걸어 sourceId
`text_range_shell_*` 를 잡아 상류 OwnershipPlanner 로 역추적했다.

## 해결

`leadingStyledStoryRunRanges` 가 **화학식 문단은 range 분리 대상에서 제외**한다.
화학식은 배지/라벨이 아니므로 선두 런을 셸로 뗄 이유가 없다.

`isChemicalFormulaParagraph`: 문단의 가시 텍스트가 원소기호(A-Z, 소문자 후속)·
숫자·화학 연산자(+,-,→,괄호)로만 이루어지고, **첨자 런(position=SUBSCRIPT 또는
charStyle 하부자/첨자)이 하나 이상** 있으며 대문자 원소가 있으면 화학식으로
판정(길이 ≤24). 첨자 런 요구 조건이 단순 라틴 단어를, 문자 집합 제한이 한글
배지/라벨을 배제한다.

## 수정 파일

1. `normalizer/resolved/ownership/OwnershipPlanner.java`
   - `leadingStyledStoryRunRanges` 문단 루프에 `isChemicalFormulaParagraph` 가드
   - `isChemicalFormulaParagraph` (신규 헬퍼)

## 검증 (2026-07-20, HEAD 대비)

- 과학 u1: 화학식 셀 중첩 rect 3→0, 파랑 H₂O 의 H 17pt 파랑 복원(색·크기·첨자
  정상). 텍스트 diff 정확히 2곳(둘 다 `[2,H]→[2H]` 정상 병합), 다른 배지/라벨
  100% 동일.
- 수학 u1: 수식 diff 0 + 텍스트 100% 동일.
- (지도서 제외 — 사용자 지시)
