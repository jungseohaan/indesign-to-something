# SPEC-077 — 문맥 기반 화학식 형제 승격 (첨자 없는 반응식 라벨)

## 문제
과학 U1 p26 반응식 다이어그램 `CaCO₃ + 2HCl → CaCl₂ + CO₂ + H₂O` 에서 각 성분이
개별 라벨 TextFrame 이다. **CaCO₃·CaCl₂·CO₂·H₂O 는 `hp:equation` 으로 승격**되는데
**"2HCl" 만 평범한 텍스트로 남아** 형제와 폰트·조판이 어긋난다.

원인: `ChemicalFormulaPolicy` 가 화학식을 **아래첨자 유무**로만 판정한다
([ChemicalFormulaPolicy.java:85-86,124](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/ChemicalFormulaPolicy.java#L85-L86)):
```java
if (!hasArrowOrMathFont) return false;
if (!hasSubscript) return false;   // ← 첨자 없는 화학식 전부 탈락
```
그리고 근본적으로 MathProcessor 는 **수식 폰트(BT/EH) 런이 있어야** 수식을 만드는데,
첨자가 없는 "2HCl"(계수 2 + HCl)은 첨자 charStyle(`00_수식(첨자-하부자)`)이 없어
수식 폰트 런이 0개 → MathProcessor 미개입 → 평범한 텍스트.

첨자를 유일한 화학식 표지로 삼는 설계는 "수학 수식 오판 방지"([주석](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/ChemicalFormulaPolicy.java#L18-L21))
때문인데, 부작용으로 **첨자 없는 화학식(HCl·NaCl·2HCl·KI 등)이 false negative**.

## 목표
같은 반응식 다이어그램의 형제 라벨이 화학식으로 인식되면, 첨자 없는 화학 라벨(2HCl)도
같은 다이어그램의 일부로 보고 `hp:equation` 으로 승격한다. 과승격(수학 오판)은 막는다.

## 신호 (문맥 = 같은 부모 그룹)
resolved `parentId` 가 신뢰 신호다. 실측(p26):
- `2HCl`(83587) `parentId`=**152383**
- `CaCO₃`(83562) `parentId`=**152383** ← 같은 그룹(반응물 좌변)
- `CaCl₂`(83610) `parentId`=152384 (생성물 우변)

즉 **부모 그룹 152383 안에 확정 화학식(CaCO₃)이 있으면, 같은 그룹의 2HCl 도 화학식**.

## 해결 방안
1. **사전 패스** (phase3 프레임 처리 초입, 1회): 모든 라벨 TextFrame 을 `parentId` 로 묶어
   `Map<parentId, boolean hasConfirmedChemFormula>` 구축. 확정 판정은 기존
   `ChemicalFormulaPolicy.isChemicalFormula*`(첨자+수식폰트+known element+!mathStructure).
   결과를 `ResolvedBuildContext` 에 `chemicalFormulaSiblingGroupIds: Set<String>` 로 저장.
2. **승격 게이트 완화** (문맥 한정): `MathProcessor.convertMathRunsInParagraph` 에서,
   현재 프레임 `parentId ∈ chemicalFormulaSiblingGroupIds` 이고 문단이
   **첨자 없는 화학 후보**(`containsKnownChemicalElement` && `!containsMathStructure`
   && 짧은 라벨(예: ≤ N자) && 아라비아 계수+원소기호 패턴)면 → 수식 승격.
3. **승격 실행**: 첨자 없는 평문 화학식(예 "2HCl")을 `rm 2HCl` HWP 수식 스크립트로 빌드하는
   경량 경로 추가(기존 화학식 이모터 재사용; 첨자 `_{}` 없이 계수·원소만). 원본 폰트 size/color
   메타는 형제 승격 경로와 동일하게 전달.

## 과승격 방지
- 승격은 **부모 그룹에 확정 화학식이 있을 때만** (전역 아님).
- `containsKnownChemicalElement` + `!containsMathStructure` + 짧은 라벨 가드 유지.
- 한글/캡션 형제("탄산 칼슘")는 원소기호 없음 → 미승격. 수학("A=B") → mathStructure 로 차단.

## 수정 파일
1. `normalizer/resolved/ResolvedBuildContext.java` — `chemicalFormulaSiblingGroupIds` 필드 추가
2. `normalizer/resolved/phase3/StoryConverter.java`(또는 phase3 초입) — 사전 패스로 그룹 맵 채움
3. `normalizer/resolved/phase3/ChemicalFormulaPolicy.java` — 첨자 없는 화학 후보 판정 헬퍼
   (`isSubscriptlessChemicalLabelCandidate`) 추가
4. `normalizer/resolved/phase3/MathProcessor.java` — 문맥 승격 게이트 + 평문 화학식 이모트

## 구현 노트
- 승격 게이트를 세 겹으로 좁혀 over-trigger 를 막았다:
  1. **부모 그룹 문맥**: 같은 `parentId` 에 확정 `CHEM_FORMULA` 형제가 있어야 함.
  2. **첨자 속성 런 제외**: `ASTTextRun.subscript()/superscript()` 가 있으면 flatten 금지.
  3. **선두 계수만 허용**: 문자 뒤 숫자(=아래첨자)가 있으면 후보 탈락.
     → "2HCl"(계수 2 + 원소) 승격, "2H2O"(H 뒤 2=첨자)는 제외.
- 별건 관찰: 반응식 물 라벨 "2H₂O" 는 원본에서 ₂ 가 첨자 charStyle(`00_수식(첨자-하부자)`)
  인데도 AST 런에 첨자가 전파되지 않아 평문 "2H2O" 로 렌더된다(첨자 소실). 이는 SPEC-077
  범위 밖의 **별도 첨자 전파 이슈** — 위 3번 가드로 SPEC-077 이 이를 flatten 하지 않게만 막았다.

## 검증
- [x] 8-49 재변환 → "2HCl" 이 `<hp:script>rm 2HCl</hp:script>` 로 승격 (승격 1건, 정확)
- [x] 과승격 회귀 없음: 골든 게이트 diff = 의도한 3건뿐
  (`수식 추가 rm 2HCl` + 셀 `[1x1|2HCl염산]→[1x1|염산]`). 골든 갱신 완료, 재검증 PASS
- [x] 물 라벨 "2H2O" 오승격 제외 확인 (선두계수 가드)
- [ ] 한글 육안 확인 (사용자) — 탐구 다이어그램에서 2HCl 이 CaCO₃/CaCl₂ 와 동일 조판인지
