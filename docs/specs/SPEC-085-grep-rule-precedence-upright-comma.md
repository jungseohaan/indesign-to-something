# SPEC-085: GREP 규칙 우선순위·직립 서체 반영 + 수식 사이 쉼표 소실 수정

## 문제 (수학 u1 p24, 사용자 보고 2건)

1. "점 A", "두 점 P, Q" 의 단일 대문자와 선분 OA 수식이 **이탤릭**으로 나옴
   — 원본 InDesign 은 직립(로만)
2. "두 점 P, Q" 의 **쉼표가 소실**되어 "P Q" 로 렌더

## 원인

### (a) GREP 규칙 우선순위 무시 → 이탤릭 오적용

문단 스타일의 GREP 규칙(수학 u1 실측):
- 규칙1: `\d+|[\l\u]|[연산자·쉼표…]` → **상부자(이탤릭)** (쉼표 포함!)
- 규칙8: `mm|cm|…|kg|\u` → **상부자(직립, Plain)**

대문자는 두 규칙 모두에 매칭되고 InDesign 은 **나중 규칙이 이긴다** → 원본은
직립. 그러나 파서(`resolveGrepForParagraph`)는 모든 규칙을 불리언 합집합으로만
매칭해 (1) 어느 규칙이 이겼는지 잃고 (2) `applyMathItalicScript` 가 대문자
수식에 무조건 `it` 를 붙여 이탤릭이 됐다.

### (b) 쉼표 소실 — 3연쇄

1. 규칙1이 쉼표에도 수식 charStyle 을 입힘 (원본 조판)
2. 합집합 매칭이라 `P,` 가 한 GREP 서브런으로 뭉치거나, 분리돼도 `,` 런이
   "EH 수식 런" 으로 남음
3. 수식 정규화의 EH 장식 잔여 제거(`planDiscardableEHStructureResidues` →
   `isFractionBarDecoration` = "영숫자 없으면 전부 장식")가 `,` 런을
   근호/분수선 장식 글리프로 오판해 **통째로 삭제**

이 소실은 p24 만이 아니라 문서 전반("옳으면 ◯표, 옳지 않으면", "계산기에서
√,", "데데킨트, J.W.R.,", "점 B, C"→BC 뭉침 등)에 있었다.

## 해결

1. **파서: 규칙별 charStyle 추적 + 나중 규칙 우선** — 문자별 승자 규칙 기록
   (나중 규칙 덮어쓰기), 서브런에 `grepAppliedCharStyle` 로 승자 스타일 기록.
   **경계는 규칙 인덱스가 아니라 적용 charStyle 동일성** — 숫자·소수점이 같은
   이탤릭 스타일이면 `1.4` 는 한 런 (인덱스 비교 시 소수 수식이 `[1][.][4]` 로
   조각나는 회귀 실측)
2. **직립 방출**: `ASTEquation.sourceUpright` 신설. 원본 증거(상부자 비이탤릭
   grep/charStyle)가 직립이면 `applyMathItalicScript` 가 `it` 대신 `rm` 방출.
   설정 지점: 단일 라틴 승격(`MathPlanConverter`), BT/EH flush
   (`applySourceScriptOrientation`), 선분 overline(`applyOverlineOrientation`)
3. **쉼표 보존**: 장식 잔여 판정에 "가시 ASCII 구두점 포함 시 보존" 가드
   (`containsVisibleAsciiPunctuation`)
4. **단위 가드**: 스타일 경계 분할로 `10g` 의 단위 문자가 단독 노출되며
   수식화되던 것 차단 — 파서에서 "숫자 직후 단일 단위 문자(m/g/L/l/t)" 는
   수식 플래그 미부여 + span 승격에도 동일 가드 (실측: 과학 u1 "수산화 바륨 10g")

## 수정 파일

1. `idml/IDMLStoryParser.java` — GrepMathRule(패턴+charStyle), 승자 기반 분할,
   단위 가드
2. `core/ast/ASTEquation.java` — `sourceUpright` 필드
3. `formula/FormulaStyleResolver.java` — sourceUpright → `rm` 방출
4. `normalizer/ASTMathGrouper.java` — `applySourceScriptOrientation` (BT/EH flush)
5. `normalizer/math/MathPlanConverter.java` — 승격 시 이탤릭/직립 증거 판정
   (이탤릭 하드코딩 제거)
6. `normalizer/math/MathSpanPlanner.java` — 단위 가드
7. `normalizer/math/MathStructurePlanner.java` — sourceUpright 복사
8. `normalizer/resolved/phase3/MathProcessor.java` — 장식 잔여 판정 구두점 가드
9. `normalizer/resolved/phase3/RunPostProcessor.java` — overline 방향

## 검증

- [x] 빌드 성공
- [x] p24: "두 점 `rm P`, `rm Q`" — 쉼표 복원 + 직립. "점 `rm A`", 선분
      `rm overline{OA/AP/AB/DF/PQ}` 직립
- [x] 수학 u1 vs SPEC-084 기준: diff 79건 전부 의도한 변화 — it→rm 전환,
      문서 전반 쉼표 복구(visibleChars +144, "점 B, C" 뭉침 `rm BC` 해체 포함),
      단위 g/m 수식 해제. 소수점 조각 회귀 없음
- [x] 과학 u1: 구조 시그니처 **PASS** (완전 일치)
- [ ] 한글 육안 확인

## 조사 기록 (재발 방지)

쉼표 소실 추적은 6단계 파이프라인(스토리 변환→그룹핑→재변환→정규화→배분→방출)
전 구간 계측 이분 탐색으로 확정했다. **같은 문단 인스턴스가 StoryLoader 완료
후 두 번째 `convertMathRunsInParagraph` 를 거치며**(fallback 재변환 경로),
그 안의 장식 잔여 제거에서 소실됐다 — 소실 지점이 "쉼표를 지운다"고 써 있지
않은 일반화된 판정(`영숫자 없음=장식`)일 때는 단계별 identityHash 덤프가
가장 빠른 도구였다.

## 후속 (p14 사용자 보고 3건, 같은 PR)

1. **왼쪽 괄호 소실** `(1/2)²` → `1/2)²`: GREP 분수대문자 스타일의 큰괄호
   글리프 `{`/`}` 가 디코드 없이 리터럴로 수식에 새어 HWP 불가시 그룹 `{}` 이
   됨. 파서 승자 스타일 실행 시 `{`→`(`, `}`→`)` 정규화 (`applyGrepMathWinnerStyle`)
2. **빈박스 사이 쉼표가 박스 스타일(20pt 하늘색)로 커짐**: (a) 수식 경계
   텍스트의 charPr 상속 3중 폴백이 전부 □ charPr 로 귀결 → □ 상속 차단 가드
   (`lastVisibleRunIsAnswerBox` + 상속원 조회 2곳 □ 제외), (b) 그룹 색 상속
   (`groupFill`)에서 □ 제외. 크기 2024→1000 정상화. 잔여: 쉼표 색이 답란
   강조 채널을 따라 하늘색 — 필요시 후속
3. **수식 크기 축소**: GREP charStyle 의 fontSize(태광10.5)가 런에 전달되지
   않아 수식 baseUnit 이 본문(8.5pt) 상속으로 떨어짐 → 파서에서 승자
   charStyle fontSize 를 런에 주입. 잔여: 봉합(stitch) 산출 수식 일부는
   조각 크기 힌트 미전달로 여전히 본문 상속 — 후속
4. 부수 가드: 스타일 경계 분할로 단독 노출된 단위 문자("10g"의 g) 수식화
   차단 (파서+span 이중), 소수점 규칙 분리로 인한 `1.4` 조각 회귀는
   charStyle 동일성 경계로 해소

## 후속 2 (수학 u3 p108, 이탤릭 수식의 rm 오방출)

u3 p108 소제목 "이차함수 y=ax² 의 그래프에는…"의 수식이 `rm y=ax^{2}` 로
직립 방출됐다 (사용자 보고). 원인: `sourceUpright` 판정
(`applySourceScriptOrientation`)이 **스타일 이름 휴리스틱**("상부자" 포함 &
"이탤릭" 미포함 → 직립)에 의존하는데, u3 의 GREP charStyle `상부자13pt(B)` 는
이름에 이탤릭 표기가 없지만 **실제 FontStyle 은 BoldItalic**(EH상부자)이라
직립으로 오판됐다. u1 은 "상부자(이탤릭)" vs "상부자(직립, Plain)" 이름 관례가
지켜져 문제가 없었던 것.

수정: `applyGrepMathWinnerStyle` 이 승자 charStyle 정의의 실제 FontStyle 을
런에 주입(`grepCharStyleFontStyle`, fontSize 주입과 동일 위치)하고,
`applySourceScriptOrientation` 이 이름 휴리스틱보다 이 실측 FontStyle 의
이탤릭 여부를 우선 증거로 쓴다.

검증: 수학 u1(1002수식·rm48)/u5(534수식·rm344) 수정 전후 diff 0 —
P·Q·O·overline 직립 유지. u3 는 968수식 중 오적용 7건만 정확히 rm 해제
(`y=x²`, `y=ax²`×2, `y=ax²+q`, `y=a(x-p)²`, `y=a(x-p)²+q`, `y=ax²+bx+c`),
잔여 rm 41건은 라벨(A/B/C/D)·단위(cm²)·답란(□) 류로 정당. 테스트 만성
기준선(55F/44E) 동일. 잔여 관찰: `rm y=`·`rm y=-` 파편 수식은 별개 경로로
이번 수정 전후 동일 — 필요시 후속.

## 후속 3 (수학 u1 p14, 분수/근호 괄호가 신축 괄호로 확대되지 않음)

"(½)²=¼" 의 괄호가 리터럴 `(` `)` 로 방출돼 분수 높이만큼 늘어나지 않았다
(사용자 보고). 후속 1 의 `{`→`(` 정규화는 소실 방지까지만 했고,
`left( right)` 신축 확대는 EH 방출기(`EHHwpScriptEmitter.
enlargeBracketsContainingFraction`)에만 있어 GREP 분수 복원(SPEC-081 클래스 E)
등 EH 밖 경로 수식은 확대를 못 받았다 — 같은 문장의 "(−½)²"(EH 경로)만
`left(...right)` 로 나가는 비대칭.

수정: 확대 구현을 `EquationBuilder` 로 이동해 **sanitize 공통 관문의 마지막
단계**로 적용 (모든 방출 경로 통과). EH 방출기는 중간 단계 소비자(MathProcessor
조각 분할)를 위해 조기 적용을 유지하되 구현을 위임 — 중복 적용 가드를
`left(`/`LEFT ( `(공백·대소문자 허용)로 보강해 멱등.

검증: 수식 개수 u1 1002/u3 968/u5 534 전부 동일. 변경은 u1 35건·u3 1건
전부 "괄호 안 over/sqrt → left/right 확대" 패턴뿐, u5 diff 0. 테스트 만성
기준선(55F/44E) 동일.
