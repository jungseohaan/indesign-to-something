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

## 실패 기록 4·5: 계수 크기 백필과 색 백필 (2026-07-19, 되돌림)

계수 다이얼로그("2H₂O")의 선행 계수 2 에 크기를 백필하는 정밀화(문자 뒤
숫자만 첨자 후보로 제외)를 시도했으나:

- 같은 문단의 **이웃 첨자 보강 정렬이 밀려** H₂ 단독 프레임 5건에서 첨자
  유실 재발, H₂O 트리플 2건에서 SUB 가 O 로 오배치.
- 첨자 후보 숫자에 **색만** 백필해도 동일하게 정렬이 깨짐.

결론: 문단 안에 "아직 하류 첨자 보강을 기다리는 숫자 런"이 하나라도 있으면,
그 문단의 다른 숫자 런에도 어떤 속성도 선주입해선 안 된다. 하류 보강의
정체는 여전히 미특정 — CharPrFactory 는 AST subscript 플래그를 그대로 쓰는
것 확인(무혐의), AST 후처리 어딘가에서 숫자 런 시퀀스를 정렬 기반으로
매칭하는 것으로 추정. **이 보강 지점을 특정하기 전에는 계수 크기·색 수정을
재시도하지 말 것.** (커밋 dfb67406 → revert)

미해결로 남는 문제: 계수 2 크기(17pt Bold 미복원), 화학식 색상(주황 계수·
파랑 O — IDML FillColor 가 phase3 tr 에 없음, 제3의 tr 생성 지점 누락).

## 하류 첨자 보강의 정체 규명 + 힌트 백필 (2026-07-19, 구현)

ASTTextRun.subscript 세터에 스택트레이스를 심어 전 지점을 실측:

1. `RunBuilder.applyPositionStyle` — resolved position (빌드 시점)
2. `ASTMathGrouper.emitSimplePositionedTextRun` — 단독 첨자 런 (flush 시점)
3. **`FormulaRenderer.toChemicalTextRuns`** — CHEM_FORMULA 수식을 HWPX 단계
   에서 편집 텍스트런으로 재구성하며 **위치 기반**(원소기호 뒤 숫자→첨자,
   맨앞/연산자 뒤 숫자→계수)으로 첨자 부여. 출력 equations=0 인 이유이자,
   "숫자 런 시퀀스 정렬 보강"의 정체.

구현: `backfillChemicalEquationStyleHints` — flush 가 낸 CHEM_FORMULA 수식에
원본 런의 크기·색을 `preferredBaseUnit`/`textColor` 힌트로 주입.
`FormulaStyleResolver.resolve` → `FormulaRenderer.addTextRun` 이 소비해
화학식 텍스트런 전체(계수 포함)가 원본 크기로 나온다. 게이트
(`usesBodyTextEquationStyle`)는 sourceType 만 보므로 힌트 주입은 흐름 불변.

검증: H,2 쌍 전량 (17pt/22.5pt Bold + SUB) 복원, SUB 누락 3→3(무회귀),
수학 u1 diff 0.

## 미해결 (잔여)

- **텍스트-flow 계수 5건**: 수식을 경유하지 않는 폴백 텍스트 흐름의 계수 2 는
  여전히 소형. 계수 텍스트 백필은 같은 문단 첨자와 양방향 상호작용으로 유실
  유발(실패 기록 4·5) — FormulaRenderer 흐름과 달리 텍스트 흐름의 첨자
  부여 경로가 재그룹핑 재진입에 의존하는 것으로 추정, 별도 분석 필요.
- **색상**: phase3 tr 에 IDML FillColor 미도달(제3의 tr 생성 지점 누락).
  힌트 백필의 색 채널은 준비돼 있어 tr 색만 살리면 자동 적용된다.

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
