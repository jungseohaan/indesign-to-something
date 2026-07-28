# SPEC-084: 고립 단일 라틴 강등 규칙 폐기

## 문제

SPEC-078 audit-A 에서 도입한 고립 단일 라틴 강등(인접 수식 없는 단일 라틴
equation → 이탤릭 텍스트)이 과보호로 판정됐다:

- 보호 대상이던 오검(과학자 연표 이니셜 A/J/L 의 수식화)은 시각 피해가 작다
  (이탤릭 단일 문자 수식 — 원본도 GREP 로 수식 서체가 입혀져 조판됨)
- 반면 강등의 부수 피해가 더 크다: **단독 A/B/C/D(기하 점 라벨 "점 A",
  보기 라벨 등)가 수식화되지 않음** (수학 u1 p24 "점 A" 사례, 사용자 판정)
- SPEC-079 는 이탤릭 소문자만 예외로 살렸지만 대문자 라벨은 계속 강등됨

## 목표

강등 규칙 자체를 폐기한다. GREP 증거로 수식화된 단일 라틴 문자는 대소문자·
인접 문맥과 무관하게 수식으로 유지한다. (과학자 이니셜도 수식으로 남는 것을
감수 — 사용자 결정 2026-07-28)

## 해결 방안

3단계 파이프라인 Stage 1 의 converted-items 강등 계획(`planConvertedItems`,
reason="isolated-single-latin-without-math-context")을 제거한다.
승격 경로(`plan()` — 이탤릭/GREP 증거 단일 라틴 텍스트 → 수식)는 유지.

## 수정 파일

1. `normalizer/math/MathSpanPlanner.java` — `planConvertedItems` 강등 로직 제거
2. `normalizer/math/MathPipeline.java` — CONVERTED_ITEMS 분기에서 span 단계 제거
3. `normalizer/math/MathSpanPlannerTest.java` — 강등 테스트 제거

## 검증

- [x] 빌드 성공 — 테스트 실패 수가 기준선(만성 55F/44E)과 동일, 신규 실패 0.
      강등을 단언하던 테스트 3건은 새 정책으로 갱신
- [x] 수학 u1 재변환: 수식 976→1002 (+26, 전부 단일 라틴 `it A`/`it K`/`a`/`x` 류).
      "수직선 위의 점 A" 의 A 가 수식으로 유지됨 확인. 수식 소실 0
- [x] 과학 u1 재변환: 연표 이니셜 6곳(`it H/A/L/J/J/I`)이 수식화되는 것 외 회귀 0
      (SPEC-078 audit-A 가 막던 바로 그 케이스 — 감수 결정)
- [ ] 한글 육안 확인

## 관계

- SPEC-078 audit-A(단일문자 오수식화 강등)를 폐기 — 본 SPEC 이 대체
- SPEC-079(강등에서 이탤릭 소문자 제외)는 강등 자체가 사라져 자연 무효화
  (이탤릭 변수 x 승격 경로는 그대로 유효)
