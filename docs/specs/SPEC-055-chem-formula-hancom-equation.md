# SPEC-055: 화학식 전면 한컴 수식(hp:equation) 변환

## 문제

수학 수식(BT/EH/NP 폰트 그룹)은 이미 `EquationBuilder.fromHwpScript` 로
**hp:equation(한컴 수식)** 으로 방출된다. 그러나 화학식은:

1. **출력 강등**: 판정에 성공해 `ASTEquation("CHEM_FORMULA")` 가 되어도, 출력
   단계 `InlineItemDispatcher.addEquationRun` 이
   `usesBodyTextEquationStyle`(= sourceType==CHEM_FORMULA 이면 무조건 true)
   → `FormulaRenderer.toChemicalTextRuns` 로 **첨자 CharPr 텍스트런으로 강등**
   한다 (SPEC-042 계열, 본문 크기·색 어울림을 위한 결정).
2. **판정 누락**: 화학식 판정(`ChemicalFormulaPolicy`)이 "수식폰트(BT)/화살표
   증거"를 필수 조건으로 요구한다. **일반 본문 폰트 + 첨자 문자속성**(position=
   SUBSCRIPT 또는 하부자 charStyle)으로만 조판된 화학식(본문 속 H₂O, CO₂ 등)은
   수식화 대상에 아예 들어가지 않고 첨자 텍스트런으로 남는다
   (`RunBuilder.applyPositionStyle` 경로).

## 목표

**화학식으로 판정되는 모든 조각을 hp:equation(한컴 수식)으로 방출한다.**
수학 수식은 현행대로 한컴 수식 유지. 화학식 여부가 불확실한 것은 기존
보수 원칙대로 텍스트로 남긴다(억지 수식화 금지 원칙은 "증거 없는 글리프"에
한해 유지).

## 현재 구조 (분석 결과)

### 판정·AST 단계
| 지점 | 역할 |
|------|------|
| `phase3/ChemicalFormulaPolicy` | 문단 단위 보수 판정: ①수식폰트(BT)/화살표 증거 ②아래첨자 ③원소기호 ④수학구조 문자 없음 — 4조건 AND. resolved 런 우선, IDML 런 폴백(표 셀) |
| `phase3/MathProcessor` + `ASTMathGrouper` | BT/EH/NP 런 그룹 → hwpScript 생성 → `ASTEquation("CHEM_FORMULA")` (또는 수학 ASTEquation). `containsNonChemicalFormulaKeyword` 로 각/도(°) 조각 제외 (SPEC-051) |
| `phase3/RunBuilder.splitChemicalFormulasAndLatinVarsInMixedText` | 혼합 텍스트에서 화학식/LATIN_VAR 조각 분리 (이탤릭 대문자 라벨 AB/ABC 는 제외) |
| `phase3/RunBuilder.applyPositionStyle` | 첨자 문자속성 → ASTTextRun.subscript/superscript (수식화 안 됨 — **누락 경로**) |
| `formula/FormulaClassifier·FormulaDecision·FormulaStyleResolver·FormulaRenderer` | 분류·스타일·텍스트런 렌더 |

### 출력 단계 (`converter/InlineItemDispatcher.addEquationRun`)
```
ASTEquation
 ├─ 단독 로마숫자 → 텍스트런 (유지)
 ├─ CHEM_FORMULA → toChemicalTextRuns → 첨자 텍스트런  ← [강등, 제거 대상]
 └─ 그 외(수학)  → EquationBuilder.fromHwpScript → hp:equation ✓
```
CHEM_FORMULA 의 hwpScript 는 이미 HWP 수식 문법(`_`/`^`/`rarrow`)으로 생성돼
있어 hp:equation 방출에 추가 변환이 필요 없다.

## 해결 방안 (3단계)

### Phase A — 출력 전환 (핵심, 저위험)
`InlineItemDispatcher.addEquationRun` 에서 CHEM_FORMULA 강등 분기 제거 →
수학 수식과 같은 hp:equation 경로로 방출.
- 스타일: 기존 `resolveFormulaStyle` 이 CHEM_FORMULA 의 본문 힌트
  (`preferredBaseUnit`/`preferredFontFamily`/`textColor`, SPEC-042 백필)를
  이미 소비하므로 재사용 — 본문 크기·색 어울림 유지.
- 강등이 존재했던 이유(본문 줄흐름·크기)를 검증으로 확인: 수식 개체는
  "글자처럼 취급" 인라인이므로 줄높이 영향은 크기 추정(`estimateRenderedWidthChars`
  /height multiplier)에 달려 있다. 첨자만 있는 화학식은 분수가 없어
  NORMAL_EQUATION_HEIGHT — 문제 시 첨자 전용 height 보정 추가.
- `toChemicalTextRuns`/`normalizeChemicalTextScript` 는 제거하지 않고 미사용
  전환 (회귀 시 되돌림 용이).

### Phase B — 판정 커버리지 확장 (첨자 문자속성 화학식)
일반 폰트 화학식 세그먼트를 수식화한다. 문단 전체가 아니라 **세그먼트 단위**
("물(H₂O)은" 에서 H₂O 만).
1. `RunBuilder` 첨자 경로에 세그먼트 감지 추가: 연속 런 시퀀스가
   `[계수?][원소기호][첨자 숫자] ...` 패턴 + 아래첨자 문자속성 증거 + 원소기호
   화이트리스트 + 수학구조 문자 없음이면 화학식 세그먼트.
   - 보수 가드 유지: 첨자 증거 필수 (첨자 없는 "CO" 단어는 대상 아님),
     이탤릭 대문자 라벨(AB/ABC) 제외 재사용, `containsNonChemicalFormulaKeyword`
     재사용.
2. 세그먼트 → hwpScript 생성기: 원소·계수·`_`첨자·`^`이온전하·`rarrow`·`+`·
   상태표기 `(g)/(l)/(s)/(aq)`·수화물 `·`. 기존 CHEM_FORMULA 스크립트 문법과
   동일 규약.
3. `ASTEquation("CHEM_FORMULA")` 로 방출 → Phase A 경로로 hp:equation.
4. 기존 트랩 회귀 방지: SPEC-045(계수 오첨자), SPEC-046/047(text-range-shell),
   SPEC-050(overline 분리 제외), SPEC-042(CharPr 캐시 키) 케이스를 검증 목록에
   포함.

### Phase C — 검증 (text-only 모드 사용)
개발 루프는 **text-only 모드로만** 추출/변환한다 (SPEC-054, 사용자 지시).
화학식은 텍스트 소유 산출물이므로 text-only 로 완전 검증 가능.

## 수정 파일 (예상)

1. `converter/InlineItemDispatcher.java` — CHEM_FORMULA 강등 분기 제거 (Phase A)
2. `formula/FormulaStyleResolver.java` — usesBodyTextEquationStyle 소비처 정리
3. `phase3/ChemicalFormulaPolicy.java` — 첨자 문자속성 세그먼트 판정 추가 (Phase B)
4. `phase3/RunBuilder.java` — 첨자 화학식 세그먼트 → ASTEquation 방출 (Phase B)
5. (필요 시) `converter/HwpxParagraphBuilder.java` — 첨자 수식 height 보정

## 검증

- [ ] 빌드 성공
- [ ] Phase A: 과학 u1 p47(2H₂O 반응식 표), p25, p17, p20, p26/28 — 기존
      CHEM_FORMULA 가 hp:equation 으로 나오고 크기·색·줄흐름 정상
- [ ] Phase A 회귀: u5 p166 (SPEC-050/051 — 각·도(°) 조각이 수식화되지 않음),
      수학 수식 페이지 무변화
- [ ] Phase B: 본문 속 일반폰트 H₂O/CO₂ 등이 hp:equation 으로 방출
- [ ] Phase B 회귀: 이탤릭 대문자 라벨(AB), 영단어 대문자, SPEC-045 계수 케이스
- [ ] 전체 u1 text-only 추출/변환 → 화학식 페이지 육안 + 텍스트 카운트 비교
