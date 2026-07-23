# SPEC-065: 문자 스타일 색이 resolved DOM 오보고에 덮이는 문제 — 영어 u1 p15

> 상태: **완료** — 구현·과학 골든 게이트 PASS. 2026-07-23.
> 브랜치 `fix-eng-p15-charstyle-color`.

## 문제

영어 u1 p15 대화문 "What can I do to **stay in shape**?" 에서 `@색자` 문자 스타일이
적용된 "stay in shape" 가 강조색(마젠타)이 아니라 본문 회색으로 변환됨.
같은 유형(색자 계열 문자 스타일)이 문서 전반에서 본문색으로 뭉개짐.

## 원인

- IDML: `CharacterStyleRange AppliedCharacterStyle="CharacterStyle/@색자"`,
  로컬 FillColor 오버라이드 없음 → **스타일 색(C=0 M=100 Y=0 K=0 = #D7157E)이
  확정 렌더 색**.
- resolved DOM: 같은 런의 `fillColor` 를 **본문색 C=0 M=0 Y=0 K=60(#757877)로
  오보고** (DOM 이 인접/문단 본문색을 흘리는 사례 — EH 폰트 과대 보고와 같은 계열).
- `RunPropertyResolver.resolveTextColorHexWithConfidence` 는 "IDML 에 색 증거가
  있으면(effectiveIdmlColor != null) HIGH/MEDIUM 신뢰도 resolved 색을 우선"
  하므로 오보고가 승리 → 회색.

## 수정 (`RunBuilder.buildRun`)

실제 문자 스타일이 적용된 런에서는 IDML 색을 resolved 보다 권위로 둔다:

- 조건: `appliedCharacterStyle` 이 실제 스타일([No character style] 아님)
  **AND** GREP 재정의 없음(`grepAppliedCharStyle == null`)
  **AND** IDML 색 ≠ resolved 색
- 이때 `resolveColorToHex(effectiveIdmlColor)` 를 직접 적용 (해석 실패 시 기존
  우선순위로 폴백).
- 로컬 FillColor 도 없고 파서가 스타일 색을 주입하지 못한 변형은 적용 문자 스타일
  정의에서 색을 직접 조회해 같은 경로로 처리.

GREP 적용 런은 제외 — GREP/중첩 스타일 결과는 resolved 가 더 정확할 수 있다
(SPEC-016 의 confidence 우선 규칙 유지).

## 검증

- p15: "stay in shape" 3런 모두 #D7157E 복원, 앞뒤 본문은 #757877 유지
- 영어 u1 전체: 마젠타 46→456자 등 색자 계열 대량 복원
- 과학 u1: **골든 게이트 PASS**, 색 분포 변화 5건(#D0322B 1→25 등) — 같은
  오보고 패턴의 복원

## 메모

- 색 우선순위는 3층(로컬 FillColor > 문자 스타일 > resolved 보정)으로 봐야 한다.
  "IDML 에 색이 있으면 resolved 우선" 이라는 기존 단순 규칙이 스타일 색을
  오보고로 덮던 지점.
- 구조 시그니처 골든에는 색이 포함되지 않는다 — 색 회귀는 색 히스토그램 비교로
  별도 확인할 것 (본 SPEC 검증에서 사용).
