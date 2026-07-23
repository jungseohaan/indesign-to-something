# SPEC-066: 답란 빈칸이 낀 영어 산문의 수식 오변환 — 영어 u3 p62

> 상태: **완료** — 구현·한글 육안 확인, 과학 골든 게이트 PASS. 2026-07-23.
> 브랜치 `fix-eng-u3-p62-prose-equation`.

## 문제

영어 u3 p62 요약 문장 "□ Marketing words can push people to make a quick choice
by creating a sense of ¹___." 이 통째로 HWP 수식(hp:equation)으로 변환됨.
결과: 이탤릭 수식체 + **단어 사이 공백 전부 소실**
(`Marketingwordscanpushpeopletomakeaquickchoice…`). 같은 페이지에 2건.

## 원인

`FormulaClassifier.shouldMaterializeChemicalEquation` 의 조기 확정 규칙:

```java
if (hasArrow || hasBox) return true;   // ← 내용 무관 즉시 수식화
```

해당 문단이 이 규칙에 걸린 경로:
- 답란 앵커(빈칸)가 `isFormulaAnswerPlaceholder` 로 □ 로 수집 → `hasBox=true`
- 문제 번호 위첨자 "1"(charStyle `5_Grammar_문제 번호(위첨자)`)이 `^{1}` 로 스크립트에 편입

즉 "□ 하나면 무조건 화학 반응식"이라는 전제가 **영어 산문 + 답란 빈칸** 조합에서
깨진다. 화학식(H₂O, CaCO₃)을 노린 규칙인데 자연어 문장을 배제하는 조건이 없었다.

## 수정 (`FormulaClassifier`)

`looksLikeProseWithBlank(hwpScript)` 가드를 조기 확정 앞에 배치:

- 수식 구조 토큰(` over `/`sqrt`/`root`/`rarrow`/`overline`/` TIMES `/` div `)이
  하나라도 있으면 산문 판정하지 않음 (구조 근거 우선).
  `^{}`/`_{}` 는 문제 번호 위첨자로도 쓰이므로 구조 근거에서 제외.
- **원소기호로 볼 수 없는 4자 이상 라틴 낱말이 3개 이상**이면 산문 → 수식화 거부.
- 원소기호 판정: 4자 이상 구간이라도 대문자가 2개 이상이면(NaCl, CaCO 등)
  원소 나열로 보아 낱말 수에서 제외.

## 검증

- 영어 u3: 수식 2개 → 0개, 문장이 공백·답란 밑줄 포함 정상 텍스트로 복원
- 과학 u1: **골든 게이트 PASS** (수식 69개 그대로 — 화학 반응식 무영향)
- 영어 u1: 변화 없음

## 메모

- "증거 하나로 즉시 확정"하는 분류 규칙은 다른 교과서/언어에서 반례가 나온다.
  SPEC-060(포괄 supersede), SPEC-064(가드 오탐)와 같은 계열의 교훈.
- 화학식 판정은 원소기호 토큰 밀도가 핵심 신호다. 산문/화학식 경계 판정이
  추가로 필요해지면 이 헬퍼를 확장할 것.
