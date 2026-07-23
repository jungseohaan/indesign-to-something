# SPEC-060: 중첩 데이터 표 스타일 흡수 소실 — stage0 대체 억제의 per-plan 커버리지 검사

> 상태: **완료** — 구현·검증·한글 육안 확인 통과. 2026-07-23.
> 브랜치 `fix-nested-table-style-supersede`.

## 문제

과학 u1 p47 스스로 확인 문제의 구리 실험 데이터 표(2×5, "구리의 질량(g) /
산화 구리(Ⅱ)의 질량(g)")에서 **좌측 라벨 컬럼 fill(#DEE7EB)과 셀 괘선**이
사라짐. 7/17 추출·변환에서는 정상이었음 ("어느 순간부터" 회귀).

## 원인 (증거 사슬)

1. 7/17 추출: 표 캐리어 TF(211452, 문제 캐리어 흐름에 앵커된 표 전용 TF)가
   추출기 선언 plan `objectPlan.table_only_text_frame.211452`
   (PLACE_TABLE_STYLE, styleSource=fill rect+괘선)를 받아 정상 임포트·실행.
2. 7/22 이후: 추출기는 여전히 같은 plan 을 만들지만 Java 임포트가
   `superseded_by_stage0_table_source_contract` 로 스킵
   (ownership-warnings.jsonl 실측).
3. 도입 커밋: **PR #93 (85fb5521, 테이블 스타일 흡수 p28)** — Stage 0
   `TableSourceIndexBuilder` 의 수집 범위 확대(같은 페이지 형제 스캔)로
   tableSourceIndex 가 채워졌고, `shouldSuppressPlannerDeclaredTablePlan` 이
   "**index 가 비어있지 않으면** table style 계열 plan 전부 억제"라는 포괄
   조건이어서 — stage0 이 계약을 만들지 않은 중첩 데이터 표(211859)의 plan
   까지 **대체자 없이** 죽음. (stage0 계약 6건은 전부 바깥 문제 캐리어용,
   211859 를 커버하는 계약 0건)

## 수정 (`ResolvedToASTBuilder.java`)

`shouldSuppressPlannerDeclaredTablePlan` 을 per-plan 커버리지 검사로 교체:
- plan JSON 의 소스 식별자(primarySourceObjectId/domId/exportTargetObjectId +
  sourceObjectIds/styleSourceObjectIds/ownedTextFrameIds)를 수집해
- `TableSourceRecord` 의 `carrierTextFrameId`/`tableId` 와 대조 —
  **실제로 커버하는 계약이 있을 때만** 억제. 없으면 추출기 plan 그대로 임포트.
- 식별자 추출 실패 시 기존 동작(억제) 유지 — 안전 폴백.

## 검증 (전체 u1 재변환, 추출 데이터 동일)

- p47 2×5 표: 좌측 컬럼 fill #DEE7EB + 셀 괘선 3~4방향 복원, 한글 육안 확인
- p28 6×3 표: #EEF4E9 18/18 셀 무회귀 (stage0 계약 경로 유지)
- SPEC-059 반응식 좌변 / p19 답란·게이지 / 유령 테이블 무회귀

## 함정 메모

- **"index 비어있지 않음 = 전부 대체됨" 류의 포괄 supersede 금지**: 대체
  로직은 대체자가 그 대상을 실제로 커버하는지 확인해야 한다. 커버리지 없는
  억제는 기능을 조용히 지운다 (SPEC-057 텍스트 강등의 시각 소유 삭제와 같은
  패턴).
- 표 스타일 흡수 경로는 2계보: stage0 `table_source_contract`(#93 이후 주 경로)
  + 추출기 선언 `table_only_text_frame` plan(중첩/미커버 표 폴백). 진단 시
  ownership-warnings.jsonl 의 `superseded_by_stage0_table_source_contract` 를
  먼저 볼 것.
