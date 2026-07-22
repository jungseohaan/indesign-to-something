# SPEC-048 재개 노트 (다음 세션용)

> **2026-07-22 완료** — 구현·산출물 검증·한글 육안 확인 통과.
> 결과는 [SPEC-048](SPEC-048-floating-anchored-gauge-inline-misplacement.md)
> "구현 결과" 절 참조. 이 노트는 이력 보존용.

> 본 SPEC 요지는 [SPEC-048](SPEC-048-floating-anchored-gauge-inline-misplacement.md).
> 이 파일은 **작업 재개용 실무 정보**(정확한 파일·줄, 재현·재추출 절차, 시도 이력).
> 작성 2026-07-21. 현재 open-indd 머지 시점: `acfb6e37`.

## 지금까지 상태 (한 줄)

p19 게이지("100%"+눈금+연필)가 인라인 오분류되어 "100%"가 문장 앞으로 새고
게이지가 수평 정렬 안 됨 — **원인 규명 완료, 코드 수정은 전부 되돌린 상태**
(open-indd 에 SPEC 문서만 머지됨). 다음 세션에서 실제 수정 착수.

## 재현 방법

1. 원본: **과학 u1** INDD (데스크탑 앱으로 추출). 게이지는 **p19**
   "나의 활동 확인하기" 의 "과정·기능"/"가치·태도" 문장 오른쪽 끝.
2. 데스크탑 앱에서 u1 추출 → 최신 `indd-extract-*/` 에 `output.idml` +
   `resolved.json` 생성. 추출기 코드 바꿨으면 **재추출 필수**
   (`--no-reuse-idml`, 매회 ~3-4분).
3. CLI 변환:
   ```
   /opt/homebrew/opt/openjdk/bin/java -jar target/idml-to-something-1.0.9-cli.jar \
        --convert <extract>/output.idml out.hwpx --links-directory <원본옆>/Links
   ```
4. 증상 확인: 한글에서 p19 열어 "100%" 가 문장 앞에 붙는지 / 게이지 그래픽
   유무 / 문장과 수평 정렬 여부.

## 대상 객체 식별값 (실측)

- 게이지 Group id: **75901** (InDesign DOM decimal). IDML 은 `u` + hex 변환:
  `parseInt(hex,16)`.
- 게이지 sourceInfo: `storyAnchorPlacement = FLOATING_ANCHORED`,
  `storyTextInlineSlot = true`.
- **게이지 vs 라벨 구분**: 게이지 자식 `GraphicLine` **27개**, 라벨은 **0개**.
  → 판정 헬퍼 `_isGaugeLikeVectorGraphic(item)`: `item.allPageItems` 중
  GraphicLine ≥ 8 이면 게이지.

## 코드 지점 (파일:줄 — 조사 시점 기준, 재확인 필요)

전부 `scripts/indd/` 하위. `extract_indd.jsx` 가 eval 로드 → 전역 함수 공유.

| 파일 | 함수 / 줄 | 역할 |
|------|-----------|------|
| `source_index.jsx` | `_isInlineFlowItemBySourceInfo` (~329) | `storyTextInlineSlot===true` 를 FLOATING_ANCHORED 배제보다 **먼저** 판정 → 게이지 인라인 오분류의 1차 관문 |
| `ownership_candidates.jsx` | `_appendSourceDeclaredInlineShellCandidates` (~369, push ~421) | 게이지를 **2소스 candidate(textOwner=indesign_png, 통짜PNG 가능)** 로 seed |
| `ownership_candidates.jsx` | `_isSimpleMarkerLabelTextForOwnership` (~35) | "100%" 는 여기서 simple marker 아님(1-2자리/자모/원문자만) → 통짜PNG 판정 실패 원인 중 하나 |
| `ownership_candidates.jsx` | `_inlineCompositeCompletePngDecisionForOwnership` (~172) | 편집 자식 전부 simple marker 여야 통짜PNG 허용 |
| `extraction_plan_builder.jsx` | `_appendInlineObjectExtractionCandidates` (~879, 핵심 ~956) | **같은 게이지를 34소스 candidate(textOwner=hwpx_tf, 텍스트분리)로도 seed. 이게 최종을 이김.** ~956 `inlineRequiresTextHidden = 편집TF자식 있음` → attrs(~966-983) `textOwner = inlineRequiresTextHidden ? "hwpx_tf" : "none"` |
| `extraction_plan_builder.jsx` | `_appendUnclaimedVisibleVectorOwnershipCandidates` (~5722, floating분기 ~6136) | floating backfill 경로. **이 게이지는 여기서 처리 안 됨**(log 비어있었음) — `_isInlineFlowItemBySourceInfo` 순서 바꿔 inline 제외하면 여기서도 안 잡혀 게이지 누락됨 |
| `planner_bundles.jsx` | bundle 반환객체 (~355-380) | `completePngTextAllowed`/`textOwner`/`containsEditableText` **누락** → seed 의 통짜PNG 신호가 bundle 로 전파 안 됨 |
| `object_plans.jsx` | `_objectPlanBundleIsInlineEditableTextShellComposite` (~5651), `_inlineEditableTextShellCompositeBundle` (~5660), `_objectPlanBundleOwnsInlineCompletePngText` | bundle → inline_editable_text_shell_composite 정규화. 통짜PNG 소유 판정 |

## 핵심 진단 결론

게이지가 **여러 seed 경로로 중복 생성**된다:
- 2소스 통짜PNG candidate (원하는 결과: 그래픽+"100%" 함께 PNG, floating 배치)
- 34소스 텍스트분리 candidate (**실제 최종**: "100%" 를 hwpx 텍스트로 떼서
  문장 flow 로 흘림)

한 지점만 고치면 다른 seed 가 최종을 좌우 → **여러 seed 를 게이지에 대해
단일 통짜PNG+floating 경로로 통일**해야 함.

## 시도 이력 (전부 되돌림, 반복 금지)

1. `_isInlineFlowItemBySourceInfo` 순서 변경(FLOATING_ANCHORED 먼저 배제)
   → 게이지 inline 제외되나 **floating 경로가 재-claim 안 해 게이지 통째 누락**.
   사용자 확인: "밑줄 모양 하나만 보이고 게이지 안보임, 100% 사라짐".
2. `_inlineCompositeCompletePngDecisionForOwnership` 게이지 조건 추가 →
   seed 는 completeOwns=true 내나 **34소스 candidate 가 이겨 무효**.
3. `planner_bundles` 에 `completePngTextAllowed`/`textOwner` 전파 추가 →
   컴포지트 오분류는 막음(textOwner hwpx_tf→none) 하나 **통짜PNG 는 아님**.
   → 이 변경은 유효, 최종 수정에 유지 후보.
4. `_appendInlineObjectExtractionCandidates` 34소스 seed 에 게이지 통짜PNG
   가드 추가 → **여전히 textOwner=none** (판정 미발동 또는 후단 정규화가 덮음,
   미확정).

## 다음 세션 권장 착수 순서

1. **어느 seed 가 최종 candidate 를 만드는지 먼저 계측 확정**. candidate 마다
   `textOwner`/`passId`/소스개수 를 로그로 찍고 게이지(75901) 필터.
   → "34소스 hwpx_tf 가 최종" 가정을 재확인(코드가 그새 바뀌었을 수 있음:
   open-indd 에 PR #58~#60 로 inline/table/backdrop ownership 변경 머지됨).
2. 최종 seed 경로 한 곳에서 게이지(`_isGaugeLikeVectorGraphic`)를 **통짜PNG +
   FLOATING 배치**로 강제하고, 나머지 중복 seed 는 억제(dedup 우선순위).
3. `planner_bundles` 필드 전파(#3)는 유지.
4. 재추출 → CLI 변환 → p19 육안: 게이지 보임 + "100%" 문장앞 유출 0 + 수평 정렬.

## 함정 (CLAUDE.md 에도 유사 기록)

- 추출기 파이프라인 다단계: seed → cluster → bundle → normalize → placement.
  한 지점 수정이 최종을 안 바꿀 수 있음.
- `$.stack` 은 인자값 통째로 담아 노이즈 큼 → 함수명만 필요하면
  `.replace(/\([^)]*\)/g,"()")`.
- ExtendScript 트레이스: `File("~/gauge_trace.log").open("e"); seek(0,2); writeln(...)`.
  **작업 끝나면 로그 파일·트레이스 코드 반드시 제거** (지난 세션처럼).
- candidatePreview 는 100개로 잘림 → extraction-plan.json 요약에 게이지가
  안 보일 수 있음. 직접 grep 으로 75901 추적.
- PR #58~#60(inline/table shell, backdrop absorb) 머지로 ownership 경로가
  최근 바뀜 → **재개 시 위 줄번호 먼저 재확인**.
