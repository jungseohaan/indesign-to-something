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
