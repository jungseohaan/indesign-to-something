# SPEC-059: 반응식 좌변 잘림 — 화살표 시작 클러스터의 좌변 흡수

> 상태: **완료** — 구현·기준선 대조 검증 통과. 2026-07-23.
> 브랜치 `fix-equation-lhs-truncation`.

## 문제

과학 u1 문제 보기의 화학 반응식들(`H₂+O₂ → H₂O`, `Cu+O₂ → 2CuO`,
`CH₄+2O₂ → CO₂+2H₂O`, `CaCO₃+2HCl → …` 등)이 **좌변은 일반 텍스트**로,
수식은 `~ rarrow ~ 우변` 만 남는 형태로 갈라짐.

## 원인

- PR #107(inline math option ownership)이 다중 TF 그룹의 통짜 PNG 텍스트
  소유를 hwpx 로 전환(의도적: p27 수식 선택지 편집성). 그 결과 이 반응식
  텍스트가 처음으로 hwpx 수식 변환 경로를 타게 됨. (#105 기준선까지는
  통PNG 여서 이 경로를 안 탔음 — bisect 로 #105 정상/#107 회귀 확정)
- 그 경로(`MathProcessor.convertMathRunsInParagraph`)의 기존 약점:
  화살표(→)는 정규화에서 폰트가 벗겨져 `collectFormulaEquationCluster` 가
  **화살표에서 시작**하고 앞으로만 수집 → 좌변 미포함. 좌변(BT 폰트 그룹)은
  `isSimplePositionedTextRun` 등 여러 flush 지점에서 조각나 텍스트로 방출됨.
  (SPEC-058 의 박스 반응식 미변환과 같은 뿌리)

## 수정 (`MathProcessor.java`)

클러스터 스크립트가 화살표로 시작하면 좌변을 흡수:
1. `collectTrailingEquationFontRuns` — 이미 방출된 newItems 꼬리에서 수식
   폰트(BT/EH/NP) 또는 첨자 런을 역방향 수집 (클러스터 문법 텍스트 한정,
   경계 텍스트에서 중단, 선두 공백은 본문에 남김).
2. 대기 중인 mathGroupSrc(pending BT 그룹)와 연결해
   `buildFormulaScriptFromEquationFontRuns` 로 좌변 스크립트 생성
   (`appendFormulaScript` 재사용 — 첨자 `_{}` 포함).
3. 성공 시 흡수한 꼬리 아이템 제거 + 그룹 클리어 + 클러스터 스크립트 앞에
   결합. 실패 시 기존 flush 폴백 (동작 무변화).

판정은 내용 휴리스틱이 아니라 **수식 폰트/첨자 증거** 기반 (SPEC-058 방침).

## 검증

- 회귀 5건 전부 좌변 복원: `H_{2}+O_{2} ~ rarrow ~ H_{2}O`,
  `Cu+O_{2} ~ rarrow ~ 2CuO`, `H_{2}O_{2} ~ rarrow ~ 2H_{2}O+O_{2}`,
  `CH_{4}+2O_{2} ~ rarrow ~ CO_{2}+2H_{2}O`,
  `CaCO_{3}+2HCl ~ rarrow ~ CaCl_{2}+CO_{2}+2H_{2}O` — #105 기준선과 의미 동일
- 기존 수식 무회귀 (`N_{2}+3H_{2} ~ rarrow ~ 2NH_{3}` 등), p19 답란·게이지·
  유령 테이블 회귀 없음

## 메모

- p18 `H₂O₂ → …` 2분할(`H_{2}O_{2} ~ rarrow ~` + `H_{2}O+O_{2}`)은 #105
  이전부터 있던 별개 현상 (다행 수식 분할) — 본 SPEC 범위 아님.
- SPEC-058(박스 반응식 통짜 수식화)은 여전히 보류 — 본 수정의
  `collectTrailingEquationFontRuns` 가 재개 시 재사용 후보.
