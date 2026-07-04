# SPEC-037: 정책-코드-검증 이슈 처리 사이클 가속화

> 작성: 2026-06-30. 상태: **Active / 설계 중**. 우선순위: P0.
> 목적: source ownership 정책을 깨지 않으면서 페이지 이슈 조사, 코드 수정,
> 빌드, 추출, 변환, 검증까지의 반복 시간을 줄인다.

## 배경

현재 이슈 하나를 처리할 때마다 다음 작업을 수동으로 반복한다.

1. 사용자가 말한 문서와 페이지를 찾는다.
2. 해당 페이지를 추출하거나 이미 있는 산출물을 찾는다.
3. IDML/resolved/source metadata에서 원천 source를 추적한다.
4. `ObjectPlan`, `ownership-plan.jsonl`, `render-decisions.jsonl`을 확인한다.
5. Java 변환 결과를 열어 눈으로 확인한다.
6. 회귀가 있으면 다시 source ownership 정책과 코드 경로를 뒤진다.

이 과정은 정책 자체가 복잡해서 느린 것이 아니라, 정책-코드-검증을 연결하는
고정된 작업 루프가 없어서 매번 추론으로 복원해야 하기 때문에 느리다. 특히
다음 상황에서 시간이 크게 늘어난다.

- 같은 page 이슈인데도 전체 문서 또는 넓은 구간을 다시 추출한다.
- source id, ObjectPlan, rendered PNG, HWPX 객체 사이의 대응표를 매번 `rg`로 찾는다.
- `extract_indd.jsx`의 candidate/planner/render 책임이 한 파일에 남아 있어 경로를
  빠르게 좁히기 어렵다.
- 회귀 검증이 자동화되어 있지 않아 사용자가 눈으로 다시 알려 줄 때까지 문제를
  놓친다.
- 정책 문서가 canonical 역할과 상세 구현 메모를 동시에 맡아 필요한 규칙을 빨리
  찾기 어렵다.

## 목표

- 이슈 하나의 기본 조사 단위를 `문서 case + 물리 page + source trace + ObjectPlan trace + 변환 결과`로 고정한다.
- 페이지가 명시된 이슈는 기본적으로 해당 페이지만 추출/변환한다.
- source ownership 판단은 계속 Stage 1/ObjectPlan에서만 수행한다.
- trace와 검증 산출물을 자동 생성해 추론 시간을 줄인다.
- 회귀 테스트를 source-slot invariant 중심으로 추가한다.
- legacy helper를 새 구조로 옮길 때는 `move -> wire -> verify -> delete old path`를 따른다.

## 비목표

- 페이지/문구/좌표/색상 기반 예외를 추가하지 않는다.
- HWPX 배치 단계에서 ownership, layer, inline/floating을 재판정하지 않는다.
- 시각 증상만으로 source ownership을 결정하지 않는다.
- 전체 변환을 기본 검증 루프로 삼지 않는다. 전체 변환은 마지막 smoke test다.

## 기본 워크플로우

이슈 처리 기본 명령은 다음 형태를 목표로 한다.

```bash
make issue CASE=park31-u1 PAGE=8
```

생성 산출물:

```text
output/issues/park31-u1/p008/
  extract/
  converted/page.hwpx
  trace/source-objects.json
  trace/page-inventory.md
  trace/page-items.tsv
  trace/object-plans.tsv
  trace/render-decisions.tsv
  trace/text-flows.tsv
  trace/object-plans.jsonl
  trace/render-decisions.jsonl
  thumbs/rendered-*.png
  perf-summary.md
  report.md
```

`report.md`는 사람이 바로 읽을 수 있어야 한다.

```text
case: park31-u1
physicalPage: 8
extractRange: 8..8
sourceSummary:
  - source 2597 Rectangle layer=밑바탕 plan=PAGE_BACKGROUND file=deco_2597.png
  - source 52371 Group plan=CONTENT_VISUAL file=img_52371.png
planInvariant:
  duplicateSourceSlot: 0
  missingVisiblePlan: 0
  backgroundAboveContent: 0
perf:
  extract: 12.3s
  convert: 1.1s
```

## Stage별 개선 계획

### Phase 1. 단일 페이지 이슈 루프 도입

작업:

- `test-data/cases.json` 또는 동등한 case registry에서 문서 경로를 조회한다.
- `scripts/dev/issue.py`와 `Makefile`의 `issue`/`issue-dry-run` 타깃을 추가한다.
- 물리 페이지 하나를 지정하면 `extract_indd.jsx`를 `skipPdf=true`, `perfMode=fast`,
  physical range 모드로 실행한다.
- 사용자가 입력하는 `PAGE`는 `cases.json`의 표시 페이지다. 문서 단원 범위가
  `006~053`이면 `PAGE=6`은 추출 로컬 페이지 `1`로 변환한다.
- 추출 직후 `extraction-plan.json`의 `pageRange`를 검증한다. 요청한 로컬 범위와
  실제 `startPage/endPage/rangePageCount`가 다르면 변환 단계로 넘어가지 않는다.
- 변환까지 자동 실행하고 HWPX를 연다.
- 산출물 디렉터리 구조를 고정한다.

성공 기준:

- `CASE + PAGE`만으로 단일 page 추출/변환이 가능하다.
- preview/PDF 생성 없이 디버깅 산출물만 만든다.
- 단일 page 이슈 처리에서 전체 구간 추출을 하지 않는다.
- 잘못된 page mapping으로 전체 문서가 변환되는 경우를 변환 전에 차단한다.

### Phase 2. Source/ObjectPlan trace 자동화

작업:

- `scripts/dev/page_inventory.py`를 추가한다.
- 입력은 `extract dir`와 `physical page`를 받는다.
- 페이지의 source item, ObjectPlan, render decision, TextFlow를 한 번에
  `trace/page-inventory.md`, `trace/*.tsv`, `trace/page-inventory.json`으로 출력한다.
- `scripts/dev/trace_source.py`를 추가한다.
- 입력은 `extract dir`, `source id`, `text snippet`, `page`를 허용한다.
- 다음 파일을 한 번에 검색하고 연결한다.
  - `resolved.json`
  - `extraction-plan.json`
  - `object-plans.json`
  - `ownership-plan.jsonl`
  - `render-decisions.jsonl`
  - `extraction-results.json`
- rendered PNG가 있으면 thumbnail 목록을 만든다.
- CLI의 `--page`는 물리 페이지 번호로 통일한다. diagnostics의 raw
  zero-based 값은 `--page-index`로만 받는다.
- 처음에는 `page-inventory.md`로 후보 source/text/plan/render를 좁히고,
  그 다음 `trace_source.py`로 특정 source/snippet 생애를 추적한다.
- `Makefile`의 `trace` 타깃으로 자주 쓰는 호출을 고정한다.
- `Makefile`의 `inventory` 타깃으로 기존 extract의 페이지 인벤토리만
  다시 만들 수 있게 한다.

성공 기준:

- source id를 모르는 상태에서도 페이지별 후보를 한 화면에서 확인할 수 있다.
- 특정 source id가 어떤 `ObjectPlan`으로 실행됐는지 한 화면에서 확인된다.
- text snippet으로 관련 TextFrame/source 후보를 찾을 수 있다.
- `ObjectPlan` 없음, PNG 없음, HWPX 배치 없음이 어느 단계 문제인지 자동 구분된다.

### Phase 3. 정책 문서 분리

상태: **진행 중**. `POLICY-source-ownership.md`는 canonical index로 축소하고,
상세 규칙은 `docs/policy/` 모듈로 분리한다.

`POLICY-source-ownership.md`는 canonical index로 유지하되 상세 규칙을 작은 파일로
분리한다.

추천 구조:

```text
docs/policy/
  00-overview.md
  10-source-bundle.md
  20-text-ownership.md
  30-visual-slots.md
  40-layer-zdepth.md
  50-table-policy.md
  60-inline-policy.md
  70-validation-invariants.md
```

성공 기준:

- 이슈가 들어왔을 때 먼저 볼 정책 파일이 명확하다.
- `POLICY-source-ownership.md`에는 최상위 원칙과 링크만 남긴다. **완료**
- 세부 규칙은 하나의 정책 파일에만 존재한다.

### Phase 4. 추출 코드 모듈화

상세 이관 맵: [SPEC-038](SPEC-038-extract-indd-modularization-map.md)

`extract_indd.jsx`를 orchestration 파일로 줄이고, 책임별 모듈로 이동한다.

목표 구조:

```text
scripts/indd/
  source_index.jsx
  source_clusters.jsx
  ownership_candidates.jsx
  ownership_planner.jsx
  object_plan_writer.jsx
  render_executor.jsx
  extraction_validation.jsx
```

이관 규칙:

- 새 파일로 복사만 하지 않는다.
- 한 책임을 옮기면 기존 정의는 같은 변경에서 삭제한다.
- 새 모듈은 `ObjectPlan` 결정 이전 입력 생성 또는 결정 실행 중 하나만 맡는다.
- 후속 실행 단계에서 candidate/source set을 좁히거나 넓히는 함수는 이관하지 않고 제거한다.

성공 기준:

- candidate 생성, planner 결정, render 실행 함수가 한 파일에 섞여 있지 않다.
- `extract_indd.jsx`의 주요 책임은 인자 파싱, 모듈 호출, 파일 쓰기 orchestration이다.
- legacy helper 제거 목록을 `report.md` 또는 migration log에 남긴다.

### Phase 5. 회귀 테스트와 invariant 검증

테스트는 페이지 증상 조건이 아니라 source ownership invariant를 검증한다.

구현 진입점:

- `scripts/dev/regression.py`
- `scripts/dev/regression_suite.py`
- `test-data/issue-regressions.json`
- `make regression EXTRACT=output/issues/.../extract`
- `make regression CASE=park31-u1 PAGE=8`
- `make regression-suite`

초기 regression case:

- `park31-u1-p006`: 연두 배경, `01/02` 표식, header table 위치
- `park31-u1-p008`: 노랑 배경, `01` composite, background/content layer
- `park31-u1-p015`: table-like shell, native line/shell, duplicate fallback 제거
- `park31-u1-p022`: 활동 마무리 shell, inline 보기/check spacing
- `lit-guide-u2-p174`: 구절풀이 shell z-depth
- `lit-guide-u1-p049`: table shell/table style 중복

검증 항목:

- 같은 source bundle/slot visible owner 중복 금지
- `BACKGROUND`이 `CONTENT`/`TEXT`를 덮지 않음
- `PLACE_TEXT_SHELL`은 소유 TF보다 뒤
- same source의 inline/floating 동시 visible 배치 금지
- `OWNED_BY_PNG`와 `OWNED_BY_HWPX_TEXT` 동시 소유 금지
- required source id가 계획과 실행 산출물에 모두 존재

초기 blocking 기준:

- `duplicateVisibleVisualSourceRefs > 0`
- `textOwnerConflicts > 0`
- `placedDespiteDrop > 0`
- `noObjectPlan > 0`

초기 warning 기준:

- ownership warning row 존재
- `droppedDespitePlan > 0`
- `outsidePageDespitePlan > 0`
- `plannedNonFloatingRoutes > 0`
- `expectedNonFloatingBypass > 0`

성공 기준:

- `make regression CASE=park31-u1 PAGE=8`로 해당 page 검증이 가능하다.
- `make regression-suite`로 등록된 known issue page를 한 번에 검증할 수 있다.
- suite는 기존 extract를 기본 재사용하고, 누락된 extract는 `RUN_MISSING=1`일 때만
  새로 추출한다.
- 기존 extract가 잘못된 page mapping으로 만들어졌거나 재검증 기준선을 새로 만들
  때만 `FORCE=1`로 명시적으로 재추출한다.
- suite report는 각 case의 첫 조사 명령으로 `make inventory ...`를 표시하고,
  blocking failure 샘플이 있으면 `make trace ... SOURCE=...` 후보 명령을 표시한다.
- 실패 메시지가 source id, slot, plan, rendered file을 포함한다.
- 사용자가 눈으로 알려 주기 전에 중복/누락/layer invariant 실패를 감지한다.

### Phase 6. 성능 요약 자동화

작업:

- `_phase_timing.log`를 `perf-summary.md`로 요약한다.
- 상위 병목 10개를 자동 표시한다.
- 이전 기준선과 비교한다.

예:

```text
extract total: 205.1s
top bottlenecks:
  03d10_plan_normalizeSlots: 50.3s
  08_complexFrames: 17.8s
  10i_collectStories: 14.6s
  03d08_plan_textOwningShellGroups: 7.9s
  10m_collectPageItems: 6.7s
```

성공 기준:

- 추출/변환 완료 시 병목 요약이 자동 생성된다.
- 변경 전후 비교가 가능하다.
- 느린 함수가 정책 문제인지 export 문제인지 구분된다.

## 역할 분리

실제 구현은 한 agent가 하더라도 사고 단위는 아래처럼 나눈다.

| 역할 | 책임 | 산출물 |
|---|---|---|
| Policy reviewer | 이슈가 어떤 정책과 충돌하는지 판단 | `report.md`의 policy section |
| Source tracer | source id, parentage, layer, zOrder 추적 | `trace/source-objects.json` |
| Plan auditor | ObjectPlan/slot/layer/materialization 확인 | `trace/object-plans.jsonl` |
| Implementer | 최소 코드 수정 | git diff |
| Regression verifier | page invariant와 관련 case 확인 | regression report |
| Performance checker | phase timing 비교 | `perf-summary.md` |

## 권장 실행 순서

1. `scripts/dev/issue.py`와 `Makefile`의 `issue`/`issue-dry-run` 타깃으로 case lookup부터 만든다.
2. `page_inventory.py`와 `make inventory`로 페이지 후보 목록을 만든다.
3. `trace_source.py`와 `make trace`로 source-plan-render 연결 리포트를 만든다.
4. `perf-summary.py`로 병목 요약을 자동 생성한다.
5. `test-data/issue-regressions.json`과 `make regression-suite`로 regression case를 source invariant 중심으로 추가한다.
6. 정책 문서를 `docs/policy/`로 분리한다.
7. `extract_indd.jsx`에서 candidate/planner/render 책임을 하나씩 새 모듈로 옮기고 기존 경로를 삭제한다.

## Done 기준

- 페이지 이슈 하나를 `CASE + PAGE`만으로 추출, 변환, trace, perf summary까지 생성할 수 있다.
- 단일 page 이슈 조사에 전체 문서 추출이 필요하지 않다.
- source id 하나의 생애가 `IDML source -> candidate -> ObjectPlan -> rendered file -> HWPX object`까지 자동 리포트된다.
- 최소 5개 regression page가 source ownership invariant로 검증된다.
- `extract_indd.jsx`에서 candidate 생성, ObjectPlan 결정, render 실행 책임이 분리되기 시작했고, 이관된 legacy 함수는 삭제됐다.
