# SPEC-078 — 파편화된 화학식 재조립 (p26 H₂O 라벨 분리)

## 문제
과학 U1 p26 반응식 다이어그램의 물 라벨 "H₂O" 가 한글에서 **"H₂ O" 로 벌어져** 보인다.
원본 스토리(u146cb `H` `2`(첨자) `‌O`(hair space+O))에서 아래첨자 ₂ 와 O 사이의
**hair space(U+200A)** + 폰트크기 경계 + 첨자 강등이 겹쳐, 변환 결과가

```
[수식 "H"] [텍스트 "2"] [수식 "O"]      (세 조각)
[수식 "rm □H_{2}"] [수식 "O"]           (두 조각)
```

처럼 **여러 조각으로 파편화**된다. 조각 수식들이 간격을 두고 렌더돼 "H 2 O"/"H₂ O" 로 벌어진다.
전체 8-49 에서 바레 단일원소 수식이 O 5개·H 6개 잔존.

## 목표
파편화된 화학식 조각(단독원소 수식 + 숫자/원소 텍스트)을 하나의 `rm H_{2}O` 화학식
수식으로 재조립한다. 과병합(수학·비화학 오합침)은 막는다.

## 해결 방안
`MathProcessor` 에 두 가지 추가:

1. **재조립 패스 `reassembleFragmentedChemicalFormula`** (hasEquation 블록,
   `stitchChemicalFormulaFragments` 직전). 단독원소 수식으로 시작하는 연속 조각
   (단독원소 수식 + 숫자/원소/'+' 텍스트런)을 모아 raw 문자열을 만들고
   `finalizeChemicalScript`(→ `FormulaClassifier.inferChemicalSubscriptScript`)로
   계수/첨자 위치를 추론해 하나의 `CHEM_FORMULA` 수식으로 치환.
   과병합 방지: (a) 시퀀스는 단독원소 수식으로 시작, (b) 라틴 전부 원소기호,
   (c) 알려진 원소 ≥1, (d) 한글/비화학 텍스트를 만나면 즉시 종료.

2. **`stitchChemicalFormulaFragments` 확장** — 선행 `CHEM_FORMULA` 뒤에 떨어져 나온
   단독원소 수식(예 `rm □H_{2}` + `O`)도 흡수하도록 후행 수집에 바레 원소 수식 인정.
   또한 **원소가 확정된 뒤의 단독 숫자 텍스트(꼬리 아래첨자)**도 흡수 — 예 `rm N_{2}` +
   수식 `H` + 텍스트 `4` = N₂H₄(하이드라진), `rm H_{2}O` + `2` = H₂O₂(과산화수소).
   (계수가 아닌 꼬리 첨자라 `chemicalFragmentCommits` 가 false 라 빠지던 것.)

3. **hair space 누수 차단** — `finalizeChemicalScript` 의 공백 제거를 `stripAllFormulaSpaces`
   로 교체. Java `\s` 는 U+200A 를 못 잡아 `rm H_{2} O`(여전히 벌어짐)가 새어나오던 것을,
   `Character.isSpaceChar`(유니코드 공백 포함) 기준으로 전부 제거.

4. **audit-A: 고립 단일문자 수식 강등** — `demoteIsolatedSingleLetterMathEquation`.
   GREP 규칙(단일 라틴 문자 → charStyle `수식서체관련-태광`=BT수식H-편한글씨)이 문단의
   모든 단일 라틴 문자를 수식화해 **화학자 연표 이니셜(A 아보가드로·J 돌턴·L 게이뤼삭 등)**
   까지 단일문자 수식이 되던 것을, 인접에 수식 근거가 없는 단독 단일 문자는 이탤릭
   텍스트로 되돌린다. 반응식 파편의 단일 원소(예 H₂ 좌변의 "H")는 인접 수식이 있어 보존.

## 수정 파일
1. `normalizer/resolved/phase3/MathProcessor.java`
   - `reassembleFragmentedChemicalFormula` + `isBareChemicalElementEquation`
     + `chemicalFragmentRawText` + `isBareChemicalElementScript` 추가
   - `stitchChemicalFormulaFragments` 후행 수집에 바레 원소 수식 인정
   - `stripAllFormulaSpaces` 추가, `finalizeChemicalScript` 에서 사용

## 검증
- [x] fresh 8-49 재변환 → 물 라벨 `rm H_{2}O` 로 통일. 바레 O 5→0, 바레 H 6→2(정상 잔여)
- [x] SPEC-078 격리 diff = 전부 의도한 재조립 (파편 소실 → 온전 화학식 추가), hair space 누수 0
- [x] `rm H_{2}O` 계열 12→15, SPEC-077 `rm 2HCl` 유지
- [ ] 한글 육안 확인 (사용자) — 물/이산화탄소 등 라벨이 안 벌어지는지

## 관찰 (별건)
- `rm N_{2}H`, 선두 orphan `_{2}` 같은 경계 케이스는 재조립으로 개선됐으나 완벽하진 않음
  (원본 파편 자체가 불완전). 반응식 계수·첨자 정밀화는 후속 과제.
