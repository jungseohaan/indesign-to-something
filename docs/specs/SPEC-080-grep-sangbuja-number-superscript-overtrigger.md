# SPEC-080: `상부자(이탤릭)` GREP 가 본문 숫자를 위첨자로 깨뜨림

> 상태: **위첨자 제거 구현 완료, 회귀 0 검증**. 2026-07-26.
> 사례: 수학 u1 p14 "제곱하여 **4**가 되는 수는 **2**와 **-2**이다" — 4·2·-2·25 가
> 작은 위첨자로 올라가 깨진다.

## 문제

수학 발문의 본문 숫자(제곱 결과 4·25, 답 2·-2)가 지수처럼 **작은 위첨자**로 렌더된다.
수식도 아니고 정상 본문 크기도 아니다.

## 원인 (계측으로 규명)

문단 스타일(`02_함께 탐구` 등)의 GREP 규칙 `\d+|[\l\u]|[...]` → charStyle
`상부자(이탤릭)`(폰트 EH상부자)이 **본문의 모든 숫자를 무차별 매칭**한다. 원래 지수(²)용인데
`\d+` 가 제곱 결과·답·연도까지 잡는다.

위첨자를 실제로 세우는 지점은 **`IDMLCharacterRun.isSuperscript()`** — position 속성이
없어도 charStyle 이름에 "상부자"가 있으면 true 를 반환한다. 이 값을 두 곳이 소비한다:
1. `MathProcessor.applyPositionFromCharacterStyle`(호출부 102·855) — 이름-폴백 위첨자
2. `RunPostProcessor.emitItalicMathRunsAsText` — 이탤릭 수학 런 텍스트 폴백 시 복사

전수 조사: 위첨자 런 207개, 대부분 `앞[]`(밑수 없음) 본문 숫자·연도(1776)·단위(cm)·
라벨(ABCD)이 오위첨자.

## 해결 (위첨자 제거)

위첨자(지수)는 **밑수 뒤에만** 온다. charStyle 이름만으로(position 근거 없이) 온 위첨자는,
바로 앞 런이 밑수(숫자·라틴 변수·닫는 괄호 `)`·`}`·`]`)로 끝날 때만 유지한다. 밑수 없는
본문 숫자·연도·번호는 본문 크기로 되돌린다.

1. `IDMLCharacterRun.isSuperscriptByStyleNameOnly()` 추가 — position 이 아니라 이름만으로
   온 위첨자 판별 (position=superscript 인 진짜 첨자는 가드 대상 아님)
2. `MathProcessor.applyPositionFromCharacterStyle(tr, prevText)` — 이름-폴백 위첨자에 밑수
   가드. 호출부 102·855 에서 앞 텍스트 런 전달. 진입 시점 superscript(position 근거)는 존중
3. `RunPostProcessor.emitItalicMathRunsAsText` — 같은 밑수 가드

## 미완성 (별도 과제)

사용자 요구 "GREP 에 걸린 숫자를 **수식(hp:equation)으로**"는 미완성. `상부자(이탤릭)` 숫자만
grepMathFont 유지 + `FormulaClassifier` 순수숫자 거부 완화까지 했으나, 숫자가 `flushMathGroup`
의 수식화 지점에 **도달 못 함**(mathGroup 진입·조기반환 4개·다중 거부 층). SPEC-079(x 변수)
보다 훨씬 여러 파이프라인 층을 통과해야 해 층마다 숫자 전용 우회 필요. 되돌리고 후속 과제로.

**안전성**: 과학 화학식 계수·첨자는 `상부자(이탤릭)` 을 전혀 안 쓴다(수식(첨자-하부자)/
[No character style], 전수 0건) — 이 스코프는 화학식과 무관.

## 수정 파일

1. `converter/.../idml/IDMLCharacterRun.java` — `isSuperscriptByStyleNameOnly()`
2. `converter/.../normalizer/resolved/phase3/MathProcessor.java` — 밑수 가드 + 호출부 2곳
3. `converter/.../normalizer/resolved/phase3/RunPostProcessor.java` — 밑수 가드

## 검증

- [x] 수학 u1: 위첨자 **207 → 30**. p14 "4·2·-2·25" 위첨자 아닌 본문 크기.
      **수식 719→719 무변경**(진짜 지수 2²·화학식 온전)
- [x] 과학 u1: 수식 56→56 무변경 — **화학식(CaCO₃·CaCl₂·2HCl) 회귀 0**
- [x] 영어 u1: 무영향 (warnings=0)
- [ ] 한글 육안 확인

## 주의사항

- **`상부자(이탤릭)` = EH상부자 폰트 + charStyle 이름**만으로 위첨자가 되는 게 근본. position
  속성과 무관하다. 진짜 첨자(position=superscript)와 이름-폴백을 구분해야 본문 숫자만 되돌린다
- 남은 위첨자 30개는 밑수 뒤(진짜 지수)이거나 쉼표·단위 등 별개 경로 — 대부분 정당
