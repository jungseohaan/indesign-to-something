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

## 완성 (2026-07-19, 원본 런 재사용)

`reuseOriginalRunsForPlainFallback` — MathProcessor 재그룹핑의 flush 가 소스와
1:1 동일 텍스트의 평문 런만 낸 경우, 손실 복제본을 버리고 **원본 ASTTextRun
(크기·굵기·색·첨자 보유)을 그대로 재사용**한다. 텍스트 정리/디코딩이 일어나
불일치하거나 수식·분절 산출이 있으면 기존 폴백 산출을 쓴다(자기방어 가드).

이로써 잔여 2건 해소:
- 텍스트-flow 계수 2 → 17pt/22.5pt Bold 복원
- 색상 → 계수 주황(#df542b)·O 파랑(#0074b1) 등 원본 색 복원 (flush 폴백
  컬러 배관 b274e179 와 합작 — 배관이 tr 에 색을 싣고, 재사용이 보존)

검증: 계수·H·첨자 시퀀스 색·크기 원본 일치, H,2 SUB 무결(3/39), 과학 u1
텍스트 100% 동일, 수학 u1 수식 diff 0 + 텍스트 동일, 지도서 u2 통계 동일.

## 추가 수정 (2026-07-19, IDML-resolved 색 충돌)

p17 상단 반응식 2건에서 H₂ 가 파랑으로 남던 문제: **IDML 로컬 속성(파랑)과
resolved DOM 계산값(빨강)이 충돌**하는 데이터였다 — 인쇄는 빨강(사용자 확인).
수식 그룹 런은 RunBuilder(resolved 권위) 경로를 우회하므로 IDML 로컬이 그대로
나가던 것. StoryLoader 의 그룹 진입 지점에서 매칭 resolved run 의 fillColor 를
주입해 resolved 권위를 복원했다. 제약 2가지:

- `findResolvedRun` 은 `ctx.lastMatchResult` 공유 커서를 갱신 → 저장/복원 필수
  (누락 시 본류 매칭이 밀려 이웃 런 크기가 드리프트)
- **무채색(회색조 hex)은 주입하지 않음** — 기본 텍스트에 명시 색이 실리면
  CharPr 생성 경로가 바뀌어 크기 계승이 깨진다. 목적은 유채 강조색 보존.

검증: 유채 시퀀스 전부 #df542b 통일(파랑 0), 검정 컨텍스트 크기 원복(1100),
SUB 무결(3/39), 텍스트 동일, 수학 u1 diff 0 + 텍스트 동일, 지도서 u2 통계 동일.

## p46·p47 반응식 박스 (2026-07-19, 해결)

확인문제의 반응식 박스("N₂+3H₂ → 2NH₃", "2H₂+O₂ → 2H₂O")가 첨자 없는
평문 + 원시 화살표 글리프("?C")로 나오던 문제. 원인은 두 겹:

1. **화살표 정규화 비대칭이 셸 게이트를 깨뜨림** — `ResolvedDataReader.parseRun`
   은 story 런의 화살표 글리프를 "→" 로 정규화하지만 `frameVisibleText` 는 원문
   ("C"/"@"/"@C"/"?C")을 그대로 가진다. 인라인 셸의 구조 보존 경로 게이트
   (`InlineFrameHandler.canMaterializeShellTextFromWholeStory`)가 두 텍스트의
   동일성을 요구하므로 화살표 든 프레임은 **항상** 평탄화 경로
   (`addSyntheticRunsFromTextFrame`, 단일 소스런 통짜 텍스트)로 떨어져 런 구조
   (첨자 position, 화살표 폰트)를 잃었다.
   → `frameTextMatchesStoryModuloArrowGlyphs`: storyText 의 "→" 자리에 원문
   글리프 후보(@C|?C|@|C|→)만 허용하는 비교를 추가. **스토리에 실제 BT화살표
   폰트 런이 있을 때만** 적용 — 자유 와일드카드(`.{1,2}`)로 하면 본문에 진짜
   "→" 문자를 쓰는 수학 u1 에서 내용이 다른 프레임까지 잘못 통과해 수식
   그룹핑이 깨졌다 (실측 54건 회귀 → 조건 강화로 0건).

2. **수식 폰트 CharPr 캐시 키에 첨자 누락** — 게이트를 고치자 p47 에서 H 가
   첨자, 2 가 일반으로 뒤바뀌는 스왑이 드러났다. AST 는 정상(계측 확인)이고,
   범인은 `CharPrFactory.createEquationFontCharPr` 의 `eqFontCharPrCache` 키가
   base|폰트|크기|색|shade 만 포함하고 **subscript/superscript/fontStyle 을
   제외**한 것. base 는 "직전 런의 charPr" 라 체인이 문서 전반에 걸쳐 이어지는데,
   p46 박스가 만든 체인(N→755, 2˅→757, +3→758 …)을 p47 박스가 같은 키로
   재사용하면서 한 칸 어긋난 속성을 물려받았다 (H 가 첨자 757, 첨자 2 가 일반
   758). → 키에 fontStyle/letterSpacing/characterStyleRef/subscript/superscript
   전부 추가 (키 분할은 항상 안전 — 병합만 위험).

검증: 과학 u1 — HEAD 대비 텍스트 diff 가 정확히 목표 박스 2곳뿐, 전 반응식
첨자가 숫자에만 적용(숫자外 SUB 런 0), ?C 잔존 0. 부수 효과로 p46 02번 문제
보기의 반응식 5건(CH₄, CaCO₃ 등)도 평문→구조화 복원. 수학 u1 — HEAD 대비
수식 diff 0 + 텍스트 100% 동일. (지도서는 수식이 없어 검증 대상 아님 — 사용자
확인.)

디버깅 노트: AST 세터 계측(text/subscript)이 전부 침묵인데 HWPX 에 SUB 가
있으면 **CharPr 캐시/공유 층**을 의심할 것. `StyleRegistry.nextCharPrId` 에
특정 id 트랩을 걸면 생성 주체 스택을 바로 잡을 수 있다.

## 미해결 (잔여)

- (해소됨 — 원본 런 재사용) ~~텍스트-flow 계수~~
- **색상**: 전체 메커니즘 규명 완료(2026-07-19), 완성은 후속.
  - IDML FillColor 는 일부 런에만 명시(계수 2 주황, O 파랑) — H 는 상속이라
    IDML run 속성에 없음 (resolved DOM 은 상속 계산값을 갖지만 매칭 실패).
  - flush 폴백에 컬러 리졸버를 배관해 tr 색 주입까지는 동작 확인
    (`flushMathGroup/flushEHMathGroup` colorToHex 오버로드, 이번 커밋).
  - 그러나 **MathProcessor 재그룹핑이 색 실린 tr 을 폐기**하고 새 런을 만들며,
    새 런의 색 백필은 숫자-스킵 제약(첨자 정렬 보호)에 걸려 색이 소실된다.
    textColor 세터 계측으로 덮어쓰기 0 확인 — 유실은 런 교체에 의한 것.
  - 완성 경로: 재그룹핑에서 원본 tr 보존(교체 대신 재사용) 또는 첨자 정렬을
    속성 무관하게 만드는 것. 숫자-스킵 제약과 동시에 풀어야 한다.

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
