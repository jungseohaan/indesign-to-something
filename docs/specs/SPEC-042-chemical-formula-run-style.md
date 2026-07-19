# SPEC-042: 화학식 런 스타일 보존 (H₂ 크기·첨자)

## 문제

과학 u1 p17 "수소+산소→물" 다이어그램의 H₂ 등에서, H 가 원본 17pt Bold 대신
10pt 비볼드로 출력된다. 아래첨자 2 는 17pt Bold + SUB 로 정상이라, 결과적으로
첨자 2 가 H 보다 크게 보인다.

주의: 최초 "첨자 미반영" 판정은 검출 버그였다 — HWPX 첨자는 charPr 의
`<hh:subscript/>` (소문자 요소)로 표현되며, 실제로는 첨자가 대부분 정상 적용돼
있다. 실측 기준 상태(과학 u1): H/2 인접쌍 약 40건 중 SUB 누락 3건(별개 문맥),
문제의 본질은 **H 의 크기·굵기 손실**.

## 원인 (조사 완료, 2026-07-19)

- 소스는 온전: IDML H 런 = `PointSize=17 FontStyle=Bold` (스타일 없음),
  2 런 = 동일 + `00_수식(첨자-하부자)` 스타일. resolved DOM 도
  position=SUBSCRIPT 정확히 보고.
- `MathProcessor.mathRunFromTextRun` (resolved 문단 재그룹핑용
  ASTTextRun→IDMLCharacterRun 어댑터)이 **content/fontFamily/position 만 복사**
  — 수식 그룹에 들어간 H 는 폴백 텍스트런이 될 때 크기·굵기를 잃는다.
- 짧은 첨자 런('2')은 `isSimplePositionedTextRun` 분기로 원본 ASTTextRun 이
  유지되기 때문에 17pt Bold + SUB 를 지킨다 → H 와 2 의 비대칭.

## 시도한 수정과 실패 기록 (재시도 금지 아님 — 상호작용 파악용)

1. `flushMathGroup` 최종 폴백을 `copyMathRunTextStyle` 공통 sink 로 교체
   → 이 문서에서는 효과 0 + 미세 부작용 1건(각주 H 7.5→10pt). 되돌림.
2. 어댑터에 fontStyle/fontSize/characterStyleRef 복사 추가
   → H 는 17pt Bold 복원 ✓. 그러나 **H₂·H₂O 단독 프레임 5건에서 2 의 SUB 가
   유실**되는 회귀. characterStyleRef 복사 제거로도 동일. fontSize/fontStyle
   에 반응하는 명시적 판정은 grep 상 없음 — `collectFormulaEquationCluster`
   또는 후단 재그룹핑 경로가 어댑터 속성 변화로 다른 분기를 타는 것으로 추정.
   원인 미특정 상태로 전부 되돌림 (현재 코드는 베이스라인).

## 구현 (2026-07-19 완료)

**백필 방식** — 어댑터는 베이스라인 유지(하류 그룹핑·보강 경로 불변),
`flushResolvedMathGroupWithBackfill` 이 flush 산출 텍스트런에 원본 ASTTextRun 의
크기·굵기·첨자를 **비어 있는 속성만** 채운다 (순서 보존 텍스트 1:1 매칭,
분절·병합 산출물은 제외).

핵심 제약(실패 기록 3): **짧은 숫자 런(첨자 후보)은 백필 제외** — 하류 어딘가의
첨자 보강이 "크기 없는 폴백 런"을 전제로 동작해, 숫자 런에 크기를 선주입하면
경로 불문(어댑터/백필) 첨자가 유실된다. 이 보강 패스의 정체는 미특정 —
백필 우회로 회피했고, 특정되면 근본 정리 가능.

검증: 과학 u1 — H 17pt/22.5pt Bold 복원 10건, 2 의 SUB 전량 유지(누락 3→3,
기존 별개 건), 텍스트 런 15709건 내용 동일. 수학 u1 수식 diff 0. 지도서 u2
통계 동일.

## 확장 (2026-07-19, 계수 크기)

계수 다이얼로그("2H₂ + O₂ → 2H₂O")의 선행 계수 2 가 숫자-제외 가드에 걸려
크기 백필을 못 받던 것을 정밀화: **문자·닫는괄호 뒤 숫자만 첨자 후보로 제외**
(H₂의 2, (OH)₂의 2), 그룹 맨 앞·연산자 뒤 숫자(계수)는 백필한다.
검증: 계수 2 → 17pt/22.5pt Bold 복원, 첨자 SUB 전량 유지, 수학 u1 diff 0.

## 미해결: 색상 손실 (별도 원인)

같은 다이얼로그의 원본 색(계수 2 주황 C=0 M=80 Y=100, O 파랑 C=100 M=50 Y=0
— IDML FillColor 로 존재)이 출력에서 검정. 백필 소스인 phase3 ASTTextRun 의
textColor 가 이미 null 임을 디버그로 확인 — RunBuilder(항상 색 설정, 기본
#000000)·ASTRunConverter(fillColor→textColor 해석) 경로가 아닌 **제3의 tr
생성 지점**이 IDML fontSize/fontStyle 만 복사하고 FillColor 를 누락한다.
그 생성 지점 특정이 다음 작업 (fontSizeHwpunits 설정 + textColor 미설정
조합을 만드는 빌더 추적).

## 다음 단계 (선택)

1. (완료) 재현 사이클: 추출물
   `output/issues/중3과학교과서/u1/p008-049-20260719-111323/extract/` 로
   Java 재변환만 반복 (재추출 불필요).
2. 어댑터에 fontSize/fontStyle 복사를 다시 적용한 뒤, SUB 가 사라지는 5개
   프레임(스토리 186510·186538 등 "H2"/"H2O" 단독 프레임)에 대해
   `convertMathRunsInParagraph` 분기(96행 cluster / 106행 simple-positioned /
   140행 그룹 편입)를 디버그 로그로 추적해 어느 경로가 바뀌는지 특정.
3. 해당 경로가 첨자를 보존하도록 수정 (원본 ASTTextRun 유지가 원칙).
4. 검증: 과학 u1 H/2 쌍 전수 비교(SUB 유실 0 + H 17pt Bold), 수학 u1 수식
   diff 0, 지도서 u2 회귀 없음.

## 검증 스크립트 메모

H/2 인접쌍 charPr 비교(높이·bold·`<hh:subscript/>`)는 세션 스크래치의
파이썬 스니펫 참조 — 검출 시 반드시 `<hh:subscript/>` 소문자 요소로 판정할 것.
