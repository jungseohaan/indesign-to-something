# SPEC-038: extract_indd.jsx 분리 맵

> 상태: Active / Batch 39 진행 중. Legacy normalization filter는 regression suite 기준 0.
> 목적: `scripts/extract_indd.jsx`를 책임별 모듈로 안전하게 분리하기 위한
> 실제 함수/라인 단위 이관 맵을 제공한다.

## 원칙

- 분리는 `move -> wire -> verify -> delete old path` 순서로만 한다.
- 새 모듈은 기존 구현의 병렬 fallback이 되면 안 된다.
- source ownership 판단은 계속 Stage 1/ObjectPlan에서만 수행한다.
- 후속 실행 단계에서 source set, placement, layer, materialization을
  좁히거나 넓히는 함수는 이관하지 않고 제거 대상으로 표시한다.
- 이관 단위는 작은 함수 묶음이어야 하며, 각 묶음마다 단일 페이지 issue loop와
  regression audit로 검증한다.

## 현재 구조 요약

`scripts/extract_indd.jsx`는 최초 약 13,846 LOC였고, 2026-07-01 기준 약 215 LOC까지
축소되었다. 현재 루트 파일의 책임은 module loader, JSON bootstrap, global
runtime state, entry trigger뿐이다. 실제 추출 실행 흐름은
`scripts/indd/extraction_orchestrator.jsx`로 이동했다.

| 구간 | 대표 함수 | 현재 책임 | 목표 |
|---|---|---|---|
| 1-240 | `_loadExtractInddModules`, JSON polyfill, globals, entry call | bootstrap/runtime entry | 루트 유지 |
| 287-717 | page data, config, args, links | input prepare | `input_prepare.jsx` |
| 718-1205 | source metadata helpers | source item read/query | 대부분 `source_index.jsx`로 이동 |
| 1206-5479 | candidate ownership normalization + extraction plan appenders/build | legacy Stage 1 candidate/plan build | Batch 9 분리 완료 |
| 207-564 | plan diagnostics/results/render orchestration | orchestration + diagnostics + render dispatch | Batch 11 분리 완료 |
| 565-597 | PDF preview export | optional preview | Batch 10 분리 완료 |
| 565-731 | `main` | orchestration | Batch 11 분리 완료 |
| 7023-8720 | hide/show, ownership render metadata, fallback helpers | render prep + legacy fallback | Batch 8 분리 완료 |
| 8721-12475 | complex/image/deco/render export | visual render executor | Batch 6 분리 완료 |
| 12476-13218 | master page graphics export | master/applied page fragment export | Batch 6 분리 완료 |
| 13219-16075 | resolved/text/story/page collection | resolved writer | Batch 7 분리 완료 |
| 16076-16236 | JSON/write/progress | writer utilities | Batch 1 분리 완료 |

## 이미 분리된 모듈

| 모듈 | LOC | 책임 | 현재 상태 |
|---|---:|---|---|
| `scripts/indd/source_ids.jsx` | 131 | source id/set utilities | 사용 중 |
| `scripts/indd/page_geometry.jsx` | 39 | page/coordinate geometry helper | 사용 중 |
| `scripts/indd/source_index.jsx` | 797 | source item index/query + source metadata helpers | 사용 중 |
| `scripts/indd/ownership_candidates.jsx` | 614 | extraction candidate record/meta helpers + source metadata detector helpers | 사용 중 |
| `scripts/indd/extraction_base_candidates.jsx` | 645 | sourceItems 1차 순회 base extraction candidate 생성 | 사용 중 |
| `scripts/indd/inline_visual_source_detector.jsx` | 159 | hidden/owned inline visual source id read-only detector | 사용 중 |
| `scripts/indd/extraction_passes.jsx` | 21 | static extraction pass contract definitions | 사용 중 |
| `scripts/indd/extraction_plan_builder.jsx` | 586 | extraction candidate appenders/build + plan output | 사용 중 |
| `scripts/indd/source_declared_shell_candidates.jsx` | 961 | source-declared text-owning shell group candidates | 사용 중 |
| `scripts/indd/candidate_normalization_source_context.jsx` | 147 | normalization용 read-only source index/cache context | 사용 중 |
| `scripts/indd/candidate_normalization.jsx` | 2403 | policy-sensitive legacy candidate normalization bridge | 사용 중 |
| `scripts/indd/source_clusters.jsx` | 377 | recursive source cluster diagnostics/query | 사용 중 |
| `scripts/indd/planner_bundles.jsx` | 736 | legacy candidate -> planner bundle diagnostics | 사용 중 |
| `scripts/indd/object_plans.jsx` | 591 | ObjectPlan-shaped diagnostics/validation | 사용 중 |
| `scripts/indd/execution_candidates.jsx` | 187 | ObjectPlan -> legacy execution candidate adapter + execution row contract diagnostics | 사용 중 |
| `scripts/indd/source_slot_registry.jsx` | 620 | executable slot registry diagnostics + Stage 1 canonical source-slot execution filter | 사용 중 |
| `scripts/indd/render_matcher.jsx` | 177 | render pass/candidate lookup + rendered item metadata helpers | 사용 중 |
| `scripts/indd/render_images.jsx` | 604 | complex/image placed render executors | 사용 중 |
| `scripts/indd/render_text_shells.jsx` | 2795 | planned decoration/text-shell render executor | 사용 중 |
| `scripts/indd/render_inline_objects.jsx` | 334 | planned inline/page-background render executor | 사용 중 |
| `scripts/indd/render_master_graphics.jsx` | 743 | master/applied page graphic executor | 사용 중 |
| `scripts/indd/render_prep.jsx` | 1772 | render hide/restore + ownership metadata annotation helpers | 사용 중 |
| `scripts/indd/resolved_collectors.jsx` | 1161 | document/style/color/font/page/page-item collectors | 사용 중 |
| `scripts/indd/text_collectors.jsx` | 1332 | story/text-frame/run collectors | 사용 중 |
| `scripts/indd/preview_export.jsx` | 40 | optional preview.pdf exporter | 사용 중 |
| `scripts/indd/extraction_orchestrator.jsx` | 485 | top-level extraction orchestration + render phase dispatch | 사용 중 |
| `scripts/indd/extraction_result_writer.jsx` | 189 | extraction result schema + slim plan write shape helpers | 사용 중 |
| `scripts/indd/extraction_validation.jsx` | 653 | extraction result/plan validation | 사용 중 |
| `scripts/indd/io_utils.jsx` | 259 | JSON/progress/done/phase marker writer | 사용 중 |

현재 `extract_indd.jsx`는 위 모듈을 load하고 entry trigger만 실행한다.
bootstrap loader 자체는 자기 자신을 로드할 수 없으므로 루트에 남긴다.

## 목표 모듈 구조

```text
scripts/indd/
  input_prepare.jsx
  source_ids.jsx
  page_geometry.jsx
  source_index.jsx
  source_clusters.jsx
  ownership_candidates.jsx
  extraction_base_candidates.jsx
  inline_visual_source_detector.jsx
  extraction_passes.jsx
  extraction_plan_builder.jsx
  planner_bundles.jsx
  object_plans.jsx
  source_slot_registry.jsx
  render_matcher.jsx
  render_text_shells.jsx
  render_inline_objects.jsx
  render_images.jsx
  render_master_graphics.jsx
  render_prep.jsx
  resolved_collectors.jsx
  text_collectors.jsx
  preview_export.jsx
  extraction_orchestrator.jsx
  extraction_validation.jsx
  io_utils.jsx
```

`extract_indd.jsx`의 최종 책임은 모듈 load, JSON bootstrap, 전역 runtime state,
entry trigger뿐이다. 인자 파싱, Stage 호출, 진행률/완료 파일 기록, 최상위 예외
처리는 모듈화된 실행 흐름이 담당한다.

## 이관 순서

### Batch 1. 순수 utility 이동

상태: **완료(2026-07-01)**.

대상:

- `_jsonQuote`, `_jsonSerialize`
- `_marker`
- `_mergeKeys`
- `arrCopy`, `writeJson`, `writeResolvedJson`
- `_jsonStream*`
- `writeProgress`, `writeDone`
- bounds/set 소형 helper 중 ownership 결정을 하지 않는 함수

목표 모듈:

- `scripts/indd/io_utils.jsx`
- 필요 시 `scripts/indd/bounds_utils.jsx`

검증:

- `make issue CASE=park31-u1 PAGE=19 OPEN=0`
- `make regression EXTRACT=...`

실행 결과:

- `extract_indd.jsx` module loader가 `io_utils.jsx`를 가장 먼저 로드한다.
- 기존 `extract_indd.jsx` 내부 utility 정의는 삭제했다.
- 검증 output:
  - `output/issues/park31-u1/p019-20260701-093758`
  - regression: PASS, blocking failure 0

주의:

- JSON 출력 형태가 바뀌면 trace diff가 커진다. 첫 batch에서는 동작만 이동하고
  직렬화 형식은 바꾸지 않는다.

### Batch 2. Input Prepare 이동

상태: **완료(2026-07-01)**.

대상:

- `loadConversionConfig`
- `_parseArgs`
- `_pageLabelIndexMap`
- `_computePageRange`
- `_fixLinks`
- `computePageHash`, `buildPageDataFast`, `buildPageData`

목표 모듈:

- `scripts/indd/input_prepare.jsx`

검증:

- `make issue-dry-run CASE=park31-u1 PAGE=19`
- `make issue CASE=park31-u1 PAGE=19 OPEN=0`
- page range가 요청 page 하나로 유지되는지 `issue-run.json` 확인

실행 결과:

- `input_prepare.jsx`를 추가했다.
- `extract_indd.jsx` module loader가 `input_prepare.jsx`를 `io_utils.jsx` 다음에 로드한다.
- 기존 `extract_indd.jsx` 내부 input prepare 함수 정의는 삭제했다.
- 검증 output:
  - `output/issues/park31-u1/p019-20260701-094704`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0

### Batch 3. Source metadata helper 통합

상태: **완료(2026-07-01)**.

대상:

- `_itemKind`, `_itemId`, `_itemParentId`, `_itemParentKind`
- `_itemAnchoredPosition`, `_storyTextContainerForItem`
- `_textFrameContentBounds`, `_storyAnchorPlacementForItem`
- `_itemBounds`, `_itemLayerName`, `_itemVisible`
- `_pageIndexOfItem`, `_pageIndexBySpreadBounds`
- `_textLengthOfItem`, `_plainTextOfTextFrameForOwnership`

목표 모듈:

- 기존 `scripts/indd/source_index.jsx` 확장

삭제 조건:

- `extract_indd.jsx` 내부의 fallback source metadata reader가 남아 있지 않아야 한다.

실행 결과:

- source metadata helper 17개를 `source_index.jsx`로 이동했다.
- 기존 `extract_indd.jsx` 내부 helper 정의는 삭제했다.
- 검증 output:
  - `output/issues/park31-u1/p019-20260701-095630`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0

주의:

- source metadata helper는 ownership 입력이다. 다른 모듈이 live InDesign tree를
  다시 스캔하게 만들면 안 된다.

### Batch 4. Candidate generation과 normalization 분리

상태: **완료(2026-07-01)**.

대상:

- `_pushExtractionCandidate`
- `_normalizeExtractionCandidateOwnershipSlots`
- `applyClosedTextOwningShellContract`
- `completeTextOwningShellExportContract`
- `directChildShellSlotCandidates*`
- `append*ShellCandidates`
- `_buildExtractionPlan`

목표 모듈:

- `scripts/indd/ownership_candidates.jsx`
- `scripts/indd/extraction_plan_builder.jsx`

진행 상황:

- candidate record/meta helper를 `scripts/indd/ownership_candidates.jsx`로 이동했다.
  - `_candidatePageInRange`
  - `_isBackgroundLayerName`
  - `_diagnosticItemZOrder`
  - `_candidateId`
  - `_candidateCompositeId`
  - `_sourceSetStableHash`
  - `_pushExtractionCandidate`
  - `_createExtractionPlanSourceIndexCache`
  - `_isExtractionShellCandidate`
  - `_candidateEditableTextIds`
  - `_buildExtractionCandidateMeta`
- source-declared inline shell candidate helper를 `scripts/indd/ownership_candidates.jsx`로
  이동했다.
  - `_inlineCompleteMarkerDecisionForOwnership`
  - `_appendSourceDeclaredInlineShellCandidates`
- candidate meta/index helper를 `scripts/indd/ownership_candidates.jsx`로 이동했다.
  - `_refreshExtractionCandidateMeta`
  - `_buildExtractionCandidateMetaIndexes`
  - `extract_indd.jsx`에는 local `sourceInfoById`를 주입하는 얇은 wrapper만 남겼다.
- candidate meta 비교 helper를 `scripts/indd/ownership_candidates.jsx`로 이동했다.
  - `_candidateMetaSourceContainsAll`
  - `_candidateSourceIdArrayContainsAll`
  - `_candidateEditableTextIntersects`
- candidate read-only detector를 `scripts/indd/ownership_candidates.jsx`로 이동했다.
  - `_isPlannerDeclaredDirectChildShellSlot`
  - `_isDirectChildShellSlotCandidate`
  - `_isSlotOnlyShellWithHiddenChildren`
  - `_candidateVisibleExportSourceIds`
  - `_sourceHasVisiblePaintMetadataInIndex`
  - `_sourceHasPlacedVisualMetadataInIndex`
  - `_sourceHasExecutableShellMaterialMetadataInIndex`
  - `_candidateHasExecutableShellMaterial`
- candidate identity 계산 helper를 `scripts/indd/ownership_candidates.jsx`로 이동했다.
  - `_candidateSourceKindPriority`
  - `_chooseFallbackPrimarySourceId`
- candidate effective visual source 조회 helper를 `scripts/indd/ownership_candidates.jsx`로
  이동했다.
  - `_candidateEffectiveVisualSourceIds`
- 기존 `extract_indd.jsx` 내부 helper 정의는 삭제했다.
- `_normalizeExtractionCandidateOwnershipSlots`는 policy-sensitive normalization이므로
  아직 `extract_indd.jsx`에 유지한다. 단순 이동하지 않고 source-slot registry 결정으로
  흡수할 수 있는지 별도 검토한다.
- `_normalizeExtractionCandidateOwnershipSlots` 1차 감사 완료:
  - 함수는 단순 normalize가 아니라 candidate 조회 helper, shell contract 생성,
    direct-child slot 생성, duplicate suppression, drop/mark, master/inline candidate
    generation 일부까지 섞고 있다.
  - 따라서 함수 전체를 `ownership_candidates.jsx`로 이동하지 않는다.
  - 다음 작업은 아래 분류표 기준으로 순수 helper만 먼저 이동하고, policy-sensitive
    mutation은 registry/ObjectPlan 결정으로 접는다.
- 순수 source-id set helper를 `scripts/indd/source_ids.jsx`로 이동했다.
  - `_sourceIdsMinus`
  - `_sourceIdsUnion`
  - `_sourceIdsWithout`
  - 기존 `_sourceIdsContain` 재사용
  - `extract_indd.jsx` 내부 중복 helper 정의는 삭제했다.
- source tree query helper를 `scripts/indd/source_index.jsx`로 이동했다.
  - `_sourceHasEditableTextDescendantInIndex`
  - `_collectSourceDescendantIdsInIndex`
  - `_sourceRootObjectIdsForSourceSetInIndex`
  - `_sourceIdsInCompleteSubtreeInIndex`
  - `_sourceIdsInFullSubtreeInIndex`
  - `_sourceHasAncestorInIndex`
  - `_sourceContainsSourceIdInIndex`
  - `extract_indd.jsx`에는 기존 cache 생명주기를 유지하는 얇은 wrapper만 남겼다.
- 검증 output:
  - `output/issues/park31-u1/p019-20260701-100538`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0
  - `output/issues/park31-u1/p019-20260701-101248`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0
  - `output/issues/park31-u1/p019-20260701-102347`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0
  - `output/issues/park31-u1/p019-20260701-103121`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0
  - `output/issues/park31-u1/p019-20260701-103537`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0
  - `output/issues/park31-u1/p019-20260701-104122`
  - `extraction-plan.json.pageRange`: requested/start/end `14..14`, `rangePageCount=1`
  - regression: PASS, blocking failure 0

핵심 결정:

- candidate 생성은 source-slot registry가 허락한 executable slot만 만들도록 점진적으로
  바꾼다.
- `normalize`가 ownership을 고치는 함수라면 이동하지 않고 Stage 1 registry 결정으로
  흡수한다.

#### `_normalizeExtractionCandidateOwnershipSlots` 1차 분류

| 분류 | 함수/구간 | 판단 | 다음 조치 |
|---|---|---|---|
| 순수 query/set helper | `metaSourceContainsAll`, `sourceIdArrayContainsAll`, `editableIntersects`, `_sourceIdsMinus`, `_sourceIdsUnion`, `_sourceIdsContain`, `_sourceIdsWithout` | source/candidate를 읽고 새 배열만 만든다 | source-id set helper와 candidate meta 비교 helper 이동 완료 |
| source tree query helper | `sourceHasEditableTextDescendant`, `collectSourceDescendantIds`, `sourceRootObjectIdsForSourceSet`, `sourceIdsInCompleteSubtree`, `sourceContainsSourceId`, `sourceHasAncestor` | source index 질의다. ownership 결정은 하지 않는다 | source-index helper 이동 완료. normalize 내부에는 cache wrapper만 유지 |
| candidate meta/index helper | `buildCandidateMeta`, `refreshCandidateMeta`, `rebuildCandidateMetaIndexes`, page별 index map 생성 | 로컬 meta cache 관리다. visible owner를 만들지는 않는다 | meta/index helper 이동 완료. normalize 내부에는 wrapper만 유지 |
| candidate read-only detector | `isPlannerDeclaredDirectChildShellSlot`, `isDirectChildShellSlotCandidate`, `isSlotOnlyShellWithHiddenChildren` | candidate 상태만 읽고 boolean을 반환한다 | detector 1차 이동 완료 |
| candidate identity/helper | `sourceKindPriority`, `chooseFallbackPrimarySourceId`, `candidateEffectiveVisualSourceIds` | candidate id 재계산과 effective visual source 조회만 한다 | identity/effective source helper 이동 완료 |
| shell export source 계산 | `textlessShellExportSourceIds`, `textlessShellExportSourceIdsForTarget`, `concreteShellExportSourceIds`, `visibleShellSlotSourceIds` | export source를 고르는 policy 입력이다 | 바로 이동하지 않고 ObjectPlan의 `visualSourceObjectIds/exportSourceObjectIds`로 승격 |
| shell contract mutation | `applyClosedTextOwningShellContract`, `applyClosedTextlessShellFragmentContract`, `completeTextOwningShellExportContract`, `applyExactTextOwningShellExportContract`, `completeSlotOnlyShellExportSources` | `sourceObjectIds`, `exportSourceObjectIds`, `hiddenVisualSourceObjectIds`, `slotRole`, `mode`를 직접 바꾼다 | Stage 1 source-slot registry 결정으로 흡수 |
| direct-child/table sibling slot 생성 | `makeDirectChildShellSlotCandidate`, `directChildShellSlotCandidatesForCompositeShell`, `directChildShellSlotCandidatesFromHiddenSources`, `appendTableSiblingShellSlotsForComposite`, `appendCompositeChildShellSlots`, `appendTextFrameStyleShellSlotsForCompositeCarriers` | normalize 중 새 visible candidate를 만든다 | candidate generation 단계로 이동하되 registry 허용 slot만 생성 |
| 후속 보정/제거 | `removeInlineAnchorSourcesFromFloatingShellExport`, `includeHiddenInlineSourceProvenance`, `excludeDirectChildShellSlotsFromParent`, `excludeDeclaredChildShellSlotsFromParentExports` | 기존 candidate의 visible source를 줄이거나 숨긴다 | legacy 제거 후보. 같은 효과가 필요하면 planner slot에서 먼저 결정 |
| duplicate/drop/suppress | `dropCandidateIndexes`, `isSubsumedBySamePageShell`, `isDuplicateFloatingShellOfInline`, `assertNoExactShellSlotDuplicates` | 후보를 drop/suppress하거나 충돌을 검증한다 | exact shell slot 중복은 source 생성 차단 + validator 실패로 전환 완료. 나머지 drop/suppress는 registry invariant로 대체 대상 |
| materialization fallback | `assertExecutableShellCandidates` / `assertExecutableShellCandidate` | visual 없는 shell을 더 이상 다른 materialization으로 바꾸지 않고 invariant 위반으로 중단한다 | 실행 가능 material 판정은 read-only detector로 분리 완료. 생성 차단 + planner invariant assert로 교체 완료 |

1차 결론:

- 이동 가능한 것은 read-only helper와 meta cache helper뿐이다.
- source set/export/hidden source를 바꾸는 함수는 새 모듈로 옮기지 않는다.
- 새 candidate를 만드는 함수는 `extraction_plan_builder.jsx` 대상이지만, 먼저
  source-slot registry가 허락한 slot만 생성하도록 입력 계약을 정리해야 한다.
- drop/suppress류는 “옮길 코드”가 아니라 제거하거나 invariant 검증으로 대체할 코드다.

#### Contract mutation 감사표

아래 함수들은 `normalize` 내부에 있지만 실제로는 Stage 1 ownership 결정을
후처리로 다시 쓰는 경로다. 이 함수들은 새 모듈로 단순 이동하지 않는다.
목표는 각 결정을 source-slot registry/ObjectPlan 생성 시점으로 올리는 것이다.

| 함수/경로 | 현재 mutation | 정책상 문제 | 목표 계약 |
|---|---|---|---|
| `applyClosedTextOwningShellContract` | `sourceObjectIds`, `exportSourceObjectIds`, `hiddenVisualSourceObjectIds`, `slotRole`, `mode` 재작성 | candidate 생성 후 shell slot 범위를 넓힌다 | closed text-owning shell은 candidate 생성 시 `SHELL_SLOT` source set을 확정한다 |
| `applyClosedTextlessShellFragmentContract` | parent/fragment export source와 target을 재계산 | fragment가 plan 밖에서 slot owner가 된다 | fragment shell candidate는 registry가 허락한 source bundle로만 생성한다 |
| `completeTextOwningShellExportContract` | hidden text/source/export target을 한 번에 보정 | text owner와 visual owner를 normalize에서 결합한다 | `ownedTextFrameIds`, `visualSourceObjectIds`, `hiddenTextFrameIds`는 ObjectPlan 필드로 먼저 선언한다 |
| `removeInlineAnchorSourcesFromFloatingShellExport` | floating shell export에서 inline anchor source를 제거 | inline/floating visible 여부를 후속 단계에서 뒤집는다 | source-declared inline slot을 먼저 만들고 parent floating candidate는 해당 slot을 포함하지 않는다 |
| `includeHiddenInlineSourceProvenance` | hidden inline source를 provenance에 추가하고 export에서 제거 | hidden provenance가 bounds/overlap 기반 보정으로 들어온다 | story anchor/source metadata에서 provenance를 candidate 생성 전에 연결한다 |
| `normalizeTextFrameShellStyleExports` | TF shell style source를 export/hidden 목록 사이에서 이동 | table/style/shell owner를 normalize에서 재분배한다 | TF shell style은 `SHELL_SLOT` 또는 `TABLE_STYLE_SLOT` 중 하나로 registry가 결정한다 |
| `completeSlotOnlyShellExportSources` | slot-only export source와 target을 사후 보완 | executable source set이 늦게 정해진다 | slot-only candidate는 생성 즉시 executable source set을 가져야 한다 |
| `excludeDirectChildShellSlotsFromParent*` | child shell source를 parent export에서 제거하고 parent 상태를 변경 | parent/child 중복을 후처리 drop으로 해결한다 | child shell slot을 먼저 등록하고 parent shell candidate는 child visible slot을 제외한 source set으로 생성한다 |
| `assertNoExactShellSlotDuplicates` | exact duplicate candidate를 suppress하지 않고 diagnostics 후 실패 | 중복 금지를 normalize heuristic으로 처리하지 않는다 | 같은 bundle/slot visible owner 충돌은 source 생성 단계에서 만들지 않고, 남으면 validator가 실패시킨다 |
| `assertExecutableShellCandidates` / `assertExecutableShellCandidate` | visual 없는 shell이 남으면 오류로 중단 | materialization fallback은 제거됐다. 남는 문제는 candidate 생성 단계의 계약 위반이다 | executable material 판정은 `_candidateHasExecutableShellMaterial`로 통일했다. candidate 생성 시점 차단 뒤에도 남는 경우 planner invariant로 실패시킨다 |

다음 코드 단계:

1. 위 표의 함수별 입력 조건을 read-only detector와 mutation executor로 분리한다.
2. detector는 `ownership_candidates.jsx` 또는 `source_slot_registry.jsx`에서 사용할 수 있게
   순수 predicate로만 만든다.
3. mutation executor는 새 모듈로 옮기지 않고, registry/ObjectPlan 생성이 대체되면 삭제한다.
4. 삭제 전후로 `same bundle + same slot visible owner <= 1` invariant를 regression에 연결한다.

제거 후보:

- rendered-child coverage suppression
- inline coverage suppression
- post-candidate export source 보정
- parent restore/drop 계열 heuristic

#### Non-executable shell candidate 생성 차단

상태: **완료(2026-07-01)**.

- `pass.decoration_groups` base candidate 생성 조건에 executable shell material 검사를 추가했다.
- 이전에는 `Group`이 자식을 가지면 일단 후보를 만들고, 뒤에서
  `demoteNonExecutableShellCandidate`가 `HWPX_TEXT/DROP_VISUAL`로 바꾸는 경로가 있었다.
- 이제 source-set 안에 visible fill/stroke/placed visual로 실행 가능한 material이 없으면
  해당 decoration candidate를 만들지 않는다.
- 이 판단은 `_candidateHasExecutableShellMaterial(...)` read-only predicate를 사용한다.
- `_normalizeExtractionCandidateOwnershipSlots` 내부의 별도 executable-material 판정도 같은
  predicate를 호출하도록 정리했다.
- legacy demote 함수는 제거했다. 남은 candidate가 실행 가능한 material을 갖지 않으면
  `HWPX_TEXT/DROP_VISUAL`로 바꾸지 않고 `Non-executable shell candidate...` 오류로 중단한다.
  즉, 후속 단계에서 ownership을 고치는 통로를 닫고 candidate 생성 단계의 문제로 드러나게 했다.
- 검증 output:
  - `output/issues/park31-u1/p019-20260701-131855`
    - candidate count: `26`
    - `PLACE_TEXT_SHELL`: `17`
    - `nonExecutableShellReason`: `0`
  - `output/issues/park31-u1/p006-20260701-132003`
    - candidate count: `10`
    - `PLACE_TEXT_SHELL`: `5`
    - `nonExecutableShellReason`: `0`
  - `output/issues/park31-u1/p008-20260701-132034`
    - candidate count: `18`
    - `PLACE_TEXT_SHELL`: `5`
    - `nonExecutableShellReason`: `0`
  - `output/issues/park31-u1/p015-20260701-132109`
    - candidate count: `20`
    - `PLACE_TEXT_SHELL`: `14`
    - `nonExecutableShellReason`: `0`
  - regression suite: `park31-u1-p006`, `park31-u1-p008`, `park31-u1-p015` PASS

#### Exact shell duplicate filter 제거

상태: **완료(2026-07-01)**.

결론:

- exact shell slot 중복은 후단 suppress/filter로 지우지 않는다.
- 같은 `pageIndex | SHELL_SLOT | visibleShellSlotSourceIds` owner가 둘 이상 생기면
  `assertNoExactShellSlotDuplicates`가 diagnostics를 남긴 뒤 즉시 실패한다.
- 이 validator는 후보를 바꾸지 않는다. 즉, ownership을 고치는 통로가 아니라
  Stage 1 source/candidate 생성 계약 위반을 드러내는 장치다.

정리한 경로:

- exact shell slot duplicate 필터 함수
  `_filterExactDecorationShellSlotDuplicates`를 제거했다.
- normalize 내부의 `filterExactShellSlotDuplicates`를 제거하고
  `assertNoExactShellSlotDuplicates`로 대체했다.
- `suppressedByExactShellSlotDuplicate` 플래그와 이를 건너뛰는 normalized loop를 제거했다.

source 생성 단계 수정:

- direct sibling editable TextFrame이 같은 visual shell source를 소유하는 경우,
  base candidate 생성에서 다음 leaf/background shell 후보를 만들지 않는다.
  - `background_vector_source`
  - `clipped_placed_carrier_sibling_shell`
  - `leaf_vector_shell_source`
- table sibling shell 후보도 같은 source가 direct sibling text shell owner라면 만들지 않는다.
- 이 변경은 page/text/좌표 조건이 아니라 source metadata 관계
  (`direct sibling editable TextFrame + shell source`)만 사용한다.

진단 파일:

- `extraction-plan.json.exactShellSlotDuplicateSummary`
- `planner-diagnostics-summary.json.exactShellSlotDuplicateSummary`
- `exact-shell-slot-duplicates.json`

최신 broad regression 결과:

| case | latest output | pre-normalize | final-filter | suppressed |
|---|---|---:|---:|---:|
| `park31-u1-p006` | `p006-20260701-140923` | 0 | 0 | 0 |
| `park31-u1-p007` | `p007-20260701-140954` | 0 | 0 | 0 |
| `park31-u1-p008` | `p008-20260701-141027` | 0 | 0 | 0 |
| `park31-u1-p014` | `p014-20260701-141102` | 0 | 0 | 0 |
| `park31-u1-p015` | `p015-20260701-141134` | 0 | 0 | 0 |
| `park31-u1-p022` | `p022-20260701-141208` | 0 | 0 | 0 |
| `lit-guide-u2-p174` | `p174-20260701-141240` | 0 | 0 | 0 |
| `lit-guide-u1-p049` | `p049-20260701-141303` | 0 | 0 | 0 |

검증:

- `python3 scripts/dev/regression_suite.py --force-run`
  - registered 8 cases PASS
- exact shell duplicate summary
  - all registered cases: `preNormalizeCompetitionCount=0`,
    `finalFilterCompetitionCount=0`, `suppressedCount=0`

### Batch 5. Render matching/result writer 분리

상태: **완료(2026-07-01)**.

대상:

- `_buildExtractionCandidateLookup`
- `_extractionCandidatesForPass`
- `_pngExtractionCandidatesForPass`
- `_nonPngExtractionCandidatesForPass`
- `_findPlannedExtractionCandidate`
- `_findPlannedExtractionSourceSet`
- `_buildExtractionResults`
- `_dedupeRenderedFloatingItems`
- `_addRenderMeta`
- `_slimExtractionPlanForWrite`

목표 모듈:

- `scripts/indd/render_matcher.jsx`
- `scripts/indd/extraction_result_writer.jsx`

진행 상황:

- 1차 render matcher helper를 `scripts/indd/render_matcher.jsx`로 이동했다.
  - `_buildExtractionCandidateLookup`
  - `_extractionCandidatesForPass`
  - `_pngExtractionCandidatesForPass`
  - `_nonPngExtractionCandidatesForPass`
  - `_candidateMatch`
  - `_findPlannedExtractionCandidate`
  - `_findPlannedExtractionSourceSet`
  - `_findExtractionPass`
  - `_isExtractionPassEnabled`
  - `_requireExtractionPass`
  - `_hiddenTextFrameIdsFromSaved`
  - `_renderedItemDedupeKey`
  - `_dedupeRenderedFloatingItems`
  - `_addRenderMeta`
  - `_buildItemById`
- 2차 result writer helper를 `scripts/indd/extraction_result_writer.jsx`로 이동했다.
  - `_resultExportId`
  - `_buildExtractionResults`
  - `_slimExtractionPlanForWrite`
- 3차 ObjectPlan bridge helper를 `scripts/indd/object_plan_candidate_bridge.jsx`로
  이동했다.
  - `_annotateExtractionCandidatesWithObjectPlans`
  - 이후 Batch 14에서 bridge 파일을 제거하고, ObjectPlan 모듈 내부의
    `_buildExecutionCandidatesFromObjectPlans` 복사 생성기로 대체했다.
- 4차 ObjectPlan filter bridge를 격리했으나, 이후 bridge 파일은 제거했다.
  현재 같은 호환 진단은 `scripts/indd/source_slot_registry.jsx`의 Stage 1
  canonical source-slot execution filter가 담당한다.
  - `_canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics`
  - `_canonicalizeSourceSlotSubsumedCandidates`
- 5차 base extraction candidate 생성 루프를
  `scripts/indd/extraction_base_candidates.jsx`로 이동했다.
  - `_appendBaseExtractionCandidates`
  - `sourceItems` 1차 순회에서 만드는 content/deco/vector/textframe shell 후보만 담당한다.
  - `extract_indd.jsx`는 source index/cluster 생성 후 모듈 함수 호출만 수행한다.
- 6차 `_includeOwnedInlineVisualsInTextlessShellCandidates` 내부의 read-only inline
  visual source detector를 `scripts/indd/inline_visual_source_detector.jsx`로 이동했다.
  - `_createInlineVisualSourceDetector`
  - hidden/owned inline visual source id 조회만 수행한다.
  - candidate source/export set mutation은 아직 `extract_indd.jsx`에 남아 있으며,
    다음 단계에서 ObjectPlan/source-slot registry 결정으로 대체 후 삭제해야 한다.
- 남은 정책 부채:
  - source-slot registry canonical execution filter가 아직 legacy candidate-shaped
    execution row를 줄인다.
  - 다음 단계에서는 이 필터를 ObjectPlan/source-slot registry가 canonical executable
    owner를 생성하는 구조로 끌어올려야 한다.

주의:

- matcher는 candidate를 고르기만 해야 한다.
- matcher가 fallback strategy를 만들거나 source set을 넓히면 안 된다.

### Batch 6. Visual render executor 분리

상태: **완료(2026-07-01)**.

대상:

- `exportComplexGraphicFrames`
- `exportImagePlacedFrames`
- `exportDecorationGroups`
- `_decoRender`
- `_renderEditableVisualLabelShell`
- `_exportLabelBackdropGroup`
- `_exportPaperStrokeShapes`
- `exportInlineObjects`
- `exportMasterPageGraphics`

목표 모듈:

- `scripts/indd/render_text_shells.jsx`
- `scripts/indd/render_images.jsx`
- `scripts/indd/render_inline_objects.jsx`
- `scripts/indd/render_master_graphics.jsx`

진행 상황:

- 1차 image/graphic render helper를 `scripts/indd/render_images.jsx`로 이동했다.
  - `exportComplexGraphicFrames`
  - planned `pass.complex_graphic_frames` candidate만 실행한다.
  - 함수 본문은 그대로 이동했고, ownership/source/placement 판단은 추가하지 않았다.
  - `extract_indd.jsx`는 module loader에서 `render_images.jsx`를 로드한 뒤 기존
    orchestration 호출만 유지한다.
- 2차 image placed render helper를 `scripts/indd/render_images.jsx`로 이동했다.
  - `exportImagePlacedFrames`
  - planned `pass.image_placed_frames` / `pass.image_textless_groups` candidate만 실행한다.
  - 내부 helper까지 함수 단위로 그대로 이동했고, fallback/ownership 판단을 새로 만들지 않았다.
- 3차 visual backdrop cluster render helper는 이후 Batch 16에서 제거했다.
  - 해당 pass는 `disabled: true`와 `enableVisualBackdropClusterExport = false`로
    실행되지 않던 실험 경로였다.
  - multi-source shell은 Stage 1 source-slot shell planning이 소유한다.
- 4차 decoration/text shell render helper를 `scripts/indd/render_text_shells.jsx`로 이동했다.
  - `_buildDecorationCandidateIndexes`
  - `exportDecorationGroups`
  - `isAllShapeChildren`
  - `_decoAllPageItems`는 모듈 공용 helper로 노출해 기존 호출 의미를 유지했다.
- 5차 inline/page-background render helper를 `scripts/indd/render_inline_objects.jsx`로 이동했다.
  - `exportPageBackgrounds`
  - `exportInlineObjects`
  - page background pass는 기존처럼 planned no-op이다.
- 6차 master graphics render helper를 `scripts/indd/render_master_graphics.jsx`로 이동했다.
  - `exportMasterPageGraphics`
  - master/applied page fragment export 본문은 그대로 이동했다.
- smoke 검증:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output:
    - `output/issues/lit-guide-u2/p174-20260701-151250`
    - `output/issues/lit-guide-u2/p174-20260701-151349`
    - `output/issues/lit-guide-u2/p174-20260701-151603`

허용:

- planned candidate의 `exportTargetObjectId`, `exportSourceObjectIds`,
  `hiddenVisualSourceObjectIds`, `hiddenTextFrameIds`를 실행한다.

금지:

- candidate가 없는데 bounds/text/style로 새 candidate를 만드는 것
- `page_object`/`inline_object`를 executor에서 뒤집는 것
- alpha crop으로 placement를 재계산하는 것
- fallback shell/box를 그리는 것

### Batch 7. Resolved/text/story collection 분리

상태: **완료(2026-07-01)**.

대상:

- `collectResolved`
- `collectDocumentInfo`
- `collectParagraphStyles`
- `collectColors`
- `collectFonts`
- `collectComposedLines`
- `collectStories`
- `collectTextFrames`
- `instanceMasterFrames`
- `collectPages`
- `collectPageItems`

목표 모듈:

- `scripts/indd/resolved_collectors.jsx`
- `scripts/indd/text_collectors.jsx`

주의:

- resolved 수집은 Stage 0 입력이다.
- ownership 판단을 resolved collector 안으로 다시 넣지 않는다.

진행 상황:

- `scripts/indd/resolved_collectors.jsx`로 이동했다.
  - `collectDocumentInfo`
  - `collectParagraphStyles`
  - `collectColors`
  - `collectFonts`
  - `measureFontMetrics`
  - `collectComposedLines`
  - `collectResolved`
  - `instanceMasterFrames`
  - `collectPages`
  - `collectPageItems`
  - `collectNativeParentTextShellCandidates`
- `scripts/indd/text_collectors.jsx`로 이동했다.
  - GREP/중첩 스타일 보정 helper
  - `collectStories`
  - `collectTextFrames`
- `isInlineItem`은 여러 Stage 0 source query가 공유하므로 `scripts/indd/source_index.jsx`로 이동했다.

### Batch 8. Render prep / legacy fallback helper 정리

상태: **완료(2026-07-01)**.

대상:

- TextFrame classifier / hide-restore helper
- render ownership metadata helper
- editable TextFrame visual shell fallback helper
- remaining source/object bounds helper

목표 모듈:

- `scripts/indd/render_prep.jsx`
- `scripts/indd/render_ownership_metadata.jsx`
- 필요 시 `scripts/indd/geometry_utils.jsx`

주의:

- 이번 batch에서는 기존 동작을 바꾸지 않고 파일 책임만 분리했다.
- fallback visible material 제거는 별도 정책 변경 작업이다. 제거 시에는 Stage 1/ObjectPlan
  대체 owner가 먼저 있어야 하며, 실행 단계에서 임의로 drop/promotion하지 않는다.

진행 상황:

- `scripts/indd/render_prep.jsx`로 이동했다.
  - TextFrame shape/fill/stroke classifier
  - `exportEditableTextFrameVisualShells`
  - hide/restore helper
  - render ownership metadata annotation helper
  - range page item collector
- smoke 검증:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-152536`

### Batch 9. Page geometry / extraction plan builder 분리

상태: **완료(2026-07-01)**.

대상:

- page-relative geometry helper
- legacy extraction candidate normalization
- master/inline/text-owning shell group candidate appender
- extraction plan assembly

목표 모듈:

- `scripts/indd/page_geometry.jsx`
- `scripts/indd/extraction_plan_builder.jsx`

진행 상황:

- 공용 page geometry helper를 `scripts/indd/page_geometry.jsx`로 이동했다.
  - `_resolveParentPage`
  - `_toPageRelativeBounds`
  - `_pageRelativeBoundsCopy`
- legacy Stage 1 candidate/plan builder를 `scripts/indd/extraction_plan_builder.jsx`로
  이동했다.
  - `_normalizeExtractionCandidateOwnershipSlots`
  - `_appendMasterCompositeExtractionCandidates`
  - `_appendInlineObjectExtractionCandidates`
  - `_appendSourceDeclaredTextOwningShellGroupCandidates`
  - `_includeOwnedInlineVisualsInTextlessShellCandidates`
  - `_buildExtractionPlan`
- `extract_indd.jsx`는 이제 module loader, render orchestration, preview export,
  entrypoint만 유지한다.

주의:

- 이번 batch는 함수 본문 이동만 수행했고, source ownership 정책이나 candidate
  생성 조건을 변경하지 않았다.
- `_normalizeExtractionCandidateOwnershipSlots` 안에는 아직 legacy mutation 부채가
  남아 있다. 다음 정책 작업에서는 이 로직을 source-slot registry/ObjectPlan
  결정으로 대체해야 한다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-155109`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 10. Preview export 분리

상태: **완료(2026-07-01)**.

대상:

- optional `preview.pdf` export

목표 모듈:

- `scripts/indd/preview_export.jsx`

진행 상황:

- `_exportPdf`를 `scripts/indd/preview_export.jsx`로 이동했다.
- `extract_indd.jsx`는 PDF export 구현을 직접 갖지 않고, `main`에서 모듈 함수를
  호출한다.

주의:

- 이번 batch는 ownership, extraction plan, render materialization과 무관한
  output-only 이동이다.
- PDF pageRange와 export preference 본문은 그대로 이동했다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-161336`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 11. Extraction orchestrator 분리

상태: **완료(2026-07-01)**.

대상:

- `_runRenderPhases`
- `main`

목표 모듈:

- `scripts/indd/extraction_orchestrator.jsx`

진행 상황:

- plan build, render pass dispatch, extraction result validation, resolved write,
  preview export 호출을 `scripts/indd/extraction_orchestrator.jsx`로 이동했다.
- `extract_indd.jsx`는 module loader, JSON polyfill, global runtime state,
  entry trigger만 유지한다.
- 이번 이동은 함수 본문 이동만 수행했고, source ownership 정책, extraction pass
  순서, candidate filtering, validation 규칙은 변경하지 않았다.

주의:

- module loader는 자기 자신을 module list로 로드할 수 없으므로 루트에 남긴다.
- `_phaseTimingState`, `_ctfCache`, `_hiddenLayerCache`,
  `_hiddenVisibilityCache`, `_extractionCandidateLookup`, `CONFIG`는 여러 모듈이
  공유하는 ExtendScript runtime state라 이번 batch에서는 루트에 유지한다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-162627`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 12. Source-slot canonicalization bridge 제거

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/object_plan_candidate_filter_bridge.jsx`
- post-ObjectPlan legacy candidate suppression 진단 경로

진행 상황:

- `object_plan_candidate_filter_bridge.jsx` 파일을 삭제했다.
- 기존 호환 동작은 `scripts/indd/source_slot_registry.jsx`의 Stage 1
  canonical source-slot execution filter로 이동했다.
  - `_canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics`
  - `_canonicalizeSourceSlotSubsumedCandidates`
- 새 source-slot canonicalization 진단 필드를 기록한다.
  - `sourceSlotCanonicalizationSummary`
- full diagnostics 모드에서는 `source-slot-canonicalization.json`을 기록한다.

주의:

- 이번 batch는 output 호환을 위해 기존 canonicalization 동작을 source-slot
  registry 모듈로 이동한 것이다.
- 이번 batch 직후에는 legacy candidate에 `disabled/DROP_VISUAL`을 표시하는
  migration debt가 남아 있었고, Batch 13에서 candidate mutation과
  `objectPlanSubsumed*` 호환 필드 기록을 제거했다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-165155`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 13. Canonical candidate mutation 제거와 pass contract 분리

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/source_slot_registry.jsx`
- `scripts/indd/object_plan_candidate_bridge.jsx`
- `scripts/indd/extraction_plan_builder.jsx`
- `scripts/indd/extraction_passes.jsx`

목표:

- source-slot canonicalization이 legacy candidate 객체를 `disabled/DROP_VISUAL`로
  바꾸지 않는다.
- canonical owner가 아닌 후보는 Stage 1 plan 후보 목록에서 제외하고,
  진단에는 suppressed candidate record로 남긴다.
- ObjectPlan bridge는 더 이상 suppressed/disabled candidate 전용 branch를 갖지 않는다.
- extraction pass 정의는 plan builder에서 분리해 static execution contract로 관리한다.

주의:

- 이번 단계는 여전히 legacy candidate array를 실행 입력으로 사용한다.
- 다음 단계의 목표는 canonical executable owner를 ObjectPlan/source-slot registry에서
  직접 생성해 `object_plan_candidate_bridge.jsx` 자체를 제거하는 것이다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-170721`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

잔여 audit:

- `DROP_TEXT` / `DROP_VISUAL` 문자열은 ObjectPlan action vocabulary로 남아 있다.
  source-slot canonicalization은 더 이상 candidate에 이 값을 써 넣지 않는다.
- `disabled`는 export pass enable/disable 계약과 read-only matcher skip에만 남아 있다.
  candidate mutation assignment는 제거했다.
- `restore*` 이름은 InDesign DOM hide/export 이후 원상복구 경로다. visible owner를
  새로 만들지 않으므로 Legacy 제거 목록의 "restore 계열 visible owner 생성"과는
  분리한다.
- `_dedupeRenderedFloatingItems`는 Batch 15에서 제거했다. 같은 result row가 두 번
  생성되면 더 이상 executor가 숨기지 않고 `duplicate_extraction_result_row` validation
  error로 실패한다.
- `fallback` 검색 결과 중 source metadata fallback과 image export fallback은 ownership
  생성이 아니라 입력/파일 export 경로다. visible shell/material fallback은 새로 만들지
  않는다.

### Batch 14. ObjectPlan candidate bridge 파일 제거와 실행 후보 복사 생성

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/object_plans.jsx`
- `scripts/indd/extraction_plan_builder.jsx`
- `scripts/extract_indd.jsx`
- `scripts/indd/object_plan_candidate_bridge.jsx`

목표:

- `object_plan_candidate_bridge.jsx`를 제거한다.
- ObjectPlan 필드를 legacy candidate 객체에 제자리로 덮어쓰지 않는다.
- Stage 1에서 만든 ObjectPlan diagnostics를 기준으로 execution candidate 복사본을 만든다.
- 기존 executor는 아직 candidate-shaped row를 읽지만, 입력 row는
  `_buildExecutionCandidatesFromObjectPlans`가 만든 Stage 1 실행 계약으로 제한한다.

변경:

- `scripts/indd/object_plans.jsx`
  - `_buildExecutionCandidatesFromObjectPlans`
  - `_objectPlansByCandidateId`
  - `_copyExecutionCandidate`
  - `_applyObjectPlanExecutionFields`
- `scripts/indd/extraction_plan_builder.jsx`
  - 원본 candidate 선언 목록과 execution candidate 목록을 분리했다.
  - source-slot canonicalization 이후에도 ObjectPlan 기반 execution candidate를 다시 만든다.
- `scripts/extract_indd.jsx`
  - `object_plan_candidate_bridge.jsx` module load를 제거했다.
- `scripts/indd/object_plan_candidate_bridge.jsx`
  - 삭제했다.

남은 정책 부채:

- executor는 여전히 candidate-shaped row를 실행 입력으로 사용한다.
- 다음 단계는 `planner_bundles`/`source_slot_registry`가 만든 canonical owner row를
  ObjectPlan 자체 또는 canonical execution row로 직접 전달해 legacy candidate shape를
  더 줄이는 것이다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-172251`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 15. Rendered floating item dedupe 제거와 validation 실패 전환

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/render_matcher.jsx`
- `scripts/indd/extraction_orchestrator.jsx`
- `scripts/indd/extraction_validation.jsx`

목표:

- executor가 rendered result row를 조용히 dedupe하지 않는다.
- 같은 row가 두 번 생성되면 Stage 4 validation에서 실패시킨다.
- 중복 원인은 Stage 1 ObjectPlan/source-slot registry에서 해결해야 한다.

변경:

- `scripts/indd/render_matcher.jsx`
  - `_renderedItemDedupeKey`
  - `_dedupeRenderedFloatingItems`
  - 위 두 helper를 제거했다.
- `scripts/indd/extraction_orchestrator.jsx`
  - `_dedupeRenderedFloatingItems(renderedFloatingItems)` 호출을 제거했다.
- `scripts/indd/extraction_validation.jsx`
  - `_validateExtractionResultRowDuplicates`
  - `_extractionResultRowDuplicateKey`
  - exact duplicate result row를 `duplicate_extraction_result_row`로 실패시킨다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-173355`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 16. Disabled visual backdrop cluster executor 제거

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/render_executor.jsx`
- `scripts/indd/extraction_passes.jsx`
- `scripts/indd/extraction_orchestrator.jsx`
- `scripts/extract_indd.jsx`

목표:

- 실행되지 않는 `pass.visual_backdrop_clusters` 경로를 삭제한다.
- visual backdrop bundle은 executor가 새로 후보를 만들지 않고 Stage 1
  source-slot shell planning으로만 결정한다.
- disabled pass와 `enableVisualBackdropClusterExport = false` 같은 이중 차단
  코드를 남겨 두지 않는다.

변경:

- `scripts/indd/render_executor.jsx`
  - 삭제했다.
- `scripts/extract_indd.jsx`
  - module loader에서 `render_executor.jsx`를 제거했다.
- `scripts/indd/extraction_passes.jsx`
  - disabled `pass.visual_backdrop_clusters` 계약을 제거했다.
- `scripts/indd/extraction_orchestrator.jsx`
  - `06b_visualBackdropClusters` marker, disabled executor guard, stats field를 제거했다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-174514`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 17. Source-slot canonicalizer 분리

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/source_slot_registry.jsx`
- `scripts/indd/source_slot_canonicalizer.jsx`
- `scripts/extract_indd.jsx`

목표:

- source-slot registry 진단/요약과 canonical execution filter 책임을 분리한다.
- 동작은 유지하되, 남은 migration debt인 “legacy execution candidate filtering”을
  별도 파일로 격리한다.
- 이후 이 필터를 validator failure 또는 ObjectPlan-native execution row로 대체할 때
  제거 범위를 좁힌다.

변경:

- `scripts/indd/source_slot_canonicalizer.jsx`
  - `_canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics`
  - `_canonicalizeSourceSlotSubsumedCandidates`
  - 위 두 함수를 새 파일로 이동했다.
- `scripts/indd/source_slot_registry.jsx`
  - registry summary/slot scoring 책임만 남겼다.
- `scripts/extract_indd.jsx`
  - module loader에 `source_slot_canonicalizer.jsx`를 추가했다.

참고:

- 이후 Batch 38에서 source-slot canonical execution filter는
  `source_slot_registry.jsx`로 다시 통합했다.
  - 이유: 같은 source-slot owner 선택 책임이 diagnostics 모듈과 별도 필터 모듈로
    갈라져 있었기 때문이다.
  - Batch 17은 당시 분리 이력으로만 남긴다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-175518`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

### Batch 18. Legacy normalization filter diagnostics 추가

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/extraction_plan_builder.jsx`
- `scripts/indd/extraction_orchestrator.jsx`

목표:

- `_normalizeExtractionCandidateOwnershipSlots` 안에 남아 있는 조용한 drop/suppress
  경로를 즉시 제거하지 않고 먼저 진단 파일로 드러낸다.
- visible output은 바꾸지 않는다.
- 다음 batch에서 각 filter를 Stage 1 생성 방지, source-slot registry 결정, 또는
  validation failure로 옮길 때 실제 사용량과 reason을 근거로 삼는다.

변경:

- `scripts/indd/extraction_plan_builder.jsx`
  - `legacyNormalizationFilterDiagnostics`를 추가했다.
  - `dropCandidateIndexes`, `invalidSlotOnlyCandidateIndexes`,
    `suppressedByDirectChildShellSlots`, `isSubsumedBySamePageShell`,
    `isDuplicateFloatingShellOfInline`,
    `isTextFrameStyleShellSubsumedByCompositeShell`로 걸러진 후보를 reason별로 기록한다.
  - `extractionPlan.legacyNormalizationFilterSummary`와
    `ctx._extractionPlanDiagnostics.legacyNormalizationFilterDiagnostics`를 노출한다.
- `scripts/indd/extraction_orchestrator.jsx`
  - `legacy-normalization-filters.json`을 쓴다.
  - `planner-diagnostics-summary.json`에 `legacyNormalizationFilterSummary`를 포함한다.

1차 smoke 관찰:

- `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
- output: `output/issues/lit-guide-u2/p174-20260701-180921`
- `legacy-normalization-filters.json`
  - `filteredCount=129`
  - `CHILD_ONLY_VISUAL_OF_CLIP_PARENT=107`
  - `SUBSUMED_BY_SAME_PAGE_SHELL=15`
  - `REDUNDANT_CHILD_TEXT_SHELL=7`

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-180921`
- build:
  - `mvn -pl converter -am -DskipTests package`
  - result: `BUILD SUCCESS`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
- hygiene:
  - `git diff --check`
  - result: no whitespace errors

남은 정책 부채:

- 진단은 추가되었지만 filter 자체는 아직 legacy normalization 안에서 실행된다.
- 다음 단계는 reason별로 실제 정책 의미를 확인해서 output-preserving filter를
  ObjectPlan/source-slot registry 결정 또는 Stage 4 validation으로 이관하는 것이다.

### Batch 19. Regression suite legacy filter summary 추가

상태: **완료(2026-07-01)**.

대상:

- `scripts/dev/regression_suite.py`

목표:

- Batch 18에서 생성한 `legacy-normalization-filters.json`을 사람이 따로 열지 않아도
  regression suite report에서 케이스별/전체 reason 분포를 확인한다.
- 개발 사이클에서 “이번 변경이 어떤 legacy filter 부채를 건드렸는지”를 빠르게
  파악한다.

변경:

- 각 regression row에 `legacyNormalizationFilterSummary`를 포함한다.
- `regression-suite.md` table에 `legacy filters` 컬럼을 추가한다.
- markdown 하단에 전체 reason 합계를 `Legacy Normalization Filters` 섹션으로 쓴다.

검증:

- syntax:
  - `python3 -m py_compile scripts/dev/regression_suite.py`
  - result: pass
- report regeneration:
  - `python3 scripts/dev/regression_suite.py`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
  - observed total: `579` filtered candidates across the current 8 latest extracts

### Batch 20. Same-page shell subsumption owner diagnostics 보강

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/extraction_plan_builder.jsx`

목표:

- `SUBSUMED_BY_SAME_PAGE_SHELL` filter가 어떤 shell owner 때문에 후보를
  숨겼는지 진단에 기록한다.
- 기존 visible output과 filter 동작은 바꾸지 않는다.

변경:

- `isSubsumedBySamePageShell`의 실제 판정을
  `findSamePageShellSubsumingCandidateIndex`로 분리했다.
- normalization filter 기록 시 `ownerCandidateId`, `ownerPassId`,
  `ownerSourceObjectIds`를 채운다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case park31-u1 --page 6 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/park31-u1/p006-20260701-182251`
- diagnostic check:
  - `SUBSUMED_BY_SAME_PAGE_SHELL` sample owner:
    - candidate: `cand.pass.decoration_groups.page.0.src.147476`
    - owner: `cand.pass.decoration_groups.composite.page.0.src.147475.147478.n3.h588502842`

### Batch 21. Direct-child shell suppression diagnostics 분리

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/extraction_plan_builder.jsx`

목표:

- `suppressedByDirectChildShellSlots`라는 단일 flag에 섞여 있던 원인을 진단에서
  분리한다.
- parent shell 후보가 어떤 child shell slot 때문에 suppress됐는지 source/candidate
  근거를 기록한다.
- visible output은 바꾸지 않는다.

변경:

- `annotateShellSuppression`을 추가했다.
- parent 후보에 아래 진단 전용 필드를 기록한다.
  - `suppressedByDirectChildShellSlotsReason`
  - `suppressedByDirectChildShellSlotSourceIds`
  - `suppressedByDirectChildShellSlotCandidateIds`
- legacy normalization filter reason을 기존
  `SUPPRESSED_BY_DIRECT_CHILD_SHELL_SLOTS` 대신 구체 reason으로 기록한다.
  - `DIRECT_CHILD_SHELL_SLOTS`
  - `INLINE_OWNED_SOURCES_REMOVED`
  - `INLINE_PARENT_REPLACED_BY_CHILD_SHELL_SLOTS`

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case park31-u1 --page 15 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/park31-u1/p015-20260701-182808`
- diagnostic check:
  - `DIRECT_CHILD_SHELL_SLOTS=2`
  - sample suppression source ids: `240001,240002,240025`
  - sample child slot:
    `cand.pass.decoration_groups.composite.page.9.src.240001.240025.n3.h188554967.ungrouped_outline_text_shell_slot`

### Batch 22. Clip-parent visual filter reason 분리와 alternate pass 생성 차단

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/extraction_base_candidates.jsx`
- `scripts/indd/extraction_plan_builder.jsx`
- `scripts/indd/ownership_candidates.jsx`

목표:

- `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`에 섞여 있던 두 상황을 분리한다.
  - 같은 source-set이 `decoration_groups`와 `complex_graphic_frames` 양쪽에서
    만들어진 alternate pass
  - composite shell 내부의 leaf/vector child 후보
- alternate pass는 후단 drop/filter가 아니라 base candidate 생성 시점에서 만들지 않는다.
- 실제 child-only 후보는 아직 legacy filter에 남겨 두되, owner candidate/source-set을
  진단에 기록한다.

변경:

- `legacy-normalization-filters.json`에 clip-parent 관련 owner 진단 필드를 추가했다.
  - `clipParentShellOwnerSourceId`
  - `clipParentShellOwnerSourceObjectIds`
  - `ownerCandidateId`
  - `ownerPassId`
  - `ownerSourceObjectIds`
- 같은 source-set의 `decoration_groups` 후보가 이미 생성된 경우,
  동일 source-set의 `complex_graphic_frames` 후보를 만들지 않는다.
- normalize 단계에서는 같은 source-set이면
  `CLIP_PARENT_SHELL_ALTERNATE_PASS`로, 실제 child source-set이면
  `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`로 reason을 구분한다.

검증:

- before:
  - output: `output/issues/lit-guide-u2/p174-20260701-183917`
  - `filteredCount=129`
  - `CLIP_PARENT_SHELL_ALTERNATE_PASS=6`
  - `CHILD_ONLY_VISUAL_OF_CLIP_PARENT=101`
- after:
  - smoke:
    `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-184427`
  - `filteredCount=123`
  - `CLIP_PARENT_SHELL_ALTERNATE_PASS=0`
  - `CHILD_ONLY_VISUAL_OF_CLIP_PARENT=101`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - latest p174 output: `output/issues/lit-guide-u2/p174-20260701-184932`
  - suite total legacy filters: `562`

남은 정책 부채:

- `CHILD_ONLY_VISUAL_OF_CLIP_PARENT` 101건은 여전히 normalize 내부 drop이다.
- 대표 케이스 `274738`은 leaf `GraphicLine`이고 실제 owner는
  `274707..274738` source-set의 `decoration_groups` candidate다.
- 다음 단계에서는 leaf child 후보를 만든 뒤 지우지 말고, Stage 1 source-slot registry가
  composite shell source-set을 먼저 소유하면 leaf/vector candidate를 생성하지 않는
  방향으로 옮긴다.

### Batch 23. Clip-parent child visual 후보 생성 차단

상태: **완료(2026-07-01)**.

대상:

- `scripts/indd/source_index.jsx`
- `scripts/indd/extraction_base_candidates.jsx`

목표:

- `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`로 뒤에서 drop되던 leaf/vector/image child
  후보를 base candidate 생성 시점에서 만들지 않는다.
- clip parent 탐색은 immediate parent만 보지 않고, IDML의 Group ancestor를 타고
  올라가 첫 clip-carrying shape source를 찾는다.
- composite shell source-set이 이미 owner가 될 수 있으면 그 내부 child visual은
  별도 visible 후보가 아니다.

변경:

- `sourceIndex.clipCarryingParentIdOfSource(...)`가 Group ancestor를 최대 16단계
  따라 올라가 clip-carrying shape를 찾도록 확장했다.
- base candidate 생성에서 clip-parent shell owner가 확인된 source는 아래 후보를
  생성하지 않는다.
  - `pass.image_placed_frames`
  - `pass.vector_shape_frames`
  - leaf vector shell decoration candidate
  - child decoration candidate
- 이 결정은 page/text/좌표 조건이 아니라 source parent chain과 executable shell
  material 조건만 사용한다.

검증:

- smoke:
  - `python3 scripts/dev/issue.py --case lit-guide-u2 --page 174 --output-root /Users/seohan/works/indesign-to-something/output/issues --no-open`
  - output: `output/issues/lit-guide-u2/p174-20260701-185414`
  - p174 `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`: `101 -> 1`
- regression:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - report: `output/regression-suite/regression-suite.md`
  - suite total legacy filters: `562 -> 244`
  - suite `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`: `318 -> 8`

남은 정책 부채:

- `CHILD_ONLY_VISUAL_OF_CLIP_PARENT` 잔여 8건은 Batch 24에서 생성 단계로
  이관했다.

### Batch 24: 조상 shell owner 기반 child visual 생성 억제

문제:

- Batch 23 이후에도 `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`가 8건 남았다.
- 잔여 케이스는 source leaf 자체가 문제라기보다, leaf 또는 작은 clip parent가
  이미 더 넓은 조상 shell source set에 포함되는데도 별도 후보로 만들어지는
  구조였다.
- 특히 shape 분기 초반의 background/vector 후보 생성이 clip-parent ownership
  판정보다 먼저 실행되어, 후단 normalize가 다시 drop해야 했다.

정책:

- source hierarchy에서 실행 가능한 조상 shell owner가 확인되면 child visual
  후보를 만들지 않는다.
- "조상 shell owner"는 IDML source parent chain과 page-local source set으로만
  판단한다.
- bounds/occlusion/색상/문구/페이지 조건으로 판단하지 않는다.
- clip parent 후보 자체도 더 넓은 조상 shell owner에 포함되면 별도 후보로
  만들지 않는다.

구현:

- `extraction_base_candidates.jsx`
  - `ancestorShellOwnerForBase()` 추가.
  - 가장 가까운 조상이 아니라, 같은 visual channel을 포괄하는 최상위 실행 가능
    shell source set을 owner로 본다.
  - `sourceHasClipParentShellOwner()`가 direct clip parent와 ancestor shell owner를
    함께 확인한다.
  - shape 후보 생성에서 clip/ancestor owner 판정을 background/vector 후보보다
    먼저 수행한다.
  - clip-parent decoration 후보도 더 넓은 조상 shell owner가 있으면 생성하지
    않는다.
- `extract_indd.jsx`
  - extraction cache version `48 -> 49`.

검증:

- p49 targeted:
  - `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`: `7 -> 0`
  - rendered images: `24 -> 17` in the targeted run, all removed outputs were child
    visual duplicates under the same shell owner.
- p174 targeted:
  - `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`: `1 -> 0`

남은 정책 부채:

- `SUBSUMED_BY_SAME_PAGE_SHELL`는 Batch 25에서 source-set 포함 관계 기반 생성
  억제로 일부 이관한다.
- `REDUNDANT_CHILD_TEXT_SHELL`은 아직 normalize 단계에 남아 있다.

### Batch 25: same-page shell source-set subset 후보 생성 억제

문제:

- Batch 24 이후 회귀 스위트의 `SUBSUMED_BY_SAME_PAGE_SHELL` 148건은 모두
  candidate source set이 owner shell source set의 부분집합이었다.
- 즉 보이는 위치/색상/occlusion 문제가 아니라, 같은 page-local shell slot에서
  더 큰 source bundle이 이미 visible owner로 선언되었는데 subset 후보가 다시
  생성되는 문제다.

정책:

- 같은 page에서 이미 decoration shell source set이 선언되었고, 이후 후보의
  source set이 그 owner source set의 엄격한 부분집합이면 subset 후보를 만들지
  않는다.
- 판단 근거는 source id 집합 포함 관계뿐이다.
- bounds/occlusion/색상/문구/페이지별 예외는 사용하지 않는다.
- source set이 동일한 경우는 기존 exact duplicate 경로가 담당하므로 이 규칙은
  "broader owner"에만 적용한다.

구현:

- `extraction_base_candidates.jsx`
  - page별 `decorationSourceSetsByPage`를 추가해 선언된 shell source set을
    보관한다.
  - `hasBroaderDecorationSourceSet()`으로 subset 후보를 생성 전에 차단한다.
  - 대상 후보:
    - background vector source
    - clipped placed carrier sibling shell
    - leaf vector shell
    - native vector shape frame
    - graphic line vector frame
    - clip-parent decoration shell
    - decoration group shell
    - complex graphic frame
- `extract_indd.jsx`
  - extraction cache version `49 -> 50`.

검증 계획:

- targeted:
  - `park31-u1` p008:
    - `SUBSUMED_BY_SAME_PAGE_SHELL`: `40 -> 1`
  - `park31-u1` p014:
    - `SUBSUMED_BY_SAME_PAGE_SHELL`: `35 -> 10`
  - `lit-guide-u1` p049:
    - visible conversion passed, but late direct-child shell owner still leaves
      `CHILD_ONLY_VISUAL_OF_CLIP_PARENT=3`
- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - result: `8/8 PASS`
  - total legacy filters: `192 -> 63`
  - `SUBSUMED_BY_SAME_PAGE_SHELL`: `148 -> 22`
- build:
  - `mvn -pl converter -am -DskipTests package`

남은 정책 부채:

- sourceItems 순서상 broader owner가 subset 후보보다 늦게 선언되는 케이스는 아직
  normalize 단계에 남을 수 있다.
- `lit-guide-u1` p049처럼 direct-child shell 보강 경로가 base 후보 생성 이후에
  broader owner를 선언하는 케이스는 아직 `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`로
  남는다.
- 다음 batch는 선언 순서에 의존하지 않는 Stage 1 owner registry로 이 규칙을 옮기고,
  `direct_child_shell_slot` 보강 후보가 만들어지는 즉시 같은 slot의 child 후보를
  만들지 않도록 한다.
- `REDUNDANT_CHILD_TEXT_SHELL`은 별도 source text ownership slot 정리로 다룬다.

## Batch 26: generated direct-child shell owner subset pruning

목표:

- Batch 25 이후 `lit-guide-u1` p049에 남은
  `CHILD_ONLY_VISUAL_OF_CLIP_PARENT=3`를 normalize drop이 아니라 Stage 1 후보
  생성/브리지 안에서 제거한다.
- 남은 3건은 base 후보 생성 시점에는 broader owner가 아직 없고,
  `direct_child_shell_slot` 보강 단계에서 owner가 늦게 생기는 순서 문제다.

정책:

- `direct_child_shell_slot` owner가 생성되면 같은 page/pass에서 그 owner의 visible
  source set에 엄격히 포함되는 작은 visual 후보는 같은 `SHELL_SLOT`을 따로 만들지
  않는다.
- 판단 근거는 source id 집합 포함 관계뿐이다.
- bounds, occlusion, 색상, 문구, 페이지별 예외는 사용하지 않는다.
- editable/hidden text ownership을 가진 후보는 제거하지 않는다.

구현:

- `extraction_plan_builder.jsx`
  - `appendCompositeChildShellSlots()`가 생성/재사용한 direct-child shell owner를
    수집한다.
  - owner 확정 직후 `pruneExistingSubsetCandidatesForGeneratedDirectChildShellSlots()`
    로 이미 존재하던 subset visual 후보를 후보 리스트에서 제거한다.
  - 대상은 `pass.decoration_groups`, `pass.vector_shape_frames`,
    `pass.complex_graphic_frames` 중 text ownership이 없는 후보로 제한한다.
- `extract_indd.jsx`
  - extraction cache version `50 -> 51`.

검증 계획:

- targeted:
  - `lit-guide-u1` p049:
    - `CHILD_ONLY_VISUAL_OF_CLIP_PARENT`: `3 -> 0`
- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 27: redundant child text shell owner pruning before diagnostics

목표:

- Suite에 남은 `REDUNDANT_CHILD_TEXT_SHELL=34`를 normalize filter가 아니라
  Stage 1 후보 확정 단계에서 제거한다.
- 기존 최종 output은 이미 이 후보들을 필터링하고 있었으므로 output-preserving
  migration으로 처리한다.

정책:

- 같은 page에서 parent shell source set이 child shell source set을 엄격히 포함하고,
  두 후보가 같은 editable TextFrame ownership을 공유하면 child shell은 별도
  visible `SHELL_SLOT` 후보를 만들지 않는다.
- 판단 근거는 source id 포함 관계와 editable text id 교집합뿐이다.
- bounds, occlusion, 색상, 문구, 페이지별 예외는 사용하지 않는다.
- `direct_child_shell_slot`은 명시 slot owner이므로 이 규칙에서 제외한다.

구현:

- `extraction_plan_builder.jsx`
  - legacy diagnostics를 만들기 전에
    `pruneRedundantChildTextShellCandidatesBeforeDiagnostics()`를 실행한다.
  - 기존 `isRedundantChildTextShell()` 기준을 Stage 1 후보 리스트 확정으로 이동한다.
- `extract_indd.jsx`
  - extraction cache version `51 -> 52`.

검증 계획:

- suite:
  - `REDUNDANT_CHILD_TEXT_SHELL`: `34 -> 0`
  - `python3 scripts/dev/regression_suite.py --force-run`
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 28: suppressed direct-child shell parent pruning before diagnostics

목표:

- Suite에 남은 `DIRECT_CHILD_SHELL_SLOTS=4`를 final normalize filter가 아니라
  Stage 1 후보 확정 단계에서 제거한다.
- 이 후보들은 이미 direct-child shell slot으로 대체되어
  `suppressedByDirectChildShellSlots=true`가 붙은 parent shell이다.

정책:

- parent shell이 direct-child shell slot들로 완전히 대체되면 parent 후보는 더 이상
  visible owner 후보가 아니다.
- parent suppression 판단은 기존 direct-child slot 생성/분리 결과를 따른다.
- 후속 실행 단계나 bounds/occlusion/색상/문구 기반 판단은 추가하지 않는다.

구현:

- `extraction_plan_builder.jsx`
  - legacy diagnostics 전에
    `pruneSuppressedDirectChildShellParentCandidatesBeforeDiagnostics()`를 실행한다.
  - `suppressedByDirectChildShellSlots=true` parent 후보를 candidate list에서 제거한다.
- `extract_indd.jsx`
  - extraction cache version `52 -> 53`.

검증 계획:

- suite:
  - `DIRECT_CHILD_SHELL_SLOTS`: `4 -> 0`
  - `python3 scripts/dev/regression_suite.py --force-run`
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 29: same-page shell subset pruning before diagnostics

목표:

- Suite에 마지막으로 남은 `SUBSUMED_BY_SAME_PAGE_SHELL=22`를 final normalize
  filter가 아니라 Stage 1 후보 확정 단계에서 제거한다.
- 기존 `findSamePageShellSubsumingCandidateIndex()` 판정은 바꾸지 않고, 같은
  source-set 포함 관계를 diagnostics 전에 적용한다.

정책:

- 같은 page의 shell owner가 후보의 visible shell source-set을 포함하면, subset
  후보는 같은 `SHELL_SLOT`의 별도 owner가 아니다.
- 후보 제거 기준은 source-set과 ObjectPlan shell metadata이며 bounds, occlusion,
  문구, 색상, 페이지별 예외를 사용하지 않는다.
- parent/complete shell과 subset shell이 동시에 visible candidate로 내려가지 않게
  한다.

구현:

- `extraction_plan_builder.jsx`
  - `rebuildSamePageShellSourceSetsForPruning()`으로 diagnostics 이전 same-page
    shell owner index를 구성한다.
  - `pruneSamePageShellSubsumedCandidatesBeforeDiagnostics()`로 기존
    `findSamePageShellSubsumingCandidateIndex()` 판정에 걸리는 subset 후보를
    candidate list에서 제거한다.
- `extract_indd.jsx`
  - extraction cache version `53 -> 54`.

검증 계획:

- suite:
  - `SUBSUMED_BY_SAME_PAGE_SHELL`: `22 -> 0`
  - `python3 scripts/dev/regression_suite.py --force-run`
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 30: regression suite legacy-filter gate

목표:

- Batch 29 이후 suite 기준 legacy normalization filter가 0이 되었으므로, 이 상태가
  다시 무너지면 회귀로 실패하게 만든다.
- 개발자가 별도 JSON을 열지 않아도 `regression_suite.py` 결과만으로 Stage 1
  ownership 부채 재발을 감지한다.

정책:

- legacy normalization filter는 더 이상 정상 경로가 아니다.
- 필요 시 조사용으로만 `--allow-legacy-filters`를 사용할 수 있지만, 기본 suite는
  filter 존재를 blocking failure로 처리한다.

구현:

- `scripts/dev/regression_suite.py`
  - `legacy_filtered_count()`를 추가한다.
  - 기본 실행에서 `legacy-normalization-filters.json.summary.filteredCount > 0`이면
    `legacy_normalization_filter_present` failure를 추가한다.
  - 조사/이전 결과 확인용 예외 옵션 `--allow-legacy-filters`를 추가한다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py`
  - 기존 최신 extract 재사용 기준 `8/8 PASS`
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 31: source-declared shell candidate module split

목표:

- `extraction_plan_builder.jsx`에 남아 있던 source-declared shell candidate 생성 구간을
  별도 모듈로 이동한다.
- 동작 변경 없이 파일 책임만 분리한다.

정책:

- source-declared shell candidate 생성은 여전히 source metadata / ObjectPlan 정책을
  따른다.
- 이동 과정에서 candidate 생성 조건, ownership slot, materialization, z-order 판단을
  바꾸지 않는다.

구현:

- 새 파일:
  - `scripts/indd/source_declared_shell_candidates.jsx`
- 이동:
  - `_appendSourceDeclaredTextOwningShellGroupCandidates`
- loader:
  - `extract_indd.jsx`에서 `extraction_passes.jsx` 다음에 새 모듈을 로드한다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 32: candidate normalization module split

목표:

- `_normalizeExtractionCandidateOwnershipSlots` 전체를 `extraction_plan_builder.jsx`에서
  분리해, plan builder가 candidate 조립과 plan 출력에 집중하도록 한다.
- 이번 단계에서는 normalization 내부 정책/조건/출력 shape를 바꾸지 않는다.

정책:

- normalization은 여전히 policy-sensitive legacy bridge다.
- 이 파일로 이동했다고 mutation이 정당화되는 것은 아니다.
- 다음 단계에서 read-only detector와 mutation block을 이 파일 안에서 분리하고,
  mutation은 ObjectPlan/source-slot registry 결정으로 접는다.

구현:

- 새 파일:
  - `scripts/indd/candidate_normalization.jsx`
- 이동:
  - `_normalizeExtractionCandidateOwnershipSlots`
- loader:
  - `extract_indd.jsx`에서 `source_declared_shell_candidates.jsx` 다음에 새 모듈을 로드한다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 33: normalization read-only source detector reuse

목표:

- `candidate_normalization.jsx` 내부에 중복으로 남아 있던 source metadata detector를
  공용 read-only helper로 접는다.
- candidate mutation block은 건드리지 않고, 판단 함수의 위치만 정리한다.

구현:

- `ownership_candidates.jsx`
  - `_sourceInfoHasVisiblePaintMetadata`
  - `_sourceHasTextFrameShellStyleMetadataInIndex`
- `candidate_normalization.jsx`
  - `sourceHasTextFrameShellStyle`
  - `sourceHasVisiblePaint`
  - `sourceHasPlacedVisualSource`
  위 wrapper들이 공용 read-only detector를 호출하도록 변경한다.

정책:

- source metadata만 읽는다.
- candidate 생성/drop/suppress/placement/layer 판단은 변경하지 않는다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 34: source-declared shell read-only detector reuse

목표:

- `source_declared_shell_candidates.jsx` 내부에 중복으로 남아 있던 source metadata
  detector를 `ownership_candidates.jsx` 공용 helper로 접는다.
- source-declared shell 후보 생성 조건과 candidate shape는 바꾸지 않는다.

구현:

- `source_declared_shell_candidates.jsx`
  - `sourceHasTextFrameShellStyleInSourceIndex`가
    `_sourceHasTextFrameShellStyleMetadataInIndex`를 호출한다.
  - `sourceHasPlacedVisualSource`가 `_sourceHasPlacedVisualMetadataInIndex`를 호출한다.
  - `sourceHasVisiblePaint`가 `_sourceInfoHasVisiblePaintMetadata`를 호출한다.

정책:

- source metadata만 읽는다.
- candidate 생성/drop/suppress/placement/layer 판단은 변경하지 않는다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 35: candidate normalization source context split

목표:

- `_normalizeExtractionCandidateOwnershipSlots` 내부의 source index/cache 생성과
  read-only source tree query를 별도 context 모듈로 분리한다.
- normalization 파일에는 candidate mutation block이 더 명확히 남도록 한다.

구현:

- 새 파일:
  - `scripts/indd/candidate_normalization_source_context.jsx`
- 이동/위임:
  - source index/cache 생성
  - editable TextFrame descendant/source-set query
  - complete/full subtree query
  - source containment query
  - inline-anchor ancestor query
- loader:
  - `extract_indd.jsx`에서 `source_declared_shell_candidates.jsx` 다음,
    `candidate_normalization.jsx` 전에 로드한다.

정책:

- 새 context는 source metadata만 읽는다.
- candidate 생성/drop/suppress/placement/layer 판단을 하지 않는다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 36: candidate normalization mutation map

목표:

- `candidate_normalization.jsx`에 남아 있는 mutation block을 목록화한다.
- 이후 작업에서 “무엇을 ObjectPlan/source-slot registry로 이동해야 하는지”를
  코드 검색 없이 확인할 수 있게 한다.

분류:

| 분류 | 함수/패턴 | 현재 역할 | 다음 처리 |
|---|---|---|---|
| shell export contract mutation | `applyClosedTextOwningShellContract`, `applyClosedTextlessShellFragmentContract`, `completeTextOwningShellExportContract`, `applyExactTextOwningShellExportContract`, `completeSlotOnlyShellExportSources`, `applyPlannedShellExportTarget` | `sourceObjectIds`, `exportSourceObjectIds`, `hiddenVisualSourceObjectIds`, `slotRole`, `mode`, `exportTargetObjectId`를 보정 | Stage 1 ObjectPlan/source-slot contract 필드로 이전 |
| inline provenance mutation | `removeInlineAnchorSourcesFromFloatingShellExport`, `includeHiddenInlineSourceProvenance` | inline-owned source를 floating shell export에서 제거하거나 provenance로 추가 | source-slot registry에서 inline/floating owner를 먼저 확정 |
| direct-child/table sibling slot 생성 | `makeDirectChildShellSlotCandidate`, `makeTextFrameStyleShellSlotCandidate`, `makeTableSiblingShellSlotCandidate`, `appendCompositeChildShellSlots`, `appendTableSiblingShellSlotsForComposite`, `appendTextFrameStyleShellSlotsForCompositeCarriers` | parent composite에서 child shell slot 후보를 사후 생성 | candidate generation 단계 또는 ObjectPlan slot expansion으로 이전 |
| parent/child export exclusion | `excludeDirectChildShellSlotsFromParent`, `excludeDeclaredChildShellSlotsFromParentExports` | parent export source에서 child slot source를 제외 | source-slot registry의 slot owner 충돌 해결로 이전 |
| prune before diagnostics | `pruneExistingSubsetCandidatesForGeneratedDirectChildShellSlots`, `pruneRedundantChildTextShellCandidatesBeforeDiagnostics`, `pruneSuppressedDirectChildShellParentCandidatesBeforeDiagnostics`, `pruneSamePageShellSubsumedCandidatesBeforeDiagnostics` | diagnostics 전에 candidate list에서 제거 | Stage 1 owner selection 결과로 후보 생성 자체를 억제 |
| legacy filter diagnostics | `markLegacyNormalizationFilter`, `recordLegacyNormalizationFilter` | 남은 legacy filter를 기록 | 유지. visible output 생성/재판정 금지 |
| exact shell duplicate validation | `exactShellSlotDuplicateKey`, `recordExactShellSlotDuplicate`, `assertNoExactShellSlotDuplicates` | 같은 shell slot 중복을 검증 | Stage 4 validation으로 유지 또는 이동 |

정책:

- 위 mutation 함수는 새 모듈로 단순 이동하지 않는다.
- 이동할 때는 ObjectPlan/source-slot registry 결정으로 표현하거나, Stage 4
  validation으로 바꾼다.
- diagnostics 함수는 visible output을 만들거나 candidate를 수정하지 않는 한 유지할 수 있다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 37: execution candidate adapter split

목표:

- `object_plans.jsx`에서 ObjectPlan diagnostics/validation 책임과 legacy execution
  candidate adapter 책임을 분리한다.
- executor contract 축소 전까지 legacy candidate-shaped row 복사는 한 파일에만 남긴다.

구현:

- 새 파일:
  - `scripts/indd/execution_candidates.jsx`
- 이동:
  - `_buildExecutionCandidatesFromObjectPlans`
  - `_objectPlansByCandidateId`
  - `_copyExecutionCandidate`
  - `_applyObjectPlanExecutionFields`
- loader:
  - `extract_indd.jsx`에서 `object_plans.jsx` 다음에 로드한다.

정책:

- 이번 단계는 shape 축소 전의 책임 분리다.
- ObjectPlan 필드를 candidate-shaped execution row에 복사하는 migration bridge는
  `execution_candidates.jsx`에만 남긴다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 38: source-slot canonicalizer registry 통합

목표:

- source-slot owner 선택 책임을 `source_slot_registry.jsx` 한 곳으로 모은다.
- 별도 `source_slot_canonicalizer.jsx` 파일이 Stage 1 registry와 다른 판단 축처럼
  보이지 않게 한다.
- 동작은 유지하되, 다음 단계에서 legacy candidate-shaped row 필터를
  ObjectPlan/source-slot registry의 canonical executable owner 생성으로 바꾸기 쉽게
  경계를 좁힌다.

구현:

- `scripts/indd/source_slot_canonicalizer.jsx` 삭제.
- `_canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics`와
  `_canonicalizeSourceSlotSubsumedCandidates`를 `source_slot_registry.jsx`로 통합.
- `scripts/extract_indd.jsx` loader에서 `source_slot_canonicalizer.jsx` 제거.

정책:

- 이번 단계는 책임 위치를 바로잡는 구조 변경이다.
- canonicalizer는 여전히 Stage 1 plan build 중에만 실행된다.
- pixel/occlusion/page text를 보지 않고 source ids, pass id, ownership slot,
  ObjectPlan에서 온 execution field만 사용한다.
- 아직 canonical owner 생성 자체를 registry가 직접 만드는 구조는 아니다.
  이 부분은 다음 migration debt로 남긴다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 39: execution candidate contract diagnostics

목표:

- executor가 아직 legacy candidate-shaped row를 읽고 있으므로, 축소 전에 실제
  실행 row contract를 명시한다.
- `execution_candidates.jsx`가 복사하는 legacy 필드 중 executor가 읽는 필드와
  추가 baggage 필드를 구분한다.
- 다음 단계에서 canonical execution row로 줄일 때 삭제 가능한 필드를 리포트로
  확인할 수 있게 한다.

구현:

- `scripts/indd/execution_candidates.jsx`
  - `_buildExecutionCandidateContractDiagnostics` 추가.
  - required field, executor-read field, extra legacy field, missing required field를
    요약한다.
- `scripts/indd/extraction_plan_builder.jsx`
  - execution candidate 생성 후 contract diagnostics를 생성한다.
  - `executionCandidateContractSummary`를 plan에 포함한다.
- `scripts/indd/extraction_orchestrator.jsx`
  - full diagnostics 모드에서 `execution-candidate-contract.json`을 기록한다.
  - summary 모드에서는 `planner-diagnostics-summary.json`에 요약을 포함한다.
- `scripts/indd/extraction_result_writer.jsx`
  - slim plan에도 `executionCandidateContractSummary`를 유지한다.
- `scripts/indd/extraction_validation.jsx`
  - `executionCandidateContractSummary.rowsWithMissingRequiredCount > 0`이면
    extraction validation을 실패시킨다.

정책:

- 이 단계는 output을 바꾸지 않는다.
- executor contract 축소 전에 관측 가능한 guard를 먼저 추가한다.
- contract diagnostics는 visible owner를 생성하거나 candidate를 수정하지 않는다.
- required execution field 누락은 Stage 4 validation 실패로만 처리한다.

검증 계획:

- suite:
  - `python3 scripts/dev/regression_suite.py --force-run`
  - legacy filters 0 유지
- build:
  - `mvn -pl converter -am -DskipTests package`

## Batch 40: TextFrame style shell executable source guard

상태: **완료(2026-07-01)**.

목표:

- `TEXT_SLOT`으로 숨겨지는 editable TextFrame과, 같은 source의
  `SHELL_SLOT`/`styleSourceObjectIds`를 분리한다.
- TextFrame fill/stroke shell은 editable text와 같은 source id를 공유할 수 있지만,
  ownership slot은 다르므로 executable visual source 판정에서 제거하지 않는다.

변경:

- `scripts/indd/ownership_candidates.jsx`
  - `_candidateVisibleExportSourceIds(...)`가 `hiddenTextFrameIds`와
    `editableTextFrameIds`를 제외할 때, `styleSourceObjectIds`에 포함된 source는
    제외하지 않도록 했다.

정책:

- 이 변경은 후속 단계의 materialization fallback이나 drop이 아니다.
- Stage 1 후보의 source slot 판정에서 `TEXT_SLOT`과 `SHELL_SLOT`을 분리하는
  read-only guard 보강이다.
- 같은 TextFrame source라도 editable text는 HWPX가 소유하고, TextFrame paint
  shell은 style source channel로 실행될 수 있다.

검증:

- build:
  - `mvn -pl converter -am -DskipTests package`
- issue run:
  - `make issue CASE=park31-u1 PAGE=6 END_PAGE=30`
  - `park31-u1-p006-030.hwpx` 생성 및 open 성공

## Batch 41: canonical execution row slim copy

상태: **완료(2026-07-01)**.

목표:

- executor가 legacy candidate 전체 shape를 복사해 실행하지 않도록 1차 축소한다.
- 아직 renderer/planner bundle이 읽는 migration 필드는 명시 contract에 포함하되,
  contract 밖의 임의 필드는 execution row로 내려가지 않게 한다.

변경:

- `scripts/indd/execution_candidates.jsx`
  - `_executionCandidateContractFields()`를 추가해 execution row 허용 필드를 한 곳에서
    정의한다.
  - contract diagnostics와 `_copyExecutionCandidate(...)`가 같은 허용 필드 목록을
    사용하게 했다.
  - `id`, `suffix`, `requiresTextHidden`, `textOwner` 등 실제 renderer read field와
    `unit`, `bounds`, `zOrder`, `required`, `disabled` 등 migration observer field를
    명시 contract로 편입했다.
  - execution row는 허용 필드만 복사한다.

정책:

- 이 단계는 ObjectPlan/source-slot 결정 결과를 실행 row로 옮기는 bridge 축소다.
- visible owner 생성, drop, fallback, inline/floating 재판정은 하지 않는다.
- legacy candidate 전체 복사는 금지하고, 필요한 migration 필드는 contract에 이름을
    올린 뒤 제거한다.

검증:

- build:
  - `mvn -pl converter -am -DskipTests package`
- issue run:
  - `make issue CASE=park31-u1 PAGE=19 OPEN=0`
- regression:
  - `python3 scripts/dev/regression.py --extract output/issues/park31-u1/p019-20260701-225148/extract --out output/issues/park31-u1/p019-20260701-225148/audit/manual-regression.json`
  - result: `PASS`
- contract summary:
  - `extraFieldNameCount=0`
  - `rowsWithMissingRequiredCount=0`

## Legacy 제거 목록

다음 이름/패턴은 이동 대상이 아니라 제거 또는 Stage 1 registry 대체 대상이다.

- `fallback`이라는 이름으로 visible material을 만드는 경로
- `restore` 계열 visible owner 생성
- `promote` 계열 visible owner 변경
- `dedupe`가 ObjectPlan 대신 source ownership을 결정하는 경로
- child coverage만으로 parent/child를 drop하는 경로
- inline/floating을 executor에서 재판정하는 경로
- table/TF shell을 Java 또는 JSX fallback shape로 새로 그리는 경로

단, migration 동안 같은 함수가 diagnostic을 생성하는 것은 허용된다. Diagnostic은
visible output을 만들거나 ObjectPlan을 수정할 수 없다.

## 남은 작업

Batch 39 이후 남은 작업은 신규 정책 추가가 아니라 구조 분리와 guard 강화다.
이미 완료된 작업은 다음과 같다.

- `source_declared_shell_candidates.jsx` 분리 완료.
- `candidate_normalization.jsx` 분리 완료.
- normalization 내부 source metadata detector 일부는 `ownership_candidates.jsx` 공용
  read-only helper를 재사용한다.
- normalization용 source index/cache context는 `candidate_normalization_source_context.jsx`로
  분리 완료.
- normalization mutation block은 Batch 36 표로 목록화 완료.
- `object_plan_candidate_bridge.jsx`는 제거 완료.
- ObjectPlan diagnostics와 legacy execution candidate adapter는 분리 완료.
- source-slot canonical execution filter는 `source_slot_registry.jsx`로 통합 완료.
- execution candidate contract diagnostics 추가 완료.
- regression suite 기본 실행은 legacy normalization filter가 0보다 크면 실패한다.

남은 작업은 아래 순서로 진행한다.

1. `_normalizeExtractionCandidateOwnershipSlots` 내부 mutation block을
   ObjectPlan/source-slot registry로 옮길 수 있는 단위부터 이동한다.
   - 목표: normalization이 visible owner를 고치는 경로가 아니라 Stage 1 결정을
     검증/정리하는 얇은 bridge가 되게 한다.
2. executor가 candidate-shaped row를 읽는 구조를 canonical execution row로 줄인다.
   - 다음 목표는 Batch 39의 `execution-candidate-contract.json`을 기준으로
     `execution_candidates.jsx`의 출력 shape를 executor contract로 좁히는 것이다.
   - legacy candidate 전체 복사는 migration debt로 남긴다.
3. render/executor 모듈에서 visible output에 관여하는 fallback/restore/promote/dedupe
   경로를 점검하고, ObjectPlan 실행 또는 Stage 4 validation으로 이동한다.
4. legacy diagnostics는 유지하되, visible owner 생성이나 후보 재판정에 관여하지 않음을
   regression suite와 validation에서 계속 검증한다.

## 첫 번째 코드 이동 추천

가장 안전한 첫 이동은 Batch 1이다.

이유:

- ownership 정책을 바꾸지 않는다.
- trace/output 형식만 유지하면 회귀 위험이 낮다.
- 이후 모든 모듈에서 공통 utility를 재사용할 수 있다.

Batch 1 이후에는 `extract_indd.jsx`가 약 200-300 LOC 줄어드는 것이 목표다.
Batch 4/6 이전까지는 변환 결과가 byte-for-byte 동일하거나 trace ordering만 달라야 한다.

## 검증 체크리스트

각 batch마다 아래를 수행한다.

```bash
mvn -pl converter -am -DskipTests package
make issue CASE=park31-u1 PAGE=19 OPEN=0
python3 scripts/dev/regression.py --extract <issue-output>/extract --out <issue-output>/audit/manual-regression.json
```

Batch 4 이후에는 최소 아래 페이지도 확인한다.

```bash
make issue CASE=park31-u1 PAGE=15 OPEN=0
make issue CASE=park31-u1 PAGE=22 OPEN=0
make issue CASE=park31-u1 PAGE=31 OPEN=0
```

Batch 6 이후에는 image/shell-heavy page를 추가한다.

```bash
make issue CASE=park31-u1 PAGE=14 OPEN=0
make issue CASE=park31-u1 PAGE=37 OPEN=0
```

## Done 기준

- `extract_indd.jsx`는 module loader, JSON bootstrap, runtime globals, entry trigger만
  갖는다.
- 실제 extraction orchestration은 `scripts/indd/extraction_orchestrator.jsx`가
  담당한다.
- source index/cluster/planner/render/validation 책임이 한 파일에 섞여 있지 않다.
- 새 모듈마다 책임이 하나이고, visible ownership을 재판정하지 않는다.
- legacy helper가 새 모듈과 동시에 존재하지 않는다.
- `make issue`와 regression audit가 각 batch에서 통과한다.
